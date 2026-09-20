package cc.ytdttj.noticleaner.update

import android.os.Build
import cc.ytdttj.noticleaner.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * 更新通道（1.3.2）：
 * - 稳定版：版本号 x.x.x，经 Gitee 检查与下载（Release/发行版）
 * - Dev 版：版本号 x.x.x Dev N（N 为该版本的 Dev 轮次），仅经 GitHub 检查与下载；
 *   versionCode 与稳定版同一序列正常递增（如 1.3.1=29 → 1.3.2 Dev 1=30 → 1.3.2=32），
 *   因此升级比较只看 versionCode，Dev 用户会自然收到同版本的稳定版更新提示。
 */
enum class UpdateChannel(val label: String) {
    STABLE("稳定版"),
    DEV("Dev 版"),
}

/**
 * 更新检查（1.2.2 自 UpdateViewModel 抽出，供应用内检查与后台 Worker 共用）。
 * 1.3.2 更新分流：不再双源排序取首个成功——按更新通道单源获取：
 * - 稳定版（x.x.x）→ Gitee（正式 Release 的 latest.json）
 * - Dev 版（x.x.x Dev N）→ GitHub（Dev Release 的 latest.json）
 * 显式 UA + 手动跟随 3xx 重定向 + 剥离 BOM（与 1.1.4 网络栈语义一致）。
 */
object UpdateChecker {

    data class CheckResult(val release: LatestRelease, val source: String)

    private val json = Json { ignoreUnknownKeys = true }

    /** @return 通道对应源的最新 latest；该源失败为 null（不做另一源兜底） */
    suspend fun checkLatest(channel: UpdateChannel): CheckResult? = withContext(Dispatchers.IO) {
        val bust = "t=${System.currentTimeMillis()}"
        when (channel) {
            UpdateChannel.STABLE ->
                fetchJson("${BuildConfig.UPDATE_LATEST_GITEE}?$bust")?.let { CheckResult(it, "gitee") }
            UpdateChannel.DEV ->
                fetchJson("${BuildConfig.UPDATE_LATEST_GITHUB}?$bust")?.let { CheckResult(it, "github") }
        }
    }

    private fun fetchJson(urlStr: String): LatestRelease? = runCatching {
        var url = urlStr
        repeat(5) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "NotiCleaner/${BuildConfig.VERSION_NAME}")
            try {
                when (conn.responseCode) {
                    in 200..299 -> {
                        // 剥离可能的 UTF-8 BOM，kotlinx.serialization 不容忍 BOM
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                            .trimStart('\uFEFF')
                        return json.decodeFromString<LatestRelease>(body)
                    }
                    in 300..399 -> {
                        val loc = conn.getHeaderField("Location") ?: return null
                        url = loc
                    }
                    else -> {
                        android.util.Log.w("UpdateVM", "HTTP ${conn.responseCode} for $url")
                        return null
                    }
                }
            } finally {
                conn.disconnect()
            }
        }
        null
    }.onFailure { android.util.Log.w("UpdateVM", "fetch failed for $urlStr", it) }.getOrNull()
}
