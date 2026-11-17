package cc.ytdttj.noticleaner

import cc.ytdttj.noticleaner.ai.SpamModel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 双端一致性校验（Plan.md §5.4）：
 * 用 Python 参考打分器导出的 parity.json 逐条比对 Kotlin 实现的打分，容差 1e-4。
 * parity.json 与 model.bin 由 `python training/train.py` 产出，必须同批生成。
 */
class ParityTest {

    @Serializable
    private data class Row(val text: String, val score: Double)

    private fun model(): SpamModel =
        SpamModel.load(File("src/main/resources/model/model.bin").inputStream())

    private fun parityRows(): List<Row> {
        val f = File("src/test/resources/parity.json")
        return Json { ignoreUnknownKeys = true }.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(Row.serializer()),
            f.readText(),
        )
    }

    @Test
    fun parityTextsScoreMatchPythonReference() {
        val m = model()
        val rows = parityRows()
        assertTrue("parity.json 为空", rows.isNotEmpty())
        for (row in rows) {
            val actual = m.score(row.text)
            assertEquals(
                "score mismatch for: ${row.text.take(40)}",
                row.score,
                actual,
                1e-4,
            )
        }
    }

    @Test
    fun scoreInRangeAndSanity() {
        val m = model()
        for (t in listOf("", "你好", "【XX商城】限时秒杀！全场5折起，点击领取100元优惠券！")) {
            val p = m.score(t)
            assertTrue("score out of range: $p", p in 0.0..1.0)
        }
    }
}
