package cc.ytdttj.noticleaner.diagnostics

import android.content.Context
import android.os.Build
import android.os.PowerManager
import cc.ytdttj.noticleaner.BuildConfig
import cc.ytdttj.noticleaner.notify.CleanerListenerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 诊断日志导出（2.0.1 Dev 9：分模块 ZIP）。
 *
 * 旧版把所有模块堆在一个 .txt 里：排查某条链路要在整坨文本里 grep，而且各模块共用
 * 一份环形缓冲配额，通知风暴会把上岛/看门狗的 24 小时历史挤掉。
 *
 * 现在导出为 **ZIP**，每个模块一个 log 文件：
 * - 00：诊断头 + 状态快照 + 各模块 24 小时覆盖汇总（先看这个文件）
 * - 01..09：九个模块各自的 log（[LogModules]），**每个文件都只保留导出前完整 24 小时
 *   窗口内的条目**，并在文件头给出窗口、条目数、最早/最晚时间、逐时分布与覆盖状态
 *   （最早条目明显晚于窗口起点 → 标 ⚠ 部分覆盖并说明可能原因）
 * - 10：通知历史 CSV（最近 24 小时，数据库为准）
 *
 * 24 小时覆盖由分模块环形日志（filesDir/logs/ring/<TAG>.log，保留 24h、崩溃/重启不丢）
 * 保证；logcat 缓冲通常不足 24 小时，只作为每个模块文件末尾的"补充段"附带。
 *
 * 输出写入外部私有目录 logs/，经 FileProvider 系统分享（mime application/zip）。
 */
object DiagExporter {

    /** 导出覆盖窗口：导出时刻往前 24 小时（例如 09-28 23:42 导出 → 09-27 23:42 ~ 09-28 23:42） */
    private const val WINDOW_MS = 24L * 60 * 60 * 1000

    /** 逐时分布槽位数 */
    private const val BUCKETS = 24

    /** 最早条目晚于窗口起点超过此值 → 判定"部分覆盖"（进程重启/缓冲滚动都会造成缺口） */
    private const val COVERAGE_TOLERANCE_MS = 60 * 60 * 1000L

    private val dayFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val stampFmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val shortFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    private val hourFmt = SimpleDateFormat("HH", Locale.US)

    /** 一条日志条目：首行（带时间戳）+ 续行（崩溃栈/诊断段等无时间戳行） */
    private class Entry(val ts: Long, val lines: MutableList<String>)

    /** @return 导出的 zip 文件；失败抛异常由调用方提示 */
    suspend fun export(context: Context): File = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val windowStart = now - WINDOW_MS
        val dir = File(context.getExternalFilesDir(null), "logs").apply { mkdirs() }
        val out = File(dir, "NotiCleaner-log-${stampFmt.format(Date(now))}.zip")

        // ---- 先生成各模块文件正文（00 的汇总需要各模块统计） ----
        val logcat = dumpLogcat()
        val logcatByModule = splitLogcat(logcat, windowStart)
        val bodies = LinkedHashMap<String, String>()
        val stats = LinkedHashMap<String, ModStat>()
        for (m in LogModules.ALL) {
            val raw = runCatching { RingLog.dump(m) }.getOrDefault("")
            val entries = parseEntries(raw, windowStart)
            val extra = logcatByModule[m].orEmpty()
            bodies[m] = buildModuleFile(m, now, windowStart, entries, extra)
            stats[m] = ModStat(m, entries.size, extra.size, entries.firstOrNull()?.ts, entries.lastOrNull()?.ts)
        }
        val history = runCatching { recentNotificationsCsv(windowStart) }
            .getOrDefault("(通知历史读取失败)\n")

