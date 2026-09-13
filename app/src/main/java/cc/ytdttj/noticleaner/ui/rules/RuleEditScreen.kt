package cc.ytdttj.noticleaner.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import cc.ytdttj.noticleaner.ServiceLocator
import cc.ytdttj.noticleaner.data.db.MatchMode
import cc.ytdttj.noticleaner.data.db.MATCH_CONTENT
import cc.ytdttj.noticleaner.data.db.MATCH_TITLE
import cc.ytdttj.noticleaner.data.db.RuleCondition
import cc.ytdttj.noticleaner.data.db.RuleConditionSet
import cc.ytdttj.noticleaner.data.db.RuleDao
import cc.ytdttj.noticleaner.data.db.RuleEntity
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch

class RuleEditViewModel(private val dao: RuleDao) : ViewModel() {
    /** 保存：为每个选中的 APP 建一条规则（同一套条件） */
    fun save(apps: List<Pair<String, String>>, set: RuleConditionSet) {
        viewModelScope.launch {
            apps.forEach { (pkg, label) ->
                dao.insert(
                    RuleEntity(
                        packageName = pkg,
                        appName = label,
                        conditions = set.toJson(),
                    ),
                )
            }
        }
    }
}

/** 规则编辑页（1.0.2）：APP 跳转选择器多选 + 条件构建器（8 种模式 + 并且/或者） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleEditScreen(
    onBack: () -> Unit,
    openAppPicker: () -> Unit,
    vm: RuleEditViewModel = viewModel(factory = ruleEditVmFactory()),
) {
    var selectedApps by remember { mutableStateOf(AppPickerSession.initial) }
    var join by remember { mutableStateOf("AND") }
    var conditions by remember {
        mutableStateOf(listOf(RuleCondition(MATCH_TITLE, MatchMode.ANY_TEXT, listOf(""))))
    }

    // 从选择器取回结果
    LaunchedEffect(Unit) {
        AppPickerSession.result?.let { result ->
            selectedApps = result
            AppPickerSession.result = null
        }
    }

    fun updateCondition(index: Int, c: RuleCondition) {
        conditions = conditions.toMutableList().also { it[index] = c }
    }

    val valid = selectedApps.isNotEmpty() && conditions.any { c ->
        c.mode == MatchMode.ALL_TEXT || c.values.any { it.isNotBlank() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("新建规则") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            // ---- 选中 APP ----
            OutlinedButton(onClick = {
                AppPickerSession.initial = selectedApps
                openAppPicker()
            }, modifier = Modifier.fillMaxWidth()) {
                Text(if (selectedApps.isEmpty()) "选择 APP（可多选）" else "已选 ${selectedApps.size} 个 APP（点击修改）")
            }
            selectedApps.forEach { (pkg, label) ->
                Text("  · $label", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            Spacer(Modifier.height(12.dp))

            // ---- 条件关系 ----
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("条件满足方式：", style = MaterialTheme.typography.titleSmall)
                FilterChip(selected = join == "AND", onClick = { join = "AND" }, label = { Text("并且") })
                FilterChip(selected = join == "OR", onClick = { join = "OR" }, label = { Text("或者") })
            }
            Spacer(Modifier.height(12.dp))

            // ---- 条件列表 ----
            conditions.forEachIndexed { idx, c ->
                ConditionCard(
                    index = idx,
                    condition = c,
                    canDelete = conditions.size > 1,
                    onChange = { updateCondition(idx, it) },
                    onDelete = { conditions = conditions.filterIndexed { i, _ -> i != idx } },
                )
                Spacer(Modifier.height(10.dp))
            }
            OutlinedButton(onClick = {
                conditions = conditions + RuleCondition(MATCH_TITLE, MatchMode.ANY_TEXT, listOf(""))
            }, modifier = Modifier.fillMaxWidth()) {
                Text("添加条件（${conditions.size}）")
            }
            Spacer(Modifier.height(16.dp))

            Button(
                enabled = valid,
                onClick = {
                    val cleaned = conditions.map { c ->
                        c.copy(values = c.values.map { it.trim() }.filter { it.isNotEmpty() })
                    }.filter { it.mode == MatchMode.ALL_TEXT || it.values.isNotEmpty() }
                    vm.save(selectedApps, RuleConditionSet(join, cleaned))
                    onBack()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("保存规则") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConditionCard(
    index: Int,
    condition: RuleCondition,
    canDelete: Boolean,
    onChange: (RuleCondition) -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("条件 ${index + 1}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (canDelete) {
                    IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "删除条件") }
                }
            }
            // 字段
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = condition.field == MATCH_TITLE,
                    onClick = { onChange(condition.copy(field = MATCH_TITLE)) },
                    label = { Text("标题") },
                )
                FilterChip(
                    selected = condition.field == MATCH_CONTENT,
                    onClick = { onChange(condition.copy(field = MATCH_CONTENT)) },
                    label = { Text("内容") },
                )
            }
            Spacer(Modifier.height(8.dp))
            // 模式（下拉）
            var modeExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = modeExpanded, onExpandedChange = { modeExpanded = it }) {
                OutlinedTextField(
                    value = MatchMode.label(condition.mode),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("匹配方式") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modeExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                )
                ExposedDropdownMenu(expanded = modeExpanded, onDismissRequest = { modeExpanded = false }) {
                    MatchMode.all.forEach { (mode, label) ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                onChange(condition.copy(mode = mode))
                                modeExpanded = false
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            // 文本值（多行；ALL_TEXT 不需要输入）
            if (condition.mode != MatchMode.ALL_TEXT) {
                OutlinedTextField(
                    value = condition.values.joinToString("\n"),
                    onValueChange = { raw ->
                        onChange(condition.copy(values = raw.split("\n")))
                    },
                    label = {
                        Text(
                            when (condition.mode) {
                                MatchMode.INCLUDE_EXCLUDE -> "每行一个：第 1 行 = 包含 A，其余行 = 排除 B…"
                                else -> "每行一个文本"
                            },
                        )
                    },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
internal fun ruleEditVmFactory(): androidx.lifecycle.ViewModelProvider.Factory =
    viewModelFactory {
        initializer {
            RuleEditViewModel(ServiceLocator.db.ruleDao())
        }
    }
