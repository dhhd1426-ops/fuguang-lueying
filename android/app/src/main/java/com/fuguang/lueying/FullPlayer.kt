package com.fuguang.lueying

import android.content.pm.ActivityInfo
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 网页视频全屏（方案一）：视频从它在网页里的位置长成整屏（黑底从原处铺开 320ms decel，画面随后淡入），
 * 横视频长好再横过来，竖视频不转；退出时先转回竖屏，再缩回原处（260ms accel）。
 * 上面盖浮光控件：退出 · 标题 · 下载，中间播放 / 暂停，下面杏色进度条 · 时间 · 倍速。播放中 2.6 秒没动就收起，只留底部一道细进度。
 * 手势（VideoGesture）：点一下显隐、点两下左右跳 10 秒 / 中间暂停、按住 2 倍速、左右滑跳进度。
 * 视频本身还是网页里的那个，控件都通过 [VideoCtl] 发 JS 去操作它；网站自己的按钮被这一层挡住，不会再冒出来。
 */
@Composable
internal fun FullPlayer(view: View, web: WebView?, title: String, from: Rect?, exiting: Boolean, onExit: () -> Unit, onDownload: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val k = remember { Animatable(0f) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val p = remember { PlayerState() }
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    // 每 250ms 问一次网页里的视频
    LaunchedEffect(web) { while (true) { web?.evaluateJavascript(VideoCtl.STATE) { r -> VideoCtl.parse(r)?.let { p.sync(it) } }; delay(250) } }
    // 长出来；横视频再横过来
    LaunchedEffect(Unit) {
        k.animateTo(1f, tween(320, easing = Curves.decel))
        withTimeoutOrNull(400) { while (p.state == null) delay(40) }
        val s = p.state
        if (s == null || s.vw >= s.vh) ctx.activity()?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
    // 收回去：先转回竖屏，等画面变竖了再缩回原处
    LaunchedEffect(exiting) {
        if (!exiting) return@LaunchedEffect
        p.shown = false
        val a = ctx.activity()
        if (a != null && a.requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            withTimeoutOrNull(700) { while (box.width > box.height) delay(30) }
        }
        k.animateTo(0f, tween(260, easing = Curves.accel))
        onDone()
    }
    // 播放中 2.6 秒没动就收起控件
    LaunchedEffect(p.shown, p.poke, p.playing, p.menu) { if (p.shown && p.playing && !p.menu) { delay(2600); p.shown = false } }

    val t = k.value
    val r = from ?: Rect(0f, box.height * .36f, box.width.toFloat(), box.height * .64f)
    Box(
        Modifier.fillMaxSize().onSizeChanged { box = it }.drawBehind {
            val l = r.left * (1 - t); val tp = r.top * (1 - t)
            drawRect(Color.Black, Offset(l, tp), Size(r.width + (size.width - r.width) * t, r.height + (size.height - r.height) * t))
        },
    ) {
        AndroidView(
            factory = { c -> FrameLayout(c).apply { (view.parent as? ViewGroup)?.removeView(view); addView(view, FrameLayout.LayoutParams(-1, -1)) } },
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = ((t - .55f) / .45f).coerceIn(0f, 1f) },
        )
        if (t < .99f && !exiting) return@Box
        // 手势层在下，控件在上：空白处的触摸落到手势层，按钮自己接
        VideoGesture(p, web, haptic, scope)
        val ca by animateFloatAsState(if (p.shown && !exiting) 1f else 0f, tween(220, easing = Curves.standard), label = "ctl")
        val now = p.now()
        val dur = p.state?.d ?: 0.0
        if (ca < .99f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(if (dur > 0) (now / dur).toFloat().coerceIn(0f, 1f) else 0f).height(2.dp).graphicsLayer { alpha = (1f - ca) * .85f }.background(FpWarm))
        if (ca > .01f) Controls(p, web, title, now, dur, Modifier.graphicsLayer { alpha = ca }, onExit, onDownload)
        GestureMarks(p)
    }
}

@Composable
private fun Controls(p: PlayerState, web: WebView?, title: String, now: Double, dur: Double, modifier: Modifier, onExit: () -> Unit, onDownload: () -> Unit) {
    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(.3f).background(Brush.verticalGradient(listOf(Color(0x6B0A0B0E), Color.Transparent))))
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().fillMaxHeight(.4f).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x990A0B0E)))))
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout).padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Chip("退出全屏", onExit) { SvgIcon(FpIcons.shrink, Page.ink, 16.dp, stroke = 1.8f) }
                Txt(title.ifBlank { "视频" }, Page.ink, 16.sp, Modifier.weight(1f).padding(horizontal = 12.dp), family = Serif, maxLines = 1)
                Chip("下载", onDownload) { SvgIcon(FpIcons.down, Page.ink, 15.dp, stroke = 1.8f); Spacer(Modifier.width(5.dp)); Txt("下载", Page.ink, 12.5.sp, maxLines = 1) }
            }
            Spacer(Modifier.weight(1f))
            ProgressBar(p, web, now, dur)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt(if (dur > 0) fmtTime(now) + " / " + fmtTime(dur) else "直播", Page.ink, 12.5.sp, maxLines = 1)
                Spacer(Modifier.weight(1f))
                Chip("倍速", { p.menu = !p.menu; p.poke++ }) { Txt(rateText(p.state?.r ?: 1.0), if (p.menu) FpWarm else Page.ink, 12.5.sp, maxLines = 1) }
            }
        }
        PressSurface({ VideoCtl.run(web, VideoCtl.TOGGLE); p.setPaused(!(p.state?.p ?: true)); p.poke++ }, Modifier.align(Alignment.Center).size(58.dp), shape = CircleShape, fill = FpGlass, pressScale = .92f, label = if (p.playing) "暂停" else "播放") {
            PlayGlyph(p.playing, Modifier.align(Alignment.Center).size(24.dp))
        }
        if (p.menu) RateMenu(p, web, Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.displayCutout).padding(end = 14.dp, bottom = 52.dp))
    }
}

