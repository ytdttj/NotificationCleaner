package cc.ytdttj.noticleaner.keepalive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.github.libxposed.api.XposedInterface

/**
 * 岛代发（2.0.1 Dev 16，方案 B——借鉴 ref/HyperIsland islanddispatch 信任模型）：
 *
 * 在 SystemUI 进程内注册广播接收器，收到 App 的派发广播后**以 com.android.systemui
 * 身份** `nm.notify()` 发出岛通知。发送者 = systemui → SystemUI/xmsf 的三道认证门槛
 * （canShowFocus 白名单 / 签名校验 / 云认证 -300 scope mismatch）**天然全部不适用**，
 * 认证等待归零，且不再依赖 xmsf 混淆符号（AuthSession 兜底降级为旧路径回退用）。
 *
 * 安全：接收器注册时要求 signature 权限 `PERMISSION_DISPATCH_ISLAND`（模块 APK 声明
 * 并自动持有，第三方无法伪造），框架在 AMS 层强制校验发送方。
 *
 * 取消路径（2.2.0 Dev 3）：岛的「已完成」按钮点击由 App 侧收到后广播
 * [ACTION_DISPATCH_DISMISS] 回来——代发通知的 owner 是 systemui，App 自己 cancel
 * 是空操作（真机实测：App 打了"已取消"日志，SystemUI 侧从未出现 canceled，岛不消），
 * 详见 [handleDismiss]。
 *
 * 注册时机（2.2.0 Dev 1 热重载改造，两条路径共用 [doRegister]）：
 * - 首次加载：hook `Application.attach` / `onCreate`（SystemUIApplication 会走基类），
 *   拿到 Context 后注册；
 * - 热重载后：Application.attach 不会再触发，新代由 LspEntry.onHotReloaded 调
 *   [registerNow] 用现存 Context（ActivityThread 反射自取）急切注册。
 * 注册成功即回发 READY 广播，App 侧据此启用代发路径（未 READY 时 App 自动回退自身
 * notify + AuthSession 兜底，双保险不断链；READY 处理幂等，重发无副作用）。
 *
 * 退役契约（官方 onHotReloading 返回 true 前必须完成）：接收器与注册 Context 均保存引用，
 * [teardown] 可逆拆除——否则旧 ClassLoader 被系统钉死 + 新旧接收器并存导致双投递。
 */
internal object SystemUIIslandDispatcher {

    private const val TAG = "NCIslandHook"
    const val ACTION_DISPATCH_ISLAND = "cc.ytdttj.noticleaner.ACTION_DISPATCH_ISLAND"
    const val ACTION_DISPATCH_PING = "cc.ytdttj.noticleaner.ACTION_DISPATCH_PING"
    const val ACTION_DISPATCH_READY = "cc.ytdttj.noticleaner.ACTION_DISPATCH_READY"
    const val ACTION_DISPATCH_DISMISS = "cc.ytdttj.noticleaner.ACTION_DISPATCH_DISMISS"

    /**
     * 投递确认（Dev 9）：`handleDispatch` 成功 notify 后回发，App 侧据此把
     * 「已提交」升级为「已投递」。
     *
     * 起因（2026-10-04 真机实测）：`sendBroadcast` 无回执，广播因过大被
     * BroadcastQueue 丢弃时 App 侧毫无察觉，日志误记成功、岛却不上。
     */
    const val ACTION_DISPATCH_DONE = "cc.ytdttj.noticleaner.ACTION_DISPATCH_DONE"
    const val PERMISSION_SEND = "cc.ytdttj.noticleaner.PERMISSION_DISPATCH_ISLAND"
    const val CHANNEL_ID = "nc_island_dispatcher"
    const val EXTRA_INNER = "nc_island_extras"
    const val EXTRA_ID = "nc_island_id"
    const val EXTRA_CONTENT_PI = "nc_island_content_pi"

    @Volatile private var registered = false

