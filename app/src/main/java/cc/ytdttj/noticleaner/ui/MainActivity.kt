package cc.ytdttj.noticleaner.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import cc.ytdttj.noticleaner.ServiceLocator
import cc.ytdttj.noticleaner.ui.history.HistoryScreen
import cc.ytdttj.noticleaner.ui.rules.AppPickerScreen
import cc.ytdttj.noticleaner.ui.rules.AppPickerSession
import cc.ytdttj.noticleaner.ui.rules.RuleEditScreen
import cc.ytdttj.noticleaner.ui.rules.RulesScreen
import cc.ytdttj.noticleaner.ui.settings.SettingsScreen
import cc.ytdttj.noticleaner.ui.settings.StatsDetailScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                MainScaffold()
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
