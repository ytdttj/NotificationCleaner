package cc.ytdttj.noticleaner.notify.island

import java.math.BigDecimal

/**
 * 支付金额/币种/方向解析器（islandplan.md §1.2/§1.3，纯 Kotlin 可单测）。
 *
 * 命中规则：金额必须带币种特征（符号 / ISO 代码 / 中文币种 / "元"后缀），
 * 裸数字（如"余额 3000"）不认定为支付金额，防止误上岛。
 */
object PaymentExtractor {

    enum class Direction { IN, OUT, UNKNOWN }

    enum class Currency(val display: String) {
        CNY("¥"), USD("US$"), EUR("€"), GBP("£"), JPY("JP¥"), HKD("HK$"),
    }

    data class Payment(
        val currency: Currency,
        /** 规范化金额文本（保留千分位），如 "1,234.50" */
        val amountText: String,
        val direction: Direction,
        /** 外币双金额场景的人民币折算（如 "¥180.25"），无则 null */
        val convertedCnyText: String?,
    ) {
        /** 摘要态胶囊文本："-US$12.34" / "+¥25.00"；末尾 thin space 防压缩字形被右边界裁切 */
        val capsuleText: String
            get() = when (direction) {
                Direction.IN -> "+"
                Direction.OUT -> "-"
                Direction.UNKNOWN -> ""
            } + currency.display + amountText + "\u2009"

        val isExpense: Boolean get() = direction == Direction.OUT
    }

    /** 金额数字：千分位或整数，可选两位小数 */
    private const val NUM = "(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{1,2})?"

    /**
     * 币种前置 token（US$/HK$ 必须排在 $ 之前，否则被单 $ 吃掉）+
     * 金额，或 "xx元" 后缀形态（group3）。
     * lookbehind/lookahead 防止从千分位中间或半截数字起配。
     */
    private val PATTERN = Regex(
        "(?<![\\d.,])(US\\$|HK\\$|[¥￥\\$€£]|USD|EUR|JPY|GBP|HKD|CNY|RMB|美元|欧元|日元|英镑|港币|人民币)" +
            "\\s*($NUM)(?!\\d)" +
            "|(?<![\\d.,])($NUM)\\s*元(?!\\d)",
    )

    /**
     * 余额/额度类关键词（2026-10-06 修复"亲情卡剩余9968.90元误上岛"）：
     * 金额紧邻在这些词后面时是**存量**不是交易（"剩余9968.90元""余额1,000.00元"
     * "本期应还3,000.50元"），必须跳过这笔、继续找真交易金额。
     * 注意不能用"余额"前缀一票否决"余额宝收益2.35元"——"余额宝"三字结尾不命中"余额"。
     */
    private val BALANCE_WORDS = listOf(
        "剩余", "余额", "可用", "额度", "总额", "净额", "应还", "待还", "已还",
    )

    /** 金额前最多回看几个字符找余额关键词（容忍"额度：5000元"这类分隔符） */
    private const val BALANCE_LOOKBACK = 6

    /** 紧邻金额前的文本是否为余额/额度语义（存量为非交易金额） */
    private fun isBalanceLike(text: String, matchStart: Int): Boolean {
        val from = (matchStart - BALANCE_LOOKBACK).coerceAtLeast(0)
        val prefix = text.substring(from, matchStart).trimEnd(' ', '\u3000', '：', ':', ' ')
        return BALANCE_WORDS.any { prefix.endsWith(it) }
    }

    /** 收入关键词优先于支出（"您支付的交易已退款"应归收入） */
    private val INCOME_WORDS = listOf(
        "收入", "入账", "到账", "收款", "退款", "转入", "存款", "存入", "红包",
        "工资", "代发", "返现", "奖励", "利息", "赔付", "理赔", "提现",
    )
    private val EXPENSE_WORDS = listOf(
        "支付", "付款", "消费", "支出", "扣款", "划扣", "扣除", "转账", "转出",
        "取现", "取款", "分期", "还款", "缴费", "充值",
    )

    // ---- 方向判定（2026-09-25 修复"招行快捷支付扣款上岛成 +52 收入"）----
    //
    // 原实现对全文做 contains 且收入词无条件优先，两个污染源导致判反：
    // 1. 招行交易描述的"收款方/收款商户：xxx"（"收款"命中收入词，覆盖"扣款"）
    // 2. 通知尾部营销文案（"转账0手续费，收款到账快"）
    //
    // 新策略：方向词几乎总紧贴金额（"扣款人民币52.00""收款¥25"），先在金额
    // 邻接窗口内取**距金额最近**的方向词；窗口内没有再全文回退（保持退款语义）。
    // "收款"后跟"方/人/商/账/户"是名词（收款方/收款商户），不算收入动作。
    private const val WINDOW_BEFORE = 40
    private const val WINDOW_AFTER = 16

    /** "收款方/收款人/收款商户/收款账号/收款户名"等名词，不作为收入动作词 */
    private fun isCollecteeNoun(text: String, idx: Int): Boolean =
        text.getOrNull(idx + 2) in setOf('方', '人', '商', '账', '户', '名')

