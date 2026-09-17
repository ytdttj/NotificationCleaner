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

    fun start(scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)) {
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
    }

    fun isWhitelisted(packageName: String): Boolean = packageName in whitelist

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
}
