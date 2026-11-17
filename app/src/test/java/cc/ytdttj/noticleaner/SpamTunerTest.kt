package cc.ytdttj.noticleaner

import cc.ytdttj.noticleaner.ai.FeatureHasher
import cc.ytdttj.noticleaner.ai.SpamModel
import cc.ytdttj.noticleaner.ai.SpamTuner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 端上学习回归测试（1.1.0，复刻 Notice 全量重拟合架构）：
 * 标注集拟合稀疏 delta → 叠加 base → 学习效果显著；移除标注后重拟合精确回滚。
 * 1.1.11：样本构造扩展 channelKey/weight（默认值，行为不变）。
 */
class SpamTunerTest {

    private fun base(): SpamModel = SpamModel.load(
        File("src/main/resources/model/model.bin").inputStream(),
    )

    private val adText = "【XX商城】限时秒杀！全场5折起，点击领取100元优惠券，仅剩2小时！"
    private val normalText = "您的验证码是 483920，5分钟内有效，请勿泄露。"

    @Test
    fun learnNormalStronglyReducesAdProbability() {
        val m = base()
        assertTrue("前置条件：该文本初始应为高广告概率", m.score(adText) >= 0.8)
        val delta = SpamTuner.fit(m, listOf(SpamTuner.Sample(adText, spam = false)))
        val after = m.withDelta(delta).score(adText)
        assertTrue("学习为正常后概率应 < 0.2，实际 $after", after < 0.2)
    }

    @Test
    fun learnAdStronglyRaisesProbability() {
        val m = base()
        val delta = SpamTuner.fit(m, listOf(SpamTuner.Sample(normalText, spam = true)))
        val after = m.withDelta(delta).score(normalText)
        assertTrue("学习为广告后概率应 > 0.8，实际 $after", after > 0.8)
    }

    @Test
    fun removingLabelRefitsBackExactly() {
        val m = base()
        val before = m.score(adText)
        // 学习为正常 → 概率大降
        val d1 = SpamTuner.fit(m, listOf(SpamTuner.Sample(adText, spam = false)))
        assertTrue(m.withDelta(d1).score(adText) < 0.2)
        // 移除标注 → 重拟合为空 delta → 与初始完全一致（确定性拟合）
        val d2 = SpamTuner.fit(m, emptyList())
        assertTrue(d2.isEmpty)
        assertEquals(before, m.withDelta(d2).score(adText), 0.0)
    }

    @Test
    fun jointLabelsBalanceEachOther() {
        val m = base()
        val delta = SpamTuner.fit(
            m,
            listOf(
                SpamTuner.Sample(adText, spam = true),
                SpamTuner.Sample(normalText, spam = false),
            ),
        )
        val merged = m.withDelta(delta)
        assertTrue("广告文本应保持高概率", merged.score(adText) >= 0.8)
        assertTrue("正常文本应保持低概率", merged.score(normalText) <= 0.2)
    }

    @Test
    fun baseModelFileIsNeverMutated() {
        val m = base()
        val original = m.weights.copyOf()
        val delta = SpamTuner.fit(m, listOf(SpamTuner.Sample(adText, spam = true)))
        m.withDelta(delta)
        org.junit.Assert.assertArrayEquals(original, m.weights, 0f)
    }

    // ==================== 2.2.1 Dev 1：渠道桶护栏 ====================
    // 背景（2026-10-11 抖音私信「学为正常后仍被拦」排查）：
    // 学习是文本级、拦截是文本+渠道级。同渠道「广告」标注多于「正常」标注时，
    // delta[channelKey] 会变成一个大正值，叠加到该渠道**每一条**通知上，
    // 把用户没逐条学过的干净通知一并顶过阈值 —— 逐条学习永远追不上。

    /** 取 delta 中某个桶的权重（不存在即 0） */
    private fun deltaAt(d: cc.ytdttj.noticleaner.ai.SpamDelta, key: Int): Float {
        val i = d.indices.indexOf(key)
        return if (i >= 0) d.values[i] else 0f
    }

    /** 未参与标注、文本干净的"第三方"文本——只能被渠道偏置影响 */
    private val unseenCleanText = "今天的会议改到下午三点，会议室在二楼"

