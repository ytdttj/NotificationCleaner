package cc.ytdttj.noticleaner.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import cc.ytdttj.noticleaner.ui.GlassStyle
import cc.ytdttj.noticleaner.ui.LocalGlassMode
import cc.ytdttj.noticleaner.ui.LocalGlassStyle
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.capsule.ContinuousCapsule
import com.kyant.shapes.RoundedCornerStyle
import com.kyant.shapes.RoundedRectangle
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.wrapContentWidth
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/**
 * 液态玻璃设计系统（1.4.0 Dev 7，方案 C，基于 Kyant0 Backdrop 1.0.6）：
 * - 所有界面的容器组件（卡片/底栏/顶栏/对话框/输入框/按钮/底弹层）经由 Nc* 包装，
 *   M3 模式渲染原样 Material 3，玻璃模式渲染液态玻璃——同一份布局代码、零功能差异
 * - 玻璃实现：backdrop 采样 → AGSL 折射 → RenderEffect 模糊 → 高光描边 + 半透明着色
 * - Dev 7 双采样源（分离式，详见 [GlassRoot]）：
 *     wallpaperBackdrop = 淡蓝纯色背景，供页面内玻璃卡片采样；
 *     contentBackdrop   = 纯色底 + 页面真实内容，供悬浮底栏采样（透视滚动内容）。
 *   两者互不嵌套，玻璃元素均不在自己采样源的子树内——这是避免 RenderNode 自引用环的关键。
 * - 弹窗（独立窗口）无法采样主窗口，退化为磨砂半透明
 */

/**
 * 采样源一：壁纸/背景（页面内所有玻璃元素用它）。
 * 主窗口内可用；弹窗（独立窗口）中为 null → 磨砂降级。
 */
val LocalGlassBackdrop = compositionLocalOf<LayerBackdrop?> { null }

/**
 * 采样源二：页面真实内容（仅悬浮底栏用它，用于透视底下的滚动内容）。
 * 引用方向铁律见 [GlassRoot]：底栏必须位于 content 采样子树之外。
 */
val LocalContentBackdrop = compositionLocalOf<LayerBackdrop?> { null }

/**
 * 玻璃卡片默认形状：连续曲率圆角（squircle，Dev 9 起替代 RoundedCornerShape）。
 * 用 backdrop 传递依赖 kyant.shapes 的 RoundedRectangle + Continuous——它在 lens 折射着色器的
 * 形状白名单内（基于 androidx.graphics.shapes 的第三方平滑圆角库不在白名单，会直接抛异常）。
 */
val GlassCardShape: Shape = RoundedRectangle(24.dp, RoundedCornerStyle.Continuous)

/** 玻璃背景底色（1.4.0 Dev 7：淡蓝纯色，替代 Dev 6 的四色渐变壁纸） */
private val GlassBackgroundColor = Color(0xFFD7E7F8)
private val GlassBackgroundColorDark = Color(0xFF10161F)

private fun glassBorder(dark: Boolean): Color =
    if (dark) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.55f)

/**
 * 玻璃表面着色（drawBackdrop 的 onDrawSurface 层）：
 * 磨砂=可读性优先的薄雾，柔光=近乎全透明；strong 为底栏等需要最低可读性的场景。
 *
 * 注意：本函数只服务玻璃**表面**。对话框/输入框/按钮等控件的**填充色**请用 [glassFillTint]
 * ——两者数值不同，混用会让控件填充随表面调整一起变透明（暗色下文字可读性下降）。
 */
private fun glassSurfaceTint(dark: Boolean, strong: Boolean = false, soft: Boolean = false): Color = when {
    // 柔光玻璃：近乎全透明的清玻璃——玻璃感靠折射，几乎无奶感
    soft && strong -> if (dark) Color(0xFF0E1420).copy(alpha = 0.10f) else Color.White.copy(alpha = 0.05f)
    soft -> if (dark) Color(0xFF0E1420).copy(alpha = 0.06f) else Color.White.copy(alpha = 0.03f)
    // 磨砂玻璃：明显奶感（Dev 11 起与柔光大幅拉开——磨砂就该像磨砂）
    dark && strong -> Color(0xFF151B2A).copy(alpha = 0.38f)
    dark -> Color(0xFF141A28).copy(alpha = 0.35f)
    strong -> Color.White.copy(alpha = 0.32f)
    else -> Color.White.copy(alpha = 0.30f)
}

