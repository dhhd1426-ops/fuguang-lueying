package com.fuguang.lueying

import android.os.SystemClock
import android.webkit.WebView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/** 网页里那个视频此刻的样子：秒、总长（直播是 0）、暂停没、速度、缓冲到哪、画面宽高。 */
internal data class VidState(val t: Double, val d: Double, val p: Boolean, val r: Double, val b: Double, val vw: Int, val vh: Int)

/** 通过 JS 摸到全屏里那个 <video>：先找全屏元素里的，找不到就挑网页里最大的。 */
internal object VideoCtl {
    private const val FIND = "var f=document.fullscreenElement||document.webkitFullscreenElement;var v=f&&(f.tagName==='VIDEO'?f:f.querySelector('video'));" +
        "if(!v){var a=0;document.querySelectorAll('video').forEach(function(x){var r=x.getBoundingClientRect(),s=r.width*r.height;if(s>a){a=s;v=x}})}"
    const val STATE = "(function(){$FIND if(!v)return null;var b=0;try{for(var i=0;i<v.buffered.length;i++)if(v.buffered.start(i)<=v.currentTime+1)b=v.buffered.end(i)}catch(e){}" +
        "return {t:v.currentTime,d:isFinite(v.duration)?v.duration:0,p:v.paused,r:v.playbackRate,b:b,vw:v.videoWidth,vh:v.videoHeight}})()"
    const val TOGGLE = "if(v.paused)v.play();else v.pause()"
    /** 最近点到的那个视频在网页里的位置（VideoTouch 记的，CSS 像素）。 */
    const val LAST = "JSON.stringify(window.__fgLast||null)"

    fun run(web: WebView?, body: String) { web?.evaluateJavascript("(function(){$FIND if(v){$body}})()", null) }

    fun parse(r: String?): VidState? = runCatching {
        val o = JSONObject(r!!)
        VidState(o.optDouble("t"), o.optDouble("d"), o.optBoolean("p", true), o.optDouble("r", 1.0), o.optDouble("b"), o.optInt("vw"), o.optInt("vh"))
    }.getOrNull()
}

/** 全屏播放器的界面状态。手里改了（点暂停、拖进度）先改自己，下一次问网页时再对齐。 */
internal class PlayerState {
    var state by mutableStateOf<VidState?>(null)
    private var stamp by mutableLongStateOf(0L)
    var shown by mutableStateOf(true)
    var poke by mutableIntStateOf(0)
    var menu by mutableStateOf(false)
    var dragging by mutableStateOf(false)
    var preview by mutableStateOf<Double?>(null)
    var seekFrom = 0.0
    var hold by mutableStateOf(false)
    var flash by mutableStateOf<Boolean?>(null)
    var flashKey by mutableIntStateOf(0)
    var ripSide = 1
    var ripKey by mutableIntStateOf(0)
    val playing get() = state?.p == false

    fun sync(s: VidState) { state = s; stamp = SystemClock.uptimeMillis() }
    fun setPaused(paused: Boolean) { state?.let { sync(it.copy(t = nowPlain(), p = paused)) } }
    fun setRate(r: Double) { state?.let { sync(it.copy(t = nowPlain(), r = r)) } }
    fun setTime(t: Double) { state?.let { sync(it.copy(t = t)) } }
    fun ripple(side: Int) { ripSide = side; ripKey++ }
    fun flashIcon(paused: Boolean) { flash = paused; flashKey++ }

    fun nowPlain(): Double {
        val s = state ?: return 0.0
        if (s.p) return s.t
        val t = s.t + (SystemClock.uptimeMillis() - stamp) / 1000.0 * (if (hold) 2.0 else s.r)
        return if (s.d > 0) t.coerceAtMost(s.d) else t
    }

    /** 每帧往前走一点，进度条不一顿一顿。 */
    @Composable
    fun now(): Double {
        val frame by produceState(0L, playing) { while (playing) withFrameMillis { value = it } }
        frame.hashCode()
        return preview ?: nowPlain()
    }
}

