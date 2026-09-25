package cc.ytdttj.noticleaner.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cc.ytdttj.noticleaner.ServiceLocator
import cc.ytdttj.noticleaner.data.SettingsRepository
import cc.ytdttj.noticleaner.data.db.NotificationDao
import cc.ytdttj.noticleaner.notify.KeepAliveStatus
import cc.ytdttj.noticleaner.notify.RootExecutor
import cc.ytdttj.noticleaner.notify.ShizukuExecutor
import cc.ytdttj.noticleaner.notify.runKeepAliveCommands
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val dao: NotificationDao,
) : ViewModel() {
    val threshold = settings.threshold.stateIn(viewModelScope, SharingStarted.Eagerly, 0.8f)
    val interceptMode = settings.interceptMode.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val excludeFromRecents = settings.excludeFromRecents.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val filteredCount = dao.filteredCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val learnedCount = dao.learnedCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    // ---- 超级岛（island 分支功能；Dev 5 重构：LSPosed only）----
    val islandEnabled = settings.islandEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val islandPackages = settings.islandPackages.stateIn(viewModelScope, SharingStarted.Eagerly, cc.ytdttj.noticleaner.notify.island.IslandNotifier.DEFAULT_PACKAGES)

    /** 岛探测状态（实时刷新：保活动作完成后自动重新探测） */
    private val _islandProbe = MutableStateFlow("探测系统支持中…")
    val islandProbe: StateFlow<String> = _islandProbe

    /** 通知模拟解锁（设置 tab 快速点击 5 次，1.3.0 beta2） */
    val simUnlocked = settings.simUnlocked.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // ---- 界面风格（1.4.0 Dev 4）：Material 3 / 液态玻璃 ----
    val uiTheme = settings.uiTheme.stateIn(viewModelScope, SharingStarted.Eagerly, cc.ytdttj.noticleaner.ui.UiTheme.MATERIAL.name)

    fun setUiTheme(mode: cc.ytdttj.noticleaner.ui.UiTheme) {
        viewModelScope.launch { settings.setUiTheme(mode.name) }
    }

    // ---- 玻璃清晰度（1.4.0 Dev 5）：磨砂 / 柔光 ----
    val glassStyle = settings.glassStyle.stateIn(viewModelScope, SharingStarted.Eagerly, cc.ytdttj.noticleaner.ui.GlassStyle.FROSTED.name)

    fun setGlassStyle(style: cc.ytdttj.noticleaner.ui.GlassStyle) {
        viewModelScope.launch { settings.setGlassStyle(style.name) }
    }

    private val _keepAlive = MutableStateFlow(KeepAliveStatus())
    val keepAlive: StateFlow<KeepAliveStatus> = _keepAlive

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast

    /** 高级保活命令执行结果（弹窗展示） */
    private val _execResult = MutableStateFlow<String?>(null)
    val execResult: StateFlow<String?> = _execResult

    private val _execBusy = MutableStateFlow(false)
    val execBusy: StateFlow<Boolean> = _execBusy

    /**
     * 模型信息：跟随 Room 学习计数实时刷新。
     * （修复：此前在 init 一次性读取 learn_count 文件，底部导航 restoreState 保留 VM，
     *   学完通知返回设置页仍显示旧值 0。）
     */
    val modelInfo: StateFlow<String> = dao.learnedCount().map { n ->
        "已学习样本：$n 条（NSPM v2 基线 + 端上学习修正）"
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "已学习样本：0 条（NSPM v2 基线 + 端上学习修正）")

    init {
        refreshKeepAlive()
        // Dev 7：LSPosed 框架服务绑定/作用域变化（含授权框批准后）自动刷新保活与岛探测
        viewModelScope.launch {
            cc.ytdttj.noticleaner.keepalive.LspServiceDetector.state.collect {
                refreshKeepAlive()
            }
        }
    }

    fun refreshKeepAlive() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val shizukuOk = runCatching {
                rikka.shizuku.Shizuku.pingBinder() &&
                    rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            _keepAlive.value = ServiceLocator.keepAlive.status(shizukuOk)
            // 保活状态变化后同步刷新岛探测（Shizuku 授权/白名单状态实时反映，1.3.0 beta2）
            probeIsland()
        }
    }

    /** 探测超级岛支持情况（OS 版本 + 白名单 hook 状态 + Shizuku），结果写入 islandProbe */
    fun probeIsland() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val ctx = ServiceLocator.appContext
            val protocol = runCatching {
                android.provider.Settings.System.getInt(ctx.contentResolver, "notification_focus_protocol", 0)
            }.getOrDefault(0)
            val shizukuOk = runCatching {
                rikka.shizuku.Shizuku.pingBinder() &&
                    rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            // 本地白名单状态：LSPosed hook 生效后为 true（官方 Q&A 的 canShowFocus 查询）
            val canFocus = runCatching {
                val extras = android.os.Bundle().apply { putString("package", ctx.packageName) }
                ctx.contentResolver.call(
                    android.net.Uri.parse("content://miui.statusbar.notification.public"),
                    "canShowFocus", null, extras,
                )?.getBoolean("canShowFocus", false)
            }.getOrNull()
            val lsp = ServiceLocator.keepAlive.isLspActive()
            val lspService = cc.ytdttj.noticleaner.keepalive.LspServiceDetector.state.value
            val islandScopeReady = lspService.bound && lspService.hasAllScope(
                setOf(
                    cc.ytdttj.noticleaner.keepalive.LspServiceDetector.SCOPE_SYSTEM_UI,
                    cc.ytdttj.noticleaner.keepalive.LspServiceDetector.SCOPE_XMSF,
                ),
            )
            val osLine = when {
                protocol >= 3 -> "系统：HyperOS 3 超级岛"
                protocol == 2 -> "系统：焦点通知（OS2），无岛形态"
                else -> "系统：不支持焦点通知/超级岛"
            }
            val hookLine = when (canFocus) {
                true -> "白名单：已放行（hook 生效）"
                false -> "白名单：未放行（LSPosed 未激活或未勾选系统界面作用域）"
                null -> "白名单：无法查询"
            }
            val lspLine = when {
                lspService.bound && islandScopeReady -> "LSPosed：模块已激活（岛作用域已就绪）"
                lspService.bound -> "LSPosed：模块已激活（岛作用域未授权，打开岛开关可授权）"
                lsp == true -> "LSPosed：模块已激活（system_server 心跳）"
                // Dev 5：检测不到证据 ≠ 未激活（原实现误报），如实显示"无法自动检测"
                else -> "LSPosed：无法自动检测（以 LSPosed 管理器为准）"
            }
            val shizukuLine = if (shizukuOk) "Shizuku：已授权" else "Shizuku：未授权"
            _islandProbe.value = listOf(osLine, hookLine, lspLine, shizukuLine).joinToString("\n")
        }
    }

    fun setThreshold(v: Float) {
        viewModelScope.launch { settings.setThreshold(v) }
    }

    fun setInterceptMode(v: Boolean) {
        viewModelScope.launch { settings.setInterceptMode(v) }
    }

    fun setExcludeFromRecents(v: Boolean) {
        viewModelScope.launch { settings.setExcludeFromRecents(v) }
    }

    // ---- 超级岛（island 分支功能）----

    fun setIslandEnabled(v: Boolean) {
        val was = islandEnabled.value
        viewModelScope.launch { settings.setIslandEnabled(v) }
        // Dev 7：首次打开岛开关 → 弹 LSPosed 授权框，请求岛作用域（系统界面 + 小米服务框架）
        if (v && !was) {
            cc.ytdttj.noticleaner.keepalive.LspServiceDetector.requestScope(
                listOf(
                    cc.ytdttj.noticleaner.keepalive.LspServiceDetector.SCOPE_SYSTEM_UI,
                    cc.ytdttj.noticleaner.keepalive.LspServiceDetector.SCOPE_XMSF,
                ),
            ) { result ->
                _toast.value = result.fold(
                    onSuccess = { scope ->
                        val ok = scope.containsAll(
                            listOf(
                                cc.ytdttj.noticleaner.keepalive.LspServiceDetector.SCOPE_SYSTEM_UI,
                                cc.ytdttj.noticleaner.keepalive.LspServiceDetector.SCOPE_XMSF,
                            ),
                        )
                        if (ok) "岛作用域已授权：请到 高级功能 点击「重启岛作用域」让 hook 立即生效"
                        else "岛作用域部分授权，可在 LSPosed 管理器补齐后重启作用域"
                    },
                    onFailure = { "岛作用域授权失败：${it.message}（也可在 LSPosed 管理器手动勾选）" },
                )
            }
        }
    }

    // ---- LSPosed 框架服务状态（Dev 7：libxposed service 绑定 + 作用域）----
    val lspServiceState = cc.ytdttj.noticleaner.keepalive.LspServiceDetector.state

    /** 保活：请求系统框架（android）作用域（system_server 保活/拦截 hook） */
    fun requestKeepAliveScope() {
        cc.ytdttj.noticleaner.keepalive.LspServiceDetector.requestScope(
            listOf(cc.ytdttj.noticleaner.keepalive.LspServiceDetector.SCOPE_SYSTEM_SERVER),
        ) { result ->
            _toast.value = result.fold(
                onSuccess = { "系统框架作用域已授权：请重启手机使保活 hook 生效" },
                onFailure = { "授权失败：${it.message}" },
            )
        }
    }

    // ---- 历史通知（Dev 6：保留天数可调）----
    val historyRetentionDays = settings.historyRetentionDays.stateIn(viewModelScope, SharingStarted.Eagerly, 7)

    fun setHistoryRetentionDays(v: Int) {
        viewModelScope.launch { settings.setHistoryRetentionDays(v) }
    }

    fun toggleIslandPackage(pkg: String) {
        viewModelScope.launch {
            val current = islandPackages.value
            val next = if (pkg in current) current - pkg else current + pkg
            settings.setIslandPackages(next)
        }
    }

    /** 发送测试岛通知（走完整 LSPosed 链路） */
    fun sendTestIsland() {
        cc.ytdttj.noticleaner.notify.island.IslandNotifier.sendTest(ServiceLocator.appContext) { msg ->
            _toast.value = msg
        }
    }

    // ---- 通知模拟（island 测试）：Shell 身份发通知，tag 携带模拟包名 ----

    private val _simulateBusy = MutableStateFlow(false)
    val simulateBusy: StateFlow<Boolean> = _simulateBusy

    fun simulateNotification(pkg: String, title: String, content: String) {
        if (_simulateBusy.value) return
        if (title.isBlank() && content.isBlank()) {
            _toast.value = "标题和内容不能同时为空"
            return
        }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _simulateBusy.value = true
            val island = cc.ytdttj.noticleaner.notify.island.IslandNotifier
            val trace = cc.ytdttj.noticleaner.notify.island.IslandTrace
            val cmd = buildString {
                append("cmd notification post")
                if (title.isNotBlank()) append(" -t ").append(shellQuote(title))
                append(" ").append(shellQuote(island.SIM_TAG_PREFIX + pkg))
                if (content.isNotBlank()) append(" ").append(shellQuote(content))
            }
            trace.log("模拟发送开始: 来源=${island.PACKAGE_LABELS[pkg] ?: pkg} cmd=$cmd")
            val label = island.PACKAGE_LABELS[pkg] ?: pkg

            // 通道1：Shizuku（cmd post 成功也输出 "posting for user 0: ..."，不能以空判成败）
            val shizukuOut = runCatching { ShizukuExecutor.exec(cmd) }.getOrElse { "通道异常: $it" }
            var ok = isCmdOutputOk(shizukuOut)
            var via = "Shizuku"
            var out = shizukuOut
            trace.log("Shizuku 通道: ok=$ok 输出=${shizukuOut.take(200)}")

            // 通道2：Root 回退
            if (!ok) {
                val rootOut = runCatching { RootExecutor.exec(cmd) }.getOrElse { "通道异常: $it" }
                ok = isCmdOutputOk(rootOut)
                via = "Root"
                out = rootOut
                trace.log("Root 回退通道: ok=$ok 输出=${rootOut.take(200)}")
            }

            _simulateBusy.value = false
            _toast.value = if (ok) "模拟通知已发送（来源模拟为 $label，经 $via）" else "发送失败（$via）: ${out.take(120)}"
        }
    }

    /** cmd notification post 成功时输出 "posting for user 0: ..."（非空）；失败含 Exception/Error */
    private fun isCmdOutputOk(out: String): Boolean {
        val o = out.trim()
        if (o.isEmpty()) return true
        if (o.startsWith("通道异常")) return false
        return !o.contains("Exception", ignoreCase = true) && !o.contains("Error", ignoreCase = true)
    }

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** 管线直接注入（主模拟路径，绕开 shell 通知不投递给监听器的限制） */
    fun simulateDirect(pkg: String, title: String, content: String) {
        if (_simulateBusy.value) return
        if (title.isBlank() && content.isBlank()) {
            _toast.value = "标题和内容不能同时为空"
            return
        }
        _simulateBusy.value = true
        _toast.value = "5 秒后发送，请立刻回到桌面（App 在前台时系统不渲染岛）"
        cc.ytdttj.noticleaner.notify.island.IslandNotifier.postSimulated(
            ServiceLocator.appContext, pkg, title, content,
        ) { msg ->
            _simulateBusy.value = false
            _toast.value = msg
        }
    }

    fun requestIgnoreBattery() {
        ServiceLocator.keepAlive.requestIgnoreBatteryOptimization()
    }

    fun openListenerSettings() {
        ServiceLocator.keepAlive.openListenerSettings()
    }

    /** 1.2.1：手动修复通知监听（摘除→写回强制重绑；Shizuku 优先，Root 兜底） */
    fun repairListener() {
        if (_execBusy.value) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _execBusy.value = true
            val executor = when {
                runCatching {
                    rikka.shizuku.Shizuku.pingBinder() &&
                        rikka.shizuku.Shizuku.checkSelfPermission() ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                }.getOrDefault(false) -> ShizukuExecutor
                ServiceLocator.keepAlive.isRootAvailable() -> RootExecutor
                else -> {
                    _execBusy.value = false
                    _toast.value = "需要 Shizuku（已授权）或 Root 才能强制修复；普通用户可直接开关一次通知监听权限"
                    return@launch
                }
            }
            _execResult.value = runCatching {
                cc.ytdttj.noticleaner.notify.ListenerRepair.repair(executor)
            }.getOrElse { "修复失败: $it" }
            _execBusy.value = false
            refreshKeepAlive()
        }
    }

    /** 1.2.1：跳转无障碍设置（保活守护层） */
    fun openAccessibilitySettings() {
        runCatching {
            ServiceLocator.appContext.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun resetModel() {
        viewModelScope.launch {
            ServiceLocator.db.notificationDao().clearLearned()
            ServiceLocator.modelRepo.resetToBaseline()
            _toast.value = "已清除学习标注，模型重置为预训练基线"
        }
    }

    // ---- Shizuku / Root 高级保活（仅用户主动点击时授权/执行） ----

    /** 请求 Shizuku 授权；授权结果刷新状态 */
    fun requestShizuku() {
        runCatching {
            if (!rikka.shizuku.Shizuku.pingBinder()) {
                _toast.value = "Shizuku 未运行：请先安装并启动 Shizuku（adb 或 Root 激活）"
                return
            }
            rikka.shizuku.Shizuku.addRequestPermissionResultListener { _, grantResult ->
                refreshKeepAlive()
                if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) applyShizuku()
            }
            rikka.shizuku.Shizuku.requestPermission(0)
        }.onFailure { _toast.value = "Shizuku 不可用：$it" }
    }

    fun applyShizuku() {
        if (_execBusy.value) return
        viewModelScope.launch {
            _execBusy.value = true
            _execResult.value = runKeepAliveCommands(ServiceLocator.appContext, ShizukuExecutor) { }
            _execBusy.value = false
            refreshKeepAlive()
        }
    }

    fun applyRoot() {
        if (_execBusy.value) return
        viewModelScope.launch {
            _execBusy.value = true
            _execResult.value = runKeepAliveCommands(ServiceLocator.appContext, RootExecutor) { }
            _execBusy.value = false
            refreshKeepAlive()
        }
    }

    /**
     * 重启岛作用域进程（Dev 5，Root；命令参考 ref/HyperIsland RestartScopeDialog）：
     * - SystemUI：killall（persistent 进程对 force-stop 不响应），死后由 zygote 自动拉起；
     * - 小米服务框架：am force-stop，被小米推送自行重新拉起。
     * LSPosed hook 修改作用域/更新模块后，重启对应进程即可让 hook 生效，无需整机重启。
     */
    fun restartIslandScope() {
        if (_execBusy.value) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _execBusy.value = true
            val commands = listOf(
                "killall com.android.systemui",
                "am force-stop com.xiaomi.xmsf",
            )
            _execResult.value = buildString {
                for (cmd in commands) {
                    val out = runCatching { RootExecutor.exec(cmd) }.getOrElse { "执行失败: $it" }
                    append("$ ").appendLine(cmd)
                    append(if (out.isBlank()) "(无输出)" else out).appendLine().appendLine()
                }
                appendLine("两条命令执行完毕。SystemUI 与小米服务框架正在自动重启，")
                append("约 10–20 秒后锁屏/岛恢复即可测试上岛。")
            }.trim()
            _execBusy.value = false
            refreshKeepAlive()
        }
    }

    fun dismissExecResult() {
        _execResult.value = null
    }

    fun showToast(msg: String) {
        _toast.value = msg
    }

    fun clearToast() {
        _toast.value = null
    }
}