    private fun directionOf(text: String, amountRange: IntRange?, candidates: Int = 1): Direction {
        if (amountRange != null) {
            val from = (amountRange.first - WINDOW_BEFORE).coerceAtLeast(0)
            val to = (amountRange.last + 1 + WINDOW_AFTER).coerceAtMost(text.length)
            val window = text.substring(from, to)
            val amountStart = amountRange.first - from
            val amountEnd = amountRange.last - from
            var best: Direction? = null
            var bestDist = Int.MAX_VALUE
            fun scan(words: List<String>, dir: Direction) {
                for (w in words) {
                    var idx = window.indexOf(w)
                    while (idx >= 0) {
                        if (!(w == "收款" && isCollecteeNoun(window, idx))) {
                            // 词与金额区间的间隔：词在金额前→(金额起点-词尾)；在金额后→(词头-金额终点)
                            val dist = if (idx + w.length <= amountStart) {
                                amountStart - (idx + w.length)
                            } else {
                                idx - amountEnd
                            }
                            if (dist in 0 until bestDist) { bestDist = dist; best = dir }
                        }
                        idx = window.indexOf(w, idx + 1)
                    }
                }
            }
            scan(INCOME_WORDS, Direction.IN)
            scan(EXPENSE_WORDS, Direction.OUT)
            if (best != null) return best
        }
        // 全文回退（收入词优先，保持"您支付的交易已退款"归收入语义）——
        // 2026-10-06 加守卫：仅当全文只有这一笔金额时才允许回退。
        // 多金额时方向词可能属于另一笔（"剩余9968.90元/消费30.10元"把 30.10 旁的
        // "消费"错配给 9968.90 判成 OUT），宁 UNKNOWN 也不猜。
        if (candidates > 1) return Direction.UNKNOWN
        return when {
            INCOME_WORDS.any { text.contains(it) } -> Direction.IN
            EXPENSE_WORDS.any { text.contains(it) } -> Direction.OUT
            else -> Direction.UNKNOWN
        }
    }

    /**
     * 从通知标题+正文中提取第一笔支付金额。
     * 外币双金额（"交易金额 USD 25.00，折合人民币 ¥180.25"）时，
     * 第一笔（原币）作主金额，其后第一笔人民币作折算行。
     */
    fun extract(title: String, content: String): Payment? {
        val text = "$title\n$content"
        var main: Pair<Currency, String>? = null
        var mainRange: IntRange? = null
        var converted: String? = null
        // 有效候选数（非零、非余额类）——方向全文回退的守卫依据
        var candidates = 0
        for (m in PATTERN.findAll(text)) {
            val prefix = m.groupValues[1]
            val currency = if (prefix.isNotEmpty()) tokenCurrency(prefix) else Currency.CNY
            val raw = if (prefix.isNotEmpty()) m.groupValues[2] else m.groupValues[3]
            val amount = normalize(raw) ?: continue
            // 2.0.1 Dev 10（P1-2）：零额只跳过**这一笔**，不再让整条通知提取失败。
            // 旧实现在此 `return null`——"支付¥25.00（手续费0.00元）"这类文案主金额已命中，
            // 扫描到后面的零额时整条被判失败 → 银行扣款不上岛且无任何提示。
            // 末尾 `main ?: return null` 已覆盖"全篇只有零额"的语义（如"手续费0.00元"）。
            if (isZero(amount)) continue
            // 2026-10-06：余额/额度类金额（"剩余9968.90元"）是存量不是交易，
            // 跳过这笔继续找真交易金额——亲情卡通知由此落到正文的"消费30.10元"
            if (isBalanceLike(text, m.range.first)) continue
            candidates++
            if (main == null) {
                main = currency to amount
                mainRange = m.range
            } else if (main.first != Currency.CNY && currency == Currency.CNY && converted == null) {
                converted = "¥$amount"
                break
            }
        }
        val mainHit = main ?: return null
        return Payment(
            currency = mainHit.first,
            amountText = mainHit.second,
            direction = directionOf(text, mainRange, candidates),
            convertedCnyText = converted,
        )
    }

    private fun tokenCurrency(token: String): Currency = when (token) {
        "US$", "USD" -> Currency.USD
        "$" -> Currency.USD
        "€", "EUR" -> Currency.EUR
        "£", "GBP" -> Currency.GBP
        "JPY", "日元" -> Currency.JPY
        "HK$", "HKD", "港币" -> Currency.HKD
        else -> Currency.CNY // ¥ ￥ CNY RMB 人民币 / "元"后缀
    }

    /** 千分位校验 + 规范化；无法转成数值视为误匹配 */
    private fun normalize(raw: String): String? {
        val v = raw.replace(",", "")
        return runCatching { BigDecimal(v) }.getOrNull()?.let {
            // 保留原始千分位写法用于展示（如 "1,234.50"）
            raw
        }
    }

    private fun isZero(amount: String): Boolean =
        runCatching { BigDecimal(amount.replace(",", "")).signum() == 0 }.getOrDefault(false)

}
