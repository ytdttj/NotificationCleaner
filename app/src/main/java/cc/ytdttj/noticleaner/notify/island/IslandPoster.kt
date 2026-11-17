package cc.ytdttj.noticleaner.notify.island

import android.app.Notification
import android.app.NotificationManager
import android.content.Context

/**
 * 岛通知发送器（Dev 5 重构，替代原 IslandBypassExecutor 盲窗方案）。
 *
 * 发送协议（借鉴 ref/HyperIsland IslandDispatcherNotifier 的 clearBeforePost）：
 * 同一通知 id 先 cancel 再 notify——HyperOS 超级岛对"更新已有通知"不触发
 * 展示动画；60s 岛超时窗内同 id 重发必须先清后发。
 *
 * 可见性（借鉴 HyperIsland 的无痕做法，在 IslandParamsBuilder.build 设置）：
 * - 常规路径（showNotification=false）：VISIBILITY_SECRET + 空 publicVersion，
 *   岛照常展示、通知栏完全无痕；
 * - 测试路径（showNotification=true）：VISIBILITY_PRIVATE 留痕于通知栏，
 *   用于判别"岛被认证拒绝"（岛不出现但通知栏有记录）。
 *
 * 认证放行由 LSPosed 模块端 hook 完成（IslandUnlockFocusHook / XmsfUnlockAuthHook），
 * App 端仅负责发送，无网络层绕过。
 */
object IslandPoster {

    fun post(context: Context, notificationId: Int, notification: Notification) {
        try {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            // clearBeforePost：同 id 重发前先清（HyperOS 对"更新"不触发展示）
            runCatching { nm.cancel(notificationId) }
            nm.notify(notificationId, notification)
        } catch (t: Throwable) {
            IslandTrace.log("✗ 岛通知提交失败: $t")
        }
    }
}
