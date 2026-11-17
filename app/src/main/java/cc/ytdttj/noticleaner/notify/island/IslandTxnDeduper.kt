package cc.ytdttj.noticleaner.notify.island

import java.math.BigDecimal

/**
 * 同一笔动账的跨来源去重（2.2.2）。
 *
 * 场景：亲属卡/银行卡消费会在极短时间内产生多条通知——
 * 支付宝消费通知 + 支付宝亲属卡扣款通知 + 招商银行扣款通知。
 * 三者金额、方向、币种相同，只应让**银行**那条上岛，其余仍作为普通通知展示。
 *
 * 策略：
 * - 钱包来源（支付宝/微信/云闪付）先押后 [walletHoldMs]：期间同金额银行动账到达，
 *   直接取消本次上岛；没有银行到来才在延迟后补上岛（钱包余额支付不能永远不上岛）。
 * - 银行来源立即上岛；若同金额钱包岛已展示（银行比押后窗口还晚），返回其 id 供调用方撤销。
 * - 同币种 + 同方向 + 同金额 + [windowMs] 内视为同一笔。
 *
 * 纯 Kotlin + 注入时钟，便于单测；方法均为 synchronized，可被通知线程与押后调度线程并发调用。
 */
class IslandTxnDeduper(
    private val clock: () -> Long = System::currentTimeMillis,
    private val walletHoldMs: Long = DEFAULT_WALLET_HOLD_MS,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
) {

    enum class Source { WALLET, BANK }

    /** 金额同一性键：币种 + 方向 + 规范化数值（9.90 与 9.9 等价） */
    data class TxnKey(val currency: String, val direction: String, val amount: String)

    sealed class Decision {
        /** 同金额动账已上岛或已在等待，本条不再上岛 */
        object Skip : Decision()

        /** 立即上岛；[dismissIds] 是同金额银行到达时需要撤销的钱包岛 id */
        data class Post(val source: Source, val dismissIds: List<Int>) : Decision()

        /** 钱包来源押后 [delayMs]；到点用 [token] 调 [confirmHold] 决定是否仍上岛 */
        data class Hold(val token: Long, val delayMs: Long) : Decision()
    }

    private data class Recent(val key: TxnKey, val source: Source, val at: Long, val notificationId: Int)
    private data class Pending(val key: TxnKey, val at: Long)

    private val recent = mutableListOf<Recent>()
    private val pending = linkedMapOf<Long, Pending>()
    private var nextToken = 1L

    /** 判定一条候选动账是否可以上岛（以及是否需要押后/撤销其它岛） */
    @Synchronized
    fun decide(pkg: String, key: TxnKey): Decision {
        val now = clock()
        prune(now)
        return if (pkg in WALLET_PACKAGES) decideWallet(key, now) else decideBank(key, now)
    }

    private fun decideWallet(key: TxnKey, now: Long): Decision {
        if (recent.any { it.key == key }) return Decision.Skip
        // 同一笔交易可能有两条钱包通知（消费通知 + 亲属卡扣款通知），只保留第一条
        if (pending.any { it.value.key == key }) return Decision.Skip
        val token = nextToken++
        pending[token] = Pending(key, now)
        return Decision.Hold(token, walletHoldMs)
    }

    private fun decideBank(key: TxnKey, now: Long): Decision {
        if (recent.any { it.key == key && it.source == Source.BANK }) return Decision.Skip
        // 银行优先级最高：撤掉已展示的同金额钱包岛，并取消还在等待的钱包
        val walletIds = recent.filter { it.key == key && it.source == Source.WALLET }
            .map { it.notificationId }
            .filter { it > 0 }
        recent.removeAll { it.key == key && it.source == Source.WALLET }
        pending.entries.removeAll { it.value.key == key }
        return Decision.Post(Source.BANK, walletIds)
    }

    /**
     * 押后到点确认：期间银行已上岛（或本次已被取消）返回 false。
     * true 时会**预占**一条 recent 槽位（notificationId=0），避免确认与提交之间
     * 银行并发到达造成双岛；提交失败由 [forgetPosted] 撤回，提交成功由 [recordPosted] 回填。
     */
    @Synchronized
    fun confirmHold(token: Long): Boolean {
        val p = pending.remove(token) ?: return false
        val now = clock()
        prune(now)
        if (recent.any { it.key == p.key }) return false
        recent += Recent(p.key, Source.WALLET, now, notificationId = 0)
        return true
    }

    /**
     * 登记已提交的岛通知。
     * @return false = 提交期间同金额银行已上岛，调用方应立即撤销这条钱包岛
     */
    @Synchronized
    fun recordPosted(key: TxnKey, source: Source, notificationId: Int): Boolean {
        val now = clock()
        prune(now)
        if (source == Source.WALLET && recent.any { it.key == key && it.source == Source.BANK }) {
            recent.removeAll { it.key == key && it.source == Source.WALLET && it.notificationId == 0 }
            return false
        }
        val reserved = recent.indexOfLast {
            it.key == key && it.source == source && it.notificationId == 0
        }
        if (reserved >= 0) {
            recent[reserved] = recent[reserved].copy(at = now, notificationId = notificationId)
        } else {
            recent += Recent(key, source, now, notificationId)
        }
        return true
    }

    /** 提交失败时撤回预占槽位 */
    @Synchronized
    fun forgetPosted(key: TxnKey, source: Source) {
        recent.removeAll { it.key == key && it.source == source && it.notificationId == 0 }
    }

    private fun prune(now: Long) {
        recent.removeAll { now - it.at > windowMs }
        pending.entries.removeAll { now - it.value.at > walletHoldMs + windowMs }
    }

    companion object {
        /** 钱包押后时长：覆盖银行推送的正常到达延迟，同时尽量不拖慢钱包余额支付 */
        const val DEFAULT_WALLET_HOLD_MS = 2_500L

        /** 同金额判定窗口：必须大于押后时长，晚到的银行仍能撤掉已展示的钱包岛 */
        const val DEFAULT_WINDOW_MS = 8_000L

        /** 钱包类来源：同一笔交易被多个 App 重复通知的高发端，其余白名单包按银行处理 */
        val WALLET_PACKAGES = setOf(
            "com.eg.android.AlipayGphone", // 支付宝
            "com.tencent.mm",              // 微信
            "com.unionpay",                // 云闪付
        )

        /** 金额规范化为数值文本：9.90 / 9.9 视为同一笔 */
        fun canonicalAmount(amountText: String): String = runCatching {
            BigDecimal(amountText.replace(",", "")).stripTrailingZeros().toPlainString()
        }.getOrDefault(amountText)
    }
}