    /** 退役契约依据：接收器与注册时的 Context 必须保存引用，teardown 才能反向拆除 */
    @Volatile private var activeReceiver: BroadcastReceiver? = null
    @Volatile private var activeContext: Context? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 由 LspEntry 在 SystemUI 分支调用：挂 Application.attach/onCreate，拿 Context 注册 */
    fun install(module: LspEntry, classLoader: ClassLoader) {
        val appClass = runCatching { classLoader.loadClass("android.app.Application") }.getOrNull() ?: run {
            module.log(android.util.Log.WARN, TAG, "island dispatcher: Application class not found")
            return
        }
        var hooked = 0
        for (name in listOf("attach", "onCreate")) {
            runCatching {
                val m = appClass.declaredMethods.firstOrNull { it.name == name } ?: return@runCatching
                if (module.hookOnce(m, "dispatcher:app-$name", AttachInterceptor(module))) hooked++
            }
        }
        module.log(
            if (hooked > 0) android.util.Log.INFO else android.util.Log.WARN,
            TAG, "island dispatcher installed on $hooked Application hooks",
        )
    }

    private class AttachInterceptor(private val module: LspEntry) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            runCatching {
                val app = chain.thisObject as? android.app.Application
                    ?: (chain.args.firstOrNull() as? android.content.Context)?.applicationContext
                    ?: return@runCatching
                register(app.applicationContext, module)
            }
            return result
        }
    }

    /** 首次加载路径（Application hook 现场）：幂等守卫后走统一注册 */
    @Synchronized
    private fun register(ctx: Context, module: LspEntry) {
        if (registered) return
        doRegister(ctx, module)
    }

    /**
     * 急切注册（热重载路径）：新代 onHotReloaded 用现存 Context 直接注册，
     * 不依赖 Application.attach（重载后它不会再次触发）。幂等。
     */
    @Synchronized
    fun registerNow(ctx: Context, module: LspEntry) {
        if (registered) return
        doRegister(ctx, module)
    }

    /** 统一注册体：成功后保存 (ctx, receiver) 供 teardown 反向拆除 */
    @Synchronized
    private fun doRegister(ctx: Context, module: LspEntry) {
        if (registered) return
        registered = true
        runCatching {
            val appCtx = ctx.applicationContext ?: ctx
            val nm = appCtx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "通知滤盒岛", NotificationManager.IMPORTANCE_HIGH),
                )
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    when (intent.action) {
                        ACTION_DISPATCH_ISLAND -> handleDispatch(c, intent)
                        ACTION_DISPATCH_PING -> answerReady(c)
                        ACTION_DISPATCH_DISMISS -> handleDismiss(c, intent)
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(ACTION_DISPATCH_ISLAND)
                addAction(ACTION_DISPATCH_PING)
                addAction(ACTION_DISPATCH_DISMISS)
            }
            // signature 权限在框架层校验发送方；EXPORTED 是接收外部（本 App）广播的必要标志
            if (Build.VERSION.SDK_INT >= 33) {
                appCtx.registerReceiver(receiver, filter, PERMISSION_SEND, mainHandler, Context.RECEIVER_EXPORTED)
            } else {
                appCtx.registerReceiver(receiver, filter, PERMISSION_SEND, mainHandler)
            }
            activeReceiver = receiver
            activeContext = appCtx
            answerReady(appCtx)
            module.log(
                android.util.Log.INFO, TAG,
                "island dispatcher receiver registered — systemui-identity posting active",
            )
        }.onFailure {
            registered = false
            activeReceiver = null
            activeContext = null
            module.log(android.util.Log.WARN, TAG, "island dispatcher register failed: $it")
        }
    }

    /**
     * 退役契约（热重载 onHotReloading 返回 true 前调用）：反注册接收器。
     * 返回 false = 拆不干净（调用方必须拒绝热重载，避免新旧接收器并存双投递）。
     * 从未注册过 / 已被系统回收（IllegalArgumentException）视为已拆干净。
     */
    @Synchronized
    fun teardown(): Boolean {
        val receiver = activeReceiver ?: return true
        val ctx = activeContext
        val ok = try {
            if (ctx != null) ctx.unregisterReceiver(receiver)
            true
        } catch (_: IllegalArgumentException) {
            true // 未注册/已回收
        } catch (_: Throwable) {
            false
        }
        if (ok) {
            activeReceiver = null
            activeContext = null
            registered = false
            android.util.Log.i(TAG, "island dispatcher receiver unregistered (hot reload retiring)")
        }
        return ok
    }

    /** 热重载：按 id 重建 Hooker（replaceHook 只换 Hooker，executable 由旧句柄保留） */
    fun hookerForId(module: LspEntry, id: String): XposedInterface.Hooker? = when (id) {
        "dispatcher:app-attach", "dispatcher:app-onCreate" -> AttachInterceptor(module)
        else -> null
    }

    /** 告知 App：派发接收器已就绪（App 侧据此启用代发路径） */
    private fun answerReady(ctx: Context) {
        runCatching {
            ctx.sendBroadcast(
                Intent(ACTION_DISPATCH_READY)
                    .setPackage("cc.ytdttj.noticleaner"),
            )
        }
    }

    /** Dev 9：告知 App「这条岛通知已真正 notify 出去」——闭合 fire-and-forget 的状态黑洞 */
    private fun answerDone(ctx: Context, notificationId: Int) {
        runCatching {
            ctx.sendBroadcast(
                Intent(ACTION_DISPATCH_DONE)
                    .setPackage("cc.ytdttj.noticleaner")
                    .putExtra(EXTRA_ID, notificationId),
            )
        }
    }

    private fun handleDispatch(ctx: Context, intent: Intent) {
        val inner = intent.getBundleExtra(EXTRA_INNER) ?: return
        if (!inner.containsKey("miui.focus.param")) return // 非岛通知防御
        val id = intent.getIntExtra(EXTRA_ID, 0)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        // HyperIsland 经验：同 id 先 cancel 再 notify，避免被系统当作"更新"不触发展示
        runCatching { nm.cancel(id) }
        // 自排除标记：App 侧 NLS 收到这条（pkg=systemui）时据此跳过，不进过滤管线/历史
        inner.putBoolean("nc_island_dispatched", true)
        val notif = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setVisibility(Notification.VISIBILITY_SECRET) // 通知栏完全无痕，仅岛展示
            .addExtras(inner)
            .also { b ->
                (intent.getParcelableExtra(EXTRA_CONTENT_PI, android.app.PendingIntent::class.java))
                    ?.let { b.setContentIntent(it) }
            }
            .build()
        // Dev 9：只有真正 notify 成功才回执——让 App 侧的「已投递」名副其实
        val ok = runCatching { nm.notify(id, notif) }.isSuccess
        android.util.Log.i(
            TAG,
            "island dispatched via systemui-identity (id=$id, notify=$ok, " +
                "extras=${inner.size()}B)",
        )
        if (ok) answerDone(ctx, id)
    }

    /**
     * 「已完成」按钮（2.2.0 Dev 3）：取消**必须由本进程执行**。
     *
     * 代发通知的 owner 是 com.android.systemui，App 侧 `NotificationManager.cancel(id)`
     * 只能取消本 App 名下的通知——对代发 id 是**空操作**（真机实测：App 记录了
     * "已取消通知"，而 SystemUI 侧从未出现 notification_canceled，岛一直不消）。
     * 因此 App 收到按钮点击后广播过来，由 SystemUI 用自己的 NotificationManager 取消。
     *
     * 复用 [PERMISSION_SEND]（signature）校验发送方，第三方无法伪造取消请求。
     */
    private fun handleDismiss(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_ID, 0)
        if (id == 0) {
            android.util.Log.w(TAG, "island dismiss ignored: bad id")
            return
        }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val ok = runCatching { nm.cancel(id) }.isSuccess
        android.util.Log.i(
            TAG,
            "island dismissed by user action (id=$id, systemui-identity=$ok, " +
                "active=${activeContext != null})",
        )
    }
}
