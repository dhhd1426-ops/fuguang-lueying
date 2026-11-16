package com.fuguang.lueying

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * 语音页（原型 HqPFuGXT73T5RuKRexxyLd 第 6 版）。动效主题「星海光弦」：只用发光细丝一种材料。
 * 视觉中心在她心口：光环圆心、声波地平线（3D 透视消失点）、辉光、暗角、涟漪、微尘都从这一点出发。
 * 冷色是你（在听偏蓝青）、暖色是她（在说偏粉橙），在想偏紫，静音压暗。
 * 层次：VoiceBackdrop（夜海 + 光）在 Whale.Stage 下面，VoiceControls（顶栏 + 三个钮）在上面。
 */

private const val LISTEN = 0
private const val THINK = 1
private const val SPEAK = 2
private const val MUTED = 3

private class Rib(c: LongArray, val k: Float, val s: Float, val l: FloatArray, val bias: Float, val tw: Float, val a: Float) {
    val c = c.map { Color(it) }
    /** 声波横向渐变（按屏宽）、光环斜向渐变（按环外接方框），停点和原来的 Brush 一样 */
    val wave = StopGrad(floatArrayOf(0f, .3f, .5f, .7f, 1f), arrayOf(this.c[0], this.c[0].copy(alpha = .9f), this.c[1].copy(alpha = .45f), this.c[2].copy(alpha = .9f), this.c[2]))
    val ring = StopGrad(floatArrayOf(0f, .5f, 1f), arrayOf(this.c[0], this.c[1], this.c[2]))
}

private val RIBS = listOf(
    Rib(longArrayOf(0xFF2F4BFF, 0xFF3D8BFF, 0xFF30F0E4), 1.25f, .55f, floatArrayOf(-.48f, .28f, .36f, .22f), -.25f, 2.4f, 1f),
    Rib(longArrayOf(0xFF18D6E8, 0xFF3BF2D0, 0xFF6FB6FF), 1.65f, -.7f, floatArrayOf(-.32f, .22f, .52f, .20f), .35f, 3.0f, .95f),
    Rib(longArrayOf(0xFF7A4DFF, 0xFFC25BFF, 0xFFFF6FD0), 1.45f, .8f, floatArrayOf(-.58f, .24f, .28f, .26f), .15f, 2.7f, .95f),
    Rib(longArrayOf(0xFFFF5FC8, 0xFFFF4A6E, 0xFFFF3D3D), 1.9f, -.5f, floatArrayOf(.46f, .22f, -.40f, .20f), .45f, 3.3f, .8f),
    Rib(longArrayOf(0xFFFF6A3D, 0xFFFFA640, 0xFFFFE070), 1.7f, .62f, floatArrayOf(.62f, .20f, -.22f, .18f), -.1f, 2.2f, .72f),
)
private val WT = arrayOf(floatArrayOf(1f, 1f, .85f, .5f, .4f), floatArrayOf(.75f, .8f, 1f, .75f, .6f), floatArrayOf(.5f, .55f, .9f, 1f, 1f), floatArrayOf(.5f, .5f, .5f, .5f, .5f))
private val TINT = arrayOf(Color(0xFF5AA8FF), Color(0xFFB08CFF), Color(0xFFFF7DB8), Color(0xFF4A5266))
private val HUES = arrayOf(Color(0xFF3D6BFF), Color(0xFF30DCF0), Color(0xFF965AFF), Color(0xFFFF5FC8), Color(0xFFFF7846), Color(0xFFFFD25A), Color(0xFF3D6BFF))

/** 画面状态（帧之间要记的东西）。语音页只有一个，放成单例，背景和按钮共用 */
private object Fx {
    var t by mutableFloatStateOf(0f)
    var lastT = 0f
    var st = LISTEN
    val ampR = FloatArray(5); val velR = FloatArray(5)
    val wg = floatArrayOf(1f, 1f, 1f, 1f, 1f)
    val band = FloatArray(45)
    var level = 0f; var rot = 0f; var spark = 0f; var glow = .5f
    val ripples = ArrayList<Float>()
    val dA = FloatArray(60); val dD = FloatArray(60); val dZ = FloatArray(60); val dP = FloatArray(60)
    val path = Path()
    // 网格：声波 14 根 × 97 点、倒影 4 根、光环 7 根 × 121 点、中间球小环 4 根 × 73 点
    val mWave = GlowMesh(14, 97, 8); val mRefl = GlowMesh(4, 97, 4)
    val mRing = GlowMesh(7, 121, 8); val mMini = GlowMesh(4, 73, 4)
    // 每帧复用的点数组（按最多 121 点开）
    val xs = FloatArray(121); val ys = FloatArray(121); val ys2 = FloatArray(121)
    val rgb = IntArray(121); val ga = FloatArray(121); val al = FloatArray(121); val al2 = FloatArray(121)
    // 声波每个采样点上、同一条丝带各根线共用的量
    val env = FloatArray(97); val ph = FloatArray(97); val twS = FloatArray(97); val y0 = FloatArray(97); val yd = FloatArray(97)
    init { for (i in 0 until 60) { dA[i] = (Math.random() * 6.283).toFloat(); dD[i] = Math.random().toFloat(); dZ[i] = Math.random().toFloat(); dP[i] = (Math.random() * 6.283).toFloat() } }

