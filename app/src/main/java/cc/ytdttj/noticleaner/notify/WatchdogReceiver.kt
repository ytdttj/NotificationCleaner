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
        // 1.4.0 Dev 12：环形留痕（心跳 30s/次，相邻相同自动折叠为 ×N 摘要，不刷爆配额）
        cc.ytdttj.noticleaner.diagnostics.RingLog.log("看门狗心跳 enabled=$enabled connected=$connected")

        // ── 2.0.1 Dev 2 重构（修复崩溃死循环）────────────────────────────────────
        // 旧实现：Shizuku 修复分支和 purgeExpired 各调一次 goAsync —— BroadcastReceiver
        // 的 goAsync 只能调一次，第二次返回的 PendingResult 为 null → finish() NPE →
        // 进程 FATAL。实测（M332BF / Android 17）：断连 + Shizuku 可用时每 30s 崩一次，
        // 修复协程每次都被崩溃杀掉 → 监听永远修不好 → 无限崩溃-重启循环。
        // 新实现：
        //   1. 自续约 schedule() 前置——后面任何异常都不断闹钟链
        //   2. goAsync 全程只调一次，修复与清理合并进同一个协程
        //   3. 整个 onReceive 兜底 runCatching，绝不向上抛
        schedule(context)

        val pending: android.content.BroadcastReceiver.PendingResult? = try {
            goAsync()
        } catch (t: Throwable) {
            Log.w(TAG, "goAsync failed: $t")
            null
        }

        fun finishSafely() {
            runCatching { pending?.finish() }
        }

        if (enabled && !connected) {
            CleanerListenerService.requestRebindIfEnabled(context)
            Log.i(TAG, "rebind requested")
            // Dev 15：看门狗发现"权限在、连接不在"→ 发失效提醒（内部 30 分钟冷却，不会刷屏）
            // 2.0.1 Dev 3：连续 ≥10 次（约 5 分钟）仍断连 → 升级提醒为"建议重启手机"
            // （系统在监听服务反复崩溃后会放弃重绑，只有重启能复位——M332BF 实测）
            runCatching {
                ListenerAlertNotifier.notifyDown(
                    context,
                    "监听未连接，看门狗已尝试重绑",
                    escalate = consecutiveDisconnected + 1 >= 10,
                )
            }
            cc.ytdttj.noticleaner.diagnostics.RingLog.log(
                "✗ 看门狗：监听断连 → 请求重绑（连续第 ${consecutiveDisconnected + 1} 次）",
            )
            // 1.1.14：尝试重启保活前台服务——恢复进程重要性并抖掉可能卡死的绑定
            // （受 FGS 后台启动限制时抛异常，忽略：重绑请求已发出）
            runCatching { KeepAliveService.start(context) }
                .onFailure { Log.w(TAG, "fgs restart rejected: $it") }
            consecutiveDisconnected++
            val now = SystemClock.elapsedRealtime()
            val needRepair = consecutiveDisconnected >= 2 &&
                now - lastRepairAt > REPAIR_THROTTLE_MS
            val shizukuUsable = needRepair && runCatching {
                rikka.shizuku.Shizuku.pingBinder() &&
                    rikka.shizuku.Shizuku.checkSelfPermission() ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            if (shizukuUsable) {
                lastRepairAt = now
                consecutiveDisconnected = 0
                Log.i(TAG, "listener still disconnected → shizuku listener repair")
                cc.ytdttj.noticleaner.diagnostics.RingLog.log("看门狗：Shizuku 强制修复监听")
            }
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    if (shizukuUsable) {
                        runCatching {
                            val log = ListenerRepair.repair(ShizukuExecutor)
                            Log.i(TAG, "listener repair done:\n$log")
                            // 2.0.1 Dev 3：系统侧诊断段进环形日志（导出可见，logcat 易滚动）
                            val diag = log.substringAfter("---- 系统侧诊断", "")
                            if (diag.isNotBlank()) {
                                val trimmed = diag.lineSequence().take(45).joinToString("\n")
                                cc.ytdttj.noticleaner.diagnostics.RingLog.log("系统侧诊断\n$trimmed")
                            }
                            cc.ytdttj.noticleaner.diagnostics.RingLog.log(
                                "看门狗：Shizuku 修复完成 → ${log.lineSequence().firstOrNull()?.take(80)}",
                            )
                        }.onFailure { t ->
                            Log.w(TAG, "listener repair failed: $t")
                            cc.ytdttj.noticleaner.diagnostics.RingLog.log("✗ 看门狗：Shizuku 修复失败 $t")
                        }
                    }
                    // 1.2.0（ImprovePlan P0-2）：顺带清理过期通知
                    runCatching {
                        cc.ytdttj.noticleaner.ServiceLocator.db.notificationDao()
                            .purgeExpired(System.currentTimeMillis())
                    }.onFailure { Log.w(TAG, "purgeExpired failed: $it") }
                } finally {
                    finishSafely()
                }
            }
        } else {
            consecutiveDisconnected = 0
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    runCatching {
                        cc.ytdttj.noticleaner.ServiceLocator.db.notificationDao()
                            .purgeExpired(System.currentTimeMillis())
                    }.onFailure { Log.w(TAG, "purgeExpired failed: $it") }
                } finally {
                    finishSafely()
                }
            }
        }
    }
}
