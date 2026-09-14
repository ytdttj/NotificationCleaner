package cc.ytdttj.noticleaner.ui.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.ytdttj.noticleaner.update.UpdateState
import cc.ytdttj.noticleaner.update.UpdateViewModel

/**
 * 进入应用时的更新提示弹窗（1.1.8）：
 * - Available：发现新版本 → 立即更新 / 关闭
 * - Downloading：显示进度，可取消
 * - ReadyToInstall：安装
 * - Checking/UpToDate/Error：不显示任何提示（静默）
 */
@Composable
fun UpdatePromptDialog(vm: UpdateViewModel, onDismiss: () -> Unit) {
    val state by vm.state.collectAsState()
    when (val s = state) {
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = { vm.reset(); onDismiss() },
            title = { Text("发现新版本 v${s.release.versionName}") },
            text = {
                Text(
                    s.release.notes.ifBlank { "已发布新版本，是否立即下载更新？" },
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = { TextButton(onClick = { vm.startDownload(s.release) }) { Text("立即更新") } },
            dismissButton = { TextButton(onClick = { vm.reset(); onDismiss() }) { Text("关闭") } },
        )
        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = { vm.cancelDownload(); vm.reset(); onDismiss() },
            title = { Text("正在下载更新") },
            text = {
                Column {
                    Text("v${s.release.versionName}　${s.progress}%")
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { s.progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { vm.cancelDownload(); vm.reset(); onDismiss() }) { Text("取消") }
            },
        )
        is UpdateState.ReadyToInstall -> AlertDialog(
            onDismissRequest = { vm.reset(); onDismiss() },
            title = { Text("更新包已就绪") },
            text = { Text("点击「安装」打开系统安装器升级到 v${s.release.versionName}。") },
            confirmButton = {
                TextButton(onClick = { vm.install(s.release, s.file); onDismiss() }) { Text("安装") }
            },
            dismissButton = { TextButton(onClick = { vm.reset(); onDismiss() }) { Text("稍后") } },
        )
        else -> {}
    }
}
