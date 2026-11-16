package com.fuguang.lueying

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 帧耗时小浮窗（语音页长按名字开关）。用系统 FrameMetrics 每帧报的分段耗时，每秒汇总一次：
 * 帧率 · 主线程（动画 + 布局 + 绘制录制）· 渲染线程（同步 + 发命令 + 交换）· GPU（Android 12+）· 超时帧数。
 * 卡的时候看哪一段大，就知道该往哪改。
 */
object FrameStat {
    var on by mutableStateOf(false)
    var text by mutableStateOf("…")
        private set

    private var thread: HandlerThread? = null
    private var listener: Window.OnFrameMetricsAvailableListener? = null
    private var window: Window? = null

    private var n = 0; private var ui = 0L; private var rt = 0L; private var gpu = 0L; private var slow = 0
    private var maxTotal = 0L; private var t0 = 0L

    fun start(act: Activity) {
        if (listener != null) return
        val th = HandlerThread("frame-stat").also { it.start() }; thread = th
        val hz = hzOf(act)
        val budget = (1e9 / hz.coerceAtLeast(30f)).toLong()
        t0 = System.nanoTime()
        val l = Window.OnFrameMetricsAvailableListener { _, m, _ ->
            val u = m.getMetric(FrameMetrics.ANIMATION_DURATION) + m.getMetric(FrameMetrics.INPUT_HANDLING_DURATION) +
                m.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION) + m.getMetric(FrameMetrics.DRAW_DURATION)
            val r = m.getMetric(FrameMetrics.SYNC_DURATION) + m.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION) + m.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION)
            val g = if (Build.VERSION.SDK_INT >= 31) m.getMetric(FrameMetrics.GPU_DURATION) else 0L
            val tot = m.getMetric(FrameMetrics.TOTAL_DURATION)
            n++; ui += u; rt += r; gpu += g; if (tot > budget) slow++; if (tot > maxTotal) maxTotal = tot
            val now = System.nanoTime()
            if (now - t0 >= 1_000_000_000L) {
                val sec = (now - t0) / 1e9
                fun ms(v: Long) = "%.1f".format(v / 1e6 / n.coerceAtLeast(1))
                text = "%.0f帧  主%s  渲%s%s  慢%d  最长%.0fms".format(
                    n / sec, ms(ui), ms(rt), if (Build.VERSION.SDK_INT >= 31) "  GPU" + ms(gpu) else "", slow, maxTotal / 1e6,
                )
                n = 0; ui = 0; rt = 0; gpu = 0; slow = 0; maxTotal = 0; t0 = now
            }
        }
        listener = l; window = act.window
        act.window.addOnFrameMetricsAvailableListener(l, Handler(th.looper))
    }

    @Suppress("DEPRECATION")
    private fun hzOf(act: Activity): Float =
        if (Build.VERSION.SDK_INT >= 30) act.display?.refreshRate ?: 60f else act.windowManager.defaultDisplay.refreshRate

    fun stop() {
        listener?.let { l -> runCatching { window?.removeOnFrameMetricsAvailableListener(l) } }
        listener = null; window = null
        thread?.quitSafely(); thread = null
        text = "…"
    }
}
