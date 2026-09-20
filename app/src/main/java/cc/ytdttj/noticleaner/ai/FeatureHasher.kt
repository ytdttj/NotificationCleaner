package cc.ytdttj.noticleaner.ai

/**
 * 特征哈希器 —— 与 training/train.py 中的 Python 参考实现逐位一致（唯一规范见 Plan.md §5.2）。
 *
 * 流程：
 *  1. 归一化：全小写 → 删除空白字符、数字(Nd)、字母 'x'、标点与符号(P* 与 S* 类)
 *  2. 按 Unicode codepoint 截断至 500（在原始文本上截断）
 *  3. 对 UTF-16 代码单元取字符 n-gram（n = 1..3），跨词连续取
 *  4. 每个 n-gram 的 UTF-16-LE 字节做 FNV-1a 32bit → index = hash & (BUCKETS-1)
 *  5. 计数，整条文本特征向量做 L2 归一化
 */
object FeatureHasher {
    const val BUCKETS = 1 shl 18
    const val NGRAM_MIN = 1
    const val NGRAM_MAX = 3
    const val MAX_TEXT_LEN = 500

    /** 与 Python SPACE_CHARS 一致的空白字符集（全部 BMP，转 Int 便于按码点比较） */
    private val SPACE_CP: IntArray = intArrayOf(
        0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x1C, 0x1D, 0x1E, 0x1F, 0x20,
        0x85, 0xA0, 0x1680,
        0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007,
        0x2008, 0x2009, 0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000,
    )

    fun normalize(rawText: String): String {
        val text = rawText.takeCodepoints(MAX_TEXT_LEN).lowercase()
        val sb = StringBuilder(text.length)
        // 按码点遍历（与 Python 逐 codepoint 语义一致：emoji 是 So 类会被删除，
        // 若按 UTF-16 单元遍历，代理对会被误保留导致双端特征不一致）
        for (cp in text.codePoints()) {
            if (cp == 'x'.code) continue
            if (SPACE_CP.contains(cp)) continue
            when (Character.getType(cp)) {
                Character.DECIMAL_DIGIT_NUMBER.toInt() -> continue
                Character.CONNECTOR_PUNCTUATION.toInt(),
                Character.DASH_PUNCTUATION.toInt(),
                Character.START_PUNCTUATION.toInt(),
                Character.END_PUNCTUATION.toInt(),
                Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
                Character.FINAL_QUOTE_PUNCTUATION.toInt(),
                Character.OTHER_PUNCTUATION.toInt(),
                Character.MATH_SYMBOL.toInt(),
                Character.CURRENCY_SYMBOL.toInt(),
                Character.MODIFIER_SYMBOL.toInt(),
                Character.OTHER_SYMBOL.toInt(),
                -> continue
            }
            sb.appendCodePoint(cp)
        }
        return sb.toString()
    }

    /** 文本 → (index, count) 原始计数（未 L2 归一化） */
    fun counts(rawText: String): FeatureCounts = countsOfNormalized(normalize(rawText))

