package cc.ytdttj.noticleaner.diagnostics

import android.content.Context
import cc.ytdttj.noticleaner.BuildConfig
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 文件环形日志（1.4.0 Dev 12）——监控式近 24 小时滚动留痕。
 *
 * 背景（2026-09-23 招行上岛排查）：岛 trace 仅内存 40 条 + logcat 环形缓冲易滚动，
 * 早晨的事件到下午导出诊断时已丢失，无法事后归因。本组件把全链路关键事件
 * 落盘循环存储，导出诊断时可覆盖近 24 小时，进程崩溃/重启不丢失。
 *
 * 设计：
 * - **存储**：filesDir/logs/ring.log（当前代）+ ring.old.log（上一代）；
 *   单文件上限 [MAX_FILE_BYTES]，追加超限时当前代 → old 轮转（old 直接覆盖），
 *   总占用 ≤ 2×[MAX_FILE_BYTES]。
 * - **时间裁剪**：init 时与每小时异步删除 24 小时前的行（解析行首时间戳，
 *   解析失败的行保守保留）；加上尺寸轮转双保险。
 * - **风暴折叠**：相邻完全相同的消息只在首次落盘，切换消息时补一条
 *   "…(连续重复 ×N)" 摘要——消息风暴/看门狗心跳不再刷爆配额。
 * - **全异步单线程**：[log] 仅入队，IO/轮转/裁剪全部在后台单线程串行执行；
 *   [dump] 直接读盘（并发中可能读到半行，可接受）。
 * - **崩溃留痕**：init 时包装默认未捕获异常处理器，崩溃栈**同步**写入本日志
 *   （进程即将死亡，不能依赖异步队列）后转发原处理器。
 */
object RingLog {

    private const val MAX_FILE_BYTES = 2L * 1024 * 1024
    private const val RETAIN_MS = 24L * 60 * 60 * 1000
    private const val MAX_STACK_CHARS = 4000
    private const val TRIM_PERIOD_HOURS = 1L

    /** 行首时间戳格式（长度固定，供裁剪按前缀解析） */
    private val tsFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private const val TS_LEN = 18 // "MM-dd HH:mm:ss.SSS"

    private lateinit var dir: File
    private val currentFile: File get() = File(dir, "ring.log")
    private val oldFile: File get() = File(dir, "ring.old.log")

