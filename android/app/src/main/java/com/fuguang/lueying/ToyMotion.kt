package com.fuguang.lueying

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 变形弹跳（第 339 版加，第 340 版定下只用在两处；原型 27H9hsGmHqHqqD4QkSRkcY 第 2 版、2NN8H5p9eH7EEKCw89gShS）：
 * 雨滴皮肤 Real'sToy 的优化版动作，原封不动，只等比缩小——
 * 一拍 1200ms，从高处（原图 220px）抛下、低处落地；边跳边转，一拍转一整圈；
 * 落地前后 72ms 平滑换形（凸形混合，方块开场，之后三角 → 方块 → 圆 循环），落地前后 84ms 挤压回弹（横 +16%、竖 −20%）。
 * 尺寸按原图像素算：方块 30、三角边长 33、圆直径 31；f = 每个原图像素画多少 dp。
 * 用在两处，一直开着（主题里没有开关）：新对话问候页 Vignette 的 i 点（Toy.drop）、作品卡片「正在写」的小方块（ToyTile）。
 * 思考行还是原来的三点换位（ThinkingMotion）。
 */
internal object Toy {
    const val PERIOD = 1200L
    const val HEIGHT = 220f
    private const val N = 240

    /** 三个形状在 N 个方向上的支撑点（凸形混合用）：0 三角、1 方块、2 圆。 */
    private val xs = Array(3) { FloatArray(N) }
    private val ys = Array(3) { FloatArray(N) }

    init {
        fun poly(i: Int, sides: Int, r: Double, rot: Double) {
            val vx = DoubleArray(sides) { r * cos(rot + it * 2 * PI / sides) }
            val vy = DoubleArray(sides) { r * sin(rot + it * 2 * PI / sides) }
            for (j in 0 until N) {
                val t = j * 2 * PI / N
                var best = 0; var bd = -1e9
                for (v in 0 until sides) { val d = vx[v] * cos(t) + vy[v] * sin(t); if (d > bd) { bd = d; best = v } }
                xs[i][j] = vx[best].toFloat(); ys[i][j] = vy[best].toFloat()
            }
        }
        poly(0, 3, 33 / sqrt(3.0), -PI / 2)
        poly(1, 4, 30 / sqrt(2.0), PI / 4)
        for (j in 0 until N) { val t = j * 2 * PI / N; xs[2][j] = (15.5 * cos(t)).toFloat(); ys[2][j] = (15.5 * sin(t)).toFloat() }
    }

    /** 第 c 拍落地后的形状（1 三角、2 方块、3 圆）；开场是方块。 */
    fun shapeAt(c: Long): Int = if (c < 0) 2 else (c % 3).toInt() + 1

