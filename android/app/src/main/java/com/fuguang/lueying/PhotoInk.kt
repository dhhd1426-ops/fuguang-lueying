package com.fuguang.lueying

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * 圈画的一笔：颜色、粗细（占图宽的比例）、点（x、y 占图宽高的比例；第三个数是落笔快慢定的粗细系数，快的细）。
 * 画法照原型：每段按那一点的粗细画二次曲线，起笔 7 个点、收笔 9 个点收尖。
 */
class InkLine(val color: Color, val w: Float, val pts: List<FloatArray>)

/** 每一段：起点、控制点（没有时 = 终点，画直线）、终点、粗细。 */
private fun InkLine.segments(W: Float, H: Float, prog: Float, each: (sx: Float, sy: Float, cx: Float, cy: Float, ex: Float, ey: Float, w: Float, quad: Boolean) -> Unit) {
    val n = pts.size; val m = (n * prog).toInt().coerceIn(1, n); val base = w * W
    fun width(i: Int): Float { val f = pts[i].getOrElse(2) { 1f }; val head = min(1f, (i + 1) / 7f); val tail = min(1f, (n - i) / 9f); return base * f * (.28f + .72f * min(head, tail)) }
    var sx = pts[0][0] * W; var sy = pts[0][1] * H
    for (i in 1 until m) {
        val quad = i < m - 1
        val ex = if (quad) (pts[i][0] + pts[i + 1][0]) / 2 * W else pts[i][0] * W
        val ey = if (quad) (pts[i][1] + pts[i + 1][1]) / 2 * H else pts[i][1] * H
        each(sx, sy, pts[i][0] * W, pts[i][1] * H, ex, ey, width(i), quad); sx = ex; sy = ey
    }
}

