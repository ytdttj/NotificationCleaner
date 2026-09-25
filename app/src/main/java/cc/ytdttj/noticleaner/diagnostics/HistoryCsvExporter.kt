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
                        n.title,
                        n.content,
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

    /** RFC 4180：含引号/逗号/换行的字段包裹双引号，内部引号翻倍 */
    private fun escape(v: String): String =
        if (v.contains('"') || v.contains(',') || v.contains('\n') || v.contains('\r')) {
            "\"" + v.replace("\"", "\"\"") + "\""
        } else {
            v
        }
}
