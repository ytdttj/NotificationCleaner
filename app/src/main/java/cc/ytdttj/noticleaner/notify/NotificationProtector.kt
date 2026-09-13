package cc.ytdttj.noticleaner.notify

import android.app.Notification
import cc.ytdttj.noticleaner.data.db.DECISION_CONVERSATION
import cc.ytdttj.noticleaner.data.db.DECISION_MEDIA
import cc.ytdttj.noticleaner.data.db.DECISION_ONGOING

/**
 * 内置通知类型保护（默认不过滤）：
 * - 媒体通知：音乐/视频 APP 的播放控件（MediaStyle / MediaSession / category=transport）
 * - 对话通知：各类会话通知（category=msg / MessagingStyle / 会话标题与消息列表）
 * - 常驻通知：进度条、来电等（FLAG_ONGOING_EVENT / category=call）
 *
 * 命中返回对应决策常量（仅入库留档），未命中返回 null。
 * 白名单逻辑在 [RuleEngine] 中（APP 级，跳过 AI 过滤，规则仍生效）。
 */
object NotificationProtector {

    fun classifyType(notification: Notification?): String? {
        notification ?: return null
        val extras = notification.extras

        // 常驻：进度条 / 来电 / 下载进度等
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return DECISION_ONGOING
        if (notification.category == Notification.CATEGORY_CALL) return DECISION_ONGOING

        // 媒体：播放控件（Android 无 CATEGORY_MEDIA，媒体会话通知用 CATEGORY_TRANSPORT）
        if (notification.category == Notification.CATEGORY_TRANSPORT) return DECISION_MEDIA
        val template = extras.getString(Notification.EXTRA_TEMPLATE).orEmpty()
        if (template.contains("MediaStyle")) return DECISION_MEDIA
        if (extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return DECISION_MEDIA

        // 对话：MessagingStyle / 消息类
        if (notification.category == Notification.CATEGORY_MESSAGE) return DECISION_CONVERSATION
        if (template.contains("MessagingStyle")) return DECISION_CONVERSATION
        if (extras.containsKey(Notification.EXTRA_CONVERSATION_TITLE)) return DECISION_CONVERSATION
        if (extras.containsKey(Notification.EXTRA_MESSAGES)) return DECISION_CONVERSATION

        return null
    }
}
