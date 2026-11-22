package com.tengwear.tengfilemanager.core.lan

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tengwear.tengfilemanager.R

/**
 * 传输前台服务。
 *
 * 为什么必须有它：Wear OS 在息屏后会冻结后台网络，一个 40MB 的传输会在 20 秒后停住。
 * 前台服务 + dataSync 类型是系统允许的唯一正规做法（不要用 WakeLock 硬顶）。
 */
class TransferService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 通知渠道是 API 26+ 概念；API 25（Wear OS 2.0）走 NotificationCompat 的
        // priority 兜底，不需要（也不能）创建渠道
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "局域网传输", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "文件传输进度"
                    setShowBadge(false)
                }
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "正在传输"
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.splash_icon)
            .setContentTitle("局域网传输")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL_ID = "teng_lan_transfer"
        private const val NOTIFICATION_ID = 4101
        private const val EXTRA_TEXT = "text"

        fun start(context: Context, text: String) {
            val intent = Intent(context, TransferService::class.java).putExtra(EXTRA_TEXT, text)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TransferService::class.java)) }
        }
    }
}
