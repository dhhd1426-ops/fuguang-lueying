package com.fuguang.lueying

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * AI 思考动效（来自 dongxiaosheji 仓库）：
 * 三个点在正三角 ▲ 与倒三角 ▼ 之间交替换位，旁边文字带左→右流光。
 * 点循环 3000ms = 4 步 × 750ms；每步停 150ms → 移 600ms，缓动 cubic-bezier(.6,0,.4,1)；
 * 三角框 13u × 12.4u，点径 5.4u（u = 字号 ÷ 14）；
 * 文字流光 2000ms 线性三角波，透明度 .22 ↔ 1，峰值停留 100ms，逐字延迟 22ms。
 */
private object Tk {
    private val P = mapOf(
        "T" to floatArrayOf(0f, -6.2f), "BL" to floatArrayOf(-6.5f, 6.2f), "BR" to floatArrayOf(6.5f, 6.2f),
        "TL" to floatArrayOf(-6.5f, -6.2f), "TR" to floatArrayOf(6.5f, -6.2f), "B" to floatArrayOf(0f, 6.2f),
    )

    private fun L(a: String, b: String): Array<FloatArray> {
        val pa = P.getValue(a); val pb = P.getValue(b)
        return arrayOf(
            pa,
            floatArrayOf(pa[0] + (pb[0] - pa[0]) / 3, pa[1] + (pb[1] - pa[1]) / 3),
            floatArrayOf(pa[0] + 2 * (pb[0] - pa[0]) / 3, pa[1] + 2 * (pb[1] - pa[1]) / 3),
            pb,
        )
    }

    private fun C(a: String, x1: Float, y1: Float, x2: Float, y2: Float, b: String) =
        arrayOf(P.getValue(a), floatArrayOf(x1, y1), floatArrayOf(x2, y2), P.getValue(b))

    /** 第几步 → 点 1/2/3 → 三次贝塞尔控制点（与 tools/model.py 一致）。 */
    val M: List<Map<Int, Array<FloatArray>>> = listOf(
        mapOf(1 to L("T", "TR"), 2 to C("BL", -6.5f, -2.5f, 2f, -1.5f, "B"), 3 to C("BR", -1f, 10f, -10f, 4f, "TL")),
        mapOf(3 to C("TL", -4f, -2f, -4f, 2f, "BL"), 2 to C("B", 12f, 6.5f, 12f, -6.5f, "T"), 1 to C("TR", 0f, -3f, 0f, 4f, "BR")),
        mapOf(2 to L("T", "TL"), 3 to C("BL", 1f, 10f, 10f, 4f, "TR"), 1 to C("BR", 6.5f, -2.5f, -2f, -1.5f, "B")),
        mapOf(3 to C("TR", 4f, -2f, 4f, 2f, "BR"), 1 to C("B", -12f, 6.5f, -12f, -6.5f, "T"), 2 to C("TL", 0f, -3f, 0f, 4f, "BL")),
    )

    private fun bez(c: Array<FloatArray>, t: Float): Offset {
        val u = 1 - t
        val a = u * u * u; val b = 3 * u * u * t; val d = 3 * u * t * t; val e = t * t * t
        return Offset(a * c[0][0] + b * c[1][0] + d * c[2][0] + e * c[3][0], a * c[0][1] + b * c[1][1] + d * c[2][1] + e * c[3][1])
    }

    /** cubic-bezier(.6,0,.4,1)：二分求 x(s)=x，返回 y(s)。 */
    private fun ease(x: Float): Float {
        val p0 = .6f; val p1 = 0f; val p2 = .4f; val p3 = 1f
        var lo = 0f; var hi = 1f; var s = .5f
        repeat(30) {
            s = (lo + hi) / 2
            val xs = 3 * (1 - s) * (1 - s) * s * p0 + 3 * (1 - s) * s * s * p2 + s * s * s
            if (xs < x) lo = s else hi = s
        }
        return 3 * (1 - s) * (1 - s) * s * p1 + 3 * (1 - s) * s * s * p3 + s * s * s
    }

    /** t 毫秒时第 d 个点的位置（单位 u）。 */
    fun pos(t: Long, d: Int): Offset {
        val ms = ((t % 3000) + 3000) % 3000
        val m = (ms / 750).toInt()
        val local = ms - m * 750
        val c = M[m].getValue(d)
        return if (local < 150) Offset(c[0][0], c[0][1]) else bez(c, ease((local - 150) / 600f))
    }

    /** 文字流光：第 k 个字在 t 毫秒时的透明度。 */
    fun glow(t: Long, k: Int): Float {
        val ph = ((((t - k * 22L) % 2000) + 2000) % 2000) / 2000f
        return when {
            ph < .475f -> .22f + .78f * (ph / .475f)
            ph < .525f -> 1f
            else -> 1f - .78f * ((ph - .525f) / .475f)
        }
    }
}

@Composable
fun ThinkingMotion(
    text: String,
    modifier: Modifier = Modifier,
    dot: Color = Color(0xFFD97757),
    ink: Color = Color(0xFFD0CEC7),
    size: TextUnit = 15.sp,
) {
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (true) withFrameMillis { now = it - start }
    }
    val density = LocalDensity.current
    val fontPx = with(density) { size.toPx() }
    val u = fontPx / 14f
    val boxDp = with(density) { (fontPx * 1.2f).toDp() }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(boxDp)) {
            val c = Offset(this.size.width / 2, this.size.height / 2)
            for (d in 1..3) {
                val p = Tk.pos(now, d)
                drawCircle(dot, radius = 2.7f * u, center = c + Offset(p.x * u, p.y * u))
            }
        }
        Spacer(Modifier.width(with(density) { (fontPx * .6f).toDp() }))
        BasicText(
            buildAnnotatedString {
                text.forEachIndexed { k, ch ->
                    pushStyle(SpanStyle(color = ink.copy(alpha = ink.alpha * Tk.glow(now, k))))
                    append(ch)
                    pop()
                }
            },
            style = TextStyle(fontSize = size),
        )
    }
}
