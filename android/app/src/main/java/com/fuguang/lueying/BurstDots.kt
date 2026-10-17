package com.fuguang.lueying

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

private val BurstColors = listOf(Color(0xFFE9B48E), Color(0xFFF3D2B8), Color(0xFFB97F62), Color(0xFFFAF5EE), Color(0xFFE9B48E))
private const val BURST_MS = 1450L

private class Dot(var x: Float, var y: Float, var vx: Float, var vy: Float, val r: Float, val c: Color, val ring: Boolean, val life: Float)

/**
 * 杏色光点：从 at 迸开，偏上方的扇面，空气阻力 + 重力，跑完停帧并调 onEnd（照 dongxiaosheji 01-done-burst，颜色换成杏色系、数量减到 28）。
 * 固定种子：每次迸开都一样好看。
 */
@Composable
internal fun Burst(at: Offset, modifier: Modifier, onEnd: () -> Unit) {
    val dp = LocalDensity.current.density
    val dots = remember(at) {
        var seed = 11L
        fun rnd(): Float { seed = (seed * 16807) % 2147483647; return seed / 2147483647f }
        List(28) {
            val a = -PI.toFloat() / 2 + (rnd() - .5f) * PI.toFloat() * 1.8f
            val sp = (150 + rnd() * 260) * dp
            Dot(at.x + cos(a) * 14 * dp, at.y + sin(a) * 14 * dp, cos(a) * sp, sin(a) * sp, (1.6f + rnd() * 2.6f) * dp, BurstColors[(rnd() * BurstColors.size).toInt().coerceAtMost(BurstColors.lastIndex)], rnd() < .2f, 900 + rnd() * 500)
        }
    }
    var age by remember(at) { mutableLongStateOf(0L) }
    LaunchedEffect(at) {
        val t0 = withFrameMillis { it }
        var prev = t0
        while (true) {
            val t = withFrameMillis { it }
            val dt = (t - prev) / 1000f
            prev = t
            val drag = exp(-2.6f * dt)
            dots.forEach { d -> d.vx *= drag; d.vy = d.vy * drag + 380 * dp * dt; d.x += d.vx * dt; d.y += d.vy * dt }
            age = t - t0
            if (age > BURST_MS) break
        }
        onEnd()
    }
    Canvas(modifier) {
        val a = age.toFloat()
        dots.forEach { d ->
            if (a > d.life) return@forEach
            val k = a / d.life
            val alpha = if (k < .5f) 1f else 1f - (k - .5f) / .5f
            val r = d.r * (if (a < 80) .4f + .6f * a / 80 else 1f - .3f * k)
            if (d.ring) drawCircle(d.c.copy(alpha = alpha), r, Offset(d.x, d.y), style = Stroke(1.2f * dp))
            else drawCircle(d.c.copy(alpha = alpha), r, Offset(d.x, d.y))
        }
    }
}
