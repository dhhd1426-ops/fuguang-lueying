package com.fuguang.lueying

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

/**
 * 深度思考六档（方案 1 分段条 · 动效 2 液体拉伸）。
 * 点哪档去哪档：朝前那条边先到，后面那条边跟上，中间拉长时色块变细、圆角变大，像一滴液体流过去。
 * 按住色块能拖，色块跟着手指走，松手按手速吸到最近一档；拖出两头会被轻轻拽回。
 * 字是两层：底层灰字，上层深色字只露出色块盖住的那一截，色块流过时字是一点点被“染”过去的。
 * max（方案 3）：色块向两头流开铺满整条，停稳后一道淡光从左扫到右，只扫一次。
 */
@Composable
fun ThinkSegment(level: Int, providerId: String, onChange: (Int) -> Unit) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val pad = with(density) { 3.dp.toPx() }
    var width by remember { mutableFloatStateOf(0f) }
    val cw = if (width > 0f) (width - pad * 2) / 6f else 0f

    // 色块左右两条边，单位像素
    val left = remember { Animatable(0f) }
    val right = remember { Animatable(0f) }
    var placed by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    // 正在显示的档：拖动时随手指变，松手才真正保存
    var shown by remember { mutableIntStateOf(level) }

    // 每一档色块停稳的位置：max 铺满整条
    fun spanOf(i: Int): Pair<Float, Float> = if (i == 5) pad to pad + 6 * cw else (pad + i * cw) to (pad + (i + 1) * cw)

    suspend fun flowTo(i: Int) {
        if (cw <= 0f) return
        val (tl, tr) = spanOf(i)
        // 往外走的边是前沿，弹得快；往里收的边是后沿，稍慢但不拖沓
        val lead = spring<Float>(dampingRatio = .74f, stiffness = 620f)
        val trail = spring<Float>(dampingRatio = .9f, stiffness = 300f)
        coroutineScope {
            launch { right.animateTo(tr, if (tr > right.value) lead else trail) }
            launch { left.animateTo(tl, if (tl < left.value) lead else trail) }
        }
    }
    // max 铺满后的一道光：-1 = 不画，0→1 = 从左扫到右
    val sweep = remember { Animatable(-1f) }

    // 外面换了档（或第一次量到宽度）
    LaunchedEffect(level, cw) {
        if (cw <= 0f) return@LaunchedEffect
        if (!placed) {
            val (a, b) = spanOf(level); left.snapTo(a); right.snapTo(b); placed = true
        } else if (!dragging) { shown = level; flowTo(level) }
    }
    // 到 max 且色块停稳：扫一道光，只一次
    LaunchedEffect(shown, dragging, placed) {
        sweep.snapTo(-1f)
        if (shown != 5 || dragging || !placed) return@LaunchedEffect
        snapshotFlow { cw > 0f && right.value - left.value > cw * 5.9f && !left.isRunning && !right.isRunning }.first { it }
        sweep.snapTo(0f)
        sweep.animateTo(1f, tween(1100, easing = FastOutSlowInEasing))
        sweep.snapTo(-1f)
    }
    // 每换一档轻轻震一下
    LaunchedEffect(Unit) {
        snapshotFlow { shown }.drop(1).collect { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick) }
    }

    val off = shown == 0
    val blockColor by animateColorAsState(if (off) rgba(255, 255, 255, .2f) else css("#e9b48e"), tween(260), label = "blk")
    val inkOn by animateColorAsState(if (off) Page.ink else css("#2a211b"), tween(260), label = "ink")
    val squeeze by animateFloatAsState(if (pressed && !dragging) .955f else 1f, spring(dampingRatio = .6f, stiffness = 900f), label = "press")

    // 色块此刻的形状：拉得越长越细、圆角越大；按下时微微收一点
    fun DrawScope.block(): RoundRect {
        val l = left.value
        val r = right.value
        val w = (r - l).coerceAtLeast(1f)
        // 拉长是相对停稳时的宽度算的：铺满 max 时本来就宽，不算拉长；从 max 收回时才变细
        val rest = if (shown == 5 && !dragging) cw * 6 else cw
        val stretch = if (rest > 0f) (w / rest - 1f).coerceAtLeast(0f) else 0f
        val thin = min(stretch * 2.2.dp.toPx(), 2.6.dp.toPx())
        val h0 = size.height - pad * 2 - thin * 2
        val cx = (l + r) / 2
        val cy = size.height / 2
        val bw = w * (1f - (1f - squeeze) * .6f)
        val bh = h0 * squeeze
        val rad = min(9.dp.toPx() + stretch * 9.dp.toPx(), bh / 2)
        return RoundRect(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2, CornerRadius(rad))
    }

    Column(
        Modifier.fillMaxWidth()
            .background(rgba(255, 255, 255, .06f), RoundedCornerShape(14.dp))
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt("深度思考", Page.ink, 15.sp, Modifier.weight(1f))
            // 档名：往深处换向上翻，往浅处换向下翻
            AnimatedContent(
                shown,
                transitionSpec = {
                    val up = targetState > initialState
                    (slideInVertically(spring(dampingRatio = .8f, stiffness = 500f)) { if (up) it / 2 else -it / 2 } + fadeIn(tween(200)))
                        .togetherWith(slideOutVertically(tween(160)) { if (up) -it / 2 else it / 2 } + fadeOut(tween(140)))
                        .using(SizeTransform(clip = true))
                },
                contentAlignment = Alignment.CenterEnd,
                label = "level",
            ) { Txt(THINK_LEVELS[it], if (it == 0) Page.muted else Page.accent, 13.sp) }
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.fillMaxWidth().height(42.dp)
                .background(rgba(255, 255, 255, .05f), RoundedCornerShape(12.dp))
                .onSizeChanged { width = it.width.toFloat() }
                .semantics { contentDescription = "深度思考：" + THINK_LEVELS[shown] }
                .pointerInput(cw) {
                    if (cw <= 0f) return@pointerInput
                    val lo = pad + cw / 2
                    val hi = pad + cw * 5.5f
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        pressed = true
                        val vt = VelocityTracker()
                        vt.addPosition(down.uptimeMillis, down.position)
                        val x0 = down.position.x
                        val mid0 = (left.value + right.value) / 2
                        // 按在色块上就“抓住”它，抓的位置不跳；按在别处拖（或 max 铺满时），色块先流到手指下
                        val single = right.value - left.value < cw * 1.5f
                        val grab = if (single && x0 in left.value..right.value) x0 - mid0 else 0f
                        var drag = false
                        var lastX = x0
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            vt.addPosition(ch.uptimeMillis, ch.position)
                            if (!drag && abs(ch.position.x - x0) > viewConfiguration.touchSlop) { drag = true; dragging = true }
                            if (drag) {
                                ch.consume()
                                val raw = ch.position.x - grab
                                // 拖出两头：只走三成，像被橡皮筋拽着
                                val cx = when { raw < lo -> lo + (raw - lo) * .3f; raw > hi -> hi + (raw - hi) * .3f; else -> raw }
                                val toRight = ch.position.x >= lastX
                                lastX = ch.position.x
                                val fast = spring<Float>(dampingRatio = 1f, stiffness = 1500f)
                                val slow = spring<Float>(dampingRatio = 1f, stiffness = 420f)
                                scope.launch { right.animateTo(cx + cw / 2, if (toRight) fast else slow) }
                                scope.launch { left.animateTo(cx - cw / 2, if (toRight) slow else fast) }
                                shown = ((cx - pad) / cw).toInt().coerceIn(0, 5)
                            }
                        }
                        pressed = false
                        val i = if (drag) {
                            // 松手时顺着手速往前送一点，再吸到最近一档
                            val v = runCatching { vt.calculateVelocity().x }.getOrDefault(0f)
                            val c = (left.value + right.value) / 2 + v * .06f
                            ((c - pad) / cw).toInt().coerceIn(0, 5)
                        } else ((x0 - pad) / cw).toInt().coerceIn(0, 5)
                        dragging = false
                        shown = i
                        scope.launch { flowTo(i) }
                        if (i != level) onChange(i)
                    }
                },
        ) {
            // 色块
            Box(
                Modifier.fillMaxSize().drawBehind {
                    if (!placed) return@drawBehind
                    val b = block()
                    drawRoundRect(blockColor, Offset(b.left, b.top), Size(b.width, b.height), CornerRadius(b.topLeftCornerRadius.x))
                    val p = sweep.value
                    if (p in 0f..1f && p < 1f) {
                        val half = 70.dp.toPx()
                        val gx = -half + p * (size.width + half * 2)
                        clipPath(Path().apply { addRoundRect(b) }) {
                            drawRect(
                                Brush.horizontalGradient(
                                    listOf(Color(255, 250, 244, 0), Color(255, 250, 244, 107), Color(255, 250, 244, 0)),
                                    startX = gx - half, endX = gx + half,
                                ),
                            )
                        }
                    }
                },
            )
            // 底层灰字
            SegmentLabels(Page.muted, Modifier.fillMaxSize())
            // 上层深色字，只露出色块盖住的部分
            SegmentLabels(
                inkOn,
                Modifier.fillMaxSize().drawWithContent {
                    if (!placed) return@drawWithContent
                    val path = Path().apply { addRoundRect(block()) }
                    clipPath(path) { this@drawWithContent.drawContent() }
                },
            )
        }
        Spacer(Modifier.height(10.dp))
        // 这一档实际发给模型的是什么
        AnimatedContent(
            shown,
            transitionSpec = {
                (fadeIn(tween(240, delayMillis = 40)) + slideInVertically(tween(260)) { it / 3 })
                    .togetherWith(fadeOut(tween(120)))
                    .using(SizeTransform(clip = false))
            },
            label = "note",
        ) { Txt(thinkNote(providerId, it), Page.muted, 12.sp, Modifier.padding(start = 2.dp), maxLines = 1) }
    }
}

@Composable
private fun SegmentLabels(color: Color, modifier: Modifier) {
    Row(modifier.padding(horizontal = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        THINK_LEVELS.forEach { l ->
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Txt(l, color, 13.sp, weight = FontWeight.Medium, maxLines = 1)
            }
        }
    }
}
