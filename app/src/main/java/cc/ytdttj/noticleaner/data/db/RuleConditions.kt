package cc.ytdttj.noticleaner.data.db

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 规则条件匹配模式（1.0.2 扩展，语义对齐通知滤盒）：
 * - ALL_TEXT        所有内容（恒命中，用于整 APP 过滤）
 * - ANY_TEXT        包括任一文本（多行值，包含其一即命中）
 * - ALL_TEXTS       包括全部文本（所有值都包含才命中）
 * - MISSING_ANY     缺少任一文本（未包含全部值即命中）
 * - EXCLUDE_ALL     排除全部文本（所有值都不包含才命中）
 * - INCLUDE_EXCLUDE 包括 A 且不包括 B（第 1 行 = A，其余行 = B 任一出现则不命中）
 * - EQUALS_ANY      完全等于任一文本（整段文本与某一行完全相同）
 * - REGEX_ANY       匹配任一正则（任一正则命中即命中，非法正则跳过）
 */
object MatchMode {
    const val ALL_TEXT = "ALL_TEXT"
    const val ANY_TEXT = "ANY_TEXT"
    const val ALL_TEXTS = "ALL_TEXTS"
    const val MISSING_ANY = "MISSING_ANY"
    const val EXCLUDE_ALL = "EXCLUDE_ALL"
    const val INCLUDE_EXCLUDE = "INCLUDE_EXCLUDE"
    const val EQUALS_ANY = "EQUALS_ANY"
    const val REGEX_ANY = "REGEX_ANY"

    val all = listOf(
        ALL_TEXT to "所有内容",
        ANY_TEXT to "包括任一文本",
        ALL_TEXTS to "包括全部文本",
        MISSING_ANY to "缺少任一文本",
        EXCLUDE_ALL to "排除全部文本",
        INCLUDE_EXCLUDE to "包括 A 且不包括 B",
        EQUALS_ANY to "完全等于任一文本",
        REGEX_ANY to "匹配任一正则",
    )

    fun label(mode: String): String = all.firstOrNull { it.first == mode }?.second ?: mode
}

/** 单个条件：字段（TITLE/CONTENT）+ 模式 + 文本值列表（多行，每行一个值） */
@Serializable
data class RuleCondition(
    val field: String, // MATCH_TITLE / MATCH_CONTENT
    val mode: String,
    val values: List<String>,
)

/** 条件集合 + 条件间关系（AND=并且 / OR=或者），JSON 持久化在 rules.conditions 列 */
@Serializable
data class RuleConditionSet(
    val join: String = "AND", // AND / OR
    val conditions: List<RuleCondition>,
) {
    fun toJson(): String = Json.encodeToString(this)

    companion object {
        const val AND_JOIN = "AND"
        const val OR_JOIN = "OR"

        private val json = Json { ignoreUnknownKeys = true }
        fun fromJson(raw: String): RuleConditionSet? = runCatching {
            json.decodeFromString<RuleConditionSet>(raw)
        }.getOrNull()
    }
}

/** 条件求值（标题/内容传入已 trim 的原文） */
object ConditionEvaluator {

    fun evalSet(set: RuleConditionSet, title: String, content: String): Boolean {
        if (set.conditions.isEmpty()) return false
        return if (set.join == "OR") set.conditions.any { eval(it, title, content) }
        else set.conditions.all { eval(it, title, content) }
    }

    fun eval(c: RuleCondition, title: String, content: String): Boolean {
        val text = if (c.field == MATCH_TITLE) title else content
        val values = c.values.map { it.trim() }.filter { it.isNotEmpty() }
        return evalValues(c.field, c.mode, values, title, content)
    }

    /** 与 [eval] 相同语义，但 values/正则由调用方预编译（ImprovePlan P1-1：热路径零解析零编译） */
    fun evalValues(field: String, mode: String, values: List<String>, title: String, content: String): Boolean {
        val text = if (field == MATCH_TITLE) title else content
        return when (mode) {
            MatchMode.ALL_TEXT -> true // 所有内容：该字段存在即命中（空文本不命中）
                .let { if (text.isEmpty()) false else it }
            MatchMode.ANY_TEXT -> values.any { text.contains(it, ignoreCase = true) }
            MatchMode.ALL_TEXTS -> values.isNotEmpty() && values.all { text.contains(it, ignoreCase = true) }
            MatchMode.MISSING_ANY -> values.isNotEmpty() && !values.all { text.contains(it, ignoreCase = true) }
            MatchMode.EXCLUDE_ALL -> values.isNotEmpty() && values.none { text.contains(it, ignoreCase = true) }
            MatchMode.INCLUDE_EXCLUDE -> {
                if (values.isEmpty()) return false
                val include = values.first()
                val excludes = values.drop(1)
                text.contains(include, ignoreCase = true) && excludes.none { text.contains(it, ignoreCase = true) }
            }
            MatchMode.EQUALS_ANY -> values.any { text.equals(it, ignoreCase = true) }
            MatchMode.REGEX_ANY -> values.any { v ->
                runCatching { Regex(v).containsMatchIn(text) }.getOrDefault(false)
            }
            else -> false
        }
    }
}

/**
 * 预编译条件（ImprovePlan P1-1）：values 预 trim 过滤、REGEX_ANY 预编译正则。
 * 语义与 [ConditionEvaluator.eval] 完全一致（非法正则按原语义跳过该项）。
 */
class CompiledCondition(
    private val field: String, // MATCH_TITLE / MATCH_CONTENT
    private val mode: String,
    private val values: List<String>, // 已 trim、已滤空
    private val regexes: List<Regex>?, // 仅 REGEX_ANY 非空
) {
    fun eval(title: String, content: String): Boolean {
        val text = if (field == MATCH_TITLE) title else content
        return when (mode) {
            MatchMode.ALL_TEXT -> text.isNotEmpty()
            MatchMode.ANY_TEXT -> values.any { text.contains(it, ignoreCase = true) }
            MatchMode.ALL_TEXTS -> values.isNotEmpty() && values.all { text.contains(it, ignoreCase = true) }
            MatchMode.MISSING_ANY -> values.isNotEmpty() && !values.all { text.contains(it, ignoreCase = true) }
            MatchMode.EXCLUDE_ALL -> values.isNotEmpty() && values.none { text.contains(it, ignoreCase = true) }
            MatchMode.INCLUDE_EXCLUDE -> {
                if (values.isEmpty()) false
                else text.contains(values.first(), ignoreCase = true) &&
                    values.drop(1).none { text.contains(it, ignoreCase = true) }
            }
            MatchMode.EQUALS_ANY -> values.any { text.equals(it, ignoreCase = true) }
            MatchMode.REGEX_ANY -> regexes?.any { it.containsMatchIn(text) } ?: false
            else -> false
        }
    }
}

/** 规则预编译入口（RuleEngine 缓存构建用，低频路径） */
object RuleCompiler {

    /** 单条件 → 预编译条件 */
    fun compile(c: RuleCondition): CompiledCondition {
        val values = c.values.map { it.trim() }.filter { it.isNotEmpty() }
        val regexes = if (c.mode == MatchMode.REGEX_ANY) {
            values.mapNotNull { v -> runCatching { Regex(v) }.getOrNull() }
        } else {
            null
        }
        return CompiledCondition(c.field, c.mode, values, regexes)
    }

    /** 条件集合 → 预编译条件列表（保留 AND/OR 关系由调用方读取 set.join） */
    fun compile(set: RuleConditionSet): List<CompiledCondition> = set.conditions.map { compile(it) }
}
