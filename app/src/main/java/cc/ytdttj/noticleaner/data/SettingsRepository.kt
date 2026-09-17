package cc.ytdttj.noticleaner.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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

    // ---- 1.2.0（ImprovePlan P2-2）：拦截计数内存累积 + 500ms 批量落盘 ----
    // 拦截风暴（一次弹 N 条广告）时不再逐条全文件读改写 DataStore。
    // 常驻 ticker 只在有待写数据时 edit，进程被杀最多丢 500ms 窗口内的计数（仅展示用，可接受）。
    private val pendingAi = java.util.concurrent.atomic.AtomicInteger()
    private val pendingRule = java.util.concurrent.atomic.AtomicInteger()

    private val countFlushScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

    init {
        countFlushScope.launch {
            while (true) {
                kotlinx.coroutines.delay(500)
                val ai = pendingAi.getAndSet(0)
                val rule = pendingRule.getAndSet(0)
                if (ai == 0 && rule == 0) continue
                runCatching {
                    context.dataStore.edit {
                        if (ai > 0) it[keyFilteredAi] = (it[keyFilteredAi] ?: 0) + ai
                        if (rule > 0) it[keyFilteredRule] = (it[keyFilteredRule] ?: 0) + rule
                    }
                }
            }
        }
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

    /** 累计拦截计数：先入内存累积器，由后台 ticker 每 500ms 批量落盘（P2-2 防抖） */
    fun incrementFiltered(ai: Boolean) {
        (if (ai) pendingAi else pendingRule).incrementAndGet()
    }
}
