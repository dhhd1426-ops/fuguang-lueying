package com.fuguang.lueying

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Smart Turn v3 的输入：Whisper 式 log-mel（80 × 800）。
 * 逐行照 pipecat `_whisper_features.py`（transformers WhisperFeatureExtractor 的 numpy 版）：
 * 取最后 8 秒、前面补零 → 零均值单位方差 → 反射补边、周期 Hann、n_fft 400 / hop 160 → Slaney mel → log10 → 截到最大值 −8 → (x+4)/4。
 * 纯计算，不碰 Android，可以在电脑上对照 Python 验。
 */
object TurnMel {
    const val RATE = 16000
    const val SAMPLES = RATE * 8
    const val MELS = 80
    const val FRAMES = 800
    private const val NFFT = 400
    private const val HOP = 160
    private const val BINS = NFFT / 2 + 1

    private val window = DoubleArray(NFFT) { 0.5 - 0.5 * cos(2 * PI * it / NFFT) }
    private val cosT = DoubleArray(NFFT) { cos(2 * PI * it / NFFT) }
    private val sinT = DoubleArray(NFFT) { -sin(2 * PI * it / NFFT) }
    private val FACT = intArrayOf(5, 5, 2, 2, 2, 2)       // 400 = 5·5·2⁴

    /** 混合基 FFT（递归 DIT），比逐点 DFT 快约 10 倍；[tr]/[ti] 每层一段临时区 */
    private class Fft {
        val re = DoubleArray(NFFT); val im = DoubleArray(NFFT)
        private val tr = DoubleArray(NFFT * 2); private val ti = DoubleArray(NFFT * 2)
        fun run(x: DoubleArray) = go(x, 0, 1, NFFT, 0, 0, 0)
        private fun go(x: DoubleArray, inOff: Int, stride: Int, n: Int, out: Int, lvl: Int, tmp: Int) {
            if (n == 1) { re[out] = x[inOff]; im[out] = 0.0; return }
            val p = FACT[lvl]; val m = n / p
            for (q in 0 until p) go(x, inOff + q * stride, stride * p, m, out + q * m, lvl + 1, tmp + n)
            val step = NFFT / n
            for (k in 0 until m) {
                for (r in 0 until p) {
                    var sr = 0.0; var si = 0.0
                    for (q in 0 until p) {
                        val a = re[out + q * m + k]; val b = im[out + q * m + k]
                        val w = (q * (k + r * m) * step) % NFFT
                        sr += a * cosT[w] - b * sinT[w]; si += a * sinT[w] + b * cosT[w]
                    }
                    tr[tmp + r] = sr; ti[tmp + r] = si
                }
                for (r in 0 until p) { re[out + k + r * m] = tr[tmp + r]; im[out + k + r * m] = ti[tmp + r] }
            }
        }
    }

    /** mel 滤波器：每个 mel 只存非零的那一段 [from, from+w.size) */
    private class Tri(val from: Int, val w: DoubleArray)
    private val filters: Array<Tri> = run {
        fun h2m(f: Double) = if (f >= 1000.0) 15.0 + ln(f / 1000.0) * (27.0 / ln(6.4)) else 3.0 * f / 200.0
        fun m2h(m: Double) = if (m >= 15.0) 1000.0 * exp(ln(6.4) / 27.0 * (m - 15.0)) else 200.0 * m / 3.0
        val lo = h2m(0.0); val hi = h2m(RATE / 2.0)
        val ff = DoubleArray(MELS + 2) { m2h(lo + (hi - lo) * it / (MELS + 1)) }
        val fft = DoubleArray(BINS) { (RATE / 2).toDouble() * it / (BINS - 1) }
        Array(MELS) { m ->
            val enorm = 2.0 / (ff[m + 2] - ff[m])
            val row = DoubleArray(BINS) { k ->
                val down = (ff[m] - fft[k]) * -1 / (ff[m + 1] - ff[m])
                val up = (ff[m + 2] - fft[k]) / (ff[m + 2] - ff[m + 1])
                max(0.0, min(down, up)) * enorm
            }
            val a = row.indexOfFirst { it > 0 }.coerceAtLeast(0)
            val b = row.indexOfLast { it > 0 }.coerceAtLeast(a)
            Tri(a, row.copyOfRange(a, b + 1))
        }
    }

    /** [pcm] 16k 单声道 [-1,1]，任意长度 → 80×800 行优先（mel 在外层） */
    fun features(pcm: FloatArray): FloatArray {
        // 最后 8 秒，不够前面补零（pipecat truncate_audio_to_last_n_seconds）
        val x = DoubleArray(SAMPLES)
        val n = min(pcm.size, SAMPLES)
        val off = SAMPLES - n
        for (i in 0 until n) x[off + i] = pcm[pcm.size - n + i].toDouble()
        // 零均值单位方差（numpy 用 float32 算均值方差；这里 double，差在 1e-6 量级）
        var mean = 0.0; for (v in x) mean += v; mean /= SAMPLES
        var vr = 0.0; for (v in x) { val d = v - mean; vr += d * d }; vr /= SAMPLES
        val sd = sqrt(vr + 1e-7)
        for (i in x.indices) x[i] = (x[i] - mean) / sd
        val pad = NFFT / 2
        fun at(j: Int): Double { val k = j - pad; return x[if (k < 0) -k else if (k >= SAMPLES) 2 * (SAMPLES - 1) - k else k] }

        val out = DoubleArray(MELS * FRAMES)
        val frame = DoubleArray(NFFT)
        val power = DoubleArray(BINS)
        val fft = Fft()
        var top = Double.NEGATIVE_INFINITY
        var cached = -1                                   // 整帧都在补零段：频谱都一样，算一次
        for (t in 0 until FRAMES) {
            val start = t * HOP
            val inPad = start - pad >= 0 && start - pad + NFFT <= off
            if (!(inPad && cached >= 0)) {
                for (i in 0 until NFFT) frame[i] = at(start + i) * window[i]
                fft.run(frame)
                for (k in 0 until BINS) power[k] = fft.re[k] * fft.re[k] + fft.im[k] * fft.im[k]
                for (m in 0 until MELS) {
                    val f = filters[m]; var s = 0.0
                    for (j in f.w.indices) s += f.w[j] * power[f.from + j]
                    out[m * FRAMES + t] = log10(max(1e-10, s))
                }
                if (inPad) cached = t
            } else for (m in 0 until MELS) out[m * FRAMES + t] = out[m * FRAMES + cached]
            for (m in 0 until MELS) top = max(top, out[m * FRAMES + t])
        }
        // 参考实现算 801 帧再丢最后一帧：最大值也只在前 800 帧里取，这里一致
        return FloatArray(out.size) { ((max(out[it], top - 8.0) + 4.0) / 4.0).toFloat() }
    }
}
