package cc.ytdttj.noticleaner.notify.island

import android.app.PendingIntent
import android.content.Context
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 支付通知上岛总入口（islandplan.md §一）。
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

    /** 默认白名单（islandplan.md §1.1；包名待真机核对，设置页可逐个开关） */
    val DEFAULT_PACKAGES: Set<String> = linkedSetOf(
        "com.eg.android.AlipayGphone", // 支付宝
        "com.tencent.mm",              // 微信
        "com.unionpay",                // 云闪付
        "com.icbc",                    // 工商银行
        "com.chinamworld.main",        // 建设银行
        "com.android.bankabc",         // 农业银行
        "com.chinamworld.bocmbci",     // 中国银行
        "com.bankcomm.BankComm",       // 交通银行
        "com.cmbchina.cmb.plainpinkage", // 招商银行
    )

    /** 白名单中文显示名（设置页 chip + 岛卡片标题用） */
    val PACKAGE_LABELS: Map<String, String> = linkedMapOf(
        "com.eg.android.AlipayGphone" to "支付宝",
        "com.tencent.mm" to "微信",
        "com.unionpay" to "云闪付",
        "com.icbc" to "工商银行",
        "com.chinamworld.main" to "建设银行",
        "com.android.bankabc" to "农业银行",
        "com.chinamworld.bocmbci" to "中国银行",
        "com.bankcomm.BankComm" to "交通银行",
        "com.cmbchina.cmb.plainpinkage" to "招商银行",
    )

    // ---- 设置热路径缓存（CleanerListenerService.initScope 内收集，决策零 IO） ----
    @Volatile
    var enabled: Boolean = false
        private set

    @Volatile
    private var packages: Set<String> = DEFAULT_PACKAGES

    @Volatile
    private var bypassMs: Long = 100L

    /** 由设置收集协程回调（避免 island 依赖 DataStore 的循环） */
    fun onSettings(enabled: Boolean, packages: Set<String>, bypassMs: Long) {
        this.enabled = enabled
        this.packages = packages.ifEmpty { DEFAULT_PACKAGES }
        this.bypassMs = bypassMs.coerceIn(50, 500)
    }

    /** 同文本 60s 去重：银行类 App 常用同一通知槽位反复刷新 */
    private val recentPosted = ConcurrentHashMap<String, Long>()
    private val nextId = AtomicInteger(NOTIF_ID_FIRST)

    /**
     * 放行通知的支付信息上岛。同步快速路径（解析/去重），
     * 发送排队到盲窗执行器（串行）。
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
        if (!inWhitelist) return
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
            )
        }.getOrElse {
            IslandTrace.log("✗ 岛通知构建失败: $it")
            Log.w(TAG, "build island notification failed", it); return
        }

        val id = nextId.updateAndGet { cur ->
            if (cur >= NOTIF_ID_LAST) NOTIF_ID_FIRST else cur + 1
        }
        IslandBypassExecutor.post(context, id, notification, bypassMs)
        IslandTrace.log("岛通知已入队 (id=$id, 盲窗=${bypassMs}ms)，等待盲窗执行器结果…")
    }

    /** 设置页"发送测试岛"：走完整盲窗链路，结果回调主线程 */
    fun sendTest(context: Context, onResult: (String) -> Unit) {
        if (!IslandBypassExecutor.isReady()) {
            onResult("Shizuku 未授权：上岛需要 Shizuku（或 Root）授权")
            return
        }
        val p = PaymentExtractor.extract("测试支付", "您已支付 ¥25.00") ?: run {
            onResult("测试解析失败")
            return
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
                )
                IslandBypassExecutor.post(appContext, NOTIF_ID_FIRST, notif, bypassMs)
                "测试岛通知已发送"
            }.getOrElse { "发送失败: ${it.message}" }
            android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(result) }
        }.start()
    }

    private fun appLabel(context: Context, pkg: String): String = runCatching {
        context.packageManager.getApplicationLabel(
            context.packageManager.getApplicationInfo(pkg, 0),
        ).toString()
    }.getOrDefault(pkg)
}
