package cc.ytdttj.noticleaner.notify.island

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PaymentExtractor 多币种/方向解析单测（islandplan.md §七 M1）。
 */
class PaymentExtractorTest {

    private fun extract(title: String, content: String = "") = PaymentExtractor.extract(title, content)

    // ---- 人民币基础 ----

    @Test
    fun `支付宝符号前置 支出`() {
        val p = extract("支付宝支付通知", "您已支付 ¥25.00")
        assertNotNull(p)
        assertEquals(PaymentExtractor.Currency.CNY, p!!.currency)
        assertEquals("25.00", p.amountText)
        assertEquals(PaymentExtractor.Direction.OUT, p.direction)
        assertEquals("-¥25.00\u2009", p.capsuleText)
    }

    @Test
    fun `千分位元后缀 入账`() {
        val p = extract("招商银行", "您尾号1234的账户入账人民币1,234.50元")
        assertNotNull(p)
        assertEquals(PaymentExtractor.Currency.CNY, p!!.currency)
        assertEquals("1,234.50", p.amountText)
        assertEquals(PaymentExtractor.Direction.IN, p.direction)
    }

    @Test
    fun `退款通知含支付字样 归收入`() {
        val p = extract("微信支付", "您支付的交易已退款，退款金额￥9.90已原路退回")
        assertNotNull(p)
        assertEquals(PaymentExtractor.Direction.IN, p!!.direction)
        assertEquals("+¥9.90\u2009", p.capsuleText)
    }

    // ---- 外币（VISA 信用卡场景） ----

    @Test
    fun `美元代码前置`() {
        val p = extract("招商银行信用卡", "您的VISA卡消费 USD 25.00")
        assertNotNull(p)
        assertEquals(PaymentExtractor.Currency.USD, p!!.currency)
        assertEquals("25.00", p.amountText)
        assertEquals(PaymentExtractor.Direction.OUT, p.direction)
        assertEquals("-US$25.00\u2009", p.capsuleText)
    }

    @Test
    fun `美元符号前置`() {
        val p = extract("消费提示", "您消费$12.34")
        assertNotNull(p)
        assertEquals(PaymentExtractor.Currency.USD, p!!.currency)
    }

    @Test
    fun `外币双金额取原币 折算入展开行`() {
        val p = extract("招商银行", "交易金额 USD 25.00，折合人民币 ¥180.25")
        assertNotNull(p)
        assertEquals(PaymentExtractor.Currency.USD, p!!.currency)
        assertEquals("25.00", p.amountText)
        assertEquals("¥180.25", p.convertedCnyText)
    }

    @Test
    fun `欧元符号 到账`() {
        val p = extract("到账通知", "您有一笔 €20 入账")
        assertNotNull(p)
        assertEquals(PaymentExtractor.Currency.EUR, p!!.currency)
        assertEquals(PaymentExtractor.Direction.IN, p.direction)
        assertEquals("+€20\u2009", p.capsuleText)
    }

    @Test
    fun `日元无小数`() {
        val p = extract("消费", "您消费日元1,500")
        assertNotNull(p)
        assertEquals(PaymentExtractor.Currency.JPY, p!!.currency)
        assertEquals("1,500", p.amountText)
    }

    @Test
    fun `港币代码 还款`() {
        val p = extract("还款提醒", "您已还款 HK$100")
        assertNotNull(p)
        assertEquals(PaymentExtractor.Currency.HKD, p!!.currency)
        assertEquals(PaymentExtractor.Direction.OUT, p.direction)
    }

    // ---- 负例 ----

    @Test
    fun `裸数字不算金额`() {
        assertNull(extract("账户余额", "您的账户余额3000，明细请查看APP"))
        assertNull(extract("账单", "本期应还 3,000.50 账单日5号")) // 无币种特征的裸数字
    }

    @Test
    fun `零金额过滤`() {
        assertNull(extract("扣款通知", "手续费扣款0.00元"))
    }

    @Test
    fun `无金额通知`() {
        assertNull(extract("动账提醒", "您有一笔交易请查看详情"))
    }

