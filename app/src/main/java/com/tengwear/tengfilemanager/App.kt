package com.tengwear.tengfilemanager

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.tengwear.tengfilemanager.core.prefs.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 全局入口。
 *
 * 这里只做三件事：
 *  1. 注册 Coil 的 VideoFrameDecoder —— 视频首帧缩略图不自己解码，交给现成库；
 *  2. 预热 DataStore（deviceId），局域网广播要用；
 *  3. 启动时清理过期回收站（30 天），不做任何定时任务，省电。
 */
class App : Application(), ImageLoaderFactory {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val prefs = UserPreferences(this)
        appScope.launch {
            prefs.ensureDeviceId()
            runCatching {
                val fs = com.tengwear.tengfilemanager.core.fs.StorageGateway(this@App)
                com.tengwear.tengfilemanager.core.fs.TrashRepository(this@App, fs).purgeExpired()
            }
        }
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components { add(VideoFrameDecoder.Factory()) }
            // 手表内存小，缩略图缓存压在 12MB 以内
            .memoryCache(coil.memory.MemoryCache.Builder(this).maxSizePercent(0.12).build())
            .crossfade(true)
            .build()
}
