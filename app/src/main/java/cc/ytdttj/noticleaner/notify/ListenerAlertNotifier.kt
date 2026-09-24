package cc.ytdttj.noticleaner.notify

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import cc.ytdttj.noticleaner.R

/**
 * 监听失效提醒通知（1.4.0 Dev 15）。
 *
 * 监听断线（进程被杀/绑定卡死/权限被摘除）时发一条**悬浮 + 锁屏可见**的提醒，
 * 让用户在没打开 APP 的情况下也能立刻知道"净化停了"，并可直接点「立即修复」。
 *
 * 形态要点：
 * - 渠道 IMPORTANCE_HIGH → 系统以**悬浮通知（heads-up）**弹出；
 * - [Notification.VISIBILITY_PUBLIC] → 锁屏界面完整显示内容（不是"隐藏内容"）；
 * - 两个操作：立即修复（Shizuku/Root 直接执行，普通用户跳权限页）/ 权限设置；
 * - 30 分钟冷却，避免看门狗 30s 一次反复弹同一条。
 */
object ListenerAlertNotifier {

    private const val CHANNEL_ID = "listener_alert"
    private const val NOTIF_ID = 8001
    private const val COOLDOWN_MS = 30 * 60 * 1000L
    private const val PREFS = "listener_alert"
    private const val KEY_LAST = "last_notified"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                android.app.NotificationChannel(
                    CHANNEL_ID,
                    "通知监听状态提醒",
                    android.app.NotificationManager.IMPORTANCE_HIGH, // 悬浮通知
                ).apply {
                    description = "通知监听失效时提醒（悬浮弹出 + 锁屏显示）"
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    enableVibration(true)
                },
            )
        }
    }

    /** 监听失效提醒；冷却期内不重复弹 */
    fun notifyDown(context: Context, reason: String) {
        val app = context.applicationContext
        ensureChannel(app)
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST, 0L) < COOLDOWN_MS) return
        prefs.edit().putLong(KEY_LAST, now).apply()

        val openApp = PendingIntent.getActivity(
            app, 0,
            Intent(app, cc.ytdttj.noticleaner.ui.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val repair = PendingIntent.getBroadcast(
            app, 1,
            Intent(app, ListenerRepairActionReceiver::class.java)
                .setAction(ListenerRepairActionReceiver.ACTION_REPAIR),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val settings = PendingIntent.getBroadcast(
            app, 2,
            Intent(app, ListenerRepairActionReceiver::class.java)
                .setAction(ListenerRepairActionReceiver.ACTION_OPEN_SETTINGS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notif = Notification.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle("通知监听已失效")
            .setContentText("广告净化暂时停止（$reason），点「立即修复」恢复")
            .setStyle(Notification.BigTextStyle().bigText(
                "通知监听已失效（$reason）。\n广告净化暂时停止，恢复前新通知不会被过滤。\n" +
                    "• 有 Shizuku/Root：点「立即修复」自动摘除写回强制重绑\n" +
                    "• 普通用户：点「权限设置」，取消勾选本应用后重新勾选",
            ))
            // 锁屏完整显示
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setOngoing(false)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(app, R.drawable.ic_stat_alert),
                    "立即修复", repair,
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(null, "权限设置", settings).build(),
            )
            .build()

        runCatching {
            app.getSystemService(android.app.NotificationManager::class.java).notify(NOTIF_ID, notif)
        }
    }

    /** 监听恢复后撤下提醒 */
    fun cancel(context: Context) {
        runCatching {
            context.applicationContext.getSystemService(android.app.NotificationManager::class.java)
                .cancel(NOTIF_ID)
        }
    }

    /** 清冷却（用户手动点修复后允许再次提醒） */
    fun resetCooldown(context: Context) {
        runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST, 0L).apply()
        }
    }
}
