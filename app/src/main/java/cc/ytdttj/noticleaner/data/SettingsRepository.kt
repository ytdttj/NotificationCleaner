package cc.ytdttj.noticleaner.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cc.ytdttj.noticleaner.notify.island.IslandNotifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * 设置（Plan.md §4 / §6.3）：
 * - threshold：过滤阈值 0.5~1.0，默认 0.8
 * - interceptMode：true=达到阈值即清除；false=仅标记不拦截（观察模式）
 * - filteredAi/filteredRule：累计拦截计数（持久化，历史 7 天过期不影响）
 */
class SettingsRepository(private val context: Context) {
    private val keyThreshold = floatPreferencesKey("threshold")
    private val keyIntercept = booleanPreferencesKey("intercept_mode")
    private val keyHideRecents = booleanPreferencesKey("exclude_from_recents")
    private val keyFilteredAi = intPreferencesKey("filtered_ai_count")
    private val keyFilteredRule = intPreferencesKey("filtered_rule_count")
    private val keyOnboardingDone = booleanPreferencesKey("onboarding_done")

    // ---- 超级岛（island 分支功能，islandplan.md §四）----
    // Dev 5 重构：删除 island_bypass_ms / island_drop_blind（iptables 盲窗方案废弃，
    // 仅保留 LSPosed 模式）。旧 key 残留在 DataStore 中无害，不做迁移。
    private val keyIslandEnabled = booleanPreferencesKey("island_enabled")
    private val keyIslandPackages = stringSetPreferencesKey("island_packages")

    // ---- 1.2.0（ImprovePlan P2-2）：拦截计数内存累积 + 500ms 批量落盘 ----
    // 拦截风暴（一次弹 N 条广告）时不再逐条全文件读改写 DataStore。
    // 1.3.2（P3-3）：常驻 ticker 改为按需启动的一次性 flush 协程——
    // incrementFiltered 时才起协程（含 500ms 防抖窗口），写完且无新增即退出；
    // 零拦截期间完全休眠，不再每秒唤醒 2 次。
    private val pendingAi = java.util.concurrent.atomic.AtomicInteger()
    private val pendingRule = java.util.concurrent.atomic.AtomicInteger()