/**
 * 控件填充色（对话框/底弹层/输入框/按钮/顶栏滚动态）：与玻璃表面解耦，不随表面调整变动。
 *
 * Dev 11：这些控件都在独立窗口或密集内容之上、**没有 backdrop 采样**（无法折射/模糊），
 * 只能靠填充浓度保证可读性——真机实测半透明填充会让弹窗文字与背景列表文字重叠（Dev 10 反馈）。
 * 同时两档玻璃拉开浓度差：磨砂近实心，柔光保留一定通透但仍在可读线之上。
 */
@Composable
private fun glassFillTint(): Color {
    val dark = isSystemInDarkTheme()
    val soft = LocalGlassStyle.current == GlassStyle.SOFT
    return if (soft) {
        if (dark) Color(0xFF0E1420).copy(alpha = 0.80f) else Color.White.copy(alpha = 0.82f)
    } else {
        if (dark) Color(0xFF101726).copy(alpha = 0.93f) else Color.White.copy(alpha = 0.94f)
    }
}

/**
 * 玻璃表面 Modifier：优先 backdrop 实时折射/模糊；backdrop 为 null（弹窗）时磨砂半透明降级。
 * @param strong 更强的着色与模糊（底栏等需要保证文字可读性的场景）
 * @param backdropOverride 显式指定采样源（悬浮底栏传 [LocalContentBackdrop] 以透视页面真实内容）
 *
 * 安全基线：玻璃元素绝不能处于自己采样源的子树内（见 [GlassRoot] 注释——RenderNode 自引用环
 * 导致 RenderThread 栈溢出）。AGSL（lens/高光）在该前提下真机验证可用。
 *
 * 透明度只由 tint 与 blur 强度控制；高光描边（边框）与投影在两种清晰度下都保留，
 * 柔光档不再去掉边框/投影（Dev 6 的 `if (soft) null` 已移除）。
 */
@Composable
fun Modifier.glassSurface(
    shape: Shape = GlassCardShape,
    strong: Boolean = false,
    refraction: Boolean = true,
    backdropOverride: LayerBackdrop? = null,
): Modifier {
    val backdrop = backdropOverride ?: LocalGlassBackdrop.current
    val dark = isSystemInDarkTheme()
    val soft = LocalGlassStyle.current == GlassStyle.SOFT
    val tint = glassSurfaceTint(dark, strong, soft)
    return if (backdrop != null) {
        drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                if (refraction) {
                    lens(
                        // 边缘折射带加宽、扭曲加深：清玻璃的"玻璃感"主要来自这里
                        refractionHeight = 12.dp.toPx(),
                        refractionAmount = 24.dp.toPx(),
                        depthEffect = true,
                    )
                }
                // 两档玻璃的清晰度分工（Dev 11）：柔光=清玻璃（blur 2px，靠折射看内容）；
                // 磨砂=真磨砂（卡片 8px 奶雾，底栏 strong 4px 兼顾可读性）
                blur((if (soft) 2f else if (strong) 4f else 8f).dp.toPx())
            },
            highlight = { Highlight.Default },
            // 参数化柔影（替代已废弃的 View 体系 loopeer/shadow 的思路）：大半径、低浓度、向下偏移
            shadow = {
                Shadow(
                    radius = 14.dp,
                    offset = DpOffset(0.dp, 4.dp),
                    color = Color.Black.copy(alpha = 0.10f),
                )
            },
            // 内阴影：玻璃内侧的暗边，iOS 液态玻璃的标准质感（Dev 9 新增）
            innerShadow = {
                InnerShadow(
                    radius = 2.dp,
                    offset = DpOffset(0.dp, 1.dp),
                    color = Color.Black.copy(alpha = 0.08f),
                )
            },
            onDrawSurface = { drawRect(tint) },
        )
    } else {
        background(tint, shape).border(0.5.dp, glassBorder(dark), shape)
    }
}

