package com.fuguang.lueying

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

private val Bodoni = FontFamily(Font(R.font.bodoni_moda_semibold_italic, FontWeight.SemiBold, FontStyle.Italic))
private val Apricot = Color(0xFFE9B48E)
private val OnApricot = Color(0xFF2A211B)
/** 进度条右端收进去的深色，和抽屉底色一致。 */
private val Deep = Color(13, 16, 22)

/**
 * 深度思考档位（跟着模型走：档位来自 Effort.spec，几档就分几格；换模型时色块流到新位置）。
 * 关闭到复杂：液体色块。点哪档去哪档，朝前那条边先到、后面那条边跟上，拉长时变细、圆角变大；
 *   按住能拖，松手按手速吸到最近一档；字是两层，色块流过时一点点被染成深色。关闭时色块是灰的。
 * max（照 dongxiaosheji/max-rainbow）：色块向两头流开铺满整条，彩虹从左往右铺开，其他档位名依次淡出，
 *   只剩右端的 max 字样；彩虹和速度线画在色块里的同一张画布上，被圆角裁住，不出框。离开 max 淡回杏色。
 */
@Composable
fun ThinkSegment(spec: EffortSpec, value: String?, onChange: (String) -> Unit) {
    if (spec.levels.isEmpty()) { NoLevels(spec); return }
    val n = spec.levels.size
    val top = n - 1
    val maxAt = spec.levels.indexOfFirst { it.value == "max" }
    val names = spec.levels.map { it.label }
    val level = spec.index(value)
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val pad = with(density) { 3.dp.toPx() }
    var width by remember { mutableFloatStateOf(0f) }
    val cw = if (width > 0f) (width - pad * 2) / n else 0f
    val saved by rememberUpdatedState(level)
    val change by rememberUpdatedState { i: Int -> onChange(spec.levels[i].value) }

    // 色块左右两条边，单位像素
    val left = remember { Animatable(0f) }
    val right = remember { Animatable(0f) }
    var placed by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    // 正在显示的档：拖动时随手指变，松手才真正保存
    var shown by remember { mutableIntStateOf(level) }
    if (shown > top) shown = level

    // 每一档色块停稳的位置：max 铺满整条
    fun spanOf(i: Int): Pair<Float, Float> = if (i == maxAt) pad to pad + n * cw else (pad + i * cw) to (pad + (i + 1) * cw)

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

    // 外面换了档（或第一次量到宽度）
    LaunchedEffect(level, cw, n) {
        if (cw <= 0f) return@LaunchedEffect
        if (!placed) {
            val (a, b) = spanOf(level); left.snapTo(a); right.snapTo(b); placed = true
        } else if (!dragging) { shown = level; flowTo(level) }
    }
    // 每换一档轻轻震一下
    LaunchedEffect(Unit) {
        snapshotFlow { shown }.drop(1).collect { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick) }
    }

    val isMax = shown == maxAt && !dragging
    val off = spec.levels.getOrNull(shown)?.value == Effort.OFF
    val blockColor by animateColorAsState(if (off) veil(.2f) else Apricot, tween(260), label = "blk")
    val inkOn by animateColorAsState(if (off) Page.ink else OnApricot, tween(260), label = "ink")
    val squeeze by animateFloatAsState(if (pressed && !dragging) .955f else 1f, spring(dampingRatio = .6f, stiffness = 900f), label = "press")

    // 彩虹画布：进 max 0.1 秒显出，离开 0.45 秒淡回杏色
    val fxAlpha by animateFloatAsState(if (isMax) 1f else 0f, tween(if (isMax) 100 else 450), label = "fx")
    // 档位名：进 max 从左到右依次淡出（190ms 起，每个隔 70ms，各 250ms）；离开 200ms 恢复
    val labelAlpha = List(n) { j ->
        animateFloatAsState(
            if (isMax) 0f else 1f,
            if (isMax) tween(250, delayMillis = 190 + 70 * j) else tween(200),
            label = "lbl$j",
        ).value
    }
    // max 字样：550ms 起从左侧 40dp 滑进右端
    val maxIn by animateFloatAsState(if (isMax) 1f else 0f, if (isMax) tween(250, delayMillis = 550) else tween(250), label = "maxA")
    val maxSlide by animateFloatAsState(
        if (isMax) 0f else 1f,
        if (isMax) tween(500, delayMillis = 550, easing = CubicBezierEasing(.15f, 1.3f, .4f, 1f)) else tween(500),
        label = "maxX",
    )
    val fx = remember { RainbowFx() }
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(isMax) {
        if (!isMax) return@LaunchedEffect
        fx.enter()
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 1f / 60 else ((now - last) / 1e9f).coerceIn(0f, .05f)
                last = now
                fx.step(dt)
                frame = now
            }
        }
    }

    // 色块此刻的形状：拉得越长越细、圆角越大；按下时微微收一点
    fun DrawScope.block(): RoundRect {
        val l = left.value
        val r = right.value
        val w = (r - l).coerceAtLeast(1f)
        // 拉长是相对停稳时的宽度算的：铺满 max 时本来就宽，不算拉长；从 max 收回时才变细
        val rest = if (shown == maxAt && !dragging) cw * n else cw
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
            .background(veil(.06f), RoundedCornerShape(14.dp))
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt("深度思考", Page.ink, 15.sp, Modifier.weight(1f))
            // 档名：往深处换向上翻，往浅处换向下翻；max 是流动的彩虹字
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
            ) { i -> val l = spec.levels.getOrNull(i); if (i == maxAt) RainbowWord("max") else Txt(l?.let { if (it.value == Effort.OFF) "不思考" else it.label }.orEmpty(), if (l?.value == Effort.OFF) Page.muted else Page.accent, 13.sp) }
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.fillMaxWidth().height(44.dp)
                .background(veil(.05f), RoundedCornerShape(12.dp))
                .onSizeChanged { width = it.width.toFloat() }
                .semantics { contentDescription = "深度思考：" + names.getOrElse(shown) { "" } }
                .pointerInput(cw, n) {
                    if (cw <= 0f) return@pointerInput
                    val lo = pad + cw / 2
                    val hi = pad + cw * (n - .5f)
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
                                shown = ((cx - pad) / cw).toInt().coerceIn(0, top)
                            }
                        }
                        pressed = false
                        val i = if (drag) {
                            // 松手时顺着手速往前送一点，再吸到最近一档
                            val v = runCatching { vt.calculateVelocity().x }.getOrDefault(0f)
                            val c = (left.value + right.value) / 2 + v * .06f
                            ((c - pad) / cw).toInt().coerceIn(0, top)
                        } else ((x0 - pad) / cw).toInt().coerceIn(0, top)
                        dragging = false
                        shown = i
                        scope.launch { flowTo(i) }
                        if (i != saved) change(i)
                    }
                },
        ) {
            // 色块
            Box(
                Modifier.fillMaxSize().drawBehind {
                    if (!placed) return@drawBehind
                    val b = block()
                    drawRoundRect(blockColor, Offset(b.left, b.top), Size(b.width, b.height), CornerRadius(b.topLeftCornerRadius.x))
                },
            )
            // 彩虹 + 速度线：同一张画布，裁在色块里
            Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = fxAlpha; compositingStrategy = CompositingStrategy.Offscreen }) {
                if (fxAlpha <= 0f || !placed) return@Canvas
                frame // 每帧重画
                clipPath(Path().apply { addRoundRect(block()) }) { fx.draw(this) }
            }
            // 档位名：底层灰字；上层深色字只露出色块盖住的部分
            SegmentLabels(names, Page.muted, labelAlpha, Modifier.fillMaxSize())
            SegmentLabels(
                names, inkOn, labelAlpha,
                Modifier.fillMaxSize().drawWithContent {
                    if (!placed) return@drawWithContent
                    clipPath(Path().apply { addRoundRect(block()) }) { this@drawWithContent.drawContent() }
                },
            )
            // max 字样
            if (maxIn > 0f) {
                Box(
                    Modifier.fillMaxSize().padding(end = 18.dp).graphicsLayer {
                        alpha = maxIn; translationX = -40.dp.toPx() * maxSlide
                    },
                    contentAlignment = Alignment.CenterEnd,
                ) { MaxWord() }
            }
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
        ) { i -> Txt(Effort.note(spec, spec.levels.getOrNull(i)?.value ?: ""), Page.muted, 12.sp, Modifier.padding(start = 2.dp), maxLines = 1) }
    }
}

