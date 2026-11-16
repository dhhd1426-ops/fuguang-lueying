package com.fuguang.lueying

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 发光细丝的网格画法（语音页声波、光环用）。
 *
 * 一根线 = 沿中线铺开的一条三角形带，截面是几段平台 + 半像素宽的羽化边（等同 Skia 抗锯齿描边的覆盖率坡），
 * 「细芯 + 宽光晕」两层在截面上叠好一次画完；同一批的所有细线合成一次 drawVertices，叠加混合（PLUS）。
 * 替代每根线两三次 drawPath 描边：每帧约 250 次路径描边 → 约 20 次网格绘制，亮度截面与原来一致。
 *
 * strands × pts × P 不能超过 32767（索引是 short）。
 */
internal class GlowMesh(private val strands: Int, private val pts: Int, private val P: Int) {
    private val xy = FloatArray(strands * pts * P * 2)
    // 老版本 drawVertices 按 float 个数校验颜色数组长度，多给一倍，哪个版本都过
    private val col = IntArray(strands * pts * P * 2)
    private val idx: ShortArray
    private val off = FloatArray(P)
    private val mul = FloatArray(P)
    private val nx = FloatArray(pts)
    private val ny = FloatArray(pts)
    private var used = 0
    // 没有 shader 时顶点色和画笔的不透明色相乘（Modulate），画笔必须是白的
    private val paint = Paint().apply { color = android.graphics.Color.WHITE; blendMode = BlendMode.PLUS }

    init {
        require(strands * pts * P <= 32767)
        idx = ShortArray(strands * (pts - 1) * (P - 1) * 6)
        var o = 0
        for (s in 0 until strands) for (i in 0 until pts - 1) for (b in 0 until P - 1) {
            val v00 = s * pts * P + i * P + b; val v10 = v00 + P
            idx[o++] = v00.toShort(); idx[o++] = v10.toShort(); idx[o++] = (v00 + 1).toShort()
            idx[o++] = (v00 + 1).toShort(); idx[o++] = v10.toShort(); idx[o++] = (v10 + 1).toShort()
        }
    }

    /** 截面：细芯半宽 c（亮度 1）+ 光晕半宽 g（亮度 ga，盖住芯所在处也加上），像素 */
    fun glow(c: Float, g: Float, ga: Float) {
        check(P == 8)
        val fr = .5f
        val cc = max(c, fr + .01f); val k = min(1f, c / cc)   // 芯不足一像素宽时压亮度，总亮度守恒
        val o = floatArrayOf(-(g + fr), -(g - fr), -(cc + fr), -(cc - fr), cc - fr, cc + fr, g - fr, g + fr)
        val m = floatArrayOf(0f, ga, ga, ga + k, ga + k, ga, ga, 0f)
        o.copyInto(off); m.copyInto(mul)
    }

    /** 截面：只有一层，半宽 c */
    fun line(c: Float) {
        check(P == 4)
        val fr = .5f
        val cc = max(c, fr + .01f); val k = min(1f, c / cc)
        off[0] = -(cc + fr); off[1] = -(cc - fr); off[2] = cc - fr; off[3] = cc + fr
        mul[0] = 0f; mul[1] = k; mul[2] = k; mul[3] = 0f
    }

    fun reset() { used = 0 }

    /** 加一根线：xs/ys 中线点，rgb 每点颜色（0xRRGGBB），al 每点不透明度（0..1） */
    fun add(xs: FloatArray, ys: FloatArray, rgb: IntArray, al: FloatArray) {
        if (used >= strands) return
        // 法线：前后两点的切线转 90°
        var lx = 0f; var ly = 1f
        for (i in 0 until pts) {
            val a = if (i == 0) 0 else i - 1; val b = if (i == pts - 1) pts - 1 else i + 1
            val tx = xs[b] - xs[a]; val ty = ys[b] - ys[a]
            val l = sqrt(tx * tx + ty * ty)
            if (l > 1e-4f) { lx = -ty / l; ly = tx / l }
            nx[i] = lx; ny[i] = ly
        }
        var v = used * pts * P
        for (i in 0 until pts) {
            val x = xs[i]; val y = ys[i]; val a = al[i]; val c = rgb[i]
            for (b in 0 until P) {
                xy[v * 2] = x + nx[i] * off[b]; xy[v * 2 + 1] = y + ny[i] * off[b]
                val aa = (a * mul[b] * 255f).roundToInt().coerceIn(0, 255)
                col[v] = (aa shl 24) or c
                v++
            }
        }
        used++
    }

    fun draw(canvas: Canvas) {
        if (used == 0) return
        canvas.drawVertices(
            Canvas.VertexMode.TRIANGLES, used * pts * P * 2, xy, 0, null, 0, col, 0,
            idx, 0, used * (pts - 1) * (P - 1) * 6, paint,
        )
    }
}

/** 多段渐变取色（跟 Brush 的颜色停点一样：未预乘、线性插值、两头夹住） */
internal class Grad(private val pos: FloatArray, private val argb: Array<androidx.compose.ui.graphics.Color>) {
    /** 取 t 处颜色：返回 0xRRGGBB，透明度写进 alphaOut[i] */
    fun at(t0: Float, alphaOut: FloatArray, i: Int): Int {
        val t = t0.coerceIn(0f, 1f)
        var s = 0
        while (s < pos.size - 2 && t > pos[s + 1]) s++
        val f = ((t - pos[s]) / (pos[s + 1] - pos[s]).coerceAtLeast(1e-6f)).coerceIn(0f, 1f)
        val a = argb[s]; val b = argb[s + 1]
        alphaOut[i] = a.alpha + (b.alpha - a.alpha) * f
        val r = ((a.red + (b.red - a.red) * f) * 255f).roundToInt().coerceIn(0, 255)
        val g = ((a.green + (b.green - a.green) * f) * 255f).roundToInt().coerceIn(0, 255)
        val bl = ((a.blue + (b.blue - a.blue) * f) * 255f).roundToInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or bl
    }
}