/**
 * 玻璃模式根容器（仅主框架使用）：**分离式双采样源**（1.4.0 Dev 7）。
 *
 * 铁律：玻璃元素绝不能位于自己采样源的子树内——采样层是 Android RenderNode，玻璃元素绘制它、
 * 它又包含玻璃元素，会构成自引用环，HWUI prepareTree 无限递归 → RenderThread 栈溢出
 * 原生崩溃（SIGSEGV，Java 无法捕获，日志导出拿不到）。
 *
 * 引用方向（改本文件前先画一遍，确认无环）：
 * - wallpaperBackdrop ← 页面内玻璃卡片（NcCard 等读 [LocalGlassBackdrop]）
 * - contentBackdrop   ← 悬浮底栏（floatingBar，读 [LocalContentBackdrop]）
 * - contentRN 录制 = 纯色底 + 页面（含卡片）；卡片引用的是**另一个**独立采样源，
 *   且壁纸不在 contentRN 子树内（分离式，刻意不嵌套采样层）→ 无环
 * - floatingBar 在 content 层之后声明，不在 contentRN 子树内 → 无环
 *
 * content 层自带同一块纯色底：页面空白区不会采样到透明，底栏因此不发灰；背景是纯色，
 * 这与"采到壁纸"视觉等价。注意底色必须画在 content 层自身，不能画进 onDrawSurface
 * （那是最后一层，会把采样到的内容影像整个盖掉）。
 */
@Composable
fun GlassRoot(
    modifier: Modifier = Modifier,
    floatingBar: @Composable BoxScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val wallpaperBackdrop = rememberLayerBackdrop()
    val contentBackdrop = rememberLayerBackdrop()
    val dark = isSystemInDarkTheme()
    val background = if (dark) GlassBackgroundColorDark else GlassBackgroundColor
    CompositionLocalProvider(
        LocalGlassBackdrop provides wallpaperBackdrop,
        LocalContentBackdrop provides contentBackdrop,
    ) {
        Box(modifier) {
            // 采样源一：纯色背景（被上层不透明的 content 层遮住，仅作为页面玻璃的采样源存在）
            GlassWallpaper(Modifier.fillMaxSize().layerBackdrop(wallpaperBackdrop))
            // 采样源二：纯色底 + 页面真实内容（供悬浮底栏透视）
            Box(
                Modifier.fillMaxSize().background(background).layerBackdrop(contentBackdrop),
                content = content,
            )
            // 悬浮层：在 content 采样子树之外，安全
            floatingBar()
        }
    }
}

/**
 * 玻璃采样背景：淡蓝纯色（1.4.0 Dev 7 起替代 Dev 6 的四色渐变壁纸）。
 *
 * 纯色缺少可折射的纹理，玻璃感会明显变弱，因此叠一层 alpha ≤ 0.12 的大颗粒径向渐变：
 * 整体仍读作淡蓝纯色，但给 lens/blur 留了内容。不需要纹理时把 blobs 置空即可。
 */
@Composable
fun GlassWallpaper(modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val base = if (dark) GlassBackgroundColorDark else GlassBackgroundColor
    val blobs = if (dark) {
        listOf(
            Color(0xFF2A3F6B).copy(alpha = 0.12f) to Offset(0.18f, 0.12f),
            Color(0xFF1B3350).copy(alpha = 0.10f) to Offset(0.88f, 0.85f),
        )
    } else {
        listOf(
            Color(0xFFBBD5F5).copy(alpha = 0.12f) to Offset(0.18f, 0.12f),
            Color(0xFFCDE0F7).copy(alpha = 0.10f) to Offset(0.88f, 0.85f),
        )
    }
    Canvas(modifier) {
        drawRect(base)
        blobs.forEach { (color, center) ->
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(color, color.copy(alpha = 0f)),
                    center = Offset(center.x * size.width, center.y * size.height),
                    radius = size.minDimension * 0.9f,
                ),
                size = size,
            )
        }
    }
}

// ============================ Nc* 双皮肤容器组件 ============================

/** 卡片：M3 Card / 玻璃卡片（onClick 可选，两模式语义一致） */
@Composable
fun NcCard(
    modifier: Modifier = Modifier,
    shape: Shape = GlassCardShape,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalGlassMode.current) {
        val clickable = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
        Box(
            modifier
                .glassSurface(shape)
                .clip(shape)
                .then(clickable),
        ) {
            Column(content = content)
        }
    } else if (onClick != null) {
        Card(modifier = modifier, shape = shape, onClick = onClick, content = content)
    } else {
        Card(modifier = modifier, shape = shape, content = content)
    }
}

