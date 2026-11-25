package cc.ytdttj.noticleaner.ui.settings

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cc.ytdttj.noticleaner.ui.glass.NcCard
import cc.ytdttj.noticleaner.ui.glass.NcScaffold
import cc.ytdttj.noticleaner.ui.glass.NcTopAppBar

/**
 * 参考开源项目（2.0.1 Dev 17）：
 * 本项目的借鉴、参考与依赖来源——岛通知协议/参数（Notice、SignalDock、HyperIsland）、
 * 认证与代发信任模型（HyperIsland）、液态玻璃（Backdrop/Shapes）、UI 风格（miuix）、
 * 模块 API（libxposed）与免 Root 通道（Shizuku）。
 */
private data class OpenSourceProject(
    val name: String,
    val author: String,
    val url: String,
)

private val PROJECTS = listOf(
    OpenSourceProject(
        "Notice", "Night-stars-1", "https://github.com/Night-stars-1/Notice",
    ),
    OpenSourceProject(
        "HyperIsland", "1812z", "https://github.com/1812z/HyperIsland",
    ),
    OpenSourceProject(
        "SignalDock", "jizizr", "https://github.com/jizizr/signaldock",
    ),
    OpenSourceProject(
        "miuix", "compose-miuix-ui", "https://github.com/compose-miuix-ui/miuix",
    ),
    OpenSourceProject(
        "Backdrop", "Kyant0", "https://github.com/Kyant0/Backdrop",
    ),
    OpenSourceProject(
        "Shapes", "Kyant0", "https://github.com/Kyant0/Shapes",
    ),
    OpenSourceProject(
        "libxposed API", "libxposed", "https://github.com/libxposed/api",
    ),
    OpenSourceProject(
        "Shizuku", "RikkaApps", "https://github.com/RikkaApps/Shizuku",
    ),
)

@Composable
fun OpenSourceScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    NcScaffold(
        // 2.3.0 Dev 2：外层 MainScaffold 已应用 inset，内层清零避免顶部双叠加
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            NcTopAppBar(
                title = { Text("参考开源项目") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
        ) {
            item {
                Text(
                    "感谢以下开源项目，点击卡片访问仓库。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }
            items(PROJECTS, key = { it.name }) { p ->
                NcCard(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.url)))
                            }
                        },
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                p.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "↗",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "作者：${p.author}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            p.url.removePrefix("https://"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "以上项目排名不分先后",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
