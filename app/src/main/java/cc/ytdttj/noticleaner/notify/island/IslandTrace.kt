package cc.ytdttj.noticleaner.notify.island

import java.time.format.DateTimeFormatter
import java.util.ArrayDeque

/**
 * 岛链路诊断环形缓冲（islandv2plan.md P1）：
 * 记录每次上岛尝试的每步结果（收到通知 → 开关/白名单/金额 → 盲窗 → 发送），
 * 供设置页诊断区展示。内存驻留，最近 [MAX] 条。
 */
object IslandTrace {

    private const val MAX = 40
    private val lock = Any()
    private val entries = ArrayDeque<String>()

    // 1.3.2（P3-6）：SimpleDateFormat → java.time（不可变、线程安全）
    private val fmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    fun log(msg: String) {
        val line = "${java.time.LocalTime.now().format(fmt)}  $msg"
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
