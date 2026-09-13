package cc.ytdttj.noticleaner.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机/替换安装自启（Plan.md §7.1）：检查监听绑定状态，未连接则请求重绑。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                KeepAliveService.start(context)
                CleanerListenerService.requestRebind(context)
            }
        }
    }
}
