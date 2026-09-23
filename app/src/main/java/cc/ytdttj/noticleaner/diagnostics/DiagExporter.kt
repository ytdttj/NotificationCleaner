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

/**
 * 诊断日志导出（1.2.1）：设置页一键导出排查材料。
 *
 * 内容 = 诊断头（版本/机型/权限/保活/过滤配置快照）+ logcat 快照。
 * 普通应用可读**自身 UID** 的 logcat（无需任何权限）——NCWatch 覆盖通知决策/看门狗/
 * 重绑全链路（1.1.14 诊断日志），NotiCleaner 为 LSPosed 模块日志，另含更新与模型仓库日志。
 * 输出写入外部私有目录 logs/，经 FileProvider 系统分享。
 */
object DiagExporter {

    private val LOG_TAGS = arrayOf(
        "NCWatch:*",        // 监听决策/看门狗/重绑全链路
        "NotiCleaner:*",    // LSPosed 模块
        "UpdateVM:*",       // 应用内更新
        "ModelRepository:*",// 模型加载/学习
        "NCIsland:*",       // 岛链路 trace
        "IslandNotifier:*", // 岛通知解析/入队
        "IslandBypass:*",   // 岛通知盲窗发送
    )

    /** @return 导出的日志文件；失败抛异常由调用方提示 */
    suspend fun export(context: Context): File = withContext(Dispatchers.IO) {
        val dir = File(context.getExternalFilesDir(null), "logs").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val out = File(dir, "NotiCleaner-log-$stamp.txt")
        out.writeText(buildHeader(context))
        out.appendText("\n==== 岛链路 trace（内存环形缓冲，最近 40 条，进程重启即清空）====\n")
        out.appendText(
            runCatching { cc.ytdttj.noticleaner.notify.island.IslandTrace.dump() }
                .getOrDefault("(trace 读取失败)"),
        )
        // 1.4.0 Dev 12：环形日志（文件循环存储）——覆盖近 24 小时的全链路事件，
        // 不随进程崩溃/重启丢失（岛内存 trace 与 logcat 均易滚动，2026-09-23 排查实测）
        out.appendText("\n==== 环形日志（文件循环存储，保留近 24 小时，崩溃/重启不丢失）====\n")
        out.appendText(
            runCatching { RingLog.dump() }.getOrDefault("(环形日志读取失败)"),
        )
        out.appendText("\n================ logcat dump ================\n")
        out.appendText(dumpLogcat())
        out
    }

    /** 诊断头：排查所需的静态与动态状态快照 */
    private suspend fun buildHeader(context: Context): String {
        val pm = context.getSystemService(PowerManager::class.java)
        val exempt = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        val settings = cc.ytdttj.noticleaner.ServiceLocator.settings
        val threshold = settings.threshold.first()
        val intercept = settings.interceptMode.first()
        return buildString {
            appendLine("==== NotiCleaner 诊断日志 ====")
            appendLine("导出时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
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
            appendLine("================")
        }
    }

    /** logcat 快照：仅自身 UID 日志可见，失败返回错误说明（不中断导出） */
    private fun dumpLogcat(): String = try {
        val cmd = mutableListOf("logcat", "-d", "-v", "time")
        LOG_TAGS.forEach { cmd += it }
        val p = Runtime.getRuntime().exec(cmd.toTypedArray())
        val stdout = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        if (stdout.isBlank()) "(日志为空——环形缓冲已滚动或进程刚启动)\n" else stdout
    } catch (t: Throwable) {
        "logcat 读取失败: $t\n"
    }
}
