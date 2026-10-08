package cc.ytdttj.noticleaner.notify.island

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 支付通知上岛总入口（islandplan.md §一；Dev 5 按 ref/HyperIsland 学习重构）。
 *
 * 发送模式（LSPosed only，Dev 5 起唯一模式）：
 * 认证放行完全依赖 LSPosed 模块端 hook（keepalive/LspEntry）——
 * - SystemUI：canShowFocus/canCustomFocus/checkSignatures 放行（IslandUnlockFocusHook）
 * - xmsf：AuthSession.b(error) 强制成功（XmsfUnlockAuthHook）
 * App 端不再做任何网络层绕过（Dev 4 及之前的 iptables DROP 盲窗已删除：
 * OS3 fail-closed 实测无效，见 islandv2plan 风险 C）。
 *
 * 发送协议（借鉴 ref/HyperIsland 的 IslandDispatcherNotifier）：
 * - 同 id 先 cancel 再 notify（clearBeforePost）：60s 超时窗内重复 id 属"更新"，
 *   HyperOS 对更新不触发岛展示（2026-09-24 23:09 两次模拟测试同 id=9001 实证）
 * - VISIBILITY_SECRET：岛展示但通知栏无痕；测试路径 showNotification=true
 *   时 PRIVATE 留痕，判别认证拒绝
 *
 * 触发条件（2026-10-06 起五者同时满足）：包名白名单 + 文本含币种特征金额 +
 * 方向可判定（非 UNKNOWN）+ 微信需含支付动作词（其余白名单包不要求）+ 通知已放行。
 * 所有路径静默降级，绝不影响通知净化主流程。
 */
object IslandNotifier {

    private const val TAG = "IslandNotifier"
    private const val DEDUP_WINDOW_MS = 60_000L
    private const val ISLAND_TIMEOUT_SEC = 120
    private const val NOTIF_ID_FIRST = 9001
    private const val NOTIF_ID_LAST = 9999

    // ---- 通知模拟（测试用）：Shell 身份通知 tag 携带模拟包名 ----
    /** cmd notification post 的发送者固定为 com.android.shell（系统按 UID 绑定包名，无法伪造他人） */
    const val SHELL_PACKAGE = "com.android.shell"
    /** 模拟来源约定：tag = "island:<pkg>"，管线与岛展示按 <pkg> 处理 */
    const val SIM_TAG_PREFIX = "island:"

    /**
     * 模拟来源解析：Shell 通知的 tag 带 island:<pkg> 前缀时返回模拟包名，
     * 其余返回真实包名。设置页"通知模拟"发通知 → 完整过滤管线 → 上岛按模拟包名展示。
     */
    fun effectivePackage(sbn: StatusBarNotification): String {
        if (sbn.packageName == SHELL_PACKAGE) {
            val tag = sbn.tag
            if (tag != null && tag.startsWith(SIM_TAG_PREFIX)) {
                return tag.substringAfter(SIM_TAG_PREFIX).takeIf { it.isNotBlank() } ?: sbn.packageName
            }
        }
        return sbn.packageName
    }

    /** 该通知是否与岛链路相关（白名单包或模拟通知），用于诊断日志降噪 */
    fun isIslandRelevant(sbn: StatusBarNotification): Boolean =
        effectivePackage(sbn) in packages || sbn.packageName == SHELL_PACKAGE

    /** 默认白名单（islandplan.md §1.1；包名 2026-09-21 真机核对，设置页可逐个开关） */
    val DEFAULT_PACKAGES: Set<String> = linkedSetOf(
        "com.eg.android.AlipayGphone",        // 支付宝
        "com.tencent.mm",                     // 微信
        "com.unionpay",                       // 云闪付
        "com.icbc",                           // 工商银行
        "com.icbc.elife",                     // 工银e生活
        "com.chinamworld.main",               // 建设银行
        "com.android.bankabc",                // 农业银行
        "com.chinamworld.bocmbci",            // 中国银行
        "com.bankcomm.BankComm",              // 交通银行
        "cmb.pb",                             // 招商银行（注意：真实包名即 cmb.pb）
        "com.cmbchina.ccd.pluto.cmbActivity", // 掌上生活（招行信用卡 App）
    )

    /** 白名单中文显示名（设置页 chip + 岛卡片标题用） */
    val PACKAGE_LABELS: Map<String, String> = linkedMapOf(
        "com.eg.android.AlipayGphone" to "支付宝",
        "com.tencent.mm" to "微信",
        "com.unionpay" to "云闪付",
        "com.icbc" to "工商银行",
        "com.icbc.elife" to "工银e生活",
        "com.chinamworld.main" to "建设银行",
        "com.android.bankabc" to "农业银行",
        "com.chinamworld.bocmbci" to "中国银行",
        "com.bankcomm.BankComm" to "交通银行",
        "cmb.pb" to "招商银行",
        "com.cmbchina.ccd.pluto.cmbActivity" to "掌上生活",
    )