    fun ripple() { ripples += t }

    /** 每帧推进：音量表（起 60ms 落 280ms）、频谱（起 25ms 落 150ms）、五根弹簧（阻尼 .78）、换色（约 0.4 秒） */
    fun step(open: Boolean) {
        val dt = (t - lastT).coerceIn(0f, .064f); lastT = t
        spark += ((if (open) 1f else 0f) - spark) * min(1f, dt * (if (open) 2f else 7f))
        val raw = when (st) { LISTEN -> Talk.level; SPEAK -> Speech.amp; else -> 0f }
        level += (raw - level) * min(1f, dt * (if (raw > level) 16f else 3.5f))
        for (i in 0 until 45) {
            val x = i / 44f
            val v = when (st) {
                LISTEN -> Talk.bands[i]
                SPEAK -> {
                    val f1 = .12f + .06f * sin(t * 3.1f); val f2 = .34f + .1f * sin(t * 2.3f + 1); val f3 = .62f + .08f * sin(t * 1.7f + 2)
                    level * (1.05f * exp(-(x - f1).pow(2) / .006f) + .75f * exp(-(x - f2).pow(2) / .01f) + .45f * exp(-(x - f3).pow(2) / .008f) + .18f * (1 - x)) * (.82f + .36f * Math.random().toFloat())
                }
                THINK -> { val u = ((x * 2 + t * .55f) % 2f) - 1f; .32f * exp(-u * u / .05f) + .06f }
                else -> 0f
            }.coerceIn(0f, 1f)
            band[i] += (v - band[i]) * min(1f, dt * (if (v > band[i]) 40f else 6.5f))
        }
        val g = when (st) { LISTEN -> .10f + level; SPEAK -> .2f + level * .95f; THINK -> .42f + .14f * sin(t * 1.3f); else -> 0f }
        for (i in 0 until 5) {
            val goal = g * (.72f + .28f * sin(t * (.7f + i * .23f) + i * 1.7f))
            val k = 90f + i * 14f; val c = 2 * .78f * sqrt(k)
            velR[i] += (k * (goal - ampR[i]) - c * velR[i]) * dt; ampR[i] += velR[i] * dt
            wg[i] += (WT[st][i] - wg[i]) * min(1f, dt * 2.6f)
        }
        glow += ((if (st == MUTED) .22f else .55f + level * .6f) - glow) * min(1f, dt * 3f)
        rot += dt * 6.2832f / 48f
        for (i in 0 until 60) { dD[i] += (.012f + dZ[i] * .03f) * dt * (1 + level * 2.2f); if (dD[i] > 1f) { dD[i] = .08f + .1f * Math.random().toFloat(); dA[i] = (Math.random() * 6.283).toFloat() } }
        ripples.removeAll { t - it > .9f }
    }

    fun bandAt(a0: Float): Float {
        val a = ((a0 % 6.2832f) + 6.2832f) % 6.2832f
        val u = if (a < Math.PI) a / Math.PI.toFloat() else (6.2832f - a) / Math.PI.toFloat()
        val f = u * 44; val i = f.toInt().coerceIn(0, 44); val k = f - i
        return band[i] * (1 - k) + band[min(44, i + 1)] * k
    }
}

private fun ease(x: Float) = 1 - (1 - x.coerceIn(0f, 1f)).pow(3)
private fun hueAt(p0: Float): Color {
    val p = (((p0 % 1f) + 1f) % 1f) * (HUES.size - 1); val i = p.toInt().coerceIn(0, HUES.size - 2); val f = p - i
    val a = HUES[i]; val b = HUES[i + 1]
    return Color(a.red + (b.red - a.red) * f, a.green + (b.green - a.green) * f, a.blue + (b.blue - a.blue) * f)
}

/* ---------------- 画 ---------------- */

