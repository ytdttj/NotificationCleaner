package cc.ytdttj.noticleaner.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.ytdttj.noticleaner.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 * 下载顺序 GitHub → Gitee（DownloadManager，前一个源失败自动切换下一个）。
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
    private var downloadId = -1L

    fun reset() {
        _state.value = UpdateState.Idle
    }

    /** 手动检查更新：GitHub → Gitee 依次请求 latest.json */
    fun checkUpdate() {
        if (_state.value is UpdateState.Checking) return
        downloadJob?.cancel()
        _state.value = UpdateState.Checking
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                fetchJson(BuildConfig.UPDATE_LATEST_GITHUB) ?: fetchJson(BuildConfig.UPDATE_LATEST_GITEE)
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
            add(BuildConfig.UPDATE_APK_GITHUB + "/v" + release.versionName + "/" + apkName)
            add(BuildConfig.UPDATE_APK_GITEE + "/v" + release.versionName + "/" + apkName)
            if (release.url.isNotBlank() && release.url.startsWith("http")) add(release.url)
        }
        _state.value = UpdateState.Downloading(release, 0)
        downloadJob = viewModelScope.launch {
            val dm = appCtx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            for (url in candidates) {
                val id = enqueue(dm, url, apkName) ?: continue
                downloadId = id
                val ok = awaitDownload(dm, id)
                if (ok) {
                    val file = File(appCtx.getExternalFilesDir(null), "update/$apkName")
                    if (file.exists() && file.length() > 0) {
                        _state.value = UpdateState.ReadyToInstall(release, file)
                        return@launch
                    }
                }
                // 该源失败：删除残留记录，切换下一个候选
                runCatching { dm.remove(id) }
                File(appCtx.getExternalFilesDir(null), "update/$apkName").delete()
            }
            _state.value = UpdateState.Error("下载失败：GitHub 与 Gitee 均不可用")
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
        val ctx = cc.ytdttj.noticleaner.ServiceLocator.appContext
        if (downloadId > 0) {
            runCatching {
                (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(downloadId)
            }
            downloadId = -1L
        }
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

    private fun enqueue(dm: DownloadManager, url: String, apkName: String): Long? = runCatching {
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle("NotiCleaner 更新包")
            .setDescription("正在下载 $apkName")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
            .setDestinationInExternalFilesDir(
                cc.ytdttj.noticleaner.ServiceLocator.appContext, null, "update/$apkName",
            )
        dm.enqueue(req)
    }.onFailure { android.util.Log.w("UpdateVM", "enqueue failed for $url", it) }.getOrNull()

    /** 轮询下载状态直到完成/失败/取消；@return 是否成功 */
    private suspend fun awaitDownload(dm: DownloadManager, id: Long): Boolean {
        val query = DownloadManager.Query().setFilterById(id)
        while (kotlin.coroutines.coroutineContext.isActive) {
            dm.query(query).use { cursor ->
                if (cursor.moveToFirst()) {
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> return true
                        DownloadManager.STATUS_FAILED -> return false
                        else -> {
                            val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                            val done = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                            if (total > 0) {
                                val s = _state.value
                                if (s is UpdateState.Downloading) {
                                    _state.value = s.copy(progress = (done * 100 / total).toInt())
                                }
                            }
                        }
                    }
                } else return false
            }
            delay(500)
        }
        return false
    }

    override fun onCleared() {
        downloadJob?.cancel()
    }
}