    @Test
    fun mixedChannelDropsChannelFeature() {
        val ch = 0x1A2B
        val mixed = buildList {
            repeat(6) { add(SpamTuner.Sample("限时秒杀广告文本$it", spam = true, channelKey = ch)) }
            repeat(4) { add(SpamTuner.Sample("你的快递已放驿站$it", spam = false, channelKey = ch)) }
        }
        assertTrue(
            "混杂渠道（广告 6 : 正常 4）应剔除渠道特征",
            SpamTuner.suppressMixedChannels(mixed).all { it.channelKey == 0 },
        )

        // 一边倒的渠道保留渠道桶（渠道先验本身可靠）
        val oneSided = listOf(
            SpamTuner.Sample("限时秒杀广告文本 A", spam = true, channelKey = ch),
            SpamTuner.Sample("限时秒杀广告文本 B", spam = true, channelKey = ch),
            SpamTuner.Sample("限时秒杀广告文本 C", spam = true, channelKey = ch),
        )
        assertTrue(
            "纯渠道应保留渠道特征",
            SpamTuner.suppressMixedChannels(oneSided).all { it.channelKey == ch },
        )
    }

    /**
     * 回归测试（本次修复的核心断言）：
     * 混杂渠道被护栏剔除后，**未参与标注的干净文本**在该渠道里的得分
     * 必须与"完全不使用渠道特征"时**逐位一致**——即渠道不再对该文本施加任何抬升。
     */
    @Test
    fun mixedChannelLeavesUnseenTextUntouched() {
        val m = base()
        val ch = 0x5C5C
        val mixed = buildList {
            repeat(7) { add(SpamTuner.Sample("限时秒杀全场五折领取优惠券$it", spam = true, channelKey = ch)) }
            repeat(3) { add(SpamTuner.Sample("你的包裹已到达菜鸟驿站$it", spam = false, channelKey = ch)) }
        }
        val withChannel = m.withDelta(SpamTuner.fit(m, mixed))
        val withoutChannel = m.withDelta(SpamTuner.fit(m, mixed.map { it.copy(channelKey = 0) }))
        assertEquals(
            "混杂渠道不得对未标注的干净文本产生任何抬升",
            withoutChannel.score(unseenCleanText, ch),
            withChannel.score(unseenCleanText, ch),
            1e-6,
        )
        assertTrue(
            "该文本得分应远低于任何阈值",
            withChannel.score(unseenCleanText, ch) < 0.3,
        )
    }

    /** 安全阀：即使渠道"看起来纯"（正常侧为 0，可能因模块端提前拦截而学不到），渠道偏置也不得失控 */
    /**
     * vc87 改版：**纯 App**（同侧 ≥3 条标注、另一侧 0 条）的 App 桶接管渠道桶，解除封顶到 ±8 ——
     * 百度网盘式"mipush 渠道 ID 动态轮换"（109478/102668/…）让渠道粒度失效，
     * 发件人先验的正确粒度是 App；纯文本学习对这类伪装营销有平台期（p≈0.62 封顶，见 10-21 实验）。
     *
     * 样本刻意用「模型判错」的形态（伪装成个人回忆的营销，p≈0.06）——
     * 这正是先验桶增长的真实来源：基础模型已判对的样本梯度趋零，学不出先验权重。
     */
    @Test
    fun pureAdAppBucketUnlocksHigherCap() {
        val m = base()
        val app = FeatureHasher.appKey("com.baidu.netdisk")
        val chA = 0x7A7A
        val chB = 0x5B5B
        val pureAd = listOf(
            SpamTuner.Sample(
                "「订阅故事已送达」去年今天的这个瞬间，都和美食相关👉",
                spam = true, channelKey = chA, appKey = app,
            ),
            SpamTuner.Sample(
                "「订阅故事已送达」2020年的今天，你拍的照片里都有谁🤔",
                spam = true, channelKey = chB, appKey = app,
            ),
            SpamTuner.Sample(
                "「订阅故事已送达」还记得那天做了什么吗，回顾一下吧",
                spam = true, channelKey = 0, appKey = app,
            ),
        )
        assertTrue(
            "vc88 重训练后：基础模型应直接识别该模板家族（此前 p≈0.06 的盲区已消除）",
            m.score(pureAd[0].text) >= 0.7,
        )
        // App 桶保留价值：未来出现新的伪装模板家族时，学 3 条即可让整个 App 的先验接管
        val delta = SpamTuner.fit(m, pureAd)
        val w = deltaAt(delta, app)
        assertTrue(
            "纯广告 App 应建立非零先验桶（实际 $w）",
            w > 0f,
        )
        assertTrue(
            "先验桶幅值仍应受 ±${SpamTuner.PURE_CHANNEL_BIAS_CAP} 约束（实际 $w）",
            w <= SpamTuner.PURE_CHANNEL_BIAS_CAP + 1e-4f,
        )
        // 关键场景：下一波广告换了一个**全新的 channelId**，App 桶仍然生效（渠道轮换被破解）
        val nextAdCh = 0x3C3C
        assertTrue(
            "换 channelId 的新广告应被 App 先验推过阈值",
            m.withDelta(delta).score(
                "「订阅故事已送达」2024年的今天，有一段值得重温的记忆",
                appKey = app,
                channelKey = nextAdCh,
            ) >= 0.7,
        )
        // 渠道桶已收起：App 桶存在时渠道桶不应再占用幅值
        assertEquals(
            "纯 App 场景下渠道桶应被收起（App 桶接管）",
            0f,
            deltaAt(delta, chA),
        )
    }

