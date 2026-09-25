package cc.ytdttj.noticleaner.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cc.ytdttj.noticleaner.ServiceLocator
import cc.ytdttj.noticleaner.data.db.DECISION_CONVERSATION
import cc.ytdttj.noticleaner.data.db.DECISION_FILTERED_BY_AI
import cc.ytdttj.noticleaner.data.db.DECISION_FILTERED_BY_AI_MODULE
import cc.ytdttj.noticleaner.data.db.DECISION_FILTERED_BY_RULE
import cc.ytdttj.noticleaner.data.db.DECISION_FILTERED_BY_RULE_MODULE
import cc.ytdttj.noticleaner.data.db.DECISION_MANUAL_MARKED_AD
import cc.ytdttj.noticleaner.data.db.DECISION_MEDIA
import cc.ytdttj.noticleaner.data.db.DECISION_ONGOING
import cc.ytdttj.noticleaner.data.db.DECISION_WHITELIST
import cc.ytdttj.noticleaner.data.db.NotificationEntity
import cc.ytdttj.noticleaner.notify.KeepAliveManager
import cc.ytdttj.noticleaner.ui.rules.AppPickerSession
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// 1.3.2（P3-6）：SimpleDateFormat（非线程安全）→ java.time DateTimeFormatter（不可变）
private val timeFmt = DateTimeFormatter.ofPattern("MM-dd HH:mm")

/** internal：统计明细页（Dev 14 起可点开详情）复用同一时间格式 */
internal fun formatTime(epochMs: Long): String =
    timeFmt.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

/** 日期分组头格式（Dev 6）："9月25日" */
private val dayFmt = DateTimeFormatter.ofPattern("M月d日")

private fun dayOf(epochMs: Long): java.time.LocalDate =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(vm: HistoryViewModel = viewModel(factory = vmFactory()), onOpenAppPicker: () -> Unit = {}) {
    val list by vm.list.collectAsState()
    val filter by vm.filter.collectAsState()
    val search by vm.search.collectAsState()
    val selected by vm.selected.collectAsState()
    val toast by vm.toast.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var hadData by remember { mutableStateOf(false) }

    // 修复：打开页面/切换筛选/首次加载后定位到最新通知（列表顶部）
    LaunchedEffect(list.isEmpty(), filter) {
        if (list.isNotEmpty() && !hadData) {
            listState.scrollToItem(0)
        }
        hadData = list.isNotEmpty()
    }

    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it)
            vm.clearToast()
        }
    }

    // Dev 14：玻璃模式下悬浮底栏位于 Scaffold 之外，Snackbar 默认贴底会被它盖住
    // （升级后"正在拟合 N 条标注…"看不见）→ 底部让位一个底栏高度
    val snackbarBottomPadding =
        if (cc.ytdttj.noticleaner.ui.LocalGlassMode.current) {
            cc.ytdttj.noticleaner.ui.glass.GlassFloatingBarClearance
        } else {
            0.dp
        }

    // 1.4.0 Dev 13：外层 MainScaffold 已应用状态栏 inset，此处必须清零，
    // 否则顶部 inset 双叠加 → 筛选按钮上方一大片空白
    cc.ytdttj.noticleaner.ui.glass.NcScaffold(
        snackbarHost = {
            Box(Modifier.padding(bottom = snackbarBottomPadding)) { SnackbarHost(snackbar) }
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HistoryFilter.entries.forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { vm.setFilter(f) },
                        label = {
                            Text(
                                when (f) {
                                    HistoryFilter.ALL -> "全部"
                                    HistoryFilter.FILTERED -> "已过滤"
                                    HistoryFilter.PASSED -> "正常"
                                },
                            )
                        },
                    )
                }
            }
            var advOpen by remember { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.OutlinedTextField(
                    value = search,
                    onValueChange = { vm.search.value = it },
                    placeholder = { Text("搜索 App / 标题 / 内容") },
                    singleLine = true,
                    modifier = Modifier.weight(1f).height(56.dp),
                )
                AdvancedFilterButton(vm, onOpenAppPicker, advOpen) { advOpen = !advOpen }
            }
            if (advOpen) {
                AdvancedFilterPanel(vm, onOpenAppPicker)
            }
            Spacer(Modifier.height(4.dp))
            if (list.isEmpty()) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(if (search.isBlank()) "暂无通知记录" else "无匹配结果", style = MaterialTheme.typography.titleMedium)
                    if (search.isBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text("请先在系统设置中授予通知监听权限", style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                ) {
                    // Dev 6：日期分组头——列表从新到旧，相邻两条日期不同时插入"9月25日"分隔
                    itemsIndexed(list, key = { _, n -> n.id }) { i, n ->
                        val day = dayOf(n.postTime)
                        if (i == 0 || dayOf(list[i - 1].postTime) != day) {
                            DateHeader(day)
                        }
                        NotificationCard(n) { vm.select(n) }
                    }
                }
            }
        }

        selected?.let { n ->
            cc.ytdttj.noticleaner.ui.glass.NcModalBottomSheet(onDismissRequest = { vm.select(null) }) {
                NotificationDetail(
                    n = n,
                    onJumpChannel = {
                        KeepAliveManager.openChannelSettings(context, n.packageName, n.channelId)
                    },
                    onLearn = { label -> vm.learn(n, label) },
                    onUnlearn = { vm.unlearn(n) },
                )
            }
        }
    }
}

