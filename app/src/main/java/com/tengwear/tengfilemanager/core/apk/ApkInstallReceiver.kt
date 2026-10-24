package com.tengwear.tengfilemanager.core.apk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 接收 PackageInstaller 的提交结果。
 * 只做状态回传，不做任何自动重试——安装失败的原因必须原样展示给用户。
 */
class ApkInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
        val result = when (status) {
            PackageInstaller.STATUS_SUCCESS -> InstallOutcome(true, "安装完成", packageName)
            PackageInstaller.STATUS_PENDING_USER_ACTION -> InstallOutcome(false, "等待用户确认", packageName)
            PackageInstaller.STATUS_FAILURE_ABORTED -> InstallOutcome(false, "安装已取消", packageName)
            PackageInstaller.STATUS_FAILURE_BLOCKED -> InstallOutcome(false, "安装被系统策略阻止", packageName)
            PackageInstaller.STATUS_FAILURE_CONFLICT -> InstallOutcome(false, "与已安装版本冲突（签名或版本不一致）", packageName)
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> InstallOutcome(false, "与当前系统不兼容", packageName)
            PackageInstaller.STATUS_FAILURE_INVALID -> InstallOutcome(false, "安装包无效或已损坏", packageName)
            PackageInstaller.STATUS_FAILURE_STORAGE -> InstallOutcome(false, "存储空间不足", packageName)
            else -> InstallOutcome(false, message?.take(60) ?: "安装失败", packageName)
        }
        _lastOutcome.value = result
    }

    data class InstallOutcome(val success: Boolean, val message: String, val packageName: String?)

    companion object {
        const val ACTION_INSTALL_STATUS = "com.tengwear.tengfilemanager.INSTALL_STATUS"

        private val _lastOutcome = MutableStateFlow<InstallOutcome?>(null)
        val lastOutcome: StateFlow<InstallOutcome?> = _lastOutcome.asStateFlow()

        fun consume() {
            _lastOutcome.value = null
        }
    }
}
