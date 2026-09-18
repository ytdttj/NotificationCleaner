package cc.ytdttj.noticleaner.keepalive

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.lang.reflect.Method

/**
 * LSPosed 模块入口（libxposed Modern API 102，作用域：系统 android / system_server）。
 *
 * 两组 hook：
 * 1. 保活（Plan.md §7.4）：拦截 ActiveServices 的 stop 系列，阻止系统/厂商框架停止本应用服务。
 * 2. 入队前拦截（1.2.1，借鉴 ref/Notice）：hook NotificationManagerService.enqueueNotificationInternal，
 *    通知入队前在 system_server 内完成决策——命中即吞掉，根除 NLS 进程冻结/被杀导致的过滤延迟。
 *    拦截记录经 ModuleLogSink 回流 APP（ModuleLogProvider），历史与学习闭环完整。
 *
 * API 102 模型：入口类继承 XposedModule（框架实例化后 attachFramework 注入），
 * hook 为拦截器式 Hooker——【不调用 chain.proceed() 即阻断原方法】；
 * ExceptionMode.PROTECTIVE：hook 内异常被框架吞掉并照常放行。
 * 在 LSPosed 管理器中禁用模块即完全停用。
 */
class LspEntry : XposedModule() {

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        log(Log.INFO, TAG, "module loaded in ${param.processName} (isSystemServer=${param.isSystemServer()})")
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        hookActiveServices(param.classLoader)
        hookNotificationManagerService(param.classLoader)
    }

    // ---- Hook 组 1：防服务被停（保活） ----

    private fun hookActiveServices(classLoader: ClassLoader) {
        try {
            val asClass = Class.forName(AS_CLASS, false, classLoader)
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

    // ---- Hook 组 2：入队前拦截（1.2.1） ----

    private fun hookNotificationManagerService(classLoader: ClassLoader) {
        try {
            val nms = Class.forName(NMS_CLASS, false, classLoader)
            val target = longestEnqueue(nms)
            if (target == null) {
                log(Log.WARN, TAG, "enqueueNotificationInternal not found")
                return
            }
            val engine = FilterEngine().also { it.attach(this) }
            val sink = ModuleLogSink()
            hook(target).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(NmsBlockHooker(engine, sink))
            log(Log.INFO, TAG, "NMS enqueueNotificationInternal hooked (${target.parameterCount} params)")
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "NMS hook failed: $t")
        }
    }

    /** 取参数最多的 enqueueNotificationInternal 重载（跨 ROM 版本兜底） */
    private fun longestEnqueue(nms: Class<*>): Method? {
        val methods = ArrayList<Method>()
        var current: Class<*>? = nms
        while (current != null && current != Any::class.java) {
            methods += current.declaredMethods.filter { it.name == "enqueueNotificationInternal" }
            current = current.superclass
        }
        return methods.maxByOrNull { it.parameterTypes.size }
    }

    /**
     * 入队前拦截器：解析参数（pkg, Notification）→ FilterEngine 决策；
     * 命中 → 回流记录 + 返回 skipResult（boolean 返回类型给 false，否则 null）阻断入队；
     * 未命中/异常 → 照常 proceed。
     */
    private class NmsBlockHooker(
        private val engine: FilterEngine,
        private val sink: ModuleLogSink,
    ) : XposedInterface.Hooker {

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val args = chain.args
            val notification = args.firstOrNull { it is android.app.Notification } as? android.app.Notification
            var pkg: String? = null
            for (arg in args) {
                if (arg is String && arg.contains('.')) {
                    pkg = arg
                    break
                }
            }
            val outcome = try {
                engine.decide(pkg.orEmpty(), notification)
            } catch (t: Throwable) {
                android.util.Log.w("NCWatch", "module decide failed: $t")
                null
            }
            if (outcome == null || !outcome.block) return chain.proceed()

            android.util.Log.i("NCWatch", "enqueue blocked: ${outcome.decision} p=${outcome.probability} ${pkg.orEmpty()}")
            val postTime = System.currentTimeMillis()
            val values = android.content.ContentValues().apply {
                put("package", pkg.orEmpty())
                put("channel", notification?.channelId.orEmpty())
                put("title", outcome.title)
                put("content", outcome.content)
                put("post_time", postTime)
                put("probability", outcome.probability)
                put("decision", outcome.decision)
                put("key", "mod:${pkg.orEmpty()}:$postTime")
            }
            (chain.thisObject?.let { nmsContext(it) })?.let { sink.submit(it, values) }
            return skipResult(chain.executable as Method)
        }

        private fun nmsContext(service: Any): android.content.Context? = try {
            val m = service.javaClass.getMethod("getContext")
            m.invoke(service) as? android.content.Context
        } catch (_: Throwable) {
            try {
                val f = service.javaClass.getField("mContext")
                f.get(service) as? android.content.Context
            } catch (_: Throwable) {
                null
            }
        }

        private fun skipResult(method: Method): Any? = when (method.returnType) {
            java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> java.lang.Boolean.FALSE
            else -> null
        }
    }

    companion object {
        private const val TAG = "NotiCleaner"
        // island 分支：跟随 applicationId（island 版包名不同，LSPosed 需单独激活本模块）
        private val TARGET = cc.ytdttj.noticleaner.BuildConfig.APPLICATION_ID
        private const val AS_CLASS = "com.android.server.am.ActiveServices"
        private const val NMS_CLASS = "com.android.server.notification.NotificationManagerService"

        /** 覆盖多个 ROM 版本的方法名（存在哪个 hook 哪个，全部失败也不影响系统） */
        private val HOOK_METHODS = setOf(
            "stopServiceLocked",
            "bringDownServiceLocked",
            "stopServiceTokenLocked",
        )
    }
}
