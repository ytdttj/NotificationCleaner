package cc.ytdttj.noticleaner.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import cc.ytdttj.noticleaner.ServiceLocator
import cc.ytdttj.noticleaner.ai.SpamTuner
import cc.ytdttj.noticleaner.data.ModelRepository
import cc.ytdttj.noticleaner.data.db.DECISION_FILTERED_BY_AI
import cc.ytdttj.noticleaner.data.db.DECISION_FILTERED_BY_RULE
import cc.ytdttj.noticleaner.data.db.DECISION_MANUAL_MARKED_AD
import cc.ytdttj.noticleaner.data.db.DECISION_PASSED
import cc.ytdttj.noticleaner.data.db.FILTERED_DECISIONS
import cc.ytdttj.noticleaner.data.db.NotificationDao
import cc.ytdttj.noticleaner.data.db.NotificationEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class HistoryFilter { ALL, FILTERED, PASSED }

/**
 * 历史高级筛选（Dev 6）：App（多选，复用规则页 AppPicker，含系统应用）+ 仅看已学习 +
 * 日期范围（含起止当天）。任一条件为空/关闭即不参与过滤。
 */
data class HistoryAdvancedFilter(
    val apps: List<Pair<String, String>> = emptyList(), // pkg to label；空 = 全部 App
    val learnedOnly: Boolean = false,
    val startDate: java.time.LocalDate? = null,
    val endDate: java.time.LocalDate? = null,
) {
    val isDefault: Boolean
        get() = apps.isEmpty() && !learnedOnly && startDate == null && endDate == null
}

