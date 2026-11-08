package dev.aigw.app.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 全局动效参数：一处调整全局生效。 */
object Motion {
    const val PUSH_MILLIS = 320
    const val TAB_MILLIS = 200
    // 入场从 420ms/40ms 收紧到 240ms/24ms：工具型 App 动效超过 400ms 就显拖沓，
    // 30 条账号原来要等 29×40+420 ≈ 1.6 秒才齐。
    const val ENTER_MILLIS = 240
    const val STAGGER_MILLIS = 24L
    const val BAR_FILL_MILLIS = 320

    /** 错峰延迟上限：第 N 张不再线性变慢，超过这个值一起出。 */
    const val MAX_STAGGER_DELAY_MILLIS = 200
}

/**
 * 物理弹簧预设矩阵。
 *
 * 参数取自 open-claude-ui（MIT, Caho1）逆向的 Claude 桌面端二阶阻尼谐振子配置：
 * 原项目给的是 stiffness/damping/mass 三元组，这里按 ζ = c / (2·√(m·k)) 换算成
 * Compose 的 dampingRatio + stiffness（Compose 的质量固定为 1，质量 0.1 的那档用
 * 高阻尼比等价表达）。四档的用途分工不要混用：
 *
 * - [Bouncy]：按钮、开关、图标按压（超调约 8.8%，快速收敛）
 * - [Snappy]：气泡上浮、卡片展开、浮层唤起（超调约 1.8%，沉稳着陆）
 * - [Gentle]：复制成功勾选、点赞一类轻反馈（超调约 19.4%，两个周期的跳跃感）
 * - [HeavySnap]：抽屉/面板拖拽吸附（过阻尼 ζ≈3.65，零过冲，磁吸感）
 */
object Springs {
    val Bouncy = spring<Float>(dampingRatio = 0.612f, stiffness = 600f)
    val Snappy = spring<Float>(dampingRatio = 0.783f, stiffness = 320f)
    val Gentle = spring<Float>(dampingRatio = 0.462f, stiffness = 380f)
    val HeavySnap = spring<Float>(dampingRatio = 1f, stiffness = 1200f)
}

/** 二级页推拉：进入从右滑入，返回向右滑出（方向由目标是否为 null 决定）。 */
fun AnimatedContentTransitionScope<*>.pushPopSpec(): ContentTransform {
    val pushing = targetState != null
    val enter = slideInHorizontally(
        animationSpec = tween(Motion.PUSH_MILLIS, easing = FastOutSlowInEasing),
    ) { if (pushing) it else -it / 4 } + fadeIn(tween(Motion.PUSH_MILLIS))
    val exit = slideOutHorizontally(
        animationSpec = tween(Motion.PUSH_MILLIS, easing = FastOutSlowInEasing),
    ) { if (pushing) -it / 4 else it } + fadeOut(tween(Motion.PUSH_MILLIS))
    return (enter togetherWith exit)
}

/** 主 Tab 渐进渐远：按索引方向做小幅水平位移 + 淡入淡出。 */
fun <T> AnimatedContentTransitionScope<T>.tabContentSpec(
    index: (T) -> Int,
): ContentTransform {
    val forward = index(targetState) > index(initialState)
    val enter = slideInHorizontally(tween(Motion.TAB_MILLIS)) {
        (if (forward) 1 else -1) * 48
    } + fadeIn(tween(Motion.TAB_MILLIS))
    val exit = slideOutHorizontally(tween(Motion.TAB_MILLIS)) {
        (if (forward) -1 else 1) * 48
    } + fadeOut(tween(Motion.TAB_MILLIS))
    return (enter togetherWith exit)
}

/**
 * 错峰入场：首次组合时淡入 + 上浮，[index] 越大延迟越久（每张 40ms）。
 * 只在第一次出现时播一次，之后重组不重播。
 */
@Composable
fun Modifier.staggeredAppear(index: Int): Modifier {
    var shown by remember(index) { mutableStateOf(false) }
    LaunchedEffect(index) { shown = true }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(
            Motion.ENTER_MILLIS,
            delayMillis = ((index * Motion.STAGGER_MILLIS).toInt()).coerceAtMost(Motion.MAX_STAGGER_DELAY_MILLIS),
            easing = FastOutSlowInEasing,
        ),
        label = "stagger$index",
    )
    return graphicsLayer {
        alpha = progress
        translationY = (1f - progress) * 36f
    }
}

