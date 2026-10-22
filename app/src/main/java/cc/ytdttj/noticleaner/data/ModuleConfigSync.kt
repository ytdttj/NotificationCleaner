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

    /** 5 路输入的合并载体（2.2.1 Dev 1）：kotlinx 的 combine 无 6 元同构重载，故先合 5 路再与渠道豁免合流 */
    private class CoreConfig(
        val rules: List<cc.ytdttj.noticleaner.data.db.RuleEntity>,
        val whitelist: List<cc.ytdttj.noticleaner.data.db.WhitelistEntity>,
        val threshold: Float,
        val intercept: Boolean,
        val deltaV: Long,
    )

    /** 框架服务（App.onServiceBind 注入）；delta 文件推送需要，null = 模块未激活/框架不可用 */
    @Volatile
    var xposedService: io.github.libxposed.service.XposedService? = null

    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        // 1.3.2（P1-5）：整个同步协程挪 IO 池 + 500ms 防抖（注释承诺但此前未实现），
        // 配置抖动合并为一次落盘；commit → apply，不再阻塞 CPU 池线程
        scope.launch(Dispatchers.IO) {
            // 2.2.1 Dev 1：渠道豁免加入同步集合后共 6 路输入，而 kotlinx 的 combine 只提供
            // 到 5 个同构重载——用嵌套 combine（5 + 1）保持类型安全，不用 vararg 数组。
            val core = combine(
                ruleDao.listAll(),
                whitelistDao.listAll(),
                ServiceLocator.settings.threshold,
                ServiceLocator.settings.interceptMode,
                ServiceLocator.modelRepo.deltaVersion,
            ) { rules, whitelist, threshold, intercept, deltaV ->
                CoreConfig(rules, whitelist, threshold, intercept, deltaV)
            }
            combine(
                core,
                ServiceLocator.settings.channelAllowlist,
                ServiceLocator.settings.channelBlocklist,
            ) { c, allow, block ->
                ModuleConfig(
                    threshold = c.threshold,
                    interceptMode = c.intercept,
                    deltaVersion = c.deltaV,
                    whitelist = c.whitelist.map { it.packageName },
                    rules = c.rules.map { r ->
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
                    allowedChannels = allow.toList(),
                    blockedChannels = block.toList(),
                )
            }
                .debounce(500)
                .collect { config ->
                    prefs.edit()
                        .putString(ModuleConfigCodec.KEY_CONFIG, ModuleConfigCodec.encode(config))
                        .apply()
                    // 2.0.1 Dev 11：**先写 delta 文件，再写版本号**——版本号是模块端
                    // "内容已就绪"的信号。旧顺序（版本号与 config 一起先写、delta 后推）
                    // 存在 TOCTOU：模块端 OnSharedPreferenceChangeListener 一收到版本号变化
                    // 就 openRemoteFile 去读，而此时 pushDelta 还没写（或只写了一半）→
                    // 读到**上一版** delta；更糟的是模块端随后把该版本号登记为"已加载"，
                    // 之后版本号不再变化就不会重试 → **学习成果在模块端永久不生效**
                    //（实测：抖音同一条通知 NLS p=0.0025 放行、模块端 p=0.98 拦截）。
                    if (config.deltaVersion != 0L) pushDelta()
                    prefs.edit().putLong(ModuleConfigCodec.KEY_DELTA_VERSION, config.deltaVersion).apply()
                }
        }
    }

    /** Xposed 服务绑定完成（App.onServiceBind 调用）：补推 delta + 版本号 */
    suspend fun onServiceBound() {
        val v = ServiceLocator.modelRepo.deltaVersion.value
        // 同上：先文件后版本号
        if (v != 0L) pushDelta()
        prefs.edit().putLong(ModuleConfigCodec.KEY_DELTA_VERSION, v).commit()
    }

    /** 经框架 openRemoteFile 写 delta 二进制（学习修正文件 → 模块可读） */
    private suspend fun pushDelta() = withContext(Dispatchers.IO) {
        val service = xposedService
        if (service == null) {
            // Dev 11：未绑定≠正常，此前静默 return，模块端一直用 base 打分而日志上毫无痕迹
            cc.ytdttj.noticleaner.diagnostics.RingLog.log(
                cc.ytdttj.noticleaner.diagnostics.LogModules.MODEL,
                "△ delta 未推送：Xposed 服务未绑定（模块端暂用内置基线打分）",
            )
            return@withContext
        }
        pushMutex.withLock {
            runCatching {
                // Dev 10（P1-3）：经 ModelRepository 在同一锁域取快照，
                // 不再直接读文件（旧写法与 applyDelta 的写入锁不共享 → 可能推撕裂文件给模块）
                val bytes = ServiceLocator.modelRepo.deltaSnapshot()
                if (bytes == null) {
                    cc.ytdttj.noticleaner.diagnostics.RingLog.log(
                        cc.ytdttj.noticleaner.diagnostics.LogModules.MODEL,
                        "△ delta 未推送：当前无学习修正（模型为内置基线）",
                    )
                    return@runCatching
                }
                service.openRemoteFile(ModuleConfigCodec.DELTA_REMOTE_FILE)?.use { pfd ->
                    ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { out ->
                        out.write(bytes)
                    }
                }
                cc.ytdttj.noticleaner.diagnostics.RingLog.log(
                    cc.ytdttj.noticleaner.diagnostics.LogModules.MODEL,
                    "delta 已推送到模块：${bytes.size}B",
                )
            }.onFailure {
                android.util.Log.w("NCWatch", "module delta push failed: $it")
                cc.ytdttj.noticleaner.diagnostics.RingLog.log(
                    cc.ytdttj.noticleaner.diagnostics.LogModules.MODEL,
                    "✗ delta 推送失败: $it（模块端仍用旧/基线模型打分）",
                )
            }
        }
    }
}
