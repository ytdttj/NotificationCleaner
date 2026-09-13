package cc.ytdttj.noticleaner.keepalive

import android.app.Notification
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_LoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * LSPosed 模块入口（Xposed API，作用域：系统 android / system_server）。
 *
 * 作用：阻止系统/厂商框架停止本应用的前台保活服务与通知监听服务（Plan.md §7.4）。
 * Hook 全部 try-catch 包裹，任一失败不影响系统运行；在 LSPosed 管理器中禁用模块即完全停用。
 */
class LspEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpp: XC_LoadPackage.LoadPackageParam) {
        if (lpp.packageName != "android") return // 仅 system_server
        try {
            XposedBridge.log("[NotiCleaner] LSPosed module loaded, hooking ActiveServices")
            val asClass = XposedHelpers.findClass("com.android.server.am.ActiveServices", lpp.classLoader)
            val hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        // 参数中找到 ServiceRecord，检查其 packageName 是否为本应用
                        for (arg in param.args) {
                            arg ?: continue
                            val pkg = runCatching {
                                XposedHelpers.getObjectField(arg, "packageName") as? String
                            }.getOrNull()
                            if (pkg == TARGET) {
                                // 阻止 stop/bringDown 本应用的服务
                                param.result = null
                                return
                            }
                        }
                    } catch (t: Throwable) {
                        XposedBridge.log("[NotiCleaner] hook error: $t")
                    }
                }
            }
            // 覆盖多个 ROM 版本的方法名（存在哪个 hook 哪个，全部失败也不影响系统）
            for (m in listOf("stopServiceLocked", "bringDownServiceLocked", "stopServiceTokenLocked")) {
                try {
                    XposedBridge.hookAllMethods(asClass, m, hook)
                } catch (t: Throwable) {
                    XposedBridge.log("[NotiCleaner] hook $m failed: $t")
                }
            }
        } catch (t: Throwable) {
            XposedBridge.log("[NotiCleaner] init failed: $t")
        }
    }

    companion object {
        private const val TARGET = "cc.ytdttj.noticleaner"
    }
}
