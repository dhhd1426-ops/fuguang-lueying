package com.fuguang.lueying

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min
import kotlin.math.roundToInt

private val OpenEase = CubicBezierEasing(.2f, .9f, .25f, 1f)
private val FlyEase = CubicBezierEasing(.3f, .7f, .2f, 1f)

/** 一个变换：平移、缩放（以左上角为原点）、只露出本地的哪一块（裁成小图的正方形时用）、屏幕上的圆角。 */
private data class Xf(val tx: Float, val ty: Float, val s: Float, val ox: Float, val oy: Float, val cw: Float, val ch: Float, val r: Float) {
    fun to(b: Xf, t: Float) = Xf(tx + (b.tx - tx) * t, ty + (b.ty - ty) * t, s + (b.s - s) * t, ox + (b.ox - ox) * t, oy + (b.oy - oy) * t, cw + (b.cw - cw) * t, ch + (b.ch - ch) * t, r + (b.r - r) * t)
}

private class Crop(val x: Xf) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
        Outline.Rounded(RoundRect(x.ox, x.oy, x.ox + x.cw, x.oy + x.ch, CornerRadius(x.r / x.s)))
}

/** 看图页：画在 App 最上层。 */
@Composable
fun PhotoLayer() {
    val o = PhotoView.open ?: return
    key(o) { PhotoPage(o) }
}

private object Phase { const val OPEN = 0; const val IDLE = 1; const val CLOSE = 2; const val FLY = 3 }

/**
 * 从小图长出来（360ms），轻点显隐按钮，往下拖跟手缩小、松手过 110dp 或甩下去就缩回小图（260ms 加速）。
 * 羽毛进圈画；✓ 合成新图，看图页缩小飞进输入框（440ms），飞到七成时输入框里那张淡入接手。
 */
@Composable
private fun PhotoPage(o: PhotoView.Open) {
    val ctx = LocalContext.current
    val d = LocalDensity.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val img by produceState<ImageBitmap?>(null, o.ref) { value = withContext(Dispatchers.IO) { load(o.ref) } }
    var host by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val ink = remember { PhotoInkState(o.lines) }
    var bars by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf(Phase.OPEN) }
    val u = remember { Animatable(0f) }
    val bg = remember { Animatable(0f) }
    var from by remember { mutableStateOf<Xf?>(null) }
    var target by remember { mutableStateOf(o.thumb) }
    var drag by remember { mutableStateOf(Offset.Zero) }
    val radius = with(d) { (if (o.tray) 14.dp else 16.dp).toPx() }

    val im = img
    val W = box.width.toFloat(); val H = box.height.toFloat()
    val ready = im != null && W > 0f
    val ratio = if (im != null) im.height.toFloat() / im.width else 1f
    var fw = W; var fh = W * ratio
    if (fh > H * .8f) { fh = H * .8f; fw = fh / ratio }
    val fx = (W - fw) / 2; val fy = (H - fh) / 2
    val still = Xf(0f, 0f, 1f, 0f, 0f, fw, fh, 0f)
    fun thumb(ref: String): Xf? = PhotoView.spots[ref]?.let { sp ->
        val side = min(fw, fh); val s = sp.width / side; val ox = (fw - side) / 2; val oy = (fh - side) / 2
        Xf(sp.left - host.x - fx - s * ox, sp.top - host.y - fy - s * oy, s, ox, oy, side, side, radius)
    }
    fun dragged(): Xf {
        val dy = if (drag.y > 0) drag.y else drag.y * .3f
        val sc = 1f - dy.coerceIn(0f, H * .5f) / (H * 2f)
        return Xf(drag.x * .6f + fw * (1 - sc) / 2, dy + fh * (1 - sc) / 2, sc, 0f, 0f, fw, fh, 0f)
    }
    fun now(): Xf = when (phase) {
        Phase.OPEN -> (thumb(o.thumb) ?: still).to(still, u.value)
        Phase.IDLE -> if (drag == Offset.Zero) still else dragged()
        else -> { val a = from ?: still; a.to(thumb(target) ?: a, u.value) }
    }
    fun close() {
        if (phase != Phase.IDLE) return
        from = now(); drag = Offset.Zero; target = o.thumb; phase = Phase.CLOSE; bars = false; ink.editing = false
        scope.launch {
            launch { bg.animateTo(0f, tween(260, easing = Curves.accel)) }
            u.snapTo(0f); u.animateTo(1f, tween(260, easing = Curves.accel))
            PhotoView.hidden = null; PhotoView.open = null
        }
    }
    fun done() {
        if (ink.busy) return; ink.busy = true
        scope.launch {
            val lines = ink.lines.toList()
            val made = PhotoView.bake(o.ref, lines) ?: run { ink.busy = false; return@launch }
            PhotoView.marks[made] = o.ref to lines
            PhotoView.landing = made; PhotoView.landK = 0f
            val atts = o.chat.atts
            if (o.tray) {
                val i = atts.indexOf(o.thumb); if (i >= 0) atts[i] = made else atts.add(made)
                if (PhotoView.marks.remove(o.thumb) != null) Attach.drop(o.thumb)
            } else atts.add(made)
            ink.editing = false; ink.ask = false; bars = false
            withTimeoutOrNull(600) { snapshotFlow { PhotoView.spots[made] }.first { it != null } }
            from = now(); target = made; phase = Phase.FLY; PhotoView.hidden = null
            launch { bg.animateTo(0f, tween(360, easing = Curves.standard)) }
            u.snapTo(0f)
            u.animateTo(1f, tween(440, easing = FlyEase)) { PhotoView.landK = ((value - .7f) / .3f).coerceIn(0f, 1f) }
            PhotoView.landK = 1f; PhotoView.landing = null
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            o.chat.focusTick++
            PhotoView.open = null
        }
    }
    fun share() = runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", Attach.file(o.ref))
        val send = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        launch { bg.animateTo(1f, tween(320, easing = Curves.standard)) }
        launch { delay(120); bars = true }
        u.animateTo(1f, tween(360, easing = OpenEase))
        phase = Phase.IDLE
    }
    BackHandler { when { ink.ask -> ink.ask = false; ink.editing -> ink.leave(); else -> close() } }

    val pull = if (H > 0f) (drag.y.coerceAtLeast(0f) / (H * .5f)).coerceAtMost(.85f) else 0f
    Box(
        Modifier.fillMaxSize()
            .onGloballyPositioned { host = it.positionInWindow() }.onSizeChanged { box = it }
            .background(Color(0xFF050406).copy(alpha = bg.value * (1f - pull)))
            .pointerInput(ink.editing, phase) {
                if (ink.editing || phase != Phase.IDLE) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val vt = VelocityTracker(); vt.addPosition(down.uptimeMillis, down.position)
                    var total = Offset.Zero; var moved = false; var upAt = down.uptimeMillis
                    var tapOk = true
                    while (true) {
                        val e = awaitPointerEvent(); val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        upAt = c.uptimeMillis
                        // 点在按钮上：按钮已经接走了，这里不再显隐
                        if (!c.pressed) { tapOk = !c.isConsumed; break }
                        vt.addPosition(c.uptimeMillis, c.position); total += c.positionChange()
                        if (!moved && total.getDistance() > viewConfiguration.touchSlop) { moved = true; bars = false }
                        if (moved) { drag = total; c.consume() }
                    }
                    if (!moved) { if (tapOk && upAt - down.uptimeMillis < 300) bars = !bars; return@awaitEachGesture }
                    if (total.y > 110.dp.toPx() || vt.calculateVelocity().y > 1600f) close()
                    else scope.launch { animate(Offset.VectorConverter, drag, Offset.Zero, animationSpec = spring(.82f, 420f)) { v, _ -> drag = v }; bars = true }
                }
            },
    ) {
        if (ready && im != null) Box(
            Modifier.offset { IntOffset(fx.roundToInt(), fy.roundToInt()) }.size(with(d) { fw.toDp() }, with(d) { fh.toDp() })
                .graphicsLayer {
                    val x = now()
                    transformOrigin = TransformOrigin(0f, 0f)
                    translationX = x.tx; translationY = x.ty; scaleX = x.s; scaleY = x.s
                    clip = true; shape = Crop(x)
                    alpha = when {
                        phase == Phase.FLY -> 1f - PhotoView.landK
                        phase == Phase.OPEN && PhotoView.spots[o.thumb] == null -> u.value
                        phase == Phase.CLOSE && PhotoView.spots[target] == null -> 1f - u.value
                        else -> 1f
                    }
                },
        ) {
            Image(im, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            InkCanvas(ink)
        }
        PhotoBars(ink, viewOn = bars && !ink.editing && phase == Phase.IDLE, editOn = ink.editing && phase == Phase.IDLE,
            onClose = ::close, onShare = { share() }, onDone = ::done)
    }
}