    /** 单线程串行执行所有文件操作（append/rotate/trim 天然无并发写） */
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "RingLog").apply { isDaemon = true }
        }

    // ---- 相邻重复折叠状态（仅 executor 线程访问） ----
    private var lastMsg: String? = null
    private var lastMsgTs = ""
    private var lastCount = 1

    @Volatile
    private var ready = false

    /**
     * 初始化：建目录、裁剪超期行、标记"进程启动"、每小时定期裁剪、挂崩溃钩子。
     * 由 ServiceLocator.init（Application.onCreate）调用，任何进程拉起路径都会执行。
     */
    fun init(context: Context) {
        if (ready) return
        dir = File(context.filesDir, "logs").apply { mkdirs() }
        ready = true
        executor.execute {
            runCatching { appendLocked("进程启动 v${BuildConfig.VERSION_NAME} (vc${BuildConfig.VERSION_CODE})") }
            runCatching { trimLocked() }
        }
        executor.scheduleWithFixedDelay(
            { runCatching { trimLocked() } },
            TRIM_PERIOD_HOURS, TRIM_PERIOD_HOURS, TimeUnit.HOURS,
        )
        installCrashHook()
    }

    /** 异步写一条日志（崩溃路径请用 [logSync]） */
    fun log(msg: String) {
        if (!ready) return
        executor.execute {
            runCatching { appendLocked(msg) }
        }
    }

    /** 同步写（仅崩溃钩子用）：进程即将死亡，不能等异步队列 */
    private fun logSync(msg: String) {
        if (!ready) return
        runCatching { appendLocked(msg) }
    }

    /**
     * 导出用：old 代（较旧）在前、当前代在后拼接。并发写入中可能读到半行，
     * 对排查无碍。未初始化或无文件时返回说明文本。
     */
    fun dump(): String {
        if (!ready) return "（RingLog 未初始化）"
        val sb = StringBuilder()
        for (f in arrayOf(oldFile, currentFile)) {
            if (!f.exists()) continue
            runCatching { sb.append(f.readText()) }
                .onFailure { sb.appendLine("(读取失败: $f: $it)") }
        }
        return if (sb.isEmpty()) "（环形日志为空）" else sb.toString()
    }

    // ---- 以下均在 executor 线程执行（logSync 除外，进程垂死无并发顾虑） ----

    private fun appendLocked(msg: String) {
        // 相邻重复折叠：重复不落盘，仅累计；切换消息时补摘要行
        if (msg == lastMsg) {
            lastCount++
            return
        }
        val writer = openOrRotate()
        if (lastMsg != null && lastCount > 1) {
            writer.appendLine("$lastMsgTs （上行连续重复 ×$lastCount）$lastMsg")
        }
        val stamp = tsFormat.format(Date())
        writer.appendLine("$stamp $msg")
        writer.flush()
        lastMsg = msg
        lastMsgTs = stamp
        lastCount = 1
        if (currentFile.length() > MAX_FILE_BYTES) rotateLocked(writer)
    }

    /** 轮转：当前代 → old（覆盖旧 old），开新当前代；随后异步裁剪 old 超期行 */
    private fun rotateLocked(writer: FileWriter) {
        runCatching { writer.close() }
        runCatching { oldFile.delete() }
        runCatching { currentFile.renameTo(oldFile) }
        lastMsg = null // 轮转后重复折叠状态失效，避免摘要行指向已滚动文件
        val newWriter = openWriter()
        newWriter.appendLine("${tsFormat.format(Date())} （环形日志轮转：单文件超 ${MAX_FILE_BYTES / 1024}KB，旧内容移至 ring.old.log）")
        newWriter.flush()
        attachWriter(newWriter)
        executor.execute { runCatching { trimLocked() } }
    }

    /** 当前代打开的 writer；未打开（进程重启后首写）则新建 */
    private fun openOrRotate(): FileWriter {
        writerRef?.let { return it }
        val w = openWriter()
        attachWriter(w)
        return w
    }

    @Volatile
    private var writerRef: FileWriter? = null

    private fun openWriter(): FileWriter = FileWriter(currentFile, true)

    private fun attachWriter(w: FileWriter) {
        writerRef = w
    }

    /** 删除 24 小时前的行（old 与当前代都处理）；行首时间戳解析失败保守保留 */
    private fun trimLocked() {
        val cutoff = System.currentTimeMillis() - RETAIN_MS
        for (f in arrayOf(oldFile, currentFile)) {
            if (!f.exists()) continue
            val trimmed = runCatching {
                val lines = f.readLines()
                val kept = lines.filterIndexed { i, line ->
                    if (i == 0 && f == currentFile && line.startsWith("（")) return@filterIndexed true // 轮转标记行
                    val t = parseLeadingTs(line)
                    t == null || t >= cutoff
                }
                if (kept.size == lines.size) return@runCatching false
                // 裁剪期间有并发写入风险极低（同线程串行），整文件重写
                f.writeText(kept.joinToString("\n") + if (kept.isEmpty()) "" else "\n")
                true
            }.getOrDefault(false)
            if (trimmed && f == currentFile) {
                // 重写后 writer 的追加偏移与文件脱钩，强制重开
                runCatching { writerRef?.close() }
                writerRef = null
            }
        }
    }

    private fun parseLeadingTs(line: String): Long? =
        runCatching {
            tsFormat.parse(line.take(TS_LEN))?.time
        }.getOrNull()?.takeIf { _ ->
            line.length >= TS_LEN && line[2] == '-' && line[5] == ' '
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
                    "✗ 进程崩溃 thread=${t.name} ${e.javaClass.name}: " +
                        "${e.message}\n${sw.toString().take(MAX_STACK_CHARS)}",
                )
            }
            prev?.uncaughtException(t, e)
        }
    }
}
