package com.fuguang.lueying

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp

/**
 * 笔顺：每一笔多长、什么时候起笔、写多久。
 * 连在一起的几笔是一组，组内不停；提笔处停 [LIFT] 毫秒。每组起笔慢、行笔快、收笔慢。
 */
internal object InkPlan {
    /** 笔速：每毫秒走多少格（字宽 2234 格）。用户在原型里调到的速度，整个词约 1.2 秒。 */
    private const val SPEED = 4.1f
    private const val LIFT = 75f
    private const val MIN_GROUP = 160f

    class Group(val strokes: List<Int>, val len: Float, val start: Float, val dur: Float)

    val fill: Path by lazy { PathParser().parsePathString(VignetteInk.FILL).toPath() }
    val paths: List<Path> by lazy { VignetteInk.STROKES.map { PathParser().parsePathString(it.d).toPath() } }
    val lens: List<Float> by lazy { paths.map { p -> PathMeasure().apply { setPath(p, false) }.length } }
    val groups: List<Group> by lazy {
        val gs = mutableListOf<MutableList<Int>>()
        VignetteInk.STROKES.forEachIndexed { i, s -> if (s.lift || gs.isEmpty()) gs += mutableListOf<Int>(); gs.last() += i }
        var t = 0f
        gs.map { g ->
            val len = g.sumOf { lens[it].toDouble() }.toFloat()
            val dur = maxOf(MIN_GROUP, len / SPEED)
            val st = if (t > 0f) t + LIFT else 0f
            t = st + dur
            Group(g, len, st, dur)
        }
    }
    /** i 的点那一笔（不写它，换成掉下来的变形弹跳，见 VignetteMark）。 */
    val dot: Int by lazy { VignetteInk.STROKES.indexOfFirst { it.d.startsWith("M751 354") } }
    /** 整个词写完要多久（毫秒）。 */
    val total: Float by lazy { groups.last().let { it.start + it.dur } }

    /** 第 [ms] 毫秒时，每一笔写到了多长。 */
    fun done(ms: Float): FloatArray {
        val out = FloatArray(paths.size)
        groups.forEach { g ->
            var x = ((ms - g.start) / g.dur).coerceIn(0f, 1f)
            x = if (x < .5f) 2 * x * x else 1 - (-2 * x + 2) * (-2 * x + 2) / 2
            var d = x * g.len
            g.strokes.forEach { i -> out[i] = d.coerceIn(0f, lens[i]); d -= lens[i] }
        }
        return out
    }
}

/**
 * 新对话正中那行杏色手写「Vignette」：打开时按笔顺一笔一笔写出来，写完就停。
 * 做法：先在离屏层画出写到这一刻的笔（沿笔画中线的粗线），再用字形轮廓 SrcIn 染色，只露出写过的那部分字。
 * 系统关了动画（动画时长 0）时直接整个出现。
 */
@Composable
internal fun VignetteMark(width: Dp, color: Color, modifier: Modifier = Modifier) {
    val clock = remember { Animatable(0f) }
    // 变形弹跳（第 339 版起）：i 的点不写，写完后从上方掉下一个形状，弹三下变圆、缩成那个点（ToyMotion.kt Toy.drop）
    val fall = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        clock.animateTo(InkPlan.total, tween(InkPlan.total.toInt(), easing = LinearEasing))
        fall.animateTo(Toy.dropTotal, tween(Toy.dropTotal.toInt(), easing = LinearEasing))
    }
    val toyPath = remember { Path() }
    val measures = remember { InkPlan.paths.map { p -> PathMeasure().apply { setPath(p, false) } } }
    val paint = remember { Paint() }
    val clip = remember { Paint().apply { blendMode = BlendMode.SrcIn } }
    Canvas(
        modifier.width(width).aspectRatio(VignetteInk.W / VignetteInk.H)
            .semantics { contentDescription = "Vignette" },
    ) {
        val done = InkPlan.done(clock.value)
        val k = size.width / VignetteInk.W
        drawContext.canvas.saveLayer(Rect(Offset.Zero, size), paint)
        scale(k, k, pivot = Offset.Zero) {
            VignetteInk.STROKES.forEachIndexed { i, s ->
                if (i == InkPlan.dot) return@forEachIndexed
                val u = done[i]
                if (u <= 0f) return@forEachIndexed
                val path = if (u >= InkPlan.lens[i]) InkPlan.paths[i] else {
                    Path().also { measures[i].getSegment(0f, u, it, true) }
                }
                drawPath(path, Color.Black, style = Stroke(s.width, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        // 字形整层 SrcIn 合上去：只按字形画会留下字外那圈粗笔（白色主题下是一道黑边），整层合成才把字外的清掉
        drawContext.canvas.saveLayer(Rect(Offset.Zero, size), clip)
        scale(k, k, pivot = Offset.Zero) { drawPath(InkPlan.fill, color) }
        drawContext.canvas.restore()
        drawContext.canvas.restore()
        // 字形里 i 的点在 (742..798, 340..395) 格，中心 (770, 367.5)、半径 28 格；形状画在字外面（往上掉），不裁
        if (clock.value >= InkPlan.total) with(Toy) { drop(toyPath, fall.value, 770f * k, 367.5f * k, 28f * k, density, color) }
    }
}
