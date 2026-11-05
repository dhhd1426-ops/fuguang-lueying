package com.fuguang.lueying

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.unit.dp

/** 「前情」入口卡上的图标（原来聊天摘要那只，第 394 版接着用）：和「记忆」那张同一个底（点缀色浅底 52dp），里面两只叠着的对话气泡，前面那只第一行用点缀色；按下前面那只翘一下。 */
@Composable
internal fun FoldIcon(pressed: Boolean) {
    val light = Skin.light
    val ac = Skin.accent
    val ink = Page.ink
    val muted = Page.muted
    val lift by animateFloatAsState(if (pressed) 1f else 0f, tween(260, easing = Curves.decel), label = "foldLift")
    val backB = remember { PathParser().parsePathString("M12 9.5h12.5a2.5 2.5 0 0 1 2.5 2.5v7a2.5 2.5 0 0 1-2.5 2.5H23v3l-3.5-3H12a2.5 2.5 0 0 1-2.5-2.5v-7A2.5 2.5 0 0 1 12 9.5Z").toPath() }
    val front = remember { PathParser().parsePathString("M7.5 6h12.5A2.5 2.5 0 0 1 22.5 8.5v7A2.5 2.5 0 0 1 20 18h-7.5L9 21v-3H7.5A2.5 2.5 0 0 1 5 15.5v-7A2.5 2.5 0 0 1 7.5 6Z").toPath() }
    Box(
        Modifier.size(52.dp).clip(RoundedCornerShape(15.dp))
            .background(Brush.verticalGradient(listOf(ac.copy(alpha = if (light) .22f else .2f), ac.copy(alpha = if (light) .34f else .1f)))),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawLine(Color.White.copy(alpha = if (light) .7f else .08f), Offset(15.dp.toPx(), .5.dp.toPx()), Offset(size.width - 15.dp.toPx(), .5.dp.toPx()), 1.dp.toPx())
            val u = 40.dp.toPx() / 32f
            val ox = (size.width - 40.dp.toPx()) / 2f; val oy = (size.height - 40.dp.toPx()) / 2f
            val thin = Stroke(1.1.dp.toPx() / u, join = StrokeJoin.Round)
            val edge = ink.copy(alpha = ink.alpha * (if (light) .45f else .4f))
            translate(ox, oy) {
                scale(u, u, Offset.Zero) {
                    drawPath(backB, if (light) Color.White.copy(alpha = .7f) else Color.White.copy(alpha = .07f))
                    drawPath(backB, edge.copy(alpha = edge.alpha * .7f), style = thin)
                }
                translate(-.4f * u * lift, -1.4f * u * lift) {
                    scale(u, u, Offset.Zero) {
                        drawPath(front, if (light) Color.White else Color.White.copy(alpha = .13f))
                        drawPath(front, edge, style = thin)
                        drawLine(ac, Offset(9f, 10.5f), Offset(15f, 10.5f), 1.6f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                        drawLine(muted, Offset(9f, 13.8f), Offset(18f, 13.8f), 1.4f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    }
                }
            }
        }
    }
}
