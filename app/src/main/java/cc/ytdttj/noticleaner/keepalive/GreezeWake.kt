package cc.ytdttj.noticleaner.keepalive

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import java.util.concurrent.Executors
import cc.ytdttj.noticleaner.BuildConfig
import cc.ytdttj.noticleaner.provider.ModuleLogProvider

/**
 * Greeze「按需解冻」（2.2.1 Dev 1）—— 在 system_server 内跑。
 *
 * ## 为什么需要它
 * HyperOS 的 `GreezeManagerService` 会在灭屏后冻结本应用进程。它的解冻**只由外部事件触发**
 * （`Check Binder caller` / `Sync Binder` / `adj caller` / `provider caller` / 亮屏 / 启动 Activity），
 * **没有任何定时解冻**。而**通知投递走的是 `oneway` 回调，不会触发解冻** ——
 * 所以进程一旦被冻，积压的通知要等到"某个巧合的调用"才被放出来。
 *
 * 2026-10-14 实证（17PM / Android 16）：
 * ```
 * 12:34:33.637  GreezeManager: FZ uid = 10587 reason =from system success !
 *               ↓  316 秒内没有任何 binder 调用进来
 * 12:39:49.679  GreezeManager: THAW uid = 10587  reason : Sync Binder4 caller : 1000
 * 12:39:49.680  ← 积压的 7 条通知在同一毫秒一次性投递（lag 21s~2分8秒）
 * ```
 * 对照：另一台设备（K90U）拿到 `NO_RESTRICT_APP` 永久豁免、从不被冻 → 全天 0 次延迟批次。
 *
 * ## 做法
 * 我们的模块**本来就已经 hook 了 `NotificationManagerService.enqueueNotificationInternal`**
 * （见 [LspEntry.hookNotificationManagerService]，跑在 system_server）。在每次通知入队时，
 * 到我们自己的 ContentProvider 发起一次 **同步** `call()` ——
 * 目的不是传数据，而是**制造一次"外部调用进来"的事件**，让 Greeze 把进程解冻。
 * 解冻后 NMS 早已排队的 `oneway` 通知回调会立即执行，延迟从**分钟级回到毫秒级**。
 *
 * ## 为什么不用别的手段
 * - 不去碰"判定层"（`PolicyMaker` 决定该不该冻）：Android 16 上根本没有 `NO_RESTRICT_APP` 这一档
 *   （17PM 的 PolicyMaker 理由表里 0 次），也没法可靠地"冒充白名单"。
 * - 不用定时器空转：按需触发零额外耗电，且精准。
 * - 不需要改 `freezeAction` 的返回值：那会破坏 Greeze 的记账（我们已实测到副作用）。
 *
 * ## 安全
 * 全部逻辑跑在**独立 worker 线程**上（绝不在 NMS 入队路径上做同步 IPC）；
 * 1 秒节流；连续失败 5 次后自动停用；任何异常只记日志、绝不抛出。
 */
internal object GreezeWake {

    private const val TAG = "NCWatch"

    /** 节流：1 秒内最多发起一次（一次解冻足够覆盖紧随其后的多条通知） */
    private const val THROTTLE_MS = 1_000L

    /** 应用存活检查结果的缓存时长（避免每条通知都去问 ActivityManager） */
    private const val ALIVE_CACHE_MS = 30_000L

    /** 连续失败多少次后停用（例如 provider 不存在 / 权限被拒） */
    private const val MAX_CONSECUTIVE_FAILURES = 5

    /** 超过这个耗时说明目标进程当时被冻着（记一条日志作为"确实解冻过"的旁证） */
    private const val SLOW_CALL_MS = 30L

    private val worker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "nc-greeze-wake").apply { isDaemon = true }
    }

    /**
     * 同一时刻只允许一次在途调用。
     *
     * 同步 binder 调用**没有超时**：若目标进程被冻住且 Greeze 因故没解冻它，
     * 这次 call 会一直阻塞。加了这道闸，worker 队列不会堆积（最坏只是本功能静默失效，
     * 不会比现状更差）；慢调用会打日志，便于从诊断导出里发现。
     */
    private val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile private var lastRequestAt = 0L

    @Volatile private var appAliveCached = false
    @Volatile private var appAliveCheckedAt = 0L

    @Volatile private var consecutiveFailures = 0
    @Volatile private var disabled = false

    @Volatile private var issued = 0L
    @Volatile private var sent = 0L

    /**
     * 请求一次"按需解冻"。**在 NMS 入队 hook 里调用，必须立即返回**（重活都在 worker 上）。
     *
     * @param ctx NMS 实例对应的 SystemContext（见 `LspEntry.nmsContext`）
     */
    fun request(ctx: Context?) {
        if (disabled || !BuildConfig.GREEZE_WAKE_ON_ENQUEUE) return
        val c = ctx ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRequestAt < THROTTLE_MS) return
        lastRequestAt = now
        issued++
        worker.execute { perform(c) }
    }

    private fun perform(ctx: Context) {
        if (!appAlive(ctx)) return
        if (!inFlight.compareAndSet(false, true)) return
        val startedAt = SystemClock.elapsedRealtime()
        try {
            // 同步 binder 调用：副作用（触发 Greeze 解冻）本身就是目的
            ctx.contentResolver.call(ModuleLogProvider.CONTENT_URI, ModuleLogProvider.WAKE_METHOD, null, null)
            consecutiveFailures = 0
            sent++
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            if (sent == 1L || sent % 200L == 0L) {
                android.util.Log.i(TAG, "greeze wake: sent=$sent issued=$issued last=${elapsed}ms")
            } else if (elapsed > SLOW_CALL_MS) {
                // 慢调用说明目标进程当时被冻着（或系统正忙）——这条日志是"确实解冻过一次"的旁证
                android.util.Log.i(TAG, "greeze wake slow: ${elapsed}ms (sent=$sent)")
            }
        } catch (t: Throwable) {
            consecutiveFailures++
            android.util.Log.w(TAG, "greeze wake failed ($consecutiveFailures): $t")
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                disabled = true
                android.util.Log.w(TAG, "greeze wake disabled after $consecutiveFailures consecutive failures")
            }
        } finally {
            inFlight.set(false)
        }
    }

    /** 本应用进程是否存活（结果缓存 30s，避免每条通知都查 ActivityManager） */
    private fun appAlive(ctx: Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - appAliveCheckedAt < ALIVE_CACHE_MS) return appAliveCached
        appAliveCheckedAt = now
        appAliveCached = runCatching {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return@runCatching false
            am.runningAppProcesses?.any { it.pkgList?.contains(BuildConfig.APPLICATION_ID) == true } == true
        }.getOrDefault(false)
        return appAliveCached
    }

    /** 诊断用统计（Hook 事件回流里带上，便于从导出日志判断是否真的在发） */
    fun stats(): String =
        "wake sent=$sent issued=$issued" + if (disabled) " (disabled)" else ""
}
