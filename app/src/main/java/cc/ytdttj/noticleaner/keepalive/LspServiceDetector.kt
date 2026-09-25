package cc.ytdttj.noticleaner.keepalive

import androidx.compose.runtime.Immutable
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * LSPosed 框架服务检测（Dev 7，机制借鉴 ref/HyperIsland XposedPrefsSyncApp）：
 *
 * App 进程经 XposedServiceHelper 注册后，LSPosed 框架会向【已启用】的模块 App
 * 绑定服务（与作用域无关）——绑定本身即"模块已激活"的标准证据；
 * service.scope 提供当前作用域列表；requestScope 可程序化请求作用域
 * （LSPosed 弹授权框，用户批准后回调）。
 *
 * App.onServiceBind/onServiceDied 须转发到 [onServiceBound]/[onServiceDied]。
 */
object LspServiceDetector {

    @Immutable
    data class LspServiceState(
        val bound: Boolean = false,
        val frameworkName: String = "",
        val frameworkVersion: String = "",
        val scope: Set<String> = emptySet(),
    ) {
        fun hasScope(pkg: String): Boolean = pkg in scope
        fun hasAllScope(pkgs: Collection<String>): Boolean = pkgs.all { it in scope }
    }

    /** 岛链路作用域目标 */
    const val SCOPE_SYSTEM_UI = "com.android.systemui"
    const val SCOPE_XMSF = "com.xiaomi.xmsf"
    /** 保活 hook 作用域目标（system_server） */
    const val SCOPE_SYSTEM_SERVER = "android"

    @Volatile
    private var service: XposedService? = null

    private val _state = MutableStateFlow(LspServiceState())

    /** Compose/VM 直接订阅（绑定/作用域变化自动刷新） */
    val state: StateFlow<LspServiceState> = _state.asStateFlow()

    fun onServiceBound(s: XposedService) {
        service = s
        _state.value = LspServiceState(
            bound = true,
            frameworkName = s.frameworkName.orEmpty(),
            frameworkVersion = s.frameworkVersion.orEmpty(),
            scope = runCatching { s.scope.toSet() }.getOrDefault(emptySet()),
        )
    }

    fun onServiceDied() {
        service = null
        _state.value = LspServiceState()
    }

    /** LSPosed 框架服务已绑定（= 模块已在 LSPosed 中启用） */
    fun isFrameworkBound(): Boolean = service != null

    /**
     * 程序化请求作用域：缺哪些请求哪些（已在 scope 的自动跳过，不会重复弹框）。
     * 回调可能在非主线程；失败时携带可读原因（含"框架服务未绑定"）。
     */
    fun requestScope(pkgs: List<String>, onResult: (Result<List<String>>) -> Unit) {
        val s = service ?: run {
            onResult(Result.failure(IllegalStateException("LSPosed 框架服务未绑定，请先在 LSPosed 中启用本模块")))
            return
        }
        val missing = pkgs.filter { it !in _state.value.scope }
        if (missing.isEmpty()) {
            onResult(Result.success(_state.value.scope.toList()))
            return
        }
        s.requestScope(missing, object : XposedService.OnScopeEventListener {
            override fun onScopeRequestApproved(scope: List<String>) {
                service = s
                _state.value = LspServiceState(true, s.frameworkName.orEmpty(), s.frameworkVersion.orEmpty(), scope.toSet())
                onResult(Result.success(scope))
            }

            override fun onScopeRequestFailed(message: String) {
                onResult(Result.failure(IllegalStateException(message)))
            }
        })
    }
}
