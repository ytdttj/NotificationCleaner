package cc.ytdttj.noticleaner.diagnostics

import android.content.Context
import cc.ytdttj.noticleaner.BuildConfig
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 文件环形日志（1.4.0 Dev 12 引入；2.0.1 Dev 9 分模块重构）——监控式近 24 小时滚动留痕。
 *
 * 背景（2026-09-23 招行上岛排查）：岛 trace 仅内存 40 条 + logcat 环形缓冲易滚动，
 * 早晨的事件到下午导出诊断时已丢失，无法事后归因。本组件把全链路关键事件
 * 落盘循环存储，导出诊断时可覆盖近 24 小时，进程崩溃/重启不丢失。
 *
 * 2.0.1 Dev 9 重构（用户反馈：所有模块堆在一个文本里，排查要靠 grep）：
 * - **分模块落盘**：filesDir/logs/ring/<TAG>.log（当前代）+ <TAG>.old.log（上一代），
 *   模块见 [LogModules]。导出的诊断 ZIP 里每个模块一个文件，各自覆盖完整 24 小时；
 *   单个模块刷屏（通知风暴 / 崩溃风暴）不再挤掉其它模块的 24 小时历史。
 * - **裁剪年份推断修复**（P1，CodeReview-2026-09-27 #2）：行首时间戳格式 `MM-dd HH:mm:ss.SSS`
 *   不带年份，`SimpleDateFormat` 解析后落到 **1970** → 每小时 trim 判定"早于 24 小时前"
 *   把**全部**行删掉，24 小时保留实际失效（实测导出只剩 2 小时）。现改为按当前年份回填，
 *   跨年自动回退（解析结果晚于当前时间 +1h 视为去年）。
 *
 * 设计：
 * - **容量**：每模块单文件上限 [MAX_FILE_BYTES]，超限时当前代 → old 轮转（old 直接覆盖），
 *   每模块占用 ≤ 2×[MAX_FILE_BYTES]，全模块理论上限 9×2×1MB = 18MB（实测远低于此）。
 * - **时间裁剪**：init 时与每小时异步删除 24 小时前的条目（按行首时间戳，解析失败的行
 *   跟随上一条目的去留，不再残留孤儿续行）。
 * - **风暴折叠**：相邻完全相同的消息只在首次落盘，切换消息时补一条
 *   "…(连续重复 ×N)" 摘要——消息风暴/看门狗心跳不再刷爆配额（按模块独立计数）。
 * - **全异步单线程**：[log] 仅入队，IO/轮转/裁剪全部在后台单线程串行执行；
 *   [dump] 直接读盘（并发中可能读到半行，可接受）。
 * - **崩溃留痕**：init 时包装默认未捕获异常处理器，崩溃栈**同步**写入本日志
 *   （进程即将死亡，不能依赖异步队列）后转发原处理器。
 */
object RingLog {

    /** 单个模块的单文件上限（当前代 + 上一代 = 2MB/模块） */
    private const val MAX_FILE_BYTES = 1024L * 1024L
    private const val RETAIN_MS = 24L * 60 * 60 * 1000
    private const val MAX_STACK_CHARS = 4000
    private const val TRIM_PERIOD_HOURS = 1L

    /** 行首时间戳格式（长度固定，供裁剪按前缀解析） */
    private val tsFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private const val TS_LEN = 18 // "MM-dd HH:mm:ss.SSS"

    private lateinit var dir: File

    private fun curFile(module: String): File = File(File(dir, "ring"), "$module.log")
    private fun oldFile(module: String): File = File(File(dir, "ring"), "$module.old.log")

