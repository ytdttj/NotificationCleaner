package cc.ytdttj.noticleaner.data

import androidx.room.withTransaction
import cc.ytdttj.noticleaner.ServiceLocator
import kotlinx.coroutines.sync.withLock
import cc.ytdttj.noticleaner.ai.FeatureHasher
import cc.ytdttj.noticleaner.ai.SpamTuner
import cc.ytdttj.noticleaner.data.db.DECISION_MANUAL_MARKED_AD
import cc.ytdttj.noticleaner.data.db.DECISION_PASSED
import cc.ytdttj.noticleaner.data.db.NotificationDao
import cc.ytdttj.noticleaner.data.db.NotificationEntity

/**
 * 学习标注 / 模型重拟合的共享逻辑（1.4.0 Dev 13 从 HistoryViewModel 抽出）。
 *
 * 历史页单条学习与统计明细页「全部学习」共用同一套流程：
 * 写标注 → 用【全部标注】在冻结 base 上全量重拟合稀疏 delta → 刷新已学习行的概率展示。
 * base 权重永不被改写，重拟合结果只落在 delta 上。
 */
object LearningHelper {

    /** 重拟合串行锁（Dev 10，P1-8）：全进程只有一个 refit 在跑 */
    private val refitMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * 批量重学习结果统计：
     * adCount/normalCount = 实际重学条数；skipped = 未学习而跳过的条数（Dev 16）。
     */
    data class RelearnResult(
        val adCount: Int,
        val normalCount: Int,
        val total: Int,
        val skipped: Int,
    )

    /**
     * 按各自**原有方向**重新学习一批通知中**已学习**的部分（2.0.1 Dev 1 语义修订）。
     *
     * - 已学习为广告 → 再次学习广告（learnCount+1，广告权重**加强**）
     * - 已学习为正常 → 再次学习正常（learnCount+1，广告权重**下调**）
     * - **未学习过的条目一律跳过**（v2.0.1 修复：此前默认学为广告——「已过滤」页里
     *   躺着大量被误拦的正常通知，一键全学为广告会把模型带偏；2.0.0 升级重拟合后
     *   实测把招行扣款顶回 86% 拦截）
     *
     * 全程只做**一次**重拟合（而非每条一次），避免 N 次 SGD 拟合。
     * learnCount 上限 [SpamTuner.MAX_WEIGHT]，与单条重复学习的惯例一致。
     *
     * @return adCount/normalCount 为实际重学条数；skipped 为跳过的未学习条数
     */
    suspend fun relearnAll(
        dao: NotificationDao,
        modelRepo: ModelRepository,
        items: List<NotificationEntity>,
    ): RelearnResult {
        var ad = 0
        var normal = 0
        var skipped = 0
        for (n in items) {
            if (!n.learned) {
                // 未学习 → 跳过：学习方向必须由用户逐条确认，绝不代填“广告”
                skipped++
                continue
            }
            if (n.learnLabel == 0) normal++ else ad++
            dao.update(
                n.copy(
                    learned = true,
                    learnLabel = n.learnLabel,
                    learnCount = (n.learnCount + 1).coerceAtMost(SpamTuner.MAX_WEIGHT),
                    decision = if (n.learnLabel == 1) DECISION_MANUAL_MARKED_AD else DECISION_PASSED,
                ),
            )
        }
        if (ad + normal > 0) refit(dao, modelRepo)
        return RelearnResult(adCount = ad, normalCount = normal, total = items.size, skipped = skipped)
    }

    /**
     * 用全部标注在冻结 base 上重新拟合稀疏 delta，叠加到生效模型，
     * 并刷新所有已学习行的概率展示（带通道偏置，与热路径决策一致）。
     *
     * @return 重算后的已学习行；无标注或模型不可用时返回空/原列表。
     */
    suspend fun refit(dao: NotificationDao, modelRepo: ModelRepository): List<NotificationEntity> =
        refitMutex.withLock { refitLocked(dao, modelRepo) }