/** 一圈丝带：几根细线绕成环，半径随频谱往外鼓，线与线相位错开扭成网纱（跟横向声波同一个公式） */
private fun DrawScope.strandRing(cx: Float, cy: Float, r0: Float, a: Float, n: Int, m: Int, lw: Float, sweep: Float, t: Float, mini: Boolean) {
    // 每根线：细线 lw + （大环）4dp 宽、16% 亮的光晕，一条网格带画完；一条丝带一次 drawVertices
    val mesh = if (mini) Fx.mMini else Fx.mRing
    if (mini) mesh.line(lw / 2) else mesh.glow(lw / 2, 2.dp.toPx(), .16f)
    val xs = Fx.xs; val ys = Fx.ys; val rgb = Fx.rgb; val ga = Fx.ga; val alA = Fx.al
    val gx = cx - r0; val gy = cy - r0; val gl = 4 * r0
    RIBS.forEachIndexed { ri, r ->
        val w = Fx.wg[ri]; if (w < .05f) return@forEachIndexed
        val dir = if (ri % 2 == 1) 1f else -1f
        val rr = Fx.rot * dir * (1 + ri * .12f) + ri * 1.26f
        mesh.reset()
        for (j in 0 until n) {
            val q = j / (n - 1f) - .5f
            val al = (if (mini) .3f else .1f + .17f * (1 - abs(q) * 1.6f)) * w * (if (Fx.st == MUTED) .45f else 1f)
            for (k in 0..m) {
                val ang = (-Math.PI / 2).toFloat() + k / m.toFloat() * sweep * 6.2832f
                val b = Fx.bandAt(ang + (Math.PI / 2).toFloat() + rr)
                val s = .55f + .45f * sin(ang * (3 + ri) + t * r.s * 1.6f + ri + q * r.tw * 1.4f)
                val rad = r0 + (ri - 2) * 1.6f * density + a * (.06f + b * 1.15f) * s * (.6f + .4f * w)
                val x = cx + cos(ang) * rad; val y = cy + sin(ang) * rad
                xs[k] = x; ys[k] = y
                rgb[k] = r.ring.at(((x - gx) + (y - gy)) / gl, ga, k); alA[k] = ga[k] * al
            }
            mesh.add(xs, ys, rgb, alA)
        }
        drawIntoCanvas { mesh.draw(it.nativeCanvas) }
    }
}

