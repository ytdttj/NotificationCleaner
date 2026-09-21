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
    private val keyIslandEnabled = booleanPreferencesKey("island_enabled")
    private val keyIslandPackages = stringSetPreferencesKey("island_packages")
    private val keyIslandBypassMs = intPreferencesKey("island_bypass_ms")
    private val keyIslandDropBlind = booleanPreferencesKey("island_drop_blind")

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
    val islandBypassMs: Flow<Int> = context.dataStore.data.map { it[keyIslandBypassMs] ?: 100 }

    /** 免 LSPosed 模式：iptables DROP 盲窗（需 Root），关闭时走 xmsf auth hook */
    val islandDropBlind: Flow<Boolean> = context.dataStore.data.map { it[keyIslandDropBlind] ?: false }

    suspend fun setIslandEnabled(value: Boolean) {
        context.dataStore.edit { it[keyIslandEnabled] = value }
    }

    suspend fun setIslandPackages(value: Set<String>) {
        context.dataStore.edit { it[keyIslandPackages] = value }
    }

    suspend fun setIslandDropBlind(value: Boolean) {
        context.dataStore.edit { it[keyIslandDropBlind] = value }
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

    /** 一次性 flush：防抖 500ms → 落盘 → 若落盘期间又有新增则继续，无新增即退出（协程结束，零唤醒） */
    private suspend fun flushPendingCounters() {
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
    }
}
