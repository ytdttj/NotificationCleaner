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

    /**
     * 回流一条 Hook 事件。
     * @param ctx 任意能解析 ContentProvider 的 Context（hook 到的服务实例/系统上下文）
     * @param event 事件名（如 canShowFocus-ALLOW / auth-DONE）
     * @param detail 附加信息（参数形态/耗时等）
     */
    fun log(ctx: Context?, event: String, detail: String = "") {
        val s = sink ?: return
        val c = ctx ?: return
        val values = ContentValues().apply {
            put(cc.ytdttj.noticleaner.provider.ModuleLogProvider.COL_PACKAGE, process)
            put(cc.ytdttj.noticleaner.provider.ModuleLogProvider.COL_CHANNEL, "")
            put(cc.ytdttj.noticleaner.provider.ModuleLogProvider.COL_TITLE, event)
            put(cc.ytdttj.noticleaner.provider.ModuleLogProvider.COL_CONTENT, detail)
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
}
