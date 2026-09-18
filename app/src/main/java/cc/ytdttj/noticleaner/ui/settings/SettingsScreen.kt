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
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

    // ---- 超级岛（island 分支功能）----
    val islandEnabled = settings.islandEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val islandPackages = settings.islandPackages.stateIn(viewModelScope, SharingStarted.Eagerly, cc.ytdttj.noticleaner.notify.island.IslandNotifier.DEFAULT_PACKAGES)

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
    }

    fun refreshKeepAlive() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val shizukuOk = runCatching {
                rikka.shizuku.Shizuku.pingBinder() &&
                    rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            _keepAlive.value = ServiceLocator.keepAlive.status(shizukuOk)
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
        viewModelScope.launch { settings.setIslandEnabled(v) }
    }

    fun toggleIslandPackage(pkg: String) {
        viewModelScope.launch {
            val current = islandPackages.value
            val next = if (pkg in current) current - pkg else current + pkg
            settings.setIslandPackages(next)
        }
    }

    /** 探测超级岛支持情况（OS 版本 + Shizuku），结果回调主线程 */
    fun probeIsland(onResult: (String) -> Unit) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val ctx = ServiceLocator.appContext
            val protocol = runCatching {
                android.provider.Settings.System.getInt(ctx.contentResolver, "notification_focus_protocol", 0)
            }.getOrDefault(0)
            val shizukuOk = runCatching {
                rikka.shizuku.Shizuku.pingBinder() &&
                    rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            val osLine = when {
                protocol >= 3 -> "系统：HyperOS 3 超级岛"
                protocol == 2 -> "系统：焦点通知（OS2），无岛形态"
                else -> "系统：不支持焦点通知/超级岛"
            }
            val shizukuLine = if (shizukuOk) "Shizuku：已授权" else "Shizuku：未授权（上岛必需）"
            onResult("$osLine\n$shizukuLine")
        }
    }

    /** 发送测试岛通知（走完整盲窗链路） */
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
        if (!cc.ytdttj.noticleaner.notify.island.IslandBypassExecutor.isReady()) {
            _toast.value = "Shizuku 未授权：模拟发送需要 Shizuku"
            return
        }
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
    val modelInfo by vm.modelInfo.collectAsState()
    val execResult by vm.execResult.collectAsState()
    val execBusy by vm.execBusy.collectAsState()
    var thresholdInput by remember(threshold) { mutableStateOf("%.2f".format(threshold)) }
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
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        // ---- 过滤阈值 ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("过滤阈值", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("AI 判定广告概率 ≥ 阈值时自动清除。范围 0.5~1.0，默认 0.8。", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
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

        // ---- 拦截模式 ----
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("拦截模式", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("关闭后仅标记不拦截，便于观察误杀（AI 仍打分并记录）", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = intercept, onCheckedChange = { vm.setInterceptMode(it) })
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- 统计 ----
        Card(Modifier.fillMaxWidth()) {
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

        // ---- 模型信息 ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("AI 模型", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(modelInfo, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { vm.resetModel() }) { Text("重置模型（回到预训练基线）") }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- 多任务隐藏 ----
        Card(Modifier.fillMaxWidth()) {
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

        // ---- 超级岛支付提醒（island 分支实验功能） ----
        val islandEnabled by vm.islandEnabled.collectAsState()
        val islandPackages by vm.islandPackages.collectAsState()
        var islandStatus by remember { mutableStateOf("探测系统支持中…") }
        var showDiag by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { vm.probeIsland { islandStatus = it } }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("超级岛支付提醒（实验）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "银行/支付类 App 的收支通知自动上岛：摘要态显示来源图标与金额，展开显示详情。仅 HyperOS 3 + Shizuku 生效，失败自动退化为普通通知。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(islandStatus, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                    Switch(checked = islandEnabled, onCheckedChange = { vm.setIslandEnabled(it) })
                }
                Text("上岛应用（点击切换）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    cc.ytdttj.noticleaner.notify.island.IslandNotifier.PACKAGE_LABELS.forEach { (pkg, label) ->
                        androidx.compose.material3.FilterChip(
                            selected = pkg in islandPackages,
                            onClick = { vm.toggleIslandPackage(pkg) },
                            label = { Text(label) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Row {
                    OutlinedButton(enabled = islandEnabled, onClick = { vm.sendTestIsland() }) {
                        Text("发送测试岛")
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { showDiag = true }) { Text("诊断日志") }
                }
                if (showDiag) {
                    androidx.compose.material3.AlertDialog(
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

        // ---- 通知模拟（island 测试：Shell 身份发通知进完整管线） ----
        val simulateBusy by vm.simulateBusy.collectAsState()
        var simPkg by remember { mutableStateOf("com.cmbchina.cmb.plainpinkage") }
        var simTitle by remember { mutableStateOf("") }
        var simContent by remember { mutableStateOf("") }
        var simMenu by remember { mutableStateOf(false) }
        Card(Modifier.fillMaxWidth()) {
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
                    OutlinedButton(onClick = { simMenu = true }) {
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
                OutlinedTextField(
                    value = simTitle,
                    onValueChange = { simTitle = it },
                    label = { Text("通知标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = simContent,
                    onValueChange = { simContent = it },
                    label = { Text("通知内容（含金额即可上岛，如：您消费 ¥25.00）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.Button(
                    enabled = !simulateBusy,
                    onClick = { vm.simulateNotification(simPkg, simTitle, simContent) },
                ) { Text(if (simulateBusy) "发送中…" else "发送模拟通知") }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- 后台保活卡片 ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("后台保活", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                StatusRow("通知监听权限", keepAlive.listenerEnabled) { vm.openListenerSettings() }
                StatusRow("电池优化白名单", keepAlive.ignoringBattery) { vm.requestIgnoreBattery() }
                manufacturerHint?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    Spacer(Modifier.height(8.dp))
                }
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                // ---- 高级保活（非必需，用户主动启用） ----
                Text("高级保活（可选）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
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
                    desc = "安装 LSPosed 并激活本模块（作用域勾选「系统(android)」）后自动生效，重启手机完成",
                    ok = keepAlive.lspDetected == true,
                    actionLabel = null,
                    onAction = {},
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
        Spacer(Modifier.height(24.dp))

        // ---- 诊断日志导出（1.2.1） ----
        var exporting by remember { mutableStateOf(false) }
        val exportScope = androidx.compose.runtime.rememberCoroutineScope()
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("诊断", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "导出运行日志（含监听状态/过滤决策/看门狗记录），生成后可通过微信/邮箱发送给开发者排查问题。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    enabled = !exporting,
                    onClick = {
                        exporting = true
                        exportScope.launch {
                            val file = runCatching {
                                cc.ytdttj.noticleaner.diagnostics.DiagExporter.export(context)
                            }.getOrNull()
                            exporting = false
                            if (file == null) {
                                android.widget.Toast.makeText(context, "日志导出失败", android.widget.Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                            runCatching {
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file,
                                )
                                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                    putExtra(
                                        android.content.Intent.EXTRA_SUBJECT,
                                        "NotiCleaner 诊断日志 ${file.name}",
                                    )
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(
                                    android.content.Intent.createChooser(send, "分享诊断日志")
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }.onFailure {
                                android.widget.Toast.makeText(
                                    context,
                                    "已保存：${file.path}",
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    },
                ) { Text(if (exporting) "导出中…" else "导出诊断日志") }
            }
        }
        Spacer(Modifier.height(24.dp))

        // ---- 检查更新 ----
        val updateVm: cc.ytdttj.noticleaner.update.UpdateViewModel =
            viewModel(key = "update", factory = viewModelFactory { initializer { cc.ytdttj.noticleaner.update.UpdateViewModel() } })
        val updateState by updateVm.state.collectAsState()
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("检查更新", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "当前版本 v${cc.ytdttj.noticleaner.BuildConfig.VERSION_NAME}（检查顺序：GitHub → Gitee）",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    OutlinedButton(
                        enabled = updateState !is cc.ytdttj.noticleaner.update.UpdateState.Checking,
                        onClick = { updateVm.checkUpdate() },
                    ) { Text("检查") }
                }
            }
        }
        when (val s = updateState) {
            is cc.ytdttj.noticleaner.update.UpdateState.Checking -> UpdateStatusDialog(
                title = "正在检查更新…", text = "依次请求 GitHub / Gitee",
                confirm = null, onDismiss = { updateVm.reset() },
            )
            is cc.ytdttj.noticleaner.update.UpdateState.UpToDate -> UpdateStatusDialog(
                title = "已是最新版本", text = "当前 v${cc.ytdttj.noticleaner.BuildConfig.VERSION_NAME} 已是最新",
                confirm = "知道了", onDismiss = { updateVm.reset() },
            )
            is cc.ytdttj.noticleaner.update.UpdateState.Error -> UpdateStatusDialog(
                title = "更新失败", text = s.message, confirm = "知道了", onDismiss = { updateVm.reset() },
            )
            is cc.ytdttj.noticleaner.update.UpdateState.Available -> androidx.compose.material3.AlertDialog(
                onDismissRequest = { updateVm.reset() },
                title = { Text("发现新版本 v${s.release.versionName}") },
                text = { Column { Text(s.release.notes.ifBlank { "无更新说明" }, style = MaterialTheme.typography.bodyMedium) } },
                confirmButton = { TextButton(onClick = { updateVm.startDownload(s.release) }) { Text("立即更新") } },
                dismissButton = { TextButton(onClick = { updateVm.reset() }) { Text("稍后再说") } },
            )
            is cc.ytdttj.noticleaner.update.UpdateState.Downloading -> androidx.compose.material3.AlertDialog(
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
            is cc.ytdttj.noticleaner.update.UpdateState.ReadyToInstall -> androidx.compose.material3.AlertDialog(
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
            androidx.compose.material3.AlertDialog(
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
    androidx.compose.material3.AlertDialog(
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
