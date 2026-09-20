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
        // 1.3.2（P2-7a）：不再为每条样本分配 List(repeats)——内层 repeat 循环，迭代顺序与展开完全一致
        val prepared = samples.mapNotNull { prepare(base, it) }
        if (prepared.isEmpty()) return SpamDelta.empty(base.buckets)

        // P2-7a：delta 用密集 FloatArray（1MB 临时，仅在用户点击学习时分配一次，学完释放），
        // 千万级 HashMap 装箱读写 → 数组索引；桶数恰为 2 的幂，索引即 key
        val delta = FloatArray(base.buckets)
        repeat(epochs) {
            for (s in prepared) {
                repeat(s.repeats) {
                    var z = s.z0
                    for (i in s.keys.indices) z += delta[s.keys[i]] * s.x[i]
                    // 通道独立偏置（x=1，不参与 L2 归一化）：同 App 同渠道的推送性质高度一致
                    if (s.channelKey != 0) z += delta[s.channelKey]
                    val g = sigmoid(z) - s.y
                    for (i in s.keys.indices) {
                        val k = s.keys[i]
                        val current = delta[k]
                        delta[k] = current - lr * (g * s.x[i] + l2 * current)
                    }
                    if (s.channelKey != 0) {
                        val current = delta[s.channelKey]
                        delta[s.channelKey] = current - lr * (g + l2 * current)
                    }
                }
            }
        }
        // 稀疏输出：仅非零桶；数组天然升序（与原 HashMap 键排序结果一致），逐位一致
        var count = 0
        for (v in delta) if (v != 0f) count++
        val keys = IntArray(count)
        val values = FloatArray(count)
        var i = 0
        for (idx in delta.indices) {
            if (delta[idx] != 0f) {
                keys[i] = idx
                values[i] = delta[idx]
                i++
            }
        }
        return SpamDelta(buckets = base.buckets, indices = keys, values = values)
    }

    private fun prepare(base: SpamModel, sample: Sample): Prepared? {
        // 1.3.2（P2-3）：normalize 只做一次——长度校验与计数共用同一归一化结果
        val n = FeatureHasher.normalize(sample.text)
        if (n.length < MIN_LENGTH) return null
        val counts = FeatureHasher.countsOfNormalized(n)
        if (counts.size == 0) return null
        var sq = 0.0
        counts.forEach { _, c -> sq += c.toDouble() * c }
        val norm = sqrt(sq).toFloat()
        val keys = IntArray(counts.size)
        val x = FloatArray(counts.size)
        var z0 = base.bias.toFloat()
        var i = 0
        counts.forEach { k, c ->
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
