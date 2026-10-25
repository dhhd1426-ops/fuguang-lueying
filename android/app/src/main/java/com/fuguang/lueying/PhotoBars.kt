package com.fuguang.lueying

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Glass = Color(0xFF1E1A22).copy(alpha = .62f)
private val Warm = Color(0xFFE9B48E)
private val OnWarm = Color(0xFF2A1D14)
private val Bright = Color(0xFFECE6DF)
private object PhotoIc {
    val share = Ic("M12 15V4M8 8l4-4 4 4M5 13v5a2 2 0 002 2h10a2 2 0 002-2v-5")
    val undo = Ic("M9 14L4 9l5-5M4 9h10a6 6 0 010 12h-3")
    val redo = Ic("M15 14l5-5-5-5M20 9H10a6 6 0 000 12h3")
    val check = Ic("M5 12.5l4.5 4.5L19 7.5")
}

/**
 * 看图页上面的按钮：看的时候「× ‖ 羽毛 分享」，圈画时换成「× 撤销 恢复 ‖ ✓」，两组淡入淡出交替（200ms，带 8dp 位移）。
 * 圈画时底下一排墨色；有改动时点 × 从 × 下面长出「刚才画的不要了吗？」。
 */
@Composable
internal fun BoxScope.PhotoBars(ink: PhotoInkState, viewOn: Boolean, editOn: Boolean, onClose: () -> Unit, onShare: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val v by animateFloatAsState(if (viewOn) 1f else 0f, tween(200, easing = Curves.standard), label = "viewBar")
    val e by animateFloatAsState(if (editOn) 1f else 0f, tween(200, easing = Curves.standard), label = "editBar")
    val ask by animateFloatAsState(if (ink.ask) 1f else 0f, if (ink.ask) tween(200, easing = Curves.decel) else tween(140, easing = Curves.accel), label = "ask")
    fun tick() = haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)

    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp)) {
        Box(Modifier.fillMaxWidth().height(48.dp)) {
            if (v > .01f) Row(Modifier.fillMaxWidth().graphicsLayer { alpha = v; translationY = -(1 - v) * 8.dp.toPx() }, verticalAlignment = Alignment.CenterVertically) {
                RoundBtn(onClose, "关闭") { SvgIcon(Icons.close, Bright, 20.dp, stroke = 1.8f) }
                Spacer(Modifier.weight(1f))
                RoundBtn({ tick(); ink.enter() }, "圈画") { FeatherGlyph(Color(0xFFF6F0E8), 26.dp) }
                Spacer(Modifier.width(10.dp))
                RoundBtn(onShare, "分享") { SvgIcon(PhotoIc.share, Bright, 20.dp, stroke = 1.7f) }
            }
            if (e > .01f) Row(Modifier.fillMaxWidth().graphicsLayer { alpha = e; translationY = -(1 - e) * 8.dp.toPx() }, verticalAlignment = Alignment.CenterVertically) {
                RoundBtn({ ink.leave() }, "退出圈画") { SvgIcon(Icons.close, Bright, 20.dp, stroke = 1.8f) }
                Spacer(Modifier.width(8.dp))
                Row(Modifier.clip(RoundedCornerShape(50)).background(Glass)) {
                    PillBtn({ tick(); ink.undo(scope) }, ink.lines.isNotEmpty(), "撤销") { SvgIcon(PhotoIc.undo, Bright, 20.dp, stroke = 1.7f) }
                    PillBtn({ tick(); ink.again(scope) }, ink.redo.isNotEmpty(), "恢复") { SvgIcon(PhotoIc.redo, Bright, 20.dp, stroke = 1.7f) }
                }
                Spacer(Modifier.weight(1f))
                val can = (ink.lines.isNotEmpty() || ink.dirty) && !ink.busy
                val fill by animateFloatAsState(if (can) 1f else 0f, tween(200), label = "done")
                RoundBtn(onDone, "完成", enabled = can, fill = lerpColor(Glass, Warm, fill), dim = if (can) 1f else .45f) {
                    if (ink.busy) LoadRing(OnWarm, 16.dp) else SvgIcon(PhotoIc.check, lerpColor(Bright, OnWarm, fill), 20.dp, stroke = 2f)
                }
            }
        }
        // 放弃确认：从 × 下面长出来
        if (ask > .01f) Column(
            Modifier.padding(top = 8.dp).width(236.dp)
                .graphicsLayer { alpha = ask; val s = .94f + .06f * ask; scaleX = s; scaleY = s; transformOrigin = TransformOrigin(.09f, 0f); translationY = -(1 - ask) * 6.dp.toPx() }
                .background(Color(0xFF1E1B22).copy(alpha = .96f), RoundedCornerShape(18.dp)).padding(14.dp),
        ) {
            Txt("刚才画的不要了吗？", Bright, 14.sp)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PressSurface({ ink.ask = false }, Modifier.weight(1f).height(40.dp), shape = RoundedCornerShape(12.dp), fill = Color.White.copy(alpha = .08f), pressedFill = Color.White.copy(alpha = .14f), pressScale = .96f, label = "继续画") { Txt("继续画", Bright, 14.sp) }
                PressSurface({ ink.drop(scope) }, Modifier.weight(1f).height(40.dp), shape = RoundedCornerShape(12.dp), fill = Color(0x2EE0503F), pressedFill = Color(0x47E0503F), pressScale = .96f, label = "不要了") { Txt("不要了", Color(0xFFF0A093), 14.sp) }
            }
        }
    }
    // 墨色
    if (e > .01f) Row(
        Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp)
            .graphicsLayer { alpha = e; translationY = (1 - e) * 10.dp.toPx() }
            .clip(RoundedCornerShape(50)).background(Glass).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PhotoInkState.PENS.forEach { c ->
            val on = ink.color == c
            val k by animateFloatAsState(if (on) 1f else 0f, spring(.6f, 600f), label = "pen")
            Pressable({ if (!on) { ink.color = c; tick() } }, Modifier.size(40.dp), label = "墨色") { p ->
                Box(
                    Modifier.size(22.dp).graphicsLayer { val s = (1f + .18f * k) * (if (p) .9f else 1f); scaleX = s; scaleY = s }
                        .then(if (k > .01f) Modifier.border(2.dp * k, c.copy(alpha = k), CircleShape) else Modifier)
                        .padding(3.dp * k).background(c, CircleShape),
                )
            }
        }
    }
}

@Composable
private fun RoundBtn(onClick: () -> Unit, label: String, enabled: Boolean = true, fill: Color = Glass, dim: Float = 1f, size: Dp = 44.dp, content: @Composable () -> Unit) {
    PressSurface(onClick, Modifier.size(size).graphicsLayer { alpha = dim }, shape = CircleShape, fill = fill, pressedFill = fill.copy(alpha = (fill.alpha + .12f).coerceAtMost(1f)), ripple = Bright, pressScale = .9f, enabled = enabled, label = label) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun PillBtn(onClick: () -> Unit, enabled: Boolean, label: String, content: @Composable () -> Unit) {
    Pressable(onClick, Modifier.size(46.dp, 44.dp), enabled = enabled, label = label) { p ->
        Box(Modifier.fillMaxSize().graphicsLayer { val s = if (p) .88f else 1f; scaleX = s; scaleY = s }, contentAlignment = Alignment.Center) { content() }
    }
}

private fun lerpColor(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t)
