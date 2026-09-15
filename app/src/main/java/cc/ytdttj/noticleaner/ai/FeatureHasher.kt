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
    fun counts(rawText: String): Map<Int, Int> {
        val units = normalize(rawText).toByteArray(Charsets.UTF_16LE)
        val nUnits = units.size / 2
        val mask = BUCKETS - 1
        val counts = HashMap<Int, Int>()
        for (n in NGRAM_MIN..NGRAM_MAX) {
            val last = nUnits - n
            if (last < 0) continue
            for (start in 0..last) {
                val off = start * 2
                val len = n * 2
                val idx = fnv1a32(units, off, len) and mask
                counts[idx] = (counts[idx] ?: 0) + 1
            }
        }
        return counts
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
}

/** Python `text[:500]` 的 codepoint 语义截断（Kotlin String 按 UTF-16 单元索引） */
internal fun String.takeCodepoints(n: Int): String {
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
