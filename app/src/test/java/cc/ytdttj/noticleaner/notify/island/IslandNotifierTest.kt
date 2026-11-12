package cc.ytdttj.noticleaner.notify.island

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 微信支付场景闸（2.2.2：只看正文，品牌名不计入支付动作）。
 */
class IslandNotifierTest {

    @Test
    fun `优惠回执正文不构成微信支付场景`() {
        // 2026-11-12 真机事故原文：正文只出现服务号名"微信支付"，没有交易动作
        assertFalse(
            IslandNotifier.isWeChatPaymentContext(
                "[2条]微信支付: 优惠已使用，本次节省¥11.1",
            ),
        )
    }

    @Test
    fun `已支付正文仍是微信支付场景`() {
        assertTrue(IslandNotifier.isWeChatPaymentContext("已支付¥9.90"))
    }

    @Test
    fun `微信支付凭证正文仍是支付场景`() {
        assertTrue(IslandNotifier.isWeChatPaymentContext("微信支付凭证-¥45.50 商户消费"))
    }

    @Test
    fun `聊天里的金额不是支付场景`() {
        assertFalse(IslandNotifier.isWeChatPaymentContext("[2条]李玉林: 领10687元加价券包，旧机高价卖！"))
    }

    @Test
    fun `微信支付成功正文仍是支付场景`() {
        assertTrue(IslandNotifier.isWeChatPaymentContext("微信支付成功¥9.90"))
    }
}
