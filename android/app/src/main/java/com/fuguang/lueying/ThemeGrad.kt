package com.fuguang.lueying

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 主题页「渐变」下面的配色（原型 4VnzJUm4TY6ng7owGK3mzE 第 4 版）：
 * 横着一张一张的配色卡，手指拖着走，[Skin.pal] 跟着连续变，整个 App 的底色、字色、点缀色一路过渡；
 * 松手按手速吸到最近或下一套（弹簧 k180 c24，几乎不回弹），中途再按住从当前位置接着拖。
 * 下面一排小点：点一下跳过去；按住 0.3 秒放大、震一下，左右滑每 28dp 换一套、每跨一套轻震，松手吸住。
 */
@Composable
internal fun GradPalettes() {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val anim = remember { Animatable(Skin.pal) }
    val last = Grad.all.lastIndex
    var target by remember { mutableStateOf(Skin.pal.roundToInt()) }

    fun go(n: Int, v: Float = 0f) {
        val t = n.coerceIn(0, last); target = t
        scope.launch {
            anim.snapTo(Skin.pal)
            anim.animateTo(t.toFloat(), spring(dampingRatio = .89f, stiffness = 180f, visibilityThreshold = .0005f), v) { Skin.pal = value }
            Skin.savePal(t)
        }
    }
    fun hold() = scope.launch { anim.stop() }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 14.dp, bottom = 8.dp)) {
            Txt("配色", Page.muted, 12.sp, Modifier.weight(1f), spacing = ls(1f, 12f))
            Txt("${Grad.all[Skin.pal.roundToInt().coerceIn(0, last)].n} / 0${Grad.all.size}", Page.muted, 12.sp)
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(132.dp)) {
            val w = constraints.maxWidth.toFloat()
            Box(
                Modifier.fillMaxWidth().height(132.dp)
                    .semantics { contentDescription = "配色，左右滑动切换" }
                    .pointerInput(w) {
                        val vt = VelocityTracker()
                        var p0 = 0f; var dx = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { hold(); vt.resetTracking(); p0 = Skin.pal; dx = 0f },
                            onHorizontalDrag = { ch, d ->
                                vt.addPosition(ch.uptimeMillis, ch.position); dx += d
                                var p = p0 - dx / w
                                if (p < 0f) p *= .35f
                                if (p > last) p = last + (p - last) * .35f
                                Skin.pal = p; ch.consume()
                            },
                            onDragEnd = {
                                val v = -vt.calculateVelocity().x / w
                                val pos = Skin.pal
                                val n = if (abs(v) > .6f) (if (v > 0) floor(pos).toInt() + 1 else ceil(pos).toInt() - 1) else pos.roundToInt()
                                go(n, v)
                            },
                            onDragCancel = { go(Skin.pal.roundToInt()) },
                        )
                    },
            ) {
                Grad.all.forEachIndexed { i, p ->
                    val d = abs(Skin.pal - i)
                    if (d < 1.5f) Slide(i, p, Modifier.width(with(LocalDensity.current) { w.toDp() }).graphicsLayer {
                        translationX = (i - Skin.pal) * w
                        alpha = 1f - d.coerceAtMost(1f) * .55f
                    }, d)
                }
            }
        }
        Dots(Skin.pal, onTap = { go(it) }, onScrub = { hold() }, onMove = { Skin.pal = it }, onEnd = { go(Skin.pal.roundToInt()) }, haptic = { long ->
            haptic.performHapticFeedback(if (long) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove)
        })
        Txt("拖着配色走，整个 App 跟着换；按住下面的小点也能左右滑。", Page.muted.copy(alpha = .8f), 11.5.sp, Modifier.padding(horizontal = 8.dp, vertical = 8.dp), lineHeight = 19.sp)
    }
}

