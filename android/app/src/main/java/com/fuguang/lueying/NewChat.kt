package com.fuguang.lueying

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 聊天页右上角的「新对话」（照 Claude App）：实心气泡、尾巴在左下，中间挖出一个加号。
 * 已经是新对话时自己收起；她在回答时淡一点、点了不动（和侧栏「新对话」一样）。
 */
private const val BUBBLE = "M12.24 25.34A11 11 0 1 0 5.37 17.85C5.9 20.6 5.7 23.3 5.1 26.2C7.8 26.2 10.2 25.9 12.24 25.34Z"

@Composable
fun NewChatButton(empty: Boolean, busy: Boolean, compact: Boolean, onNew: () -> Unit) {
    val shown by animateFloatAsState(
        if (empty) 0f else 1f,
        if (empty) tween(200, easing = Curves.accel) else spring(dampingRatio = .62f, stiffness = 420f), label = "newChat",
    )
    val round = if (compact) 40.dp else 46.dp
    val bubble = remember { PathParser().parsePathString(BUBBLE).toPath() }
    Pressable(
        { if (!busy && !empty) onNew() },
        Modifier.size(round).graphicsLayer { alpha = shown.coerceIn(0f, 1f) * (if (busy) .45f else 1f); val s = .6f + .4f * shown; scaleX = s; scaleY = s },
        enabled = !empty,
        label = "新对话",
    ) { pressed ->
        val p by animateFloatAsState(if (pressed) 1f else 0f, if (pressed) tween(90) else spring(dampingRatio = .5f, stiffness = 600f), label = "newChatPress")
        Box(Modifier.size(round).graphicsLayer { alpha = p; val s = .6f + .4f * p; scaleX = s; scaleY = s }.background(veil(.08f), CircleShape))
        val fill = Page.ink.copy(alpha = .86f - .14f * p)
        val hole = Page.ground
        Canvas(Modifier.size(if (compact) 26.dp else 29.dp).graphicsLayer { val s = 1f - .08f * p; scaleX = s; scaleY = s }) {
            val k = size.minDimension / 32f
            scale(k, k, pivot = Offset.Zero) {
                drawPath(bubble, fill)
                drawLine(hole, Offset(16f, 10.2f), Offset(16f, 19.8f), 2.4f, StrokeCap.Round)
                drawLine(hole, Offset(11.2f, 15f), Offset(20.8f, 15f), 2.4f, StrokeCap.Round)
            }
        }
    }
}
