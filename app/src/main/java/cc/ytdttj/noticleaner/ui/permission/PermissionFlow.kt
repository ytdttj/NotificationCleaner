package cc.ytdttj.noticleaner.ui.permission

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cc.ytdttj.noticleaner.notify.CleanerListenerService

/** 可检测的权限状态快照（1.1.8） */
data class PermissionStatus(
    val listenerEnabled: Boolean, // 通知读取权限
    val batteryWhitelisted: Boolean, // 省电策略（忽略电池优化）
)

fun checkPermissions(context: Context): PermissionStatus {
    val pm = context.getSystemService(PowerManager::class.java)
    return PermissionStatus(
        listenerEnabled = CleanerListenerService.isListenerEnabled(context),
        batteryWhitelisted = pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false,
    )
}

/**
 * 跳转自启动设置页（1.1.8）：厂商页面各不相同且无公开 API 检测，
 * 按品牌给出已知页面，失败逐级回退到本应用详情页。
 */
fun jumpToAutoStart(context: Context) {
    val comp: ComponentName? = when (Build.MANUFACTURER.lowercase()) {
        "xiaomi" -> ComponentName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity",
        )
        "huawei" -> ComponentName(
            "com.huawei.systemmanager",
            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        )
        "honor" -> ComponentName(
            "com.hihonor.systemmanager",
            "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        )
        "oppo", "realme" -> ComponentName(
            "com.coloros.safecenter",
            "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        )
        "oneplus" -> ComponentName(
            "com.oneplus.security",
            "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
        )
        "vivo", "iqoo" -> ComponentName(
            "com.vivo.permissionmanager",
            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        )
        "samsung" -> ComponentName(
            "com.samsung.android.lool",
            "com.samsung.android.sm.battery.ui.BatteryActivity",
        )
        else -> null
    }
    val candidates = mutableListOf<Intent>()
    if (comp != null) {
        candidates += Intent().setComponent(comp).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    // 回退：本应用详情页（可从那里进通知/自启动管理）
    candidates += Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    for (i in candidates) {
        if (runCatching { context.startActivity(i) }.isSuccess) return
    }
}

private fun jumpToListenerSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun jumpToBatterySettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }
}

/** 返回本界面（ON_RESUME）时刷新一次 */
@Composable
private fun ResumeEffect(onResume: () -> Unit) {
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(Unit) {
        val obs = object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onResume(owner: androidx.lifecycle.LifecycleOwner) {
                onResume()
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
}

/**
 * 首次启动权限初始化流程（1.1.8）：
 * 逐项展示必要权限与当前状态，点击跳转对应系统界面；返回本界面自动刷新状态。
 * 老版本升级后 onboarding 标记默认未完成，同样会走一遍。
 */
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val context = LocalContext.current
    var status by remember { mutableStateOf(checkPermissions(context)) }
    ResumeEffect { status = checkPermissions(context) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Text("欢迎使用通知净化器", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "首次使用请完成以下权限授权，保证通知过滤与后台保活正常工作。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        PermissionCard(
            title = "通知读取权限",
            desc = "核心权限：监听并过滤通知栏消息。不授权本应用无法工作。",
            statusText = if (status.listenerEnabled) "已授权" else "未授权",
            granted = status.listenerEnabled,
            buttonText = "去授权",
            onJump = { jumpToListenerSettings(context) },
        )
        Spacer(Modifier.height(12.dp))
        PermissionCard(
            title = "省电策略（无限制）",
            desc = "将省电策略设为无限制/忽略电池优化，防止后台过滤服务被系统杀死。",
            statusText = if (status.batteryWhitelisted) "已设置" else "未设置",
            granted = status.batteryWhitelisted,
            buttonText = "去设置",
            onJump = { jumpToBatterySettings(context) },
        )
        Spacer(Modifier.height(12.dp))
        PermissionCard(
            title = "自启动权限",
            desc = "允许开机自启与后台拉起（厂商手机必需）。系统无检测接口，请手动确认已开启。",
            statusText = "请手动确认",
            granted = null,
            buttonText = "去设置",
            onJump = { jumpToAutoStart(context) },
        )

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onFinish,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("完成初始化") }
        Spacer(Modifier.height(8.dp))
        Text(
            "提示：未全部授权也可以进入应用，但通知过滤与后台保活可能无法生效，可随时在「设置」中查看保活状态。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PermissionCard(
    title: String,
    desc: String,
    statusText: String,
    granted: Boolean?,
    buttonText: String,
    onJump: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text(
                    statusText,
                    style = MaterialTheme.typography.labelMedium,
                    color = when (granted) {
                        true -> MaterialTheme.colorScheme.primary
                        false -> MaterialTheme.colorScheme.error
                        null -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = onJump) { Text(buttonText) }
            }
        }
    }
}

/**
 * 每次进入应用时的权限失效提醒（1.1.8）：
 * 列出已失效的可检测权限，点击跳转对应页面重新授权。
 */
@Composable
fun PermissionLostDialog(
    lostListener: Boolean,
    lostBattery: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("权限已失效") },
        text = {
            Column {
                Text(
                    "以下权限已失效，可能导致通知无法过滤或后台被系统清理：",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                if (lostListener) {
                    LostPermRow("通知读取权限", onJump = { jumpToListenerSettings(context) })
                }
                if (lostBattery) {
                    LostPermRow("省电策略（需设为无限制）", onJump = { jumpToBatterySettings(context) })
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "提示：部分厂商系统还需检查「自启动」权限",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("暂不处理") }
        },
        dismissButton = {
            TextButton(onClick = { activity?.let { jumpToAutoStart(it) } }) { Text("自启动设置") }
        },
    )
}

@Composable
private fun LostPermRow(name: String, onJump: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(name, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onJump) { Text("去授权") }
    }
}
