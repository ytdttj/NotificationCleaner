package cc.ytdttj.noticleaner.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
// 1.3.2（P3-7①）：material-icons-extended → core（History/Rule 为 extended 独有，
// 就近替换为 core 内语义相近图标，debug DEX 体积显著缩小）
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import cc.ytdttj.noticleaner.ServiceLocator
import cc.ytdttj.noticleaner.notify.CleanerListenerService
import cc.ytdttj.noticleaner.ui.history.HistoryScreen
import cc.ytdttj.noticleaner.ui.permission.OnboardingScreen
import cc.ytdttj.noticleaner.ui.permission.PermissionLostDialog
import cc.ytdttj.noticleaner.ui.permission.checkPermissions
import cc.ytdttj.noticleaner.ui.rules.AppPickerScreen
import cc.ytdttj.noticleaner.ui.rules.AppPickerSession
import cc.ytdttj.noticleaner.ui.rules.RuleEditScreen
import cc.ytdttj.noticleaner.ui.rules.RulesScreen
import cc.ytdttj.noticleaner.ui.settings.SettingsScreen
import cc.ytdttj.noticleaner.ui.settings.StatsDetailScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    // ---- 权限失效提醒（1.1.8）：每次进入应用检查一次 ----
    private var alertVisible by mutableStateOf(false)
    private var lostListener by mutableStateOf(false)
    private var lostBattery by mutableStateOf(false)
    private var lostNotifications by mutableStateOf(false)

    // ---- 1.2.2：通知发送权限请求（Android 13+ 常驻保活/更新提醒通知必需） ----
    private val notifPermLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {
            // 授权/拒绝后刷新状态（拒绝时由 PermissionLostDialog 引导去系统设置）
            entryPermissionCheck(showAlert = alertVisible)
        }

    // ---- 进入应用自动检查更新（1.1.8）：onCreate/onNewIntent 递增触发 ----
    private var entryCount by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { AppRoot() } }
        // 入口检查：图标/保活通知进入都会走 onCreate 或 onNewIntent
        entryPermissionCheck(showAlert = true)
        entryCount++
    }

    override fun onResume() {
        super.onResume()
        // 从授权页返回：仅当提醒框正显示时刷新（用户授权后自动关闭/更新）
        entryPermissionCheck(showAlert = alertVisible)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        entryPermissionCheck(showAlert = true)
        entryCount++
    }

    /**
     * 进入应用时的权限检查（1.1.8）：
     * - 通知读取权限已授权但监听未连接 → 请求系统重绑（自愈）
     * - 可检测权限失效 → 弹窗提醒，可跳转重新授权
     * 1.1.13：每次进入重新应用「多任务隐藏」——该标志只对当前任务实例生效，
     * 任务被系统重建后（如进程死亡后由保活通知拉起）标志丢失，必须重新应用。
     */
    private fun entryPermissionCheck(showAlert: Boolean) {
        val s = checkPermissions(this)
        lostListener = !s.listenerEnabled
        lostBattery = !s.batteryWhitelisted
        lostNotifications = !s.notificationsGranted
        if (showAlert && (lostListener || lostBattery || lostNotifications)) alertVisible = true
        if (!lostListener && !lostBattery && !lostNotifications) alertVisible = false

        // 1.2.2：无通知发送权限 → 每次进入发起系统授权请求
        //（永久拒绝时系统静默返回，由 PermissionLostDialog 引导去应用通知设置）
        if (!s.notificationsGranted && android.os.Build.VERSION.SDK_INT >= 33) {
            notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        lifecycleScope.launch {
            // 1.1.13：每次进入按当前设置重应用任务隐藏（任务标志不跨任务实例持久）
            applyExcludeFromRecents(ServiceLocator.settings.excludeFromRecents.first())
        }
        if (s.listenerEnabled) {
            lifecycleScope.launch(Dispatchers.IO) {
                if (!CleanerListenerService.isListenerConnected()) {
                    android.util.Log.i("NCWatch", "app entry → rebind requested")
                    CleanerListenerService.requestRebindIfEnabled(this@MainActivity)
                }
            }
        }
    }

    @Composable
    private fun AppRoot() {
        // 权限初始化流程（1.1.8）：首次启动（含老版本升级后首次打开 1.1.8）走一遍
        var onboardingDone by remember { mutableStateOf<Boolean?>(null) }
        LaunchedEffect(Unit) {
            onboardingDone = ServiceLocator.settings.onboardingDone.first()
        }
        when (onboardingDone) {
            null -> Box(Modifier.fillMaxSize()) // DataStore 读取中
            false -> OnboardingScreen(
                onFinish = {
                    onboardingDone = true
                    lifecycleScope.launch { ServiceLocator.settings.setOnboardingDone() }
                },
            )
            else -> {
                MainScaffold()
                // 进入应用自动检查更新（1.1.8）：有更新弹窗，无更新静默
                val updateVm: cc.ytdttj.noticleaner.update.UpdateViewModel =
                    viewModel(key = "updateEntry", factory = viewModelFactory {
                        initializer { cc.ytdttj.noticleaner.update.UpdateViewModel() }
                    })
                LaunchedEffect(entryCount) {
                    val s = updateVm.state.value
                    // 正在下载/待安装时不打断，避免重复检查取消进行中的下载
                    if (s !is cc.ytdttj.noticleaner.update.UpdateState.Downloading &&
                        s !is cc.ytdttj.noticleaner.update.UpdateState.ReadyToInstall
                    ) {
                        updateVm.checkUpdate()
                    }
                }
                cc.ytdttj.noticleaner.ui.update.UpdatePromptDialog(vm = updateVm, onDismiss = {})
                if (alertVisible && (lostListener || lostBattery || lostNotifications)) {
                    PermissionLostDialog(
                        lostListener = lostListener,
                        lostBattery = lostBattery,
                        lostNotifications = lostNotifications,
                        onDismiss = { alertVisible = false },
                    )
                }
            }
        }
    }

    /**
     * 应用「多任务界面隐藏」（API 29+，直接对当前任务设置标记，不重建任务）。
     * @return 是否成功应用（旧系统返回 false）
     */
    fun applyExcludeFromRecents(exclude: Boolean): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 29) return false
        return runCatching {
            val am = getSystemService(android.app.ActivityManager::class.java)
            am?.appTasks?.firstOrNull()?.setExcludeFromRecents(exclude)
            am?.appTasks != null
        }.getOrDefault(false)
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("history", "历史", Icons.Filled.DateRange),
    Tab("rules", "规则", Icons.AutoMirrored.Filled.List),
    Tab("settings", "设置", Icons.Filled.Settings),
)

