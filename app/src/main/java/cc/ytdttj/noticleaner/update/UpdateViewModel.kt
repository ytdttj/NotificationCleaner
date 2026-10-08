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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内更新（UpgradePlan.md）：
 * 1.3.2 更新分流：稳定版 → Gitee（x.x.x 正式 Release）；Dev 版 → GitHub（x.x.x Dev N）。
 * 检查按所选通道单源获取（不再双源排序）；下载优先同一镜像，稳定版另一镜像作备选
 * （Dev 版仅 GitHub 有资产，不做备选）。
 * 注：latest.json 的 url 字段仅保留给旧版本客户端兜底，1.1.10+ 的候选地址由本端按镜像自行构造。
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
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    /** 更新通道（1.3.2）：默认稳定版；持久化于 DataStore（SettingsRepository） */
    private val _channel = MutableStateFlow(UpdateChannel.STABLE)
    val channel: StateFlow<UpdateChannel> = _channel

    /**
     * 2.2.0 Dev 4：通道是否已从 DataStore 读出。
     *
     * **修复「通知点击进 App 不弹更新窗」**：通道初值是 STABLE，而真实通道在 [init]
     * 里异步读 DataStore 才拿到。冷启动（进程被系统回收后从通知进入）时
     * `checkUpdate()` 往往在 DataStore 读出之前就跑完了，于是：
     *   Dev 通道用户 → 误用 STABLE 去查 Gitee 正式版 → versionCode(72) ≤ 当前(75)
     *   → 判为「已是最新」静默返回；而用户手动进设置再点检查时通道已就绪，
     *   查 Dev 通道的 GitHub latest-dev.json → 正确提示新版本。
     * 这正是「必须手动进设置才弹窗」的成因。
     *
     * 修法：[checkUpdate] 内先 `awaitChannel()` 再取通道，消灭竞态。
     */
    private val _channelLoaded = MutableStateFlow(false)
    val channelLoaded: StateFlow<Boolean> = _channelLoaded

    private var downloadJob: Job? = null

    /** 上次检查成功的来源（gitee/github），下载优先使用同一镜像（1.1.10） */
    @Volatile
    private var lastCheckSource: String = "gitee"

    init {
        viewModelScope.launch {
            val saved = runCatching {
                cc.ytdttj.noticleaner.ServiceLocator.settings.updateChannel.first()
            }.getOrNull()
            _channel.value = runCatching { UpdateChannel.valueOf(saved ?: "STABLE") }
                .getOrDefault(UpdateChannel.STABLE)
            _channelLoaded.value = true
        }
    }

    /** 等待通道读出（最多 3s 兜底，防止 DataStore 异常时永久挂起检查） */
    private suspend fun awaitChannel(): UpdateChannel {
        withTimeoutOrNull(3_000) { _channelLoaded.first { it } }
        return _channel.value
    }

    /** 切换更新通道：立即生效并持久化 */
    fun setChannel(c: UpdateChannel) {
        _channel.value = c
        viewModelScope.launch {
            runCatching { cc.ytdttj.noticleaner.ServiceLocator.settings.setUpdateChannel(c.name) }
        }
    }

    fun reset() {
        _state.value = UpdateState.Idle
    }

    /** 检查更新（按所选通道单源获取；检查逻辑在 [UpdateChecker]，与后台 Worker 共用）。
     *  island 分支：包名非正式版时短路——latest.json 通道只指正式版（island 版与正式版并存，装正式版 APK 不会升级而是多装一个） */
    fun checkUpdate() {
        if (BuildConfig.APPLICATION_ID != "cc.ytdttj.noticleaner") {
            _state.value = UpdateState.Error("Island 版不参与应用内更新，请手动更新（跟随 main 分支版本号）")
            return
        }
        if (_state.value is UpdateState.Checking) return
        downloadJob?.cancel()
        _state.value = UpdateState.Checking
        viewModelScope.launch {
            // 2.2.0 Dev 4：必须等通道从 DataStore 读出再查，否则会用默认 STABLE 误判
            val ch = awaitChannel()
            val result = UpdateChecker.checkLatest(ch)
            _state.value = when {
                result == null -> UpdateState.Error("检查失败：无法访问${if (ch == UpdateChannel.STABLE) " Gitee" else " GitHub"} 更新源")
                result.release.versionCode > BuildConfig.VERSION_CODE -> {
                    lastCheckSource = result.source
                    // Dev 9：更新检查结果落环形日志（UPDATE 模块，覆盖 24 小时）
                    cc.ytdttj.noticleaner.diagnostics.RingLog.log(
                        cc.ytdttj.noticleaner.diagnostics.LogModules.UPDATE,
                        "发现新版本 ${result.release.versionName} (vc${result.release.versionCode}) " +
                            "来源=${result.source} 通道=$ch 当前=vc${BuildConfig.VERSION_CODE}",
                    )
                    UpdateState.Available(result.release)
                }
                else -> {
                    cc.ytdttj.noticleaner.diagnostics.RingLog.log(
                        cc.ytdttj.noticleaner.diagnostics.LogModules.UPDATE,
                        "已是最新：通道=$ch 远端=vc${result.release.versionCode} " +
                            "当前=vc${BuildConfig.VERSION_CODE}",
                    )
                    UpdateState.UpToDate
                }
            }
        }
    }

    /** 下载 APK：稳定版优先检查成功的镜像、另一镜像备选；Dev 版仅 GitHub（模板化 URL） */
    fun startDownload(release: LatestRelease) {
        downloadJob?.cancel()
        val appCtx = cc.ytdttj.noticleaner.ServiceLocator.appContext
        // 版本号去空格作为 tag/文件名（Dev 版 "1.3.2 Dev 1" → "1.3.2Dev1"，git tag 与附件名不允许空格）
        val tag = "v" + release.versionName.replace(" ", "")
        val apkName = "NotiCleaner-${release.versionName.replace(" ", "")}.apk"
        val giteeUrl = BuildConfig.UPDATE_APK_GITEE + "/" + tag + "/" + apkName
        val githubUrl = BuildConfig.UPDATE_APK_GITHUB + "/" + tag + "/" + apkName
        // 通道在这里读是安全的：Available 状态只可能由 checkUpdate 产出，而它已 awaitChannel()
        // 等待通道就绪（Dev 4 修复），不存在"用默认 STABLE 下载 Dev 包"的竞态。
        val candidates = when (_channel.value) {
            UpdateChannel.DEV -> listOf("GitHub" to githubUrl)
            UpdateChannel.STABLE ->
                if (lastCheckSource == "gitee") {
                    listOf("Gitee" to giteeUrl, "GitHub" to githubUrl)
                } else {
                    listOf("GitHub" to githubUrl, "Gitee" to giteeUrl)
                }
        }
        _state.value = UpdateState.Downloading(release, 0)
        downloadJob = viewModelScope.launch {
            val errors = mutableListOf<String>()
            for ((name, url) in candidates) {
                val dest = File(appCtx.getExternalFilesDir(null), "update/$apkName")
                val err = withContext(Dispatchers.IO) { directDownload(url, dest, release) }
                if (err == null) {
                    cc.ytdttj.noticleaner.diagnostics.RingLog.log(
                        cc.ytdttj.noticleaner.diagnostics.LogModules.UPDATE,
                        "APK 下载完成 $name → ${release.versionName}",
                    )
                    _state.value = UpdateState.ReadyToInstall(release, dest)
                    return@launch
                }
                cc.ytdttj.noticleaner.diagnostics.RingLog.log(
                    cc.ytdttj.noticleaner.diagnostics.LogModules.UPDATE,
                    "✗ APK 下载失败 [$name] $err",
                )
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
                        // 2.0.1 Dev 10（P1-6）：fail-closed——更新源未声明 sha256 一律拒绝安装。
                        // 旧实现 `if (!expected.isNullOrBlank() && actual != expected)`，
                        // 即 latest*.json 不带 sha256 字段时**完全不校验**就把 APK 交给安装器，
                        // 最后一道完整性防线形同虚设（中间人只要删掉该字段即可）。
                        val expected = release.sha256?.lowercase()
                        if (expected.isNullOrBlank()) {
                            return "更新源未声明 sha256，拒绝下载（完整性无法校验）"
                        }
                        if (actual != expected) {
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
