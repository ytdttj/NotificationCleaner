package cc.ytdttj.noticleaner.data

import androidx.room.withTransaction
import cc.ytdttj.noticleaner.ServiceLocator
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

    /** 批量重学习结果统计 */
    data class RelearnResult(val adCount: Int, val normalCount: Int, val total: Int)

    /**
     * 按各自**原有方向**重新学习一批通知（1.4.0 Dev 13：统计明细页「全部学习」）。
     *
     * - 已学习为广告 → 再次学习广告（learnCount+1，广告权重**加强**）
     * - 已学习为正常 → 再次学习正常（learnCount+1，广告权重**下调**）
     * - 尚未学习（如「已过滤」明细里没标注过的）→ 按广告方向学习（与其当前被过滤判定一致）
     *
     * 全程只做**一次**重拟合（而非每条一次），避免 N 次 SGD 拟合。
     * learnCount 上限 [SpamTuner.MAX_WEIGHT]，与单条重复学习的惯例一致。
     */
    suspend fun relearnAll(
        dao: NotificationDao,
        modelRepo: ModelRepository,
        items: List<NotificationEntity>,
    ): RelearnResult {
        var ad = 0
        var normal = 0
        for (n in items) {
            val label = if (n.learned && n.learnLabel == 0) 0 else 1
            if (label == 1) ad++ else normal++
            dao.update(
                n.copy(
                    learned = true,
                    learnLabel = label,
                    learnCount = (n.learnCount + 1).coerceAtMost(SpamTuner.MAX_WEIGHT),
                    decision = if (label == 1) DECISION_MANUAL_MARKED_AD else DECISION_PASSED,
                ),
            )
        }
        refit(dao, modelRepo)
        return RelearnResult(adCount = ad, normalCount = normal, total = items.size)
    }

    /**
     * 用全部标注在冻结 base 上重新拟合稀疏 delta，叠加到生效模型，
     * 并刷新所有已学习行的概率展示（带通道偏置，与热路径决策一致）。
     *
     * @return 重算后的已学习行；无标注或模型不可用时返回空/原列表。
     */
    suspend fun refit(dao: NotificationDao, modelRepo: ModelRepository): List<NotificationEntity> {
        val labels = dao.listLearnedOnce()
        if (labels.isEmpty()) return emptyList()
        val samples = labels.map {
            SpamTuner.Sample(
                text = listOf(it.title, it.content).filter { s -> s.isNotEmpty() }.joinToString("\n"),
                spam = it.learnLabel == 1,
                channelKey = if (it.channelId.isNotEmpty()) {
                    FeatureHasher.channelKey(it.packageName, it.channelId)
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
                val p = effective.score(text, chKey).toFloat()
                val row = it.copy(adProbability = p)
                dao.update(row)
                row
            }
        }
        return updated
    }
}