    /**
     * 已废弃包名 → 现行包名（1.3.2 Dev 2）：用户 DataStore 里已保存的勾选集合
     * 按此映射迁移，避免"招行旧包名残留在已存集合里导致上岛静默失效"。
     */
    val PACKAGE_MIGRATION: Map<String, String> = mapOf(
        "com.cmbchina.cmb.plainpinkage" to "cmb.pb",
    )

    // ---- 设置热路径缓存（CleanerListenerService.initScope 内收集，决策零 IO） ----
    @Volatile
    var enabled: Boolean = false
        private set

    @Volatile
    private var packages: Set<String> = DEFAULT_PACKAGES

    /** 由设置收集协程回调（避免 island 依赖 DataStore 的循环） */
    fun onSettings(enabled: Boolean, packages: Set<String>) {
        this.enabled = enabled
        this.packages = packages.ifEmpty { DEFAULT_PACKAGES }
    }

    /** 同文本 60s 去重：银行类 App 常用同一通知槽位反复刷新 */
    private val recentPosted = ConcurrentHashMap<String, Long>()
    private val nextId = AtomicInteger(NOTIF_ID_FIRST)

    /**
     * 微信支付场景关键词（2026-10-06 修复"好友分享小程序营销文案误上岛"）：
     * com.tencent.mm 是聊天 App，聊天消息里任何"XX元"都会命中金额正则
     * （实测 2026-10-06 09:34 "领10687元加价券包" 上岛）。白名单内只有支付动作
     * 类通知才该上岛——标题+正文不含这些词的一律视为聊天/分享，跳过。
     */
    private val WECHAT_PAYMENT_WORDS = listOf(
        "红包", "转账", "收款", "付款", "支付", "退款", "到账", "零钱", "佣金", "扣款", "消费", "商户",
    )

    /** 微信通知是否具备支付场景上下文（聊天文本命中任何支付动作词才算） */
    private fun isWeChatPaymentContext(title: String, content: String): Boolean {
        val text = "$title\n$content"
        return WECHAT_PAYMENT_WORDS.any { text.contains(it) }
    }

