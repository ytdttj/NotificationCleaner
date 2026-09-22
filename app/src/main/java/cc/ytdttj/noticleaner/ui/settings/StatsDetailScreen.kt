package cc.ytdttj.noticleaner.ui.settings

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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import cc.ytdttj.noticleaner.data.db.NotificationDao
import cc.ytdttj.noticleaner.data.db.NotificationEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
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

class StatsDetailViewModel(private val dao: NotificationDao) : ViewModel() {
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

    cc.ytdttj.noticleaner.ui.glass.NcScaffold(
        topBar = {
            cc.ytdttj.noticleaner.ui.glass.NcTopAppBar(
                title = { Text(if (mode is StatsMode.Filtered) "已过滤的通知" else "已学习的通知") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
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
                    cc.ytdttj.noticleaner.ui.glass.NcCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
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
}

@Composable
internal fun statsVmFactory(): androidx.lifecycle.ViewModelProvider.Factory =
    androidx.lifecycle.viewmodel.viewModelFactory {
        initializer {
            StatsDetailViewModel(ServiceLocator.db.notificationDao())
        }
    }