    fun sstep(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

    /** 一拍里的样子：离落地点多高（原图像素）、转角（度）、换形（prev → next，m）、挤压。 */
    class Beat(val up: Float, val rot: Float, val prev: Int, val next: Int, val m: Float, val sx: Float, val sy: Float)

    fun beat(ms: Long): Beat {
        val c = Math.floorDiv(ms, PERIOD)
        val t = (ms - c * PERIOD) / PERIOD.toFloat()
        val d = (t - .5f) / .07f
        val imp = if (abs(d) < 1f) cos(d * PI.toFloat() / 2).let { it * it } else 0f
        return Beat(HEIGHT * abs(cos(t * PI.toFloat())), t * 360f, shapeAt(c - 1), shapeAt(c), sstep((t - .5f + .06f) / .12f), 1 + .16f * imp, 1 - .2f * imp)
    }

    /** 画一个形状：中心 (cx, cy)，f = 每原图像素多少 px，k 再整体缩放。 */
    fun DrawScope.shape(path: Path, cx: Float, cy: Float, f: Float, prev: Int, next: Int, m: Float, rot: Float, sx: Float, sy: Float, color: Color, k: Float = 1f, alpha: Float = 1f) {
        val a = prev - 1; val b = next - 1
        path.reset()
        for (j in 0 until N) {
            val x = xs[a][j] + (xs[b][j] - xs[a][j]) * m
            val y = ys[a][j] + (ys[b][j] - ys[a][j]) * m
            if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        withTransform({ translate(cx, cy); scale(f * sx * k, f * sy * k, Offset.Zero); rotate(rot, Offset.Zero) }) { drawPath(path, color, alpha) }
    }

    /** 一拍画在落地点 (gx, gy)；j 只压跳的高度，形状不变。挤压时把底边留在地上。 */
    fun DrawScope.bounce(path: Path, ms: Long, gx: Float, gy: Float, f: Float, j: Float, color: Color): Beat {
        val b = beat(ms)
        shape(path, gx, gy - b.up * f * j + (1 - b.sy) * 16f * f, f, b.prev, b.next, b.m, b.rot, b.sx, b.sy, color)
        return b
    }

    /** 静止（系统关了动画）：方块端正地停在落地点。 */
    fun DrawScope.rest(path: Path, gx: Float, gy: Float, f: Float, color: Color, which: Int = 2) =
        shape(path, gx, gy, f, which, which, 1f, 0f, 1f, 1f, color)

    /** 系统关了动画（开发者选项动画时长 0、省电的「减少动态效果」）：不跳，停在落地点。 */
    @Composable
    fun still(): Boolean {
        val ctx = LocalContext.current
        return remember { runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false) }
    }

    /** 帧时钟（毫秒）：只在绘制里读，不触发重组。 */
    @Composable
    fun clock(run: Boolean = true): State<Long> {
        val s = remember { mutableLongStateOf(0L) }
        if (run) LaunchedEffect(Unit) { while (true) withFrameMillis { s.longValue = it } }
        return s
    }

    // ---- 问候页：Vignette 写完，形状从 i 上方 120dp 掉下，弹三下一次比一次低，第三下落地变圆、缩成 i 的点 ----
    private const val DROP_H = 120f
    const val DOT_F = .24f
    private val HEIGHTS = floatArrayOf(1f, .42f, .16f)
    private val SEQ = intArrayOf(2, 1, 3, 3)
    /** 每段 (起, 止)：第一段只有下落半拍，之后每段一整拍；时长按 √高度 缩。 */
    private val segs: List<Pair<Float, Float>> = buildList {
        var t = 0f
        HEIGHTS.forEachIndexed { i, h ->
            val d = if (i == 0) PERIOD / 2f * sqrt(h) else PERIOD * sqrt(h)
            add(t to t + d); t += d
        }
    }
    private val lastLand = segs.last().second
    /** 掉下来到缩成点一共多久（毫秒）。 */
    val dropTotal = lastLand + 400f

    /**
     * 问候页 i 点：el = 写完后过了多少毫秒；(gx, gy) i 点中心，dotR 字里那个点的半径（px）；d = density。
     */
    fun DrawScope.drop(path: Path, el: Float, gx: Float, gy: Float, dotR: Float, d: Float, color: Color) {
        val f = DOT_F * d
        if (el >= lastLand) {
            val q = sstep((el - lastLand - 120f) / 260f)
            val r0 = 15.5f * f
            shape(path, gx, gy, f, 3, 3, 1f, 0f, 1f, 1f, color, (r0 + (dotR - r0) * q) / r0)
            return
        }
        val i = segs.indexOfFirst { el < it.second }.coerceAtLeast(0)
        val (a, b) = segs[i]
        val u = ((el - a) / (b - a)).coerceIn(0f, 1f)
        val up = DROP_H * d * HEIGHTS[i] * if (i == 0) cos(u * PI.toFloat() / 2) else sin(u * PI.toFloat())
        val toLand = el - b
        val fromLand = if (i > 0) el - segs[i - 1].second else Float.MAX_VALUE
        var pv = SEQ[i]; var nx = SEQ[i]; var m = 0f
        if (toLand > -72f) { nx = SEQ[i + 1]; m = sstep((toLand + 72f) / 144f) }
        else if (fromLand < 72f) { pv = SEQ[i - 1]; nx = SEQ[i]; m = sstep((fromLand + 72f) / 144f) }
        val near = minOf(abs(toLand), abs(fromLand)) / 84f
        val imp = if (near < 1f) cos(near * PI.toFloat() / 2).let { it * it } else 0f
        val sx = 1 + .16f * imp; val sy = 1 - .2f * imp
        shape(path, gx, gy - up + (1 - sy) * 16f * f, f, pv, nx, m, el / PERIOD * 360f, sx, sy, color)
    }
}

/** 作品卡片「正在写」那块 56dp 小方块里：0.27 倍（约 8dp），落地点离底 13dp，跳的高度压到 0.5（约 30dp），不出方块。 */
@Composable
internal fun ToyTile(color: Color, modifier: Modifier = Modifier) {
    val still = Toy.still()
    val now = Toy.clock(!still)
    val path = remember { Path() }
    Canvas(modifier) {
        with(Toy) {
            val f = .27f * density
            val gy = size.height - 13.dp.toPx()
            if (still) rest(path, center.x, gy, f, color) else bounce(path, now.value, center.x, gy, f, .5f, color)
        }
    }
}