/** 按要的大小缩着读：长边不超过 2048。 */
private fun load(ref: String): ImageBitmap? = runCatching {
    val f = Attach.file(ref)
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.path, o)
    var s = 1
    while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= 2048) s *= 2
    BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
}.getOrNull()

/** 墨迹层：画好的每一笔、正在画的这一笔、撤销时淡出的那笔 / 恢复时顺着笔顺重新画出来的那笔。圈画时接手指。 */
@Composable
private fun InkCanvas(ink: PhotoInkState) {
    val d = LocalDensity.current
    Canvas(
        Modifier.fillMaxSize().graphicsLayer { alpha = ink.fade.value }.pointerInput(ink.editing) {
            if (!ink.editing) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(); down.consume(); ink.ask = false; ink.redo.clear()
                val w = size.width.toFloat(); val h = size.height.toFloat()
                ink.pen.clear(); ink.pen.add(floatArrayOf(down.position.x / w, down.position.y / h, 1f))
                var last = down.position; var lastT = down.uptimeMillis; var f = 1f
                while (true) {
                    val e = awaitPointerEvent(); val c = e.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) break
                    c.consume()
                    val dist = (c.position - last).getDistance()
                    if (dist < 1.5f * d.density) continue
                    f = inkSpeed(f, dist / d.density, c.uptimeMillis - lastT)
                    ink.pen.add(floatArrayOf(c.position.x / w, c.position.y / h, f)); last = c.position; lastT = c.uptimeMillis
                }
                ink.lines.add(InkLine(ink.color, PhotoInkState.WIDTH, ink.pen.toList())); ink.pen.clear()
            }
        },
    ) {
        val fx = ink.fx
        ink.lines.forEach { l -> if (fx == null || fx.first !== l) drawInk(l) }
        if (ink.pen.isNotEmpty()) drawInk(InkLine(ink.color, PhotoInkState.WIDTH, ink.pen.toList()))
        if (fx != null) { val k = ink.fxK.value; if (fx.second) drawInk(fx.first, prog = k) else drawInk(fx.first, alpha = 1f - k) }
    }
}