    /**
     * 重拟合本体（持锁执行）。
     *
     * 锁的作用（2.0.1 Dev 10，P1-8）：历史页单条 learn/unlearn 每次都 `launch` 新协程，
     * 快速连点会并发跑多个"读全部标注 → 60 epoch 全量拟合 → 写同一 delta 文件"，
     * 叠加非原子写时损坏概率显著放大。串行化后同一时刻只有一个 refit，
     * 且后续的重复拟合读到的是最新标注（结果收敛一致）。
     */
    private suspend fun refitLocked(dao: NotificationDao, modelRepo: ModelRepository): List<NotificationEntity> {
        val initial = dao.listLearnedOnce()
        if (initial.isEmpty()) {
            // P0-3：空标注不能只写 schema 就返回——applyDelta 是唯一写 delta 的入口，
            // 跳过它意味着"取消最后一条标注"后旧学习量仍留在内存与 spam_delta.bin 里，
            // 模型持续被已撤销的标注带偏（README 承诺"取消标注可精确回滚"）。
            // 补一次空 delta：磁盘 delta 文件被删除、effective 回到 base、deltaVersion
            // 刷新 → 模块端收到空 delta 回到基线。指纹/schema 一并登记为当前值，
            // 避免 P0-6 的指纹比对反复误判"需要重拟合"。
            val base = modelRepo.baseModel()
            if (base != null) {
                modelRepo.applyDelta(cc.ytdttj.noticleaner.ai.SpamDelta.empty(base.buckets))
                modelRepo.setTunedFingerprint(modelRepo.baseFingerprint())
            }
            // 无标注：也要把 schema 标记为最新，避免每次启动都空跑一趟检查
            modelRepo.setLearningSchema(ModelRepository.LEARNING_SCHEMA)
            return emptyList()
        }
        // 2.3.0 Dev 1：学习内容迁移。schema 4 → 5 时，把旧版「学整条通知」的标注
        // 自动改写成新方式的「学模板」——含 `@ + 详情>>` 等结构组合的旧标注会直接
        // 转成 STRUCT 学习重点并落库，用户不需要重新点一遍。
        val labels = if (modelRepo.learningSchema() < ModelRepository.LEARNING_SCHEMA) {
            migrateLearnedFocus(dao, initial)
        } else {
            initial
        }
        // 结构组合的双向统计（按整条文本）：用于「混杂组合不建先验」的动态护栏，
        // 也用于未显式选模板的旧标注自动回填（同组合 ≥2 条独立标注才回填）。
        val combos = comboStats(labels)
        val samples = labels.map { n ->
            val isAd = n.learnLabel == 1
            val structFocus = n.learnFocusKind == cc.ytdttj.noticleaner.data.db.LEARN_FOCUS_STRUCT
            val explicitKey = if (structFocus) ComboKey(n.packageName, n.learnFocus) else null
            val wholeKey = comboKeyOf(n)
            val wholeCount = wholeKey?.let { (combos[it]?.get(0) ?: 0) + (combos[it]?.get(1) ?: 0) } ?: 0
            val useKey = when {
                // 显式模板：只要当前不是双向混杂就生效（混杂时退回整条文本）
                explicitKey != null && oneSided(combos, explicitKey, isAd) -> explicitKey
                // 旧标注自动回填：同组合 ≥2 条独立标注且方向一致
                wholeKey != null && wholeCount >= COMBO_BACKFILL_MIN_SAMPLES &&
                    oneSided(combos, wholeKey, isAd) -> wholeKey
                else -> null
            }
            SpamTuner.Sample(
                // 2.3.0 Dev 1：优先只学用户勾选的「学习重点」，空串回退整条通知。
                // 背景：整条唯一文案只会记住这一条（比如微博某条 @ 的具体内容），
                // 下一批换措辞的同类推送拿不到任何泛化；只学重复出现的标题/关键词才有效。
                // STRUCT 样本不携带文本 n-gram——只学结构组合，避免把整条唯一文案一起记进去。
                text = when {
                    structFocus && useKey != null -> ""
                    // 模板当前双向混杂：退回整条文本，绝不能把 `at+detail_gt` 当文本学
                    structFocus -> wholeText(n)
                    n.learnFocus.isNotBlank() -> n.learnFocus
                    else -> wholeText(n)
                },
                spam = isAd,
                channelKey = if (n.channelId.isNotEmpty()) {
                    FeatureHasher.channelKey(n.packageName, n.channelId)
                } else {
                    0
                },
                appKey = FeatureHasher.appKey(n.packageName),
                weight = maxOf(1, n.learnCount),
                structKeys = useKey?.let {
                    FeatureHasher.structKeysForMarks(it.pkg, FeatureHasher.structMarksOfId(it.combo))
                } ?: emptyList(),
            )
        }
        val base = modelRepo.baseModel() ?: return labels
        val delta = SpamTuner.fit(base, samples)
        // Dev 9：重拟合落环形日志（MODEL 模块，覆盖 24 小时）——排查"升级后误杀"时，
        // 需要知道何时用多少标注重拟合过（标注方向错误会在此步被放大）
        cc.ytdttj.noticleaner.diagnostics.RingLog.log(
            cc.ytdttj.noticleaner.diagnostics.LogModules.MODEL,
            "重拟合 delta：${samples.size} 条标注（广告 ${samples.count { it.spam }} / " +
                "正常 ${samples.count { !it.spam }}）",
        )
        modelRepo.applyDelta(delta)
        modelRepo.setTunedFingerprint(modelRepo.baseFingerprint())
        // 2.2.1 Dev 1：记录本次拟合所用的学习 schema，避免老 delta 被反复认为"需要迁移"
        modelRepo.setLearningSchema(ModelRepository.LEARNING_SCHEMA)

        // 用新模型刷新已学习行的概率展示。
        // 注意：这里刻意用**整条通知**打分（而非 learnFocus）——该行展示的是
        // 「这条通知在生效模型下的真实广告概率」，只学关键词不代表整条文本就该高分。
        val effective = modelRepo.get() ?: return labels
        val updated = ServiceLocator.db.withTransaction {
            labels.map {
                val text = wholeText(it)
                val chKey = if (it.channelId.isNotEmpty()) {
                    FeatureHasher.channelKey(it.packageName, it.channelId)
                } else {
                    null
                }
                // 2.3.0 Dev 1：回算必须带上结构桶，否则「学过的模板」在历史页看不到效果
                val structKeys = FeatureHasher.structKeysForText(text, it.packageName)
                val p = effective.score(
                    text,
                    FeatureHasher.appKey(it.packageName),
                    chKey,
                    structKeys,
                ).toFloat()
                val row = it.copy(adProbability = p)
                dao.update(row)
                row
            }
        }
        return updated
    }

