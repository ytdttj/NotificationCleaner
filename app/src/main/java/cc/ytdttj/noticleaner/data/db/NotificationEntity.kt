package cc.ytdttj.noticleaner.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 通知决策类型 */
const val DECISION_PASSED = "PASSED"
const val DECISION_FILTERED_BY_AI = "FILTERED_BY_AI"
const val DECISION_FILTERED_BY_RULE = "FILTERED_BY_RULE"
const val DECISION_MANUAL_MARKED_AD = "MANUAL_MARKED_AD"

// 1.2.1：LSPosed 模块端入队前拦截的决策（system_server 内判定，通知不会到达 NLS）
const val DECISION_FILTERED_BY_AI_MODULE = "FILTERED_BY_AI_MODULE"
const val DECISION_FILTERED_BY_RULE_MODULE = "FILTERED_BY_RULE_MODULE"

// 保护型决策：默认不参与过滤，仅入库留档并在 UI 标注来源
const val DECISION_WHITELIST = "WHITELIST" // 白名单 APP（跳过 AI 过滤，规则仍生效）
const val DECISION_MEDIA = "MEDIA" // 媒体通知（音乐/视频播放控件）
const val DECISION_CONVERSATION = "CONVERSATION" // 对话通知（MessagingStyle 等）
const val DECISION_ONGOING = "ONGOING" // 常驻通知（进度条、来电）

/** 拦截类决策全集（NLS 端 + 模块端，1.2.1）：历史"已过滤"筛选与统计计数使用 */
val FILTERED_DECISIONS = setOf(
    DECISION_FILTERED_BY_AI,
    DECISION_FILTERED_BY_AI_MODULE,
    DECISION_FILTERED_BY_RULE,
    DECISION_FILTERED_BY_RULE_MODULE,
    DECISION_MANUAL_MARKED_AD,
)

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
        // 1.3.2（P2-2）：去重索引改 contentHash（原 (packageName,title,content,postTime)
        // 存整段文本，体积大、比较 O(len)）
        androidx.room.Index("packageName", "contentHash", "postTime"),
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
    // 1.3.2（P2-2）：title+'\u0000'+content 的 64 位哈希（[contentHashOf]），60s 去重比较用。
    // defaultValue 供 v6 迁移的 ADD COLUMN DEFAULT 0（存量行填哨兵 0，见 MIGRATION_5_6）
    @androidx.room.ColumnInfo(defaultValue = "0") val contentHash: Long = 0,
)

/**
 * 1.3.2（P2-2）：去重哈希（写死实现，勿改）——title.hashCode 与 content.hashCode
 * 组合成 64 位（Int 运算直接相加只有 32 位有效熵）。碰撞概率 ~2^-64 可忽略，
 * 属正确性取舍（与 P3-5 打分缓存键同类）。
 */
fun contentHashOf(title: String, content: String): Long =
    (title.hashCode().toLong() shl 32) xor (content.hashCode().toLong() and 0xFFFFFFFFL)
