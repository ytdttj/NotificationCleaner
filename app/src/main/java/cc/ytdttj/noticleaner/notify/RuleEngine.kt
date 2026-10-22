package cc.ytdttj.noticleaner.notify

import cc.ytdttj.noticleaner.data.db.CompiledCondition
import cc.ytdttj.noticleaner.data.db.RuleCompiler
import cc.ytdttj.noticleaner.data.db.RuleConditionSet
import cc.ytdttj.noticleaner.data.db.RuleDao
import cc.ytdttj.noticleaner.data.db.RuleEntity
import cc.ytdttj.noticleaner.data.db.WhitelistDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * 手动规则引擎（Plan.md §4）：按 APP + 标题/内容关键字过滤；
 * 附带白名单集合（白名单 APP 跳过 AI 过滤，规则仍生效）。
 * 缓存通过 Flow 持续同步，监听服务同步查询零等待。
 *
 * 1.2.0（ImprovePlan P1-1）：规则 Flow 更新时一次性预编译（JSON 解析 + trim + 正则编译），
 * 通知匹配路径直接遍历编译快照，消除每条通知 × 每条规则的重复解析/编译开销。
 */
class RuleEngine(
    private val dao: RuleDao,
    private val whitelistDao: WhitelistDao,
) {
    /** 编译后的规则快照（rules 变更时重建，低频） */
    private class CompiledRule(
        val rule: RuleEntity,
        val join: String,
        val conditions: List<CompiledCondition>,
    )

    @Volatile
    private var compiled: List<CompiledRule> = emptyList()

    @Volatile
    private var whitelist: Set<String> = emptySet()

    /** 渠道豁免集合（2.2.1 Dev 1）：元素形态 `pkg|channelId`，见 [channelAllowKey] */
    @Volatile
    private var allowedChannels: Set<String> = emptySet()

    /** 渠道一律拦截集合（2.2.1 vc86）：与豁免集合互斥（SettingsRepository.blockChannel 保证） */
    @Volatile
    private var blockedChannels: Set<String> = emptySet()

    fun start(
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        channelAllowlist: Flow<Set<String>>? = null,
        channelBlocklist: Flow<Set<String>>? = null,
    ) {
        dao.listAll()
            .onEach { rules ->
                compiled = rules.map { rule ->
                    val set = rule.conditionSet()
                    CompiledRule(rule, set.join, RuleCompiler.compile(set))
                }
            }
            .launchIn(scope)
        whitelistDao.listAll()
            .onEach { whitelist = it.map { w -> w.packageName }.toSet() }
            .launchIn(scope)
        // 2.2.1 Dev 1：渠道豁免（「此渠道不再拦截」）——与规则/白名单同样走 Flow 热同步
        channelAllowlist?.let { flow ->
            flow.onEach { allowedChannels = it }.launchIn(scope)
        }
        // 2.2.1 vc86：渠道一律拦截（「此渠道一律拦截」）——同为 Flow 热同步
        channelBlocklist?.let { flow ->
            flow.onEach { blockedChannels = it }.launchIn(scope)
        }
    }

    fun isWhitelisted(packageName: String): Boolean = packageName in whitelist

    /**
     * 渠道豁免（2.2.1 Dev 1）：该 APP 的该通知渠道被用户显式设为「不再拦截」。
     *
     * 与 APP 白名单同级（都是"跳过 AI 过滤"），但粒度是**渠道**——用于解决
     * 「同一渠道既发私信又发广告、模型逐条学不过来」的场景（抖音私信即此类）。
     */
    fun isChannelAllowed(packageName: String, channelId: String): Boolean =
        channelId.isNotEmpty() && channelAllowKey(packageName, channelId) in allowedChannels

    /**
     * 渠道一律拦截（2.2.1 vc86）：该渠道被用户显式设为「一律拦截」。
     *
     * 优先级（NLS 与模块端一致）：**规则 > 渠道拦截 > APP 白名单 > 渠道豁免 > AI** ——
     * 渠道级拦截是最具体的用户意图，压过 App 级白名单；豁免与拦截两集合互斥
     * （[cc.ytdttj.noticleaner.data.SettingsRepository.blockChannel] 保证）。
     */
    fun isChannelBlocked(packageName: String, channelId: String): Boolean =
        channelId.isNotEmpty() && channelAllowKey(packageName, channelId) in blockedChannels

    /** @return 命中的规则，未命中返回 null。遍历预编译快照，热路径零解析零编译 */
    fun match(packageName: String, title: String, content: String): RuleEntity? {
        val snapshot = compiled
        for (cr in snapshot) {
            val rule = cr.rule
            if (!rule.enabled || rule.packageName != packageName) continue
            if (cr.conditions.isEmpty()) continue
            val hit = if (cr.join == RuleConditionSet.OR_JOIN) {
                cr.conditions.any { it.eval(title, content) }
            } else {
                cr.conditions.all { it.eval(title, content) }
            }
            if (hit) return rule
        }
        return null
    }

    companion object {
        /**
         * 渠道豁免键（2.2.1 Dev 1）：`pkg|channelId`。
         *
         * 用**原始字符串**而非 [cc.ytdttj.noticleaner.ai.FeatureHasher.channelKey] 的哈希值，
         * 原因：① 设置/模块配置里可读、可导出排查；② 避免 2^18 桶碰撞把「另一个渠道」一起豁免。
         * 包名不含 `|`，故按**第一个** `|` 切分即可正确还原。
         */
        fun channelAllowKey(packageName: String, channelId: String): String =
            "$packageName|$channelId"

        /** 从豁免键还原包名（空键返回空串） */
        fun packageOf(key: String): String = key.substringBefore('|')

        /** 从豁免键还原渠道 ID */
        fun channelIdOf(key: String): String = key.substringAfter('|', "")
    }
}
