package cc.ytdttj.noticleaner.update

import android.os.Build
import cc.ytdttj.noticleaner.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * 更新检查（1.2.2 自 UpdateViewModel 抽出，供应用内检查与后台 WorkManager 共用）：
 * 检查顺序 Gitee → GitHub（国内可达性优先；首个成功的 latest.json 生效）。
 * 显式 UA + 手动跟随 3xx 重定向 + 剥离 BOM（与 1.1.4 网络栈语义一致）。
 */
object UpdateChecker {

    data class CheckResult(val release: LatestRelease, val source: String)

    private val json = Json { ignoreUnknownKeys = true }

    /** @return 首个成功源的 latest；双源均失败为 null */
    suspend fun checkLatest(): CheckResult? = withContext(Dispatchers.IO) {
        val bust = "t=${System.currentTimeMillis()}"
        fetchJson("${BuildConfig.UPDATE_LATEST_GITEE}?$bust")?.let { CheckResult(it, "gitee") }
            ?: fetchJson("${BuildConfig.UPDATE_LATEST_GITHUB}?$bust")?.let { CheckResult(it, "github") }
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