    /** 混杂 App（如抖音：同 App 既发私信又发广告）不得建立 App 桶 —— 防止 App 级误杀 */
    @Test
    fun mixedAppGetsNoAppBucket() {
        val m = base()
        val app = FeatureHasher.appKey("com.ss.android.ugc.aweme")
        val mixed = buildList {
            repeat(6) { add(SpamTuner.Sample("营销文本变体$it", spam = true, channelKey = 0, appKey = app)) }
            repeat(4) { add(SpamTuner.Sample("私信文本变体$it", spam = false, channelKey = 0, appKey = app)) }
        }
        val delta = SpamTuner.fit(m, mixed)
        assertEquals(
            "混杂 App（4/10 少数派 ≥0.2）不得建立 App 先验桶",
            0f,
            deltaAt(delta, app),
        )
    }

    /** 标注不足 3 条的渠道维持 ±2.5 封顶（防少量误标就解锁全渠道拦截） */
    @Test
    fun smallPureChannelStaysCapped() {
        val m = base()
        val ch = 0x6B6B
        val two = (0 until 2).map {
            SpamTuner.Sample("【XX商城】限时秒杀全场5折起，点击领取100元优惠券$it", spam = true, channelKey = ch)
        }
        val delta = SpamTuner.fit(m, two)
        val w = deltaAt(delta, ch)
        assertTrue(
            "标注 <3 条的渠道应维持 ±${SpamTuner.CHANNEL_BIAS_CAP} 封顶，实际 $w",
            kotlin.math.abs(w) <= SpamTuner.CHANNEL_BIAS_CAP + 1e-4f,
        )
    }

    /** 重复点击学习加强的是"这一条文本"，不应把整条渠道的偏置一起放大 */
    @Test
    fun repeatLearningDoesNotAmplifyChannelBias() {
        val m = base()
        val ch = 0x2F2F
        val ads = (0 until 5).map {
            SpamTuner.Sample("限时秒杀领券文本$it", spam = true, channelKey = ch)
        }
        val normal = SpamTuner.Sample("您的包裹已到达驿站", spam = false, channelKey = ch)
        val once = SpamTuner.fit(m, ads.map { it.copy(weight = 1) } + normal)
        val repeated = SpamTuner.fit(
            m,
            listOf(ads[0].copy(weight = 10)) + ads.drop(1) + normal,
        )
        val w1 = kotlin.math.abs(deltaAt(once, ch))
        val w10 = kotlin.math.abs(deltaAt(repeated, ch))
        assertTrue(
            "同一条广告重复学 10 次不应把渠道偏置放大近 10 倍（w1=$w1 w10=$w10）",
            w10 <= w1 * 2f + 0.3f,
        )
    }
}
