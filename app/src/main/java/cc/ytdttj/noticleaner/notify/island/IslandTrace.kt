package cc.ytdttj.noticleaner.notify.island

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 岛链路诊断环形缓冲（islandv2plan.md P1）：
 * 记录每次上岛尝试的每步结果（收到通知 → 开关/白名单/金额 → 盲窗 → 发送），
 * 供设置页诊断区展示。内存驻留，最近 [MAX] 条。
 */
object IslandTrace {

    private const val MAX = 40
    private val lock = Any()
    private val entries = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun log(msg: String) {
        val line = "${fmt.format(Date())}  $msg"
        synchronized(lock) {
            entries.addLast(line)
            while (entries.size > MAX) entries.removeFirst()
        }
        android.util.Log.i("NCIsland", msg)
    }

    /** 诊断区展示文本（新→旧倒序，方便看最近一次） */
    fun dump(): String = synchronized(lock) {
        if (entries.isEmpty()) "（暂无记录，先发送一次模拟通知）"
        else entries.toList().reversed().joinToString("\n")
    }

    fun clear() = synchronized(lock) { entries.clear() }
}
