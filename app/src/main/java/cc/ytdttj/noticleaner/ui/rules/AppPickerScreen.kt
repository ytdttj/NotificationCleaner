package cc.ytdttj.noticleaner.ui.rules

import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.ytdttj.noticleaner.ui.history.AppIcon

/** 选择器会话结果（跨页面传递选中项；null = 未选择/取消） */
object AppPickerSession {
    var result: List<Pair<String, String>>? = null
    var initial: List<Pair<String, String>> = emptyList()
}

private data class AppInfo(val pkg: String, val label: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerScreen(
    title: String,
    multiSelect: Boolean = true,
    onBack: () -> Unit,
    onConfirm: (List<Pair<String, String>>) -> Unit,
) {
    val context = LocalContext.current
    val allApps = remember {
        val pm = context.packageManager
        runCatching {
            // 1.2.3：不再按 launcher 过滤——系统应用（服务类无界面包）也可选；
            // label 解析逐项容错（个别包 RRO 资源加载失败不影响整个列表）
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .map {
                    val label = runCatching { pm.getApplicationLabel(it)?.toString() }
                        .getOrNull() ?: it.packageName
                    AppInfo(it.packageName, label)
                }
                .sortedBy { it.label.lowercase() }
        }.getOrDefault(emptyList())
    }
    var query by remember { mutableStateOf("") }
    var selected by remember {
        mutableStateOf(AppPickerSession.initial.associate { it.first to it.second })
    }

    val filtered = if (query.isBlank()) allApps
    else allApps.filter {
        it.label.contains(query, true) || it.pkg.contains(query, true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                actions = {
                    Button(
                        enabled = selected.isNotEmpty(),
                        onClick = { onConfirm(selected.entries.map { it.key to it.value }) },
                        modifier = Modifier.padding(end = 12.dp),
                    ) { Text("确定(${selected.size})") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索 App 名称或包名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            )
            if (filtered.isEmpty()) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { Text("无匹配应用", style = MaterialTheme.typography.titleMedium) }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                    items(filtered, key = { it.pkg }) { app ->
                        val checked = selected.containsKey(app.pkg)
                        Card(
                            onClick = {
                                if (multiSelect) {
                                    selected = if (checked) selected - app.pkg else selected + (app.pkg to app.label)
                                } else {
                                    selected = mapOf(app.pkg to app.label)
                                    onConfirm(listOf(app.pkg to app.label))
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        ) {
                            Row(
                                Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AppIcon(app.pkg, 36)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(app.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(app.pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (multiSelect) {
                                    Checkbox(checked = checked, onCheckedChange = {
                                        selected = if (it) selected + (app.pkg to app.label) else selected - app.pkg
                                    })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