    /** 整条通知文本（title + content），学习/打分/回算共用同一拼接口径 */
    private fun wholeText(n: NotificationEntity): String =
        listOf(n.title, n.content).filter { s -> s.isNotEmpty() }.joinToString("\n")

    /** 历史组合回填所需的最少独立标注条数（按通知条数，不按 learnCount） */
    private const val COMBO_BACKFILL_MIN_SAMPLES = 2

    /** 结构组合的标识：App + 规范组合 id（如 `com.sina.weibo` + `at+detail_gt`） */
    private data class ComboKey(val pkg: String, val combo: String)

    /**
     * 某条通知**整条文本**命中的首选结构组合（`@ + 详情>>` 优先），
     * 少于 2 个标记时返回 null（单标记永不参与自动迁移/回填）。
     */
    private fun comboKeyOf(n: NotificationEntity): ComboKey? {
        val marks = FeatureHasher.structMarksOf(wholeText(n))
        if (marks.size < 2) return null
        val combo = FeatureHasher.preferredStructCombo(marks)
        if (combo.size < 2) return null
        return ComboKey(n.packageName, FeatureHasher.structComboId(combo))
    }

    /** 结构组合的双向标注统计：[0]=广告条数，[1]=正常条数（按通知条数，不按 learnCount） */
    private fun comboStats(labels: List<NotificationEntity>): Map<ComboKey, IntArray> {
        val out = HashMap<ComboKey, IntArray>()
        for (n in labels) {
            val key = comboKeyOf(n) ?: continue
            val arr = out.getOrPut(key) { IntArray(2) }
            if (n.learnLabel == 1) arr[0]++ else arr[1]++
        }
        return out
    }

    /** 组合是否方向一致（该方向 ≥1 条、另一方向 0 条）——两侧都有标注视为混杂，不建先验 */
    private fun oneSided(stats: Map<ComboKey, IntArray>, key: ComboKey, isAd: Boolean): Boolean {
        val arr = stats[key] ?: return false
        return if (isAd) arr[1] == 0 else arr[0] == 0
    }

    /** 迁移计划：某条旧标注应改成的新学习重点（kind + focus） */
    internal data class FocusPlan(val kind: String, val focus: String)