    private val countFlushScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )
    private var flushJob: kotlinx.coroutines.Job? = null

    init {
        // 冷启动时补一次 flush（进程被杀可能留下未落盘的增量；无增量时立即退出，零开销）
        countFlushScope.launch { flushPendingCounters() }
    }

    val threshold: Flow<Float> = context.dataStore.data.map { it[keyThreshold] ?: 0.8f }
    val interceptMode: Flow<Boolean> = context.dataStore.data.map { it[keyIntercept] ?: true }

    /** 在系统多任务界面隐藏本 APP 的后台卡片（防误滑删除，切换后重建任务生效） */
    val excludeFromRecents: Flow<Boolean> = context.dataStore.data.map { it[keyHideRecents] ?: false }

    /** 累计拦截数（常驻通知展示）：AI 拦截 / 用户规则拦截 */
    val filteredAiCount: Flow<Int> = context.dataStore.data.map { it[keyFilteredAi] ?: 0 }
    val filteredRuleCount: Flow<Int> = context.dataStore.data.map { it[keyFilteredRule] ?: 0 }

    /** 权限初始化流程已完成（1.1.8 首次引入；默认 false → 老版本升级后也会走一遍初始化） */
    val onboardingDone: Flow<Boolean> = context.dataStore.data.map { it[keyOnboardingDone] ?: false }

    // ---- 超级岛（island 分支功能）----

    val islandEnabled: Flow<Boolean> = context.dataStore.data.map { it[keyIslandEnabled] ?: false }

    /**
     * 岛白名单（1.3.2 Dev 2：读取时应用废弃包名迁移——
     * 老版本保存的勾选集合里"com.cmbchina.cmb.plainpinkage"→"cmb.pb"，
     * 避免招行旧包名残留导致上岛静默失效）
     */
    val islandPackages: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        val saved = prefs[keyIslandPackages] ?: return@map IslandNotifier.DEFAULT_PACKAGES
        saved.map { IslandNotifier.PACKAGE_MIGRATION[it] ?: it }.toSet()
    }
    suspend fun setIslandEnabled(value: Boolean) {
        context.dataStore.edit { it[keyIslandEnabled] = value }
    }

    suspend fun setIslandPackages(value: Set<String>) {
        context.dataStore.edit { it[keyIslandPackages] = value }
    }

    /** 通知模拟解锁（隐藏测试功能：设置 tab 快速点击 5 次后启用，1.3.0 beta2） */
    private val keySimUnlocked = booleanPreferencesKey("sim_unlocked")
    val simUnlocked: Flow<Boolean> = context.dataStore.data.map { it[keySimUnlocked] ?: false }
    suspend fun setSimUnlocked(value: Boolean) {
        context.dataStore.edit { it[keySimUnlocked] = value }
    }

    // ---- 更新通道（1.3.2）：稳定版=Gitee / Dev 版=GitHub，默认稳定版 ----
    private val keyUpdateChannel = stringPreferencesKey("update_channel")

    /** 取值为 [cc.ytdttj.noticleaner.update.UpdateChannel] 的 name（"STABLE"/"DEV"） */
    val updateChannel: Flow<String> = context.dataStore.data.map { it[keyUpdateChannel] ?: "STABLE" }

    suspend fun setUpdateChannel(value: String) {
        context.dataStore.edit { it[keyUpdateChannel] = value }
    }

    // ---- 历史通知保留天数（Dev 6）：监控式滚动，最新的顶掉 N 天前的 ----
    private val keyHistoryRetentionDays = intPreferencesKey("history_retention_days")

    /** 历史通知保留天数（1-90，默认 7）；已学习的标注不受清理影响 */
    val historyRetentionDays: Flow<Int> = context.dataStore.data.map { it[keyHistoryRetentionDays] ?: 7 }

    suspend fun setHistoryRetentionDays(value: Int) {
        context.dataStore.edit { it[keyHistoryRetentionDays] = value.coerceIn(1, 90) }
    }

    // ---- 渠道豁免「此渠道不再拦截」（2.2.1 Dev 1）----
    // 元素形态 `pkg|channelId`（见 RuleEngine.channelAllowKey）。用 Set 而非 DataStore 的
    // 布尔键列表：渠道数量随用户使用增长，键名会无限膨胀。
    private val keyChannelAllowlist = stringSetPreferencesKey("channel_allowlist")

    /**
     * 已豁免的渠道（跳过 AI 过滤，规则仍生效）。
     *
     * 存在意义：抖音这类「同一渠道既发私信又发广告」的场景，模型逐条学习永远追不上
     * 渠道级抬升，普通用户需要一条**不需要理解模型**的出路。
     */
    val channelAllowlist: Flow<Set<String>> =
        context.dataStore.data.map { it[keyChannelAllowlist] ?: emptySet() }

    suspend fun setChannelAllowlist(value: Set<String>) {
        context.dataStore.edit { it[keyChannelAllowlist] = value }
    }

    /** 把某渠道加入豁免（幂等） */
    suspend fun allowChannel(key: String) {
        if (key.isBlank()) return
        context.dataStore.edit { it[keyChannelAllowlist] = (it[keyChannelAllowlist] ?: emptySet()) + key }
    }

    /** 取消某渠道豁免 */
    suspend fun disallowChannel(key: String) {
        if (key.isBlank()) return
        context.dataStore.edit { it[keyChannelAllowlist] = (it[keyChannelAllowlist] ?: emptySet()) - key }
    }

    // ---- 渠道一律拦截（2.2.1 vc86：「此渠道不再拦截」的对称面） ----
    private val keyChannelBlocklist = stringSetPreferencesKey("channel_blocklist")

    /**
     * 已设为「一律拦截」的渠道（元素形态 `pkg|channelId`，与豁免集合相同）。
     *
     * 存在意义：百度网盘这类「整条渠道只发营销」的场景，逐条学习追不上（每条广告文案全新）、
     * 渠道先验又被护栏封顶 —— 需要一条**确定性**的出口，不依赖模型与学习。
     */
    val channelBlocklist: Flow<Set<String>> =
        context.dataStore.data.map { it[keyChannelBlocklist] ?: emptySet() }

    /** 把某渠道加入一律拦截（幂等），并从豁免集合移除（两集合互斥） */
    suspend fun blockChannel(key: String) {
        if (key.isBlank()) return
        context.dataStore.edit {
            it[keyChannelBlocklist] = (it[keyChannelBlocklist] ?: emptySet()) + key
            it[keyChannelAllowlist] = (it[keyChannelAllowlist] ?: emptySet()) - key
        }
    }

    /** 取消某渠道的一律拦截 */
    suspend fun unblockChannel(key: String) {
        if (key.isBlank()) return
        context.dataStore.edit { it[keyChannelBlocklist] = (it[keyChannelBlocklist] ?: emptySet()) - key }
    }

    /** 清理截止时间戳：now - 保留天数（purgeOlderThan 的 cutoff） */
    suspend fun historyRetentionCutoff(now: Long): Long =
        now - historyRetentionDays.first().toLong() * 86_400_000L

    // ---- 界面风格（1.4.0 Dev 4）：MATERIAL=Material 3 / GLASS=液态玻璃（Kyant0 Backdrop） ----
    private val keyUiTheme = stringPreferencesKey("ui_theme")

    /** 取值为 [cc.ytdttj.noticleaner.ui.UiTheme] 的 name */
    val uiTheme: Flow<String> = context.dataStore.data.map { it[keyUiTheme] ?: "MATERIAL" }

    suspend fun setUiTheme(value: String) {
        context.dataStore.edit { it[keyUiTheme] = value }
    }

    // ---- 玻璃清晰度（1.4.0 Dev 5）：FROSTED=磨砂（可读性） / SOFT=柔光（近乎全透明） ----
    private val keyGlassStyle = stringPreferencesKey("glass_style")

    /** 取值为 [cc.ytdttj.noticleaner.ui.GlassStyle] 的 name；仅玻璃主题下生效 */
    val glassStyle: Flow<String> = context.dataStore.data.map { it[keyGlassStyle] ?: "FROSTED" }

    suspend fun setGlassStyle(value: String) {
        context.dataStore.edit { it[keyGlassStyle] = value }
    }

    suspend fun setThreshold(value: Float) {
        val clamped = value.coerceIn(0.5f, 1.0f)
        context.dataStore.edit { it[keyThreshold] = clamped }
    }

    suspend fun setInterceptMode(value: Boolean) {
        context.dataStore.edit { it[keyIntercept] = value }
    }

    suspend fun setExcludeFromRecents(value: Boolean) {
        context.dataStore.edit { it[keyHideRecents] = value }
    }

    suspend fun setOnboardingDone() {
        context.dataStore.edit { it[keyOnboardingDone] = true }
    }

    /** 累计拦截计数：先入内存累积器，有待写数据时按需启动一次性 flush 协程批量落盘（P2-2 防抖 / P3-3 按需） */
    fun incrementFiltered(ai: Boolean) {
        (if (ai) pendingAi else pendingRule).incrementAndGet()
        synchronized(this) {
            if (flushJob?.isActive != true) {
                flushJob = countFlushScope.launch { flushPendingCounters() }
            }
        }
    }

    /**
     * 一次性 flush：防抖 500ms → 落盘 → 若落盘期间又有新增则继续，无新增即退出（协程结束，零唤醒）。
     *
     * P1-13（flush 竞态丢数）：旧实现在普通位置 return——「最终 getAndSet(0,0) 之后、
     * 协程状态变为 inactive 之前」进来的新增会看到 isActive==true 而不启动新 job，
     * 这部分计数就滞留到下次进程启动。现改为：退出判定收进 finally 的锁内——
     * 先置空 flushJob 再检查累积器，锁与 [incrementFiltered] 共用，两个方向都不会漏：
     * - 新增在 flusher 持锁前进来 → flusher 锁内看到非零，原地重启 flush；
     * - 新增在 flusher 置空后进来 → incrementFiltered 看到空 job，自己启动 flush。
     */
    private suspend fun flushPendingCounters() {
        try {
            while (true) {
                kotlinx.coroutines.delay(500)
                val ai = pendingAi.getAndSet(0)
                val rule = pendingRule.getAndSet(0)
                if (ai == 0 && rule == 0) return
                runCatching {
                    context.dataStore.edit {
                        if (ai > 0) it[keyFilteredAi] = (it[keyFilteredAi] ?: 0) + ai
                        if (rule > 0) it[keyFilteredRule] = (it[keyFilteredRule] ?: 0) + rule
                    }
                }
            }
        } finally {
            synchronized(this) {
                flushJob = null
                if (pendingAi.get() > 0 || pendingRule.get() > 0) {
                    flushJob = countFlushScope.launch { flushPendingCounters() }
                }
            }
        }
    }
}
