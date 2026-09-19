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

    private enum class BlindMode { NONE, BINDER, IPTABLES }

    private fun runBypass(context: Context, notificationId: Int, notification: Notification, bypassMs: Long) {
        // 盲窗已停用（islandv2plan P3）：OS3 真机证实岛认证由 xmsf 联网完成且断网 fail-closed
        // （iptables 断网 → ECONNREFUSED → onAuthFailed）。认证改由 LSPosed hook xmsf
        // AuthSession 强制成功（XmsfUnlockAuthHook），无需断网。
        try {
            context.getSystemService(NotificationManager::class.java)
                .notify(notificationId, notification)
            IslandTrace.log("岛通知已提交系统 (id=$notificationId, 认证由 xmsf hook 放行)")
        } catch (t: Throwable) {
            IslandTrace.log("✗ 岛通知提交失败: $t")
            Log.e(TAG, "island post failed", t)
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

    private fun blockXmsf(uid: Int): BlindMode {
        val cm = connectivity() ?: run {
            IslandTrace.log("✗ 盲窗：ConnectivityService Binder 获取失败（Shizuku 不可用？）")
            return BlindMode.NONE
        }
        repeat(2) { attempt ->
            val result = runCatching {
                cm.setFirewallChainEnabled(CHAIN, true)
                cm.setUidFirewallRule(CHAIN, uid, RULE_DENY)
            }
            if (result.isSuccess) {
                IslandTrace.log("✓ 盲窗开启：xmsf(uid=$uid) 网络已断 (chain=$CHAIN)")
                return BlindMode.BINDER
            }
            val e = result.exceptionOrNull()
            IslandTrace.log("✗ 盲窗断网失败(${attempt + 1}/2): ${e?.javaClass?.simpleName}: ${e?.message}")
            // NoSuchMethodError = 目标平台 IConnectivityManager 无此方法（HyperOS tethering APEX），
            // 重试无意义，立即转 iptables
            if (e is NoSuchMethodError) return blockXmsfIptables(uid)
            if (attempt == 0) runCatching { Thread.sleep(50) }
        }
        return blockXmsfIptables(uid)
    }

    // ---- iptables 回退（root；Binder 接口不存在时的盲窗路径）----

    private fun execRoot(cmd: String): String = runCatching {
        val p = ProcessBuilder("su", "-c", cmd).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        val err = p.errorStream.bufferedReader().use { it.readText() }
        p.waitFor()
        (out + err).trim()
    }.getOrElse { "ROOT_FAIL: $it" }

    private fun blockXmsfIptables(uid: Int): BlindMode {
        if (!rootAvailable()) {
            IslandTrace.log("✗ iptables 盲窗需要 Root（未检测到 su），放弃盲窗")
            return BlindMode.NONE
        }
        val v4 = execRoot("iptables -I OUTPUT -m owner --uid-owner $uid -j REJECT && echo OK4")
        val ok4 = v4.contains("OK4")
        if (!ok4) IslandTrace.log("✗ iptables v4: ${v4.take(120)}")
        val v6 = execRoot("ip6tables -I OUTPUT -m owner --uid-owner $uid -j REJECT && echo OK6")
        val ok6 = v6.contains("OK6")
        if (!ok6) IslandTrace.log("⚠ ip6tables v6: ${v6.take(120)}")
        return if (ok4 || ok6) {
            IslandTrace.log("✓ 盲窗开启(iptables/root)：xmsf(uid=$uid) 网络已断 v4=$ok4 v6=$ok6")
            BlindMode.IPTABLES
        } else {
            IslandTrace.log("✗ iptables 盲窗失败（v4/v6 均未生效）")
            BlindMode.NONE
        }
    }

    /** 只清本 uid 的 DENY 规则，不禁用整条 chain（避免影响同链其他应用） */
    private fun unblockXmsfBinder(uid: Int) {
        val cm = connectivity() ?: return
        repeat(2) { attempt ->
            val ok = runCatching {
                cm.setUidFirewallRule(CHAIN, uid, RULE_DEFAULT)
            }.isSuccess
            if (ok) {
                IslandTrace.log("✓ 盲窗恢复：xmsf 网络已还原")
                return
            }
            if (attempt == 0) runCatching { Thread.sleep(50) }
        }
        IslandTrace.log("✗ 盲窗恢复失败（重试 2 次），xmsf 可能仍断网——请检查网络或重启")
        Log.e(TAG, "xmsf network restore failed after retries")
    }

    private fun unblockXmsfIptables(uid: Int) {
        val v4 = execRoot("iptables -D OUTPUT -m owner --uid-owner $uid -j REJECT && echo DEL4")
        val v6 = execRoot("ip6tables -D OUTPUT -m owner --uid-owner $uid -j REJECT && echo DEL6")
        val ok = v4.contains("DEL4") || v6.contains("DEL6")
        if (ok) {
            IslandTrace.log("✓ iptables 盲窗已还原 (v4=${v4.contains("DEL4")} v6=${v6.contains("DEL6")})")
        } else {
            IslandTrace.log("✗ iptables 还原失败: ${v4.take(80)} / ${v6.take(80)}")
        }
    }

    private fun rootAvailable(): Boolean = runCatching {
        val p = ProcessBuilder("su", "-c", "id").start()
        val ok = p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0
        p.destroy()
        ok
    }.getOrDefault(false)
}