/** 一张配色：左右两色的药丸（两端各一团同色的柔光），下面「04. 梦境的粉色泡泡」。 */
@Composable
private fun Slide(i: Int, p: Grad.Pal, modifier: Modifier, d: Float) {
    val a = Color(p.a); val b = Color(p.b); val k = Grad.capInk(i)
    Column(modifier.padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.fillMaxWidth().height(70.dp)
                .graphicsLayer { val s = 1f - d.coerceAtMost(1f) * .06f; scaleX = s; scaleY = s }
                .drawBehind {
                    // 两端底下各一团柔光
                    listOf(a to .3f, b to .7f).forEach { (c, x) ->
                        drawOval(
                            Brush.radialGradient(listOf(c.copy(alpha = .9f), c.copy(alpha = 0f)), Offset(size.width * x, size.height + 6.dp.toPx()), size.width * .26f),
                            Offset(size.width * (x - .26f), size.height - 8.dp.toPx()), Size(size.width * .52f, 28.dp.toPx()),
                        )
                    }
                }
                .background(Brush.horizontalGradient(listOf(a, b)), RoundedCornerShape(50))
                .border(1.5.dp, Color.White.copy(alpha = .9f), RoundedCornerShape(50))
                .padding(horizontal = 20.dp),
        ) {
            Column(Modifier.align(Alignment.CenterStart)) {
                Txt(hex(p.a), k, 13.sp, weight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Txt(p.aName, k.copy(alpha = .7f), 9.sp)
            }
            Column(Modifier.align(Alignment.CenterEnd), horizontalAlignment = Alignment.End) {
                Txt(hex(p.b), k, 13.sp, weight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Txt(p.bName, k.copy(alpha = .7f), 9.sp)
            }
        }
        Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.Bottom) {
            Txt("${p.n}.", k, 17.sp, weight = androidx.compose.ui.text.font.FontWeight.SemiBold, spacing = ls(.7f, 17f))
            Spacer(Modifier.width(10.dp))
            Txt(p.title, k, 14.5.sp, weight = androidx.compose.ui.text.font.FontWeight.Medium, maxLines = 1)
        }
    }
}

private fun hex(c: Long) = "#" + String.format("%06X", c and 0xFFFFFF)

/**
 * 一排小点，当前那颗拉长。点一下跳过去；按住 0.3 秒放大、震一下，之后左右滑每 28dp 换一套，
 * 每跨一套轻震一下，松手吸住。按下当帧整排略放大。
 */
@Composable
private fun Dots(pos: Float, onTap: (Int) -> Unit, onScrub: () -> Unit, onMove: (Float) -> Unit, onEnd: () -> Unit, haptic: (Boolean) -> Unit) {
    val n = Grad.all.size
    var pressed by remember { mutableStateOf(false) }
    var scrub by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (scrub) 1.3f else if (pressed) 1.08f else 1f, if (scrub) spring(.85f, 500f) else tween(if (pressed) 90 else 220, easing = Curves.standard), label = "dots")
    val step = with(LocalDensity.current) { 28.dp.toPx() }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            Modifier.height(44.dp).padding(horizontal = 16.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .semantics { contentDescription = "配色 ${pos.roundToInt() + 1} / $n，按住左右滑" }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        pressed = true
                        val up = withTimeoutOrNull(300) { waitForUpOrCancellation() }
                        if (up != null) {
                            pressed = false
                            val inner = size.width - 32.dp.toPx()
                            onTap((((up.position.x - 16.dp.toPx()) / inner) * n - .5f).roundToInt().coerceIn(0, n - 1))
                            return@awaitEachGesture
                        }
                        // 没松手：进入滑动
                        scrub = true; pressed = false; haptic(true); onScrub()
                        val p0 = Skin.pal; var x = 0f; var lastN = p0.roundToInt()
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            x += c.positionChange().x; c.consume()
                            val p = (p0 + x / step).coerceIn(0f, (n - 1).toFloat())
                            onMove(p)
                            val k = p.roundToInt(); if (k != lastN) { lastN = k; haptic(false) }
                        }
                        scrub = false; onEnd()
                    }
                },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(n) { i ->
                val k = (1f - abs(pos - i)).coerceIn(0f, 1f)
                Box(Modifier.width((6 + 12 * k).dp).height(6.dp).background(Page.ink.copy(alpha = .18f + .62f * k), RoundedCornerShape(3.dp)))
            }
        }
    }
}
