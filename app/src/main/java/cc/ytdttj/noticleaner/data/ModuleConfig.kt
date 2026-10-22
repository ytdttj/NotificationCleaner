package cc.ytdttj.noticleaner.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * APP → LSPosed 模块（system_server）的过滤配置快照（1.2.1）。
 * 经 libxposed remote preferences 跨进程推送：APP 侧写自身 SharedPreferences
 * （文件名 [PREFS_NAME]，libxposed service 将其映射为模块远程偏好），
 * 模块侧 XposedInterface.getRemotePreferences 同名读取并监听变更热更新。
 * 模型学习修正（delta）不经 prefs——经 openRemoteFile("spam_delta.bin") 传二进制，
 * 版本号走独立键 [ModuleConfigCodec.KEY_DELTA_VERSION]（P0-4：**不得**放进本 config JSON——
 * config 先写、delta 文件后推，版本号若随 config 下发，模块可能在 delta 就绪前就触发重载，
 * 读到上一版 delta 却把新版本号登记为"已加载"，学习成果从此永久不生效）。
 */
@Serializable
data class ModuleConfig(
    val threshold: Float = 0.8f,
    val interceptMode: Boolean = true,
    val whitelist: List<String> = emptyList(),
    val rules: List<ModuleRule> = emptyList(),
    /**
     * 渠道豁免（2.2.1 Dev 1）：元素形态 `pkg|channelId`，与 [cc.ytdttj.noticleaner.notify.RuleEngine.channelAllowKey] 一致。
     *
     * 必须下发给模块：LSPosed 模式下通知在 system_server 入队前就被判定，
     * 模块端若不知道豁免名单，会把用户刚设为「不再拦截」的渠道继续吞掉（用户表现为"设了没用"）。
     * 默认值保证旧版模块解码不炸（ignoreUnknownKeys + 有默认值）。
     */
    val allowedChannels: List<String> = emptyList(),
    /**
     * 渠道一律拦截（2.2.1 vc86）：元素形态同 [allowedChannels]。
     * 必须下发给模块 —— 否则模块端会把用户刚设为「一律拦截」的渠道继续放行（用户表现为"设了没用"）。
     * 默认值保证旧版解码兼容。
     */
    val blockedChannels: List<String> = emptyList(),
)

@Serializable
data class ModuleRule(
    val packageName: String,
    val enabled: Boolean = true,
    val join: String = "AND",
    val conditions: List<ModuleCondition> = emptyList(),
)

@Serializable
data class ModuleCondition(
    val field: String, // TITLE / CONTENT
    val mode: String, // MatchMode 常量
    val values: List<String> = emptyList(),
)

object ModuleConfigCodec {

    const val PREFS_NAME = "noticleaner_config"
    const val KEY_CONFIG = "config"
    const val KEY_DELTA_VERSION = "delta_version"
    const val DELTA_REMOTE_FILE = "spam_delta.bin"
    const val ACTION_FLUSH_LOGS = "cc.ytdttj.noticleaner.FLUSH_MODULE_LOGS"

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(config: ModuleConfig): String = json.encodeToString(config)

    fun decode(raw: String?): ModuleConfig = raw?.let {
        runCatching { json.decodeFromString<ModuleConfig>(it) }.getOrNull()
    } ?: ModuleConfig()
}
