package cc.ytdttj.noticleaner.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cc.ytdttj.noticleaner.ServiceLocator
import cc.ytdttj.noticleaner.data.db.ConditionEvaluator
import cc.ytdttj.noticleaner.data.db.MatchMode
import cc.ytdttj.noticleaner.data.db.RuleDao
import cc.ytdttj.noticleaner.data.db.RuleEntity
import cc.ytdttj.noticleaner.data.db.WhitelistDao
import cc.ytdttj.noticleaner.data.db.WhitelistEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RulesViewModel(
    private val dao: RuleDao,
    private val whitelistDao: WhitelistDao,
    private val notificationDao: cc.ytdttj.noticleaner.data.db.NotificationDao,
) : ViewModel() {
    val rules = dao.listAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val whitelist = whitelistDao.listAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 每条规则的历史过滤计数（1.4.0 Dev 2）：
     * 取 NLS 端 + 模块端回流的规则过滤记录，在 UI 层按各规则条件求值归因
     * （与引擎同一求值器 [ConditionEvaluator]，无需改动决策/入库功能代码）。
     * 一条通知只归因于规则列表中第一条命中的规则，避免多规则重叠时重复计数。
     */
    val ruleHitCounts: kotlinx.coroutines.flow.StateFlow<Map<Long, Int>> =
        kotlinx.coroutines.flow.combine(
            notificationDao.listByDecisions(
                listOf(
                    cc.ytdttj.noticleaner.data.db.DECISION_FILTERED_BY_RULE,
                    cc.ytdttj.noticleaner.data.db.DECISION_FILTERED_BY_RULE_MODULE,
                ),
            ),
            dao.listAll(),
        ) { notifs, rs ->
            val compiled = rs.map { Triple(it.id, it.packageName, it.conditionSet()) }
            val counts = HashMap<Long, Int>()
            for (n in notifs) {
                for ((id, pkg, set) in compiled) {
                    if (pkg == n.packageName && ConditionEvaluator.evalSet(set, n.title, n.content)) {
                        counts[id] = (counts[id] ?: 0) + 1
                        break
                    }
                }
            }
            counts
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun toggle(rule: RuleEntity, enabled: Boolean) {
        viewModelScope.launch { dao.update(rule.copy(enabled = enabled)) }
    }

    fun delete(rule: RuleEntity) {
        viewModelScope.launch { dao.delete(rule.id) }
    }

    fun addWhitelist(packageName: String, appName: String) {
        viewModelScope.launch { whitelistDao.insert(WhitelistEntity(packageName = packageName, appName = appName)) }
    }

    fun removeWhitelist(packageName: String) {
        viewModelScope.launch { whitelistDao.delete(packageName) }
    }
}

@Composable
fun RulesScreen(
    onOpenRuleEdit: () -> Unit,
    onOpenAppPicker: () -> Unit,
    vm: RulesViewModel = viewModel(factory = rulesVmFactory()),
) {
    val rules by vm.rules.collectAsState()
    val whitelist by vm.whitelist.collectAsState()
    val hitCounts by vm.ruleHitCounts.collectAsState()
    var tab by remember { mutableStateOf(0) } // 0=过滤规则 1=白名单

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = {
                if (tab == 0) onOpenRuleEdit() else {
                    AppPickerSession.initial = emptyList()
                    onOpenAppPicker()
                }
            }) {
                Icon(Icons.Filled.Add, contentDescription = if (tab == 0) "新建规则" else "添加白名单")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("过滤规则 (${rules.size})") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("白名单 (${whitelist.size})") })
            }
            when (tab) {
                0 -> {
                    if (rules.isEmpty()) {
                        EmptyHint("暂无手动规则", "点击右下角 + 新建：选择 APP + 条件组合（8 种匹配方式）")
                    } else {
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                            items(rules, key = { it.id }) { rule ->
                                RuleCard(
                                    rule,
                                    hitCount = hitCounts[rule.id] ?: 0,
                                    onToggle = { vm.toggle(rule, it) },
                                    onDelete = { vm.delete(rule) },
                                )
                            }
                        }
                    }
                }
                else -> {
                    if (whitelist.isEmpty()) {
                        EmptyHint("白名单为空", "白名单内的 APP 通知不会被 AI 过滤；点击右下角 + 添加")
                    } else {
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                            items(whitelist, key = { it.packageName }) { item ->
                                WhitelistCard(item, onRemove = { vm.removeWhitelist(item.packageName) })
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 规则摘要：APP + 条件列表 */
@Composable
private fun RuleSummary(rule: RuleEntity) {
    val cs = rule.conditionSet()
    Text(rule.appName, style = MaterialTheme.typography.titleSmall)
    if (cs.conditions.isEmpty()) {
        Text("无条件", style = MaterialTheme.typography.bodySmall)
        return
    }
    Text(
        buildString {
            append(if (cs.join == "OR") "任一条件" else "全部条件")
            append("满足时过滤：")
            cs.conditions.forEachIndexed { i, c ->
                if (i > 0) append(if (cs.join == "OR") " 或 " else " 且 ")
                append("[${if (c.field == cc.ytdttj.noticleaner.data.db.MATCH_TITLE) "标题" else "内容"}·")
                append(MatchMode.label(c.mode))
                append("]")
            }
        },
        style = MaterialTheme.typography.bodySmall,
        maxLines = 2,
    )
}

@Composable
private fun RuleCard(rule: RuleEntity, hitCount: Int, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                RuleSummary(rule)
                Spacer(Modifier.height(2.dp))
                // 1.4.0 Dev 2：该规则累计过滤的通知条数（NLS 端 + 模块端回流记录）
                Text(
                    "已过滤 $hitCount 条通知",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Switch(checked = rule.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "删除") }
        }
    }
}

@Composable
private fun WhitelistCard(item: WhitelistEntity, onRemove: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.appName, style = MaterialTheme.typography.titleSmall)
                Text(item.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, contentDescription = "移除") }
        }
    }
}

@Composable
private fun EmptyHint(title: String, subtitle: String) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun rulesVmFactory(): androidx.lifecycle.ViewModelProvider.Factory =
    viewModelFactory {
        initializer {
            RulesViewModel(
                ServiceLocator.db.ruleDao(),
                ServiceLocator.db.whitelistDao(),
                ServiceLocator.db.notificationDao(),
            )
        }
    }
