package com.fuguang.lueying

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

/** 裁头像：相册选好图后打开；用完图飞回 target（头像在屏幕上的位置）。 */
object AvatarCrop {
    var bmp by mutableStateOf<Bitmap?>(null)
        private set
    var who = ""
        private set
    var target = Rect.Zero
    /** 每落下一次加一：名片上那颗头像轻弹一下。 */
    var landed by mutableIntStateOf(0)
        private set

    fun open(b: Bitmap, id: String, at: Rect) { who = id; target = at; bmp = b }
    internal fun done(saved: Boolean) { bmp = null; if (saved) landed++ }
}

private val warm = Color(0xFFE9B48E)
private val glide = CubicBezierEasing(.3f, 0f, .1f, 1f)

/**
 * 取景：中间一个圆，拖着挪位置，双指或下面的滑条放大（1–3 倍）。「用这张」裁成 256×256，圆里的图缩小飞回头像，落下时轻弹、轻震。
 * 「取消」/ 返回键 / 点空白：取景框缩回去。
 */
@Composable
fun AvatarCropHost() {
    val src = AvatarCrop.bmp ?: return
    val img = remember(src) { src.asImageBitmap() }
    val d = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val show = remember(src) { Animatable(0f) }
    val fly = remember(src) { Animatable(0f) }
    var k by remember(src) { mutableFloatStateOf(1f) }
    var ox by remember(src) { mutableFloatStateOf(0f) }
    var oy by remember(src) { mutableFloatStateOf(0f) }
    var stage by remember { mutableStateOf(Rect.Zero) }
    var host by remember { mutableStateOf(Offset.Zero) }
    var cut by remember(src) { mutableStateOf<ImageBitmap?>(null) }
    var busy by remember(src) { mutableStateOf(false) }
    val dia = 260.dp
    val diaPx = with(d) { dia.toPx() }
    val base = diaPx / min(src.width, src.height)

    fun clamp() {
        val s = base * k
        val hw = (src.width * s - diaPx) / 2; val hh = (src.height * s - diaPx) / 2
        ox = ox.coerceIn(-hw, hw); oy = oy.coerceIn(-hh, hh)
    }
    fun cancel() { if (busy) return; busy = true; scope.launch { show.animateTo(0f, tween(200, easing = Curves.accel)); AvatarCrop.done(false) } }
    fun use() {
        if (busy) return; busy = true
        val s = base * k; val r = diaPx / 2
        val side = (2 * r / s).roundToInt().coerceIn(1, min(src.width, src.height))
        val sx = (src.width / 2f - (ox + r) / s).roundToInt().coerceIn(0, src.width - side)
        val sy = (src.height / 2f - (oy + r) / s).roundToInt().coerceIn(0, src.height - side)
        val out = Bitmap.createScaledBitmap(Bitmap.createBitmap(src, sx, sy, side, side), 256, 256, true)
        cut = out.asImageBitmap()
        scope.launch {
            launch { show.animateTo(0f, tween(320, easing = Curves.standard)) }
            fly.animateTo(1f, tween(440, easing = glide))
            Avatars.set(AvatarCrop.who, out)
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            AvatarCrop.done(true)
        }
    }
    LaunchedEffect(src) { show.animateTo(1f, spring(dampingRatio = .78f, stiffness = 380f)) }
    BackHandler { cancel() }

    Box(
        Modifier.fillMaxSize().onGloballyPositioned { host = it.boundsInRoot().topLeft }
            .background(Color(0xFF050609).copy(alpha = .86f * show.value.coerceIn(0f, 1f)))
            .pointerInput(Unit) { detectTapGestures { cancel() } },
    ) {
        Column(Modifier.align(Alignment.Center).pointerInput(Unit) { detectTapGestures { } }, horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(dia)
                    .graphicsLayer { val t = show.value; alpha = if (cut != null) 0f else t.coerceIn(0f, 1f); scaleX = .9f + .1f * t; scaleY = .9f + .1f * t }
                    .onGloballyPositioned { stage = it.boundsInRoot() }
                    .clip(CircleShape)
                    .pointerInput(src) {
                        detectTransformGestures { _, pan, zoom, _ -> k = (k * zoom).coerceIn(1f, 3f); ox += pan.x; oy += pan.y; clamp() }
                    },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val s = base * k
                    val w = src.width * s; val h = src.height * s
                    drawImage(
                        img, dstOffset = IntOffset((size.width / 2 + ox - w / 2).roundToInt(), (size.height / 2 + oy - h / 2).roundToInt()),
                        dstSize = androidx.compose.ui.unit.IntSize(w.roundToInt(), h.roundToInt()),
                    )
                    drawCircle(Color(0xFFFAF5EE).copy(alpha = .5f), size.minDimension / 2 - 1, style = Stroke(1.5f))
                }
            }
            Column(
                Modifier.width(dia).padding(top = 22.dp).graphicsLayer { val t = show.value.coerceIn(0f, 1f); alpha = t; translationY = (1f - t) * 8f },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Txt("小", Page.muted, 12.sp)
                    Box(Modifier.weight(1f).padding(horizontal = 12.dp)) { FeelSlider(k, 1f, 3f, .01f, onDone = {}) { k = it; clamp() } }
                    Txt("大", Page.muted, 12.sp)
                }
                Txt("拖着挪位置", Page.muted.copy(alpha = .6f), 12.sp, Modifier.padding(top = 12.dp, bottom = 16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CropButton("取消", false, Modifier.weight(1f)) { cancel() }
                    CropButton("用这张", true, Modifier.weight(1f)) { use() }
                }
            }
        }
        // 圆里的图缩小飞回头像
        cut?.let { c ->
            val t = fly.value
            val from = stage; val to = AvatarCrop.target
            val l = from.left + (to.left - from.left) * t - host.x
            val tp = from.top + (to.top - from.top) * t - host.y
            val sz = from.width + (to.width - from.width) * t
            Image(
                c, null,
                Modifier.offset { IntOffset(l.roundToInt(), tp.roundToInt()) }.size(with(d) { sz.toDp() }).clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun CropButton(text: String, main: Boolean, modifier: Modifier, onClick: () -> Unit) {
    PressSurface(onClick, modifier.height(44.dp), fill = if (main) warm else Color.White.copy(alpha = .08f), pressedFill = if (main) warm.copy(alpha = .85f) else Color.White.copy(alpha = .13f), pressScale = .96f, label = text) {
        Txt(text, if (main) Color(0xFF2A211B) else Page.ink, 14.5.sp)
    }
}