private class Taps { var at = 0L; var pos = Offset.Zero; var job: Job? = null }

/** 画面上的手势：点一下显隐控件、点两下左右跳 10 秒 / 中间暂停、按住 2 倍速、左右滑跳进度（一个画面宽 = 90 秒）。 */
@Composable
internal fun VideoGesture(p: PlayerState, web: WebView?, haptic: HapticFeedback, scope: CoroutineScope) {
    val taps = remember { Taps() }
    Box(
        Modifier.fillMaxSize().pointerInput(web) {
            awaitEachGesture {
                val down = awaitFirstDown(); down.consume()
                val start = down.position; val w = size.width.toFloat()
                val dur = p.state?.d ?: 0.0; val t0 = p.nowPlain()
                var seek = false; var up: PointerInputChange? = null
                val slop = viewConfiguration.touchSlop
                val quick = withTimeoutOrNull(380L) {
                    while (true) {
                        val c = awaitPointerEvent().changes.first()
                        if (!c.pressed) { up = c; break }
                        val d = c.position - start
                        if (dur > 0 && abs(d.x) > slop && abs(d.x) > abs(d.y)) { seek = true; break }
                        c.consume()
                    }
                    true
                }
                if (quick == null) {
                    // 按住：2 倍速，松手恢复
                    p.hold = true; p.menu = false
                    VideoCtl.run(web, "window.__fgr=v.playbackRate;v.playbackRate=2;if(v.paused)v.play()")
                    p.setPaused(false)
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    while (true) { val c = awaitPointerEvent().changes.first(); c.consume(); if (!c.pressed) break }
                    VideoCtl.run(web, "v.playbackRate=window.__fgr||1")
                    p.hold = false
                    return@awaitEachGesture
                }
                if (seek) {
                    p.seekFrom = t0
                    while (true) {
                        val c = awaitPointerEvent().changes.first(); c.consume()
                        if (!c.pressed) break
                        p.preview = (t0 + (c.position.x - start.x) / w * 90).coerceIn(0.0, dur)
                    }
                    p.preview?.let { VideoCtl.run(web, "v.currentTime=$it"); p.setTime(it) }
                    p.preview = null; p.shown = true; p.poke++
                    return@awaitEachGesture
                }
                val u = up ?: return@awaitEachGesture
                u.consume()
                if (u.uptimeMillis - taps.at < 280 && (u.position - taps.pos).getDistance() < 60.dp.toPx()) {
                    taps.job?.cancel(); taps.at = 0
                    val rx = u.position.x / w
                    when {
                        rx < 1 / 3f && dur > 0 -> { VideoCtl.run(web, "v.currentTime=Math.max(0,v.currentTime-10)"); p.setTime((p.nowPlain() - 10).coerceAtLeast(0.0)); p.ripple(-1) }
                        rx > 2 / 3f && dur > 0 -> { VideoCtl.run(web, "v.currentTime=Math.min(v.duration,v.currentTime+10)"); p.setTime((p.nowPlain() + 10).coerceAtMost(dur)); p.ripple(1) }
                        else -> { val paused = p.playing; VideoCtl.run(web, VideoCtl.TOGGLE); p.setPaused(paused); p.flashIcon(paused) }
                    }
                } else {
                    taps.at = u.uptimeMillis; taps.pos = u.position
                    taps.job = scope.launch { delay(280); if (p.menu) p.menu = false else p.shown = !p.shown; p.poke++ }
                }
            }
        },
    )
}

