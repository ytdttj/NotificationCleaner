package cc.ytdttj.noticleaner.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
        if (showAlert && (lostListener || lostBattery)) alertVisible = true
        if (!lostListener && !lostBattery) alertVisible = false
        lifecycleScope.launch {
            // 1.1.13：每次进入按当前设置重应用任务隐藏（任务标志不跨任务实例持久）
            applyExcludeFromRecents(ServiceLocator.settings.excludeFromRecents.first())
        }
        if (s.listenerEnabled) {
            lifecycleScope.launch(Dispatchers.IO) {
                if (!CleanerListenerService.isListenerConnected()) {
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
                if (alertVisible && (lostListener || lostBattery)) {
                    PermissionLostDialog(
                        lostListener = lostListener,
                        lostBattery = lostBattery,
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
    Tab("history", "历史", Icons.Filled.History),
    Tab("rules", "规则", Icons.Filled.Rule),
    Tab("settings", "设置", Icons.Filled.Settings),
)

@Composable
fun MainScaffold() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route ?: "history"
    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "history",
            modifier = Modifier.padding(padding),
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
}
