package com.fuguang.lueying

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 液态加号：按住加号，三颗小球（照片、拍照、文件）带着黏连从加号里“滴”出来，后面整屏压暗；滑到一颗上松手就是选它，别处松手融回去。 */
class LiquidPlusState {
    var open by mutableStateOf(false)
    /** 加号圆心，屏幕坐标（px）。 */
    var center by mutableStateOf(Offset.Unspecified)
    /** 加号左上角，屏幕坐标（px），用来把手指位置换算成屏幕坐标。 */
    var origin by mutableStateOf(Offset.Zero)
    /** 手指正停在哪颗球上：-1 = 没有。 */
    var hover by mutableStateOf(-1)
    /** 选了哪颗：0 照片、1 拍照、2 文件（AttachTray.kt 的 rememberAttachPick）。 */
    var pick: (Int) -> Unit = {}
}

private val GooColor = Color(0xFF2B2F37)
private val GooHover = Color(0xFF4A3F37)
private val Warm = Color(0xFFE9B48E)
private val Items = listOf("照片" to Ic("M4 7.5A2.5 2.5 0 0 1 6.5 5h11A2.5 2.5 0 0 1 20 7.5v9a2.5 2.5 0 0 1-2.5 2.5h-11A2.5 2.5 0 0 1 4 16.5zM5 17l4.5-4.5 3.5 3.5 2.5-2.5L20 17", circles = listOf(floatArrayOf(9.5f, 10f, 1.6f))),
    "拍照" to Ic("M4 8.5A1.5 1.5 0 0 1 5.5 7H8l1.5-2h5L16 7h2.5A1.5 1.5 0 0 1 20 8.5v9a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 17.5z", circles = listOf(floatArrayOf(12f, 12.5f, 3.2f))),
    "文件" to Ic("M7 3.5h7l4 4V20.5H7zM14 3.5v4h4"))

/** 三颗球相对加号圆心的落点（dp）。 */
private val Spots = listOf(Offset(-2f, -128f), Offset(58f, -118f), Offset(112f, -96f))

/** 输入框里的加号：按住约半秒，小球滴出；手指不松滑到某颗球上再松开就是选它，在别处松开就融回去。 */
@Composable
fun LiquidPlusButton(state: LiquidPlusState, onPick: (Int) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val rot = remember { Animatable(0f) }
    LaunchedEffect(state.open) { rot.animateTo(if (state.open) 45f else 0f, spring(dampingRatio = .5f, stiffness = Spring.StiffnessMediumLow)) }
    Box(
        Modifier
            .size(36.dp)
            .onGloballyPositioned { state.center = it.boundsInRoot().center; state.origin = it.positionInRoot() }
            .border(1.dp, Page.LINE, CircleShape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    // 没按够时间就松开：什么都不做
                    val early = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation() }
                    if (early != null) return@awaitEachGesture
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    state.open = true
                    val hitR = 34.dp.toPx()
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull() ?: break
                        val p = state.origin + ch.position
                        var h = -1
                        Spots.forEachIndexed { k, sp ->
                            val c = state.center + Offset(sp.x.dp.toPx(), sp.y.dp.toPx())
                            if ((p - c).getDistance() < hitR) h = k
                        }
                        if (h != state.hover) {
                            if (h >= 0) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            state.hover = h
                        }
                        if (!ch.pressed) break
                        ch.consume()
                    }
                    val picked = state.hover
                    state.hover = -1
                    state.open = false
                    if (picked >= 0) onPick(picked)
                }
            },
        contentAlignment = Alignment.Center,
    ) { SvgIcon(Ic("M12 5v14M5 12h14"), Page.INK, 18.dp, modifier = Modifier.rotate(rot.value)) }
}

/** 盖在整页上的液态层：黏连的球 + 球上的图标。只在打开或动画中绘制。 */
@Composable
fun LiquidPlusLayer(state: LiquidPlusState, onPick: (Int) -> Unit) {
    val anims = remember { List(3) { Animatable(0f) } }
    val base = remember { Animatable(0f) }
    LaunchedEffect(state.open) {
        if (state.open) {
            launch { base.animateTo(1f, tween(160)) }
            anims.forEachIndexed { k, a -> launch { delay(k * 60L); a.animateTo(1f, spring(dampingRatio = .52f, stiffness = 260f)) } }
        } else {
            anims.forEachIndexed { k, a -> launch { delay((2 - k) * 40L); a.animateTo(0f, spring(dampingRatio = .9f, stiffness = 420f)) } }
            launch { delay(260); base.animateTo(0f, tween(160)) }
        }
    }
    val active = state.open || anims.any { it.value > .001f } || base.value > .001f
    if (!active || state.center == Offset.Unspecified) return
    var origin by remember { mutableStateOf(Offset.Zero) }
    val d = LocalDensity.current
    val r = with(d) { 22.dp.toPx() }
    val c = state.center - origin

    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        // 托底：后面整屏压暗，小球和字不再压在她的话上看不清；点空白处收起
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = base.value }.background(Color(0xA80A0B0E)))
        if (state.open) Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { state.open = false } })
        // 黏连层：模糊 + 透明度对比（Android 12 起有效；更早的系统就是普通的球）
        Canvas(
            Modifier.fillMaxSize().graphicsLayer {
                if (Build.VERSION.SDK_INT >= 31) {
                    val blur = RenderEffect.createBlurEffect(18f, 18f, Shader.TileMode.DECAL)
                    val m = ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 24f, -10f * 255f))
                    renderEffect = RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(m), blur).asComposeRenderEffect()
                }
            },
        ) {
            drawCircle(GooColor, r * .82f, c, alpha = base.value)
            anims.forEachIndexed { k, a ->
                val p = Offset(Spots[k].x.dp.toPx(), Spots[k].y.dp.toPx()) * a.value
                val grow = if (state.hover == k) 1.18f else 1f
                drawCircle(if (state.hover == k) GooHover else GooColor, r * grow * (.55f + .45f * a.value.coerceIn(0f, 1f)), c + p)
            }
        }
        // 图标与文字，不加滤镜，保持清楚
        anims.forEachIndexed { k, a ->
            val t = a.value
            if (t < .35f) return@forEachIndexed
            val p = c + Offset(Spots[k].x.dp.toPx(d), Spots[k].y.dp.toPx(d)) * t
            Column(
                Modifier
                    .offset { IntOffset((p.x - r).roundToInt(), (p.y - r).roundToInt()) }
                    .graphicsLayer { val g = if (state.hover == k) 1.12f else 1f; scaleX = g; scaleY = g }
                    .alpha(((t - .35f) / .5f).coerceIn(0f, 1f)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(44.dp).pointerInput(k) { detectTapGestures { onPick(k); state.open = false } },
                    contentAlignment = Alignment.Center,
                ) { SvgIcon(Items[k].second, if (state.hover == k) Warm else Page.INK, 19.dp) }
                Txt(Items[k].first, if (state.hover == k) Warm else Page.INK, 11.5.sp, Modifier.padding(top = 4.dp))
            }
        }
    }
}

private fun androidx.compose.ui.unit.Dp.toPx(d: androidx.compose.ui.unit.Density) = with(d) { this@toPx.toPx() }
