package cc.ytdttj.noticleaner

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
        File("src/main/assets/model/model.bin").inputStream(),
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
}
