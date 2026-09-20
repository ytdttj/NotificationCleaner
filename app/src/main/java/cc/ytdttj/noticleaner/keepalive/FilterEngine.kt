package cc.ytdttj.noticleaner.keepalive

import android.app.Notification
import android.content.Context
import cc.ytdttj.noticleaner.ai.FeatureHasher
import cc.ytdttj.noticleaner.ai.SpamDelta
import cc.ytdttj.noticleaner.ai.SpamModel
import cc.ytdttj.noticleaner.data.ModuleConfig
import cc.ytdttj.noticleaner.data.ModuleConfigCodec
import cc.ytdttj.noticleaner.data.ModuleRule
import cc.ytdttj.noticleaner.data.db.CompiledCondition
import cc.ytdttj.noticleaner.data.db.DECISION_FILTERED_BY_AI_MODULE
import cc.ytdttj.noticleaner.data.db.DECISION_FILTERED_BY_RULE_MODULE
import cc.ytdttj.noticleaner.data.db.RuleCompiler
import cc.ytdttj.noticleaner.data.db.RuleCondition
import cc.ytdttj.noticleaner.data.db.RuleConditionSet
import cc.ytdttj.noticleaner.notify.FilterGuards
import cc.ytdttj.noticleaner.notify.NotificationProtector

/**
 * LSPosed 模块端判定引擎（1.2.1，借鉴 ref/Notice KeywordFilter 架构）：
 * 在 system_server 内、通知入队前完成决策——命中即吞掉（skipResult），
 * 通知永远不进系统，从根上消除 NLS 进程冻结/被杀导致的过滤延迟。
 *
 * 决策语义与 CleanerListenerService.handle 逐条对齐：
 * 自身豁免 → 群摘要放行 → 内置保护类型放行 → MIUI targetPkg 解析 → 规则（BLOCK_RULE）
 * → 白名单放行 → 硬放行护栏 → 观察模式放行 → AI 打分 + '>' 抬升 + 阈值（BLOCK_AI）。
 * 任何异常一律放行（PROTECTIVE + 内层 runCatching 双保险）。
 */
internal class FilterEngine {

    /** 一次判定的结果 */
    data class Outcome(
        val block: Boolean,
        val decision: String, // BLOCK 时为 MODULE 决策常量
        val probability: Float,
        val title: String,
        val content: String,
    )

    @Volatile private var config = ModuleConfig()

    /** 已应用用户学习修正的内置模型；未加载为 null（此时全部放行，靠 NLS 兜底） */
    @Volatile private var model: SpamModel? = null
    @Volatile private var loadedDeltaVersion = Long.MIN_VALUE

    // P0-2：MIUI extraNotification 反射缓存（探测一次，成功缓存 Field/Method，失败永久短路）
    @Volatile private var extraField: java.lang.reflect.Field? = null
    @Volatile private var targetPkgGetter: java.lang.reflect.Method? = null
    @Volatile private var resolveProbed = false

    /** 预编译规则快照（config.rules 变化时重建） */
    @Volatile private var compiledRules: List<CompiledModuleRule> = emptyList()

    private class CompiledModuleRule(
        val packageName: String,
        val conditions: List<CompiledCondition>,
        val join: String,
    )

    /** 从 NMS 入口参数解析出的上下文 */
    class Parsed(val pkg: String, val channelId: String, val notification: Notification)

    /**
     * 入队前判定。[ctx] 为 NMS Context（供打日志/回流使用），可为 null。
     * 返回 null 表示无法解析（放行）。
     */
    fun decide(pkgRaw: String, notification: Notification?): Outcome? {
        if (notification == null) return null
        val pkg = resolvePackage(pkgRaw, notification).ifBlank { return null }
        if (pkg == SELF_PKG) return null
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null

        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val content = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val joined = listOf(content, bigText).filter { it.isNotEmpty() }.distinct().joinToString(" ")
        if (title.isEmpty() && joined.isEmpty()) return null

        // 内置保护类型：与 NotificationProtector 相同语义，媒体/对话/常驻不过滤
        if (NotificationProtector.classifyType(notification) != null) return null

        val cfg = config
        // 规则（先于白名单，与 NLS 一致：白名单 APP 仍受规则约束）
        val snapshot = compiledRules
        for (rule in snapshot) {
            if (rule.packageName != pkg) continue
            if (rule.conditions.isEmpty()) continue
            val hit = if (rule.join == RuleConditionSet.OR_JOIN) {
                rule.conditions.any { it.eval(title, joined) }
            } else {
                rule.conditions.all { it.eval(title, joined) }
            }
            if (hit) return Outcome(true, DECISION_FILTERED_BY_RULE_MODULE, 0f, title, joined)
        }

        // 白名单：跳过 AI 过滤
        if (pkg in cfg.whitelist) return null

        // AI 判定（与 NLS 一致：归一化文本 = title + content 合流）
        val aiText = listOf(title, joined).filter { it.isNotEmpty() }.joinToString("\n")
        val normalized = FeatureHasher.normalize(aiText)
        if (normalized.length < 4 ||
            FilterGuards.HARD_ALLOW_WORDS_NORMALIZED.any { normalized.contains(it) }
        ) {
            return null
        }
        if (!cfg.interceptMode) return null // 观察模式：入队走 NLS 原路径记录
        val m = model ?: return null

        val channel = notification.channelId.orEmpty()
        val chKey = if (channel.isNotEmpty()) FeatureHasher.channelKey(pkg, channel) else null
        val p0 = m.scoreNormalized(normalized, chKey)
        val p = if (aiText.contains('>') || aiText.contains('＞')) {
            maxOf(p0, FilterGuards.SPAM_MARK_BOOST)
        } else {
            p0
        }
        return if (p >= cfg.threshold) {
            Outcome(true, DECISION_FILTERED_BY_AI_MODULE, p.toFloat(), title, joined)
        } else {
            null
        }
    }

