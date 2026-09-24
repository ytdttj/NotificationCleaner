package cc.ytdttj.noticleaner.notify

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 控制中心磁贴：一键修复通知监听（1.4.0 Dev 15）。
 *
 * - Root / Shizuku 可用：点击直接执行"摘除 → 写回"强制重绑，不离开当前界面
 * - 普通用户：跳转通知监听权限页，并 Toast 提示"取消勾选后重新勾选"
 *
 * 磁贴为 ACTIVE_TILE（manifest meta-data），系统在展开面板时即回调 onStartListening，
 * 因此状态（已连接/未授权/已失效）可实时反映。
 */
class ListenerRepairTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val executor = ListenerRepairActionReceiver.shellExecutor()
        if (executor == null) {
            // 普通用户：跳权限页 + 手动重授提示（先提示再跳，避免被系统页盖住）
            toast("请取消勾选本应用后重新勾选，即可恢复通知监听")
            runCatching {
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    // 34+：startActivityAndCollapse(Intent) 已废弃，改收 PendingIntent 重载
                    val pi = android.app.PendingIntent.getActivity(
                        this, 0, intent,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
                    )
                    startActivityAndCollapse(pi)
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(intent)
                }
            }
            return
        }

        // 有 shell 权限：直接修，不跳界面
        qsTile?.state = Tile.STATE_UNAVAILABLE
        qsTile?.updateTile()
        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
        scope.launch {
            val msg = runCatching {
                ListenerRepair.repair(executor)
                "已执行监听修复，稍后自动重绑"
            }.getOrElse { "修复失败：${it.message}" }
            toast(msg)
            ListenerAlertNotifier.resetCooldown(applicationContext)
            runCatching {
                CleanerListenerService.requestRebindIfEnabled(applicationContext)
            }
            refresh()
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val enabled = CleanerListenerService.isListenerEnabled(this)
        val connected = CleanerListenerService.isListenerConnected()
        tile.state = when {
            !enabled -> Tile.STATE_INACTIVE
            connected -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = "通知监听修复"
        tile.subtitle = when {
            !enabled -> "未授权"
            connected -> "已连接"
            else -> "已失效·点击修复"
        }
        tile.updateTile()
    }

    private fun toast(msg: String) {
        runCatching { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }
}
