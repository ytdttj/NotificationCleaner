package cc.ytdttj.noticleaner.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cc.ytdttj.noticleaner.ServiceLocator

// dev 分支 UI 改造：
// - 阶段一：Material You 动态取色 + 统一大圆角 Shapes（Material 3 Expressive 基础升级）
// - 1.4.0 Dev 4（方案 C）：双主题——MATERIAL / GLASS（液态玻璃，Kyant0 Backdrop），
//   持久化于 DataStore（SettingsRepository.uiTheme），设置页顶部可切换
// - 玻璃模式通过 [LocalGlassMode] 全局下发，所有界面经由 ui/glass/ 的 Nc* 组件
//   在同一份布局代码上渲染 M3 或液态玻璃两种皮肤

/** 界面风格：name 持久化于 DataStore */
enum class UiTheme(val label: String) {
    MATERIAL("Material 3"),
    GLASS("液态玻璃");

    companion object {
        fun from(name: String?): UiTheme = entries.firstOrNull { it.name == name } ?: MATERIAL
    }
}

/** 玻璃清晰度（1.4.0 Dev 5，仅玻璃主题下生效）：磨砂=可读性优先，柔光=近乎全透明 */
enum class GlassStyle(val label: String) {
    FROSTED("磨砂玻璃"),
    SOFT("柔光玻璃");

    companion object {
        fun from(name: String?): GlassStyle = entries.firstOrNull { it.name == name } ?: FROSTED
    }
}

/** 当前是否处于液态玻璃模式（ui/glass/ 的 Nc* 组件据此选择渲染皮肤） */
val LocalGlassMode = staticCompositionLocalOf { false }

/** 当前玻璃清晰度（ui/glass/ 的玻璃配方据此调整着色与模糊强度） */
val LocalGlassStyle = staticCompositionLocalOf { GlassStyle.FROSTED }

// 统一圆角体系：小 12 / 中 16 / 大 24 / 特大 32（M3 模式使用）
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val saved by ServiceLocator.settings.uiTheme.collectAsState(initial = UiTheme.MATERIAL.name)
    val glassStyleName by ServiceLocator.settings.glassStyle.collectAsState(initial = GlassStyle.FROSTED.name)
    val context = LocalContext.current
    // Material You 动态取色（两种模式都保留：玻璃模式的控件着色同样基于 colorScheme）
    val colorScheme = if (isSystemInDarkTheme()) {
        dynamicDarkColorScheme(context)
    } else {
        dynamicLightColorScheme(context)
    }
    MaterialTheme(colorScheme = colorScheme, shapes = AppShapes) {
        CompositionLocalProvider(
            LocalGlassMode provides (UiTheme.from(saved) == UiTheme.GLASS),
            LocalGlassStyle provides GlassStyle.from(glassStyleName),
        ) {
            content()
        }
    }
}
