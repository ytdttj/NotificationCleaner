package cc.ytdttj.noticleaner.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cc.ytdttj.noticleaner.ServiceLocator
import cc.ytdttj.noticleaner.data.db.DECISION_FILTERED_BY_AI
import cc.ytdttj.noticleaner.data.db.DECISION_FILTERED_BY_RULE
import cc.ytdttj.noticleaner.data.db.DECISION_MANUAL_MARKED_AD
import cc.ytdttj.noticleaner.data.db.DECISION_PASSED
import cc.ytdttj.noticleaner.data.db.NotificationDao
import cc.ytdttj.noticleaner.data.db.NotificationEntity
import cc.ytdttj.noticleaner.ui.history.NotificationDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// 1.3.2（P3-6）：SimpleDateFormat（非线程安全）→ java.time DateTimeFormatter（不可变）
private val detailTimeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

private fun formatDetailTime(epochMs: Long): String =
    detailTimeFmt.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

sealed class StatsMode(val key: String) {
    data object Filtered : StatsMode("filtered")
    data object Learned : StatsMode("learned")

    companion object {
        fun of(key: String): StatsMode = if (key == "learned") Learned else Filtered
    }
}

class StatsDetailViewModel(
    private val dao: NotificationDao,
    private val modelRepo: cc.ytdttj.noticleaner.data.ModelRepository,
) : ViewModel() {
    val filtered = dao.listByDecisions(
        listOf("FILTERED_BY_AI", "FILTERED_BY_AI_MODULE"),
    ).stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList(),
    )
    val filteredByRule = dao.listByDecisions(
        listOf("FILTERED_BY_RULE", "FILTERED_BY_RULE_MODULE"),
    ).stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList(),
    )
    val learned = dao.listLearned().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---- 1.4.0 Dev 13：批量重新学习（顶部「全部学习」） ----
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    fun clearToast() {
        _toast.value = null
    }

    // ---- Dev 14：明细条目点击 → 弹层内单条学习/取消学习（与历史页同款交互）----
    private val _selected = MutableStateFlow<NotificationEntity?>(null)
    val selected: StateFlow<NotificationEntity?> = _selected

    fun select(n: NotificationEntity?) {
        _selected.value = n
    }

    /** 单条学习：广告(1) / 正常(0)；同方向重复点击累积权重，随后全量重拟合 */
    fun learn(n: NotificationEntity, label: Int) {
        viewModelScope.launch(Dispatchers.Default) {
            val sameDirection = n.learned && n.learnLabel == label
            dao.update(
                n.copy(
                    learned = true,
                    learnLabel = label,
                    learnCount = if (sameDirection) n.learnCount + 1 else 1,
                    decision = if (label == 1) DECISION_MANUAL_MARKED_AD else DECISION_PASSED,
                ),
            )
            if (label == 1 && n.key.isNotEmpty()) {
                cc.ytdttj.noticleaner.notify.CleanerListenerService.cancelByKey(n.key)
            }
            _toast.value = "正在拟合标注…"
            cc.ytdttj.noticleaner.data.LearningHelper.refit(dao, modelRepo)
            _toast.value = when {
                label == 1 && sameDirection -> "已重复学习广告（第 ${n.learnCount + 1} 次）"
                label == 1 -> "已学习为广告通知"
                else -> "已学习为正常通知"
            }
        }
    }

    /** 取消学习：从标注集移除后重新拟合，精确回滚 */
    fun unlearn(n: NotificationEntity) {
        viewModelScope.launch(Dispatchers.Default) {
            dao.update(n.copy(learned = false, learnLabel = -1, learnCount = 0))
            cc.ytdttj.noticleaner.data.LearningHelper.refit(dao, modelRepo)
            _toast.value = "已取消学习"
        }
    }

    /**
     * 按各通知**原有方向**重新学习（广告→广告加强，正常→正常加强），
     * 未学习过的按广告方向学习；整体只做一次全量重拟合。
     */
    fun relearnAll(items: List<NotificationEntity>) {
        if (items.isEmpty() || _busy.value) return
        viewModelScope.launch(Dispatchers.Default) {
            _busy.value = true
            _toast.value = "正在重新学习 ${items.size} 条通知…"
            val result = runCatching {
                cc.ytdttj.noticleaner.data.LearningHelper.relearnAll(dao, modelRepo, items)
            }.getOrNull()
            _busy.value = false
            _toast.value = if (result == null) {
                "批量学习失败，请重试"
            } else {
                "已重新学习 ${result.total} 条：广告 ${result.adCount} 条 / 正常 ${result.normalCount} 条"
            }
        }
    }
}

