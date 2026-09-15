package cc.ytdttj.noticleaner.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cc.ytdttj.noticleaner.notify.island.IslandNotifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
    val islandPackages: Flow<Set<String>> =
        context.dataStore.data.map { it[keyIslandPackages] ?: IslandNotifier.DEFAULT_PACKAGES }
    val islandBypassMs: Flow<Int> = context.dataStore.data.map { it[keyIslandBypassMs] ?: 100 }

    suspend fun setIslandEnabled(value: Boolean) {
        context.dataStore.edit { it[keyIslandEnabled] = value }
    }

    suspend fun setIslandPackages(value: Set<String>) {
        context.dataStore.edit { it[keyIslandPackages] = value }
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

    suspend fun incrementFiltered(ai: Boolean) {
        context.dataStore.edit {
            if (ai) it[keyFilteredAi] = (it[keyFilteredAi] ?: 0) + 1
            else it[keyFilteredRule] = (it[keyFilteredRule] ?: 0) + 1
        }
    }
}
