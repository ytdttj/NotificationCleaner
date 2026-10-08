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

    // ---- Dev 10（P1-2 回归）：零额只能跳过"这一笔"，不能让整条通知提取失败 ----

    @Test
    fun `主金额后的零额手续费不再丢弃整条`() {
        // 旧实现：循环内 `if (isZero) return null` → 主金额 25.00 已命中，
        // 扫到"手续费0.00元"整条返回 null → 银行扣款不上岛且无提示
        val p = extract("支付成功", "支付¥25.00（手续费0.00元）")
        assertNotNull(p)
        assertEquals("25.00", p!!.amountText)
    }

    @Test
    fun `零额在前真金额在后仍取真金额`() {
        val p = extract("支付成功", "优惠0.00元，实付25.00元")
        assertNotNull(p)
        assertEquals("25.00", p!!.amountText)
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

    // ---- 2026-10-06 误上岛排查回归：亲情卡余额 / 微信券包 ----

    @Test
    fun `支付宝亲情卡 标题余额不抢主金额`() {
        // 2026-10-05 21:23 真机事故原文：标题"剩余9968.90元"是存量，
        // 旧实现取第一笔命中 → -¥9968.90 OUT 错误上岛（真交易是正文消费30.10元）
        val p = extract(
            "你的亲情卡剩余9968.90元",
            "你***啊（**琪）使用你赠送的亲情卡消费30.10元",
        )
        assertNotNull(p)
        assertEquals("30.10", p!!.amountText)
        assertEquals(PaymentExtractor.Direction.OUT, p.direction)
    }

    @Test
    fun `余额前缀金额被跳过 落到真交易金额`() {
        val p = extract("银行通知", "余额1,000.00元，消费30.10元")
        assertNotNull(p)
        assertEquals("30.10", p!!.amountText)
    }

    @Test
    fun `应还账单金额不误判为交易`() {
        // "本期应还3000.50元"是账单不是动账，整条无真交易金额 → null
        assertNull(extract("账单提醒", "您本期应还3,000.50元，请按时还款"))
    }

    @Test
    fun `余额宝收益不受余额关键词误伤`() {
        // "余额宝"三字结尾不命中"余额"前缀，收益金额仍可解析
        val p = extract("余额宝", "余额宝收益发放2.35元")
        assertNotNull(p)
        assertEquals("2.35", p!!.amountText)
    }

    @Test
    fun `微信营销券包文案 方向未知`() {
        // 2026-10-06 09:34 真机事故原文（两段聊天文本均无支付动作词）：
        // 旧实现金额解析成功即上岛；现由 IslandNotifier 方向闸拦截（UNKNOWN）
        val p1 = extract(
            "李玉林",
            "[2条]李玉林: 老朋友，假期没剩几天啦！最近好多老客户都趁这波活动来出旧机~\n" +
                "别等旧机贬值再卖，先领10687元专属",
        )
        assertNotNull(p1)
        assertEquals(PaymentExtractor.Direction.UNKNOWN, p1!!.direction)

        val p2 = extract("李玉林", "[2条]李玉林: [小程序] 领10687元加价券包，旧机高价卖！")
        assertNotNull(p2)
        assertEquals(PaymentExtractor.Direction.UNKNOWN, p2!!.direction)
    }

    @Test
    fun `多金额且窗口无方向词 宁可未知也不猜`() {
        // 方向词紧邻的是另一笔金额时，全文回退会把方向错配给主金额
        // （亲情卡事故的第二根因）——多候选时直接 UNKNOWN。
        // 注意方向词"消费"距主金额 5000 需超出 WINDOW_AFTER=16 字符，制造窗口未命中。
        val p = extract(
            "通知",
            "账户今日流水5000.00元（含多笔往来），明细里显示您昨天消费300.00元",
        )
        assertNotNull(p)
        assertEquals("5000.00", p!!.amountText)
        assertEquals(PaymentExtractor.Direction.UNKNOWN, p.direction)
    }

    @Test
    fun `微信红包与转账文案仍可解析`() {
        // 守卫不能误伤真支付：红包/转账关键词通知金额方向正常
        val p1 = extract("陈一朵", "[微信红包]恭喜发财，大吉大利")
        assertNull(p1) // 红包文案无金额数字，不上岛（金额在红包详情页）

        val p2 = extract("微信支付", "微信支付凭证-¥45.50 商户消费")
        assertNotNull(p2)
        assertEquals("45.50", p2!!.amountText)
        assertEquals(PaymentExtractor.Direction.OUT, p2.direction)
    }
}