/** APP 图标：密度感知像素尺寸 + 全局 LruCache（键含尺寸）；自适应图标裁剪安全区；失败时圆底 + 首字符占位 */
private val iconCache = android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(192)

/** 1.2.3：图标渲染失败包的负缓存——MIUI RRO idmap 缓存损坏时 getApplicationIcon 抛 IOException，
 *  不缓存会导致每次重组重试失败路径（系统日志刷屏 + 列表卡顿），见诊断日志 20260918 */
private val failedIconKey = android.util.LruCache<String, Boolean>(192)

@Composable
fun AppIcon(packageName: String, size: Int = 40, fallbackText: String = packageName) {
    val context = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    // 1.1.7：按屏幕密度生成物理像素位图，避免 40px 位图在 3x 屏上被拉伸导致模糊
    val sizePx = (size * density).toInt().coerceAtLeast(8)
    // 1.3.2（P3-1）：渲染挪 IO 线程——缓存/负缓存命中时同步返回；未命中先出占位圆底，
    // 异步 getApplicationIcon + 绘制完成后重组替换，首次出现某包名不再阻塞组合
    val bmp by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        null, packageName, sizePx,
    ) {
        val cacheKey = "$packageName:$sizePx"
        iconCache.get(cacheKey)?.let {
            value = it
            return@produceState
        }
        if (failedIconKey.get(packageName) == true) return@produceState // 已知失败包：直接占位
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val rendered = renderAppIcon(context, packageName, sizePx)
            if (rendered != null) {
                iconCache.put(cacheKey, rendered)
            } else {
                failedIconKey.put(packageName, true)
            }
            rendered
        }
    }
    val rendered = bmp
    if (rendered != null) {
        androidx.compose.foundation.Image(
            bitmap = rendered,
            contentDescription = null,
            modifier = Modifier.width(size.dp).height(size.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(size.dp / 4)),
        )
    } else {
        // 占位：浅色圆底 + APP 名首字符（1.1.7：补背景色，避免孤零零一个字母的观感）
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .width(size.dp)
                .height(size.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                fallbackText.take(1).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * drawable → 位图（1.1.7 重写）：
 * - 自适应图标（AdaptiveIconDrawable）：108 视口中心 72 安全区放大到目标尺寸（启动器同款），
 *   修复此前整幅视口塞进 bounds 导致图标偏小、四周带底色的问题
 * - 其余 drawable：按边界绘制
 * - 统一圆角裁剪，与占位风格一致
 */
private fun renderAppIcon(
    context: android.content.Context,
    packageName: String,
    sizePx: Int,
): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
    val d = context.packageManager.getApplicationIcon(packageName).mutate()
    val bmp = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    // 圆角裁剪（先于任何缩放变换，作用于整个位图）
    canvas.clipPath(
        android.graphics.Path().apply {
            addRoundRect(
                android.graphics.RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()),
                sizePx / 4f, sizePx / 4f,
                android.graphics.Path.Direction.CW,
            )
        },
    )
    if (d is android.graphics.drawable.AdaptiveIconDrawable) {
        // 安全区 [18,90] 映射到 [0,sizePx]：p' = (p-18) * (sizePx/72)
        val scale = sizePx / 72f
        canvas.scale(scale, scale)
        canvas.translate(-18f, -18f)
        d.setBounds(0, 0, 108, 108)
    } else {
        d.setBounds(0, 0, sizePx, sizePx)
    }
    d.draw(canvas)
    bmp.asImageBitmap()
}.getOrNull()

