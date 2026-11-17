package cc.ytdttj.noticleaner.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 匹配字段 */
const val MATCH_TITLE = "TITLE"
const val MATCH_CONTENT = "CONTENT"

/**
 * 手动规则（1.0.2 条件扩展）：
 * - conditions: JSON 条件集合（RuleConditionSet），非空时按其求值
 * - keyword/matchField: 旧版单关键字（conditions 为空时的兼容回退）
 */
@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appName: String,
    val keyword: String = "",
    val matchField: String = MATCH_TITLE,
    val conditions: String? = null, // RuleConditionSet JSON
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
) {
    /** 取条件集合（旧规则自动转换为新结构，便于统一编辑/求值） */
    fun conditionSet(): RuleConditionSet {
        conditions?.let { RuleConditionSet.fromJson(it)?.let { cs -> return cs } }
        if (keyword.isNotBlank()) {
            return RuleConditionSet(
                join = "AND",
                conditions = listOf(RuleCondition(matchField, MatchMode.ANY_TEXT, listOf(keyword))),
            )
        }
        return RuleConditionSet(conditions = emptyList())
    }
}

/**
 * 白名单：白名单内的 APP 通知不会被 AI 过滤（手动规则仍生效）。
 */
@Entity(tableName = "whitelist")
data class WhitelistEntity(
    @PrimaryKey val packageName: String,
    val appName: String,
    val createdAt: Long = System.currentTimeMillis(),
)
