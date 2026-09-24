package cc.ytdttj.noticleaner.ui.glass

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import cc.ytdttj.noticleaner.ui.GlassStyle
import cc.ytdttj.noticleaner.ui.LocalGlassMode
import cc.ytdttj.noticleaner.ui.LocalGlassStyle
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedCornerStyle
import com.kyant.shapes.RoundedRectangle
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.unit.DpOffset

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

/** 底栏内边距 */
private val GlassBarItemPaddingH = 10.dp
private val GlassBarItemPaddingV = 6.dp

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

/**
 * 浮动玻璃底部导航胶囊（Dev 10 起去掉选中包裹胶囊）：
 * 选中态仅靠图标/文字变色（dynamic color 主色）表达，底栏本体实时透视页面滚动内容。
 */
@Composable
fun GlassBottomBar(
    items: List<GlassNavItem>,
    selectedRoute: String,
    onItemClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Dev 14：外层 Box 负责居中 + 底部留白；底栏本体宽度 = 屏幕宽度 × 3/5（原先是
    // wrapContent，三个按钮挤在一起、整体偏短），按钮间用 SpaceEvenly 均分间距。
    Box(
        modifier
            .fillMaxWidth()
            .padding(bottom = GlassBarBottomMargin),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .fillMaxWidth(GlassBarWidthFraction)
                // 采样页面真实内容（而非壁纸）：滚到底栏下方的列表项会被实时模糊/折射进底栏。
                // 底栏由 GlassRoot 的 floatingBar 提供，位于 content 采样子树之外 → 无自引用环。
                .glassSurface(
                    Capsule(),
                    strong = true,
                    backdropOverride = LocalContentBackdrop.current,
                )
                .clip(Capsule())
                .padding(horizontal = GlassBarItemPaddingH, vertical = GlassBarItemPaddingV),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                val selected = item.route == selectedRoute
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(Capsule())
                        .clickable { onItemClick(item.route) }
                        .padding(horizontal = 18.dp, vertical = 6.dp),
                ) {
                    Icon(
                        item.icon,
                        contentDescription = item.label,
                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        item.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
