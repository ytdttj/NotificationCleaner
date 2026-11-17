package cc.ytdttj.noticleaner.keepalive

import android.content.ContentValues
import android.content.Context

/**
 * Hook 端日志回流（Dev 8）：SystemUI / xmsf / system_server 进程内的关键事件
 * （岛校验、云认证、NMS 拦截）经 ModuleLogProvider 回流到 App 的 RingLog，
 * 使"诊断导出"覆盖最近 24 小时的 Hook 行为——此前这些事件只存在于
 * LSPosed 管理器日志（普通用户无法导出，App 端分析断链）。
 *
 * decision=HOOK_LOG，provider 特殊处理写 RingLog（不入通知历史库）。
 * 每个 hook 进程持有一个单例（ModuleLogSink 自带缓冲/限频）。
 */
object HookLogSink {

    @Volatile
    private var sink: ModuleLogSink? = null

    /** 进程标识（system / com.android.systemui / com.xiaomi.xmsf:services） */
    @Volatile
    private var process: String = "unknown"

    fun init(processName: String) {
        synchronized(this) {
            if (sink == null) {
                sink = ModuleLogSink()
                process = processName
            }
        }
    }

    /** 因拿不到 Context 而丢弃的事件数（Dev 12：此前静默丢弃，排查时完全无痕） */
    private val droppedCount = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * 回流一条 Hook 事件。
     *
     * Dev 12 修复：此前 `ctx == null` 直接 return —— xmsf 进程里
     * `ActivityThread.currentActivityThread()` 取不到，**认证事件 100% 静默丢失**
     * （诊断导出的 04-HOOK 里 xmsf 侧 0 条就是这个原因，而 LSP 日志里其实一大堆）。
     * 现在：ctx 为 null 时自动回退到 [systemContextOrNull()]；仍拿不到则计数 +
     * 写 logcat（tag=NCIslandHook，会被诊断导出的 logcat 补充段抓到），
     * 并在下一次成功回流时把"此前丢弃 N 条"作为后缀带出，不再无声无息。
     *
     * @param ctx 任意能解析 ContentProvider 的 Context；可为 null（内部会兜底）
     * @param event 事件名（如 canShowFocus-ALLOW / auth-DONE）
     * @param detail 附加信息（参数形态/耗时/错误码等）
     */
    fun log(ctx: Context?, event: String, detail: String = "") {
        val s = sink ?: return
        val c = ctx ?: systemContextOrNull()
        if (c == null) {
            droppedCount.incrementAndGet()
            android.util.Log.w("NCIslandHook", "hook log dropped (no Context in $process): $event $detail")
            return
        }
        val dropped = droppedCount.getAndSet(0)
        val suffix = if (dropped > 0) " [此前因无 Context 丢弃 ${dropped} 条]" else ""
        val values = ContentValues().apply {
            put(cc.ytdttj.noticleaner.provider.ModuleLogProvider.COL_PACKAGE, process)
            put(cc.ytdttj.noticleaner.provider.ModuleLogProvider.COL_CHANNEL, "")
            put(cc.ytdttj.noticleaner.provider.ModuleLogProvider.COL_TITLE, event)
            put(cc.ytdttj.noticleaner.provider.ModuleLogProvider.COL_CONTENT, detail + suffix)
            put(cc.ytdttj.noticleaner.provider.ModuleLogProvider.COL_POST_TIME, System.currentTimeMillis())
            put(cc.ytdttj.noticleaner.provider.ModuleLogProvider.COL_PROBABILITY, 0f)
            put(cc.ytdttj.noticleaner.provider.ModuleLogProvider.COL_DECISION,
                cc.ytdttj.noticleaner.provider.ModuleLogProvider.HOOK_LOG_DECISION)
            put(cc.ytdttj.noticleaner.provider.ModuleLogProvider.COL_KEY, "hook:${System.nanoTime()}")
        }
        s.submit(c, values)
    }

    /** xmsf 等无直接 Context 的进程：读现有 ActivityThread 的 SystemContext（仅 getter，安全） */
    fun systemContextOrNull(): Context? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
            .getMethod("currentActivityThread").invoke(null) ?: return null
        at.javaClass.getMethod("getSystemContext").invoke(at) as? Context
    }.getOrNull()

    /**
     * 当前进程的 Application Context（2.2.0 Dev 1 热重载用）。
     * SystemUI 等正常 app 进程有 Application；xmsf :services 等服务进程可能为 null。
     * 仅 getter 反射，不触碰全局状态（Dev 5 教训：绝不在 hook 流程外二次初始化 ActivityThread）。
     */
    fun currentApplicationOrNull(): Context? = runCatching {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication").invoke(null) as? Context
    }.getOrNull()

    /**
     * 从 hook 现场尽力挖一个 Context：① 参数里的 Context ② thisObject 的 mContext 字段
     * （含父类）③ [systemContextOrNull()]。都失败返回 null（调用方按丢弃处理并计数）。
     */
    fun contextOf(args: List<Any?>?, thisObject: Any?): Context? {
        args?.firstOrNull { it is Context }?.let { return it as Context }
        val holder = thisObject ?: return systemContextOrNull()
        var c: Class<*>? = holder.javaClass
        while (c != null) {
            runCatching {
                val f = c!!.getDeclaredField("mContext")
                f.isAccessible = true
                val v = f.get(holder)
                if (v is Context) return v
            }
            c = c.superclass
        }
        return systemContextOrNull()
    }
}
