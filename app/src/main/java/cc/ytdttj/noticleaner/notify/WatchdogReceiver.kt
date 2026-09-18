package cc.ytdttj.noticleaner.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.launch

/**
 * 闹钟看门狗（1.1.13）：协程看门狗在 Doze 下会被挂起（长锁屏期间监听断线无人重绑，
 * 通知积压到亮屏后才批量过滤），改用 setAndAllowWhileIdle 的闹钟在 Doze 中仍可触发
 * （系统节流约 9 分钟一次，对重绑场景足够）。自续约：每次触发后重新调度下一次。
 *
 * 1.1.14：触发时除请求重绑外，还尝试重启保活前台服务（恢复进程重要性、
 * 抖掉可能卡死的绑定）；全部动作写 NCWatch 日志供远程诊断。
 */
class WatchdogReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NCWatch"
        private const val REQUEST_CODE = 2001

        /** 未获电池豁免：Doze 下 allow-while-idle 系统节流约 9 分钟，再短无意义 */
        private const val INTERVAL_DEFAULT_MS = 9 * 60 * 1000L

        /**
         * 已获电池豁免：allow-while-idle 最小窗口仅 10s（ALLOW_WHILE_IDLE_WHITELIST_MIN_TIME），
         * 闹钟不受 Doze 节流 → 断连检测窗口压到 30s（1.2.1，普通用户最大杠杆）
         */
        private const val INTERVAL_EXEMPT_MS = 30 * 1000L

        /** 摘除写回强制修复的最小间隔（1.2.1） */
        private const val REPAIR_THROTTLE_MS = 30 * 60 * 1000L

        /** 连续断连触发计数（跨 onReceive 保留，进程死亡归零——死亡自愈靠 NMS 自动重绑） */
        private var consecutiveDisconnected = 0
        private var lastRepairAt = 0L

        /** 启动/续约闹钟；间隔按电池豁免状态自适应，exact 被拒时自动退化为非精确 */
        fun schedule(context: Context) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            val exempt = context.getSystemService(android.os.PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(context.packageName) == true
            val interval = if (exempt) INTERVAL_EXEMPT_MS else INTERVAL_DEFAULT_MS
            val pi = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                Intent(context, WatchdogReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val triggerAt = SystemClock.elapsedRealtime() + interval
            val exact = runCatching {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }.isSuccess
            if (!exact) {
                runCatching {
                    am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                }
            }
            Log.i(TAG, "alarm scheduled exact=$exact exempt=$exempt interval=${interval / 1000}s")
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val enabled = CleanerListenerService.isListenerEnabled(context)
        val connected = CleanerListenerService.isListenerConnected()
        Log.i(TAG, "fired enabled=$enabled connected=$connected uptime=${SystemClock.elapsedRealtime()}")

        if (enabled && !connected) {
            CleanerListenerService.requestRebindIfEnabled(context)
            Log.i(TAG, "rebind requested")
            // 1.1.14：尝试重启保活前台服务——恢复进程重要性并抖掉可能卡死的绑定
            // （受 FGS 后台启动限制时抛异常，忽略：重绑请求已发出）
            runCatching { KeepAliveService.start(context) }
                .onFailure { Log.w(TAG, "fgs restart rejected: $it") }
            // 1.2.1：连续 2 次触发仍断连 → Shizuku 可用时做"摘除写回"强制重绑（30 分钟节流；
            // 仅 Shizuku——Root 后台自动执行会弹 su 授权打扰用户，Root 修复走设置页手动按钮）
            consecutiveDisconnected++
            val now = SystemClock.elapsedRealtime()
            if (consecutiveDisconnected >= 2 && now - lastRepairAt > REPAIR_THROTTLE_MS) {
                val shizukuUsable = runCatching {
                    rikka.shizuku.Shizuku.pingBinder() &&
                        rikka.shizuku.Shizuku.checkSelfPermission() ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                }.getOrDefault(false)
                if (shizukuUsable) {
                    lastRepairAt = now
                    consecutiveDisconnected = 0
                    Log.i(TAG, "listener still disconnected → shizuku listener repair")
                    val result = goAsync()
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        try {
                            val log = ListenerRepair.repair(ShizukuExecutor)
                            Log.i(TAG, "listener repair done:\n$log")
                        } catch (t: Throwable) {
                            Log.w(TAG, "listener repair failed: $t")
                        } finally {
                            result.finish()
                        }
                    }
                }
            }
        } else {
            consecutiveDisconnected = 0
        }

        // 1.2.0（ImprovePlan P0-2）：顺带清理过期通知——闹钟 9 分钟天然节流 + Doze 免疫，
        // 修复长驻进程下 purgeExpired 只在服务 onCreate 执行一次导致的 DB 无限膨胀
        val result = goAsync()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                runCatching {
                    cc.ytdttj.noticleaner.ServiceLocator.db.notificationDao()
                        .purgeExpired(System.currentTimeMillis())
                }.onFailure { Log.w(TAG, "purgeExpired failed: $it") }
            } finally {
                result.finish()
            }
        }

        // 自续约（KeepAliveService 存活期间由它启动；服务被杀后本接收器仍可维持链条）
        schedule(context)
    }
}