fun DrawScope.drawInk(l: InkLine, prog: Float = 1f, alpha: Float = 1f) {
    val c = l.color.copy(alpha = .95f * alpha)
    if (l.pts.size == 1) { drawCircle(c, l.w * size.width * .5f, Offset(l.pts[0][0] * size.width, l.pts[0][1] * size.height)); return }
    val p = Path()
    l.segments(size.width, size.height, prog) { sx, sy, cx, cy, ex, ey, w, quad ->
        p.reset(); p.moveTo(sx, sy); if (quad) p.quadraticTo(cx, cy, ex, ey) else p.lineTo(ex, ey)
        drawPath(p, c, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** 看图页的状态：开着哪张、每张小图在哪、哪张被盖住、输入框里圈过的图记着原图和每一笔。 */
object PhotoView {
    /** ref = 看的这张（原图）；thumb = 从哪张小图长出来（输入框里那张时和 ref 不同）。 */
    class Open(val ref: String, val thumb: String, val chat: ChatState, val tray: Boolean, val lines: List<InkLine>)
    var open by mutableStateOf<Open?>(null)
    val spots = mutableStateMapOf<String, Rect>()
    var hidden by mutableStateOf<String?>(null)
    /** 刚圈好、正飞进输入框的那张：飞到七成后才慢慢露出来。 */
    var landing by mutableStateOf<String?>(null)
    var landK by mutableFloatStateOf(1f)
    val marks = mutableMapOf<String, Pair<String, List<InkLine>>>()

    fun show(ref: String, chat: ChatState, tray: Boolean = false) {
        if (open != null) return
        val m = marks[ref]
        open = if (m != null) Open(m.first, ref, chat, true, m.second) else Open(ref, ref, chat, tray, emptyList())
        hidden = ref
    }

    /** 原图 + 每一笔合成一张新图存进来（Attach 里一个新的引用）。 */
    suspend fun bake(base: String, lines: List<InkLine>): String? = withContext(Dispatchers.IO) {
        runCatching {
            val src = BitmapFactory.decodeFile(Attach.file(base).path) ?: return@runCatching null
            val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
            val c = android.graphics.Canvas(bmp)
            val W = bmp.width.toFloat(); val H = bmp.height.toFloat()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
            val path = android.graphics.Path()
            lines.forEach { l ->
                paint.color = l.color.copy(alpha = .95f).toArgb()
                if (l.pts.size == 1) { paint.style = Paint.Style.FILL; c.drawCircle(l.pts[0][0] * W, l.pts[0][1] * H, l.w * W / 2, paint); paint.style = Paint.Style.STROKE }
                else l.segments(W, H, 1f) { sx, sy, cx, cy, ex, ey, w, quad ->
                    path.reset(); path.moveTo(sx, sy); if (quad) path.quadTo(cx, cy, ex, ey) else path.lineTo(ex, ey)
                    paint.strokeWidth = w; c.drawPath(path, paint)
                }
            }
            Attach.saveBitmap(bmp, "圈画")
        }.getOrNull()
    }
}

/**
 * 羽毛图标：实心羽片 + 挖空的羽轴和四道裂口 + 一截羽管。24 格画布里约 26dp 也看得清。
 * 羽轴是从羽管到羽尖的二次曲线，羽片两侧按 sin 包络（左宽右窄），裂口顺着羽枝方向。
 */
@Composable
fun FeatherGlyph(color: Color, size: Dp) {
    val geo = remember { featherGeo() }
    Canvas(Modifier.size(size).graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val s = this.size.minDimension / 100f
        fun o(p: FloatArray) = Offset(p[0] * s, p[1] * s)
        val vane = Path().apply { moveTo(geo.vane[0][0] * s, geo.vane[0][1] * s); geo.vane.drop(1).forEach { lineTo(it[0] * s, it[1] * s) }; close() }
        drawPath(vane, color)
        val cut = Path()
        geo.cuts.forEach { (a, b) -> cut.moveTo(a[0] * s, a[1] * s); cut.lineTo(b[0] * s, b[1] * s) }
        cut.moveTo(geo.shaft[0][0] * s, geo.shaft[0][1] * s); geo.shaft.drop(1).forEach { cut.lineTo(it[0] * s, it[1] * s) }
        drawPath(cut, Color.Black, style = Stroke(4.5f * s, cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = BlendMode.Clear)
        drawLine(color, o(geo.nib.first), o(geo.nib.second), 5.5f * s, StrokeCap.Round)
    }
}

private class FeatherGeo(val vane: List<FloatArray>, val cuts: List<Pair<FloatArray, FloatArray>>, val shaft: List<FloatArray>, val nib: Pair<FloatArray, FloatArray>)

private fun featherGeo(): FeatherGeo {
    val p0 = floatArrayOf(16f, 86f); val p1 = floatArrayOf(44f, 52f); val p2 = floatArrayOf(86f, 12f)
    fun b(t: Float) = FloatArray(2) { k -> (1 - t) * (1 - t) * p0[k] + 2 * (1 - t) * t * p1[k] + t * t * p2[k] }
    fun tan(t: Float): FloatArray { val x = 2 * (1 - t) * (p1[0] - p0[0]) + 2 * t * (p2[0] - p1[0]); val y = 2 * (1 - t) * (p1[1] - p0[1]) + 2 * t * (p2[1] - p1[1]); val l = hypot(x, y); return floatArrayOf(x / l, y / l) }
    fun tip(t: Float, s: Int, len: Float): FloatArray {
        val p = b(t); val d = tan(t); val n = floatArrayOf(-d[1] * s, d[0] * s); val a = Math.toRadians((48 - 22 * t).toDouble()).toFloat()
        return floatArrayOf(p[0] + d[0] * len * cos(a) + n[0] * len * sin(a), p[1] + d[1] * len * cos(a) + n[1] * len * sin(a))
    }
    fun edge(s: Int) = (0..36).map { i -> val t = .2f + i / 36f * .8f; val prof = sin(Math.PI * min(1f, (t - .17f) / .85f)).toFloat().coerceAtLeast(0f).pow(.7f); tip(t, s, (if (s > 0) 19f else 15f) * prof) }
    val vane = listOf(b(.2f)) + edge(1) + edge(-1).reversed()
    fun cut(t: Float, s: Int, k: Float): Pair<FloatArray, FloatArray> {
        val p = b(t); val e = tip(t, s, (if (s > 0) 19f else 15f) * 1.1f)
        return floatArrayOf(p[0] + (e[0] - p[0]) * k, p[1] + (e[1] - p[1]) * k) to e
    }
    val cuts = listOf(cut(.42f, 1, .35f), cut(.6f, -1, .3f), cut(.72f, 1, .45f), cut(.33f, -1, .4f))
    val shaft = (0..20).map { b(it / 20f * .98f) }
    return FeatherGeo(vane, cuts, shaft, p0 to b(.24f))
}

/** 落笔快慢 → 粗细系数：每毫秒走的 dp 越多越细（0.5–1.25），和上一点七三开平滑。 */
fun inkSpeed(prev: Float, distDp: Float, ms: Long): Float {
    val v = distDp / ms.coerceAtLeast(4L)
    val f = (1.3f - v * .45f).coerceIn(.5f, 1.25f)
    return prev * .7f + f * .3f
}

/** 圈画的状态：每一笔、能恢复的、进圈画时的样子（用来判断改没改）、颜色、撤销 / 恢复的那一下动画。 */
internal class PhotoInkState(start: List<InkLine>) {
    val lines = mutableStateListOf<InkLine>().apply { addAll(start) }
    val redo = mutableStateListOf<InkLine>()
    val pen = mutableStateListOf<FloatArray>()
    var snap: List<InkLine> = start
    var color by mutableStateOf(PENS[0])
    var editing by mutableStateOf(false)
    var ask by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var fx by mutableStateOf<Pair<InkLine, Boolean>?>(null)
    val fxK = Animatable(1f)
    val fade = Animatable(1f)
    val dirty get() = lines.toList() != snap

    fun enter() { snap = lines.toList(); redo.clear(); editing = true }
    fun undo(scope: CoroutineScope) {
        if (lines.isEmpty()) return
        val l = lines.removeAt(lines.lastIndex); redo.add(l); play(scope, l, false, 180)
    }
    fun again(scope: CoroutineScope) {
        if (redo.isEmpty()) return
        val l = redo.removeAt(redo.lastIndex); lines.add(l); play(scope, l, true, 280)
    }
    private fun play(scope: CoroutineScope, l: InkLine, drawIn: Boolean, ms: Int) {
        fx = l to drawIn
        scope.launch { fxK.snapTo(0f); fxK.animateTo(1f, tween(ms, easing = if (drawIn) Curves.decel else Curves.standard)); if (fx?.first === l) fx = null }
    }
    /** 退出圈画：有改动先问；问过说「不要了」就淡出、换回进来时的样子。 */
    fun leave() { if (dirty) ask = !ask else editing = false }
    fun drop(scope: CoroutineScope) {
        ask = false
        scope.launch {
            fade.animateTo(0f, tween(140, easing = Curves.accel))
            lines.clear(); lines.addAll(snap); redo.clear(); editing = false
            fade.animateTo(1f, tween(180, easing = Curves.decel))
        }
    }

    companion object {
        const val WIDTH = .0095f
        val PENS = listOf(Color(0xFFE0503F), Color(0xFFE9B48E), Color(0xFFF4EFE9), Color(0xFF3B6FD8))
    }
}