        ZipOutputStream(out.outputStream().buffered()).use { zos ->
            zos.putText("00-诊断头与状态快照.txt", buildHeader(context, now, windowStart, stats, logcat))
            for (m in LogModules.ALL) zos.putText(LogModules.fileName(m), bodies[m].orEmpty())
            zos.putText("10-通知历史-24h.csv", history)
        }
        out
    }

    // ---------------- 00：诊断头 + 覆盖汇总 ----------------

    private suspend fun buildHeader(
        context: Context,
        now: Long,
        windowStart: Long,
        stats: Map<String, ModStat>,
        logcat: String,
    ): String = buildString {
        val pm = context.getSystemService(PowerManager::class.java)
        val exempt = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        val settings = cc.ytdttj.noticleaner.ServiceLocator.settings
        val threshold = settings.threshold.first()
        val intercept = settings.interceptMode.first()
        appendLine("==== NotiCleaner 诊断日志（分模块 ZIP）====")
        appendLine("导出时间: ${dayFmt.format(Date(now))}")
        appendLine("APP 版本: ${BuildConfig.VERSION_NAME} (versionCode=${BuildConfig.VERSION_CODE})")
        appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("电池豁免: $exempt")
        appendLine("通知监听权限: ${CleanerListenerService.isListenerEnabled(context)}")
        appendLine("监听连接状态: ${CleanerListenerService.isListenerConnected()}")
        appendLine("过滤阈值: $threshold")
        appendLine("拦截模式: ${if (intercept) "拦截" else "仅标记"}")
        appendLine(runCatching {
            cc.ytdttj.noticleaner.notify.island.IslandNotifier.diagSnapshot()
        }.getOrDefault("岛设置快照读取失败"))
        appendLine()
        appendLine("覆盖窗口: ${dayFmt.format(Date(windowStart))} ~ ${dayFmt.format(Date(now))}（导出前完整 24 小时）")
        appendLine("说明: 01~09 每个模块文件只保留上述窗口内的条目；窗口外的历史已按 24 小时自动裁剪。")
        appendLine()
        appendLine("==== 上岛延迟怎么看（Dev 12）====")
        appendLine("02/03 模块里每条通知都带 post= 与 lag=：")
        appendLine("  post = 通知的原始发布时间（微信/银行发出的时刻，sbn.postTime）")
        appendLine("  lag  = post 到「我们收到/处理」的间隔 —— 几十秒以上说明是系统投递积压")
        appendLine("        （灭屏或后台冻结时系统会压着不投递，亮屏才补投），此时 App 处理再快也没用")
        appendLine("03 模块「岛通知已提交系统」一行还带：")
        appendLine("  lag  = 原始通知发布 → 我们提交岛通知（投递积压 + App 处理）")
        appendLine("  took = 纯 App 内部耗时（收到 → 提交），几十毫秒属正常")
        appendLine("距离岛真正出现在屏幕上还差：SystemUI 校验（约 15ms）+ xmsf 云认证（实测 0.5~0.8s，")
        appendLine("且当前 100% 失败、靠模块 hook 兜底）+ HyperOS 岛渲染排队（未知，需系统侧时间戳）。")
        appendLine()
        appendLine("==== 文件清单与 24 小时覆盖情况 ====")
        appendLine("00-诊断头与状态快照.txt —— 本文件（状态快照 + 覆盖汇总）")
        for (m in LogModules.ALL) {
            val s = stats[m]
            appendLine("${LogModules.fileName(m)} —— ${s?.summaryLine(windowStart) ?: "（无统计）"}")
        }
        appendLine("10-通知历史-24h.csv —— 最近 24 小时通知历史（数据库记录）：时间/应用/包名/通道/标题/**正文**/决策/AI率/已学习")
        appendLine("      注：CSV 与 02/03 模块日志均含通知正文片段（便于排查误判与未上岛），")
        appendLine("      分享前请留意其中可能带有验证码、金额等敏感内容")
        appendLine()
        appendLine("==== 模块对照表 ====")
        for (m in LogModules.ALL) {
            appendLine("${LogModules.fileName(m)}\n    ${LogModules.desc(m)}")
        }
        appendLine()
        appendLine("==== logcat 抓取范围（仅以下 tag，其余静默）====")
        appendLine(LogModules.LOGCAT_TAGS.joinToString(", "))
        appendLine("logcat 原始行数: ${logcat.lineSequence().count()}")
        appendLine("注意: 系统 logcat 缓冲通常不足 24 小时，各模块文件末尾的「logcat 补充段」仅供参考；")
        appendLine("      24 小时覆盖以分模块环形日志为准（filesDir/logs/ring/<TAG>.log，崩溃/重启不丢失）。")
    }

    // ---------------- 01..09：模块文件 ----------------

    private fun buildModuleFile(
        module: String,
        now: Long,
        windowStart: Long,
        entries: List<Entry>,
        logcatLines: List<String>,
    ): String = buildString {
        appendLine("==== NotiCleaner 诊断日志 · 模块：${LogModules.title(module)} [$module] ====")
        appendLine("模块内容: ${LogModules.desc(module)}")
        appendLine("APP 版本: ${BuildConfig.VERSION_NAME} (versionCode=${BuildConfig.VERSION_CODE})")
        appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("导出时间: ${dayFmt.format(Date(now))}")
        appendLine("覆盖窗口: ${dayFmt.format(Date(windowStart))} ~ ${dayFmt.format(Date(now))}（完整 24 小时）")
        val first = entries.firstOrNull()?.ts
        val last = entries.lastOrNull()?.ts
        appendLine("条目数: ${entries.size}（环形日志） + ${logcatLines.size}（logcat 补充）")
        appendLine(
            "时间范围: " +
                (if (first != null) shortFmt.format(Date(first)) else "（无）") +
                " ~ " + (if (last != null) shortFmt.format(Date(last)) else "（无）"),
        )
        appendLine("覆盖状态: ${coverageText(entries, windowStart)}")
        if (entries.isNotEmpty()) appendLine("逐时分布: ${hourlyDistribution(entries, windowStart)}")
        appendLine("----------------------------------------------------------------")
        if (entries.isEmpty()) {
            appendLine("（本模块在 24 小时窗口内无记录——该模块期间无事件发生，属正常）")
        } else {
            for (e in entries) {
                appendLine(e.lines.joinToString("\n"))
            }
        }
        appendLine()
        appendLine("==== 补充：logcat 快照（tag=${logcatTagDesc(module)}）====")
        appendLine("系统 logcat 缓冲通常不足 24 小时，本段仅作补充，不代表完整 24 小时覆盖。")
        appendLine("----------------------------------------------------------------")
        if (logcatLines.isEmpty()) {
            appendLine("（窗口内无 logcat 记录）")
        } else {
            logcatLines.forEach { appendLine(it) }
        }
    }

    private fun logcatTagDesc(module: String): String {
        val tags = mutableListOf<String>()
        LogModules.logcatTagFor(module)?.let { tags += it }
        if (module == LogModules.ISLAND) tags += LogModules.ISLAND_TAGS
        if (module == LogModules.KEEP) tags += "NCWatch(看门狗相关行)"
        return if (tags.isEmpty()) "（该模块无独立 logcat 来源）" else tags.joinToString(", ")
    }

    /** 覆盖状态判定：空 → 无记录；最早条目晚于窗口起点 1h 以上 → 部分覆盖 */
    private fun coverageText(entries: List<Entry>, windowStart: Long): String {
        if (entries.isEmpty()) return "—（24 小时窗口内本模块无事件）"
        val first = entries.first().ts
        val gap = first - windowStart
        if (gap <= COVERAGE_TOLERANCE_MS) {
            return "✓ 完整覆盖 24 小时（最早条目距窗口起点 ${fmtGap(gap)}）"
        }
        return "⚠ 部分覆盖：最早条目距窗口起点 ${fmtGap(gap)} —— 常见原因：进程重启清空内存缓冲、" +
            "环形缓冲容量滚动（本模块 24 小时内写入超 1MB）、或该模块近期才启用"
    }

    private fun fmtGap(ms: Long): String {
        val min = ms / 60_000
        return if (min < 60) "${min}分钟" else "${min / 60}小时${min % 60}分钟"
    }

    /** 逐时分布：窗口起点起的 24 个 1 小时槽，格式 `HH:mm:count` */
    private fun hourlyDistribution(entries: List<Entry>, windowStart: Long): String {
        val buckets = IntArray(BUCKETS)
        for (e in entries) {
            val idx = ((e.ts - windowStart) / 3_600_000L).toInt().coerceIn(0, BUCKETS - 1)
            buckets[idx]++
        }
        return buildString {
            for (i in 0 until BUCKETS) {
                if (i > 0) append(" | ")
                append("${hourFmt.format(Date(windowStart + i * 3_600_000L))}时:${buckets[i]}")
            }
        }
    }

    private class ModStat(
        val module: String,
        val ringCount: Int,
        val logcatCount: Int,
        val first: Long?,
        val last: Long?,
    ) {
        fun summaryLine(windowStart: Long): String {
            val range = if (first != null && last != null) {
                "${shortFmt.format(Date(first))} ~ ${shortFmt.format(Date(last))}"
            } else {
                "无记录"
            }
            val flag = if (ringCount == 0) "" else {
                if (first!! - windowStart <= COVERAGE_TOLERANCE_MS) " [完整覆盖]" else " [⚠ 部分覆盖]"
            }
            return "条目 $ringCount（+logcat $logcatCount）｜$range$flag"
        }
    }

    // ---------------- 解析与过滤 ----------------

    /**
     * 把模块原始文本切成条目并**只保留 24 小时窗口内的条目**。
     * 首行带 `MM-dd HH:mm:ss.SSS` 时间戳 = 新条目；其后的无时间戳行（崩溃栈、诊断段）
     * 作为续行并入该条目——保证多行的崩溃栈不会被拆散。
     */
    private fun parseEntries(raw: String, windowStart: Long): List<Entry> {
        if (raw.isBlank()) return emptyList()
        val out = ArrayList<Entry>()
        var current: Entry? = null
        var currentKept = false
        for (line in raw.lineSequence()) {
            if (line.isBlank()) continue
            val ts = RingLog.parseLeadingTs(line)
            if (ts != null) {
                currentKept = ts >= windowStart
                if (currentKept) {
                    val e = Entry(ts, mutableListOf(line))
                    out.add(e)
                    current = e
                } else {
                    current = null
                }
            } else {
                // 续行：归属上一条目（上一条目若在窗口外则丢弃）
                if (currentKept) current?.lines?.add(line)
            }
        }
        return out
    }

    /**
     * logcat 行按 tag 归到模块，并过滤到 24 小时窗口内。
     * 行格式（`-v time`）：`09-28 23:32:07.474 I/NCWatch(23522): message`
     */
    private fun splitLogcat(logcat: String, windowStart: Long): Map<String, List<String>> {
        val out = HashMap<String, MutableList<String>>()
        val re = Regex("^(\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+\\w/([^(:]+)\\(\\s*\\d+\\):\\s?(.*)$")
        for (line in logcat.lineSequence()) {
            if (line.isBlank() || line.startsWith("---------")) continue
            val m = re.find(line) ?: continue
            val ts = RingLog.parseLeadingTs(m.groupValues[1])
            if (ts == null || ts < windowStart) continue
            val tag = m.groupValues[2].trim() // 部分系统 tag 带尾部空格（如 "HWUI    "）
            val msg = m.groupValues[3]
            val module = moduleOfTag(tag, msg) ?: continue
            out.getOrPut(module) { ArrayList() }.add(line)
        }
        return out
    }

    private fun moduleOfTag(tag: String, msg: String): String? = when (tag) {
        in LogModules.ISLAND_TAGS -> LogModules.ISLAND
        "NotiCleaner" -> LogModules.HOOK
        "UpdateVM" -> LogModules.UPDATE
        "ModelRepository" -> LogModules.MODEL
        "NCWatch" -> {
            // 看门狗/保活相关行归 KEEP，其余（监听回调/补扫）归 NLS
            val low = msg.lowercase()
            if (low.contains("alarm") || low.contains("repair") || low.contains("fired") ||
                low.contains("watchdog") || low.contains("purge") || low.contains("goasync")
            ) {
                LogModules.KEEP
            } else {
                LogModules.NLS
            }
        }
        else -> null
    }

    // ---------------- 通知历史 ----------------

    /** 最近 24 小时通知历史（CSV，可直接用表格软件打开） */
    private suspend fun recentNotificationsCsv(windowStart: Long): String {
        val rows = cc.ytdttj.noticleaner.ServiceLocator.db.notificationDao().listSince(windowStart)
        // Dev 11：UTF-8 BOM——CSV 是纯 UTF-8，Excel/WPS 在中文 Windows 上按 GBK 打开即乱码
        //（历史通知 CSV 导出 HistoryCsvExporter 一直写 BOM，Dev 9 新增的本文件漏了）
        // Dev 12：补「通知正文」+「通知通道」——只有标题时无法判断"这条为什么被判广告/
        // 为什么没上岛"（判断依据是 title+content 合流后的文本，通道还会带来偏置权重）
        val header = "时间,应用,包名,通知通道,通知标题,通知正文,决策,AI率,已学习"
        if (rows.isEmpty()) return "\ufeff$header\r\n（最近 24 小时无通知记录）\r\n"
        return buildString {
            append("\ufeff")
            append(header).append("\r\n")
            for (n in rows) {
                // CRLF：与表头一致，Excel/WPS 打开不串行
                append(
                    listOf(
                        shortFmt.format(Date(n.postTime)),
                        n.appName,
                        n.packageName,
                        n.channelName.ifBlank { n.channelId }.ifBlank { "-" },
                        flatten(n.title),
                        flatten(n.content),
                        n.decision,
                        "${(n.adProbability * 100).toInt()}%",
                        if (n.learned) "是(${if (n.learnLabel == 1) "广告" else "正常"})" else "否",
                    ).joinToString(",") { csv(it) },
                )
                append("\r\n")
            }
        }
    }

    /**
     * P1-17（CSV 公式注入）：以 `= + - @ \t` 开头的字段前置单引号——通知标题/正文可被
     * 任意 App 构造成公式（`=cmd|...` 的 DDE 可拉起进程），Excel/WPS 打开导出文件即触发。
     * RFC 4180 转义照旧。
     */
    private fun csv(v: String): String {
        val guarded = if (v.isNotEmpty() && v[0] in "=+-@\t") "'" + v else v
        val s = guarded.replace("\"", "\"\"")
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"$s\"" else s
    }

    /**
     * 把正文压成单行：除了 `\n` 还要处理 `\r`（部分短信正文用裸 CR 分行，如联通满意度短信）
     * 以及 Unicode 行分隔符 `\u2028` / 下一行 `\u0085`——`replace("\n"," ")` 漏掉它们后，
     * 孤立 CR 会让 CSV 行结构损坏、Excel/WPS 打开串行（2026-10-15 实测 5~6 处/份）。
     */
    private fun flatten(v: String): String = buildString(v.length) {
        for (c in v) append(
            when (c) {
                '\n', '\r', '\u2028', '\u0085' -> ' '
                else -> c
            },
        )
    }

    /**
     * logcat 快照：仅自身 UID 日志可见（无需权限），且只抓 [LogModules.LOGCAT_TAGS]，
     * 其余一律静默（旧写法 `TAG:*` 在部分设备上被判为非法过滤式 → 退化成全量系统噪声）。
     */
    private fun dumpLogcat(): String = try {
        val cmd = mutableListOf("logcat", "-d", "-v", "time")
        LogModules.LOGCAT_TAGS.forEach { cmd += "$it:V" }
        cmd += "*:S"
        val p = Runtime.getRuntime().exec(cmd.toTypedArray())
        val stdout = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        stdout
    } catch (t: Throwable) {
        "logcat 读取失败: $t\n"
    }

    private fun ZipOutputStream.putText(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }
}