    // ---- 配置与模型加载（attach 由 LspEntry 调用一次） ----

    fun attach(api: io.github.libxposed.api.XposedInterface) {
        try {
            val prefs = api.getRemotePreferences(ModuleConfigCodec.PREFS_NAME)
            refresh(prefs)
            refreshModel(api)
            val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, _ ->
                runCatching {
                    refresh(p)
                    refreshModel(api)
                    android.util.Log.i("NCWatch", "module config hot-updated: rules=${config.rules.size} deltaV=${config.deltaVersion}")
                }
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
        } catch (t: Throwable) {
            android.util.Log.w("NCWatch", "module remote prefs unavailable: $t")
        }
    }

    private fun refresh(prefs: android.content.SharedPreferences) {
        val next = ModuleConfigCodec.decode(prefs.getString(ModuleConfigCodec.KEY_CONFIG, null))
        config = next
        compiledRules = next.rules.map { r ->
            val set = RuleConditionSet(
                join = r.join,
                conditions = r.conditions.map { RuleCondition(it.field, it.mode, it.values) },
            )
            FilterEngine.CompiledModuleRule(r.packageName, RuleCompiler.compile(set), set.join)
        }
    }

    /**
     * delta 版本变化才重载模型；base 经模块 APK classpath 读取（P0-3：base 只加载一次，
     * 缓存于 companion；重建在单线程 Executor 上异步执行，`model` @Volatile 原子换引用，
     * 重建期间旧模型继续可用，语义为"延迟生效"）。
     */
    private fun refreshModel(api: io.github.libxposed.api.XposedInterface?) {
        val version = config.deltaVersion
        if (version == loadedDeltaVersion && model != null) return
        rebuildExecutor.execute {
            runCatching {
                if (version == loadedDeltaVersion && model != null) return@runCatching
                val base = loadBaseModel()
                var next = base
                if (base != null && version != 0L && api != null) {
                    runCatching {
                        api.openRemoteFile(ModuleConfigCodec.DELTA_REMOTE_FILE)?.use { pfd ->
                            val delta = SpamDelta.decode(android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd))
                            if (!delta.isEmpty) {
                                next = base.withDelta(delta)
                                android.util.Log.i("NCWatch", "module delta v$version loaded: ${delta.indices.size} weights")
                            }
                        }
                    }.onFailure { android.util.Log.w("NCWatch", "module delta load failed: $it") }
                }
                model = next
                loadedDeltaVersion = version
            }.onFailure { android.util.Log.w("NCWatch", "module model rebuild failed: $it") }
        }
    }

    /** base 模型只加载一次；加载失败置负极标记，避免每次学习事件都重试 0.5MB IO。 */
    private fun loadBaseModel(): SpamModel? {
        cachedBase?.let { return it }
        if (baseLoadFailed) return null
        val base = runCatching {
            SpamModel::class.java.classLoader
                ?.getResourceAsStream(MODEL_RESOURCE)?.use { SpamModel.load(it) }
        }.onFailure {
            android.util.Log.w("NCWatch", "module base model load failed: $it")
        }.getOrNull()
        if (base == null) baseLoadFailed = true else cachedBase = base
        return base
    }

    /** MIUI：通知可能由系统框架代发，extraNotification.targetPkg 才是真实包名（借鉴 ref/Notice Xiaomi.kt） */
    private fun resolvePackage(pkg: String, notification: Notification): String {
        // P0-2：反射结果缓存（system_server 内 Notification 类唯一，缓存安全）。
        // 慢路径（字段/方法查找）只发生在第一条通知；此后每条仅 2 次反射调用。
        val field = extraField
        val getter = targetPkgGetter
        if (field != null && getter != null) {
            val target = runCatching {
                val extra = field.get(notification)
                if (extra != null) getter.invoke(extra) as? String else null
            }.getOrNull()
            return if (!target.isNullOrBlank()) target else pkg
        }
        if (resolveProbed) return pkg // 探测失败（非 MIUI）→ 永久短路
        return runCatching {
            val f = notification.javaClass.getField("extraNotification")
            val m = f.type.methods
                .firstOrNull { it.name == "getTargetPkg" && it.parameterCount == 0 }
            if (m == null) {
                resolveProbed = true
                return pkg
            }
            val extra = f.get(notification)
            val target = if (extra != null) m.invoke(extra) as? String else null
            // 探测成功即缓存（与 target 本次是否非空无关，extra 可能后续才有值）
            extraField = f
            targetPkgGetter = m
            if (!target.isNullOrBlank()) target else pkg
        }.getOrElse {
            resolveProbed = true
            pkg
        }
    }

    fun isWhitelisted(pkg: String): Boolean = pkg in config.whitelist

    companion object {
        private const val SELF_PKG = "cc.ytdttj.noticleaner"
        private const val MODEL_RESOURCE = "model/model.bin"

        // P0-3：base 模型进程级缓存（system_server 内 class/model 唯一，缓存安全）
        @Volatile private var cachedBase: SpamModel? = null
        @Volatile private var baseLoadFailed = false
    }

    /** P0-3：模型重建专用单线程（串行化重建，避免与 decide() 的并发读互相干扰） */
    private val rebuildExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "NCWatch-ModelRebuild").apply { isDaemon = true }
    }
}
