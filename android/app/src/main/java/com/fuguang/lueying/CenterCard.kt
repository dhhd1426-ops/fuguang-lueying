package com.fuguang.lueying

import androidx.compose.ui.graphics.RectangleShape
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.min
import kotlinx.coroutines.launch

/** 一张悬浮卡片的开关：from 是长出来的地方（点的那颗头像、那一行），关掉时原路缩回这里。 */
@Stable
open class FloatCard {
    var open by mutableStateOf(false)
    var full by mutableStateOf(false)
    var from by mutableStateOf(Offset.Unspecified)
}

internal const val RISE_MS = 570f
private val arrowOut = Ic("M7 17L17 7M9 7h8v8")


/** 一项按顺序浮出：开卡后 70ms 起，每项晚 40ms，各走 340ms。 */
internal fun Modifier.mineRise(i: Int, t: Float) = graphicsLayer {
    val k = ((t * RISE_MS - (70 + 40 * i)) / 340f).coerceIn(0f, 1f)
    val e = Curves.decel.transform(k)
    alpha = e
    translationY = (1f - e) * 8.dp.toPx()
}

/**
 * 所有悬浮卡片共用的样子：同一个位置（屏幕视觉中心，中心在 47% 高处）、同一个尺寸（宽 min(340, 屏宽−40)，高 min(540, 屏高−120)），
 * 从 from 弹簧长出、原路缩回。卡片里一页页推进（往深处新页从右进，往回从左进）；右上角 ↗ 随时展开成整页，转成 ↙ 再点缩回；
 * 整页时关掉先缩回卡片再收走。返回键：整页先缩回 → onBack 退一层 → 关卡片。
 */
@Composable
fun CenterCardLayer(
    card: FloatCard,
    screen: String,
    depth: (String) -> Int,
    onBack: () -> Boolean,
    onClosed: () -> Unit,
    above: @Composable BoxScope.() -> Unit = {},
    page: @Composable (screen: String, rise: Float) -> Unit,
) {
    val p = remember { Animatable(0f) }
    val f = remember { Animatable(0f) }
    val rise = remember { Animatable(0f) }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(card.open) {
        if (card.open) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            launch { rise.snapTo(0f); rise.animateTo(1f, tween(RISE_MS.toInt(), easing = LinearEasing)) }
            p.animateTo(1f, spring(dampingRatio = .7f, stiffness = 380f))
        } else {
            if (f.value > 0f) { card.full = false; f.animateTo(0f, tween(440, easing = Curves.standard)) }
            p.animateTo(0f, tween(200, easing = Curves.accel))
            onClosed()
        }
    }
    LaunchedEffect(card.full) {
        if (card.open) f.animateTo(if (card.full) 1f else 0f, tween(440, easing = Curves.standard))
    }
    BackHandler(enabled = card.open) {
        when {
            card.full -> card.full = false
            onBack() -> Unit
            else -> card.open = false
        }
    }
    if (!card.open && p.value == 0f) return

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        val pv = p.value
        val fv = f.value
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = pv.coerceIn(0f, 1f) }
                .background(rgba(6, 8, 12, .55f))
                .pointerInput(Unit) { detectTapGestures { card.open = false } },
        )
        val cardW = min(340.dp, maxWidth - 40.dp)
        val cardH = min(540.dp, maxHeight - 120.dp)
        val left0 = (maxWidth - cardW) / 2
        val top0 = (maxHeight * .47f - cardH / 2).coerceAtLeast(statusTop + 12.dp)
        val x = lerp(left0, 0.dp, fv)
        val y = lerp(top0, 0.dp, fv)
        Box(
            Modifier.offset(x, y).size(lerp(cardW, maxWidth, fv), lerp(cardH, maxHeight, fv))
                .graphicsLayer {
                    val s = .16f + .84f * pv
                    scaleX = s; scaleY = s
                    alpha = (pv * 1.8f).coerceIn(0f, 1f)
                    val from = card.from
                    transformOrigin = if (from.isSpecified && size.width > 0f && size.height > 0f)
                        TransformOrigin((from.x - x.toPx()) / size.width, (from.y - y.toPx()) / size.height)
                    else TransformOrigin(.5f, 1.2f)
                    shape = RoundedCornerShape(lerp(24.dp, 0.dp, fv))
                    clip = true
                    shadowElevation = 24.dp.toPx() * (1f - fv)
                }
                .then(if (Skin.frost) Modifier.frost(RectangleShape, Skin.card) else Modifier.background(Skin.card))
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            val pad = Modifier.fillMaxSize().padding(
                start = 14.dp, end = 14.dp,
                top = lerp(18.dp, statusTop + 22.dp, fv),
                bottom = 12.dp + navBottom * fv,
            )
            AnimatedContent(
                screen,
                Modifier.fillMaxSize(),
                transitionSpec = {
                    val dx = if (depth(targetState) >= depth(initialState)) 1 else -1
                    (slideInHorizontally(tween(320, easing = Curves.decel)) { dx * it / 9 } + fadeIn(tween(260)))
                        .togetherWith(slideOutHorizontally(tween(240, easing = Curves.accel)) { -dx * it / 9 } + fadeOut(tween(180)))
                        .using(SizeTransform(clip = false) { _, _ -> snap() })
                },
                contentAlignment = Alignment.TopCenter,
                label = "cardPage",
            ) { s ->
                Box(pad, contentAlignment = Alignment.TopCenter) { page(s, rise.value) }
            }
            // ↗ 钉在右上角，换页也不动
            PressSurface(
                { card.full = !card.full },
                Modifier.align(Alignment.TopEnd).padding(top = lerp(4.dp, statusTop + 8.dp, fv), end = 4.dp).size(44.dp),
                shape = CircleShape, fill = Color.Transparent, pressScale = .9f,
                label = if (card.full) "缩回卡片" else "展开成整页",
            ) {
                SvgIcon(arrowOut, Page.muted, 17.dp, stroke = 1.5f, modifier = Modifier.align(Alignment.Center).graphicsLayer { rotationZ = 180f * fv })
            }
        }
        above()
    }
}
