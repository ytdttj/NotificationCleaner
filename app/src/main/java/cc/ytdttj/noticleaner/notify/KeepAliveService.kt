package cc.ytdttj.noticleaner.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import cc.ytdttj.noticleaner.R
import cc.ytdttj.noticleaner.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/**
 * T0 保活前台服务（Plan.md §7.1）：START_STICKY 常驻，低优先级通知显示累计拦截统计。
 * 1.1.5：内容为「已拦截 AI X 条 · 规则 Y 条」，随拦截实时刷新（DataStore 持久计数）。
 * 1.1.13：闹钟看门狗（Doze 免疫）+ 亮屏/解锁立即自愈；保活通知 Intent 按开关携带
 * FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS（否则经通知拉起的新任务不会隐藏后台卡片）。
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

    /** 亮屏/解锁自愈（1.1.13）：Doze 期间积压的重绑需求在亮屏瞬间补做 */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            android.util.Log.i("NCWatch", "screen event ${intent.action}")
            runCatching {
                if (CleanerListenerService.isListenerEnabled(context) &&
                    !CleanerListenerService.isListenerConnected()
                ) {
                    CleanerListenerService.requestRebindIfEnabled(context)
                    android.util.Log.i("NCWatch", "screen event → rebind requested")
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        android.util.Log.i("NCWatch", "keepalive FGS onCreate uptime=${android.os.SystemClock.elapsedRealtime()}")
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

        // 1.1.13：Doze 免疫的闹钟看门狗 + 亮屏/解锁自愈
        WatchdogReceiver.schedule(this)
        runCatching {
            registerReceiver(
                screenReceiver,
                IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_USER_PRESENT) },
            )
        }

        // 初始进入前台（必须先 startForeground，再由统计流刷新内容）
        runCatching { startForeground(NOTIF_ID, buildNotification(0, 0, false)) }

        // 累计拦截统计 + 多任务隐藏开关：任一变化 → 重建常驻通知（Intent 携带正确的隐藏 flag）
        // 1.2.0（ImprovePlan P2-7）：conflate + 1s 节流——拦截风暴时最多每秒刷新一次常驻通知，
        // 减少对其它监听 APP（弹幕类）的噪声 onNotificationPosted 广播
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { s ->
            s.launch {
                combine(
                    ServiceLocator.settings.filteredAiCount,
                    ServiceLocator.settings.filteredRuleCount,
                    ServiceLocator.settings.excludeFromRecents,
                ) { a, r, e -> Triple(a, r, e) }
                    .conflate()
                    .collect { (a, r, e) ->
                        runCatching {
                            getSystemService(NotificationManager::class.java)
                                .notify(NOTIF_ID, buildNotification(a, r, e))
                        }
                        kotlinx.coroutines.delay(1_000)
                    }
            }
            // 协程看门狗（1.1.11：间隔 60s→30s）：亮屏期间的快速自愈路径；
            // Doze 下会被挂起，由 WatchdogReceiver 闹钟兜底
            s.launch {
                while (true) {
                    kotlinx.coroutines.delay(30_000)
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
        runCatching { unregisterReceiver(screenReceiver) }
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    private fun buildNotification(aiCount: Int, ruleCount: Int, excludeFromRecents: Boolean): Notification {
        val launchIntent =
            android.content.Intent(this, cc.ytdttj.noticleaner.ui.MainActivity::class.java)
                // CLEAR_TOP 复用已存在的任务栈，避免返回时叠一层主界面
                .addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP,
                )
        // 1.1.13：开关开启时 Intent 携带隐藏 flag——任务被系统重建后仍保持隐藏
        if (excludeFromRecents) {
            launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }
        val contentIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            launchIntent,
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
