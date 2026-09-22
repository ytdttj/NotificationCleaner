package cc.ytdttj.noticleaner.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// dev 分支 UI 改造（阶段一）：
// - Material You 动态取色：跟随系统壁纸配色（Material 3 Expressive 基础升级）
// - minSdk 33，dynamicColorScheme 恒可用，无需静态兜底配色
// - 统一 Shapes（大圆角，贴合 HyperOS 卡片风格）
//   注：material3 1.4.0 中 Expressive 运动曲线已作为组件默认行为内置，
//   MotionScheme/MaterialExpressiveTheme 公开 API 需等 1.5.0 stable

// 统一圆角体系：小 12 / 中 16 / 大 24 / 特大 32（HyperOS 风格大圆角卡片）
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colorScheme = if (isSystemInDarkTheme()) {
        dynamicDarkColorScheme(context)
    } else {
        dynamicLightColorScheme(context)
    }
    MaterialTheme(
        colorScheme = colorScheme,
        shapes = AppShapes,
        content = content,
    )
}
