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
    val usage: String,
)

private val PROJECTS = listOf(
    OpenSourceProject(
        "Notice", "Night-stars-1", "https://github.com/Night-stars-1/Notice",
        "超级岛通知协议与 ML 训练管线参考（训练脚本同源）",
    ),
    OpenSourceProject(
        "HyperIsland", "1812z", "https://github.com/1812z/HyperIsland",
        "认证绕过思路与 SystemUI 进程内代发（岛代发信任模型）参考",
    ),
    OpenSourceProject(
        "SignalDock", "jizizr", "https://github.com/jizizr/signaldock",
        "岛通知模板参数与结果岛常驻策略参考",
    ),
    OpenSourceProject(
        "miuix", "compose-miuix-ui", "https://github.com/compose-miuix-ui/miuix",
        "Compose UI 组件风格参考",
    ),
    OpenSourceProject(
        "Backdrop", "Kyant0", "https://github.com/Kyant0/Backdrop",
        "液态玻璃背景效果库（界面玻璃质感的核心依赖）",
    ),
    OpenSourceProject(
        "Shapes", "Kyant0", "https://github.com/Kyant0/Shapes",
        "连续曲率圆角形状组件",
    ),
    OpenSourceProject(
        "libxposed API", "libxposed", "https://github.com/libxposed/api",
        "LSPosed Modern API（模块端 hook 能力）",
    ),
    OpenSourceProject(
        "Shizuku", "RikkaApps", "https://github.com/RikkaApps/Shizuku",
        "免 Root shell 通道（保活修复与诊断命令）",
    ),
)

@Composable
fun OpenSourceScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    NcScaffold(
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
                    "通知滤盒的诞生离不开以下开源项目——感谢每一位作者的付出。" +
                        "点击卡片可访问对应的 GitHub 仓库。",
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
                        Text(p.usage, style = MaterialTheme.typography.bodySmall)
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
