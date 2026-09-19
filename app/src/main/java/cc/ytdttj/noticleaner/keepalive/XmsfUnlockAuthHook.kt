package cc.ytdttj.noticleaner.keepalive

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * xmsf 焦点通知认证解锁（island 分支，islandv2plan P3）。
 *
 * 作用域：com.xiaomi.xmsf（小米服务框架）。
 *
 * 真机诊断（2026-09-19，OS3）：岛通知的云端认证由 xmsf 联网
 * hyperos.developer.xiaomi.com 完成，**断网时 fail-closed**（onAuthFailed → removeByKey），
 * iptables 盲窗方案在 OS3 上方向相反。本 hook 在 xmsf 进程内拦截认证失败回调，
 * 强制走成功路径 —— 与 SignalDock/HyperIsland 的 UnlockFocusAuthHook 同一机制
 * （HyperIsland 为 MIT 许可，此处按公开机制自行实现）。
 *
 * hook 点：com.xiaomi.xms.auth.AuthSession.b(error)
 * - error == null：认证成功，照常 proceed
 * - error != null：将错误码字段 `a` 置 0，调用成功回调 `h()`，跳过原方法
 * 混淆名随 xmsf 版本可能漂移，找不到时记日志并静默退出。
 */
class XmsfUnlockAuthHook(private val module: XposedModule) {

    companion object {
        private const val AUTH_SESSION_CLASS = "com.xiaomi.xms.auth.AuthSession"
    }

    fun onPackageLoaded(param: PackageLoadedParam) {
        val cl = param.defaultClassLoader
        val authSession = runCatching { cl.loadClass(AUTH_SESSION_CLASS) }.getOrNull() ?: run {
            module.log(android.util.Log.WARN, "NCIslandHook", "AuthSession not found in xmsf CL (version changed?)")
            return
        }
        val target = authSession.declaredMethods
            .filter { it.name == "b" && it.parameterCount == 1 }
            .firstOrNull()
        if (target == null) {
            module.log(android.util.Log.WARN, "NCIslandHook", "AuthSession.b(error) not found (xmsf version changed?)")
            return
        }
        runCatching {
            module.hook(target)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(AuthBypassHooker(module))
            module.log(android.util.Log.INFO, "NCIslandHook", "hooked AuthSession.b(error) — focus auth unlocked")
        }.onFailure {
            module.log(android.util.Log.WARN, "NCIslandHook", "hook AuthSession.b failed: $it")
        }
    }

    /** 认证失败拦截：强制 errorCode=0 并调用成功回调 h() */
    private class AuthBypassHooker(private val module: XposedModule) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val error = chain.args.getOrNull(0) ?: return chain.proceed() // 无错误 = 成功，照常
            return runCatching {
                setIntField(error!!, "a", 0)
                val success = callNoArg(chain.thisObject, "h")
                module.log(android.util.Log.INFO, "NCIslandHook", "auth bypassed (errorCode forced to 0)")
                success
            }.getOrElse {
                module.log(android.util.Log.WARN, "NCIslandHook", "auth bypass failed: $it")
                chain.proceed()
            }
        }
    }
}

private fun setIntField(instance: Any, fieldName: String, value: Int) {
    var c: Class<*>? = instance.javaClass
    while (c != null) {
        runCatching {
            val f = c.getDeclaredField(fieldName)
            f.isAccessible = true
            f.set(instance, value)
            return
        }.onFailure { e ->
            if (e !is NoSuchFieldException) throw e
        }
        c = c.superclass
    }
}

private fun callNoArg(instance: Any?, methodName: String): Any? {
    if (instance == null) return null
    var c: Class<*>? = instance.javaClass
    while (c != null) {
        runCatching {
            val m = c.getDeclaredMethod(methodName)
            m.isAccessible = true
            return m.invoke(instance)
        }.onFailure { e ->
            if (e !is NoSuchMethodException) throw e
        }
        c = c.superclass
    }
    return null
}