@Composable
fun MainScaffold() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route ?: "history"
    // 1.4.0 Dev 4：界面风格（M3 / 液态玻璃）——底栏与所有页面容器随主题切换
    val uiThemeName by cc.ytdttj.noticleaner.ServiceLocator.settings.uiTheme.collectAsState(
        initial = UiTheme.MATERIAL.name,
    )
    val glassMode = UiTheme.from(uiThemeName) == UiTheme.GLASS

    // ---- 1.3.0 beta2：设置 tab 快速点击 5 次 → 解锁通知模拟（隐藏测试功能） ----
    val settingsTaps = remember { mutableListOf<Long>() }
    var showSimUnlockDialog by remember { mutableStateOf(false) }

    fun onTabClick(route: String) {
        if (route == "settings") {
            val now = System.currentTimeMillis()
            settingsTaps.add(now)
            while (settingsTaps.size > 5) settingsTaps.removeAt(0)
            if (settingsTaps.size == 5 && now - settingsTaps.first() < 3000) {
                settingsTaps.clear()
                showSimUnlockDialog = true
            }
        }
        navController.navigate(route) {
            popUpTo(navController.graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    if (glassMode) {
        // 液态玻璃（1.4.0 Dev 7）：底栏必须走 floatingBar——它要采样 contentBackdrop（页面真实内容），
        // 因此绝不能落在 content 采样子树内，否则构成 RenderNode 自引用环 → 原生崩溃。
        cc.ytdttj.noticleaner.ui.glass.GlassRoot(
            modifier = Modifier.fillMaxSize(),
            floatingBar = {
                cc.ytdttj.noticleaner.ui.glass.GlassBottomBar(
                    items = tabs.map { cc.ytdttj.noticleaner.ui.glass.GlassNavItem(it.route, it.label, it.icon) },
                    selectedRoute = currentRoute,
                    onItemClick = ::onTabClick,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            },
        ) {
            Scaffold(
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            ) { padding ->
                MainNavHost(navController = navController, modifier = Modifier.padding(padding))
            }
        }
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = { onTabClick(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            },
        ) { padding ->
            MainNavHost(navController = navController, modifier = Modifier.padding(padding))
        }
    }

    if (showSimUnlockDialog) {
        cc.ytdttj.noticleaner.ui.glass.NcAlertDialog(
            onDismissRequest = { showSimUnlockDialog = false },
            title = { Text("启用通知模拟？") },
            text = { Text("通知模拟为隐藏测试功能：可模拟银行/支付类通知进入过滤管线与超级岛，普通用户无需开启。") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showSimUnlockDialog = false
                    cc.ytdttj.noticleaner.ServiceLocator.appScope.launch {
                        cc.ytdttj.noticleaner.ServiceLocator.settings.setSimUnlocked(true)
                    }
                }) { Text("启用") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showSimUnlockDialog = false }) { Text("取消") }
            },
        )
    }
}

/** 主导航（两套皮肤共用，路由定义唯一） */
@Composable
private fun MainNavHost(navController: androidx.navigation.NavHostController, modifier: Modifier = Modifier) {
    NavHost(
        navController = navController,
        startDestination = "history",
        modifier = modifier,
    ) {
        composable("history") { HistoryScreen() }
        composable("rules") {
            RulesScreen(
                onOpenRuleEdit = { navController.navigate("ruleedit") },
                onOpenAppPicker = { navController.navigate("apppicker/白名单") },
            )
        }
        composable("settings") {
            SettingsScreen(onOpenStats = { navController.navigate("stats/$it") })
        }
        composable("stats/{mode}") { entry ->
            val mode = entry.arguments?.getString("mode") ?: "filtered"
            StatsDetailScreen(mode, onBack = { navController.popBackStack() })
        }
        composable("ruleedit") {
            RuleEditScreen(
                onBack = { navController.popBackStack() },
                openAppPicker = { navController.navigate("apppicker/选择 APP") },
            )
        }
        composable("apppicker/{title}") { entry ->
            val title = entry.arguments?.getString("title") ?: "选择 APP"
            AppPickerScreen(
                title = title,
                multiSelect = true,
                onBack = { navController.popBackStack() },
                onConfirm = {
                    // 白名单模式：直接入库；规则模式：结果由 RuleEditScreen 回读
                    if (title == "白名单") {
                        val scope = cc.ytdttj.noticleaner.ServiceLocator.appScope
                        scope.launch {
                            it.forEach { (pkg, label) ->
                                ServiceLocator.db.whitelistDao().insert(
                                    cc.ytdttj.noticleaner.data.db.WhitelistEntity(packageName = pkg, appName = label),
                                )
                            }
                        }
                    } else {
                        AppPickerSession.result = it
                    }
                    navController.popBackStack()
                },
            )
        }
    }
}
