package cc.ytdttj.noticleaner.keepalive

import android.content.Context
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.util.Collections
import java.util.WeakHashMap

/**
 * 超级岛焦点通知白名单解锁（island 分支，islandv2plan P3-2 / island.md §3.2）。
 *
 * 真机诊断结论（2026-09-18）：iptables 盲窗断网 xmsf 成功、岛通知进系统，仍不上岛 →
 * SystemUI 在**本地白名单校验**阶段直接拒绝（canShowFocus），与云认证网络无关。
 * 本 hook 在 SystemUI 进程内放行 island 版包名的焦点通知校验。
 *
 * 多回退（类前缀 × 方法名 × 类加载器随 HyperOS 版本漂移，参考 island.md §3.2）：
 * - miui.systemui / com.miui.systemui 前缀的 NotificationSettingsManager
 * - canShowFocus / canShowFocusState / canShowFocusStateApp / canCustomFocus
 * - 新版 HyperOS 上该类在"焦点通知插件"的动态 ClassLoader 里：
 *   hook PluginInstance$PluginFactory.createPluginContext 拿到插件 CL 后再挂
 *
 * 仅当调用参数中出现 island 版包名时返回 true，其余调用照常 proceed。
 */
class IslandUnlockFocusHook(private val module: XposedModule) {

    companion object {
        private const val TARGET_PKG = cc.ytdttj.noticleaner.BuildConfig.APPLICATION_ID
        private val CANDIDATE_CLASSES = listOf(
            "miui.systemui.notification.NotificationSettingsManager",
            "com.miui.systemui.notification.NotificationSettingsManager",
            // 签名校验（island 包名无小米签名，可能在校验链第二层拒绝）
            "miui.systemui.notification.focus.SignatureChecker",
            "com.miui.systemui.notification.focus.SignatureChecker",
        )
        private val CANDIDATE_METHODS = setOf(
            "canShowFocus", "canShowFocusState", "canShowFocusStateApp", "canCustomFocus",
            "checkSignatures",
        )
        private const val PLUGIN_FACTORY =
            "com.android.systemui.shared.plugins.PluginInstance\$PluginFactory"

        private val hookedLoaders =
            Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    }

    fun onPackageLoaded(param: PackageLoadedParam) {
        val cl = param.defaultClassLoader
        if (!tryHook(cl, "SystemUI 主 CL")) {
            hookPluginFactory(cl)
        }
    }

    /** 对指定 ClassLoader 挂白名单 hook；成功记日志 */
    private fun tryHook(cl: ClassLoader, tag: String): Boolean {
        synchronized(hookedLoaders) {
            if (hookedLoaders.contains(cl)) return true
        }
        var hooked = 0
        for (name in CANDIDATE_CLASSES) {
            val clazz = runCatching { cl.loadClass(name) }.getOrNull() ?: continue
            for (m in clazz.declaredMethods) {
                if (m.name in CANDIDATE_METHODS && m.returnType == Boolean::class.javaPrimitiveType) {
                    runCatching {
                        module.hook(m)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(AllowFocusHooker(module, "$name.${m.name}"))
                        hooked++
                        module.log(
                            android.util.Log.INFO, "NCIslandHook",
                            "hooked $name.${m.name}(${m.parameterTypes.joinToString { it.simpleName }})",
                        )
                    }.onFailure { t ->
                        module.log(android.util.Log.WARN, "NCIslandHook", "hook $name.${m.name} failed: $t")
                    }
                }
            }
        }
        if (hooked > 0) {
            synchronized(hookedLoaders) { hookedLoaders.add(cl) }
            module.log(android.util.Log.INFO, "NCIslandHook", "focus whitelist unlocked: $hooked methods ($tag)")
        }
        return hooked > 0
    }

    /**
     * 新版 HyperOS：NotificationSettingsManager 随"焦点通知插件"动态加载。
     * hook createPluginContext，在插件 CL 就绪后再挂主 hook。
     */
    private fun hookPluginFactory(cl: ClassLoader) {
        val factory = runCatching { cl.loadClass(PLUGIN_FACTORY) }.getOrNull() ?: run {
            module.log(android.util.Log.WARN, "NCIslandHook", "plugin factory not found; focus unlock inactive")
            return
        }
        for (m in factory.declaredMethods.filter { it.name == "createPluginContext" }) {
            runCatching {
                module.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(PluginFactoryHooker(this))
            }
        }
        module.log(android.util.Log.INFO, "NCIslandHook", "plugin factory hooked, waiting for focus plugin CL")
    }

    /**
     * 校验拦截器：参数中出现 island 包名（如 canShowFocusState(type, pkg)）→ 返回 true 放行；
     * 其余调用照常 proceed，不影响其他应用。
     * 每次命中相关方法时 dump 方法名+参数形态到模块日志，用于诊断校验链。
     */
    private class AllowFocusHooker(private val module: XposedModule, private val methodName: String) :
        XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val argsDump = chain.args.joinToString(",") { a ->
                "${a?.javaClass?.simpleName}=${a?.toString()?.take(40)}"
            }
            for (arg in chain.args) {
                if (arg is String && arg == TARGET_PKG) {
                    module.log(
                        android.util.Log.INFO, "NCIslandHook",
                        "intercept $methodName($argsDump) → ALLOW",
                    )
                    return true
                }
            }
            // 包名相关但未精确匹配的调用也记录（诊断参数形态）
            val related = chain.args.any { (it as? String)?.contains("noticleaner") == true }
            if (related) {
                module.log(
                    android.util.Log.INFO, "NCIslandHook",
                    "intercept $methodName($argsDump) → proceed",
                )
            }
            return chain.proceed()
        }
    }

    /** createPluginContext after-hook：从返回的 Context 拿插件 ClassLoader，重试主 hook */
    private class PluginFactoryHooker(private val hook: IslandUnlockFocusHook) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            if (result is Context) {
                runCatching { hook.tryHook(result.classLoader, "焦点通知插件 CL") }
            }
            return result
        }
    }
}