    /**
     * 放行通知的支付信息上岛。同步快速路径（解析/去重），
     * 发送走 [IslandPoster]（clearBeforePost + visibility）。
     */
    fun maybePost(context: Context, sbn: StatusBarNotification, title: String, content: String) {
        // Dev 9：代发客户端已在 App.onCreate / KeepAliveService.onCreate 提前握手，
        // 这里保留调用只为覆盖"未经 Application 逻辑的冷启动路径"（幂等，可重试）。
        cc.ytdttj.noticleaner.notify.island.IslandDispatch.init(context)
        // Dev 12 延迟排查：记录"我们开始处理这条原始通知"的时刻，
        // 与 sbn.postTime（微信/银行发出通知的时刻）相减 = 系统投递滞后
        val receivedAt = System.currentTimeMillis()
        // 模拟来源解析：Shell 通知的 island:<pkg> tag → 按模拟包名走白名单/图标/App名
        val pkg = effectivePackage(sbn)
        val inWhitelist = pkg in packages
        // 诊断：只对白名单相关包名记录，避免噪音
        if (inWhitelist || sbn.packageName == SHELL_PACKAGE) {
            // Dev 12：补正文片段——排查"扣款通知没上岛"时，只有标题看不出金额在哪一段
            IslandTrace.log(
                "收到通知 pkg=$pkg raw=${sbn.packageName}" +
                    " post=${cc.ytdttj.noticleaner.diagnostics.DiagTime.stamp(sbn.postTime)}" +
                    " lag=${cc.ytdttj.noticleaner.diagnostics.DiagTime.lagText(receivedAt - sbn.postTime)}" +
                    " title=${title.take(20)}" +
                    " content=${content.take(60)}",
            )
        }
        if (!enabled) {
            if (inWhitelist) IslandTrace.log("✗ 总开关未开启，跳过")
            return
        }
        if (!inWhitelist) {
            // 1.3.2 诊断补盲：白名单外的包若解析出支付金额，留痕包名——
            // 排查"银行 App 更新后包名变化导致上岛静默失效"（此前这里完全无声）
            val hit = runCatching { PaymentExtractor.extract(title, content) }.getOrNull()
            if (hit != null) {
                IslandTrace.log("✗ 非白名单包疑似支付通知 pkg=$pkg raw=${sbn.packageName} title=${title.take(20)}")
            }
            return
        }
        val payment = runCatching { PaymentExtractor.extract(title, content) }
            .getOrElse { Log.w(TAG, "extract failed", it); null }
        if (payment == null) {
            IslandTrace.log("✗ 金额解析失败：'$title' / '${content.take(30)}'")
            return
        }
        IslandTrace.log("金额解析: ${payment.capsuleText.trim()} 方向=${payment.direction} 折算=${payment.convertedCnyText ?: "无"} 原文='${title.take(16)}'/'${content.take(48)}'")

        // 2026-10-06 防误上岛三道闸（当日 09:34 微信券包 10687 / 10-05 21:23 支付宝
        // 亲情卡余额 9968.90 两起误上岛排查）：
        // 闸 1——方向未知不上岛：金额解析有结果但全文找不到任何收/支语义，
        // 大概率是营销文案里的"XX元"（领10687元券包），宁可漏不可错。
        if (payment.direction == PaymentExtractor.Direction.UNKNOWN) {
            IslandTrace.log("✗ 方向未知（无收/支语义），跳过上岛")
            return
        }
        // 闸 2——微信非支付场景不上岛：聊天/好友分享里的金额（"领10687元加价券包"）
        // 没有支付动作词，直接跳过；红包/转账/收款/微信支付服务通知均含关键词不受影响。
        if (pkg == "com.tencent.mm" && !isWeChatPaymentContext(title, content)) {
            IslandTrace.log("✗ 微信非支付场景（聊天/分享文本无支付动作词），跳过上岛")
            return
        }

        val sig = "$pkg|${title.hashCode()}|${content.hashCode()}"
        val now = System.currentTimeMillis()
        // 原子去重：同一条通知可能被系统投递多次（并发回调），
        // check-then-put 两步在并发下双双通过会重复上岛（2026-09-25 10:50 id=9002/9003 实证）
        val prev = recentPosted.putIfAbsent(sig, now)
        if (prev != null) {
            if (now - prev < DEDUP_WINDOW_MS) {
                IslandTrace.log("✗ 60s 内重复推送，跳过")
                return
            }
            recentPosted[sig] = now // 过期条目刷新
        }
        // 清理过期条目，防长期驻留膨胀
        recentPosted.entries.removeIf { now - it.value > DEDUP_WINDOW_MS }

        val appName = PACKAGE_LABELS[pkg] ?: appLabel(context, pkg)
        val icon = runCatching {
            IslandParamsBuilder.drawableToBitmap(
                context.packageManager.getApplicationIcon(pkg),
            )
        }.getOrNull()
        if (icon == null) IslandTrace.log("⚠ 来源图标获取失败（用系统占位）")
        val contentIntent = sbn.notification?.contentIntent
        val id = nextId.updateAndGet { cur ->
            if (cur >= NOTIF_ID_LAST) NOTIF_ID_FIRST else cur + 1
        }

        val notification = runCatching {
            IslandParamsBuilder.build(
                context = context,
                appName = appName,
                payment = payment,
                title = title,
                content = content,
                sourceIcon = icon,
                contentIntent = contentIntent,
                islandTimeoutSec = Int.MAX_VALUE, // Dev 16：结果岛常驻（property=2），直到"已完成"
                notificationId = id,
            )
        }.getOrElse {
            IslandTrace.log("✗ 岛通知构建失败: $it")
            Log.w(TAG, "build island notification failed", it); return
        }

        // Dev 16（方案 B）：优先 SystemUI 代发——以 systemui 身份 notify，白名单/签名/
        // 云认证三道门天然全免（认证等待归零）；接收器未就绪时回退自身 notify + 兜底。
        val dispatched = IslandDispatch.tryDispatch(context, notification, id)
        if (!dispatched) {
            IslandTrace.log("代发未就绪（SystemUI 接收器未确认 READY），回退自身 notify + 兜底路径")
            IslandPoster.post(context, id, notification)
        }
        // Dev 12 延迟排查：一条记录里给出完整时间链——
        //   srcPost = 原始动账通知（微信/银行）的发布时间
        //   lag     = 从原始通知发布 → 我们提交岛通知（含系统投递积压 + App 处理）
        //   took    = 纯 App 内部耗时（收到 → 提交），用于证明 App 侧是否拖后腿
        val postedAt = System.currentTimeMillis()
        IslandTrace.log(
            "岛通知已提交系统 (id=$id, " +
                (if (dispatched) "SystemUI 代发·待回执" else "LSPosed 放行认证") +
                ", srcPost=${cc.ytdttj.noticleaner.diagnostics.DiagTime.stamp(sbn.postTime)}" +
                ", lag=${cc.ytdttj.noticleaner.diagnostics.DiagTime.lagText(postedAt - sbn.postTime)}" +
                ", took=${postedAt - receivedAt}ms)",
        )
    }

