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
import cc.ytdttj.noticleaner.data.db.DECISION_CHANNEL_BLOCK
import cc.ytdttj.noticleaner.data.db.DECISION_FILTERED_BY_AI_MODULE
import cc.ytdttj.noticleaner.data.db.DECISION_FILTERED_BY_RULE_MODULE
import cc.ytdttj.noticleaner.data.db.RuleCompiler
import cc.ytdttj.noticleaner.data.db.RuleCondition
import cc.ytdttj.noticleaner.data.db.RuleConditionSet
import cc.ytdttj.noticleaner.notify.FilterGuards
import cc.ytdttj.noticleaner.notify.NotificationProtector
import cc.ytdttj.noticleaner.notify.RuleEngine

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

    /**
     * 渠道豁免集合（2.2.1 Dev 1）：元素形态 `pkg|channelId`。
     * 与 NLS 端 [cc.ytdttj.noticleaner.notify.RuleEngine.isChannelAllowed] 必须同语义——
     * 模块端若不知道豁免名单，会把用户刚设为「不再拦截」的渠道继续在 system_server 吞掉，
     * 通知永远到不了 App，用户表现为"设了完全没用"。
     */
    @Volatile private var allowedChannels: Set<String> = emptySet()

    /**
     * 渠道一律拦截集合（2.2.1 vc86）：元素形态 `pkg|channelId`。
     * 与 NLS 端 [cc.ytdttj.noticleaner.notify.RuleEngine.isChannelBlocked] 必须同语义——
     * 模块端若不知道拦截名单，会把用户刚设为「一律拦截」的渠道继续放行（"设了没用"）。
     */
    @Volatile private var blockedChannels: Set<String> = emptySet()

    /** 已应用用户学习修正的内置模型；未加载为 null（此时全部放行，靠 NLS 兜底） */
    @Volatile private var model: SpamModel? = null
    @Volatile private var loadedDeltaVersion = Long.MIN_VALUE

    /** P0-4：最近一次从 KEY_DELTA_VERSION 读到的 delta 版本（重试与日志以它为准） */
    @Volatile private var latestDeltaVersion = 0L

    // P0-2：MIUI extraNotification 反射缓存（探测一次，成功缓存 Field/Method，失败永久短路）
    @Volatile private var extraField: java.lang.reflect.Field? = null
    @Volatile private var targetPkgGetter: java.lang.reflect.Method? = null
    @Volatile private var resolveProbed = false

    /** 预编译规则快照（config.rules 变化时重建） */
    @Volatile private var compiledRules: List<CompiledModuleRule> = emptyList()

    /** 模型未就绪告警限频时间戳（2.2.0 Dev 10；避免高频入队时刷屏） */
    @Volatile private var lastModelWarnAt = 0L


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

        // 渠道一律拦截（2.2.1 vc86）：与 NLS 端同语义、同优先级（规则已优先；压过白名单/豁免）
        val channel = notification.channelId.orEmpty()
        if (channel.isNotEmpty() &&
            RuleEngine.channelAllowKey(pkg, channel) in blockedChannels
        ) {
            return Outcome(true, DECISION_CHANNEL_BLOCK, 0f, title, joined)
        }

        // 白名单：跳过 AI 过滤
        if (pkg in cfg.whitelist) return null

        // 渠道豁免（2.2.1 Dev 1）：与 NLS 端同语义、同优先级（规则已在上方优先判定）
        if (channel.isNotEmpty() &&
            RuleEngine.channelAllowKey(pkg, channel) in allowedChannels
        ) {
            return null
        }

        // AI 判定（与 NLS 一致：归一化文本 = title + content 合流）
        val aiText = listOf(title, joined).filter { it.isNotEmpty() }.joinToString("\n")
        val normalized = FeatureHasher.normalize(aiText)
        if (normalized.length < 4 ||
            FilterGuards.HARD_ALLOW_WORDS_NORMALIZED.any { normalized.contains(it) }
        ) {
            return null
        }
        if (!cfg.interceptMode) return null // 观察模式：入队走 NLS 原路径记录
        val m = model ?: run {
            // 2.2.0 Dev 10：此前此处**静默 return null（放行）**，导致"hook 装着、不报错、
            // 却一条都没拦"在日志上查无痕迹（20261005 排查只能靠反证定性）。降级必须可见。
            warnModelNotReady()
            return null
        }

        // 渠道已在白名单/豁免判定处取过（channel 变量复用，避免重复构建字符串）
        val chKey = if (channel.isNotEmpty()) FeatureHasher.channelKey(pkg, channel) else null
        // vc87：App 先验桶（fit 三态互斥，delta 里至多一个非零）
        val p0 = m.scoreNormalized(normalized, FeatureHasher.appKey(pkg), chKey)
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
            // P0-4：delta 版本号只认独立键 KEY_DELTA_VERSION（不再藏在 config JSON 里），
            // 该键由 APP 侧在 delta 文件写完**之后**才写——收到即文件就绪
            latestDeltaVersion = prefs.getLong(ModuleConfigCodec.KEY_DELTA_VERSION, 0L)
            refreshModel(api)
            val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, _ ->
                runCatching {
                    refresh(p)
                    latestDeltaVersion = p.getLong(ModuleConfigCodec.KEY_DELTA_VERSION, 0L)
                    refreshModel(api)
                    android.util.Log.i("NCWatch", "module config hot-updated: rules=${config.rules.size} deltaV=$latestDeltaVersion")
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
        allowedChannels = next.allowedChannels.toSet()
        blockedChannels = next.blockedChannels.toSet()
        // P0-2：编译时过滤掉用户已禁用的规则——旧实现丢弃了 enabled 位，
        // "关闭规则"在 LSPosed 模式下形同虚设（NLS 侧 RuleEngine 是检查的）
        compiledRules = next.rules.filter { it.enabled }.map { r ->
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
    private fun refreshModel(api: io.github.libxposed.api.XposedInterface?, attempt: Int = 0) {
        val version = latestDeltaVersion
        if (version == loadedDeltaVersion && model != null) return
        rebuildExecutor.execute { rebuildModel(api, version, attempt) }
    }

    /** 模型重建本体（rebuildExecutor 单线程）；[version] 为触发本次重建时看到的版本号 */
    private fun rebuildModel(api: io.github.libxposed.api.XposedInterface?, version: Long, attempt: Int) {
        runCatching {
            if (version == loadedDeltaVersion && model != null) return@runCatching
            val base = loadBaseModel()
            var next = base
            // 2.0.1 Dev 11：只有**真正读到并应用了** delta 才算这个版本加载完成。
            // 旧实现无论 openRemoteFile 返回 null（文件还没被 APP 写入——见
            // ModuleConfigSync 的 TOCTOU）还是解码失败，都把版本号登记为已加载，
            // 于是模块端就此停在 base 模型上，用户"学习为正常"后仍被按广告拦截。
            var deltaApplied = false
            if (base != null && version != 0L && api != null) {
                runCatching {
                    val pfd = api.openRemoteFile(ModuleConfigCodec.DELTA_REMOTE_FILE)
                    if (pfd == null) {
                        android.util.Log.w("NCWatch", "module delta file unavailable (v$version)")
                    } else {
                        pfd.use {
                            val delta = SpamDelta.decode(
                                android.os.ParcelFileDescriptor.AutoCloseInputStream(it),
                            )
                            // P0-4：读取并解码成功即视为该版本已应用——**空 delta 也是合法
                            // 内容**（APP 侧在全部标注取消/重置时推空 delta 覆盖残留旧文件），
                            // 语义为"回到 base"。旧实现把空 delta 当作"文件未就绪"反复重试，
                            // 且登记不了版本号。
                            next = if (delta.isEmpty) base else base.withDelta(delta)
                            deltaApplied = true
                            android.util.Log.i(
                                "NCWatch",
                                if (delta.isEmpty) "module delta v$version loaded: EMPTY (back to base)"
                                else "module delta v$version loaded: ${delta.indices.size} weights",
                            )
                        }
                    }
                }.onFailure { android.util.Log.w("NCWatch", "module delta load failed: $it") }
            }
            model = next
            if (version == 0L || deltaApplied) {
                loadedDeltaVersion = version
            } else if (attempt < DELTA_RETRY_MAX) {
                // 未拿到 delta：退避重试（APP 侧可能还在写文件）。
                // 关键：不登记版本号，保证重试仍会触发；重试以**当前**版本号为准
                //（等待期间用户可能又学了一条）
                val delay = DELTA_RETRY_DELAYS_MS[attempt.coerceIn(0, DELTA_RETRY_DELAYS_MS.lastIndex)]
                android.util.Log.w("NCWatch", "module delta v$version not applied, retry in ${delay}ms")
                Thread.sleep(delay)
                rebuildModel(api, latestDeltaVersion, attempt + 1)
            } else {
                android.util.Log.w("NCWatch", "module delta v$version gave up after $DELTA_RETRY_MAX retries")
            }
        }.onFailure { android.util.Log.w("NCWatch", "module model rebuild failed: $it") }
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

    /**
     * 2.2.0 Dev 10：模型未就绪 → 本引擎退化为"全部放行"时的**显式告警**（限频 5 分钟）。
     *
     * 排查教训（20261005）：`decide()` 的降级分支以往全是静默 `return null`，
     * 于是"入队拦截 hook 已安装"与"它其实一条都没拦"在日志上长得一模一样，
     * 只能靠反证定性。任何会让功能整体失效的降级，都必须留下可检索的痕迹。
     */
    private fun warnModelNotReady() {
        val now = System.currentTimeMillis()
        if (now - lastModelWarnAt < MODEL_WARN_INTERVAL_MS) return
        lastModelWarnAt = now
        android.util.Log.w(
            "NCWatch",
            "module model NOT ready -> enqueue filter is PASS-THROUGH " +
                "(deltaV=$latestDeltaVersion, baseLoadFailed=$baseLoadFailed)",
        )
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

        /** 2.2.0 Dev 10：模型未就绪告警的限频间隔 */
        private const val MODEL_WARN_INTERVAL_MS = 5 * 60 * 1000L

        /** Dev 11：delta 未就绪时的退避重试（最多 3 次：1s / 3s / 8s） */
        private const val DELTA_RETRY_MAX = 3
        private val DELTA_RETRY_DELAYS_MS = longArrayOf(1_000L, 3_000L, 8_000L)

        // P0-3：base 模型进程级缓存（system_server 内 class/model 唯一，缓存安全）
        @Volatile private var cachedBase: SpamModel? = null
        @Volatile private var baseLoadFailed = false
    }

    /** P0-3：模型重建专用单线程（串行化重建，避免与 decide() 的并发读互相干扰） */
    private val rebuildExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "NCWatch-ModelRebuild").apply { isDaemon = true }
    }
}