    /** 单线程串行执行所有文件操作（append/rotate/trim 天然无并发写） */
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "RingLog").apply { isDaemon = true }
        }

    /** 单个模块的落盘状态（仅 executor 线程与崩溃同步路径访问） */
    private class Seg {
        var writer: FileWriter? = null
        var lastMsg: String? = null
        var lastTs: String = ""
        var lastKey: String? = null
        var count: Int = 1
    }

    private val segs = ConcurrentHashMap<String, Seg>()

    /**
     * 去重键 = 消息里的**易变 hex 串归一化**（内存地址 @xxxx、PID 等）。
     * 否则崩溃栈每次仅地址不同 → 永不折叠 → 崩溃风暴刷爆整个环形缓冲
     * （M332BF 实测：环形日志被同一条 goAsync 崩溃刷满，正常条目全部被挤出）。
     */
    private fun dedupKey(msg: String): String = msg
        .replace(Regex("@[0-9a-fA-F]{3,}"), "@x")
        .replace(Regex("\\b[0-9a-fA-F]{8,}\\b"), "HEX")

    @Volatile
    private var ready = false

    /**
     * 初始化：建目录、清理旧版单文件格式残留、裁剪超期行、标记"进程启动"、
     * 每小时定期裁剪、挂崩溃钩子。由 ServiceLocator.init（Application.onCreate）调用。
     */
    fun init(context: Context) {
        if (ready) return
        dir = File(context.filesDir, "logs").apply { mkdirs() }
        File(dir, "ring").mkdirs()
        ready = true
        executor.execute {
            // Dev 8 及更早：单文件 ring.log / ring.old.log。分模块后这些残留无法归属模块，
            // 直接清理（新格式首次启动即开始累积，24 小时后自然补齐）
            runCatching { File(dir, "ring.log").delete() }
            runCatching { File(dir, "ring.old.log").delete() }
            runCatching {
                appendLocked(
                    LogModules.SYS,
                    "进程启动 v${BuildConfig.VERSION_NAME} (vc${BuildConfig.VERSION_CODE})",
                )
            }
            runCatching { trimLocked() }
        }
        executor.scheduleWithFixedDelay(
            { runCatching { trimLocked() } },
            TRIM_PERIOD_HOURS, TRIM_PERIOD_HOURS, TimeUnit.HOURS,
        )
        installCrashHook()
    }

    /** 异步写一条日志到指定模块（崩溃路径请用 [logSync]） */
    fun log(module: String, msg: String) {
        if (!ready) return
        executor.execute { runCatching { appendLocked(module, msg) } }
    }

    /** 未标注模块的旧调用：归入 SYS（进程与系统），保证兼容不丢日志 */
    fun log(msg: String) = log(LogModules.SYS, msg)

    /** 同步写（仅崩溃钩子用）：进程即将死亡，不能等异步队列 */
    private fun logSync(module: String, msg: String) {
        if (!ready) return
        runCatching { appendLocked(module, msg) }
    }

    /**
     * 导出用：单个模块的内容 = 上一代（较旧）在前、当前代在后。
     * 未初始化或无文件时返回空串（调用方按"该模块 24 小时内无记录"处理）。
     */
    fun dump(module: String): String {
        if (!ready) return ""
        val sb = StringBuilder()
        for (f in arrayOf(oldFile(module), curFile(module))) {
            if (!f.exists()) continue
            runCatching { sb.append(f.readText()) }
                .onFailure { sb.appendLine("(读取失败: ${f.name}: $it)") }
        }
        return sb.toString()
    }

    /** 全量拼接（各模块带段头），供需要单文本的场景使用 */
    fun dump(): String = buildString {
        if (!ready) {
            appendLine("（RingLog 未初始化）")
            return@buildString
        }
        var any = false
        for (m in LogModules.ALL) {
            val text = dump(m)
            if (text.isBlank()) continue
            any = true
            appendLine("---- 模块 ${LogModules.title(m)} [$m] ----")
            append(text)
            if (!text.endsWith("\n")) appendLine()
        }
        if (!any) appendLine("（环形日志为空）")
    }

    // ---- 以下均在 executor 线程执行（logSync 除外，进程垂死无并发顾虑） ----

    @Synchronized
    private fun appendLocked(module: String, msg: String) {
        // 相邻重复折叠：重复不落盘，仅累计；切换消息时补摘要行
        val seg = segs.getOrPut(module) { Seg() }
        val key = dedupKey(msg)
        if (key == seg.lastKey) {
            seg.count++
            return
        }
        val writer = seg.writer ?: FileWriter(curFile(module), true).also { seg.writer = it }
        if (seg.lastMsg != null && seg.count > 1) {
            writer.appendLine("${seg.lastTs} （上行连续重复 ×${seg.count}）${seg.lastMsg}")
        }
        val stamp = tsFormat.format(Date())
        writer.appendLine("$stamp $msg")
        writer.flush()
        seg.lastMsg = msg
        seg.lastTs = stamp
        seg.lastKey = key
        seg.count = 1
        if (curFile(module).length() > MAX_FILE_BYTES) rotateLocked(module)
    }

    /**
     * 轮转：当前代 → old（覆盖旧 old），开新当前代；随后异步裁剪超期行。
     * 只影响单个模块——其它模块的 24 小时历史不受本模块刷屏影响（Dev 9 分模块的核心收益）。
     */
    @Synchronized
    private fun rotateLocked(module: String) {
        val seg = segs[module] ?: return
        runCatching { seg.writer?.close() }
        seg.writer = null
        runCatching { oldFile(module).delete() }
        runCatching { curFile(module).renameTo(oldFile(module)) }
        // 轮转后重复折叠状态失效，避免摘要行指向已滚动的文件
        seg.lastMsg = null
        seg.lastKey = null
        val w = FileWriter(curFile(module), true)
        w.appendLine(
            "${tsFormat.format(Date())} （环形日志轮转：本模块单文件超 ${MAX_FILE_BYTES / 1024}KB，" +
                "旧内容移至 $module.old.log）",
        )
        w.flush()
        seg.writer = w
        executor.execute { runCatching { trimLocked() } }
    }

    /**
     * 删除 24 小时前的条目（各模块的 old 与当前代都处理）。
     * 续行（崩溃栈/诊断段，无行首时间戳）跟随上一条目一起决定去留。
     */
    @Synchronized
    private fun trimLocked() {
        val cutoff = System.currentTimeMillis() - RETAIN_MS
        for (m in LogModules.ALL) {
            for (f in arrayOf(oldFile(m), curFile(m))) {
                if (!f.exists()) continue
                val changed = runCatching {
                    val lines = f.readLines()
                    val kept = ArrayList<String>(lines.size)
                    var keepEntry = true
                    for (line in lines) {
                        val t = parseLeadingTs(line)
                        if (t != null) keepEntry = t >= cutoff
                        if (keepEntry) kept.add(line)
                    }
                    if (kept.size == lines.size) return@runCatching false
                    f.writeText(kept.joinToString("\n") + if (kept.isEmpty()) "" else "\n")
                    true
                }.getOrDefault(false)
                if (changed) {
                    // 整文件重写后 writer 的追加偏移与文件脱钩，强制重开；折叠状态一并失效——
                    // 若被裁掉的最后一条正是 lastMsg，后续逐字相同的消息（看门狗心跳等）会一直
                    // count++ 永不落盘，"（上行连续重复 ×N）"摘要也会指向已删除的行
                    // （KEEP/SYS 导出恒 1~2 条的根因，2026-10-15 修复）
                    runCatching { segs[m]?.writer?.close() }
                    segs[m]?.writer = null
                    segs[m]?.lastMsg = null
                    segs[m]?.lastKey = null
                    segs[m]?.lastTs = ""
                    segs[m]?.count = 1
                }
            }
        }
    }

    /**
     * 解析行首时间戳。**格式不带年份**（`MM-dd HH:mm:ss.SSS`），SimpleDateFormat 解析后
     * 会落到 1970 —— 必须回填当前年份，否则 24 小时裁剪会把全部行判为超期删光
     * （2026-09-27 代码评审 P1，实测导出只剩 2 小时）。跨年时（解析结果晚于当前 +1h）
     * 回退为去年。
     */
    internal fun parseLeadingTs(line: String): Long? {
        if (line.length < TS_LEN || line[2] != '-' || line[5] != ' ') return null
        val parsed = runCatching { tsFormat.parse(line.substring(0, TS_LEN)) }.getOrNull() ?: return null
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        cal.time = parsed
        cal.set(Calendar.YEAR, Calendar.getInstance().get(Calendar.YEAR))
        if (cal.timeInMillis > now + 60 * 60 * 1000L) cal.add(Calendar.YEAR, -1)
        return cal.timeInMillis
    }

    /** 崩溃钩子：崩溃栈同步落盘后转发原处理器（保留系统崩溃对话框/ANR 上报语义） */
    private fun installCrashHook() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        if (prev != null && prev.javaClass.name == "cc.ytdttj.noticleaner.diagnostics.RingLog") return
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                logSync(
                    LogModules.CRASH,
                    "✗ 进程崩溃 thread=${t.name} ${e.javaClass.name}: " +
                        "${e.message}\n${sw.toString().take(MAX_STACK_CHARS)}",
                )
            }
            prev?.uncaughtException(t, e)
        }
    }
}
