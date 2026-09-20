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

    /**
     * 历史列表（1.3.2 P2-5：筛选下推 SQL）——tab 切换用 flatMapLatest 选择对应查询，
     * 每次 DB 变更只重查/重映当前 tab 的行（原来每条变更都重查 500 行再内存过滤）；
     * 搜索 200ms 防抖；显式 flowOn + distinctUntilChanged。
     * 收益边界：各 tab 仍受 LIMIT 500 约束（"已过滤"展示的是最新 500 条过滤项，非全部）。
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
        ) { rows, q ->
            if (q.isBlank()) rows
            else rows.filter {
                it.appName.contains(q, true) ||
                    it.title.contains(q, true) ||
                    it.content.contains(q, true) ||
                    it.packageName.contains(q, true)
            }
        }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selected = MutableStateFlow<NotificationEntity?>(null)
    val selected: StateFlow<NotificationEntity?> = _selected

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast

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
            val labels = dao.listLearnedOnce()
            if (labels.isNotEmpty() && modelRepo.tunedFingerprint() != modelRepo.baseFingerprint()) {
                refit()
            }
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
            _toast.value = when {
                label == 1 && sameDirection -> "已重复学习（第 ${n.learnCount + 1} 次），权重已加强"
                label == 1 -> "已学习为广告通知并清除"
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
        val samples = labels.map {
            SpamTuner.Sample(
                text = listOf(it.title, it.content).filter { s -> s.isNotEmpty() }.joinToString("\n"),
                spam = it.learnLabel == 1,
                channelKey = if (it.channelId.isNotEmpty()) {
                    cc.ytdttj.noticleaner.ai.FeatureHasher.channelKey(it.packageName, it.channelId)
                } else {
                    0
                },
                weight = maxOf(1, it.learnCount),
            )
        }
        val base = modelRepo.baseModel() ?: return labels
        val delta = SpamTuner.fit(base, samples)
        modelRepo.applyDelta(delta)
        modelRepo.setTunedFingerprint(modelRepo.baseFingerprint())

        // 用新模型刷新已学习行的概率展示（带通道偏置，与热路径决策一致）
        // P2-4：逐行 update 改单事务批量写入（N 个事务 → 1 个）
        val effective = modelRepo.get() ?: return labels
        val updated = ServiceLocator.db.withTransaction {
            labels.map {
                val text = listOf(it.title, it.content).filter { s -> s.isNotEmpty() }.joinToString("\n")
                val chKey = if (it.channelId.isNotEmpty()) {
                    cc.ytdttj.noticleaner.ai.FeatureHasher.channelKey(it.packageName, it.channelId)
                } else {
                    null
                }
                val p = effective.score(text, chKey).toFloat()
                val row = it.copy(adProbability = p)
                dao.update(row)
                row
            }
        }
        _selected.value = _selected.value?.let { sel -> updated.firstOrNull { it.id == sel.id } ?: sel }
        return updated
    }
}
