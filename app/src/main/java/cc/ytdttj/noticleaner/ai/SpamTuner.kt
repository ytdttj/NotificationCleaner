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
     * @param appKey App 级先验桶（2.2.1 vc87，pkg 哈希，见 [FeatureHasher.appKey]）——
     *        与 [channelKey] **互斥生效**（见 [fit] 的三态判定）：mipush 渠道 ID 动态轮换时
     *        渠道桶失效，App 桶是发件人先验的正确粒度
     * @param weight 样本权重 = 同一通知被重复学习的次数（>3 次大幅提升；拟合时按重复样本计入）
     */
    data class Sample(
        val text: String,
        val spam: Boolean,
        val channelKey: Int = 0,
        val appKey: Int = 0,
        val weight: Int = 1,
    )

    /** 归一化后短于此长度的文本不参与拟合（信号太少） */
    const val MIN_LENGTH = 4

    /** 重复学习样本权重上限（防止单样本过拟合把权重推爆，与既有重复通知 cap=10 惯例一致） */
    const val MAX_WEIGHT = 10

    /**
     * 渠道桶护栏（2.2.1 Dev 1）：渠道内**少数派标注占比 ≥ 此值** ⇒ 视为「混杂渠道」，不建渠道桶。
     *
     * 背景（2026-10-11 抖音私信排查，「学为正常后仍被拦」）：学习是**文本级**、拦截是**文本+渠道级**。
     * 只要同一渠道里「广告」标注多于「正常」标注，`delta[channelKey]` 就是一个大正值，
     * 它**叠加到该渠道每一条通知**上，把文本本身干净、只是用户没逐条学过的通知一律顶过阈值
     * （实测同渠道同分钟内：纯文本私信 p≈0.001 放行，而「哈哈哈哈哈」p=0.91、「[分享视频]」p=0.98）。
     * 用户逐条「学为正常」永远追不上渠道级的抬升 —— 这正是「怎么学都没效果」的机制。
     *
     * 纯渠道（一边倒，如全是广告）保留渠道桶：此时渠道先验本身是可靠信号，且无「把正常内容一起带走」之虞。
     */
    const val CHANNEL_MIXED_RATIO = 0.2f

    /**
     * 渠道桶偏置幅值上限（2.2.1 Dev 1，log-odds）。
     *
     * 混杂护栏只能识别「已看到两侧标注」的渠道；对「看起来纯」的渠道（例如 20 条广告标注、0 条正常标注，
     * 而那些正常通知在 LSPosed 模式下被模块端提前吞掉、用户根本无从标注）护栏失效。
     * 因此再对通道桶单独做幅值封顶，把"渠道先验"限制成**助推**而不是**决定因素**：
     * 模型认为干净的通知（p≈0.001）要越过 0.8 阈值需要约 8.3 的 log-odds，而本上限只有 2.5，
     * 所以渠道偏置**不可能独自完成一次拦截**（对本身已在阈值附近的文本仍能起作用，这是合理的）。
     */
    const val CHANNEL_BIAS_CAP = 2.5f

    /**
     * **纯渠道**解除封顶（2.2.1 vc86）。
     *
     * 背景：百度网盘这类「整条渠道只发营销」的渠道，逐条学习追不上（每条广告文案全新），
     * 而 ±2.5 的封顶让渠道先验永远无法独自完成拦截 —— 用户问"学完为什么还出现"的根源。
     * 当一个渠道的标注**一边倒**（同侧标注 ≥ [PURE_CHANNEL_MIN_SAMPLES] 条、另一侧 0 条）时，
     * 渠道先验是可靠的，封顶放宽到 [PURE_CHANNEL_BIAS_CAP] —— 足以独自把干净文本顶过阈值。
     *
     * 安全性：① 混杂渠道（如抖音）维持 2.5 封顶不变；② 会话识别（CONVERSATION）先于 AI，
     * 私信即使在该渠道也不会被打分；③ 白名单仍优先；④ 标注可撤销、渠道可一键恢复。
     */
    const val PURE_CHANNEL_BIAS_CAP = 8.0f

    /** 认定"纯渠道"所需的最少同侧标注条数（防 1~2 条误标就解锁全渠道拦截） */
    const val PURE_CHANNEL_MIN_SAMPLES = 3

    /**
     * 剔除**混杂渠道**的渠道特征（2.2.1 Dev 1）。
     *
     * 判定用**通知条数**而非学习权重：用户把同一条通知反复点「学为广告」N 次，
     * 那是「这一条文本很确定」的信息，不能解释成「这个渠道有 N 条广告」。
     *
     * @return 混杂渠道的样本 `channelKey` 置 0（该渠道不再产生桶，文本 n-gram 学习照常生效）
     */
    fun suppressMixedChannels(samples: List<Sample>): List<Sample> {
        if (samples.none { it.channelKey != 0 }) return samples
        val ad = HashMap<Int, Int>()
        val normal = HashMap<Int, Int>()
        for (s in samples) {
            if (s.channelKey == 0) continue
            if (s.spam) ad[s.channelKey] = (ad[s.channelKey] ?: 0) + 1
            else normal[s.channelKey] = (normal[s.channelKey] ?: 0) + 1
        }
        val mixed = HashSet<Int>()
        for (k in ad.keys + normal.keys) {
            val a = ad[k] ?: 0
            val n = normal[k] ?: 0
            val total = a + n
            if (total == 0) continue
            val minority = minOf(a, n)
            if (minority > 0 && minority.toFloat() / total >= CHANNEL_MIXED_RATIO) mixed += k
        }
        if (mixed.isEmpty()) return samples
        return samples.map { if (it.channelKey in mixed) it.copy(channelKey = 0) else it }
    }

    private class Prepared(
        val z0: Float,
        val y: Float,
        val keys: IntArray,
        val x: FloatArray,
        /** 发件人先验桶：App 纯 App 时为 appKey，否则为 channelKey；0 = 无先验 */
        val priorKey: Int,
        val priorIsApp: Boolean,
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
        //
        // 2.2.1 vc87：发件人先验的三态判定（App 桶 vs 渠道桶，**互斥**，防止两个先验叠加失控）：
        // - App **纯**（同侧 ≥PURE_CHANNEL_MIN_SAMPLES 条标注、另一侧 0）→ App 桶接管，渠道桶收起；
        //   理由：mipush 渠道 ID 动态轮换（百度网盘 109478/102668/…），渠道粒度被绕过，
        //   且纯文本学习对伪装营销有结构性平台期（学 8 条同模板也只到 p≈0.62，见 10-21 实验）
        // - App **混杂**（少数派占比 ≥CHANNEL_MIXED_RATIO）→ App 桶不建（防抖音式误杀），渠道桶按原逻辑
        // - App **样本不足** → App 桶不建，渠道桶按原逻辑
        val appAd = HashMap<Int, Int>()
        val appNormal = HashMap<Int, Int>()
        for (s in samples) {
            if (s.appKey == 0) continue
            if (s.spam) appAd[s.appKey] = (appAd[s.appKey] ?: 0) + 1
            else appNormal[s.appKey] = (appNormal[s.appKey] ?: 0) + 1
        }
        val pureAdApps = HashSet<Int>()
        val pureNormalApps = HashSet<Int>()
        for (k in appAd.keys + appNormal.keys) {
            val a = appAd[k] ?: 0
            val n = appNormal[k] ?: 0
            if (a >= PURE_CHANNEL_MIN_SAMPLES && n == 0) pureAdApps += k
            else if (n >= PURE_CHANNEL_MIN_SAMPLES && a == 0) pureNormalApps += k
            // 其余（两边都有、或样本不足）：不给 App 桶，保守 —— 由渠道桶/文本学习接管
        }
        val transformed = samples.map { s ->
            when {
                s.appKey != 0 && (s.appKey in pureAdApps || s.appKey in pureNormalApps) ->
                    s.copy(channelKey = 0) // App 桶接管，渠道桶收起
                else -> s.copy(appKey = 0) // 混杂/样本不足 → 渠道桶按原逻辑接管
            }
        }
        // 渠道桶护栏（混杂渠道不建桶）只影响渠道桶；纯 App 的样本 channelKey 已置 0，不受影响
        val prepared = suppressMixedChannels(transformed).mapNotNull { prepare(base, it) }
        if (prepared.isEmpty()) return SpamDelta.empty(base.buckets)

        // P2-7a：delta 用密集 FloatArray（1MB 临时，仅在用户点击学习时分配一次，学完释放），
        // 千万级 HashMap 装箱读写 → 数组索引；桶数恰为 2 的幂，索引即 key
        val delta = FloatArray(base.buckets)
        repeat(epochs) {
            for (s in prepared) {
                repeat(s.repeats) { rep ->
                    var z = s.z0
                    for (i in s.keys.indices) z += delta[s.keys[i]] * s.x[i]
                    // 发件人先验独立偏置（x=1，不参与 L2 归一化）：同 App/同渠道的推送性质高度一致
                    if (s.priorKey != 0) z += delta[s.priorKey]
                    val g = sigmoid(z) - s.y
                    for (i in s.keys.indices) {
                        val k = s.keys[i]
                        val current = delta[k]
                        delta[k] = current - lr * (g * s.x[i] + l2 * current)
                    }
                    // 2.2.1 Dev 1：发件人先验桶**只按通知条数计入一次**，不随「重复点击学习」成倍放大。
                    // 重复学习表达的是「这一条文本很确定」，而先验桶是全渠道/App 共享特征——
                    // 若同样按 repeats 放大，用户对同一条广告连点 10 次就会灌入 10 倍正偏置，
                    // 足以把该渠道/App 所有正常内容一起带走（正是「学为正常没效果」的放大器）。
                    if (s.priorKey != 0 && rep == 0) {
                        val current = delta[s.priorKey]
                        delta[s.priorKey] = current - lr * (g + l2 * current)
                    }
                }
            }
        }
        // 2.2.1 Dev 1：先验桶幅值封顶——先验保留，但不足以独自把整条渠道推向一侧。
        // 2.2.1 vc86：**纯渠道**（同侧 ≥3 条标注、另一侧 0 条）封顶放宽到 ±8；
        // 2.2.1 vc87：**纯 App** 同样解封（App 桶接管时），混杂/样本不足维持 ±2.5。
        // 渠道桶的纯度统计用**变换后**的样本（纯 App 的样本 channelKey 已置 0，不再参与渠道纯度）。
        val adCount = HashMap<Int, Int>()
        val normalCount = HashMap<Int, Int>()
        for (s in transformed) {
            if (s.channelKey == 0) continue
            if (s.spam) adCount[s.channelKey] = (adCount[s.channelKey] ?: 0) + 1
            else normalCount[s.channelKey] = (normalCount[s.channelKey] ?: 0) + 1
        }
        for (s in prepared) {
            if (s.priorKey == 0) continue
            val v = delta[s.priorKey]
            val capped = when {
                s.priorIsApp ->
                    // App 桶只在纯 App 时存在（三态判定保证），按方向解封
                    if (s.y > 0f) v.coerceIn(-CHANNEL_BIAS_CAP, PURE_CHANNEL_BIAS_CAP)
                    else v.coerceIn(-PURE_CHANNEL_BIAS_CAP, CHANNEL_BIAS_CAP)
                else -> {
                    val a = adCount[s.priorKey] ?: 0
                    val n = normalCount[s.priorKey] ?: 0
                    when {
                        a >= PURE_CHANNEL_MIN_SAMPLES && n == 0 ->
                            v.coerceIn(-CHANNEL_BIAS_CAP, PURE_CHANNEL_BIAS_CAP)
                        n >= PURE_CHANNEL_MIN_SAMPLES && a == 0 ->
                            v.coerceIn(-PURE_CHANNEL_BIAS_CAP, CHANNEL_BIAS_CAP)
                        else -> v.coerceIn(-CHANNEL_BIAS_CAP, CHANNEL_BIAS_CAP)
                    }
                }
            }
            if (capped != v) delta[s.priorKey] = capped
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
        // P1-8：'>' 伪特征与打分路径对称（含 '>' 的样本拟合时能看到 GT 桶，
        // 用户"学为正常"才能对该桶产生负修正、推翻 base 的 '>' 先验）
        val counts = FeatureHasher.countsOfNormalized(n, FeatureHasher.hasGtMark(sample.text))
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
        // 通道桶的 base 权重计入 z0（fit 与 score 对称，delta 自动补偿 base 噪声）；
        // vc87：App 桶同理（两者互斥，见 fit 的三态判定）
        val priorKey = if (sample.appKey != 0) sample.appKey else sample.channelKey
        val priorIsApp = sample.appKey != 0
        if (priorKey != 0) z0 += base.weights[priorKey]
        return Prepared(
            z0 = z0,
            y = if (sample.spam) 1f else 0f,
            keys = keys,
            x = x,
            priorKey = priorKey,
            priorIsApp = priorIsApp,
            repeats = sample.weight.coerceIn(1, MAX_WEIGHT),
        )
    }

    private fun sigmoid(z: Float): Float {
        val zd = z.toDouble()
        return if (zd >= 0) (1.0 / (1.0 + exp(-zd))).toFloat() else exp(zd).let { (it / (1.0 + it)).toFloat() }
    }
}
