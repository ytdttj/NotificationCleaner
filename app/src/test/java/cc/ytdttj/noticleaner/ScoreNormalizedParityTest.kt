package cc.ytdttj.noticleaner

import cc.ytdttj.noticleaner.ai.FeatureHasher
import cc.ytdttj.noticleaner.ai.SpamModel
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * 1.2.0（ImprovePlan P1-2）对拍校验：
 * scoreNormalized（接收已归一化文本的新热路径）与 score（原始文本路径）必须逐位一致——
 * 两者共用相同的 counts 构建（countsOfNormalized(normalize(x)) == counts(x)），
 * 确保消除重复 normalize 不改变决策结果。
 */
class ScoreNormalizedParityTest {

    private fun model(): SpamModel =
        SpamModel.load(File("src/main/resources/model/model.bin").inputStream())

    private val samples = listOf(
        "",
        "你好",
        "a",
        "【XX商城】限时秒杀！全场5折起，点击领取100元优惠券！",
        "【淘】宝贝已发货，>点击查<<看物流 ＞＞ https://example.com/x?a=1&b=2",
        "您的验证码是 483920，5 分钟内有效。OTP verification code",
        "🎉🎉 关注公众号 领红包 免费领取 iPhone 17 Pro Max 🧧🧧",
        "x X mixed 空格　全角空白\n换行\t制表 abc123 ｘｘｘ",
        "限时折扣>>今晚8点开抢，前100名半价！！错过等一年！",
    )

    @Test
    fun scoreNormalizedMatchesScoreWithoutChannel() {
        val m = model()
        for (raw in samples) {
            val normalized = FeatureHasher.normalize(raw)
            val expected = m.score(raw)
            val actual = m.scoreNormalized(normalized, null)
            assertEquals(
                "no-channel mismatch for: ${raw.take(30)}",
                expected,
                actual,
                0.0,
            )
        }
    }

    @Test
    fun scoreNormalizedMatchesScoreWithChannel() {
        val m = model()
        val chKey = FeatureHasher.channelKey("cc.test.app", "promo")
        for (raw in samples) {
            val normalized = FeatureHasher.normalize(raw)
            val expected = m.score(raw, chKey)
            val actual = m.scoreNormalized(normalized, chKey)
            assertEquals(
                "channel mismatch for: ${raw.take(30)}",
                expected,
                actual,
                0.0,
            )
        }
    }

    @Test
    fun countsOfNormalizedMatchesCounts() {
        for (raw in samples) {
            val expected = FeatureHasher.counts(raw).asMap()
            val actual = FeatureHasher.countsOfNormalized(FeatureHasher.normalize(raw)).asMap()
            assertEquals(
                "counts mismatch for: ${raw.take(30)}",
                expected,
                actual,
            )
        }
    }
}
