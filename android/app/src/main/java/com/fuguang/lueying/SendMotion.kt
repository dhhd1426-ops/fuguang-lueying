package com.fuguang.lueying

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

/** 杏色发送球的颜色。 */
val SendWarm: Color get() = Skin.accent
private val SendWarmPressed = Color(0xFFD9A47F)

/**
 * 方案 3：发送时，从发送球里挤出一颗液滴，沿弧线飞到你刚发的那条消息的位置，
 * 落下后从右下角晕开成消息气泡。
 */
class SendFlight {
    /** 发送球圆心，屏幕坐标（随布局实时更新）。 */
    var from by mutableStateOf(Offset.Unspecified)
    /** 正在飞向的那条消息气泡的位置，屏幕坐标（随滚动实时更新）。 */
    var target by mutableStateOf(Rect.Zero)
    /** 正在飞向第几条消息；-1 = 没有。 */
    var idx by mutableIntStateOf(-1)
    var flying by mutableStateOf(false)
    /** 每发一次加一，用来让发送球“挤”一下。 */
    var count by mutableIntStateOf(0)
    var start = Offset.Zero
    val p = Animatable(0f)
    val reveal = Animatable(1f)

    suspend fun launch(i: Int) {
        if (from == Offset.Unspecified) return
        idx = i; start = from
        reveal.snapTo(0f); p.snapTo(0f)
        flying = true
        count++
        // 等新气泡排好版，才知道往哪里飞
        repeat(2) { withFrameNanos { } }
        try {
            p.animateTo(1f, tween(640, easing = CubicBezierEasing(.45f, 0f, .25f, 1f)))
        } finally { flying = false }
        reveal.animateTo(1f, tween(480, easing = CubicBezierEasing(.3f, .7f, .2f, 1f)))
    }
}

/** 让画布向四周多画出一圈，模糊黏连时边缘不被切掉。 */
fun Modifier.bleed(b: Dp) = layout { m, c ->
    val p = b.roundToPx()
    val pl = m.measure(Constraints.fixed(c.maxWidth + 2 * p, c.maxHeight + 2 * p))
    layout(c.maxWidth, c.maxHeight) { pl.place(-p, -p) }
}

/** 黏连效果：模糊 + 透明度对比（Android 12 起有效；更早的系统就是普通圆）。 */
fun Modifier.goo(radius: Float = 16f) = graphicsLayer {
    if (Build.VERSION.SDK_INT >= 31) {
        val blur = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL)
        val m = ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 24f, -10f * 255f))
        renderEffect = RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(m), blur).asComposeRenderEffect()
    }
}

/**
 * 方案 2：输入框里的发送球。t = 0 时在输入框里；t = 1 时已从输入框右边拉出来、独立成一颗。
 * 画在输入框外层的整块区域上，ball 是球在这块区域里的圆心，neck 是输入框里留下的“根”。
 */
@Composable
fun SendGoo(t: Float, pulse: Float, pressed: Boolean, inset: Dp, sep: Dp, size: Dp) {
    val bleed = 28.dp
    Canvas(Modifier.fillMaxSize().bleed(bleed).goo()) {
        val b = bleed.toPx()
        val r = size.toPx() / 2f
        val w = this.size.width - 2 * b
        val h = this.size.height - 2 * b
        val cy = b + h - inset.toPx() - r
        val ball = Offset(b + w - inset.toPx() * (1f - t) - r, cy)
        val neck = Offset(b + w - sep.toPx() * t - inset.toPx() - r, cy)
        val col = if (pressed) SendWarmPressed else SendWarm
        val nr = r * (1f - t).coerceIn(0f, 1f)
        if (nr > .5f) drawCircle(col, nr, neck)
        drawCircle(col, r * pulse, ball)
    }
}

/** 盖在聊天页上的飞行层：只在液滴飞的时候画。 */
@Composable
fun SendFlightLayer(flight: SendFlight) {
    if (!flight.flying) return
    var origin by remember { mutableStateOf(Offset.Zero) }
    Canvas(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        val tg = flight.target
        if (tg == Rect.Zero) return@Canvas
        val q = flight.p.value
        val inset = 16.dp.toPx()
        val from = flight.start - origin
        val to = Offset(tg.right - inset, tg.bottom - inset) - origin
        // 先往上走，再向左落进气泡
        val ctrl = Offset(from.x - 8.dp.toPx(), to.y + (from.y - to.y) * .3f)
        val u = 1f - q
        val pos = from * (u * u) + ctrl * (2 * u * q) + to * (q * q)
        val s = sin(PI * q).toFloat()
        val r = 15.dp.toPx() * (1f + .1f * (1f - q))
        val sz = Size(r * 2 * (1f - .22f * s), r * 2 * (1f + .34f * s))
        drawOval(SendWarm, topLeft = Offset(pos.x - sz.width / 2, pos.y - sz.height / 2), size = sz)
    }
}

/** 给消息气泡用：液滴落下前不显示，落下后从右下角晕开，杏色渐退成气泡本色。 */
fun Modifier.flightBubble(flight: SendFlight, mine: Boolean, corner: Dp): Modifier =
    if (!mine) this
    else this
        .onGloballyPositioned { flight.target = it.boundsInRoot() }
        .drawWithContent {
            if (flight.flying) return@drawWithContent
            val rv = flight.reveal.value
            if (rv >= 1f) { drawContent(); return@drawWithContent }
            val inset = 16.dp.toPx()
            val c = Offset(size.width - inset, size.height - inset)
            val rad = inset + (hypot(size.width, size.height) - inset) * rv
            val path = Path().apply { addOval(Rect(c, rad)) }
            clipPath(path) {
                this@drawWithContent.drawContent()
                drawRoundRect(SendWarm, alpha = (1f - rv).coerceIn(0f, 1f), cornerRadius = CornerRadius(corner.toPx()))
            }
        }
