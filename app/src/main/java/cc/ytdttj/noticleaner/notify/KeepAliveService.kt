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
import cc.ytdttj.noticleaner.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * T0 保活前台服务（Plan.md §7.1）：START_STICKY 常驻，低优先级通知显示累计拦截统计。
 * 1.1.5：内容为「已拦截 AI X 条 · 规则 Y 条」，随拦截实时刷新（DataStore 持久计数）。
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

    private var scope: CoroutineScope? = null

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

        // 初始进入前台（必须先 startForeground，再由统计流刷新内容）
        runCatching { startForeground(NOTIF_ID, buildNotification(0, 0)) }

        // 累计拦截统计：DataStore 计数变化（AI / 规则）→ 实时刷新常驻通知
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { s ->
            s.launch {
                combine(
                    ServiceLocator.settings.filteredAiCount,
                    ServiceLocator.settings.filteredRuleCount,
                ) { a, r -> a to r }
                    .collect { (a, r) ->
                        runCatching {
                            getSystemService(NotificationManager::class.java)
                                .notify(NOTIF_ID, buildNotification(a, r))
                        }
                    }
            }
            // 看门狗（1.1.8）：无 Root/Shizuku 时监听绑定可能被系统悄悄回收；
            // 每分钟检查一次：权限仍在但监听未连接 → 请求系统重绑
            s.launch {
                while (true) {
                    kotlinx.coroutines.delay(60_000)
                    runCatching {
                        if (CleanerListenerService.isListenerEnabled(this@KeepAliveService) &&
                            !CleanerListenerService.isListenerConnected()
                        ) {
                            CleanerListenerService.requestRebindIfEnabled(this@KeepAliveService)
                        }
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    private fun buildNotification(aiCount: Int, ruleCount: Int): Notification {
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
        val text = "已拦截 AI $aiCount 条 · 规则 $ruleCount 条"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_delete)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_delete)
                .setContentIntent(contentIntent)
                .setPriority(Notification.PRIORITY_MIN)
                .setOngoing(true)
                .build()
        }
    }
}
