package cc.ytdttj.noticleaner.update

import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.ytdttj.noticleaner.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内更新（UpgradePlan.md）：
 * 检查顺序 GitHub → Gitee（首个成功的 latest.json 生效）；
 * 下载顺序 GitHub → Gitee（应用内直连，1.1.9 起不再用系统 DownloadManager——
 * 其 UA/网络栈在部分 ROM 上会被 CDN 拦截或污染导致 sha256 校验失败）。
 * latest.json: {versionCode, versionName, notes, url?, sha256?}
 */
@Serializable
data class LatestRelease(
    val versionCode: Int,
    val versionName: String,
    val notes: String = "",
    val url: String = "",
    val sha256: String? = null,
)

sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data object UpToDate : UpdateState()
    data class Available(val release: LatestRelease) : UpdateState()
    data class Downloading(val release: LatestRelease, val progress: Int) : UpdateState()
    data class ReadyToInstall(val release: LatestRelease, val file: File) : UpdateState()
    data class Error(val message: String) : UpdateState()
}

class UpdateViewModel : ViewModel() {
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    private var downloadJob: Job? = null

    fun reset() {
        _state.value = UpdateState.Idle
    }

    /** 手动/入口检查更新：GitHub → Gitee 依次请求 latest.json（带时间戳穿透 CDN 缓存） */
    fun checkUpdate() {
        if (_state.value is UpdateState.Checking) return
        downloadJob?.cancel()
        _state.value = UpdateState.Checking
        viewModelScope.launch {
            val bust = "t=${System.currentTimeMillis()}"
            val result = withContext(Dispatchers.IO) {
                fetchJson("${BuildConfig.UPDATE_LATEST_GITHUB}?$bust")
                    ?: fetchJson("${BuildConfig.UPDATE_LATEST_GITEE}?$bust")
            }
            _state.value = when {
                result == null -> UpdateState.Error("检查失败：无法访问 GitHub 与 Gitee")
                result.versionCode > BuildConfig.VERSION_CODE -> UpdateState.Available(result)
                else -> UpdateState.UpToDate
            }
        }
    }

    /** 下载 APK：GitHub → Gitee 的 Release 附件依次尝试（latest.json 的 url 作为最后兜底） */
    fun startDownload(release: LatestRelease) {
        downloadJob?.cancel()
        val appCtx = cc.ytdttj.noticleaner.ServiceLocator.appContext
        val apkName = "NotiCleaner-${release.versionName}.apk"
        val candidates = buildList {
            add("GitHub" to (BuildConfig.UPDATE_APK_GITHUB + "/v" + release.versionName + "/" + apkName))
            add("Gitee" to (BuildConfig.UPDATE_APK_GITEE + "/v" + release.versionName + "/" + apkName))
            if (release.url.isNotBlank() && release.url.startsWith("http")) add("兜底" to release.url)
        }
        _state.value = UpdateState.Downloading(release, 0)
        downloadJob = viewModelScope.launch {
            val errors = mutableListOf<String>()
            for ((name, url) in candidates) {
                val dest = File(appCtx.getExternalFilesDir(null), "update/$apkName")
                val err = withContext(Dispatchers.IO) { directDownload(url, dest, release) }
                if (err == null) {
                    _state.value = UpdateState.ReadyToInstall(release, dest)
                    return@launch
                }
                android.util.Log.w("UpdateVM", "download failed [$name] $url: $err")
                errors.add("$name $err")
                dest.delete()
                File(dest.parentFile, dest.name + ".tmp").delete()
            }
            _state.value = UpdateState.Error("下载失败：所有更新源均不可用（${errors.joinToString("；")}）")
        }
    }

    /** 安装：未授权"安装未知应用"则先跳转授权页；已授权则直接拉起安装器 */
    fun install(release: LatestRelease, file: File) {
        val ctx = cc.ytdttj.noticleaner.ServiceLocator.appContext
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        _state.value = UpdateState.Idle
    }

    // ---- 内部实现 ----

    /**
     * 拉取 latest.json：
     * - 显式 UA（部分 CDN 拒绝 Dalvik 默认 UA）
     * - 手动跟随 3xx（跨域重定向不依赖系统实现）
     * - 失败原因写入 logcat（tag UpdateVM）
     */
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

    /**
     * 应用内直连下载（1.1.9，替代系统 DownloadManager）：
     * - 与 fetchJson 同一网络通道（手机端已验证可用）
     * - 显式 UA + 手动跟随重定向；分块写临时文件并汇报进度
     * - 完成后 sha256 校验；@return null=成功，否则失败原因（写入错误提示）
     */
    private suspend fun directDownload(urlStr: String, dest: File, release: LatestRelease): String? {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        var url = urlStr
        repeat(5) {
            var conn: HttpURLConnection? = null
            try {
                conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "NotiCleaner/${BuildConfig.VERSION_NAME}")
                when (conn.responseCode) {
                    in 200..299 -> {
                        val total = conn.contentLengthLong
                        tmp.parentFile?.mkdirs()
                        tmp.delete()
                        val md = java.security.MessageDigest.getInstance("SHA-256")
                        conn.inputStream.use { input ->
                            tmp.outputStream().use { out ->
                                val buf = ByteArray(1 shl 16)
                                var done = 0L
                                while (true) {
                                    if (!kotlin.coroutines.coroutineContext.isActive) return "已取消"
                                    val n = input.read(buf)
                                    if (n <= 0) break
                                    out.write(buf, 0, n)
                                    md.update(buf, 0, n)
                                    done += n
                                    if (total > 0) {
                                        val s = _state.value
                                        if (s is UpdateState.Downloading) {
                                            _state.value = s.copy(progress = (done * 100 / total).toInt())
                                        }
                                    }
                                }
                            }
                        }
                        val actual = md.digest().joinToString("") { "%02x".format(it) }
                        val expected = release.sha256?.lowercase()
                        if (!expected.isNullOrBlank() && actual != expected) {
                            return "sha256 不匹配（响应被篡改或 CDN 污染）"
                        }
                        if (dest.exists()) dest.delete()
                        return if (tmp.renameTo(dest)) null else "写入失败"
                    }
                    in 300..399 -> {
                        val loc = conn.getHeaderField("Location")
                            ?: return "重定向缺少 Location"
                        url = loc
                    }
                    else -> return "HTTP ${conn.responseCode}"
                }
            } catch (e: Exception) {
                return if (!kotlin.coroutines.coroutineContext.isActive) "已取消" else (e.message ?: e.toString())
            } finally {
                conn?.disconnect()
            }
        }
        return "重定向次数过多"
    }

    override fun onCleared() {
        downloadJob?.cancel()
    }
}
