package cc.ytdttj.noticleaner.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 通知决策类型 */
const val DECISION_PASSED = "PASSED"
const val DECISION_FILTERED_BY_AI = "FILTERED_BY_AI"
const val DECISION_FILTERED_BY_RULE = "FILTERED_BY_RULE"
const val DECISION_MANUAL_MARKED_AD = "MANUAL_MARKED_AD"

// 保护型决策：默认不参与过滤，仅入库留档并在 UI 标注来源
const val DECISION_WHITELIST = "WHITELIST" // 白名单 APP（跳过 AI 过滤，规则仍生效）
const val DECISION_MEDIA = "MEDIA" // 媒体通知（音乐/视频播放控件）
const val DECISION_CONVERSATION = "CONVERSATION" // 对话通知（MessagingStyle 等）
const val DECISION_ONGOING = "ONGOING" // 常驻通知（进度条、来电）

/**
 * 通知历史（Plan.md §4）。
 * expireAt = postTime + 7 天；learned=true 的通知永久保留（查询时忽略 expireAt）。
 *
 * 索引（ImprovePlan P0-1）：决策热路径每条通知必查 findByKey / findRecentDuplicate，
 * 且 purgeExpired / listLearnedOnce 按条件过滤——无索引时全部全表扫描。
 */
@Entity(
    tableName = "notifications",
    indices = [
        androidx.room.Index("key"),
        androidx.room.Index("packageName", "title", "content", "postTime"),
        androidx.room.Index("expireAt"),
        androidx.room.Index("learned"),
    ],
)
data class NotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appName: String,
    val channelId: String,
    val channelName: String,
    val title: String,
    val content: String,
    val postTime: Long,
    val adProbability: Float,
    val decision: String,
    val learned: Boolean = false,
    val learnLabel: Int = -1, // 学习方向：1=广告 0=正常 -1=未学习
    val learnCount: Int = 0, // 同一通知被重复学习的次数（1.1.11：重复学习加权）
    val expireAt: Long,
    val key: String = "", // 通知系统 sbn.key，撤销过滤时重新展示用不到，仅留档
)
