package com.tengwear.tengfilemanager.core.lan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress

/**
 * 设备发现（NSD / mDNS）。
 *
 * 只用系统 NsdManager，不引第三方 mDNS 库：
 *  · 手表上多一个常驻组播监听就是多一份待机功耗；
 *  · 发现只在"设备"页面打开时启用，离开即停（见 [stopAll]）。
 *
 * 隐私：服务名只暴露用户在设置里填的设备名（默认"TENG 手表"），
 * TXT 记录只带一个随机 deviceId 与协议版本，不带型号、不带文件列表。
 */
class LanDiscovery(private val context: Context) {

    data class Peer(
        val id: String,
        val name: String,
        val host: InetAddress,
        val port: Int,
    )

    private val nsd: NsdManager? = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifi: WifiManager? = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private val _status = MutableStateFlow("未开启")
    val status: StateFlow<String> = _status.asStateFlow()

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var advertisedPort = 0
    private var advertisedId = ""
    private var advertisedName = ""

    val isWifiConnected: Boolean
        get() = wifi?.isWifiEnabled == true

    // ------------------------------------------------------------ 广播自己

    fun startAdvertising(deviceId: String, deviceName: String, port: Int) {
        val manager = nsd ?: run { _status.value = "本机不支持设备发现"; return }
        if (registrationListener != null && advertisedPort == port) return
        stopAdvertising()

        advertisedId = deviceId
        advertisedName = deviceName
        advertisedPort = port

        val info = NsdServiceInfo().apply {
            serviceName = deviceName.take(40)
            serviceType = LanProtocol.SERVICE_TYPE
            setPort(port)
            setAttribute("id", deviceId)
            setAttribute("v", LanProtocol.PROTO_VERSION.toString())
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                advertisedName = info.serviceName
                _status.value = "已广播：${info.serviceName}"
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                _status.value = "广播失败（$errorCode），请确认已连接 Wi-Fi"
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        registrationListener = listener
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { _status.value = "广播失败：${it.message}" }
    }

    fun stopAdvertising() {
        registrationListener?.let { runCatching { nsd?.unregisterService(it) } }
        registrationListener = null
    }

    // ------------------------------------------------------------ 发现别人

    fun startDiscovery() {
        val manager = nsd ?: return
        if (discoveryListener != null) return
        acquireMulticastLock()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                _status.value = "正在搜索附近设备…"
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType != LanProtocol.SERVICE_TYPE) return
                // 广播出来的自己不要显示在列表里
                if (info.serviceName == advertisedName && advertisedPort > 0) return
                resolve(manager, info)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                _peers.value = _peers.value.filterNot { it.name == info.serviceName }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                _status.value = "已停止搜索"
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                _status.value = "搜索失败（$errorCode）"
                runCatching { manager.stopServiceDiscovery(this) }
                discoveryListener = null
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                runCatching { manager.stopServiceDiscovery(this) }
                discoveryListener = null
            }
        }
        discoveryListener = listener
        runCatching { manager.discoverServices(LanProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure {
                _status.value = "搜索失败：${it.message}"
                discoveryListener = null
            }
    }

    @Suppress("DEPRECATION")
    private fun resolve(manager: NsdManager, info: NsdServiceInfo) {
        manager.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "解析失败 ${serviceInfo.serviceName} code=$errorCode")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host ?: return
                if (host.isLoopbackAddress) return
                val id = serviceInfo.attributes?.get("id")?.toString(Charsets.UTF_8)
                    ?: serviceInfo.serviceName
                val peer = Peer(
                    id = id,
                    name = serviceInfo.serviceName,
                    host = host,
                    port = serviceInfo.port,
                )
                _peers.value = (_peers.value.filterNot { it.id == peer.id } + peer)
                    .sortedBy { it.name }
            }
        })
    }

    fun stopDiscovery() {
        discoveryListener?.let { runCatching { nsd?.stopServiceDiscovery(it) } }
        discoveryListener = null
        releaseMulticastLock()
    }

    fun stopAll() {
        stopDiscovery()
        stopAdvertising()
        _peers.value = emptyList()
        _status.value = "未开启"
    }

    // 组播锁：部分 Wear 设备在息屏后会丢弃 mDNS 组播包
    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        multicastLock = wifi?.createMulticastLock("teng-lan-discovery")?.apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { if (it.isHeld) runCatching { it.release() } }
        multicastLock = null
    }

    companion object {
        private const val TAG = "LanDiscovery"

        /** 设备名默认值：不含型号信息，避免在局域网里泄露设备指纹。 */
        fun defaultDeviceName(): String =
            if (Build.MODEL.isNullOrBlank()) "TENG 手表" else "TENG 手表"
    }
}