private fun DrawScope.voiceScene(cx: Float, cy: Float) {
    val t = Fx.t; val w = size.width; val h = size.height
    val sp = Fx.spark
    val line = ease((sp - .05f) / .55f); val rib = ease((sp - .4f) / .6f)
    val sway = .085f * sin(t * .16f) + .03f * sin(t * .41f)
    // 远景：夜海
    drawRect(Brush.verticalGradient(0f to Color(0xFF0C1328), .5f to Color(0xFF070B18), 1f to Color(0xFF03050B)))
    // 中景：心口后面一团呼吸的彩色辉光
    val hue = .5f + .5f * sin(t * .35f)
    fun blob(x: Float, y: Float, r: Float, c: Color) = drawCircle(Brush.radialGradient(listOf(c, Color.Transparent), Offset(x, y), r), r, Offset(x, y), blendMode = BlendMode.Plus)
    blob(cx - w * .14f, cy, w * .7f, Color(60, 100, 255).copy(alpha = (.17f * Fx.glow * line).coerceIn(0f, 1f)))
    blob(cx + w * .14f, cy, w * .62f, Color(255, 70, 180).copy(alpha = (.13f * Fx.glow * line * (.6f + .4f * hue)).coerceIn(0f, 1f)))
    blob(cx, cy, w * .45f, Color(40, 220, 230).copy(alpha = (.10f * Fx.glow * line * (1 - .4f * hue)).coerceIn(0f, 1f)))
    // 微尘：从心口向外散，远的小而清楚，近的大而虚
    val far = hypot(w, h) * .62f
    for (i in 0 until 60) {
        val d = Fx.dD[i]; val z = Fx.dZ[i]; val ang = Fx.dA[i] + sway * (.3f + z)
        val x = cx + cos(ang) * d * far; val y = cy + sin(ang) * d * far * .92f
        val fade = min(1f, (d - .08f) / .18f).coerceAtLeast(0f) * min(1f, (1 - d) / .15f).coerceAtLeast(0f)
        if (z > .82f) blob(x, y, (2.5f + z * 4f) * 2.2f * density, Color(170, 200, 255).copy(alpha = .05f * sp * fade))
        else drawCircle(Color(190, 210, 255).copy(alpha = ((.10f + .2f * (.5f + .5f * sin(t * 1.6f + Fx.dP[i]))) * sp * fade).coerceIn(0f, 1f)), (.35f + z * 1.1f) * density, Offset(x, y))
    }

    // 声波：围在她身后的弧形光幕（真 3D 透视；正中最远、两头朝镜头弯过来），下面一层倒影
    val f = .56f * w; val camH = 1.5f; val zb = 4f; val R = 3.4f; val th = 1.2f; val aw = 2.25f
    val hy = cy - f * camH / zb
    fun px(a0: Float, rr: Float): Float { val a = a0 + sway; val z = zb - rr * (1 - cos(a)); return cx + f * rr * sin(a) / z }
    fun py(a0: Float, rr: Float, disp: Float): Float { val a = a0 + sway; val z = zb - rr * (1 - cos(a)); return hy + f * (camH - disp) / z }
    val thm = th * line; val N = 14; val M = 96
    val thin = .7f * density; val wide = 5f * density
    // 每根线：细线 thin + 宽 wide、16% 亮的光晕，一条网格带；每 4 根带一条 3dp、22% 亮的倒影。一条丝带两次 drawVertices
    Fx.mWave.glow(thin / 2, wide / 2, .16f); Fx.mRefl.line(1.5f * density)
    if (rib > .002f) RIBS.forEachIndexed { ri, r ->
        val rr = R + (ri - 2) * .16f
        val amp = aw * max(0f, Fx.ampR[ri]) * rib * r.a * (.55f + .45f * Fx.wg[ri])
        val c1 = r.l[0] + .16f * sin(t * .21f * (if (Fx.st == THINK) 1.8f else 1f) + ri * 2.1f)
        val c2 = r.l[2] + .16f * sin(t * .34f * (if (Fx.st == THINK) 1.8f else 1f) + ri * 2.1f + 1)
        // 同一采样点上各根线共用：横坐标、透视（py = y0 - yd·d）、包络、相位、扭转系数、渐变色
        val xs = Fx.xs; val ys = Fx.ys; val ys2 = Fx.ys2; val rgb = Fx.rgb; val ga = Fx.ga; val alA = Fx.al; val alB = Fx.al2
        val env = Fx.env; val ph = Fx.ph; val twS = Fx.twS; val y0 = Fx.y0; val yd = Fx.yd
        for (k in 0..M) {
            val u = k / M.toFloat() * 2 - 1; val a = u * thm
            val e = (1 - exp(-(u - c1).pow(2) / (r.l[1] * r.l[1]))) * (1 - exp(-(u - c2).pow(2) / (r.l[3] * r.l[3])))
            env[k] = (1 - e) * exp(-(u / .97f).pow(10))
            ph[k] = u * r.k * Math.PI.toFloat() + t * r.s * 2 + ri * 1.3f
            twS[k] = 1 + .4f * sin(u * 2.4f + t * .6f)
            val x = px(a, rr); xs[k] = x
            val aa = a + sway; val z = zb - rr * (1 - cos(aa))
            y0[k] = hy + f * camH / z; yd[k] = f / z
            rgb[k] = r.wave.at(x / w, ga, k)
        }
        val ts = t * r.s * 1.3f
        Fx.mWave.reset(); Fx.mRefl.reset()
        for (j in 0 until N) {
            val q = j / (N - 1f) - .5f
            val al = ((.07f + .18f * (1 - abs(q) * 1.6f)) * (.5f + .5f * min(1f, amp / (aw * .22f))) * (.35f + .65f * Fx.wg[ri])).coerceIn(0f, 1f)
            val refl = j % 4 == 0
            val cq = cos(q * 1.1f); val tq = q * r.tw
            for (k in 0..M) {
                val tw = tq * twS[k]
                var s = sin(ph[k] + tw) * cq + .35f * sin(ph[k] * 2.1f - ts + tw * .6f)
                s *= if (s > 0) 1 + r.bias else 1 - r.bias
                val d = amp * env[k] * s
                ys[k] = y0[k] - yd[k] * d; alA[k] = ga[k] * al
                if (refl) { ys2[k] = y0[k] + yd[k] * d * .85f; alB[k] = ga[k] * al * .22f }
            }
            Fx.mWave.add(xs, ys, rgb, alA)
            if (refl) Fx.mRefl.add(xs, ys2, rgb, alB)
        }
        drawIntoCanvas { Fx.mRefl.draw(it.nativeCanvas); Fx.mWave.draw(it.nativeCanvas) }
    }
    // 光线：顺着弧走的一根白亮细线
    if (sp > .01f) {
        val bright = (.55f + .45f * min(1f, Fx.level * 1.3f + (if (Fx.st == SPEAK) .2f else 0f))) * (if (Fx.st == MUTED) .55f else 1f)
        val lb = Brush.horizontalGradient(
            0f to Color.Transparent, .12f to Color(140, 190, 255).copy(alpha = .7f * bright), .5f to Color.White.copy(alpha = .8f * bright),
            .88f to Color(255, 160, 215).copy(alpha = .7f * bright), 1f to Color.Transparent, startX = 0f, endX = w,
        )
        val p = Fx.path; p.reset()
        for (k in 0..80) { val a = (k / 80f * 2 - 1) * th * line; val x = px(a, R); val y = py(a, R, 0f); if (k == 0) p.moveTo(x, y) else p.lineTo(x, y) }
        drawPath(p, lb, alpha = .3f, style = Stroke(5f * density, cap = StrokeCap.Round), blendMode = BlendMode.Plus)
        drawPath(p, lb, style = Stroke(1.1f * density, cap = StrokeCap.Round), blendMode = BlendMode.Plus)
        val s2 = max(0f, 1 - line) * sp
        if (s2 > .01f) { blob(cx, py(0f, R, 0f), (26f + 34f * s2) * density, Color(200, 230, 255).copy(alpha = .85f * s2)); blob(cx, py(0f, R, 0f), 6f * density, Color.White.copy(alpha = s2)) }
    }

    // 光环：Real'sWave 的频谱环，细丝网纱；内圈保留发丝刻度，反向转
    val sweep = ease((sp - .2f) / .7f)
    if (sweep > .002f) {
        val r0 = min(w * .3f, 118f * density)
        strandRing(cx, cy, r0, 52f * density, 7, 120, .7f * density, sweep, t, false)
        for (i in 0 until 90) {
            val pp = i / 90f; if (pp > sweep) break
            val a = (-Math.PI / 2).toFloat() + pp * 6.2832f + Fx.rot
            val b = Fx.bandAt(a + (Math.PI / 2).toFloat()); val c = hueAt(pp + t * .02f)
            val q0 = r0 - (10f + b * 20f) * density; val q1 = r0 - 5f * density
            drawLine(Color(c.red * .4f + .59f, c.green * .4f + .59f, c.blue * .4f + .59f, ((.18f + b * .7f) * (if (Fx.st == MUTED) .4f else 1f)).coerceIn(0f, 1f)),
                Offset(cx + cos(a) * q0, cy + sin(a) * q0), Offset(cx + cos(a) * q1, cy + sin(a) * q1), .8f * density)
        }
        // 涟漪：她开口、被打断时，从环上推出三圈细丝，0.9 秒减速淡出
        Fx.ripples.forEach { t0 ->
            val e = ((t - t0) / .9f).coerceIn(0f, 1f); val d = 1 - (1 - e).pow(3); val rr = r0 + d * 150f * density
            for (j in 0 until 3) {
                val rad = rr - j * 5f * density * (1 - e)
                drawCircle(Brush.horizontalGradient(listOf(Color(0xFF3D6BFF), Color(0xFFC25BFF), Color(0xFFFF7A45)), cx - rad, cx + rad), rad, Offset(cx, cy),
                    alpha = ((1 - e) * (.5f - j * .13f)).coerceIn(0f, 1f), style = Stroke(.8f * density), blendMode = BlendMode.Plus)
            }
        }
    }
    // 四角压暗（也以心口为圆心）
    val vr = hypot(w, h) * .7f
    drawRect(Brush.radialGradient(0f to Color.Transparent, (w * .42f / vr) to Color.Transparent, 1f to Color.Black.copy(alpha = .6f), center = Offset(cx, cy), radius = vr))
}

