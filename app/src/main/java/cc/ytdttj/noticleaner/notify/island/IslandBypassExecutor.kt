package cc.ytdttj.noticleaner.notify.island

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 岛通知发送执行器（island 分支）。
 *
 * 两条认证放行通道（islandv2plan P3）：
 * - **LSPosed 模式**（默认，推荐）：hook SystemUI 白名单（IslandUnlockFocusHook）+
 *   xmsf 云认证（XmsfUnlockAuthHook），无需断网。
 * - **免 LSPosed 模式**（useDropBlind=true，需 Root）：参考 SignalDock 机制，
 *   发送前 iptables **DROP** 式断开 xmsf 网络——auth 请求被静默挂起（非立即失败），
 *   SystemUI 在挂起期间乐观渲染岛。注意必须用 DROP 而非 REJECT：REJECT 立即
 *   ECONNREFUSED → onAuthFailed → 通知被移除（OS3 真机实测）。
 *
 * 单线程串行执行；失败静默降级，绝不影响通知净化主流程。
 */
object IslandBypassExecutor {

    private const val TAG = "IslandBypass"
    private const val XMSF_PACKAGE = "com.xiaomi.xmsf"

    private val single = ThreadPoolExecutor(
        1, 1, 30L, TimeUnit.SECONDS, LinkedBlockingQueue(),
    ) { r -> Thread(r, "IslandBypass").apply { isDaemon = true } }

    @Volatile
    private var xmsfUid: Int = -1

    /** Shizuku 可用且已授权（诊断/兼容检查用；DROP 盲窗走 root iptables） */
    fun isReady(): Boolean = runCatching {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** 发送岛通知。useDropBlind=true 时走 iptables DROP 盲窗（免 LSPosed，需 Root） */
    fun post(
        context: Context,
        notificationId: Int,
        notification: Notification,
        bypassMs: Long,
        useDropBlind: Boolean = false,
    ) {
        val appContext = context.applicationContext
        single.execute { runBypass(appContext, notificationId, notification, bypassMs, useDropBlind) }
    }

    private enum class BlindMode { NONE, IPTABLES }

    private fun runBypass(
        context: Context,
        notificationId: Int,
        notification: Notification,
        bypassMs: Long,
        useDropBlind: Boolean,
    ) {
        var mode = BlindMode.NONE
        try {
            if (useDropBlind) {
                val uid = resolveXmsfUid(context)
                if (uid != -1) mode = blockXmsfDrop(uid)
                if (mode == BlindMode.NONE) {
                    IslandTrace.log("⚠ DROP 盲窗未开启（无 Root 或 iptables 失败），直发岛通知")
                }
            }
            context.getSystemService(NotificationManager::class.java)
                .notify(notificationId, notification)
            IslandTrace.log(
                "岛通知已提交系统 (id=$notificationId" +
                    if (mode == BlindMode.IPTABLES) ", DROP 盲窗挂起认证中)" else ", xmsf auth hook 放行)",
            )
            if (mode != BlindMode.NONE) Thread.sleep(bypassMs.coerceIn(50, 500))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (t: Throwable) {
            IslandTrace.log("✗ 岛通知提交失败: $t")
            Log.e(TAG, "island post failed", t)
        } finally {
            if (mode == BlindMode.IPTABLES) {
                resolveXmsfUid(context).takeIf { it != -1 }?.let { unblockXmsfIptables(it) }
            }
        }
    }

    private fun resolveXmsfUid(context: Context): Int {
        if (xmsfUid != -1) return xmsfUid
        return runCatching {
            context.packageManager.getPackageUid(XMSF_PACKAGE, 0).also { xmsfUid = it }
        }.getOrDefault(-1)
    }

    // ---- DROP 盲窗（免 LSPosed，需 Root）----

    private fun execRoot(cmd: String): String = runCatching {
        val p = ProcessBuilder("su", "-c", cmd).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        val err = p.errorStream.bufferedReader().use { it.readText() }
        p.waitFor()
        (out + err).trim()
    }.getOrElse { "ROOT_FAIL: $it" }

    private fun rootAvailable(): Boolean = runCatching {
        val p = ProcessBuilder("su", "-c", "id").start()
        val ok = p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0
        p.destroy()
        ok
    }.getOrDefault(false)

    private fun blockXmsfDrop(uid: Int): BlindMode {
        if (!rootAvailable()) {
            IslandTrace.log("✗ DROP 盲窗需要 Root（未检测到 su）")
            return BlindMode.NONE
        }
        val v4 = execRoot("iptables -I OUTPUT -m owner --uid-owner $uid -j DROP && echo OK4")
        val ok4 = v4.contains("OK4")
        val v6 = execRoot("ip6tables -I OUTPUT -m owner --uid-owner $uid -j DROP && echo OK6")
        val ok6 = v6.contains("OK6")
        return if (ok4 || ok6) {
            IslandTrace.log("✓ DROP 盲窗开启：xmsf(uid=$uid) 流量静默丢弃 v4=$ok4 v6=$ok6")
            BlindMode.IPTABLES
        } else {
            IslandTrace.log("✗ DROP 盲窗失败: ${v4.take(80)} / ${v6.take(80)}")
            BlindMode.NONE
        }
    }

    private fun unblockXmsfIptables(uid: Int) {
        val v4 = execRoot("iptables -D OUTPUT -m owner --uid-owner $uid -j DROP && echo DEL4")
        val v6 = execRoot("ip6tables -D OUTPUT -m owner --uid-owner $uid -j DROP && echo DEL6")
        if (v4.contains("DEL4") || v6.contains("DEL6")) {
            IslandTrace.log("✓ DROP 盲窗已还原 (v4=${v4.contains("DEL4")} v6=${v6.contains("DEL6")})")
        } else {
            IslandTrace.log("✗ DROP 盲窗还原失败: ${v4.take(80)} / ${v6.take(80)}")
        }
    }
}