/** 按压缩放：按住缩到 0.97，松手用 [Springs.Bouncy] 回弹。 */
@Composable
fun Modifier.pressScale(): Modifier {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = Springs.Bouncy,
        label = "pressScale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            pressed = true
            waitForUpOrCancellation()
            pressed = false
        }
    }
}

/**
 * Q 弹按压动效：按下缩放到 0.85，松手触发 onClick 并以低阻尼弹性超调回弹。
 */
@Composable
fun Modifier.bouncyClick(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    // 手势块只在 key 变化时重启，若不缓存最新 lambda，就会一直调用首次组合时的 onClick
    // （调用方传入的 lambda 常捕获会变的 UI 状态，会表现为「点一次后再也点不动」）。
    val currentOnClick by rememberUpdatedState(onClick)
    // 用 clickable 而不是裸 pointerInput：后者没有 onClick 语义（无 Role.Button、无水波纹、
    // TalkBack 无法激活——模型页的刷新/开放名单/测试三个高频按钮全靠它）。
    // 手感靠 graphicsLayer 缩放保留，涟漪关掉以免和缩放叠加。
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.85f else 1f,
        animationSpec = Springs.Gentle, // 低阻尼 → 明显的 Q 弹物理振荡
        label = "bouncyScale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }.clickable(
        interactionSource = interaction,
        indication = null,
        enabled = enabled,
        onClick = { currentOnClick() },
    )
}

/** 流光带的跨度（px），与渐变轴向一致。 */
private const val SHIMMER_BAND = 300f

/** 供骨架屏共享的流光进度：一张卡建一个即可，进度由各 [shimmer] 在绘制阶段读取。 */
@Composable
fun rememberShimmerProgress(): State<Float> =
    rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = -SHIMMER_BAND,
        targetValue = SHIMMER_BAND * 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerProgress",
    )

/**
 * 优雅流光微光动效（Shimmer），用于骨架屏占位。
 *
 * [progress] 由调用方共享（见 [rememberShimmerProgress]），这里只在绘制阶段读取它：
 * 动画每帧仅触发重绘，不再逐帧重组，也不再每帧新建渐变。
 */
@Composable
fun Modifier.shimmer(
    progress: State<Float>,
    shape: Shape = RoundedCornerShape(4.dp),
    baseColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    highlightColor: Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
): Modifier {
    val brush = Brush.linearGradient(
        colors = listOf(baseColor, highlightColor, baseColor),
        start = Offset.Zero,
        end = Offset(SHIMMER_BAND, SHIMMER_BAND),
    )
    return this.clip(shape).drawBehind {
        translate(progress.value, progress.value) { drawRect(brush) }
    }
}

/**
 * 滚动边缘渐隐（7 档非线性 Alpha 遮罩）。
 *
 * 算法借鉴 open-claude-ui（MIT, Caho1）逆向的 Claude 桌面端 `[data-cds-scroll-fade]`：
 * 不用「透明 → 不透明」的两色线性切线（高分屏上会出现色阶断层），而是在羽化距离内
 * 按 15/30/45/60/75/87% 六个采样点铺 alpha 0.051/0.149/0.302/0.502/0.702/0.851，
 * 曲线贴合 y≈x^1.5 的感知幂律。触顶/触底（阈值 2px）时该侧羽化归零，边缘恢复完全清晰。
 *
 * Compose 侧用「背景色渐变叠层」实现：逐档从背景色渐到透明，颜色就是页面底色，
 * 因此不会引入额外色调（用 `BlendMode.DstIn` 画黑色遮罩必须配合离屏图层，
 * 直接画会把内容染黑，见 2.12.0 的记录页底部黑条）。
 *
 * [listState] 传入时按实际滚动位置动态开关；不传则两侧常开（用于拿不到列表状态的容器）。
 */
