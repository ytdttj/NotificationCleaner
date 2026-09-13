package cc.ytdttj.noticleaner.notify

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 保活能力探测与执行（Plan.md §7）：
 * - T0 常规：通知监听权限、电池优化白名单、厂商设置引导
 * - T1 Shizuku / T2 Root：shell 级命令（用户主动启用才授权执行）
 * - T3 LSPosed：模块 hook system_server（见 keepalive/LspEntry），应用侧仅展示引导
 */
data class KeepAliveStatus(
    val listenerEnabled: Boolean = false,
    val ignoringBattery: Boolean = false,
    val rootAvailable: Boolean = false,
    val shizukuAvailable: Boolean = false,
    val lspDetected: Boolean? = null, // null=无法检测（需在 LSPosed 管理器中查看）
)

class KeepAliveManager(private val context: Context) {

    /** 通知监听 + 电池优化 + Root 探测（Shizuku 状态由调用方传入，避免主线程 ping） */
    fun status(shizukuOk: Boolean = false): KeepAliveStatus {
        val pm = context.getSystemService(PowerManager::class.java)
        return KeepAliveStatus(
            listenerEnabled = CleanerListenerService.isListenerEnabled(context),
            ignoringBattery = pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false,
            rootAvailable = isRootAvailable(),
            shizukuAvailable = shizukuOk,
        )
    }

    /** 探测 Root（su 可执行；快速超时，需在 IO 线程调用） */
    fun isRootAvailable(): Boolean = runCatching {
        val p = ProcessBuilder("su", "-c", "id").start()
        val ok = p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0
        p.destroy()
        ok
    }.getOrDefault(false)

    // ---------------- T0 ----------------

    fun requestIgnoreBatteryOptimization() {
        val pm = context.getSystemService(PowerManager::class.java)
        if (pm?.isIgnoringBatteryOptimizations(context.packageName) == true) return
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    fun openListenerSettings() {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun manufacturerAutoStartHint(): String? = when (Build.MANUFACTURER.lowercase()) {
        "xiaomi" -> "小米/HyperOS：设置 → 应用设置 → 应用管理 → 通知净化器 → 自启动"
        "huawei", "honor" -> "华为/荣耀：设置 → 应用 → 应用启动管理 → 通知净化器 → 允许自启动"
        "oppo", "realme", "oneplus" -> "OPPO/一加：设置 → 电池 → 更多设置 → 允许完全后台行为"
        "vivo", "iqoo" -> "vivo/iQOO：设置 → 电池 → 后台功耗管理 → 允许后台高耗电"
        "samsung" -> "三星：设置 → 电池 → 后台使用限制 → 移出深度休眠"
        else -> null
    }

    companion object {
        /** 跳转到指定 APP 的指定通知渠道设置（Plan.md §6.1 详情页按钮） */
        fun openChannelSettings(context: Context, packageName: String, channelId: String): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ok = runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                            putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                    )
                }.isSuccess
                if (ok) return true
            }
            return runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            }.isSuccess
        }
    }
}

// ========================= T1/T2：shell 命令集 =========================

/** 保活命令集（Plan.md §7.2），由 Shizuku(shell) 或 Root(su) 执行 */
object KeepAliveCommands {

    fun commands(context: Context): List<String> {
        val pkg = context.packageName
        val svc = "$pkg/cc.ytdttj.noticleaner.notify.CleanerListenerService"
        val cmds = mutableListOf(
            "dumpsys deviceidle whitelist +$pkg",
            "appops set $pkg RUN_IN_BACKGROUND allow",
            "appops set $pkg RUN_ANY_IN_BACKGROUND allow",
            "appops set $pkg START_FOREGROUND allow",
            "cmd notification allow_listener $svc",
        )
        // 厂商附加 op（存在则生效，失败忽略）
        when (Build.MANUFACTURER.lowercase()) {
            "xiaomi" -> {
                cmds += "appops set $pkg AUTO_START allow"
                cmds += "settings put system power_keeper_whitelist $pkg"
            }
            "oppo", "realme", "oneplus" -> cmds += "appops set $pkg START_ACTIVITY_FROM_BACKGROUND allow"
        }
        return cmds
    }
}

/** 抽象 shell 执行器：Shizuku / Root 共用 */
interface ShellExecutor {
    suspend fun exec(cmd: String): String
}

/** Root：su -c */
object RootExecutor : ShellExecutor {
    override suspend fun exec(cmd: String): String = kotlinx.coroutines.withContext(
        kotlinx.coroutines.Dispatchers.IO,
    ) {
        val p = ProcessBuilder("su", "-c", cmd).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        val err = p.errorStream.bufferedReader().use { it.readText() }
        p.waitFor()
        buildString {
            if (out.isNotBlank()) append(out.trim())
            if (err.isNotBlank()) append(if (isEmpty()) "" else "\n").append("ERR: ").append(err.trim())
        }
    }
}

/** Shizuku：shell 级（经 Shizuku 服务执行；newProcess 为私有 API，反射调用） */
object ShizukuExecutor : ShellExecutor {
    override suspend fun exec(cmd: String): String = kotlinx.coroutines.withContext(
        kotlinx.coroutines.Dispatchers.IO,
    ) {
        val m = rikka.shizuku.Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        val out = p.inputStream.bufferedReader().use { it.readText() }
        val err = p.errorStream.bufferedReader().use { it.readText() }
        p.waitFor()
        buildString {
            if (out.isNotBlank()) append(out.trim())
            if (err.isNotBlank()) append(if (isEmpty()) "" else "\n").append("ERR: ").append(err.trim())
        }
    }
}

suspend fun runKeepAliveCommands(
    context: Context,
    executor: ShellExecutor,
    onProgress: (String) -> Unit,
): String {
    val sb = StringBuilder()
    for (cmd in KeepAliveCommands.commands(context)) {
        onProgress(cmd)
        val out = runCatching { executor.exec(cmd) }.getOrElse { "执行失败: $it" }
        sb.append("$ ").appendLine(cmd)
        sb.append(if (out.isBlank()) "(无输出)" else out).appendLine().appendLine()
    }
    return sb.toString().trim()
}
