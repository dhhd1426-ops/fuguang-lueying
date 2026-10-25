package com.fuguang.lueying

import android.graphics.Bitmap
import android.graphics.Canvas as ACanvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 自定义图标取景：相册选好图后打开，「放到桌面」交回一张 432×432 的图（亮着的方框落在中间 288）。 */
object IconCrop {
    var bmp by mutableStateOf<Bitmap?>(null)
        private set
    private var done: ((Bitmap) -> Unit)? = null
    fun open(b: Bitmap, onDone: (Bitmap) -> Unit) { done = onDone; bmp = b }
    internal fun finish(out: Bitmap?) { val d = done; bmp = null; done = null; if (out != null) d?.invoke(out) }
}

private val warm = Color(0xFFE9B48E)

/**
 * 取景台 300dp，中间亮着一个圆角方框（图标会露出来的范围），外面压暗。拖着挪、双指或滑条放大（1–4 倍）。
 * 输出时把方框外再多带半圈（系统遮罩看不到，但视差动效会露一点）。
 */
@Composable
fun IconCropHost() {
    val src = IconCrop.bmp ?: return
    val img = remember(src) { src.asImageBitmap() }
    val d = LocalDensity.current
    val scope = rememberCoroutineScope()
    val show = remember(src) { Animatable(0f) }
    var k by remember(src) { mutableFloatStateOf(1f) }
    var ox by remember(src) { mutableFloatStateOf(0f) }
    var oy by remember(src) { mutableFloatStateOf(0f) }
    var busy by remember(src) { mutableStateOf(false) }
    val stage = 300.dp
    val winPx = with(d) { 216.dp.toPx() }
    val base = winPx / min(src.width, src.height)

    fun clamp() {
        val s = base * k
        val hw = (src.width * s - winPx) / 2; val hh = (src.height * s - winPx) / 2
        ox = ox.coerceIn(-hw, hw); oy = oy.coerceIn(-hh, hh)
    }
    fun close(out: Bitmap?) { if (busy) return; busy = true; scope.launch { show.animateTo(0f, tween(220, easing = Curves.accel)); IconCrop.finish(out) } }
    fun use() {
        val s = base * k; val half = winPx * .75f
        val l = src.width / 2f + (-half - ox) / s; val t = src.height / 2f + (-half - oy) / s; val side = 2 * half / s
        val out = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
        val c = ACanvas(out); c.drawColor(0xFF1A1720.toInt())
        // 方框外多带的那半圈可能超出原图：只取和原图相交的部分，对应地画到目标里
        val il = max(0f, l); val top = max(0f, t); val ir = min(src.width.toFloat(), l + side); val ib = min(src.height.toFloat(), t + side)
        if (ir > il && ib > top) {
            val f = 432f / side
            c.drawBitmap(src, android.graphics.Rect(il.roundToInt(), top.roundToInt(), ir.roundToInt(), ib.roundToInt()),
                RectF((il - l) * f, (top - t) * f, (ir - l) * f, (ib - t) * f), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        close(out)
    }
    LaunchedEffect(src) { show.animateTo(1f, spring(dampingRatio = .8f, stiffness = 380f)) }
    BackHandler { close(null) }

    Box(
        Modifier.fillMaxSize().background(Color(0xFF050609).copy(alpha = .86f * show.value.coerceIn(0f, 1f)))
            .pointerInput(Unit) { detectTapGestures { close(null) } },
    ) {
        Column(Modifier.align(Alignment.Center).pointerInput(Unit) { detectTapGestures { } }, horizontalAlignment = Alignment.CenterHorizontally) {
            Txt("调整图标", Page.INK, 16.sp, Modifier.padding(bottom = 14.dp).graphicsLayer { alpha = show.value.coerceIn(0f, 1f) })
            Box(
                Modifier.size(stage)
                    .graphicsLayer { val t = show.value; alpha = t.coerceIn(0f, 1f); scaleX = .92f + .08f * t; scaleY = .92f + .08f * t }
                    .clip(RoundedCornerShape(26.dp))
                    .pointerInput(src) { detectTransformGestures { _, pan, zoom, _ -> k = (k * zoom).coerceIn(1f, 4f); ox += pan.x; oy += pan.y; clamp() } },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val s = base * k
                    val w = src.width * s; val h = src.height * s
                    drawImage(img, dstOffset = IntOffset((size.width / 2 + ox - w / 2).roundToInt(), (size.height / 2 + oy - h / 2).roundToInt()), dstSize = IntSize(w.roundToInt(), h.roundToInt()))
                    val tl = Offset((size.width - winPx) / 2, (size.height - winPx) / 2)
                    val win = RoundRect(tl.x, tl.y, tl.x + winPx, tl.y + winPx, CornerRadius(winPx * .3f))
                    val shade = Path().apply { fillType = PathFillType.EvenOdd; addRect(androidx.compose.ui.geometry.Rect(Offset.Zero, size)); addRoundRect(win) }
                    drawPath(shade, Color(0xFF0A080C).copy(alpha = .62f))
                    drawPath(Path().apply { addRoundRect(win) }, Color.White.copy(alpha = .45f), style = Stroke(1.2.dp.toPx()))
                }
            }
            Column(
                Modifier.width(stage).padding(top = 20.dp).graphicsLayer { val t = show.value.coerceIn(0f, 1f); alpha = t; translationY = (1f - t) * 8f },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Txt("小", Page.MUTED, 12.sp)
                    Box(Modifier.weight(1f).padding(horizontal = 12.dp)) { FeelSlider(k, 1f, 4f, .01f, onDone = {}) { k = it; clamp() } }
                    Txt("大", Page.MUTED, 12.sp)
                }
                Txt("拖着挪位置；亮着的地方就是图标", Page.MUTED.copy(alpha = .6f), 12.sp, Modifier.padding(top = 12.dp, bottom = 16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CropBtn("取消", false, Modifier.weight(1f)) { close(null) }
                    CropBtn("放到桌面", true, Modifier.weight(1f)) { if (!busy) use() }
                }
            }
        }
    }
}

@Composable
private fun CropBtn(text: String, main: Boolean, modifier: Modifier, onClick: () -> Unit) {
    PressSurface(onClick, modifier.height(44.dp), fill = if (main) warm else Color.White.copy(alpha = .08f), pressedFill = if (main) warm.copy(alpha = .85f) else Color.White.copy(alpha = .13f), pressScale = .96f, label = text) {
        Txt(text, if (main) Color(0xFF2A211B) else Page.INK, 14.5.sp)
    }
}
