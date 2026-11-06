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
 * - 不可恢复的高代价通知（P1-9）：闹钟/日历提醒（CATEGORY_ALARM/REMINDER/EVENT）、
 *   导航/进度/系统服务（CATEGORY_NAVIGATION/PROGRESS/SERVICE/SYSTEM）——
 *   误判即"删掉且找不回"（错过闹钟、错过日程），宁可漏放不可误杀
 * - 前台服务通知（P1-10）：Android 30+ 的 FLAG_FOREGROUND_SERVICE——误拦他人 FGS
 *   通知会让对方 App 因"未及时发通知"被系统处理
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
        // P1-10：前台服务通知（API 29+，minSdk 33 直接引用）
        if (notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return DECISION_ONGOING
        if (notification.category == Notification.CATEGORY_CALL) return DECISION_ONGOING

        // P1-9：闹钟 / 提醒 / 日历事件——误拦不可恢复，全部放行
        when (notification.category) {
            Notification.CATEGORY_ALARM,
            Notification.CATEGORY_REMINDER,
            Notification.CATEGORY_EVENT,
            Notification.CATEGORY_NAVIGATION,
            Notification.CATEGORY_PROGRESS,
            Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_SYSTEM,
            -> return DECISION_ONGOING
        }

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

        // 2.2.1 Dev 1：文本级会话识别兜底。
        // 抖音/闲鱼/小红书等私信走裸 mipush，上面四个结构性信号一个都不带，
        // 会被丢给 AI 打分，而「私信了你: [分享视频]」这类文本被 AI 判到 0.98（误拦真人私信）。
        // 这里用会话句式兜底（见 [ConversationTextMatcher]），命中即判会话、跳过 AI。
        val textTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val textContent = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        if (ConversationTextMatcher.isConversation(textTitle, textContent)) return DECISION_CONVERSATION

        return null
    }
}
