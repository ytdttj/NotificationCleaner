package cc.ytdttj.noticleaner.notify.island

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.IConnectivityManager
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * xmsf 网络盲窗执行器（islandplan.md §二，机制参考 island.md §3.4）。
 *
 * 焦点通知白名单的实时云认证经 xmsf（com.xiaomi.xmsf）联网：认证网络不可达时
 * SystemUI fail-open，按岛通知渲染。因此发岛通知前短暂断开 xmsf 网络：
 *
 *   setFirewallChainEnabled(9, true) + setUidFirewallRule(9, xmsfUid, DENY)
 *   → notify() → sleep(盲窗) → setUidFirewallRule(9, uid, DEFAULT)
 *
 * 全程 Shizuku Binder IPC（无 shell 命令）；单线程串行保证
 * disable→notify→enable 严格成对；失败降级为直发（被白名单拦成普通通知）。
 * 注意：GPL-3.0 隔离——本实现只参考机制与系统接口语义，代码独立编写。
 */
object IslandBypassExecutor {

    private const val TAG = "IslandBypass"
    private const val XMSF_PACKAGE = "com.xiaomi.xmsf"

    /** FIREWALL_CHAIN_OEM_DENY_3（InstallerX-Revived 同款 chain） */
    private const val CHAIN = 9
    private const val RULE_DEFAULT = 0
    private const val RULE_DENY = 2

    private val single = ThreadPoolExecutor(
        1, 1, 30L, TimeUnit.SECONDS, LinkedBlockingQueue(),
    ) { r -> Thread(r, "IslandBypass").apply { isDaemon = true } }

    @Volatile
    private var xmsfUid: Int = -1

    /** Shizuku 可用且已授权 */
    fun isReady(): Boolean = runCatching {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * 在盲窗内发送通知（异步、串行）。盲窗时长由设置控制（默认 100ms）。
     * 任何异常静默降级，绝不影响调用方（通知净化主流程）。
     */
    fun post(context: Context, notificationId: Int, notification: Notification, bypassMs: Long) {
        val appContext = context.applicationContext
        single.execute { runBypass(appContext, notificationId, notification, bypassMs) }
    }

    private fun runBypass(context: Context, notificationId: Int, notification: Notification, bypassMs: Long) {
        val uid = resolveXmsfUid(context)
        var blocked = false
        try {
            if (uid != -1) blocked = blockXmsf(uid)
            if (!blocked) Log.w(TAG, "xmsf network block unavailable; posting without bypass")
            context.getSystemService(NotificationManager::class.java)
                .notify(notificationId, notification)
            if (blocked) Thread.sleep(bypassMs.coerceIn(50, 500))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (t: Throwable) {
            Log.e(TAG, "island post failed", t)
        } finally {
            if (blocked && uid != -1) unblockXmsf(uid)
        }
    }

    private fun resolveXmsfUid(context: Context): Int {
        if (xmsfUid != -1) return xmsfUid
        return runCatching {
            context.packageManager.getPackageUid(XMSF_PACKAGE, 0).also { xmsfUid = it }
        }.getOrDefault(-1)
    }

    private fun connectivity(): IConnectivityManager? = runCatching {
        val binder = SystemServiceHelper.getSystemService(Context.CONNECTIVITY_SERVICE)
            ?: return null
        val platform = IConnectivityManager.Stub.asInterface(binder) ?: return null
        IConnectivityManager.Stub.asInterface(ShizukuBinderWrapper(platform.asBinder()))
    }.getOrNull()

    private fun blockXmsf(uid: Int): Boolean {
        val cm = connectivity() ?: return false
        repeat(2) { attempt ->
            val ok = runCatching {
                cm.setFirewallChainEnabled(CHAIN, true)
                cm.setUidFirewallRule(CHAIN, uid, RULE_DENY)
            }.isSuccess
            if (ok) return true
            if (attempt == 0) runCatching { Thread.sleep(50) }
        }
        return false
    }

    /** 只清本 uid 的 DENY 规则，不禁用整条 chain（避免影响同链其他应用） */
    private fun unblockXmsf(uid: Int) {
        val cm = connectivity() ?: return
        repeat(2) { attempt ->
            val ok = runCatching {
                cm.setUidFirewallRule(CHAIN, uid, RULE_DEFAULT)
            }.isSuccess
            if (ok) return
            if (attempt == 0) runCatching { Thread.sleep(50) }
        }
        Log.e(TAG, "xmsf network restore failed after retries")
    }
}