    /**
     * 纯决策：为一批旧标注计算迁移计划（不写库，便于单测覆盖）。
     *
     * - 整条文本命中 ≥2 个结构标记（如 `@` + `详情>>`）且该组合方向一致 → STRUCT；
     * - 已有文本重点（2.3.0 Dev 1 用户选择）→ 保留选择，补记 TEXT；
     * - 无结构模板的标注 → 不迁移（维持整条文本学习），因为没有可安全自动提取的模板。
     */
    internal fun planFocusMigration(labels: List<NotificationEntity>): Map<Long, FocusPlan> {
        val stats = comboStats(labels)
        val out = LinkedHashMap<Long, FocusPlan>()
        for (n in labels) {
            if (n.learnFocusKind == cc.ytdttj.noticleaner.data.db.LEARN_FOCUS_STRUCT) continue
            val key = comboKeyOf(n)
            val plan = when {
                n.learnFocus.isNotBlank() ->
                    FocusPlan(cc.ytdttj.noticleaner.data.db.LEARN_FOCUS_TEXT, n.learnFocus)
                key != null && oneSided(stats, key, n.learnLabel == 1) ->
                    FocusPlan(cc.ytdttj.noticleaner.data.db.LEARN_FOCUS_STRUCT, key.combo)
                else -> continue
            }
            if (plan.kind != n.learnFocusKind || plan.focus != n.learnFocus) {
                out[n.id] = plan
            }
        }
        return out
    }

    /**
     * 学习内容迁移（2.3.0 Dev 1，schema 4 → 5 时执行一次）：
     * 按 [planFocusMigration] 把旧标注改写为新方式并落库。历史详情会显示
     * 「学习重点：模板 @ + 详情>>」，用户不需要重新点一遍；[learnCount] 原样保留。
     */
    private suspend fun migrateLearnedFocus(
        dao: NotificationDao,
        labels: List<NotificationEntity>,
    ): List<NotificationEntity> {
        val plan = planFocusMigration(labels)
        if (plan.isEmpty()) return labels
        val updates = labels.mapNotNull { n ->
            plan[n.id]?.let { n.copy(learnFocus = it.focus, learnFocusKind = it.kind) }
        }
        ServiceLocator.db.withTransaction {
            for (u in updates) dao.update(u)
        }
        cc.ytdttj.noticleaner.diagnostics.RingLog.log(
            cc.ytdttj.noticleaner.diagnostics.LogModules.MODEL,
            "学习迁移：${updates.size}/${labels.size} 条旧标注按新方式重新标注" +
                "（模板 ${updates.count { it.learnFocusKind == cc.ytdttj.noticleaner.data.db.LEARN_FOCUS_STRUCT }} 条）",
        )
        val byId = updates.associateBy { it.id }
        return labels.map { byId[it.id] ?: it }
    }

    /**
     * 某个渠道的标注分布（2.2.1 Dev 1）。
     *
     * 用途：用户把某条通知「学为正常」后，若该渠道同时存在大量「广告」标注（尤其混杂），
     * 说明拦截主要来自**渠道级抬升**，逐条学习追不上——UI 据此给出「此渠道不再拦截」的引导，
     * 而不是让用户继续无效地一条条点。
     */
    data class ChannelMix(val ad: Int, val normal: Int) {
        val total: Int get() = ad + normal

        /** 两侧都有标注，且少数派占比达到护栏阈值 ⇒ 混杂渠道（渠道桶已被护栏抑制） */
        val mixed: Boolean
            get() = total > 0 && minOf(ad, normal) > 0 &&
                minOf(ad, normal).toFloat() / total >= SpamTuner.CHANNEL_MIXED_RATIO
    }

    /** 统计 [packageName] + [channelId] 渠道在标注集里的广告 / 正常条数 */
    suspend fun channelLabelMix(
        dao: NotificationDao,
        packageName: String,
        channelId: String,
    ): ChannelMix {
        if (channelId.isEmpty()) return ChannelMix(0, 0)
        var ad = 0
        var normal = 0
        for (n in dao.listLearnedOnce()) {
            if (n.packageName != packageName || n.channelId != channelId) continue
            if (n.learnLabel == 1) ad++ else normal++
        }
        return ChannelMix(ad, normal)
    }
}
