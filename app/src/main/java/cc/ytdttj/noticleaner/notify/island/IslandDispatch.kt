package cc.ytdttj.noticleaner.notify.island

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import cc.ytdttj.noticleaner.keepalive.SystemUIIslandDispatcher
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 岛代发客户端（2.0.1 Dev 16，方案 B）：
 *
 * App 收到动账后不再自己 notify 岛通知，而是发广播给 **SystemUI 进程内的接收器**
 * （[SystemUIIslandDispatcher]，LSPosed 注入），由它**以 com.android.systemui 身份**
 * notify——发送者就是 systemui，白名单/签名/云认证三道门槛天然全免。
 *
 * 就绪探测（PING/ACK）：SystemUI 侧接收器注册成功后回发 READY；App 进程启动时也会
 * 发 PING 主动询问。未就绪（模块禁用 / LSPosed 未激活 / SystemUI 未重启）时
 * [tryDispatch] 返回 false，调用方回退自身 notify + AuthSession 兜底路径——双保险不断链。
 *
 * ## Dev 9：投递确认（DONE 回执）与体积上限
 *
 * 2026-10-04 真机实测的故障：`sendBroadcast` 是 fire-and-forget，**没有回执**。
 * 当 extras 过大（未降采样的 App 图标，实测单条 parcel 达 329624 字节）时，
 * BroadcastQueue 在投递阶段抛 `TransactionTooLargeException` **把广播整个丢弃**，
 * 而 App 侧毫无察觉，仍在日志里记「岛通知已提交系统（SystemUI 代发）」——
 * **日志误报为成功，实际岛没出现**，只能靠"进 APP 触发补扫重发"才偶然补上。
 *
 * 两项对策：
 * 1. [MAX_PARCEL_BYTES] 发送前用 [Parcel] 实测体积，超限直接判定失败并回退自身路径，
 *    宁可走有兜底的旧路，也不发一个必然被丢弃的大包；
 * 2. SystemUI 侧 notify 成功后回发 [ACTION_DISPATCH_DONE][SystemUIIslandDispatcher
 *    .ACTION_DISPATCH_DONE]（带 id），App 侧据此把「已提交」升级为「已投递」；
 *    超时未回执则明确记为失败——**状态不再靠猜**。
 *
 * 安全：SystemUI 侧接收器要求 signature 权限（模块 APK 声明并自动持有），第三方
 * 无法伪造岛通知。
 */
object IslandDispatch {

    /**
     * 代发广播的 parcel 体积上限（字节，Dev 9）。
     *
     * Binder 事务共享缓冲区虽为 1MB，但单个广播只需留给系统其他事务余量；
     * 且 `miui.focus.pics` 的图标体积随银行 App 图标尺寸浮动（实测可达 300KB+）。
     * 取 64KB 保守上限：既能容纳降采样后的图标与 param 字符串，也有足够安全边际。
     */
    private const val MAX_PARCEL_BYTES = 64 * 1024

    /** 投递确认等待时长（毫秒，Dev 9）——SystemUI 侧 notify 后立即回发 DONE，1s 足够 */
    private const val DONE_TIMEOUT_MS = 1_000L

    /** 首次岛通知需要 systemui 重启后才走代发（接收器随 SystemUI 重启注册） */
    private val ready = AtomicBoolean(false)
    private val receiverRegistered = AtomicBoolean(false)

    /** 已发出、等待 SystemUI 回执的岛通知 id（Dev 9） */
    private val pendingDone: MutableSet<Int> = java.util.Collections.synchronizedSet(mutableSetOf())

    private val handler = Handler(Looper.getMainLooper())