/** 脚手架：玻璃模式下容器透明（露出壁纸），且去掉底部导航栏 inset——内容直达屏幕底部 */
@Composable
fun NcScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    /**
     * 内容区窗口 inset（1.4.0 Dev 13 开放）。默认 = 状态栏+左右（玻璃模式）。
     * **嵌套场景必须传 [WindowInsets.Companion.Zero]**：如历史页，其外层
     * MainScaffold 的 Scaffold 已应用过状态栏 inset，内层再应用一次会双叠加，
     * 页面顶部出现一整块空白（用户反馈"筛选按钮上方很大一片浪费空间"）。
     */
    contentWindowInsets: WindowInsets = WindowInsets.systemBars.only(
        WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
    ),
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    if (LocalGlassMode.current) {
        Scaffold(
            modifier = modifier,
            containerColor = Color.Transparent,
            topBar = topBar,
            floatingActionButton = floatingActionButton,
            snackbarHost = snackbarHost,
            contentWindowInsets = contentWindowInsets,
            content = content,
        )
    } else {
        Scaffold(
            modifier = modifier,
            topBar = topBar,
            floatingActionButton = floatingActionButton,
            snackbarHost = snackbarHost,
            contentWindowInsets = contentWindowInsets,
            content = content,
        )
    }
}

/** 顶栏：玻璃模式下透明容器（浮在壁纸上） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NcTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    if (LocalGlassMode.current) {
        TopAppBar(
            title = title,
            modifier = modifier,
            navigationIcon = navigationIcon,
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = glassFillTint(),
            ),
        )
    } else {
        TopAppBar(title = title, modifier = modifier, navigationIcon = navigationIcon, actions = actions)
    }
}

/** 对话框：玻璃模式下磨砂半透明容器（独立窗口无法采样主窗口，降级磨砂） */
@Composable
fun NcAlertDialog(
    onDismissRequest: () -> Unit,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null,
) {
    if (LocalGlassMode.current) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = title,
            text = text,
            confirmButton = confirmButton,
            dismissButton = dismissButton,
            containerColor = glassFillTint(),
            shape = RoundedRectangle(28.dp, RoundedCornerStyle.Continuous),
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = title,
            text = text,
            confirmButton = confirmButton,
            dismissButton = dismissButton,
        )
    }
}

/** 底部弹层：玻璃模式下磨砂半透明 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NcModalBottomSheet(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalGlassMode.current) {
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            containerColor = glassFillTint(),
            content = content,
        )
    } else {
        ModalBottomSheet(onDismissRequest = onDismissRequest, content = content)
    }
}

/** 输入框：玻璃模式下磨砂填充胶囊 */
@Composable
fun NcOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
    readOnly: Boolean = false,
) {
    if (LocalGlassMode.current) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            label = label,
            placeholder = placeholder,
            trailingIcon = trailingIcon,
            singleLine = singleLine,
            minLines = minLines,
            readOnly = readOnly,
            shape = RoundedRectangle(16.dp, RoundedCornerStyle.Continuous),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = glassFillTint(),
                unfocusedContainerColor = glassFillTint(),
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
            ),
        )
    } else {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            label = label,
            placeholder = placeholder,
            trailingIcon = trailingIcon,
            singleLine = singleLine,
            minLines = minLines,
            readOnly = readOnly,
        )
    }
}

/** 主按钮：玻璃模式下磨砂填充 + 主题色文字 */
@Composable
fun NcFilledButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    if (LocalGlassMode.current) {
        Button(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = glassFillTint(),
                contentColor = MaterialTheme.colorScheme.primary,
            ),
            content = content,
        )
    } else {
        Button(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
    }
}

/** 描边按钮：玻璃模式下磨砂填充 */
@Composable
fun NcOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    if (LocalGlassMode.current) {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = glassFillTint(),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            content = content,
        )
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
    }
}

/** 底部导航项（主框架用） */
data class GlassNavItem(val route: String, val label: String, val icon: ImageVector)

/** 底栏距屏幕底部 */
private val GlassBarBottomMargin = 24.dp

/**
 * 悬浮底栏让位高度（1.4.0 Dev 14）：底栏由 GlassRoot 的 floatingBar 提供，位于
 * Scaffold 之外 → Scaffold 的 Snackbar 不知道它存在，会被底栏盖住（升级后"正在拟合
 * N 条标注…"提示看不见）。Snackbar/浮层用它做底部 padding 上移到底栏之上。
 */
