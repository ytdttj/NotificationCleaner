package cc.ytdttj.noticleaner.ai

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * 广告通知打分模型 —— 解析 NSPM v2 格式（大端，int16 权重），与 training/train.py 导出端一致。
 *
 * 文件布局（Plan.md §5.2）：
 *   magic "NSPM"(4) version i32 buckets i32 ngramMin i32 ngramMax i32 bias f32 scale f32
 *   weights int16[buckets]  （加载时 w = q * scale）
 *
 * 模型不可变：端上学习结果通过 [withDelta] 叠加稀疏修正生成新实例，
 * 内置 base 权重永不被改写（学习成果独立存 spam_delta.bin，见 [SpamDelta]）。
 * [fingerprint] 为文件内容 CRC32，用于换基线模型后自动重新拟合学习量。
 *
 * 打分：z = bias + Σ w[k] * (count_k / L2norm)，p = sigmoid(z)（全程 Double，与 Python 一致）
 */
class SpamModel(
    val buckets: Int,
    val weights: FloatArray,
    val bias: Double,
    /** 模型文件内容 CRC32；内置模型更新后变化，用于判断学习量是否需要重新拟合。 */
    val fingerprint: Long = 0L,
) {
    init {
        require(weights.size == buckets) { "weights ${weights.size} != buckets $buckets" }
        require(buckets > 0 && buckets and (buckets - 1) == 0) { "buckets must be a power of two" }
    }

    /** [text] 为广告的概率，取值范围 [0, 1]。 */
    fun score(rawText: String): Double = scoreWith(weights, bias, rawText)

    /**
     * 带通道偏置的打分（1.1.11）：在文本 log-odds 上叠加通道桶权重（delta 学习所得）。
     * [channelKey] 为 [FeatureHasher.channelKey] 计算的桶索引；null/0 表示无通道信息。
     */
    fun score(rawText: String, channelKey: Int?): Double {
        if (channelKey == null || channelKey == 0) return score(rawText)
        return scoreWithChannel(FeatureHasher.counts(rawText), weights[channelKey].toDouble())
    }

    /**
     * 带通道偏置的打分（1.2.0，ImprovePlan P1-2）：接收**已归一化**文本，
     * 供决策热路径复用 hardAllow 检查时的 normalize 结果（消除重复 normalize）。
     * 与 [score] 传同一原始文本的结果完全一致（counts 的构建方式相同）。
     */
    fun scoreNormalized(normalizedText: String, channelKey: Int?): Double {
        val counts = FeatureHasher.countsOfNormalized(normalizedText)
        if (channelKey == null || channelKey == 0) {
            return scoreWithCounts(weights, bias, counts)
        }
        return scoreWithChannel(counts, weights[channelKey].toDouble())
    }

    /** 通道偏置 + 文本特征打分（通道权重不计入 L2 归一化，避免被稀释） */
    private fun scoreWithChannel(counts: Map<Int, Int>, channelWeight: Double): Double {
        var z = bias + channelWeight
        if (counts.isEmpty()) return sigmoid(z)
        var normSq = 0.0
        for (c in counts.values) normSq += c.toDouble() * c.toDouble()
        val norm = kotlin.math.sqrt(normSq)
        for ((k, c) in counts) {
            z += weights[k].toDouble() * (c.toDouble() / norm)
        }
        return sigmoid(z)
    }

    /** 将 [delta] 叠加到权重后得到的新模型副本；桶数不匹配或 delta 为空时返回自身。 */
    fun withDelta(delta: SpamDelta): SpamModel {
        if (delta.buckets != buckets || delta.isEmpty) return this
        val merged = weights.copyOf()
        for (i in delta.indices.indices) merged[delta.indices[i]] += delta.values[i]
        return SpamModel(buckets, merged, bias, fingerprint)
    }

    companion object {
        const val FORMAT_VERSION = 2
        private val MAGIC = byteArrayOf('N'.code.toByte(), 'S'.code.toByte(), 'P'.code.toByte(), 'M'.code.toByte())

        fun load(input: InputStream): SpamModel {
            val bytes = input.readBytes()
            val crc = CRC32().also { it.update(bytes) }.value
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val magic = ByteArray(4).also { buf.get(it) }
            require(magic.contentEquals(MAGIC)) { "bad magic: ${magic.joinToString()} — 旧格式模型请重训导出" }
            val version = buf.int
            require(version == FORMAT_VERSION) { "unsupported model format version: $version" }
            val buckets = buf.int
            val ngramMin = buf.int
            val ngramMax = buf.int
            val bias = buf.float.toDouble()
            val scale = buf.float.toDouble()
            require(ngramMin == FeatureHasher.NGRAM_MIN && ngramMax == FeatureHasher.NGRAM_MAX) {
                "n-gram range mismatch: model=[$ngramMin,$ngramMax] hasher=[${FeatureHasher.NGRAM_MIN},${FeatureHasher.NGRAM_MAX}]"
            }
            require(buckets in 1..(1 shl 24)) { "bad bucket count $buckets" }
            val w = FloatArray(buckets)
            for (i in 0 until buckets) {
                val s = buf.short.toInt() and 0xFFFF
                val signed = if (s >= 0x8000) s - 0x10000 else s
                w[i] = signed.toFloat() * scale.toFloat()
            }
            return SpamModel(buckets, w, bias, crc)
        }

        /** 参考打分（与 Python score_text 一致）：Double 累加，w 为反量化 float32 */
        fun scoreWith(weights: FloatArray, bias: Double, rawText: String): Double =
            scoreWithCounts(weights, bias, FeatureHasher.counts(rawText))

        /** 计数向量打分核心（Double 累加顺序与 Python 参考实现一致） */
        private fun scoreWithCounts(weights: FloatArray, bias: Double, counts: Map<Int, Int>): Double {
            if (counts.isEmpty()) return sigmoid(bias)
            var normSq = 0.0
            for (c in counts.values) normSq += c.toDouble() * c.toDouble()
            val norm = kotlin.math.sqrt(normSq)
            var z = bias
            for ((k, c) in counts) {
                z += weights[k].toDouble() * (c.toDouble() / norm)
            }
            return sigmoid(z)
        }

        internal fun quantise(weights: FloatArray): Pair<ShortArray, Float> {
            var maxAbs = 0f
            for (v in weights) {
                val a = Math.abs(v)
                if (a > maxAbs) maxAbs = a
            }
            val scale = if (maxAbs > 0f) maxAbs / 32767f else 1f
            val q = ShortArray(weights.size)
            for (i in weights.indices) {
                val v = Math.rint((weights[i] / scale).toDouble()).coerceIn(-32767.0, 32767.0)
                q[i] = v.toInt().toShort()
            }
            return q to scale
        }

        internal fun sigmoid(z: Double): Double =
            if (z >= 0) 1.0 / (1.0 + kotlin.math.exp(-z))
            else {
                val e = kotlin.math.exp(z)
                e / (1.0 + e)
            }
    }
}