/**
 * 历史列表 + 详情页的共享 VM（详情含通道跳转与 AI 学习，Plan.md §6.1）。
 *
 * 学习机制（1.1.0，复刻 Notice）：
 * 标注写入 Room（learned/learnLabel）→ 用【全部标注】在冻结 base 上全量重拟合
 * 稀疏 delta（SpamTuner.fit，60 epoch）→ delta 独立落盘并叠加到生效模型。
 * base 权重永不被改写；删除标注后重新拟合即精确回滚；基线模型升级后自动重拟合。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
class HistoryViewModel(
    private val dao: NotificationDao,
    private val modelRepo: ModelRepository,
) : ViewModel() {

    val filter = MutableStateFlow(HistoryFilter.ALL)

    /** 历史搜索（匹配 App 名称/标题/内容，忽略大小写） */
    val search = MutableStateFlow("")

    /** 高级筛选（Dev 6）：App（多选）/ 已学习 / 日期范围 */
    val advancedFilter = MutableStateFlow(HistoryAdvancedFilter())

    /**
     * 历史列表（1.3.2 P2-5：筛选下推 SQL）——tab 切换用 flatMapLatest 选择对应查询，
     * 每次 DB 变更只重查/重映当前 tab 的行（原来每条变更都重查 500 行再内存过滤）；
     * 搜索 200ms 防抖；显式 flowOn + distinctUntilChanged。
     * 收益边界：各 tab 仍受 LIMIT 500 约束（"已过滤"展示的是最新 500 条过滤项，非全部）。
     * Dev 6：高级筛选（App/已学习/日期）在 500 行窗口内内存过滤。
     */
    val list: StateFlow<List<NotificationEntity>> =
        combine(
            filter.flatMapLatest { f ->
                when (f) {
                    HistoryFilter.ALL -> dao.listAll()
                    HistoryFilter.FILTERED -> dao.listByDecisions(FILTERED_DECISIONS.toList())
                    // "正常"= 未被过滤（含白名单/媒体/会话/常驻等保护型通知）
                    HistoryFilter.PASSED -> dao.listNotInDecisions(FILTERED_DECISIONS.toList())
                }
            },
            search.debounce(200),
            advancedFilter,
        ) { rows, q, adv ->
            var out = if (q.isBlank()) rows else rows.filter {
                it.appName.contains(q, true) ||
                    it.title.contains(q, true) ||
                    it.content.contains(q, true) ||
                    it.packageName.contains(q, true)
            }
            if (!adv.isDefault) out = applyAdvanced(out, adv)
            out
        }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun applyAdvanced(
        rows: List<NotificationEntity>,
        adv: HistoryAdvancedFilter,
    ): List<NotificationEntity> {
        val zone = java.time.ZoneId.systemDefault()
        val startMs = adv.startDate?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
        val endMs = adv.endDate?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
        return rows.filter { n ->
            (adv.apps.isEmpty() || adv.apps.any { it.first == n.packageName }) &&
                (!adv.learnedOnly || n.learned) &&
                (startMs == null || n.postTime >= startMs) &&
                (endMs == null || n.postTime < endMs)
        }
    }

    fun setAdvancedFilter(f: HistoryAdvancedFilter) {
        advancedFilter.value = f
    }

    private val _selected = MutableStateFlow<NotificationEntity?>(null)
    val selected: StateFlow<NotificationEntity?> = _selected

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast

    /** 已豁免渠道（2.2.1 Dev 1）：`pkg|channelId` 集合，供详情页展示「此渠道不再拦截」的当前状态 */
    private val _allowedChannels = MutableStateFlow<Set<String>>(emptySet())
    val allowedChannels: StateFlow<Set<String>> = _allowedChannels

    /** 已设为一律拦截的渠道（2.2.1 vc86）：`pkg|channelId` 集合，供详情页展示「此渠道一律拦截」的当前状态 */
    private val _blockedChannels = MutableStateFlow<Set<String>>(emptySet())
    val blockedChannels: StateFlow<Set<String>> = _blockedChannels

    fun select(n: NotificationEntity?) {
        _selected.value = n
    }

    fun setFilter(f: HistoryFilter) {
        filter.value = f
    }

    fun clearToast() {
        _toast.value = null
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            modelRepo.get() // 预热模型加载
            // 基线模型升级（assets 更新）后：用既有标注对新基线自动重新拟合
            // 2.2.1 Dev 1：**学习 schema 变更**（渠道桶护栏）同样触发一次全量重拟合——
            // 落盘的 delta 是只增不改的历史产物，不重算则本次修复对已学习过的用户不生效。
            val labels = dao.listLearnedOnce()
            if (labels.isNotEmpty() &&
                (modelRepo.tunedFingerprint() != modelRepo.baseFingerprint() || modelRepo.needsRefit())
            ) {
                refit()
            }
        }
        viewModelScope.launch {
            ServiceLocator.settings.channelAllowlist.collect { _allowedChannels.value = it }
        }
        viewModelScope.launch {
            ServiceLocator.settings.channelBlocklist.collect { _blockedChannels.value = it }
        }
    }

    /** 渠道豁免键（与 NLS / 模块端同一形态） */
    fun channelAllowKeyOf(n: NotificationEntity): String =
        cc.ytdttj.noticleaner.notify.RuleEngine.channelAllowKey(n.packageName, n.channelId)

    /**
     * 「此渠道不再拦截」（2.2.1 Dev 1）。
     *
     * 存在的理由：抖音这类「同一渠道既发私信又发广告」的场景，模型的黑白判断在渠道层面
     * 天然不可靠，普通用户需要一条**不需要理解模型**的出路。本操作只是把渠道加进豁免集合，
     * 随时可撤销（[revokeChannelAllow]），不是不可逆操作。
     */
    fun allowChannel(n: NotificationEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            if (n.channelId.isEmpty()) {
                _toast.value = "该通知没有渠道信息，无法按渠道豁免"
                return@launch
            }
            ServiceLocator.settings.allowChannel(channelAllowKeyOf(n))
            _toast.value = "已设置「此渠道不再拦截」；该渠道的通知一律放行，可随时撤销"
        }
    }

    fun revokeChannelAllow(n: NotificationEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            ServiceLocator.settings.disallowChannel(channelAllowKeyOf(n))
            _toast.value = "已恢复该渠道的 AI 过滤"
        }
    }

    /**
     * 「此渠道一律拦截」（2.2.1 vc86）——豁免的对称面。
     *
     * 面向百度网盘这类「整条渠道只发营销」的场景：逐条学习追不上（每条广告文案全新）、
     * 渠道先验被护栏封顶 —— 给用户一条**确定性**的出口，不依赖模型与学习。
     * 与豁免集合互斥（blockChannel 会同时从豁免集合移除），随时可撤销。
     */
    fun blockChannel(n: NotificationEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            if (n.channelId.isEmpty()) {
                _toast.value = "该通知没有渠道信息，无法按渠道拦截"
                return@launch
            }
            ServiceLocator.settings.blockChannel(channelAllowKeyOf(n))
            _toast.value = "已设置「此渠道一律拦截」；该渠道的通知一律清除，可随时撤销"
        }
    }

    fun revokeChannelBlock(n: NotificationEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            ServiceLocator.settings.unblockChannel(channelAllowKeyOf(n))
            _toast.value = "已恢复该渠道的 AI 过滤"
        }
    }

    /**
     * 学习标注（写入标注集后全量重拟合）。学习为广告的同时清除通知栏中的该通知。
     * 1.1.11：允许对已学习通知重复学习——同方向重复点击累积 learnCount，
     * 拟合时按重复次数加权（大幅提升权重）；换方向则重置计数。
     */
    fun learn(n: NotificationEntity, label: Int) {
        viewModelScope.launch(Dispatchers.Default) {
            val sameDirection = n.learned && n.learnLabel == label
            dao.update(
                n.copy(
                    learned = true,
                    learnLabel = label,
                    learnCount = if (sameDirection) n.learnCount + 1 else 1,
                    decision = if (label == 1) DECISION_MANUAL_MARKED_AD else DECISION_PASSED,
                ),
            )
            if (label == 1 && n.key.isNotEmpty()) {
                cc.ytdttj.noticleaner.notify.CleanerListenerService.cancelByKey(n.key)
            }
            val updated = refit()
            _selected.value = updated.firstOrNull { it.id == n.id }
                ?: n.copy(learned = true, learnLabel = label, learnCount = if (sameDirection) n.learnCount + 1 else 1)
            // 2.0.1 Dev 11：学为"正常"时若该通知仍命中用户自定义规则，规则会继续拦截
            //（规则判定优先于 AI 打分）——不提示的话用户只会觉得"学了没用"
            val hitRule = if (label == 0) {
                runCatching {
                    cc.ytdttj.noticleaner.ServiceLocator.ruleEngine
                        .match(n.packageName, n.title, n.content)
                }.getOrNull()
            } else {
                null
            }
            // 2.2.1 Dev 1：渠道级拦截提示。学习是**文本级**、拦截是**文本+渠道级**——
            // 同一渠道若还有广告标注，会整体抬高该渠道，用户逐条"学为正常"永远追不上，
            // 必须明确告诉他出路是「此渠道不再拦截」，否则只会重复无效点击。
            val mix = if (label == 0) {
                runCatching {
                    cc.ytdttj.noticleaner.data.LearningHelper
                        .channelLabelMix(dao, n.packageName, n.channelId)
                }.getOrNull()
            } else {
                null
            }
            _toast.value = when {
                label == 1 && sameDirection -> "已重复学习（第 ${n.learnCount + 1} 次），权重已加强"
                label == 1 -> "已学习为广告通知并清除"
                hitRule != null ->
                    "已学习为正常通知；但该通知命中规则「${hitRule.appName}｜${hitRule.keyword.ifBlank { "条件规则" }}」，" +
                        "规则优先于学习，仍会被拦截——请到「规则」页调整或删除该规则"
                mix != null && mix.ad > 0 ->
                    "已学习为正常通知；但该渠道还有 ${mix.ad} 条广告标注，会把本渠道整体抬高，" +
                        "光靠逐条学习可能仍被拦——如仍被拦，请点「此渠道不再拦截」"
                else -> "已学习为正常通知"
            }
        }
    }

    /** 取消学习：从标注集移除后重新拟合，精确回滚 */
    fun unlearn(n: NotificationEntity) {
        viewModelScope.launch(Dispatchers.Default) {
            dao.update(n.copy(learned = false, learnLabel = -1, learnCount = 0))
            val updated = refit()
            _selected.value = updated.firstOrNull { it.id == n.id } ?: n.copy(learned = false, learnLabel = -1, learnCount = 0)
            _toast.value = "已取消学习"
        }
    }

    /**
     * 用全部标注在冻结 base 上重新拟合稀疏 delta，叠加到生效模型，
     * 并刷新所有已学习行的概率展示。@return 重算后的已学习行。
     * 1.1.11：样本携带通道特征（同 App 同渠道偏置）与重复学习权重。
     */
    private suspend fun refit(): List<NotificationEntity> {
        val labels = dao.listLearnedOnce()
        if (labels.isNotEmpty()) {
            _toast.value = "正在拟合 ${labels.size} 条标注…" // P2-4：长拟合进度反馈，防"假死"
        }
        // 1.4.0 Dev 13：重拟合抽到 LearningHelper——统计明细页「全部学习」复用同一实现
        val updated = cc.ytdttj.noticleaner.data.LearningHelper.refit(dao, modelRepo)
        // Dev 14：拟合完成提示（此前只有"正在拟合…"，用户不知道何时结束）
        if (labels.isNotEmpty()) {
            _toast.value = "拟合完成（${labels.size} 条标注）"
        }
        _selected.value = _selected.value?.let { sel -> updated.firstOrNull { it.id == sel.id } ?: sel }
        return updated
    }
}