@Composable
fun Modifier.scrollFade(
    listState: LazyListState? = null,
    fadeSize: Dp = 28.dp,
): Modifier {
    val background = MaterialTheme.colorScheme.background
    // 内容不满一屏（不可滚动）时两侧都不该有渐隐，否则底部会凭空多出一条色带
    val scrollable by remember(listState) {
        if (listState == null) {
            derivedStateOf { true }
        } else {
            derivedStateOf {
                val layout = listState.layoutInfo
                layout.totalItemsCount > 0 &&
                    layout.visibleItemsInfo.lastOrNull()?.let { it.offset + it.size } ?: 0 > layout.viewportEndOffset
            }
        }
    }
    val fadeTop by remember(listState, scrollable) {
        if (listState == null || !scrollable) {
            derivedStateOf { false }
        } else {
            derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 2 }
        }
    }
    val fadeBottom by remember(listState, scrollable) {
        if (listState == null || !scrollable) {
            derivedStateOf { false }
        } else {
            derivedStateOf {
                val layout = listState.layoutInfo
                val last = layout.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false
                // 末项底部距视口底部还差多少；差 2px 以内视为已触底
                last.offset + last.size < layout.viewportEndOffset - 2
            }
        }
    }
    val feather = with(LocalDensity.current) { fadeSize.toPx() }
    return drawWithContent {
        drawContent()
        if (feather <= 0f || (!fadeTop && !fadeBottom)) return@drawWithContent
        val top = if (fadeTop) feather else 0f
        val bottom = if (fadeBottom) feather else 0f
        val colors = ArrayList<Color>(16)
        val stops = ArrayList<Float>(16)
        if (top > 0f) {
            // 顶部：背景色（不透明） → 透明，六个非线性采样点
            val alphas = floatArrayOf(1f, 0.851f, 0.702f, 0.502f, 0.302f, 0.149f, 0.051f, 0f)
            val positions = floatArrayOf(0f, 0.15f, 0.30f, 0.45f, 0.60f, 0.75f, 0.87f, 1f)
            val span = top / size.height
            for (i in alphas.indices) {
                colors.add(background.copy(alpha = alphas[i]))
                stops.add(span * positions[i])
            }
        } else {
            colors.add(background.copy(alpha = 0f))
            stops.add(0f)
        }
        if (bottom > 0f) {
            // 底部：透明 → 背景色（不透明），镜像对称
            val alphas = floatArrayOf(0f, 0.051f, 0.149f, 0.302f, 0.502f, 0.702f, 0.851f, 1f)
            val positions = floatArrayOf(0f, 0.13f, 0.25f, 0.40f, 0.55f, 0.70f, 0.85f, 1f)
            val span = bottom / size.height
            for (i in alphas.indices) {
                colors.add(background.copy(alpha = alphas[i]))
                stops.add(1f - span + span * positions[i])
            }
        } else {
            colors.add(background.copy(alpha = 0f))
            stops.add(1f)
        }
        drawRect(
            brush = Brush.verticalGradient(
                colorStops = Array(colors.size) { i -> stops[i] to colors[i] },
            ),
        )
    }
}

/**
 * 呼吸指示灯：圆点做 3s 周期的缩放 + 亮度呼吸；[haloSize] 非空时额外画一圈柔光外晕
 * （占位取 [haloSize]，核心仍是 [size] 大小）。
 *
 * [breathing] 为 false 时不动画、无外晕，仅画一个静态圆点。
 *
 * 时序借鉴 open-claude-ui（MIT, Caho1）逆向的 `dframe-clay-breathe`
 * （scale 0.86→1、brightness 0.96→1.06、3.0s ease-in-out）。
 */
@Composable
fun Modifier.breathingDot(
    color: Color,
    size: Dp = 8.dp,
    breathing: Boolean = true,
    haloSize: Dp? = null,
): Modifier {
    // 外晕只在显式给出时占位：不传即维持原来的纯圆点尺寸，不改变已有调用方的布局
    val boxSize = haloSize ?: size
    if (!breathing) {
        return this
            .size(boxSize)
            .drawBehind {
                val center = Offset(this.size.width / 2f, this.size.height / 2f)
                if (haloSize != null) {
                    drawCircle(color = color.copy(alpha = 0.22f), radius = (boxSize / 2).toPx(), center = center)
                }
                drawCircle(color = color, radius = (size / 2).toPx(), center = center)
            }
    }
    val transition = rememberInfiniteTransition(label = "breathe")
    val scale by transition.animateFloat(
        initialValue = 0.86f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breatheScale",
    )
    val alpha by transition.animateFloat(
        initialValue = 0.72f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breatheAlpha",
    )
    val actualHalo = boxSize
    return this
        .size(actualHalo)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            this.alpha = alpha
        }
        .drawBehind {
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            if (haloSize != null) {
                drawCircle(
                    color = color.copy(alpha = 0.22f),
                    radius = (actualHalo / 2).toPx(),
                    center = center,
                )
            }
            drawCircle(
                color = color,
                radius = (size / 2).toPx(),
                center = center,
            )
        }
}
