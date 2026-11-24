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
 *
 * 2.3.0 Dev 1：归一化**不再**保留符号（见 §2.5 语料实测），`@`/`详情>>`/链接等
 * 结构信号改由 [structKeysForText] 以 prior 型特征单独表达（x=1、不参与 L2、
 * base 权重为 0、只由用户标注学习）。旧 `feat:gt` 全局伪特征已退役。
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

    /** 文本 → (index, count) 原始计数（未 L2 归一化）。 */
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
        // P0-1：表容量必须严格大于 1~3-gram 的最大去重键数（≤ 3n-3，n 为 UTF-16 单元数）。
        // 旧实现固定 2048：normalize 按 500 **码点**截断，astral 字母（U+1D400 系）每个占
        // 2 单元 → n 可达 1000 → 去重键最多 ~2997 > 2048；开放寻址表满后 while(true)
        // 找不到空槽会**永久自旋**（模块模式跑在 system_server → 一条通知即 DoS）。
        // 按 n 动态取 2 的幂（> 3n-3 恒成立），数学上保证永不填满；典型文本（n≤600）
        // 容量 ≤ 2048，与旧实现的分配开销相当。
        val cap = nextPowerOfTwo(3 * normalizedText.length + 1).coerceAtLeast(16)
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

    /**
     * App 级先验桶（2.2.1 vc87）：按包名聚合，与渠道无关。
     *
     * 为什么需要它：mipush 的 channelId 按推送动态生成、营销方多渠道轮换
     * （百度网盘实测 109478/102668/…），渠道粒度的学习桶与拦截都被绕过；
     * 而纯文本学习对"伪装成个人生活"的营销有结构性平台期（学 8 条同模板也只到 p≈0.62）。
     * 先验的正确粒度是**发件人** —— 同 App 的推送性质高度一致。
     */
    fun appKey(pkg: String): Int {
        val units = "app:$pkg".toByteArray(Charsets.UTF_16LE)
        return fnv1a32(units, 0, units.size) and (BUCKETS - 1)
    }

    // ==================== 2.3.0 Dev 1：结构特征（prior 型） ====================

    const val STRUCT_AT = "at"
    const val STRUCT_DETAIL_GT = "detail_gt"
    const val STRUCT_LINK = "link"
    const val STRUCT_SHARE = "share"

    /** 结构标记的规范顺序（组合 id 与哈希都依赖它，保证双端/跨版本稳定） */
    private val STRUCT_ORDER = listOf(STRUCT_AT, STRUCT_DETAIL_GT, STRUCT_LINK, STRUCT_SHARE)

    /**
     * 从**原始文本**提取结构标记（不经过 normalize——符号正是在那一步被删除的）。
     *
     * 为什么需要独立通道：`@`、`详情>>` 是用户眼中最强的判别信号，但归一化必须删除
     * 全部标点符号（语料两侧预处理不对称，保留会学成「标点=广告」）。结构标记以
     * prior 型特征（x=1、不参与 L2、base 权重 0）进入模型，只由用户标注拟合。
     */
    fun structMarksOf(rawText: String): List<String> {
        val out = ArrayList<String>(4)
        if (rawText.indexOf('@') >= 0 || rawText.indexOf('＠') >= 0) out.add(STRUCT_AT)
        if (hasDetailGt(rawText)) out.add(STRUCT_DETAIL_GT)
        if (rawText.contains("网页链接") || rawText.contains("链接") ||
            rawText.indexOf("http", ignoreCase = true) >= 0
        ) {
            out.add(STRUCT_LINK)
        }
        if (rawText.contains("[分享") || rawText.contains("【分享")) out.add(STRUCT_SHARE)
        return out
    }

    /** `详情[空白]{1,2}`（`详情>>`、`详情>`、`详情 >`、全角 ＞ 均命中） */
    private fun hasDetailGt(text: String): Boolean {
        var from = 0
        while (true) {
            val i = text.indexOf("详情", from)
            if (i < 0) return false
            var j = i + 2
            while (j < text.length && text[j].isWhitespace()) j++
            if (j < text.length && (text[j] == '>' || text[j] == '＞')) return true
            from = i + 1
        }
    }

    /** 单标记桶：`struct:{mark}/{pkg}`（按 App 隔离，避免微博的 @ 影响微信） */
    fun structKey(pkg: String, mark: String): Int {
        val units = "struct:$mark/$pkg".toByteArray(Charsets.UTF_16LE)
        return fnv1a32(units, 0, units.size) and (BUCKETS - 1)
    }

    /** 精确组合桶：`struct:combo:{markA}+{markB}/{pkg}`（排序固定，顺序无关） */
    fun structComboKey(pkg: String, marks: Collection<String>): Int {
        val canonical = STRUCT_ORDER.filter { it in marks }
        val units = "struct:combo/${canonical.joinToString("+")}/$pkg".toByteArray(Charsets.UTF_16LE)
        return fnv1a32(units, 0, units.size) and (BUCKETS - 1)
    }

    /**
     * 「首选组合」：`@` 与 `详情>>` 同时存在时优先用这一对（最常见的营销模板）；
     * 否则退回全部命中标记。UI 推荐与历史回填共用同一规则，避免「多一个 链接 标记
     * 就学成三段组合、下一批少一个标记又失配」。
     */
    fun preferredStructCombo(marks: Collection<String>): List<String> {
        return if (STRUCT_AT in marks && STRUCT_DETAIL_GT in marks) {
            listOf(STRUCT_AT, STRUCT_DETAIL_GT)
        } else {
            STRUCT_ORDER.filter { it in marks }
        }
    }

    /** 结构组合的持久化 id（如 `at+detail_gt`），用于 `learnFocusKind=STRUCT` 的存档 */
    fun structComboId(marks: Collection<String>): String =
        STRUCT_ORDER.filter { it in marks }.joinToString("+")

    /** 从持久化 id 还原标记集合（未知/空 id 返回空列表） */
    fun structMarksOfId(id: String): List<String> {
        val parts = id.split("+").map { it.trim() }.filter { it.isNotEmpty() }
        return STRUCT_ORDER.filter { it in parts }
    }

    /** 结构组合 id 的展示名（`at+detail_gt` → `@ + 详情>>`），toast 与详情页共用 */
    fun structComboLabel(id: String): String {
        val names = structMarksOfId(id).map {
            when (it) {
                STRUCT_AT -> "@"
                STRUCT_DETAIL_GT -> "详情>>"
                STRUCT_LINK -> "网页链接"
                STRUCT_SHARE -> "[分享]"
                else -> it
            }
        }
        return if (names.isEmpty()) id else names.joinToString(" + ")
    }

    /**
     * 正文命中的全部结构桶：每个单标记 + 所有 size ≥ 2 的子集组合。
     *
     * 例如文本同时含 `@` 与 `详情>>` 时，返回 `at`、`detail_gt`、`combo:at+detail_gt`；
     * 学过的组合对「多一个标记」的文本仍然生效（子集命中）。
     */
    fun structKeysForText(rawText: String, pkg: String): List<Int> {
        val marks = structMarksOf(rawText)
        if (marks.isEmpty()) return emptyList()
        val out = ArrayList<Int>(marks.size + 8)
        for (m in marks) out.add(structKey(pkg, m))
        val n = marks.size
        if (n >= 2) {
            for (mask in 1 until (1 shl n)) {
                if (Integer.bitCount(mask) < 2) continue
                val subset = ArrayList<String>(Integer.bitCount(mask))
                for (i in 0 until n) if (mask and (1 shl i) != 0) subset.add(marks[i])
                out.add(structComboKey(pkg, subset))
            }
        }
        return out
    }

    /**
     * 用户显式选择的结构标记对应的学习桶：
     * - 选多个标记 → **只**给精确组合桶（单标记保持 0，避免「只有 @」的真人消息被误伤）；
     * - 只选一个标记 → 给该单标记桶（UI 必须警告作用范围）。
     */
    fun structKeysForMarks(pkg: String, marks: Collection<String>): List<Int> {
        val canonical = STRUCT_ORDER.filter { it in marks }
        return when {
            canonical.isEmpty() -> emptyList()
            canonical.size == 1 -> listOf(structKey(pkg, canonical[0]))
            else -> listOf(structComboKey(pkg, canonical))
        }
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

    /** 最小 ≥ v 的 2 的幂（v ≤ 0 返回 1） */
    private fun nextPowerOfTwo(v: Int): Int {
        var c = v - 1
        c = c or (c shr 1)
        c = c or (c shr 2)
        c = c or (c shr 4)
        c = c or (c shr 8)
        c = c or (c shr 16)
        return c + 1
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