/** 不收档位的型号：只有一句话，不放档位条。 */
@Composable
private fun NoLevels(spec: EffortSpec) {
    Column(
        Modifier.fillMaxWidth()
            .background(veil(.06f), RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Txt("深度思考", Page.ink, 15.sp)
        Spacer(Modifier.height(6.dp))
        Txt(spec.note, Page.muted, 12.sp)
    }
}

@Composable
private fun SegmentLabels(names: List<String>, color: Color, alphas: List<Float>, modifier: Modifier) {
    Row(modifier.padding(horizontal = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        names.forEachIndexed { j, l ->
            Box(Modifier.weight(1f).graphicsLayer { alpha = alphas.getOrElse(j) { 1f } }, contentAlignment = Alignment.Center) {
                Txt(l, color, 13.sp, weight = FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

/** 右上角的档名在 max 时是流动的彩虹字。 */
@Composable
private fun RainbowWord(text: String) {
    val inf = rememberInfiniteTransition(label = "rw")
    val p by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "rwp")
    val w = with(LocalDensity.current) { 60.dp.toPx() }
    BasicText(
        text,
        style = TextStyle(
            brush = Brush.linearGradient(RAINBOW, start = Offset(-p * w * 2, 0f), end = Offset(-p * w * 2 + w * 2, 0f), tileMode = TileMode.Repeated),
            fontFamily = Sans, fontSize = 13.sp, fontWeight = FontWeight.Bold,
        ),
    )
}

/** max 字样：Bodoni Moda 斜体，暖白，字内一道高光每 1.5 秒从左扫到右。 */
@Composable
private fun MaxWord() {
    val inf = rememberInfiniteTransition(label = "mw")
    val p by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart), label = "mwp")
    val w = with(LocalDensity.current) { 52.dp.toPx() }
    val x = -w * .9f + p * w * 2.4f
    val warm = Color(0xFFFFE9C9)
    BasicText(
        "max",
        style = TextStyle(
            brush = Brush.linearGradient(
                0f to warm, .38f to warm, .46f to Color.White, .5f to Color(0xFFB9E6FF), .54f to Color.White, .62f to warm, 1f to warm,
                start = Offset(x - w, 0f), end = Offset(x + w, w * .36f),
            ),
            fontFamily = Bodoni, fontStyle = FontStyle.Italic, fontWeight = FontWeight.SemiBold,
            fontSize = 25.sp, letterSpacing = .06.em,
            shadow = Shadow(Color.White.copy(alpha = .55f), blurRadius = 14f),
        ),
    )
}

private val RAINBOW = listOf(
    Color(0xFFFF5F6D), Color(0xFFFFB347), Color(0xFFFFE66D), Color(0xFF5EE6A8),
    Color(0xFF4FC3FF), Color(0xFF9B7BFF), Color(0xFFFF6EC7), Color(0xFFFF5F6D),
)

/** 彩虹和速度线的状态与绘制（参数照 max-rainbow：60 帧下每帧色相偏 7°、每帧约 2 条速度线）。 */
private class RainbowFx {
    private class Line(var x: Float, val y: Float, val v: Float, val len: Float, val w: Float, val dark: Boolean, val a: Float)

    private val lines = ArrayList<Line>()
    /** 0→1 铺开，1→2 冲一下再回落；2 = 常态。 */
    private var intro = 2f
    private var t = 0f
    private var w = 0f
    private var h = 0f
    private var u = 1f // 1 个原稿像素 = 1dp

    fun enter() { intro = 0f; lines.clear() }

    private fun speed() = if (intro < 1f) .15f + .85f * intro * intro else 1f + max(0f, 1.5f - intro) * 1.6f

    fun step(dt: Float) {
        if (w <= 0f) return
        val f = dt * 60f
        intro = min(2f, intro + f / 42f)
        val k = speed()
        t += 7f * k * f
        val n = 2f * k * f
        repeat(floor(n + Random.nextFloat()).toInt()) { spawn(k > 1.2f) }
        lines.forEach { it.x += it.v * f }
        lines.removeAll { it.x - it.len > w }
    }

    private fun spawn(fast: Boolean) {
        val v = (10f + Random.nextFloat() * 16f + if (fast) 12f else 0f) * u
        val dark = Random.nextFloat() < .3f
        lines += Line(
            x = -Random.nextFloat() * w * .6f, y = Random.nextFloat() * h, v = v,
            len = v * (4f + Random.nextFloat() * 6f), w = (1.5f + Random.nextFloat() * 5f) * u,
            dark = dark, a = if (dark) .22f else .3f + Random.nextFloat() * .4f,
        )
    }

    private fun hueAt(x: Float) = (((x / w * 300f - t) % 360f) + 360f) % 360f

    fun draw(s: DrawScope) = with(s) {
        u = 1.dp.toPx(); w = size.width; h = size.height
        // 底色：整条彩虹向右流动
        drawRect(Brush.horizontalGradient(*Array(11) { i -> i / 10f to Color.hsl(hueAt(i / 10f * w), .95f, .66f) }, startX = 0f, endX = w))
        // 速度线：取所在位置的彩虹色再偏 25°，七成提亮、三成压暗，是彩虹自身的明暗流纹
        lines.forEach { l ->
            val hue = (hueAt(l.x) + 25f) % 360f
            val light = if (l.dark) .48f else .86f
            drawLine(
                Brush.horizontalGradient(listOf(Color.hsl(hue, 1f, light, 0f), Color.hsl(hue, 1f, light, l.a)), startX = l.x - l.len, endX = l.x),
                Offset(l.x - l.len, l.y), Offset(l.x, l.y), strokeWidth = l.w, cap = StrokeCap.Round,
            )
        }
        // 右端收进深色，彩虹和流纹冲向 max 字样
        val dw = 150f * u
        drawRect(
            Brush.horizontalGradient(0f to Deep.copy(alpha = 0f), .55f to Deep.copy(alpha = .78f), 1f to Deep.copy(alpha = .94f), startX = w - dw, endX = w),
            topLeft = Offset(w - dw, 0f), size = Size(dw, h),
        )
        // 字后面的彗尾：四道亮线贴着字的高度射入
        if (intro >= 1f) {
            val k = (t / 7f) % 1f
            for (i in 0 until 4) {
                val y = h / 2 + (i - 1.5f) * 5f * u
                val x1 = w - 86f * u
                val x0 = x1 - 60f * u - ((k + i * .27f) % 1f) * 70f * u
                drawLine(
                    Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0f), Color.hsl(hueAt(x0), 1f, .85f, .75f)), startX = x0, endX = x1),
                    Offset(x0, y), Offset(x1, y), strokeWidth = 1.4f * u,
                )
            }
        }
        // 铺开的前沿：柔边遮罩（前沿右边仍是杏色）+ 一道白色亮边
        if (intro < 1f) {
            val e = 1f - (1f - intro).let { it * it * it }
            val x = e * (w + 60f * u)
            drawRect(
                Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = x - 60f * u, endX = x),
                blendMode = BlendMode.DstIn,
            )
            drawRect(
                Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0f), Color.White.copy(alpha = .7f)), startX = x - 70f * u, endX = x - 20f * u),
                topLeft = Offset(x - 70f * u, 0f), size = Size(50f * u, h), blendMode = BlendMode.Plus,
            )
        }
    }
}
