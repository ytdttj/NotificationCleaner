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
    val accessibilityEnabled: Boolean = false, // 1.2.1：无障碍保活层（设置中启用）
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
            accessibilityEnabled = NotiGuardService.isEnabledInSettings(context),
            lspDetected = isLspActive(),
        )
    }

    /**
     * LSPosed 模块激活检测（Dev 5 重构：原实现只读 Settings.Secure
     * "enabled_xposed_modules"，新版 LSPosed/ROM 上该 key 不存在或格式漂移，
     * 导致"已激活却显示未激活"——激活状态被误报为 false）。
     *
     * 证据链（按可靠度）：
     * 1. 运行时铁证：模块在 system_server 内 hook 成功后，经 ModuleLogProvider
     *    回写心跳（keepalive/LspEntry → lsp_heartbeat 偏好文件）。有心跳 = 模块真实在跑。
     * 2. Settings.Secure 多 key 兜底："enabled_xposed_modules"（LSPosed 传统）/
     *    "active_xposed_modules"，冒号分隔包名列表。
     * 3. 都拿不到证据 → null（无法检测），**绝不返回 false**——
     *    检测不到 ≠ 未激活（本次 bug 根因）。
     */
    fun isLspActive(): Boolean? {
        // 证据 1：system_server 心跳
        runCatching {
            if (context.getSharedPreferences("lsp_heartbeat", Context.MODE_PRIVATE)
                    .getLong("last_alive", 0L) > 0L
            ) return true
        }
        // 证据 2：Settings.Secure 模块列表
        runCatching {
            for (key in listOf("enabled_xposed_modules", "active_xposed_modules")) {
                val raw = android.provider.Settings.Secure.getString(context.contentResolver, key)
                    ?.trim().orEmpty()
                if (raw.isEmpty()) continue
                if (raw.split(":").any {
                        it.equals(context.packageName, ignoreCase = true) || it.contains(context.packageName)
                    }
                ) return true
            }
        }
        return null
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

/**
 * 监听强制重绑修复（1.2.1，借鉴 ref/signaldock repairAccessibility）：
 * 以 shell 身份对 enabled_notification_listeners 做"摘除自身 → 写回"，
 * 强制系统重新触发 NLS 绑定——比 requestRebind 更彻底（覆盖绑定卡死场景）。
 * shell（Shizuku）与 root 天然持有 WRITE_SECURE_SETTINGS，无需额外授权。
 */
object ListenerRepair {

    private val SVC = "${cc.ytdttj.noticleaner.BuildConfig.APPLICATION_ID}" +
        "/cc.ytdttj.noticleaner.notify.CleanerListenerService"

    /** @return 修复过程日志；抛异常表示失败 */
    suspend fun repair(executor: ShellExecutor): String {
        val sb = StringBuilder()
        val get = executor.exec("settings get secure enabled_notification_listeners")
            .trim().removeSuffix("null").trim()
        sb.append("$ settings get secure enabled_notification_listeners").appendLine().appendLine(get).appendLine()
        val entries = get.split(':').filter { it.isNotBlank() && !it.equals(SVC, ignoreCase = true) }
        val without = entries.joinToString(":")
        if (without != get) {
            val cmd = "settings put secure enabled_notification_listeners \"$without\""
            sb.appendLine("$ $cmd")
            executor.exec(cmd)
            kotlinx.coroutines.delay(350)
        }
        val restored = if (without.isBlank()) SVC else "$without:$SVC"
        val put = "settings put secure enabled_notification_listeners \"$restored\""
        sb.appendLine("$ $put")
        executor.exec(put)
        sb.appendLine("$ cmd notification allow_listener $SVC")
        sb.appendLine(executor.exec("cmd notification allow_listener $SVC"))

        // 2.0.1 Dev 3：系统侧诊断——M332BF/Android 17 上设置写回成功但系统始终不绑定
        // （listener onCreate 出现 0 次），且导出日志只含应用自身 tag，看不到 NMS 视角。
        // 抓取 NMS 对监听器的真实视图（requested/live/snoozed）与包状态，随修复日志落盘。
        runCatching {
            sb.appendLine("---- 系统侧诊断（dumpsys notification 过滤） ----")
            val dump = executor.exec(
                "dumpsys notification | grep -iE \"listener|ManagedServices\" | grep -iE \"noticleaner|requested|live|snoozed|disabled|ManagedServices:\" | head -n 60",
            )
            sb.appendLine(dump.ifBlank { "(dumpsys 无匹配行)" })
            val pkg = executor.exec(
                "dumpsys package cc.ytdttj.noticleaner | grep -iE \"versionName|enabled=|stopped=|installerPackageName\" | head -n 8",
            )
            sb.appendLine("---- 包状态 ----").appendLine(pkg.ifBlank { "(无输出)" })
        }.onFailure {
            sb.appendLine("系统侧诊断失败: $it")
        }
        return sb.toString().trim()
    }
}
