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
        val labels = dao.listLearnedOnce()
        if (labels.isEmpty()) {
            // 无标注：也要把 schema 标记为最新，避免每次启动都空跑一趟检查
            modelRepo.setLearningSchema(ModelRepository.LEARNING_SCHEMA)
            return emptyList()
        }
        val samples = labels.map {
            SpamTuner.Sample(
                text = listOf(it.title, it.content).filter { s -> s.isNotEmpty() }.joinToString("\n"),
                spam = it.learnLabel == 1,
                channelKey = if (it.channelId.isNotEmpty()) {
                    FeatureHasher.channelKey(it.packageName, it.channelId)
                } else {
                    0
                },
                appKey = FeatureHasher.appKey(it.packageName),
                weight = maxOf(1, it.learnCount),
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

        // 用新模型刷新已学习行的概率展示
        val effective = modelRepo.get() ?: return labels
        val updated = ServiceLocator.db.withTransaction {
            labels.map {
                val text = listOf(it.title, it.content).filter { s -> s.isNotEmpty() }.joinToString("\n")
                val chKey = if (it.channelId.isNotEmpty()) {
                    FeatureHasher.channelKey(it.packageName, it.channelId)
                } else {
                    null
                }
                val p = effective.score(text, FeatureHasher.appKey(it.packageName), chKey).toFloat()
                val row = it.copy(adProbability = p)
                dao.update(row)
                row
            }
        }
        return updated
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