@Composable
fun SettingsScreen(onOpenStats: (String) -> Unit, vm: SettingsViewModel = viewModel(factory = settingsVmFactory())) {
    val threshold by vm.threshold.collectAsState()
    val intercept by vm.interceptMode.collectAsState()
    val excludeRecents by vm.excludeFromRecents.collectAsState()
    val filteredCount by vm.filteredCount.collectAsState()
    val learnedCount by vm.learnedCount.collectAsState()
    val keepAlive by vm.keepAlive.collectAsState()
    val lspServiceState by vm.lspServiceState.collectAsState()
    val modelInfo by vm.modelInfo.collectAsState()
    val execResult by vm.execResult.collectAsState()
    val execBusy by vm.execBusy.collectAsState()
    var thresholdInput by remember(threshold) { mutableStateOf("%.2f".format(threshold)) }
    val simUnlocked by vm.simUnlocked.collectAsState()
    val uiThemeMode by vm.uiTheme.collectAsState()
    val glassStyleMode by vm.glassStyle.collectAsState()
    val islandProbe by vm.islandProbe.collectAsState()
    val manufacturerHint = remember { ServiceLocator.keepAlive.manufacturerAutoStartHint() }

    // 多任务隐藏：切换后立即应用（API 29+ 直接设置任务标记，不重建任务）
    val context = LocalContext.current
    var prevExclude by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(excludeRecents) {
        if (prevExclude != null && prevExclude != excludeRecents) {
            (context as? cc.ytdttj.noticleaner.ui.MainActivity)?.let { act ->
                if (!act.applyExcludeFromRecents(excludeRecents)) {
                    vm.showToast("仅支持 Android 10 及以上系统")
                }
            }
        }
        prevExclude = excludeRecents
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // Dev 15：玻璃模式下悬浮底栏位于 Scaffold 之外，会盖住滚到最底部的功能块
            // → 内容底部让位一个底栏高度（非玻璃模式有 bottomBar，无需让位）
            .padding(bottom = if (cc.ytdttj.noticleaner.ui.LocalGlassMode.current) {
                cc.ytdttj.noticleaner.ui.glass.GlassFloatingBarClearance
            } else {
                0.dp
            })
            .padding(16.dp),
    ) {
        // ---- 界面风格切换（1.4.0 Dev 4）：液态玻璃 / Material 3 ----
        cc.ytdttj.noticleaner.ui.glass.NcCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("界面风格", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("液态玻璃或 Material 3；玻璃模式含壁纸折射、磨砂卡片与胶囊底栏", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = uiThemeMode == cc.ytdttj.noticleaner.ui.UiTheme.GLASS.name,
                    onCheckedChange = {
                        vm.setUiTheme(
                            if (it) cc.ytdttj.noticleaner.ui.UiTheme.GLASS else cc.ytdttj.noticleaner.ui.UiTheme.MATERIAL,
                        )
                    },
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- 玻璃清晰度（1.4.0 Dev 5）：仅玻璃主题下显示 ----
        if (uiThemeMode == cc.ytdttj.noticleaner.ui.UiTheme.GLASS.name) {
            cc.ytdttj.noticleaner.ui.glass.NcCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("玻璃清晰度", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("柔光玻璃近乎全透明；磨砂玻璃提供一定可读性", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        cc.ytdttj.noticleaner.ui.GlassStyle.entries.forEachIndexed { index, style ->
                            SegmentedButton(
                                selected = glassStyleMode == style.name,
                                onClick = { vm.setGlassStyle(style) },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = cc.ytdttj.noticleaner.ui.GlassStyle.entries.size),
                            ) { Text(style.label) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // ---- 拦截模式 ----
        cc.ytdttj.noticleaner.ui.glass.NcCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("拦截模式", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("关闭后仅标记不拦截，便于观察误杀（AI 仍打分并记录）", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = intercept, onCheckedChange = { vm.setInterceptMode(it) })
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- 过滤阈值 ----
        cc.ytdttj.noticleaner.ui.glass.NcCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("过滤阈值", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("AI 判定广告概率 ≥ 阈值时自动清除。范围 0.5~1.0，默认 0.8。", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    cc.ytdttj.noticleaner.ui.glass.NcOutlinedTextField(
                        value = thresholdInput,
                        onValueChange = { s ->
                            // 仅编辑本地输入，点击"保存"后才生效
                            thresholdInput = s
                        },
                        label = { Text("阈值 (0.5~1.0)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = {
                        thresholdInput = "0.80"
                        vm.setThreshold(0.8f)
                    }) { Text("恢复默认") }
                    Spacer(Modifier.width(8.dp))
                    val parsed = thresholdInput.toFloatOrNull()
                    val valid = parsed != null && parsed in 0.5f..1.0f && parsed != threshold
                    androidx.compose.material3.Button(
                        enabled = valid,
                        onClick = { parsed?.let { vm.setThreshold(it) } },
                    ) { Text("保存") }
                }
                if (thresholdInput.toFloatOrNull()?.let { it !in 0.5f..1.0f } == true) {
                    Text("请输入 0.5 ~ 1.0 之间的数值", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- 统计 ----
        cc.ytdttj.noticleaner.ui.glass.NcCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("统计", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenStats("filtered") }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("已过滤 ${filteredCount} 条通知", Modifier.weight(1f))
                    Text("查看明细 ›", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenStats("learned") }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("已学习 ${learnedCount} 条通知", Modifier.weight(1f))
                    Text("查看明细 ›", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- 多任务隐藏 ----
        cc.ytdttj.noticleaner.ui.glass.NcCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("在多任务界面隐藏", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "系统多任务界面不显示本 APP 的后台卡片，防止误滑删除（需 Android 10+，关闭后恢复显示）",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = excludeRecents, onCheckedChange = { vm.setExcludeFromRecents(it) })
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- 权限检查（1.3.0 beta2：原「后台保活」，高级项折叠） ----
        var permAdvancedOpen by remember { mutableStateOf(false) }
        cc.ytdttj.noticleaner.ui.glass.NcCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("权限检查", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                StatusRow("通知监听权限", keepAlive.listenerEnabled) { vm.openListenerSettings() }
                StatusRow("电池优化白名单", keepAlive.ignoringBattery) { vm.requestIgnoreBattery() }
                manufacturerHint?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    Spacer(Modifier.height(8.dp))
                }
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().clickable { permAdvancedOpen = !permAdvancedOpen },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "高级权限（可选）",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(if (permAdvancedOpen) "收起" else "展开", style = MaterialTheme.typography.bodySmall)
                }
                if (permAdvancedOpen) {
                    Spacer(Modifier.height(4.dp))
                    AdvancedRow(
                        label = "Shizuku 保活",
                        desc = "免 Root 写入电池优化白名单 / 通知监听权限",
                        ok = keepAlive.shizukuAvailable,
                        actionLabel = if (keepAlive.shizukuAvailable) "应用" else "授权",
                        onAction = { if (keepAlive.shizukuAvailable) vm.applyShizuku() else vm.requestShizuku() },
                    )
                    AdvancedRow(
                        label = "Root 保活",
                        desc = "以 Root 执行白名单与厂商自启动命令（最彻底）",
                        ok = keepAlive.rootAvailable,
                        actionLabel = "应用",
                        onAction = { if (keepAlive.rootAvailable) vm.applyRoot() else vm.showToast("未检测到 Root（su）") },
                    )
                    AdvancedRow(
                        label = "LSPosed 保活",
                        desc = "安装 LSPosed 并激活本模块（作用域勾选「系统(android)」）后自动生效，重启手机完成。" +
                            "未打勾 = 未检测到激活证据（框架服务/模块心跳），以 LSPosed 管理器为准",
                        ok = keepAlive.lspDetected == true,
                        actionLabel = if (lspServiceState.bound && !lspServiceState.hasScope(
                                cc.ytdttj.noticleaner.keepalive.LspServiceDetector.SCOPE_SYSTEM_SERVER,
                            )
                        ) "授权" else null,
                        onAction = {
                            if (lspServiceState.bound) vm.requestKeepAliveScope()
                            else vm.showToast("请先在 LSPosed 中启用本模块")
                        },
                    )
                    AdvancedRow(
                        label = "重启岛作用域",
                        desc = "以 Root 重启 系统界面 + 小米服务框架：更新模块或修改 LSPosed 作用域后让岛 hook 立即生效，无需整机重启",
                        ok = keepAlive.rootAvailable,
                        actionLabel = if (keepAlive.rootAvailable) "重启" else null,
                        onAction = {
                            if (keepAlive.rootAvailable) vm.restartIslandScope()
                            else vm.showToast("重启作用域需要 Root（su）")
                        },
                    )
                    AdvancedRow(
                        label = "无障碍保活",
                        desc = "开启「保活守护」无障碍服务：系统绑定的第二条生命线，不读取屏幕内容",
                        ok = keepAlive.accessibilityEnabled,
                        actionLabel = if (keepAlive.accessibilityEnabled) null else "去开启",
                        onAction = { vm.openAccessibilitySettings() },
                    )
                    AdvancedRow(
                        label = "修复通知监听",
                        desc = "监听断连且无法自愈时的强制修复（需 Shizuku 已授权或 Root）",
                        ok = keepAlive.listenerEnabled,
                        actionLabel = "修复",
                        onAction = { vm.repairListener() },
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))


        // ---- 检查更新 ----
        val updateVm: cc.ytdttj.noticleaner.update.UpdateViewModel =
            viewModel(key = "update", factory = viewModelFactory { initializer { cc.ytdttj.noticleaner.update.UpdateViewModel() } })
        val updateState by updateVm.state.collectAsState()
        val updateChannel by updateVm.channel.collectAsState()
        var channelMenuOpen by remember { mutableStateOf(false) }
        cc.ytdttj.noticleaner.ui.glass.NcCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("检查更新", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "当前版本 v${cc.ytdttj.noticleaner.BuildConfig.VERSION_NAME}（${updateChannel.label}通道：${if (updateChannel == cc.ytdttj.noticleaner.update.UpdateChannel.STABLE) "Gitee" else "GitHub"}）",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    cc.ytdttj.noticleaner.ui.glass.NcOutlinedButton(
                        enabled = updateState !is cc.ytdttj.noticleaner.update.UpdateState.Checking,
                        onClick = { updateVm.checkUpdate() },
                    ) { Text("检查") }
                }
                Spacer(Modifier.height(8.dp))
                // 1.3.2 更新分流：稳定版 → Gitee（x.x.x）；Dev 版 → GitHub（x.x.x Dev N），默认稳定版
                androidx.compose.foundation.layout.Box {
                    cc.ytdttj.noticleaner.ui.glass.NcOutlinedButton(
                        onClick = { channelMenuOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "更新通道：${updateChannel.label}",
                            modifier = Modifier.weight(1f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                        )
                        androidx.compose.material3.Icon(
                            Icons.Filled.ArrowDropDown,
                            contentDescription = "选择更新通道",
                        )
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = channelMenuOpen,
                        onDismissRequest = { channelMenuOpen = false },
                    ) {
                        cc.ytdttj.noticleaner.update.UpdateChannel.entries.forEach { c ->
                            DropdownMenuItem(
                                text = { Text(c.label + if (c == updateChannel) "（当前）" else "") },
                                onClick = {
                                    updateVm.setChannel(c)
                                    channelMenuOpen = false
                                },
                            )
                        }
                    }
                }
            }
        }
        // ---- 导出诊断日志（1.3.2 恢复：1.3.0 设置页重排时丢失）----
        // 内容 = 版本/权限/岛设置快照 + 岛链路 trace + logcat，经系统分享
        val diagContext = LocalContext.current
        val diagScope = rememberCoroutineScope()
        var diagExporting by remember { mutableStateOf(false) }
        var diagMsg by remember { mutableStateOf<String?>(null) }
        Spacer(Modifier.height(12.dp))
        cc.ytdttj.noticleaner.ui.glass.NcCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("导出诊断日志", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "版本/权限/岛链路状态快照 + logcat，经系统分享发送给开发者排查",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    cc.ytdttj.noticleaner.ui.glass.NcOutlinedButton(
                        enabled = !diagExporting,
                        onClick = {
                            diagExporting = true
                            diagScope.launch {
                                val msg = runCatching {
                                    val file = cc.ytdttj.noticleaner.diagnostics.DiagExporter.export(diagContext)
                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                        diagContext,
                                        "${diagContext.packageName}.fileprovider",
                                        file,
                                    )
                                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    diagContext.startActivity(
                                        android.content.Intent.createChooser(send, "分享诊断日志"),
                                    )
                                    "已导出: ${file.name}"
                                }.getOrElse { "导出失败: ${it.message}" }
                                diagExporting = false
                                diagMsg = msg
                            }
                        },
                    ) { Text(if (diagExporting) "导出中…" else "导出") }
                }
                diagMsg?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                // ---- 历史通知管理（Dev 6，折叠）----
                var historyPanelOpen by remember { mutableStateOf(false) }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().clickable { historyPanelOpen = !historyPanelOpen },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (historyPanelOpen) "▾ 历史通知管理" else "▸ 历史通知管理",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (historyPanelOpen) {
                    Spacer(Modifier.height(8.dp))
                    // CSV 导出
                    val csvScope = rememberCoroutineScope()
                    var csvExporting by remember { mutableStateOf(false) }
                    var csvMsg by remember { mutableStateOf<String?>(null) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("导出历史通知 CSV", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "全部历史通知（应用/包名/通道/标题/正文/AI率/学习状态），经系统分享",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        cc.ytdttj.noticleaner.ui.glass.NcOutlinedButton(
                            enabled = !csvExporting,
                            onClick = {
                                csvExporting = true
                                csvScope.launch {
                                    val msg = runCatching {
                                        val file = cc.ytdttj.noticleaner.diagnostics.HistoryCsvExporter.export(diagContext)
                                        val uri = androidx.core.content.FileProvider.getUriForFile(
                                            diagContext,
                                            "${diagContext.packageName}.fileprovider",
                                            file,
                                        )
                                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                            type = "text/csv"
                                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        diagContext.startActivity(
                                            android.content.Intent.createChooser(send, "分享历史通知 CSV"),
                                        )
                                        "已导出 ${file.name}（${file.length() / 1024}KB）"
                                    }.getOrElse { "导出失败: ${it.message}" }
                                    csvExporting = false
                                    csvMsg = msg
                                }
                            },
                        ) { Text(if (csvExporting) "导出中…" else "导出") }
                    }
                    csvMsg?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                    Spacer(Modifier.height(12.dp))
                    // 保留天数（监控式循环：最新的顶掉 N 天前的）
                    val historyRetentionDays by vm.historyRetentionDays.collectAsState()
                    var retentionDraft by remember(historyRetentionDays) { mutableStateOf(historyRetentionDays) }
                    Column(Modifier.fillMaxWidth()) {
                        Text("历史保留天数：${retentionDraft} 天", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "未学习的历史通知只保留 N 天，最新通知不断把最老的顶掉（监控式循环保存）；已学习的标注不受影响",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        androidx.compose.material3.Slider(
                            value = retentionDraft.toFloat(),
                            onValueChange = { retentionDraft = it.toInt().coerceIn(1, 30) },
                            onValueChangeFinished = { vm.setHistoryRetentionDays(retentionDraft) },
                            valueRange = 1f..30f,
                            steps = 28,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        // ---- 高级功能（1.3.0 beta2：默认折叠） ----
        var advancedOpen by remember { mutableStateOf(false) }
        var confirmResetModel by remember { mutableStateOf(false) }
        cc.ytdttj.noticleaner.ui.glass.NcCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth().clickable { advancedOpen = !advancedOpen },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "高级功能",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(if (advancedOpen) "收起" else "展开", style = MaterialTheme.typography.bodySmall)
                }
                if (advancedOpen) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
        // ---- 超级岛支付提醒（island 分支实验功能；Dev 5 重构：LSPosed only） ----
        val islandEnabled by vm.islandEnabled.collectAsState()
        val islandPackages by vm.islandPackages.collectAsState()
        var showDiag by remember { mutableStateOf(false) }
        cc.ytdttj.noticleaner.ui.glass.NcCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("超级岛支付提醒（实验）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "银行/支付类 App 的收支通知自动上岛：摘要态显示来源图标与金额，展开显示详情。" +
                        "认证放行依赖 LSPosed 模块（需在 LSPosed 中启用本模块并勾选" +
                        "系统界面 + 小米服务框架作用域），失败自动退化为普通通知。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(islandProbe, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                    Switch(checked = islandEnabled, onCheckedChange = { vm.setIslandEnabled(it) })
                }
                Text("上岛应用（勾选后，该应用含金额的收支通知才会上岛）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                cc.ytdttj.noticleaner.notify.island.IslandNotifier.PACKAGE_LABELS.forEach { (pkg, label) ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 36.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = pkg in islandPackages,
                            onCheckedChange = { vm.toggleIslandPackage(pkg) },
                        )
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    cc.ytdttj.noticleaner.ui.glass.NcOutlinedButton(enabled = islandEnabled, onClick = { vm.sendTestIsland() }) {
                        Text("发送测试岛")
                    }
                    Spacer(Modifier.width(8.dp))
                    cc.ytdttj.noticleaner.ui.glass.NcOutlinedButton(onClick = { showDiag = true }) { Text("诊断日志") }
                }
                if (showDiag) {
                    cc.ytdttj.noticleaner.ui.glass.NcAlertDialog(
                        onDismissRequest = { showDiag = false },
                        title = { Text("岛链路诊断") },
                        text = {
                            Column(
                                Modifier
                                    .heightIn(max = 420.dp)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                Text(
                                    cc.ytdttj.noticleaner.notify.island.IslandTrace.dump(),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                cc.ytdttj.noticleaner.notify.island.IslandTrace.clear()
                                showDiag = false
                            }) { Text("清空") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDiag = false }) { Text("关闭") }
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
        // ---- 通知模拟（island 测试：Shell 身份发通知进完整管线）----
        // 仅解锁后显示：设置 tab 3 秒内连点 5 次 → 解锁弹窗（MainActivity），解锁状态持久化于 DataStore
        if (simUnlocked) {
        val simulateBusy by vm.simulateBusy.collectAsState()
        var simPkg by remember { mutableStateOf("cmb.pb") }
        var simTitle by remember { mutableStateOf("") }
        var simContent by remember { mutableStateOf("") }
        var simMenu by remember { mutableStateOf(false) }
        cc.ytdttj.noticleaner.ui.glass.NcCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("通知模拟（测试）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "以 Shell 身份发送一条通知进入完整过滤管线（AI 评分 → 决策 → 入库 → 上岛），岛展示按所选应用处理。" +
                        "系统限制：通知栏那条通知的来源固定显示 Shell，无法伪造他人包名。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.layout.Box {
                    cc.ytdttj.noticleaner.ui.glass.NcOutlinedButton(onClick = { simMenu = true }) {
                        Text(
                            "模拟来源：" +
                                (cc.ytdttj.noticleaner.notify.island.IslandNotifier.PACKAGE_LABELS[simPkg] ?: simPkg),
                        )
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = simMenu,
                        onDismissRequest = { simMenu = false },
                    ) {
                        cc.ytdttj.noticleaner.notify.island.IslandNotifier.PACKAGE_LABELS.forEach { (p, l) ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(l) },
                                onClick = { simPkg = p; simMenu = false },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                cc.ytdttj.noticleaner.ui.glass.NcOutlinedTextField(
                    value = simTitle,
                    onValueChange = { simTitle = it },
                    label = { Text("通知标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                cc.ytdttj.noticleaner.ui.glass.NcOutlinedTextField(
                    value = simContent,
                    onValueChange = { simContent = it },
                    label = { Text("通知内容（含金额即可上岛，如：您消费 ¥25.00）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    androidx.compose.material3.Button(
                        enabled = !simulateBusy,
                        onClick = { vm.simulateDirect(simPkg, simTitle, simContent) },
                    ) { Text(if (simulateBusy) "发送中…" else "注入管线（推荐）") }
                    Spacer(Modifier.width(8.dp))
                    cc.ytdttj.noticleaner.ui.glass.NcOutlinedButton(
                        enabled = !simulateBusy,
                        onClick = { vm.simulateNotification(simPkg, simTitle, simContent) },
                    ) { Text("Shell 通知方式") }
                }
                Text(
                    "注入管线 = 跳过系统通知直接测试上岛链路（推荐）。点击后 5 秒才发送——请立刻回到桌面：" +
                        "HyperOS 前台抑制，App 自己在前台时不渲染岛，展开态也只对后台到达的通知生效。Shell 通知方式仅验证通知栏投递。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        } // if (simUnlocked)

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    // ---- AI 模型（重置需二次确认） ----
                    Text("AI 模型", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(modelInfo, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    cc.ytdttj.noticleaner.ui.glass.NcOutlinedButton(onClick = { confirmResetModel = true }) { Text("重置模型（回到预训练基线）") }
                    if (confirmResetModel) {
                        cc.ytdttj.noticleaner.ui.glass.NcAlertDialog(
                            onDismissRequest = { confirmResetModel = false },
                            title = { Text("确认重置模型？") },
                            text = { Text("将清除所有学习标注，模型回到预训练基线。已拦截统计不受影响，此操作不可撤销。") },
                            confirmButton = {
                                TextButton(onClick = { vm.resetModel(); confirmResetModel = false }) { Text("确认重置") }
                            },
                            dismissButton = { TextButton(onClick = { confirmResetModel = false }) { Text("取消") } },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        when (val s = updateState) {
            is cc.ytdttj.noticleaner.update.UpdateState.Checking -> UpdateStatusDialog(
                title = "正在检查更新…", text = "请求${updateChannel.label}更新源（${if (updateChannel == cc.ytdttj.noticleaner.update.UpdateChannel.STABLE) "Gitee" else "GitHub"}）",
                confirm = null, onDismiss = { updateVm.reset() },
            )
            is cc.ytdttj.noticleaner.update.UpdateState.UpToDate -> UpdateStatusDialog(
                title = "已是最新版本", text = "当前 v${cc.ytdttj.noticleaner.BuildConfig.VERSION_NAME} 已是最新",
                confirm = "知道了", onDismiss = { updateVm.reset() },
            )
            is cc.ytdttj.noticleaner.update.UpdateState.Error -> UpdateStatusDialog(
                title = "更新失败", text = s.message, confirm = "知道了", onDismiss = { updateVm.reset() },
            )
            is cc.ytdttj.noticleaner.update.UpdateState.Available -> cc.ytdttj.noticleaner.ui.glass.NcAlertDialog(
                onDismissRequest = { updateVm.reset() },
                title = { Text("发现新版本 v${s.release.versionName}") },
                text = { Column { Text(s.release.notes.ifBlank { "无更新说明" }, style = MaterialTheme.typography.bodyMedium) } },
                confirmButton = { TextButton(onClick = { updateVm.startDownload(s.release) }) { Text("立即更新") } },
                dismissButton = { TextButton(onClick = { updateVm.reset() }) { Text("稍后再说") } },
            )
            is cc.ytdttj.noticleaner.update.UpdateState.Downloading -> cc.ytdttj.noticleaner.ui.glass.NcAlertDialog(
                onDismissRequest = {},
                title = { Text("正在下载 v${s.release.versionName}") },
                text = {
                    Column {
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { s.progress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("${s.progress}%", style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = { TextButton(onClick = { updateVm.cancelDownload() }) { Text("取消") } },
            )
            is cc.ytdttj.noticleaner.update.UpdateState.ReadyToInstall -> cc.ytdttj.noticleaner.ui.glass.NcAlertDialog(
                onDismissRequest = { updateVm.reset() },
                title = { Text("下载完成") },
                text = { Text("点击「安装」打开系统安装器升级到 v${s.release.versionName}。") },
                confirmButton = { TextButton(onClick = { updateVm.install(s.release, s.file) }) { Text("安装") } },
                dismissButton = { TextButton(onClick = { updateVm.reset() }) { Text("稍后") } },
            )
            cc.ytdttj.noticleaner.update.UpdateState.Idle -> Unit
        }

        // ---- 高级保活执行结果弹窗 ----
        execResult?.let { result ->
            cc.ytdttj.noticleaner.ui.glass.NcAlertDialog(
                onDismissRequest = { vm.dismissExecResult() },
                title = { Text("保活命令执行结果") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(result, style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = { TextButton(onClick = { vm.dismissExecResult() }) { Text("完成") } },
            )
        }
    }
}

@Composable
private fun UpdateStatusDialog(title: String, text: String, confirm: String?, onDismiss: () -> Unit) {
    cc.ytdttj.noticleaner.ui.glass.NcAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(confirm ?: "关闭") }
        },
    )
}

@Composable
private fun StatusRow(label: String, ok: Boolean, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        if (ok) {
            Text("已启用", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
        } else {
            TextButton(onClick = action) { Text("去开启") }
        }
    }
}

@Composable
private fun AdvancedRow(label: String, desc: String, ok: Boolean, actionLabel: String?, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(6.dp))
                Text(
                    when (ok) {
                        true -> "可用"
                        false -> "不可用"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                )
            }
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        if (actionLabel != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
internal fun settingsVmFactory(): androidx.lifecycle.ViewModelProvider.Factory =
    androidx.lifecycle.viewmodel.viewModelFactory {
        initializer {
            SettingsViewModel(ServiceLocator.settings, ServiceLocator.db.notificationDao())
        }
    }