    /**
     * App 进程内注册 READY 监听 + 发 PING 询问（ServiceLocator/App 初始化时调用一次）。
     *
     * Dev 9：这是**幂等且可重试**的——此前 `receiverRegistered` 在 `runCatching` 外置位，
     * 注册抛异常也永不重试且异常被静默吞掉；且全工程只有 [IslandNotifier.maybePost]
     * 一个调用点，导致每个进程生命周期的**第一条岛通知 100% 走回退路径**
     * （init 紧接着读 ready，READY 广播是异步的必然还是 false）。现在由
     * `ServiceLocator.init` + `KeepAliveService.onCreate` 提前调用，并补上
     * 主动 PING 与延迟重试。
     */
    fun init(context: Context) {
        val appCtx = context.applicationContext
        appContext = appCtx
        if (receiverRegistered.compareAndSet(false, true)) {
            val registered = runCatching {
                appCtx.registerReceiver(
                    object : BroadcastReceiver() {
                        override fun onReceive(c: Context?, intent: Intent?) {
                            when (intent?.action) {
                                SystemUIIslandDispatcher.ACTION_DISPATCH_READY -> {
                                    ready.set(true)
                                    cc.ytdttj.noticleaner.diagnostics.RingLog.log(
                                        cc.ytdttj.noticleaner.diagnostics.LogModules.ISLAND,
                                        "岛代发接收器已就绪（SystemUI READY）——后续岛通知走 SystemUI 代发",
                                    )
                                }

                                SystemUIIslandDispatcher.ACTION_DISPATCH_DONE -> {
                                    val id = intent.getIntExtra(SystemUIIslandDispatcher.EXTRA_ID, 0)
                                    if (pendingDone.remove(id)) {
                                        cc.ytdttj.noticleaner.diagnostics.RingLog.log(
                                            cc.ytdttj.noticleaner.diagnostics.LogModules.ISLAND,
                                            "✦ 岛通知已被 SystemUI 实际投递 (id=$id)——岛应已展示",
                                        )
                                    }
                                }
                            }
                        }
                    },
                    IntentFilter().apply {
                        addAction(SystemUIIslandDispatcher.ACTION_DISPATCH_READY)
                        addAction(SystemUIIslandDispatcher.ACTION_DISPATCH_DONE)
                    },
                    // 2.1.2：必须 EXPORTED——READY/DONE 是 SystemUI 进程发来的跨应用广播，
                    // NOT_EXPORTED 会把它拦掉（此前的 bug：ready 永远 false，代发从未启用）。
                    // 风险可控：伪造 READY 顶多让岛通知走代发路径，真正把关的是
                    // SystemUI 侧接收器的 signature 权限校验。
                    Context.RECEIVER_EXPORTED,
                )
            }.isSuccess
            if (!registered) {
                // Dev 9：注册失败必须把标志位退回，否则本进程再也不会重试
                receiverRegistered.set(false)
                android.util.Log.w(TAG, "READY/DONE receiver register failed — will retry on next init")
            }
        }
        // Dev 9：init 时就主动 PING 并延迟重试，让 READY 在第一条通知到达前就位
        ping()
        handler.postDelayed({ if (!ready.get()) ping() }, 600)
    }

    /** 询问 SystemUI 接收器是否就绪（未就绪时每次岛通知都会重问，接收器可随时上线） */
    private fun ping() {
        runCatching {
            appContext?.sendBroadcast(
                Intent(SystemUIIslandDispatcher.ACTION_DISPATCH_PING)
                    .setPackage("com.android.systemui"),
            )
        }
    }

    @Volatile private var appContext: Context? = null

    private const val TAG = "IslandDispatch"

    /** 接收器是否已确认就绪（未就绪时调用方回退自身 notify 路径） */
    fun isReady(): Boolean = ready.get()

    /**
     * 请求 SystemUI 侧取消（代发）岛通知——「已完成」按钮的落地动作（2.2.0 Dev 3）。
     *
     * 代发通知由 systemui 进程发出，App 的 `NotificationManager.cancel(id)` 对它是
     * **空操作**（Android 按包名隔离通知，App 只能取消自己名下的）。因此必须把取消
     * 动作送回 SystemUI 执行。未就绪（接收器未注册）时静默失败——此时岛通知本就不归
     * SystemUI 所有，App 侧那次 cancel 会生效。
     */
    fun requestSystemUiDismiss(context: Context, notificationId: Int) {
        if (!ready.get()) return
        runCatching {
            context.applicationContext.sendBroadcast(
                Intent(SystemUIIslandDispatcher.ACTION_DISPATCH_DISMISS)
                    .setPackage("com.android.systemui")
                    .putExtra(SystemUIIslandDispatcher.EXTRA_ID, notificationId),
            )
        }.onFailure {
            android.util.Log.w(TAG, "dismiss request failed: $it")
        }
    }