val GlassFloatingBarClearance = GlassBarBottomMargin + 56.dp + 16.dp

/** 悬浮底栏宽度占屏幕宽度的比例（Dev 14：3/5） */
private const val GlassBarWidthFraction = 0.6f

/** Dev 6：底栏外壳高度（REAREye 是 64dp；我们 3/5 屏宽 + 3 个按钮，压到 56dp） */
private val GlassBarHeight = 56.dp

/**
 * Dev 8：选中指示器的静止高度 = 外壳 − 4dp = 52dp。
 *
 * ## 几何推导（解决 Dev 7 的"圆角不对齐"+"放大不明显"两个问题）
 *
 * **同心条件**：两个同心胶囊要视觉对齐，水平内缩必须等于半径差
 * `R1 − R2 = 外壳高/2 − 指示器高/2`。
 *
 * - Dev 7：指示器 44dp → 半径差 6dp，但水平内缩只有 2dp
 *   → 两圆心横向差 4dp，间隙从中间 2dp 渐变到顶部 13.3dp
 *   → 真机图示：右上角贴住外壳、左上角空一大块
 * - Dev 8：指示器 52dp → 半径差 **2dp**，水平内缩也设 **2dp** → **严格同心**
 *
 * 为什么不用"等高 + 内缩 0"（那样也能同心）：等高时按 1.36 放大只能到 76dp，
 * 相对 56dp 外壳要溢出 20dp/9dp 侧，反而过头；且 REAREye 原版指示器
 * 本来就比外壳矮（56 vs 64），保持"静止矮一点"的视觉惯例。
 *
 * ## 放大幅度
 * - Dev 7：44 → 1.36 = 60dp，只比外壳高 4dp（溢出 2dp/侧）→ 看不出"放大"
 * - Dev 8：52 → 1.42 = 74dp，比外壳高 18dp（溢出 9dp/侧）→ 明显
 */
private val GlassIndicatorHeight = 52.dp

/**
 * Dev 8：指示器相对外壳的内缩（水平与垂直同值，保证圆角同心）。
 * 2dp = 半径差 (56/2 − 52/2)。上下同时内缩 2dp，与 item 槽位居中对齐。
 */
private val GlassIndicatorHInset = 2.dp
private val GlassIndicatorVInset = 2.dp

/**
 * Dev 6：底栏 item 的横向内边距。与指示器内缩配合，让图标文字落在胶囊正中。
 */
private val GlassBarItemPaddingH = 2.dp

/**
 * 浮动玻璃底部导航胶囊（2.2.0 Dev 4 起移植 REAREye 三层液态玻璃架构）。
 *
 * ### 与 Dev 10 版本的区别（Dev 10 因效果差移除了滑移胶囊，此处按正确架构重做）
 * Dev 10 只有一个 `if (selected) primary else onSurfaceVariant` 的颜色分支——
 * Boolean 没有中间态，自然没有动画。此版改为 REAREye 的三层结构：
 *
 * ```
 * Box
 * ├── Row  外壳玻璃       drawBackdrop(contentBackdrop) + blur + lens + 指尖光斑
 * ├── Row  重复内容层     alpha(0) + layerBackdrop(tabsBackdrop) + tint(primary)
 * └── Box  选中指示器     translationX = animValue × tabWidth（连续 Float）
 * ```
 *
 * 关键点：
 * - 位置是 [DampedDragAnimation] 的**连续 Float**（0f..tabsCount-1），不是 Int 索引，
 *   所以点击切换有中间帧，渲染出真实滑移；也支持跟手拖拽与甩动形变；
 * - `tabsBackdrop` 录底栏自身内容，指示器同时采样 content + tabs，
 *   于是胶囊内能看到放大的图标内容（iOS 观感）；
 * - 重复内容层 alpha=0 但被录进 tabsBackdrop，按下时它的主色高光透出来。
 *
 * ### RenderNode 无环铁律（改本函数前先画引用图）
 * - tabsBackdrop 只录「重复内容层」，重复内容层不读 tabsBackdrop → 无环
 * - 指示器读 contentBackdrop + tabsBackdrop，但不在 tabsBackdrop 子树内 → 无环
 * - 本函数整体由 [GlassRoot] 的 floatingBar 提供，不在 contentBackdrop 子树内 → 无环
 */
