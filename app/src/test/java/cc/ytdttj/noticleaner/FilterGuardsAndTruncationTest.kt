package cc.ytdttj.noticleaner

import cc.ytdttj.noticleaner.ai.FeatureHasher
import cc.ytdttj.noticleaner.ai.SpamModel
import cc.ytdttj.noticleaner.ai.takeCodepoints
import cc.ytdttj.noticleaner.notify.FilterGuards
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 1.3.2 护栏与截断回归测试：
 * - P1-3：HARD_ALLOW_WORDS_NORMALIZED 逐词校验 `normalize(原始词) == 归一化词`，
 *   并覆盖"归一化后才命中"（分隔符变体，有意扩大的语义）与"含 x/数字的词会被删除"
 *   （新增词的坑，见 FilterGuards 注释约束）两类用例。
 * - P1-2：入库截断 500 codepoint 后 AI 打分逐位不变（joined 前 500 codepoint 保持）。
 */
class FilterGuardsAndTruncationTest {

    private fun model(): SpamModel =
        SpamModel.load(File("src/main/resources/model/model.bin").inputStream())

    @Test
    fun normalizedWordTableMatchesNormalize() {
        assertEquals(
            "两份词表必须一一对应",
            FilterGuards.HARD_ALLOW_WORDS.size,
            FilterGuards.HARD_ALLOW_WORDS_NORMALIZED.size,
        )
        for (i in FilterGuards.HARD_ALLOW_WORDS.indices) {
            val raw = FilterGuards.HARD_ALLOW_WORDS[i]
            val normalized = FilterGuards.HARD_ALLOW_WORDS_NORMALIZED[i]
            assertEquals(
                "词表第 ${i} 项的归一化形态不符（新增词需满足 normalize(词) == 归一化词）",
                normalized,
                FeatureHasher.normalize(raw),
            )
        }
    }

    @Test
    fun separatorVariantsHitAfterNormalize() {
        // 有意的语义扩大（1.3.2Plan P1-3 修订2）：分隔符变体从"不命中"变"命中"
        val normalized = FeatureHasher.normalize("O T P verification  code 验 证 码 动 态 码 校 验 码 one-time")
        for (word in FilterGuards.HARD_ALLOW_WORDS_NORMALIZED) {
            assertTrue("变体应命中: $word", normalized.contains(word))
        }
    }

    @Test
    fun wordsContainingDeletedCharsAreStripped() {
        // 反向风险的存档用例：normalize 删除 x/数字/标点——含这些字符的词改判后会漏放行
        assertEquals("verificationcode", FeatureHasher.normalize("verificationcodex"))
        assertEquals("verificationcode", FeatureHasher.normalize("veri4fication5code"))
        assertTrue(FeatureHasher.normalize("x").isEmpty())
        assertTrue(FeatureHasher.normalize("483920").isEmpty())
    }

    @Test
    fun truncationKeepsScoreBitIdentical() {
        val m = model()
        val longTitle = "【超级商城】限时秒杀".repeat(80) // 远超 500 codepoint
        val longContent = "全场五折起点击领取优惠券".repeat(80)
        val rawJoined = "$longTitle\n$longContent"
        val truncatedJoined = listOf(
            longTitle.takeCodepoints(FeatureHasher.MAX_TEXT_LEN),
            longContent.takeCodepoints(FeatureHasher.MAX_TEXT_LEN),
        ).joinToString("\n")
        for (chKey in listOf(null, FeatureHasher.channelKey("cc.test.app", "promo"))) {
            assertEquals(
                "截断前后打分必须逐位一致",
                m.score(rawJoined, chKey),
                m.score(truncatedJoined, chKey),
                0.0,
            )
        }
    }
}