/** 设置页统计明细（Plan.md §6.3）：已过滤/已学习通知全文列表 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsDetailScreen(
    modeKey: String,
    onBack: () -> Unit = {},
    vm: StatsDetailViewModel = viewModel(factory = statsVmFactory()),
) {
    val mode = StatsMode.of(modeKey)
    val filtered by vm.filtered.collectAsState()
    val filteredByRule by vm.filteredByRule.collectAsState()
    val learned by vm.learned.collectAsState()

    val items: List<NotificationEntity> = when (mode) {
        StatsMode.Filtered -> (filtered + filteredByRule).sortedByDescending { it.postTime }
        StatsMode.Learned -> learned
    }

    // 1.4.0 Dev 13：「全部学习」——按各自原方向重新学习（广告加强/正常加强）
    val toast by vm.toast.collectAsState()
    val busy by vm.busy.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var confirmAll by remember { mutableStateOf(false) }
    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it)
            vm.clearToast()
        }
    }
    val normalCount = items.count { it.learned && it.learnLabel == 0 }
    val adCount = items.size - normalCount

    if (confirmAll) {
        cc.ytdttj.noticleaner.ui.glass.NcAlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text("重新学习这 ${items.size} 条通知？") },
            text = {
                Text(
                    "按每条通知原有的学习方向再次学习：\n" +
                        "• 已学习为广告 $adCount 条 → 再学一次广告（广告权重加强）\n" +
                        "• 已学习为正常 $normalCount 条 → 再学一次正常（广告权重下调）\n" +
                        "• 未学习过的按广告方向学习\n\n" +
                        "学习后模型会立即重新拟合。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmAll = false
                    vm.relearnAll(items)
                }) { Text("开始学习") }
            },
            dismissButton = {
                TextButton(onClick = { confirmAll = false }) { Text("取消") }
            },
        )
    }

    // Dev 14：玻璃模式下悬浮底栏在 Scaffold 之外，Snackbar 需上移让位，否则被盖住
    val snackbarBottomPadding =
        if (cc.ytdttj.noticleaner.ui.LocalGlassMode.current) {
            cc.ytdttj.noticleaner.ui.glass.GlassFloatingBarClearance
        } else {
            0.dp
        }
    val selected by vm.selected.collectAsState()

    cc.ytdttj.noticleaner.ui.glass.NcScaffold(
        snackbarHost = {
            Box(Modifier.padding(bottom = snackbarBottomPadding)) { SnackbarHost(snackbar) }
        },
        topBar = {
            cc.ytdttj.noticleaner.ui.glass.NcTopAppBar(
                title = { Text(if (mode is StatsMode.Filtered) "已过滤的通知" else "已学习的通知") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                actions = {
                    if (items.isNotEmpty()) {
                        TextButton(onClick = { confirmAll = true }, enabled = !busy) {
                            Text(if (busy) "学习中…" else "全部学习")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (items.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            ) {
                Text("暂无记录", style = MaterialTheme.typography.titleMedium)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(12.dp)) {
                items(items, key = { it.id }) { n ->
                    // Dev 14：条目可点击 → 打开与历史页同款的详情弹层（可重新学习/取消学习）
                    cc.ytdttj.noticleaner.ui.glass.NcCard(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { vm.select(n) },
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row {
                                Text(n.appName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text(formatDetailTime(n.postTime), style = MaterialTheme.typography.labelSmall)
                                Spacer(Modifier.weight(1f))
                                val reason = when (n.decision) {
                                    "FILTERED_BY_AI", "FILTERED_BY_AI_MODULE" -> "AI ${(n.adProbability * 100).toInt()}%"
                                    "FILTERED_BY_RULE", "FILTERED_BY_RULE_MODULE" -> "规则"
                                    DECISION_MANUAL_MARKED_AD -> "手动学习"
                                    else -> ""
                                }
                                Text(reason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                            if (n.title.isNotEmpty()) {
                                Text(n.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(n.content, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }

    // Dev 14：点击条目 → 与历史页一致的详情弹层（重新学习 / 取消学习 / 跳转通道）
    selected?.let { n ->
        val context = LocalContext.current
        cc.ytdttj.noticleaner.ui.glass.NcModalBottomSheet(onDismissRequest = { vm.select(null) }) {
            NotificationDetail(
                n = n,
                onJumpChannel = {
                    cc.ytdttj.noticleaner.notify.KeepAliveManager.openChannelSettings(
                        context, n.packageName, n.channelId,
                    )
                },
                onLearn = { label -> vm.learn(n, label) },
                onUnlearn = { vm.unlearn(n) },
            )
        }
    }
}

@Composable
internal fun statsVmFactory(): androidx.lifecycle.ViewModelProvider.Factory =
    androidx.lifecycle.viewmodel.viewModelFactory {
        initializer {
            StatsDetailViewModel(ServiceLocator.db.notificationDao(), ServiceLocator.modelRepo)
        }
    }
