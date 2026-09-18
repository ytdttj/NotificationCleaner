package cc.ytdttj.noticleaner.data

import android.content.Context
import android.os.ParcelFileDescriptor
import cc.ytdttj.noticleaner.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * APP → LSPosed 模块配置同步（1.2.1）：
 * - 规则/白名单/阈值/拦截模式任一变化 → 500ms 防抖后编码 [ModuleConfig] 写入
 *   自身 SharedPreferences（[ModuleConfigCodec.PREFS_NAME]，libxposed 映射为模块远程偏好）
 * - delta 版本变化 或 Xposed 服务绑定完成 → 经 openRemoteFile 推送 delta 二进制并写版本号
 *
 * 模块未激活时同步照常运行（写文件零成本），LSPosed 激活后自动生效。
 */
class ModuleConfigSync(
    private val context: Context,
    private val ruleDao: cc.ytdttj.noticleaner.data.db.RuleDao,
    private val whitelistDao: cc.ytdttj.noticleaner.data.db.WhitelistDao,
) {
    private val prefs = context.getSharedPreferences(ModuleConfigCodec.PREFS_NAME, Context.MODE_PRIVATE)
    private val pushMutex = Mutex()

    /** 框架服务（App.onServiceBind 注入）；delta 文件推送需要，null = 模块未激活/框架不可用 */
    @Volatile
    var xposedService: io.github.libxposed.service.XposedService? = null

    fun start(scope: CoroutineScope) {
        scope.launch {
            @OptIn(FlowPreview::class)
            combine(
                ruleDao.listAll(),
                whitelistDao.listAll(),
                ServiceLocator.settings.threshold,
                ServiceLocator.settings.interceptMode,
                ServiceLocator.modelRepo.deltaVersion,
            ) { rules, whitelist, threshold, intercept, deltaV ->
                ModuleConfig(
                    threshold = threshold,
                    interceptMode = intercept,
                    deltaVersion = deltaV,
                    whitelist = whitelist.map { it.packageName },
                    rules = rules.map { r ->
                        val set = r.conditionSet()
                        ModuleRule(
                            packageName = r.packageName,
                            enabled = r.enabled,
                            join = set.join,
                            conditions = set.conditions.map {
                                ModuleCondition(it.field, it.mode, it.values)
                            },
                        )
                    },
                )
            }.collect { config ->
                // 写 prefs（commit 同步，框架监听文件变更推送模块）
                prefs.edit()
                    .putString(ModuleConfigCodec.KEY_CONFIG, ModuleConfigCodec.encode(config))
                    .putLong(ModuleConfigCodec.KEY_DELTA_VERSION, config.deltaVersion)
                    .commit()
                // delta 二进制推送（需要框架服务）
                if (config.deltaVersion != 0L) pushDelta()
            }
        }
    }

    /** Xposed 服务绑定完成（App.onServiceBind 调用）：补推 delta + 版本号 */
    suspend fun onServiceBound() {
        val v = ServiceLocator.modelRepo.deltaVersion.value
        prefs.edit().putLong(ModuleConfigCodec.KEY_DELTA_VERSION, v).commit()
        if (v != 0L) pushDelta()
    }

    /** 经框架 openRemoteFile 写 delta 二进制（学习修正文件 → 模块可读） */
    private suspend fun pushDelta() = withContext(Dispatchers.IO) {
        val service = xposedService ?: return@withContext
        pushMutex.withLock {
            runCatching {
                val deltaFile = java.io.File(context.filesDir, "model/spam_delta.bin")
                if (!deltaFile.exists()) return@runCatching
                service.openRemoteFile(ModuleConfigCodec.DELTA_REMOTE_FILE)?.use { pfd ->
                    ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { out ->
                        out.write(deltaFile.readBytes())
                    }
                }
            }.onFailure {
                android.util.Log.w("NCWatch", "module delta push failed: $it")
            }
        }
    }
}