    @Test
    fun `标题正文合并匹配`() {
        val p = PaymentExtractor.extract("支付成功", "金额：¥128.00 商户：星巴克")
        assertNotNull(p)
        assertEquals("128.00", p!!.amountText)
    }

    // ---- 真实文案回归（2026-09-20 招行通知未上岛排查：解析非故障点，锁定行为） ----

    @Test
    fun `招行快捷支付扣款通知`() {
        val p = extract(
            "招商银行",
            "您账户****1234于2026年9月21日08:31在【财付通-微信支付-中铁网络】发生快捷支付扣款，人民币111.00",
        )
        assertNotNull(p)
        assertEquals(PaymentExtractor.Currency.CNY, p!!.currency)
        assertEquals("111.00", p.amountText)
        assertEquals(PaymentExtractor.Direction.OUT, p.direction)
        assertEquals("-¥111.00\u2009", p.capsuleText)
    }

    @Test
    fun `日期数字不误判为金额`() {
        // 无币种特征的日期/时间数字不应抢在真实金额前成为主金额
        val p = extract(
            "招商银行",
            "您账户****1234于2026年9月21日08:31发生快捷支付扣款，人民币111.00",
        )
        assertNotNull(p)
        assertEquals("111.00", p!!.amountText)
    }

    // ---- 2026-09-25 事故回归：招行快捷支付扣款上岛成 +52 收入 ----
    // 根因：全文 contains + 收入词无条件优先，"收款方/收款商户"与尾部营销文案
    // （"收款到账快"）命中收入词表，覆盖了正文的"快捷支付扣款"。
    // 修复：金额邻接窗口内取最近方向词 + "收款方"类名词排除。

    @Test
    fun `招行快捷支付扣款含收款方 仍是支出`() {
        val p = extract(
            "招商银行",
            "您账户****1234于2026年9月25日10:50发生快捷支付扣款，收款方：某某科技有限公司，人民币52.00",
        )
        assertNotNull(p)
        assertEquals("52.00", p!!.amountText)
        assertEquals(PaymentExtractor.Direction.OUT, p.direction)
        assertEquals("-¥52.00\u2009", p.capsuleText)
    }

    @Test
    fun `招行快捷支付扣款商户为微信红包 仍是支出`() {
        // 2026-09-25 10:50 真机事故原文：商户通道名【财付通-微信支付-微信红包】
        // 里的"红包"命中收入词表，把"快捷支付扣款"覆盖判成收入（+¥52.00）
        val p = extract(
            "招商银行",
            "您账户1662于09月25日10:50在【财付通-微信支付-微信红包】发生快捷支付扣款，人民币52.00",
        )
        assertNotNull(p)
        assertEquals("52.00", p!!.amountText)
        assertEquals(PaymentExtractor.Direction.OUT, p.direction)
        assertEquals("-¥52.00\u2009", p.capsuleText)
    }

    @Test
    fun `招行扣款含尾部营销文案 仍是支出`() {
        val p = extract(
            "招商银行",
            "您账户****1234于2026年9月25日10:50快捷支付扣款人民币52.00元，交易后余额1,000.00元。" +
                "下载招商银行App，转账0手续费，收款实时到账，快去体验吧",
        )
        assertNotNull(p)
        assertEquals("52.00", p!!.amountText)
        assertEquals(PaymentExtractor.Direction.OUT, p.direction)
    }

    @Test
    fun `真实收款仍是收入`() {
        // 无"方/人/商/账/户"后缀的"收款"是动作，应判收入
        val p = extract("微信支付", "微信收款 ¥25.00")
        assertNotNull(p)
        assertEquals(PaymentExtractor.Direction.IN, p!!.direction)
    }

    @Test
    fun `工行入账含消费字样 仍归收入`() {
        // "您尾号1234账户入账人民币500元（原消费退款）"——收入词紧邻金额
        val p = extract("工商银行", "您尾号1234账户入账人民币500.00元，摘要：消费退款")
        assertNotNull(p)
        assertEquals(PaymentExtractor.Direction.IN, p!!.direction)
    }
}
