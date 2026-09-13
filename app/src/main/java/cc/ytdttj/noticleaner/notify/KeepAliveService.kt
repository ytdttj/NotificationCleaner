package cc.ytdttj.noticleaner.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import cc.ytdttj.noticleaner.R
import kotlinx.coroutines.flow.first

/**
 * T0 保活前台服务（Plan.md §7.1）：START_STICKY 常驻，低优先级通知仅显示统计占位。
 * specialUse 类型（API 34+ 声明 PROPERTY_SPECIAL_USE_FGS_SUBTYPE）。
 */
class KeepAliveService : Service() {

    companion object {
        private const val CHANNEL_ID = "keepalive"
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, KeepAliveService::class.java))
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.keepalive_channel),
                    NotificationManager.IMPORTANCE_MIN,
                ),
            )
        }
        val contentIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            android.content.Intent(this, cc.ytdttj.noticleaner.ui.MainActivity::class.java)
                // CLEAR_TOP 复用已存在的任务栈，避免返回时叠一层主界面
                .addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP,
                ),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText("通知过滤运行中，点击打开")
                    .setSmallIcon(android.R.drawable.ic_delete)
                    .setContentIntent(contentIntent)
                    .setOngoing(true)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText("通知过滤运行中，点击打开")
                    .setSmallIcon(android.R.drawable.ic_delete)
                    .setContentIntent(contentIntent)
                    .setPriority(Notification.PRIORITY_MIN)
                    .setOngoing(true)
                    .build()
            }
        runCatching { startForeground(NOTIF_ID, notification) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
}