/* ---------------- 入口：权限 + 回调 ---------------- */

/** 一直挂在聊天页：点了球先要麦克风权限，给了才开 */
@Composable
internal fun VoiceGate(onText: (String, Boolean) -> Unit, onBarge: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { Talk.init(ctx) }
    SideEffect { Talk.onText = onText; Talk.onBarge = { Fx.ripple(); onBarge() } }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        Talk.want = false
        if (ok) Talk.start() else Toast.makeText(ctx, "没有麦克风权限，开不了语音对话", Toast.LENGTH_SHORT).show()
    }
    LaunchedEffect(Talk.want) {
        if (!Talk.want) return@LaunchedEffect
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { Talk.want = false; Talk.start() }
        else ask.launch(Manifest.permission.RECORD_AUDIO)
    }
}

private fun stateOf(chat: ChatState) = when {
    Talk.muted -> MUTED
    Speech.playing.isNotEmpty() -> SPEAK
    chat.busy || Talk.transcribing || Speech.loading.isNotEmpty() -> THINK
    else -> LISTEN
}

/* ---------------- 背景层（在鲸鱼娘下面） ---------------- */

@Composable
internal fun VoiceBackdrop(chat: ChatState) {
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Talk.open) {
        if (Talk.open) reveal.animateTo(1f, tween(460, easing = CubicBezierEasing(.05f, .7f, .1f, 1f)))
        else reveal.animateTo(0f, tween(340, easing = CubicBezierEasing(.3f, 0f, .8f, .15f)))
    }
    LaunchedEffect(Unit) { snapshotFlow { reveal.value }.collect { Talk.shown = it } }
    // 帧时钟：只在语音页在的时候跑
    LaunchedEffect(Unit) {
        val t0 = androidx.compose.runtime.withFrameNanos { it }
        Fx.lastT = 0f
        while (true) androidx.compose.runtime.withFrameNanos { Fx.t = (it - t0) / 1e9f }
    }
    // 状态变到「在说」推一圈涟漪；系统识别空闲时再开一轮
    LaunchedEffect(Unit) { snapshotFlow { stateOf(chat) }.collect { if (it == SPEAK && Fx.st != SPEAK) Fx.ripple(); Fx.st = it } }
    LaunchedEffect(Unit) { while (true) { delay(600); Talk.tick(chat.busy) } }
    LaunchedEffect(Unit) { snapshotFlow { Talk.open }.collect { Whale.hold("voice", it) } }
    DisposableEffect(Unit) { onDispose { Whale.voiceRoom(0f, 0f); Whale.hold("voice", false) } }
    androidx.activity.compose.BackHandler(enabled = Talk.open) { Talk.close() }

    var origin by remember { mutableStateOf(Offset.Zero) }
    var center by remember { mutableStateOf(Offset.Unspecified) }
    Box(
        Modifier.fillMaxSize()
            .onGloballyPositioned { origin = it.boundsInWindow().topLeft }
            .graphicsLayer {
                // 从杏色球的圆心铺开整页 / 缩回球里
                val from = Talk.from.takeIf { it.isSpecified } ?: Offset(size.width / 2, size.height)
                val c = from - origin
                val far = maxOf(hypot(c.x, c.y), hypot(size.width - c.x, c.y), hypot(c.x, size.height - c.y), hypot(size.width - c.x, size.height - c.y))
                val r = 19.dp.toPx() + (far - 19.dp.toPx()) * reveal.value
                clip = reveal.value < 1f   // 铺满以后不再按圆裁（全屏非矩形裁剪每帧都要算）
                shape = GenericShape { _, _ -> addOval(Rect(c, r)) }
            }
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            Fx.step(Talk.open)
            val c = center.takeIf { it.isSpecified } ?: Offset(size.width / 2, size.height * .4f)
            voiceScene(c.x, c.y)
        }
        // 她能站的范围：顶栏下面到按钮上面；心口取这块高度的 36%
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Spacer(Modifier.height(84.dp))
            Box(
                Modifier.weight(1f).fillMaxWidth().onGloballyPositioned {
                    val r = it.boundsInWindow()
                    center = Offset(r.center.x - origin.x, r.top - origin.y + r.height * .36f)
                    if (Talk.open) Whale.voiceRoom(r.top, r.bottom)
                },
            )
            Spacer(Modifier.height(170.dp))
        }
    }
    LaunchedEffect(Talk.open) { if (!Talk.open) Whale.voiceRoom(0f, 0f) }
}