@Composable
private fun NotificationCard(n: NotificationEntity, onClick: () -> Unit) {
    cc.ytdttj.noticleaner.ui.glass.NcCard(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AppIcon(n.packageName, 40, fallbackText = n.appName)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(n.appName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(formatTime(n.postTime), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.weight(1f))
                    when (n.decision) {
                        DECISION_FILTERED_BY_AI, DECISION_FILTERED_BY_AI_MODULE ->
                            Badge { Text("AI过滤 ${(n.adProbability * 100).toInt()}%") }
                        DECISION_FILTERED_BY_RULE, DECISION_FILTERED_BY_RULE_MODULE -> Badge { Text("规则过滤") }
                        DECISION_MANUAL_MARKED_AD -> Badge { Text("已学习广告") }
                        DECISION_WHITELIST -> Text("白名单", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        DECISION_MEDIA -> Text("媒体", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                        DECISION_CONVERSATION -> Text("会话", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                        DECISION_ONGOING -> Text("常驻", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    if (n.learned) {
                        Spacer(Modifier.width(4.dp))
                        Text("已学习", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                }
                if (n.title.isNotEmpty()) {
                    Text(n.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (n.content.isNotEmpty()) {
                    Text(n.content, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                // 右下角：简单广告率（按阈值区间着色）
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val pct = (n.adProbability * 100).toInt()
                    Text(
                        "广告率 $pct%",
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            n.adProbability >= 0.8f -> MaterialTheme.colorScheme.error
                            n.adProbability >= 0.5f -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.outline
                        },
                    )
                }
            }
        }
    }
}

/**
 * 高级筛选（Dev 6）：搜索框右侧的"筛选"按钮 + 展开面板。
 * App 选择复用规则页的 AppPickerScreen（全量应用列表，含系统应用），
 * 结果经 AppPickerSession 单例回读（与 RuleEditScreen 同款机制）。
 */
private fun advancedActiveCount(adv: HistoryAdvancedFilter): Int {
    var c = 0
    if (adv.apps.isNotEmpty()) c++
    if (adv.learnedOnly) c++
    if (adv.startDate != null) c++
    if (adv.endDate != null) c++
    return c
}

@Composable
private fun AdvancedFilterButton(
    vm: HistoryViewModel,
    onOpenAppPicker: () -> Unit,
    open: Boolean,
    onToggle: () -> Unit,
) {
    val adv by vm.advancedFilter.collectAsState()
    // 进入历史页时回读选择器结果（从 AppPicker 返回后本组合重建，LaunchedEffect 重跑）
    androidx.compose.runtime.LaunchedEffect(Unit) {
        AppPickerSession.result?.let { result ->
            vm.setAdvancedFilter(vm.advancedFilter.value.copy(apps = result))
            AppPickerSession.result = null
        }
    }
    val active = advancedActiveCount(adv)
    FilterChip(
        selected = open || active > 0,
        onClick = onToggle,
        label = { Text(if (active > 0) "筛选($active)" else "筛选") },
    )
}

@Composable
private fun AdvancedFilterPanel(vm: HistoryViewModel, onOpenAppPicker: () -> Unit) {
    val adv by vm.advancedFilter.collectAsState()
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AssistChip(
                onClick = {
                    // 复用规则页 AppPicker（含系统应用），结果经 AppPickerSession 回读
                    cc.ytdttj.noticleaner.ui.rules.AppPickerSession.initial = adv.apps
                    onOpenAppPicker()
                },
                label = {
                    Text(
                        if (adv.apps.isEmpty()) "全部 App"
                        else "App(${adv.apps.size})",
                    )
                },
            )
            FilterChip(
                selected = adv.learnedOnly,
                onClick = { vm.setAdvancedFilter(adv.copy(learnedOnly = !adv.learnedOnly)) },
                label = { Text("已学习") },
            )
            if (advancedActiveCount(adv) > 0) {
                TextButton(onClick = { vm.setAdvancedFilter(HistoryAdvancedFilter()) }) { Text("清除") }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DateField(
                label = "开始时间",
                value = adv.startDate,
                onUpdate = { vm.setAdvancedFilter(adv.copy(startDate = it)) },
                modifier = Modifier.weight(1f),
            )
            DateField(
                label = "结束时间",
                value = adv.endDate,
                onUpdate = { vm.setAdvancedFilter(adv.copy(endDate = it)) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 日期选择字段（Dev 6 调整）：点击弹出 M3 日历（DatePickerDialog），不再手动输入。
 * 注意 DatePicker 用 UTC 毫秒——LocalDate 与 millis 互转必须走 UTC 日界，否则差一天。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(label: String, value: java.time.LocalDate?, onUpdate: (java.time.LocalDate?) -> Unit, modifier: Modifier = Modifier) {
    var showPicker by remember { mutableStateOf(false) }
    Box(modifier) {
        androidx.compose.material3.OutlinedTextField(
            value = value?.toString().orEmpty(),
            onValueChange = {},
            label = { Text(label) },
            placeholder = { Text("点选日期") },
            readOnly = true,
            trailingIcon = {
                androidx.compose.material3.IconButton(onClick = { showPicker = true }) {
                    androidx.compose.material3.Icon(
                        Icons.Filled.DateRange,
                        contentDescription = "选择日期",
                    )
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        )
        // 透明覆盖层：整个字段可点（readOnly TextField 自身不吃点击）
        Box(
            Modifier.matchParentSize().clickable { showPicker = true },
        )
    }
    if (showPicker) {
        val state = androidx.compose.material3.rememberDatePickerState(
            initialSelectedDateMillis = value?.toEpochDay()?.times(86_400_000L),
        )
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedDateMillis != null,
                    onClick = {
                        // DatePicker 内部按 UTC 日界取整日，回读必须走 UTC
                        onUpdate(
                            state.selectedDateMillis?.let { ms ->
                                java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.of("UTC")).toLocalDate()
                            },
                        )
                        showPicker = false
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { onUpdate(null); showPicker = false }) { Text("清除") }
            },
        ) {
            androidx.compose.material3.DatePicker(state = state)
        }
    }
}

/** 日期分组头：居中日期文本（列表从新到旧，每天插入一个） */
@Composable
private fun DateHeader(day: java.time.LocalDate) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        androidx.compose.material3.HorizontalDivider(Modifier.weight(1f))
        Text(
            dayFmt.format(day),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
        )
        androidx.compose.material3.HorizontalDivider(Modifier.weight(1f))
    }
}

/**
 * 通知详情弹层内容（internal：Dev 14 起统计明细页点击条目也复用它，
 * 学习/取消学习交互与历史页完全一致）。
 */
@Composable
internal fun NotificationDetail(
    n: NotificationEntity,
    onJumpChannel: () -> Unit,
    onLearn: (Int) -> Unit,
    onUnlearn: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        if (n.title.isNotEmpty()) {
            Text(n.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Text(n.content.ifEmpty { "（无正文）" }, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        Text("发送时间：${formatTime(n.postTime)}", style = MaterialTheme.typography.bodySmall)
        Text(
            // 1.2.3：label 解析失败时 appName 即包名，避免重复显示两次
            if (n.appName == n.packageName) "APP：${n.packageName}"
            else "APP：${n.appName} (${n.packageName})",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("发送通道：${n.channelId.ifEmpty { "（默认/未知）" }}", style = MaterialTheme.typography.bodySmall)
        Text("AI 判定：广告概率 ${(n.adProbability * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(16.dp))
        Button(onClick = onJumpChannel, Modifier.fillMaxWidth()) {
            Text("跳转到该通道设置")
        }
        Spacer(Modifier.height(8.dp))
        // 1.1.11：已学习的通知也允许再次点击学习（同方向重复点击累积权重）；随时可取消学习
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val adText = when {
                n.learned && n.learnLabel == 1 -> "再学一次广告(${n.learnCount + 1})"
                else -> "广告通知"
            }
            val normalText = when {
                n.learned && n.learnLabel == 0 -> "再学一次正常(${n.learnCount + 1})"
                else -> "正常通知"
            }
            Button(onClick = { onLearn(1) }, Modifier.weight(1f)) { Text(adText) }
            cc.ytdttj.noticleaner.ui.glass.NcOutlinedButton(onClick = { onLearn(0) }, Modifier.weight(1f)) { Text(normalText) }
        }
        if (n.learned) {
            Spacer(Modifier.height(8.dp))
            AssistChip(onClick = onUnlearn, label = { Text("取消学习") })
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
internal fun vmFactory(): androidx.lifecycle.ViewModelProvider.Factory =
    androidx.lifecycle.viewmodel.viewModelFactory {
        initializer {
            HistoryViewModel(ServiceLocator.db.notificationDao(), ServiceLocator.modelRepo)
        }
    }