@Composable
private fun RateMenu(p: PlayerState, web: WebView?, modifier: Modifier) {
    val cur = p.state?.r ?: 1.0
    Column(modifier.background(Color(0xFF262A32), RoundedCornerShape(14.dp)).padding(5.dp)) {
        listOf(2.0, 1.5, 1.25, 1.0, .75).forEach { v ->
            val on = kotlin.math.abs(v - cur) < .01
            PressSurface({ VideoCtl.run(web, "v.playbackRate=$v"); p.setRate(v); p.menu = false; p.poke++ }, Modifier.width(76.dp), shape = RoundedCornerShape(9.dp), fill = if (on) FpWarm.copy(alpha = .12f) else Color.Transparent, pressScale = .96f, label = rateText(v)) {
                Txt(rateText(v), if (on) FpWarm else Page.ink, 13.sp, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), maxLines = 1)
            }
        }
    }
}

@Composable
private fun Chip(label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    PressSurface(onClick, Modifier.height(34.dp), shape = RoundedCornerShape(17.dp), fill = FpGlass, pressedFill = FpWarm.copy(alpha = .22f), pressScale = .94f, label = label) {
        Row(Modifier.align(Alignment.Center).padding(horizontal = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) { content() }
    }
}

/** 播放三角 / 暂停两竖，实心。 */
@Composable
internal fun PlayGlyph(playing: Boolean, modifier: Modifier, color: Color = Page.ink) {
    Canvas(modifier) {
        val s = size.minDimension / 24f
        if (playing) {
            drawRect(color, Offset(7f * s, 5f * s), Size(3.6f * s, 14f * s)); drawRect(color, Offset(13.4f * s, 5f * s), Size(3.6f * s, 14f * s))
        } else drawPath(Path().apply { moveTo(8f * s, 5.5f * s); lineTo(19f * s, 12f * s); lineTo(8f * s, 18.5f * s); close() }, color)
    }
}

@Composable
private fun ProgressBar(p: PlayerState, web: WebView?, now: Double, dur: Double) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(26.dp).seekDrag(p, web, dur)) {
        val f = if (dur > 0) (now / dur).toFloat().coerceIn(0f, 1f) else 0f
        val b = if (dur > 0) ((p.state?.b ?: 0.0) / dur).toFloat().coerceIn(0f, 1f) else 0f
        val th by animateFloatAsState(if (p.dragging) 5f else 3f, tween(160), label = "th")
        val knob by animateFloatAsState(if (p.dragging) 1.25f else 1f, tween(160), label = "kn")
        Canvas(Modifier.fillMaxSize()) {
            val y = size.height / 2; val h = th.dp.toPx(); val w = size.width
            drawRoundRect(Page.ink.copy(alpha = .22f), Offset(0f, y - h / 2), Size(w, h), androidx.compose.ui.geometry.CornerRadius(h / 2))
            drawRoundRect(Page.ink.copy(alpha = .22f), Offset(0f, y - h / 2), Size(w * b, h), androidx.compose.ui.geometry.CornerRadius(h / 2))
            drawRoundRect(FpWarm, Offset(0f, y - h / 2), Size(w * f, h), androidx.compose.ui.geometry.CornerRadius(h / 2))
            if (dur > 0) {
                drawCircle(FpWarm.copy(alpha = .18f), 10.dp.toPx() * knob, Offset(w * f, y))
                drawCircle(FpWarm, 6.dp.toPx() * knob, Offset(w * f, y))
            }
        }
    }
}

internal val FpWarm = Color(0xFFE9B48E)
internal val FpGlass = Color(0x991A1D23)

internal object FpIcons {
    val shrink = Ic("M19 10h-5V5M5 14h5v5M14 10l6-6M10 14l-6 6")
    val down = Ic("M12 4v11M7.5 10.5L12 15l4.5-4.5M5 19h14")
}

internal fun fmtTime(t: Double): String {
    val s = t.coerceAtLeast(0.0).toLong(); val h = s / 3600; val m = s % 3600 / 60; val ss = s % 60
    return (if (h > 0) "$h:" + "%02d".format(m) else "$m") + ":" + "%02d".format(ss)
}

internal fun rateText(r: Double) = if (r == 1.0) "1.0×" else (if (r % 1.0 == 0.0) "${r.toInt()}" else "$r".trimEnd('0')) + "×"