/* ---------------- 按钮层（在鲸鱼娘上面） ---------------- */

private val icDown = Ic("M6 9l6 6 6-6")
private val icMic = Ic("M9 6a3 3 0 0 1 6 0v5a3 3 0 0 1-6 0zM5.5 11a6.5 6.5 0 0 0 13 0M12 17.5V21")
private val icMicOff = Ic("M9 6a3 3 0 0 1 6 0v5a3 3 0 0 1-6 0zM5.5 11a6.5 6.5 0 0 0 13 0M12 17.5V21M4 4l16 16")
private val icEnd = Ic("M6.5 6.5l11 11M17.5 6.5l-11 11")

@Composable
internal fun VoiceControls(chat: ChatState, title: String) {
    val a = Talk.shown
    val st = stateOf(chat)
    val tint by androidx.compose.animation.animateColorAsState(TINT[st], tween(400), label = "voiceTint")
    val rise by animateFloatAsState(if (Talk.open) 1f else 0f, tween(if (Talk.open) 360 else 200, delayMillis = if (Talk.open) 180 else 0, easing = CubicBezierEasing(.05f, .7f, .1f, 1f)), label = "voiceRise")
    val act = LocalContext.current as? android.app.Activity
    val stat = FrameStat.on && Talk.open
    DisposableEffect(stat, act) {
        if (stat && act != null) FrameStat.start(act)
        onDispose { FrameStat.stop() }
    }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = (a * 1.4f - .4f).coerceIn(0f, 1f) }) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            // 顶栏：收起 · 名字 + 状态点 · 识别来源
            Box(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp).graphicsLayer { alpha = rise; translationY = (1 - rise) * 14.dp.toPx() }) {
                Pressable({ Talk.close() }, Modifier.align(Alignment.CenterStart), label = "收起") { p ->
                    Box(Modifier.size(42.dp).background(Color.White.copy(alpha = if (p) .08f else 0f), CircleShape), contentAlignment = Alignment.Center) {
                        SvgIcon(icDown, Color(0xFFC9CFDC), 20.dp, stroke = 1.6f)
                    }
                }
                // 长按名字：开关帧耗时小浮窗（真机查卡顿用）
                Column(Modifier.align(Alignment.Center).pointerInput(Unit) { detectTapGestures(onLongPress = { FrameStat.on = !FrameStat.on }) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Txt(title, Color(0xFFF3F5FA), 17.sp, spacing = 1.sp, maxLines = 1)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(5.dp).drawBehind { drawCircle(tint.copy(alpha = .5f), size.minDimension * 1.4f); drawCircle(tint) })
                        Spacer(Modifier.width(6.dp))
                        Txt(arrayOf("在听", "在想", "在说", "已静音")[st], Color(0xFF7F879B), 11.5.sp, spacing = .9.sp)
                    }
                    if (FrameStat.on) Txt(FrameStat.text, Color(0xFFFFD25A), 10.5.sp, Modifier.padding(top = 4.dp)
                        .background(Color.Black.copy(alpha = .55f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp), maxLines = 1)
                    // 同一个浮窗里看断句过程；点「回放上一句」听识别实际收到的声音（含开口前那段）
                    if (FrameStat.on) Column(Modifier.padding(top = 4.dp).widthIn(max = 320.dp)
                        .background(Color.Black.copy(alpha = .6f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Talk.trace.forEach { Txt(it, Color(0xFFCFD6E6), 10.sp, maxLines = 1) }
                        Txt("▶ 回放上一句", Color(0xFFFFD25A), 11.sp, Modifier.padding(top = 3.dp).clickable { Talk.replay() })
                    }
                }
                Pressable({ Talk.cycleSrc() }, Modifier.align(Alignment.CenterEnd), label = "换识别") { p ->
                    Row(Modifier
                        .background(Color.White.copy(alpha = if (p) .1f else .06f), RoundedCornerShape(50))
                        .border(.5.dp, Color.White.copy(alpha = .12f), RoundedCornerShape(50))
                        .padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Txt(Talk.label(), Color(0xFFC9CFDC), 11.5.sp, maxLines = 1)
                        val tg = Talk.tag()
                        if (tg.isNotEmpty()) { Spacer(Modifier.width(5.dp)); Txt(tg, Color(0xFF8FE0B0), 9.5.sp, maxLines = 1) }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            VoiceCaption(st, title, Modifier.graphicsLayer { alpha = rise })
            // 提示（识别失败、没 Key 之类）；本地识别模型下载时显示进度
            Box(Modifier.fillMaxWidth().height(36.dp), contentAlignment = Alignment.Center) {
                val dl = Ear.progress
                if (dl >= 0f) Row(Modifier.background(Color(0x1FFFFFFF), RoundedCornerShape(12.dp)).border(.5.dp, Color.White.copy(alpha = .1f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt("下载本地识别", Color(0xFFC9CFDC), 12.5.sp, maxLines = 1)
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.width(110.dp).height(3.dp).background(Color.White.copy(alpha = .12f), RoundedCornerShape(2.dp))) {
                        Box(Modifier.fillMaxWidth(dl.coerceIn(0f, 1f)).height(3.dp).background(Brush.horizontalGradient(listOf(Color(0xFF5AA8FF), Color(0xFF8FE0FF))), RoundedCornerShape(2.dp)))
                    }
                    Spacer(Modifier.width(10.dp))
                    Txt("${(dl * Ear.TOTAL / 1048576f).toInt()} / ${(Ear.TOTAL / 1048576f).toInt()}MB", Color(0xFF7F879B), 11.5.sp, maxLines = 1)
                }
                val n = if (dl >= 0f) "" else Talk.note
                val na by animateFloatAsState(if (n.isNotEmpty()) 1f else 0f, tween(200), label = "note")
                if (na > 0f) Txt(n, Color(0xFF141826), 12.5.sp, Modifier.graphicsLayer { alpha = na }
                    .background(Color(0xF5F4F6FB), RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 8.dp), maxLines = 1)
            }
            Row(
                Modifier.fillMaxWidth().height(96.dp).graphicsLayer { alpha = rise; translationY = (1 - rise) * 16.dp.toPx() },
                horizontalArrangement = Arrangement.spacedBy(38.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
            ) {
                Pressable({ Talk.toggleMute() }, label = if (Talk.muted) "打开麦克风" else "静音") { p ->
                    Box(Modifier.size(54.dp).graphicsLayer { scaleX = if (p) .93f else 1f; scaleY = scaleX }
                        .background(if (Talk.muted) Color(0xFFEEF1F8) else Color.White.copy(alpha = .06f), CircleShape)
                        .border(.5.dp, Color.White.copy(alpha = .14f), CircleShape), contentAlignment = Alignment.Center) {
                        SvgIcon(if (Talk.muted) icMicOff else icMic, if (Talk.muted) Color(0xFF0A0F1D) else Color(0xFFE6EAF3), 21.dp, stroke = 1.6f)
                    }
                }
                // 中间的球：深色玻璃里装着同一种丝带的小光环；她说话时亮出白色方块，点一下打断
                Pressable({ if (st == SPEAK || st == THINK) Talk.onBarge?.invoke() else if (st == MUTED) Talk.toggleMute() }, label = if (st == SPEAK) "打断" else "说话") { p ->
                    Box(Modifier.size(80.dp).graphicsLayer { scaleX = if (p) .95f else 1f; scaleY = scaleX }
                        .drawBehind {
                            val lv = Fx.level
                            drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = .18f + lv * .3f), Color.Transparent), center, size.minDimension * (.6f + lv * .25f)), size.minDimension * (.6f + lv * .25f))
                        }
                        .background(Brush.radialGradient(listOf(Color.White.copy(alpha = .10f), Color(0xFF0B1020)), radius = 140f), CircleShape)
                        .border(.5.dp, Color.White.copy(alpha = .22f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Canvas(Modifier.size(88.dp).offset(0.dp, 0.dp)) {
                            val r = (if (st == SPEAK) 26f else 22f + Fx.level * 3f) * density
                            strandRing(size.width / 2, size.height / 2, r, (if (st == SPEAK) 9f else 12f) * density, 4, 72, .7f * density, 1f, Fx.t * 1.4f, true)
                        }
                        val sq by animateFloatAsState(if (st == SPEAK) 1f else 0f, tween(180), label = "sq")
                        if (sq > 0f) Box(Modifier.size(16.dp).graphicsLayer { alpha = sq; scaleX = .6f + .4f * sq; scaleY = scaleX }
                            .background(Color(0xFFF4F6FB), RoundedCornerShape(4.5.dp)))
                    }
                }
                Pressable({ Talk.close() }, label = "结束") { p ->
                    Box(Modifier.size(54.dp).graphicsLayer { scaleX = if (p) .93f else 1f; scaleY = scaleX }
                        .background(Color(0x24FF5E52), CircleShape).border(.5.dp, Color(0x4DFF786E), CircleShape), contentAlignment = Alignment.Center) {
                        SvgIcon(icEnd, Color(0xFFFF8A7E), 19.dp, stroke = 1.9f)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(34.dp).graphicsLayer { alpha = rise }, contentAlignment = Alignment.Center) {
                Txt(when (st) { LISTEN -> if (Talk.hearing) "在听你说…" else "说完停一下，她就会接"; SPEAK -> "直接开口，或点方块打断她"; MUTED -> "点麦克风继续"; else -> "" },
                    Color(0xFF5B6377), 11.5.sp, spacing = .7.sp)
            }
        }
    }
}


/* ---------------- 字幕（原型 1Uiwfpn4YePtw973uUAzEy） ---------------- */

private val CAP_INK = Color(0xFFF3F5FA)
private val CAP_DIM = Color(0x7AF3F5FA)
private val CAP_UNREAD = Color(0x61F3F5FA)

/**
 * 你说的：边说边出，最后三个字还在变（淡），前面的定了（白），太长只留后 40 字；
 * 她说的：正在读的那句，读到哪亮到哪；在想：「…」。刚发出的那句上浮 26dp、缩到 .94、淡出（450ms）。
 */
@Composable
private fun VoiceCaption(st: Int, title: String, modifier: Modifier) {
    val you = Talk.partial
    val her = Speech.saying
    val fly = remember { Animatable(1f) }
    LaunchedEffect(Talk.sentAt) {
        if (Talk.sentAt > 0L) { fly.snapTo(0f); fly.animateTo(1f, tween(450, easing = CubicBezierEasing(.3f, 0f, .2f, 1f))) }
    }
    val flying = fly.value < 1f && Talk.sent.isNotEmpty()
    val youC = Color(0xFF5AA8FF)
    var who = ""; var whoC = youC
    val text = androidx.compose.ui.text.buildAnnotatedString {
        fun put(t: String, c: Color) = withStyle(androidx.compose.ui.text.SpanStyle(color = c)) { append(t) }
        when {
            you.isNotBlank() -> {
                who = "你"
                val t = if (you.length > 40) "…" + you.takeLast(40) else you
                val k = (t.length - 3).coerceAtLeast(0)
                put(t.substring(0, k), CAP_INK); put(t.substring(k), CAP_DIM)
            }
            flying -> { who = "你"; put(Talk.sent.let { if (it.length > 40) "…" + it.takeLast(40) else it }, CAP_INK) }
            st == SPEAK && her.isNotBlank() -> {
                who = title; whoC = Color(0xFFFF9BC8)
                val k = kotlin.math.ceil(her.length * Speech.sayingP).toInt().coerceIn(0, her.length)
                put(her.substring(0, k), CAP_INK); put(her.substring(k), CAP_UNREAD)
            }
            st == THINK -> { who = title; whoC = Color(0xFFB08CFF); put("…", CAP_UNREAD) }
        }
    }
    val wa by animateFloatAsState(if (who.isNotEmpty()) 1f else 0f, tween(250), label = "capWho")
    Column(
        modifier.fillMaxWidth().height(84.dp).padding(horizontal = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom,
    ) {
        Row(Modifier.graphicsLayer { alpha = wa }, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(4.dp).background(whoC, CircleShape))
            Spacer(Modifier.width(6.dp))
            Txt(who, whoC, 11.sp, spacing = 1.5.sp, maxLines = 1)
        }
        Spacer(Modifier.height(4.dp))
        androidx.compose.foundation.text.BasicText(
            text,
            Modifier.graphicsLayer {
                if (flying && you.isBlank()) { val e = fly.value; translationY = -26.dp.toPx() * e; scaleX = 1 - .06f * e; scaleY = scaleX; alpha = 1 - e }
            },
            style = androidx.compose.ui.text.TextStyle(
                fontFamily = Sans, fontSize = 18.sp, lineHeight = 27.sp, letterSpacing = .4.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = .6f), blurRadius = 12f),
            ),
            maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}
