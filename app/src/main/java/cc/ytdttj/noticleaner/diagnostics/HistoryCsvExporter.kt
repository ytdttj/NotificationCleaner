package cc.ytdttj.noticleaner.diagnostics

import android.content.Context
import cc.ytdttj.noticleaner.ServiceLocator
import cc.ytdttj.noticleaner.data.db.NotificationEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 历史通知 CSV 导出（Dev 6）。
 *
 * 字段：应用名称、应用包名、通知通道、通知标题、通知正文、通知AI率、
 * 是否被手动学习、学习方向。
 * UTF-8 带 BOM（Excel 直接打开中文不乱码）；RFC 4180 转义（引号/逗号/换行）。
 * 产物写 cacheDir/exports/，由设置页经 FileProvider 分享。
 */
object HistoryCsvExporter {

    private val HEADER = arrayOf(
        "通知时间", "应用名称", "应用包名", "通知通道", "通知标题", "通知正文", "通知AI率", "是否被手动学习", "学习方向",
    )

    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    suspend fun export(context: Context): File {
        val rows = ServiceLocator.db.notificationDao().exportAll()
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "NotiCleaner-历史通知-$stamp.csv")
        file.bufferedWriter(charset = Charsets.UTF_8).use { w ->
            w.write("\ufeff") // BOM
            w.appendLine(HEADER.joinToString(",") { escape(it) })
            for (n in rows) {
                w.appendLine(
                    arrayOf(
                        timeFmt.format(Date(n.postTime)),
                        n.appName,
                        n.packageName,
                        n.channelName.ifBlank { n.channelId },
                        flatten(n.title),
                        flatten(n.content),
                        "%.2f%%".format(n.adProbability * 100),
                        if (n.learned) "是" else "否",
                        learnLabelOf(n),
                    ).joinToString(",") { escape(it) },
                )
            }
        }
        return file
    }

    private fun learnLabelOf(n: NotificationEntity): String = when {
        !n.learned -> "-"
        n.learnLabel == 1 -> "广告"
        n.learnLabel == 0 -> "正常"
        else -> "-"
    }

    /**
     * RFC 4180：含引号/逗号/换行的字段包裹双引号，内部引号翻倍。
     * P1-17（CSV 公式注入）：通知标题/正文可被任意 App 构造成 `=cmd|...`、`+1`、`@SUM(...)`
     * 等，Excel/WPS 打开导出文件时会当公式执行（DDE 可拉起进程）。以公式触发字符开头的
     * 字段前置单引号（Excel 的"文本前缀"，显示为普通文本）。
     */
    private fun escape(v: String): String {
        val safe = if (v.isNotEmpty() && v[0] in "=+-@\t") "'" + v else v
        return if (safe.contains('"') || safe.contains(',') || safe.contains('\n') || safe.contains('\r')) {
            "\"" + safe.replace("\"", "\"\"") + "\""
        } else {
            safe
        }
    }

    /** 正文压成单行（与 DiagExporter.flatten 同口径）：裸 CR / U+2028 / U+0085 也算换行 */
    private fun flatten(v: String): String = buildString(v.length) {
        for (c in v) append(
            when (c) {
                '\n', '\r', '\u2028', '\u0085' -> ' '
                else -> c
            },
        )
    }
}