    /**
     * 已归一化文本 → (index, count) 原始计数（1.2.0，ImprovePlan P1-2）：
     * 决策热路径先 normalize 做 hardAllow 检查，复用结果直接取 n-gram 计数，
     * 消除 [counts] 内部的第二次 normalize。与 [counts] 对同一文本结果完全一致。
     *
     * 1.3.2（P2-6）微优化：
     * - 开放寻址 IntArray 表替代 HashMap（每通知 ~700 次 Integer 装箱 + 节点分配 → 零装箱），
     *   system_server 侧 GC 收益最大；
     * - FNV 直接从 CharSequence 逐 char 喂入（低字节、高字节依次，与 UTF-16LE 字节序列
     *   逐位等价），省去每通知 2×len 的字节数组分配；
     * - 迭代顺序与 Python dict 插入序本就不同序（P2-6 论证），ParityTest 靠 1e-4 容差覆盖。
     */
    fun countsOfNormalized(normalizedText: String): FeatureCounts {
        // 容量 2048（2 的幂）：单条通知 500 单元 × 1~3gram 去重后 ≤1497 键，负载因子 <0.75
        val cap = 2048
        val slotMask = cap - 1
        val kArr = IntArray(cap)
        val vArr = IntArray(cap)
        var size = 0
        val mask = BUCKETS - 1
        val n = normalizedText.length
        for (len in NGRAM_MIN..NGRAM_MAX) {
            val last = n - len
            if (last < 0) continue
            for (start in 0..last) {
                val idx = fnv1a32Units(normalizedText, start, len) and mask
                var slot = idx and slotMask
                while (true) {
                    val v = vArr[slot]
                    if (v == 0) {
                        kArr[slot] = idx
                        vArr[slot] = 1
                        size++
                        break
                    }
                    if (kArr[slot] == idx) {
                        vArr[slot] = v + 1
                        break
                    }
                    slot = (slot + 1) and slotMask
                }
            }
        }
        return FeatureCounts(kArr, vArr, size)
    }

    /**
     * 通道特征 key（1.1.11）：`ch:{pkg}/{channelId}` 哈希进同一桶空间。
     * 前缀 "ch:" 避免与文本 n-gram 系统性碰撞（随机碰撞概率 2^-18，可忽略；
     * 且 fit 与 score 对称计算，即使撞上 base 噪声桶 delta 也会自动补偿）。
     */
    fun channelKey(pkg: String, channelId: String): Int {
        val units = "ch:$pkg/$channelId".toByteArray(Charsets.UTF_16LE)
        return fnv1a32(units, 0, units.size) and (BUCKETS - 1)
    }

    /** FNV-1a 32bit（与 Python 逐字节一致） */
    private fun fnv1a32(data: ByteArray, offset: Int, len: Int): Int {
        var h = 0x811C9DC5.toInt()
        for (i in offset until offset + len) {
            h = h xor (data[i].toInt() and 0xFF)
            h *= 0x01000193
        }
        return h
    }

    /**
     * FNV-1a 32bit over UTF-16LE 字节序列（P2-6）：逐 char 依次贡献低字节 `c & 0xFF`、
     * 高字节 `(c >>> 8) & 0xFF`，与 `toByteArray(UTF_16LE)` 的字节序列逐位等价，零分配。
     */
    private fun fnv1a32Units(s: String, start: Int, unitCount: Int): Int {
        var h = 0x811C9DC5.toInt()
        for (i in start until start + unitCount) {
            val c = s[i].code
            h = h xor (c and 0xFF)
            h *= 0x01000193
            h = h xor ((c ushr 8) and 0xFF)
            h *= 0x01000193
        }
        return h
    }
}

/**
 * 开放寻址 n-gram 计数表（1.3.2 P2-6）：values[i] == 0 表示空槽（计数从 1 起，非零即占用）。
 * capacity = keys.size（2 的幂）；有效条目数 = [size]。
 */
class FeatureCounts(
    val keys: IntArray,
    val values: IntArray,
    val size: Int,
) {
    /** 遍历所有 (key, count) 项 */
    inline fun forEach(action: (key: Int, count: Int) -> Unit) {
        for (i in keys.indices) {
            val v = values[i]
            if (v != 0) action(keys[i], v)
        }
    }

    /** 诊断/测试用：转为普通 Map */
    fun asMap(): Map<Int, Int> {
        val m = HashMap<Int, Int>(size * 2)
        forEach { k, c -> m[k] = c }
        return m
    }
}

/** Python `text[:500]` 的 codepoint 语义截断（Kotlin String 按 UTF-16 单元索引）。
 *  1.3.2（P1-2）提升为公共：入库路径对 title/content 复用同一截断语义。 */
fun String.takeCodepoints(n: Int): String {
    if (n >= codePointCount(0, length)) return this
    val sb = StringBuilder()
    var count = 0
    for (cp in codePoints()) {
        if (count == n) break
        sb.appendCodePoint(cp)
        count++
    }
    return sb.toString()
}
