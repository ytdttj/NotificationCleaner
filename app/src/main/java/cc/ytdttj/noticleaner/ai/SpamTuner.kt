package cc.ytdttj.noticleaner.ai

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * 根据用户标注的样本，在冻结的基础模型之上拟合一个稀疏的 [SpamDelta]
 * （复刻 Notice SpamTuner）：
 * 从 Δ = 0 出发，用普通 SGD 最小化 Σ logloss(σ(z_base + Δ·x)) + l2/2·‖Δ‖²。
 * 过程是确定性的（固定顺序、固定轮数），因此相同的标注总会得到相同的 delta。
 *
 * 与旧版「单条闭式写入权重」的差异：
 * - base 权重永不被改写，学习成果独立存放，无量化抹平问题
 * - 全部标注联合拟合，多条标注相互平衡
 * - 删除标注后重新拟合即精确回滚
 */
object SpamTuner {
    /**
     * 学习样本（1.1.11 扩展）：
     * @param channelKey 通道特征桶（pkg+channelId 哈希，见 [FeatureHasher.channelKey]）；
     *        作为独立 log-odds 偏置参与拟合与打分（不混入文本 L2 归一化，避免被稀释）
     * @param weight 样本权重 = 同一通知被重复学习的次数（>3 次大幅提升；拟合时按重复样本计入）
     */
    data class Sample(
        val text: String,
        val spam: Boolean,
        val channelKey: Int = 0,
        val weight: Int = 1,
    )

    /** 归一化后短于此长度的文本不参与拟合（信号太少） */
    const val MIN_LENGTH = 4

    /** 重复学习样本权重上限（防止单样本过拟合把权重推爆，与既有重复通知 cap=10 惯例一致） */
    const val MAX_WEIGHT = 10

    private class Prepared(
        val z0: Float,
        val y: Float,
        val keys: IntArray,
        val x: FloatArray,
        val channelKey: Int,
        val repeats: Int,
    )

    fun fit(
        base: SpamModel,
        samples: List<Sample>,
        epochs: Int = 60,
        lr: Float = 1f,
        l2: Float = 0.005f,
    ): SpamDelta {
        // 重复学习的样本按 repeats 展开计入（1.1.11：同方向重复学习显著提升权重）
        val prepared = samples.flatMap { s ->
            val p = prepare(base, s) ?: return@flatMap emptyList()
            List(p.repeats) { p }
        }
        if (prepared.isEmpty()) return SpamDelta.empty(base.buckets)

        val delta = HashMap<Int, Float>()
        repeat(epochs) {
            for (s in prepared) {
                var z = s.z0
                for (i in s.keys.indices) z += (delta[s.keys[i]] ?: 0f) * s.x[i]
                // 通道独立偏置（x=1，不参与 L2 归一化）：同 App 同渠道的推送性质高度一致
                if (s.channelKey != 0) z += (delta[s.channelKey] ?: 0f)
                val g = sigmoid(z) - s.y
                for (i in s.keys.indices) {
                    val k = s.keys[i]
                    val current = delta[k] ?: 0f
                    delta[k] = current - lr * (g * s.x[i] + l2 * current)
                }
                if (s.channelKey != 0) {
                    val current = delta[s.channelKey] ?: 0f
                    delta[s.channelKey] = current - lr * (g + l2 * current)
                }
            }
        }
        val keys = delta.keys.filter { delta[it] != 0f }.sorted()
        return SpamDelta(
            buckets = base.buckets,
            indices = keys.toIntArray(),
            values = FloatArray(keys.size) { delta[keys[it]]!! },
        )
    }

    private fun prepare(base: SpamModel, sample: Sample): Prepared? {
        // 归一化后过短的文本信号太少，不参与拟合（与 SpamJudge 护栏一致）
        if (FeatureHasher.normalize(sample.text).length < MIN_LENGTH) return null
        val counts = FeatureHasher.counts(sample.text)
        if (counts.isEmpty()) return null
        var sq = 0.0
        for (c in counts.values) sq += c.toDouble() * c
        val norm = sqrt(sq).toFloat()
        val keys = IntArray(counts.size)
        val x = FloatArray(counts.size)
        var z0 = base.bias.toFloat()
        var i = 0
        for ((k, c) in counts) {
            keys[i] = k
            x[i] = (c / norm)
            z0 += base.weights[k] * x[i]
            i++
        }
        // 通道桶的 base 权重计入 z0（fit 与 score 对称，delta 自动补偿 base 噪声）
        val channelKey = sample.channelKey
        if (channelKey != 0) z0 += base.weights[channelKey]
        return Prepared(
            z0 = z0,
            y = if (sample.spam) 1f else 0f,
            keys = keys,
            x = x,
            channelKey = channelKey,
            repeats = sample.weight.coerceIn(1, MAX_WEIGHT),
        )
    }

    private fun sigmoid(z: Float): Float {
        val zd = z.toDouble()
        return if (zd >= 0) (1.0 / (1.0 + exp(-zd))).toFloat() else exp(zd).let { (it / (1.0 + it)).toFloat() }
    }
}
