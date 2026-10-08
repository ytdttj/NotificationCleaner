package cc.ytdttj.noticleaner.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatorMutex
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 底栏选中指示器的多通道动画状态机（移植自 REAREye `DampedDragAnimation.kt`）。
 *
 * 六个并行 [Animatable] 通道：
 * - [value]：指示器位置，值域 `0f..(tabsCount-1)` **连续**，渲染时 ×tabWidth 得到像素
 * - [pressProgress]：按下进度 0..1，驱动 lens 强度/高光/投影/内阴影
 * - [scaleX] / [scaleY]：按下时的横向拉伸与纵向压缩（分开才能做出"挤压"感）
 * - [velocity]：归一化速度，驱动甩动时的拉伸形变，松手后弹簧衰减到 0
 *
 * 三处必须照搬的实现细节（否则观感明显退化）：
 * 1. [updateValue] **故意不加** [mutatorMutex]——拖动时每帧发起新 `animateTo`，
 *    `Animatable` 单值语义会自动取消旧动画，这是"跟手"的来源（加了会排队追赶）；
 * 2. [animateToValue] 才加锁，因为它要把 press / 滑移 / release 组合成一次原子流程；
 * 3. [release] 里先 `awaitFrame()` 再等 [value] 到位才回弹——少了这个等待，
 *    松手瞬间就会"回弹"与"滑移"割裂。
 */
