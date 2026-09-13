package cc.ytdttj.noticleaner.notify

import cc.ytdttj.noticleaner.data.db.ConditionEvaluator
import cc.ytdttj.noticleaner.data.db.MATCH_TITLE
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
 */
class RuleEngine(
    private val dao: RuleDao,
    private val whitelistDao: WhitelistDao,
) {
    @Volatile
    private var rules: List<RuleEntity> = emptyList()

    @Volatile
    private var whitelist: Set<String> = emptySet()

    fun start(scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)) {
        dao.listAll()
            .onEach { rules = it }
            .launchIn(scope)
        whitelistDao.listAll()
            .onEach { whitelist = it.map { w -> w.packageName }.toSet() }
            .launchIn(scope)
    }

    fun isWhitelisted(packageName: String): Boolean = packageName in whitelist

    /** @return 命中的规则，未命中返回 null。新条件集合 / 旧关键字均支持 */
    fun match(packageName: String, title: String, content: String): RuleEntity? {
        val snapshot = rules
        for (rule in snapshot) {
            if (!rule.enabled || rule.packageName != packageName) continue
            val cs = rule.conditionSet()
            if (cs.conditions.isNotEmpty() && ConditionEvaluator.evalSet(cs, title, content)) return rule
        }
        return null
    }
}
