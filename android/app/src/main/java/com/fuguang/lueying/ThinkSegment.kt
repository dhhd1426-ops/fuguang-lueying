package com.fuguang.lueying

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlinx.coroutines.flow.drop
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
 * 深度思考六档：粗进度条，点或拖都行，选到哪档就从左填到哪档。
 * 拉满到 max（照 dongxiaosheji/max-rainbow）：彩虹从左往右铺开，其他档位名依次淡出，只剩右端的 max 字样；
 * 彩虹和速度线画在进度条里的同一张画布上，被圆角裁住，不出框。离开 max 淡回杏色。
 */
@Composable
fun ThinkSegment(level: Int, providerId: String, onChange: (Int) -> Unit) {
    val haptic = LocalHapticFeedback.current
    var shown by remember { mutableIntStateOf(level) }
    LaunchedEffect(level) { shown = level }
    val saved by rememberUpdatedState(level)
    val change by rememberUpdatedState(onChange)
    LaunchedEffect(Unit) {
        snapshotFlow { shown }.drop(1).collect { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick) }
    }
    val isMax = shown == 5

    // 杏色填充：伸到哪档，带一点过冲
    val fill by animateFloatAsState((shown + 1) / 6f, tween(220, easing = CubicBezierEasing(.3f, 1.3f, .5f, 1f)), label = "fill")
    // 画布：进 max 0.1 秒显出，离开 0.45 秒淡回杏色
    val fxAlpha by animateFloatAsState(if (isMax) 1f else 0f, tween(if (isMax) 100 else 450), label = "fx")
    // 档位名：进 max 从左到右依次淡出（190ms 起，每个隔 70ms，各 250ms）；离开 200ms 恢复
    val labelAlpha = List(6) { j ->
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

    Column(
        Modifier.fillMaxWidth()
            .background(rgba(255, 255, 255, .06f), RoundedCornerShape(14.dp))
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt("深度思考", Page.ink, 15.sp, Modifier.weight(1f))
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
            ) { if (it == 5) RainbowWord("max") else Txt(THINK_LEVELS[it], if (it == 0) Page.muted else Page.accent, 13.sp) }
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.fillMaxWidth().height(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(rgba(255, 255, 255, .05f))
                .semantics { contentDescription = "深度思考：" + THINK_LEVELS[shown] }
                .pointerInput(Unit) {
                    fun at(x: Float) = floor(x / size.width * 6).toInt().coerceIn(0, 5)
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        shown = at(down.position.x)
                        var moved = false
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            if (!moved && kotlin.math.abs(ch.position.x - down.position.x) > viewConfiguration.touchSlop) moved = true
                            if (moved) { ch.consume(); shown = at(ch.position.x) }
                        }
                        if (shown != saved) change(shown)
                    }
                },
        ) {
            // 杏色填充
            Box(Modifier.fillMaxSize().drawWithContent {
                drawRoundRect(Apricot, size = Size(size.width * fill, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()))
            })
            // 彩虹 + 速度线，同一张画布，只画在填充里
            Canvas(
                Modifier.fillMaxSize().graphicsLayer { alpha = fxAlpha; compositingStrategy = CompositingStrategy.Offscreen },
            ) {
                if (fxAlpha <= 0f) return@Canvas
                frame // 每帧重画
                clipRect(right = size.width * fill) { fx.draw(this) }
            }
            // 档位名：灰字一层，填充盖住的部分是深色字
            SegmentLabels(Page.muted, labelAlpha, Modifier.fillMaxSize())
            SegmentLabels(
                OnApricot, labelAlpha,
                Modifier.fillMaxSize().drawWithContent {
                    clipRect(right = size.width * fill) { this@drawWithContent.drawContent() }
                },
            )
            // max 字样
            if (maxIn > 0f) {
                val d = LocalDensity.current
                Box(
                    Modifier.fillMaxSize().padding(end = 18.dp).graphicsLayer {
                        alpha = maxIn; translationX = -with(d) { 40.dp.toPx() } * maxSlide
                    },
                    contentAlignment = Alignment.CenterEnd,
                ) { MaxWord() }
            }
        }
        Spacer(Modifier.height(10.dp))
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
private fun SegmentLabels(color: Color, alphas: List<Float>, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        THINK_LEVELS.forEachIndexed { j, l ->
            Box(Modifier.weight(1f).graphicsLayer { alpha = alphas[j] }, contentAlignment = Alignment.Center) {
                Txt(l, color, 14.sp, weight = FontWeight.Medium, maxLines = 1)
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
            fontSize = 13.sp, fontWeight = FontWeight.Bold,
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