class DampedDragAnimation(
    private val animationScope: CoroutineScope,
    val initialValue: Float,
    val valueRange: ClosedRange<Float>,
    val visibilityThreshold: Float,
    val initialScale: Float,
    val pressedScale: Float,
    val canDrag: (Offset) -> Boolean = { true },
    val onDragStarted: DampedDragAnimation.(position: Offset) -> Unit,
    val onDragStopped: DampedDragAnimation.() -> Unit,
    val onDrag: DampedDragAnimation.(size: IntSize, dragAmount: Offset) -> Unit,
) {

    /**
     * Dev 6 弹簧参数调整。
     *
     * REAREye 原版位置弹簧是 `spring(1f, 1000f)`——阻尼比 1.0（临界阻尼）+ 高刚度，
     * 滑移约 200ms 就到位。在 64dp 的大底栏上这段距离够长，能看见"变宽再收回"；
     * 我们底栏 56dp、指示器行程约 1/3 屏宽，原参数下"按下变大"只有两三帧，
     * 视觉上就是"没动画，直接跳过去"。
     *
     * 改为刚度 420、阻尼比 0.82：行程拉长到约 350ms 且尾段有极轻微回弹，
     * 变大过程清晰可见；阻尼比从 1.0 降到 0.82 是为了避免"完全机械匀速"的死板感。
     * 代价是拖动跟手性略降（位置动画滞后略增），但 updateValue 是每帧打断重定向，
     * 实际拖动仍然跟手（见 updateValue 的注释）。
     */
    private val valueAnimationSpec = spring(0.82f, 420f, visibilityThreshold)
    private val velocityAnimationSpec = spring(0.5f, 300f, visibilityThreshold * 10f)

    // 按下进度：比位置稍慢，让"变宽"先于"滑移"被看到（0.82f/520f）
    private val pressProgressAnimationSpec = spring(0.82f, 520f, 0.001f)

    /**
     * Dev 8 缩放参数重做 —— 解决"切换时胶囊放大不明显"。
     *
     * 上一版 `spring(0.55f, 420f)` / `spring(0.62f, 420f)` 有两个问题：
     * 1. **阻尼比 0.55/0.62 欠阻尼太狠**：scale 会来回振荡好几下，在 350ms 的
     *    滑移窗口里正负抵消，视觉上就是"没放大"；
     * 2. **刚度 420 太快达峰**（约 150ms），而 press() 后紧接着就启动位置滑移，
     *    峰值还没被"看清"就进入了回落段。
     *
     * 改为阻尼比 0.9（接近临界阻尼，单调上升不回弹）、刚度 260（达峰约 260ms，
     * 与位置滑移的 350ms 时长匹配，峰值能被完整看到），配合更大的 pressedScale。
     */
    private val scaleXAnimationSpec = spring(0.9f, 260f, 0.001f)
    private val scaleYAnimationSpec = spring(0.92f, 260f, 0.001f)

    private val valueAnimation = Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation = Animatable(0f, 5f)
    private val pressProgressAnimation = Animatable(0f, 0.001f)
    private val scaleXAnimation = Animatable(initialScale, 0.001f)
    private val scaleYAnimation = Animatable(initialScale, 0.001f)

    private val mutatorMutex = MutatorMutex()
    private val velocityTracker = VelocityTracker()

    /**
     * Dev 6：press 世代号。
     *
     * 点一次 tab 会走两条路径：`inspectDragGestures` 的 onDragEnd → release()，
     * 以及 onDragStopped → animateToValue() 内部的 release()。两者都会
     * "回缩 pressProgress / scale"，若不加守卫，先到的 release 会在滑移
     * 还没跑完时就把胶囊缩回去 —— 视觉上就是"滑动一顿一顿"。
     *
     * 每次 [press] 自增；[release] 记住自己启动时的世代号，等待期间若世代已变
     * 说明有更新的 press 接管了，本次的回缩直接放弃（由新 press 的 release 负责）。
     */
    private var pressGeneration = 0

    val value: Float get() = valueAnimation.value
    val targetValue: Float get() = valueAnimation.targetValue
    val pressProgress: Float get() = pressProgressAnimation.value
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value
    val velocity: Float get() = velocityAnimation.value

    val modifier: Modifier = Modifier.pointerInput(Unit) {
        inspectDragGestures(
            onDragStart = { down ->
                onDragStarted(down.position)
                press()
            },
            onDragEnd = {
                onDragStopped()
                release()
            },
            onDragCancel = {
                onDragStopped()
                release()
            },
        ) { change, dragAmount ->
            val position = change.position
            val previousPosition = change.previousPosition

            val isInside = canDrag(position)
            val wasInside = canDrag(previousPosition)

            if (isInside && wasInside) {
                onDrag(size, dragAmount)
            }
        }
    }

    fun press() {
        velocityTracker.resetTracking()
        pressGeneration++
        animationScope.launch {
            launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(pressedScale, scaleYAnimationSpec) }
        }
    }

    fun release() {
        val myGeneration = pressGeneration
        animationScope.launch {
            awaitFrame()
            if (value != targetValue) {
                val threshold = (valueRange.endInclusive - valueRange.start) * 0.025f
                snapshotFlow { valueAnimation.value }
                    .filter { abs(it - valueAnimation.targetValue) < threshold }
                    .first()
            }
            // 等待期间若发生了新的 press（点击一次会连走 press→release→press），
            // 本次回缩作废，交给新 press 的 release，避免与滑移抢通道
            if (pressGeneration != myGeneration) return@launch
            launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(initialScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(initialScale, scaleYAnimationSpec) }
        }
    }

    fun updateValue(value: Float) {
        val targetValue = value.coerceIn(valueRange)
        animationScope.launch {
            valueAnimation.animateTo(
                targetValue,
                valueAnimationSpec,
            ) { updateVelocity() }
        }
    }

    fun animateToValue(value: Float) {
        animationScope.launch {
            mutatorMutex.mutate {
                press()
                val targetValue = value.coerceIn(valueRange)
                launch { valueAnimation.animateTo(targetValue, valueAnimationSpec) }
                if (velocity != 0f) {
                    launch { velocityAnimation.animateTo(0f, velocityAnimationSpec) }
                }
                release()
            }
        }
    }

    private fun updateVelocity() {
        velocityTracker.addPosition(
            System.currentTimeMillis(),
            Offset(value, 0f),
        )
        val targetVelocity =
            velocityTracker.calculateVelocity().x / (valueRange.endInclusive - valueRange.start)
        animationScope.launch { velocityAnimation.animateTo(targetVelocity, velocityAnimationSpec) }
    }
}