@Composable
fun GlassBottomBar(
    items: List<GlassNavItem>,
    selectedRoute: String,
    onItemClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val dark = isSystemInDarkTheme()
    val soft = LocalGlassStyle.current == GlassStyle.SOFT
    val accent = MaterialTheme.colorScheme.primary

    // 外壳 / 指示器的着色：玻璃档位下随模糊强度收敛，避免叠两层后过浓
    val shellTint = glassSurfaceTint(dark, strong = true, soft = soft)
    val indicatorShadowAlpha = if (soft) 0.08f else 0.14f

    val selectedIndex = items.indexOfFirst { it.route == selectedRoute }.coerceAtLeast(0)
    var currentIndex by remember { mutableIntStateOf(selectedIndex) }

    // Dev 4：录底栏自身内容（重复内容层），供选中指示器折射
    val tabsBackdrop = rememberLayerBackdrop()
    // 底栏由 GlassRoot 的 floatingBar 提供，正常一定有 contentBackdrop；
    // 为 null（脱离 GlassRoot 使用）时降级为壁纸源，保证不崩
    val contentBackdrop = LocalContentBackdrop.current ?: LocalGlassBackdrop.current

    var tabWidthPx by remember { mutableFloatStateOf(0f) }
    var totalWidthPx by remember { mutableFloatStateOf(0f) }

    // 拖动时底栏整体的反向位移（挤压反馈），最大 4dp
    val offsetAnimation = remember { Animatable(0f) }
    val panelOffset by remember(density) {
        derivedStateOf {
            if (totalWidthPx == 0f) 0f else {
                val fraction = (offsetAnimation.value / totalWidthPx).fastCoerceIn(-1f, 1f)
                with(density) { 4f.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction)) }
            }
        }
    }

    class Holder { var instance: DampedDragAnimation? = null }
    val holder = remember { Holder() }

    val drag = remember(scope, items.size, density, isLtr) {
        DampedDragAnimation(
            animationScope = scope,
            initialValue = selectedIndex.toFloat(),
            valueRange = 0f..(items.size - 1).toFloat(),
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            // Dev 8：静止 52dp → 按下 1.42 倍 = 74dp，比外壳 56dp 高 18dp
            // （上下各溢出 9dp）→ "点击后变宽并超出底栏上下一点点"清晰可见。
            // （Dev 7 是 44→60dp，只溢出 2dp/侧，所以看不出放大效果）
            pressedScale = 1.42f,
            canDrag = { offset ->
                val anim = holder.instance ?: return@DampedDragAnimation true
                if (tabWidthPx == 0f) return@DampedDragAnimation false
                val indicatorX = anim.value * tabWidthPx
                val padding = with(density) { GlassIndicatorHInset.toPx() }
                val globalTouchX = if (isLtr) {
                    padding + indicatorX + offset.x
                } else {
                    totalWidthPx - padding - tabWidthPx - indicatorX + offset.x
                }
                globalTouchX in 0f..totalWidthPx
            },
            onDragStarted = {},
            onDragStopped = {
                val target = targetValue.fastRoundToInt().fastCoerceIn(0, items.size - 1)
                currentIndex = target
                animateToValue(target.toFloat())
                scope.launch {
                    offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                }
            },
            onDrag = { _, dragAmount ->
                if (tabWidthPx > 0) {
                    updateValue(
                        (targetValue + dragAmount.x / tabWidthPx * if (isLtr) 1f else -1f)
                            .fastCoerceIn(0f, (items.size - 1).toFloat())
                    )
                    scope.launch { offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x) }
                }
            },
        ).also { holder.instance = it }
    }

    // 外部选中变化（页面导航/深链）→ 滑过去
    LaunchedEffect(selectedIndex) {
        if (currentIndex != selectedIndex) {
            currentIndex = selectedIndex
            drag.animateToValue(selectedIndex.toFloat())
        }
    }
    // 拖动/点击松手落到新索引 → 回调切换页面
    LaunchedEffect(currentIndex) {
        if (items.getOrNull(currentIndex)?.route != selectedRoute) {
            onItemClick(items[currentIndex].route)
        }
    }

    val interactiveHighlight = remember(scope, tabWidthPx, isLtr) {
        InteractiveHighlight(
            animationScope = scope,
            position = { size, _ ->
                androidx.compose.ui.geometry.Offset(
                    if (isLtr) (drag.value + 0.5f) * tabWidthPx + panelOffset
                    else size.width - (drag.value + 0.5f) * tabWidthPx + panelOffset,
                    size.height / 2f,
                )
            },
        )
    }

    Box(
        modifier
            .fillMaxWidth()
            .padding(bottom = GlassBarBottomMargin),
        contentAlignment = Alignment.Center,
    ) {
        // 底栏坐标系容器：宽度 = 屏宽 × 3/5（Dev 14 定的比例，勿改回满屏）。
        // 三层（外壳 / 重复内容层 / 指示器）必须同在此容器内 —— 指示器的
        // translationX = drag.value × tabWidth 是相对本容器原点的位移，
        // 放进满屏的父 Box 会导致指示器与外壳错位。
        Box(Modifier.fillMaxWidth(GlassBarWidthFraction)) {
        if (contentBackdrop == null) {
            // 降级路径：脱离 GlassRoot 使用时无采样源，只画纯色胶囊（动画逻辑仍完整）
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(ContinuousCapsule)
                    .background(shellTint, ContinuousCapsule)
                    .height(GlassBarHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEach { item ->
                    val selected = item.route == selectedRoute
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(ContinuousCapsule)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onItemClick(item.route) },
                            ),
                        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                    ) {
                        Icon(
                            item.icon,
                            contentDescription = item.label,
                            tint = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            item.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
            return@Box
        }

        // ---------- 第 1 层：外壳玻璃胶囊 ----------
        Row(
            Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coords ->
                    totalWidthPx = coords.size.width.toFloat()
                    // Dev 8：槽宽 = (外壳内容区宽) / 3；外壳 padding 与指示器内缩
                    // 相同（各 2dp），所以内容区宽 = 总宽 − 4dp
                    val inset = with(density) { (GlassIndicatorHInset * 2).toPx() }
                    tabWidthPx = (totalWidthPx - inset) / items.size
                }
                .graphicsLayer { translationX = panelOffset }
                .drawBackdrop(
                    backdrop = contentBackdrop,
                    shape = { ContinuousCapsule },
                    effects = {
                        vibrancy()
                        // 外壳 blur 比卡片轻：底栏下方的内容要能"透"出来，不是一坨奶雾
                        blur((if (soft) 2f else 4f).dp.toPx())
                        lens(12f.dp.toPx(), 12f.dp.toPx())
                    },
                    highlight = { Highlight.Default },
                    shadow = {
                        Shadow(
                            radius = 12.dp,
                            offset = DpOffset(0.dp, 3.dp),
                            color = Color.Black.copy(alpha = 0.10f),
                        )
                    },
                    layerBlock = {
                        // 按下时底栏整体轻微膨胀（比 REAREye 保守：我们的胶囊更小）
                        val progress = drag.pressProgress
                        val s = lerp(1f, 1f + 6f.dp.toPx() / size.width, progress)
                        scaleX = s
                        scaleY = s
                    },
                    onDrawSurface = { drawRect(shellTint) },
                )
                .then(interactiveHighlight.modifier)
                .height(GlassBarHeight)
                // Dev 8：水平内缩 = 指示器内缩（2dp），保证两者同心；
                // item 自身另有 padding(horizontal = GlassBarItemPaddingH) 防贴边
                .padding(
                    horizontal = GlassIndicatorHInset,
                    vertical = GlassIndicatorVInset,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                val selected = item.route == selectedRoute
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(ContinuousCapsule)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onItemClick(item.route) },
                        )
                        .padding(horizontal = GlassBarItemPaddingH),
                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                ) {
                    Icon(
                        item.icon,
                        contentDescription = item.label,
                        modifier = Modifier.graphicsLayer {
                            // 选中项轻微放大，与指示器滑移叠加出"被拾起"的层次
                            val s = if (selected) 1.08f else 1f
                            scaleX = s
                            scaleY = s
                        },
                        tint = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        item.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }

        // ---------- 第 2 层：重复内容层（按下时透出主色光） ----------
        Row(
            Modifier
                .fillMaxWidth()
                .clearAndSetSemantics {}
                .alpha(0f)
                .layerBackdrop(tabsBackdrop)
                .graphicsLayer { translationX = panelOffset }
                .drawBackdrop(
                    backdrop = contentBackdrop,
                    shape = { ContinuousCapsule },
                    effects = {
                        val progress = drag.pressProgress
                        vibrancy()
                        blur(4f.dp.toPx())
                        // lens 半径随按下进度增长 → 按下时边缘"张开"
                        lens(10f.dp.toPx() * progress, 14f.dp.toPx() * progress)
                    },
                    highlight = { Highlight.Default.copy(alpha = drag.pressProgress) },
                    onDrawSurface = { drawRect(shellTint) },
                )
                .height(GlassBarHeight)
                .padding(
                    horizontal = GlassIndicatorHInset,
                    vertical = GlassIndicatorVInset,
                )
                .graphicsLayer(colorFilter = ColorFilter.tint(accent)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(horizontal = GlassBarItemPaddingH),
                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                ) {
                    Icon(item.icon, contentDescription = null, tint = Color.White)
                    Text(
                        item.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        maxLines = 1,
                    )
                }
            }
        }

        // ---------- 第 3 层：选中指示器 ----------
        if (tabWidthPx > 0f) {
            Box(
                Modifier
                    .graphicsLayer {
                        val offset = drag.value * tabWidthPx
                        translationX = if (isLtr) offset + panelOffset else -offset + panelOffset
                    }
                    // Dev 8：内缩 2dp（= 半径差，保证与外壳圆角同心）。
                    // padding 在 graphicsLayer 之后：只影响布局，不参与位移计算。
                    .padding(
                        horizontal = GlassIndicatorHInset,
                        vertical = GlassIndicatorVInset,
                    )
                    .then(interactiveHighlight.gestureModifier)
                    .then(drag.modifier)
                    .drawBackdrop(
                        backdrop = rememberCombinedBackdrop(
                            contentBackdrop,
                            tabsBackdrop,
                        ),
                        shape = { ContinuousCapsule },
                        effects = {
                            // 折射只在按下时出现（REAREye 原版：lens 半径 × pressProgress）。
                            // 静止态不给 lens，指示器才是"透明玻璃罩住内容"而不是白色奶块。
                            val progress = drag.pressProgress
                            lens(10f.dp.toPx() * progress, 14f.dp.toPx() * progress, true)
                        },
                        highlight = {
                            // 同理：高光随按下淡入，静止态不描边
                            Highlight.Default.copy(
                                alpha = drag.pressProgress * (if (soft) 0.35f else 0.55f),
                            )
                        },
                        shadow = {
                            // 投影是"按下时抬起"的暗示，静止态为 0
                            Shadow(
                                radius = 10.dp,
                                offset = DpOffset(0.dp, 3.dp),
                                color = Color.Black.copy(
                                    alpha = indicatorShadowAlpha * drag.pressProgress,
                                ),
                            )
                        },
                        innerShadow = {
                            InnerShadow(
                                radius = 6.dp * drag.pressProgress,
                                color = Color.Black.copy(alpha = 0.10f * drag.pressProgress),
                            )
                        },
                        layerBlock = {
                            // 按下放大 + 速度形变（甩得越快横向拉得越长），是"液体感"的来源
                            scaleX = drag.scaleX
                            scaleY = drag.scaleY
                            val v = drag.velocity / 10f
                            scaleX /= 1f - (v * 0.4f).fastCoerceIn(-0.2f, 0.2f)
                            scaleY *= 1f - (v * 0.15f).fastCoerceIn(-0.2f, 0.2f)
                        },
                        onDrawSurface = {
                            // 关键：静止态是极淡的黑/白罩（10%），按下时**淡出**让位给折射。
                            // 早前误用 30% 主色填充 → 指示器成白色奶块，盖住了底栏内容。
                            val progress = drag.pressProgress
                            drawRect(
                                color = if (dark) Color.White.copy(0.10f) else Color.Black.copy(0.10f),
                                alpha = 1f - progress,
                            )
                            drawRect(Color.Black.copy(alpha = 0.03f * progress))
                        },
                    )
                    .height(GlassIndicatorHeight)
                    .width(with(density) { tabWidthPx.toDp() }),
            )
        }
        }
    }
}
