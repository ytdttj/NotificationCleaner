package cc.ytdttj.noticleaner.keepalive

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * LSPosed 模块入口（libxposed Modern API 102，作用域：系统 android / system_server）。
 *
 * 作用：阻止系统/厂商框架停止本应用的前台保活服务与通知监听服务（Plan.md §7.4）。
 * API 102 模型：入口类继承 XposedModule（框架实例化后 attachFramework 注入），
 * hook 为拦截器式 Hooker——【不调用 chain.proceed() 即阻断原方法】；
 * ExceptionMode.PROTECTIVE 等价于旧版逐个 try-catch（hook 内异常被框架吞掉并照常放行）。
 * 在 LSPosed 管理器中禁用模块即完全停用。
 */
class LspEntry : XposedModule() {

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        log(Log.INFO, TAG, "module loaded in ${param.processName} (isSystemServer=${param.isSystemServer()})")
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        try {
            val asClass = Class.forName(AS_CLASS, false, param.classLoader)
            val hooker = BlockStopHooker(this)
            var hooked = 0
            for (m in asClass.declaredMethods) {
                if (m.name in HOOK_METHODS) {
                    runCatching {
                        hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(hooker)
                    }.onSuccess { hooked++ }
                        .onFailure { log(Log.WARN, TAG, "hook ${m.name} failed: $it") }
                }
            }
            log(Log.INFO, TAG, "ActiveServices hooked: $hooked methods")
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "init failed: $t")
        }
    }

    /**
     * 拦截器：参数（含父类字段）中找到 packageName == 本应用的 ServiceRecord 时，
     * 不调用 proceed 直接返回 null → 阻止 stop/bringDown/stopServiceToken；
     * 其余调用照常 proceed。
     */
    private class BlockStopHooker(private val xposed: XposedInterface) : XposedInterface.Hooker {

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val isTarget = chain.getArgs().any { arg -> arg != null && packageNameOf(arg) == TARGET }
            if (!isTarget) return chain.proceed()
            xposed.log(Log.INFO, TAG, "blocked service stop attempt")
            return null
        }

        /** 沿类层次找 packageName 字段（等价旧 XposedHelpers.getObjectField） */
        private fun packageNameOf(arg: Any): String? {
            var c: Class<*>? = arg.javaClass
            while (c != null) {
                try {
                    val f = c.getDeclaredField("packageName")
                    f.isAccessible = true
                    return f.get(arg) as? String
                } catch (_: NoSuchFieldException) {
                    c = c.superclass
                }
            }
            return null
        }
    }

    companion object {
        private const val TAG = "NotiCleaner"
        // island 分支：跟随 applicationId（island 版包名不同，LSPosed 需单独激活本模块）
        private val TARGET = cc.ytdttj.noticleaner.BuildConfig.APPLICATION_ID
        private const val AS_CLASS = "com.android.server.am.ActiveServices"

        /** 覆盖多个 ROM 版本的方法名（存在哪个 hook 哪个，全部失败也不影响系统） */
        private val HOOK_METHODS = setOf(
            "stopServiceLocked",
            "bringDownServiceLocked",
            "stopServiceTokenLocked",
        )
    }
}