    /**
     * 通过 SystemUI 代发岛通知。
     *
     * [notification] 为 [IslandParamsBuilder.build] 的产物——真正跨进程的是它的
     * extras（miui.focus.param / pics / actions）与"已完成"按钮的 PendingIntent。
     * SystemUI 进程内以自己的 channel + smallIcon 重建 Notification 后 notify。
     *
     * @return true = 已交由代发；false = 接收器未就绪或广播过大被拒（调用方回退自身路径）
     */
    fun tryDispatch(context: Context, notification: Notification, notificationId: Int): Boolean {
        if (!ready.get()) {
            // 每次都重问（PING 很便宜）：SystemUI 可能晚于 App 注册接收器，
            // 只在 init 时问一次的话，那一次丢失就永远回退旧路径
            ping()
            return false
        }
        val inner = notification.extras ?: return false
        if (!inner.containsKey("miui.focus.param")) return false
        val contentPi = notification.contentIntent
        val intent = Intent(SystemUIIslandDispatcher.ACTION_DISPATCH_ISLAND)
            .setPackage("com.android.systemui")
            .putExtra(SystemUIIslandDispatcher.EXTRA_INNER, BundleCloner.clone(inner))
            .putExtra(SystemUIIslandDispatcher.EXTRA_ID, notificationId)
        contentPi?.let { intent.putExtra(SystemUIIslandDispatcher.EXTRA_CONTENT_PI, it) }

        // Dev 9：发送前实测 parcel 体积。超限的广播必被 BroadcastQueue 丢弃
        // （TransactionTooLargeException），与其发出去石沉大海，不如当场回退。
        val parcelSize = runCatching { measureParcel(intent) }.getOrDefault(0)
        if (parcelSize > MAX_PARCEL_BYTES) {
            cc.ytdttj.noticleaner.diagnostics.RingLog.log(
                cc.ytdttj.noticleaner.diagnostics.LogModules.ISLAND,
                "✗ 代发广播过大（$parcelSize 字节 > 上限 $MAX_PARCEL_BYTES）——必然被系统丢弃，" +
                    "回退自身 notify 路径（请检查岛图标体积）",
            )
            return false
        }

        pendingDone.add(notificationId)
        runCatching { context.sendBroadcast(intent) }
            .onFailure {
                pendingDone.remove(notificationId)
                return false
            }
        // Dev 9：等 SystemUI 回执，确认广播真的被接收方收到（超时则记为未确认）
        handler.postDelayed({
            if (pendingDone.remove(notificationId)) {
                cc.ytdttj.noticleaner.diagnostics.RingLog.log(
                    cc.ytdttj.noticleaner.diagnostics.LogModules.ISLAND,
                    "✗ 岛通知代发未收到 SystemUI 回执（id=$notificationId, ${DONE_TIMEOUT_MS}ms 超时，" +
                        "parcel=$parcelSize 字节）——广播可能已被系统丢弃，请核对 LSPosed 侧 full.log",
                )
            }
        }, DONE_TIMEOUT_MS)
        return true
    }

    /** 实测广播 intent 的 parcel 体积（字节）；失败返回 0（视为不超限，交由系统裁决） */
    private fun measureParcel(intent: Intent): Int {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(intent.extras)
            parcel.dataSize()
        } finally {
            parcel.recycle()
        }
    }
}

/** 广播 extras 的浅拷贝（直接传原 Bundle 在跨进程序列化时安全，这里仅作语义隔离） */
private object BundleCloner {
    fun clone(src: android.os.Bundle): android.os.Bundle = android.os.Bundle(src)
}