/** 拖进度条：按下就跳到手指那里，跟着走，松手落定。 */
internal fun Modifier.seekDrag(p: PlayerState, web: WebView?, dur: Double) = pointerInput(dur) {
    awaitEachGesture {
        val d = awaitFirstDown(); d.consume()
        if (dur <= 0) return@awaitEachGesture
        fun at(x: Float) = (x / size.width).coerceIn(0f, 1f) * dur
        p.dragging = true; p.menu = false; p.seekFrom = p.nowPlain(); p.preview = at(d.position.x)
        while (true) { val c = awaitPointerEvent().changes.first(); c.consume(); if (!c.pressed) break; p.preview = at(c.position.x) }
        p.preview?.let { VideoCtl.run(web, "v.currentTime=$it"); p.setTime(it) }
        p.preview = null; p.dragging = false; p.poke++
    }
}

/** 手势的回应：两边漾开的半圆（−10 / +10 秒）、中间的提示（跳到哪 / 暂停继续）、顶上的 2 倍速。 */
@Composable
internal fun GestureMarks(p: PlayerState) {
    Box(Modifier.fillMaxSize()) {
        val ra = remember { Animatable(0f) }
        LaunchedEffect(p.ripKey) { if (p.ripKey > 0) { ra.snapTo(0f); ra.animateTo(1f, tween(520, easing = Curves.decel)) } }
        val v = ra.value
        val alpha = when { v <= 0f || v >= 1f -> 0f; v < .15f -> v / .15f; v < .7f -> 1f; else -> (1f - v) / .3f }
        if (alpha > 0f) Box(Modifier.fillMaxHeight().fillMaxWidth(.42f).align(if (p.ripSide < 0) Alignment.CenterStart else Alignment.CenterEnd).graphicsLayer { this.alpha = alpha; clip = true }) {
            Canvas(Modifier.fillMaxSize()) {
                val rr = size.height * 1.3f
                val cx = if (p.ripSide < 0) size.width * .8f - rr else size.width * .2f + rr
                drawCircle(Page.ink.copy(alpha = .10f), rr, Offset(cx, size.height / 2))
            }
            Txt(if (p.ripSide < 0) "−10 秒" else "+10 秒", Page.ink, 13.sp, Modifier.align(Alignment.Center), maxLines = 1)
        }

        LaunchedEffect(p.flashKey) { if (p.flashKey > 0) { delay(500); p.flash = null } }
        val pv = p.preview; val fl = p.flash
        var lastPv by remember { mutableStateOf(0.0) }; var lastFl by remember { mutableStateOf(false) }; var seekKind by remember { mutableStateOf(true) }
        if (pv != null) { lastPv = pv; seekKind = true }
        if (fl != null) { lastFl = fl; seekKind = false }
        val ha by animateFloatAsState(if (pv != null || fl != null) 1f else 0f, tween(if (pv != null || fl != null) 200 else 160, easing = Curves.decel), label = "hint")
        if (ha > 0f) Column(
            Modifier.align(Alignment.Center).graphicsLayer { this.alpha = ha; val s = .9f + .1f * ha; scaleX = s; scaleY = s }
                .background(Color(0xA61A1D23), RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (seekKind) {
                Txt(fmtTime(lastPv), FpWarm, 22.sp, family = Serif, maxLines = 1)
                val dd = (lastPv - p.seekFrom).roundToInt()
                Txt((if (dd >= 0) "+" else "−") + abs(dd) + " 秒 · 共 " + fmtTime(p.state?.d ?: 0.0), Page.ink, 12.5.sp, maxLines = 1)
            } else PlayGlyph(lastFl, Modifier.size(28.dp))
        }

        val sa by animateFloatAsState(if (p.hold) 1f else 0f, tween(if (p.hold) 220 else 160, easing = Curves.decel), label = "speed")
        if (sa > 0f) Box(
            Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.displayCutout).padding(top = 14.dp)
                .graphicsLayer { this.alpha = sa; translationY = (1f - sa) * -6.dp.toPx() }
                .background(Color(0xA61A1D23), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
        ) { Txt("▶▶ 2 倍速", FpWarm, 12.5.sp, maxLines = 1) }
    }
}
