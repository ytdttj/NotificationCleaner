package cc.ytdttj.noticleaner.notify.island

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同金额跨来源去重状态机（2.2.2）：钱包押后等银行、银行优先、窗口内只留一条。
 */
class IslandTxnDeduperTest {

    private var now = 1_000L
    private val deduper = IslandTxnDeduper(
        clock = { now },
        walletHoldMs = 2_500L,
        windowMs = 8_000L,
    )
    private val key = IslandTxnDeduper.TxnKey("CNY", "OUT", "30.1")

    private fun hold(pkg: String, k: IslandTxnDeduper.TxnKey = key): IslandTxnDeduper.Decision.Hold {
        val d = deduper.decide(pkg, k)
        assertTrue("expected Hold, was $d", d is IslandTxnDeduper.Decision.Hold)
        return d as IslandTxnDeduper.Decision.Hold
    }

    private fun post(pkg: String, k: IslandTxnDeduper.TxnKey = key): IslandTxnDeduper.Decision.Post {
        val d = deduper.decide(pkg, k)
        assertTrue("expected Post, was $d", d is IslandTxnDeduper.Decision.Post)
        return d as IslandTxnDeduper.Decision.Post
    }

    @Test
    fun `银行先到立即上岛 钱包随后跳过`() {
        val p = post("cmb.pb")
        assertEquals(IslandTxnDeduper.Source.BANK, p.source)
        assertTrue(p.dismissIds.isEmpty())
        assertTrue(deduper.recordPosted(key, p.source, 9001))
        assertSame(IslandTxnDeduper.Decision.Skip, deduper.decide("com.eg.android.AlipayGphone", key))
    }

    @Test
    fun `钱包等待期内银行到达 取消钱包`() {
        val h = hold("com.eg.android.AlipayGphone")
        now += 500
        val p = post("cmb.pb")
        assertEquals(IslandTxnDeduper.Source.BANK, p.source)
        now += h.delayMs
        assertFalse(deduper.confirmHold(h.token))
    }

    @Test
    fun `没有银行时 钱包押后到点仍上岛`() {
        val h = hold("com.eg.android.AlipayGphone")
        now += h.delayMs
        assertTrue(deduper.confirmHold(h.token))
        assertTrue(deduper.recordPosted(key, IslandTxnDeduper.Source.WALLET, 9002))
    }

    @Test
    fun `两条钱包通知只保留一条`() {
        val h = hold("com.eg.android.AlipayGphone")
        assertSame(IslandTxnDeduper.Decision.Skip, deduper.decide("com.eg.android.AlipayGphone", key))
        now += h.delayMs
        assertTrue(deduper.confirmHold(h.token))
    }

    @Test
    fun `银行晚于钱包上岛时返回钱包id供撤销`() {
        val h = hold("com.eg.android.AlipayGphone")
        now += h.delayMs
        assertTrue(deduper.confirmHold(h.token))
        assertTrue(deduper.recordPosted(key, IslandTxnDeduper.Source.WALLET, 9003))
        now += 1_000
        val p = post("cmb.pb")
        assertEquals(listOf(9003), p.dismissIds)
    }

    @Test
    fun `同金额银行只上一条`() {
        val p = post("cmb.pb")
        assertTrue(deduper.recordPosted(key, p.source, 9004))
        assertSame(IslandTxnDeduper.Decision.Skip, deduper.decide("com.icbc", key))
    }

    @Test
    fun `不同金额不互相去重`() {
        val p = post("cmb.pb")
        assertTrue(deduper.recordPosted(key, p.source, 9005))
        val other = IslandTxnDeduper.TxnKey("CNY", "OUT", "30.11")
        post("com.icbc", other)
    }

    @Test
    fun `不同方向不互相去重`() {
        val p = post("cmb.pb")
        assertTrue(deduper.recordPosted(key, p.source, 9006))
        post("com.icbc", IslandTxnDeduper.TxnKey("CNY", "IN", "30.1"))
    }

    @Test
    fun `窗口外同金额视为新交易`() {
        val p = post("cmb.pb")
        assertTrue(deduper.recordPosted(key, p.source, 9007))
        now += 8_001
        post("cmb.pb")
    }

    @Test
    fun `钱包提交期间银行抢占 返回false供撤销`() {
        val h = hold("com.eg.android.AlipayGphone")
        now += h.delayMs
        assertTrue(deduper.confirmHold(h.token))
        // 钱包正在构建/提交时银行到达并先完成登记
        now += 10
        val p = post("cmb.pb")
        assertTrue(deduper.recordPosted(key, p.source, 9008))
        assertFalse(deduper.recordPosted(key, IslandTxnDeduper.Source.WALLET, 9009))
    }

    @Test
    fun `金额规范化 9点90 与 9点9 等价`() {
        assertEquals("9.9", IslandTxnDeduper.canonicalAmount("9.90"))
        assertEquals("9.9", IslandTxnDeduper.canonicalAmount("9.9"))
        assertEquals("1234.5", IslandTxnDeduper.canonicalAmount("1,234.50"))
    }
}
