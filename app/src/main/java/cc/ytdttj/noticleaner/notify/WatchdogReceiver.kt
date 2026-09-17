package cc.ytdttj.noticleaner.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

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
        private const val INTERVAL_MS = 9 * 60 * 1000L

        /** 启动/续约闹钟；exact 被拒绝（未授予精确闹钟）时自动退化为非精确 */
        fun schedule(context: Context) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            val pi = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                Intent(context, WatchdogReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val triggerAt = SystemClock.elapsedRealtime() + INTERVAL_MS
            val exact = runCatching {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }.isSuccess
            if (!exact) {
                runCatching {
                    am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                }
            }
            Log.i(TAG, "alarm scheduled exact=$exact at=$triggerAt")
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
        }
        // 自续约（KeepAliveService 存活期间由它启动；服务被杀后本接收器仍可维持链条）
        schedule(context)
    }
}
