package cc.ytdttj.noticleaner.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 通知监听快速修复（1.4.0 Dev 15）：失效提醒通知的按钮与**控制中心磁贴**共用。
 *
 * - Shizuku（已授权）/ Root：直接执行"摘除自身 → 写回"强制重绑（[ListenerRepair]）
 * - 普通用户：跳转通知监听权限页 + Toast 提示手动取消勾选后重新勾选
 *
 * goAsync 只调用一次（Dev 13 排查过的崩溃点：WatchdogReceiver 曾双调 goAsync）。
 */
class ListenerRepairActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_REPAIR = "cc.ytdttj.noticleaner.action.REPAIR_LISTENER"
        const val ACTION_OPEN_SETTINGS = "cc.ytdttj.noticleaner.action.OPEN_LISTENER_SETTINGS"

        /** 是否有可直接执行命令的 shell 通道（Shizuku 优先，Root 兜底） */
        fun shellExecutor(): ShellExecutor? = when {
            runCatching {
                rikka.shizuku.Shizuku.pingBinder() &&
                    rikka.shizuku.Shizuku.checkSelfPermission() ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false) -> ShizukuExecutor
            cc.ytdttj.noticleaner.ServiceLocator.keepAlive.isRootAvailable() -> RootExecutor
            else -> null
        }

        /** 跳转通知监听权限页 */
        fun openSettings(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            ACTION_OPEN_SETTINGS -> {
                openSettings(app)
                toast(app, "请取消勾选本应用后重新勾选，即可恢复通知监听")
                return
            }
            ACTION_REPAIR -> Unit
            else -> return
        }

        val executor = shellExecutor()
        if (executor == null) {
            // 普通用户：跳权限页 + 手动重授提示
            openSettings(app)
            toast(app, "请取消勾选本应用后重新勾选，即可恢复通知监听")
            return
        }

        val pending = goAsync() // 唯一一次
        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
        scope.launch {
            try {
                val log = ListenerRepair.repair(executor)
                val first = log.lineSequence().firstOrNull { it.isNotBlank() }?.take(60) ?: ""
                toast(app, "已执行监听修复，稍后自动重绑 $first")
                ListenerAlertNotifier.resetCooldown(app)
            } catch (t: Throwable) {
                toast(app, "修复失败：$t")
            } finally {
                runCatching { pending.finish() }
            }
        }
    }

    private fun toast(context: Context, msg: String) {
        runCatching { Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
    }
}
