package cc.ytdttj.noticleaner.notify.island

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.NotificationManager

/**
 * 超级岛"已完成"按钮：点击后取消对应通知，岛随通知清除。
 * exported=false，仅接受本应用发出的 PendingIntent。
 */
class IslandDismissReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_NOTIF_ID, -1)
        if (id != -1) {
            context.getSystemService(NotificationManager::class.java).cancel(id)
        }
    }

    companion object {
        const val EXTRA_NOTIF_ID = "cc.ytdttj.noticleaner.island.NOTIF_ID"
    }
}