    /**
     * 管线直接注入（islandv2plan P2-3，主模拟路径）：
     * 不依赖系统通知投递（HyperOS 不把 shell 通知投给第三方监听器），直接走
     * 金额解析 → 岛通知构建 → 发送。测试路径 showNotification=true 留痕。
     *
     * delayMs：延迟发送（默认 5 秒）——HyperOS 前台抑制：App 自己在前台时不渲染
     * 它的超级岛，岛要等应用退后台才出现（此时 islandFirstFloat 展开窗口已错过）。
     * 延迟给用户时间回到桌面，让展开逻辑在通知真正到达时评估。
     */
    fun postSimulated(
        context: Context,
        pkg: String,
        title: String,
        content: String,
        delayMs: Long = 5000L,
        onResult: (String) -> Unit,
    ) {
        val label = PACKAGE_LABELS[pkg] ?: pkg
        val payment = runCatching { PaymentExtractor.extract(title, content) }
            .getOrElse { IslandTrace.log("✗ 注入：解析异常 $it"); null }
        if (payment == null) {
            IslandTrace.log("✗ 注入：金额解析失败（需含币种特征，如 ¥25.00）")
            onResult("未解析到金额——模拟文案需含币种特征（如 ¥25.00 / USD 12.34 / 25元）")
            return
        }
        IslandTrace.log("管线注入: pkg=$pkg 金额=${payment.capsuleText.trim()} 延迟=${delayMs}ms")
        val appContext = context.applicationContext
        Thread {
            val result = runCatching {
                val appName = PACKAGE_LABELS[pkg] ?: appLabel(appContext, pkg)
                val icon = runCatching {
                    IslandParamsBuilder.drawableToBitmap(
                        appContext.packageManager.getApplicationIcon(pkg),
                    )
                }.getOrNull()
                val id = nextId.updateAndGet { cur ->
                    if (cur >= NOTIF_ID_LAST) NOTIF_ID_FIRST else cur + 1
                }
                val notif = IslandParamsBuilder.build(
                    context = appContext,
                    appName = appName,
                    payment = payment,
                    title = title,
                    content = content,
                    sourceIcon = icon,
                    contentIntent = null,
                    islandTimeoutSec = 120,
                    showNotification = true, // 测试期：岛被拒时通知栏留痕，判别认证拒绝
                    notificationId = id,
                )
                if (delayMs > 0) Thread.sleep(delayMs) // 等用户回到桌面，避开前台抑制
                IslandPoster.post(appContext, id, notif)
                "已注入管线（金额 ${payment.capsuleText.trim()}），结果见诊断日志与通知栏"
            }.getOrElse { "注入失败: ${it.message}" }
            android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(result) }
        }.start()
    }

    /** 设置页"发送测试岛"：走完整 LSPosed 链路，结果回调主线程 */
    fun sendTest(context: Context, onResult: (String) -> Unit) {
        val p = PaymentExtractor.extract("测试支付", "您已支付 ¥25.00") ?: run {
            onResult("测试解析失败")
            return
        }
        // 不固定 id：60s 岛超时内连续测试用同一 id 会变成"更新通知"，
        // HyperOS 对更新不触发岛展示（Dev 4 修复，Dev 5 重构保留）
        val id = nextId.updateAndGet { cur ->
            if (cur >= NOTIF_ID_LAST) NOTIF_ID_FIRST else cur + 1
        }
        val appContext = context.applicationContext
        Thread {
            val result = runCatching {
                val notif = IslandParamsBuilder.build(
                    context = appContext,
                    appName = "超级岛测试",
                    payment = p,
                    title = "测试支付通知",
                    content = "您已支付 ¥25.00",
                    sourceIcon = null,
                    contentIntent = null,
                    islandTimeoutSec = 60,
                    showNotification = true,
                    notificationId = id,
                )
                Thread.sleep(5000) // 等用户回到桌面，避开前台抑制（岛在 App 前台时不渲染）
                // Dev 16：同样优先 SystemUI 代发（测试岛用于验证整条链路）
                val dispatched = IslandDispatch.tryDispatch(appContext, notif, id)
                if (!dispatched) IslandPoster.post(appContext, id, notif)
                if (dispatched) "测试岛已通过 SystemUI 代发" else "测试岛通知已发送（LSPosed 放行认证）"
            }.getOrElse { "发送失败: ${it.message}" }
            android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(result) }
        }.start()
    }

    private fun appLabel(context: Context, pkg: String): String = runCatching {
        context.packageManager.getApplicationLabel(
            context.packageManager.getApplicationInfo(pkg, 0),
        ).toString()
    }.getOrDefault(pkg)

    /** 岛设置快照（DiagExporter 诊断头用，1.3.2 诊断补盲） */
    fun diagSnapshot(): String = buildString {
        appendLine("岛开关: $enabled")
        appendLine("岛模式: LSPosed（SystemUI 白名单 hook + xmsf 云认证 hook）")
        appendLine("岛白名单(${packages.size}): ${packages.joinToString()}")
    }
}
