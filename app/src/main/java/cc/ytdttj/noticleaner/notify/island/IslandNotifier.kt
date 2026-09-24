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
 * 触发条件（三者同时满足）：包名白名单 + 文本含币种特征金额 + 通知已放行。
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
     * 放行通知的支付信息上岛。同步快速路径（解析/去重），
     * 发送走 [IslandPoster]（clearBeforePost + visibility）。
     */
    fun maybePost(context: Context, sbn: StatusBarNotification, title: String, content: String) {
        // 模拟来源解析：Shell 通知的 island:<pkg> tag → 按模拟包名走白名单/图标/App名
        val pkg = effectivePackage(sbn)
        val inWhitelist = pkg in packages
        // 诊断：只对白名单相关包名记录，避免噪音
        if (inWhitelist || sbn.packageName == SHELL_PACKAGE) {
            IslandTrace.log("收到通知 pkg=$pkg raw=${sbn.packageName} title=${title.take(20)}")
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
        IslandTrace.log("金额解析: ${payment.capsuleText.trim()} 方向=${payment.direction} 折算=${payment.convertedCnyText ?: "无"}")

        val sig = "$pkg|${title.hashCode()}|${content.hashCode()}"
        val now = System.currentTimeMillis()
        val last = recentPosted[sig]
        if (last != null && now - last < DEDUP_WINDOW_MS) {
            IslandTrace.log("✗ 60s 内重复推送，跳过")
            return
        }
        // 清理过期条目，防长期驻留膨胀
        recentPosted.entries.removeIf { now - it.value > DEDUP_WINDOW_MS }
        recentPosted[sig] = now

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
                islandTimeoutSec = ISLAND_TIMEOUT_SEC,
                notificationId = id,
            )
        }.getOrElse {
            IslandTrace.log("✗ 岛通知构建失败: $it")
            Log.w(TAG, "build island notification failed", it); return
        }

        IslandPoster.post(context, id, notification)
        IslandTrace.log("岛通知已提交系统 (id=$id, LSPosed 放行认证)")
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
                IslandPoster.post(appContext, id, notif)
                "测试岛通知已发送（LSPosed 放行认证）"
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
