package com.fuguang.lueying

import android.content.Context
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.TextureView
import android.webkit.WebSettings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * 下载页里边下边看（方案三「卡片里展开」，原型 https://claude.ai/artifact/QoSXVx7KPdcErANsQRxpwr）。
 * 点一行左边的缩略图（下好的点「播放」）：这一行往下展开成 16:9 的播放器（360ms decel），再点或点 × 收回（260ms accel）。
 * 还没下好的直接从网上读，和下载同时走（拖到没下到的地方也能看，多用一份流量）；下好的放本地文件。
 * 右下角 ↗ 进全屏：横视频横过来，浮光控件和手势跟浏览器全屏一样，返回先退回卡片。同一时间只放一个。
 */
@androidx.annotation.OptIn(UnstableApi::class)
object DlPlay {
    var id by mutableStateOf<String?>(null); private set
    var full by mutableStateOf(false)
    var loading by mutableStateOf(false); private set
    var buffering by mutableStateOf(false); private set
    internal var fail by mutableStateOf<Fail?>(null); private set
    var fixing by mutableStateOf(false); private set
    var player by mutableStateOf<ExoPlayer?>(null); private set
    private var job: Job? = null
    private val scope = MainScope()
    private var app: Context? = null

    fun toggle(ctx: Context, d: Dl) { if (id == d.id) close() else open(ctx, d) }

    fun open(ctx: Context, d: Dl) {
        close()
        id = d.id; loading = true; app = ctx.applicationContext
        val p = PlayerEngine.build(ctx)
        p.addListener(object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) { fail = DlFail.of(ctx.applicationContext, d, e, p.videoFormat); loading = false }
            override fun onPlaybackStateChanged(s: Int) {
                buffering = s == Player.STATE_BUFFERING
                if (s == Player.STATE_READY || s == Player.STATE_ENDED) loading = false
            }
        })
        player = p
        job = scope.launch {
            val src = runCatching { source(ctx.applicationContext, d) }.getOrNull()
            if (player !== p) return@launch
            if (src == null) { fail = Fail("net", "这一个现在读不了，下好就能看", "没拿到播放地址"); loading = false; return@launch }
            // 上次看到哪，从那里接着放
            p.setMediaSource(src, PlayerEngine.lastPos(ctx, d.id)); p.prepare(); p.playWhenReady = true
        }
    }

    fun close() {
        job?.cancel(); job = null
        val p = player; val a = app; val i = id
        if (p != null && a != null && i != null && p.duration != C.TIME_UNSET) PlayerEngine.keepPos(a, i, p.currentPosition, p.duration)
        p?.release(); player = null
        id = null; full = false; loading = false; buffering = false; fail = null; fixing = false
    }

    /** 修一下：重新装一遍盒子，好了接着放；没修好就写原因，留着「用别的播放器」。 */
    fun repair(ctx: Context, d: Dl) {
        if (fixing) return
        fixing = true
        player?.let { player = null; it.release() }   // 先放开文件，再改它
        scope.launch {
            val r = runCatching { Remux.repair(ctx.applicationContext, d) }
            fixing = false
            if (DlPlay.id != d.id) return@launch
            r.onSuccess { n -> d.total = n; d.done = n; open(ctx, d) }
                .onFailure { e -> fail = Fail("codec", "没修好：" + (e.message ?: "重新合成也没成"), fail?.why ?: "") }
        }
    }

    private suspend fun source(ctx: Context, d: Dl): MediaSource? {
        if (d.state == 3) {
            val u = if (d.out.startsWith("content://")) Uri.parse(d.out) else Uri.fromFile(File(d.out))
            return ProgressiveMediaSource.Factory(DefaultDataSource.Factory(ctx)).createMediaSource(MediaItem.fromUri(u))
        }
        val m = d.media
        val ref = m.page.ifEmpty { m.url }
        val ua = m.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: runCatching { WebSettings.getDefaultUserAgent(ctx) }.getOrDefault("Mozilla/5.0")
        val h = m.headers.filterKeys { !it.equals("User-Agent", true) && !it.equals("Range", true) }.toMutableMap()
        if (h.keys.none { it.equals("Referer", true) }) h["Referer"] = ref
        android.webkit.CookieManager.getInstance().getCookie(ref)?.takeIf { it.isNotBlank() && h.keys.none { k -> k.equals("Cookie", true) } }?.let { h["Cookie"] = it }
        val http = DefaultHttpDataSource.Factory().setUserAgent(ua).setDefaultRequestProperties(h).setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(15_000)
        val up = PlayerEngine.cached(ctx, http)
        fun prog(u: String) = ProgressiveMediaSource.Factory(up).createMediaSource(MediaItem.fromUri(u))
        return when (m.kind) {
            "hls" -> HlsMediaSource.Factory(up).createMediaSource(MediaItem.fromUri(d.variant ?: m.url))
            "ytdlp" -> {
                val height = d.variant?.toIntOrNull() ?: Regex("(\\d{3,4})").find(d.quality)?.value?.toIntOrNull() ?: 0
                val us = YtDlp.streams(m.url, m.headers, height, d.audioOnly)
                when (us.size) { 0 -> null; 1 -> prog(us[0]); else -> MergingMediaSource(prog(us[0]), prog(us[1])) }
            }
            else -> if (m.audio != null) MergingMediaSource(prog(m.url), prog(m.audio)) else prog(m.url)
        }
    }

    /** 手势和按钮落到播放器上。按住 2 倍速，松手回到原来的速度。 */
    internal class Ctl(private val p: ExoPlayer) : VidCtl {
        private var keep = 1f
        override fun toggle() { p.playWhenReady = !p.playWhenReady }
        private var scrubbing = false
        override fun seek(t: Double) {
            if (scrubbing) { scrubbing = false; p.setScrubbingModeEnabled(false) }
            p.setSeekParameters(SeekParameters.EXACT); p.seekTo((t * 1000).toLong())
        }
        // 拖动中途：Media3 的拖动模式（连续跳转时丢掉没用的解码、不重复缓冲）+ 跳最近的关键帧，画面跟得上手指
        override fun scrub(t: Double) {
            if (!scrubbing) { scrubbing = true; p.setScrubbingModeEnabled(true) }
            p.setSeekParameters(SeekParameters.CLOSEST_SYNC); p.seekTo((t * 1000).toLong())
        }
        override fun seekBy(d: Double) = p.seekTo((p.currentPosition + (d * 1000).toLong()).coerceAtLeast(0))
        override fun rate(r: Double) = p.setPlaybackSpeed(r.toFloat())
        override fun hold(on: Boolean) { if (on) { keep = p.playbackParameters.speed; p.setPlaybackSpeed(2f); p.playWhenReady = true } else p.setPlaybackSpeed(keep) }
    }

    internal fun state(p: ExoPlayer) = VidState(
        p.currentPosition / 1000.0, if (p.duration == C.TIME_UNSET) 0.0 else p.duration / 1000.0, !p.playWhenReady,
        p.playbackParameters.speed.toDouble(), p.bufferedPosition / 1000.0, p.videoSize.width, p.videoSize.height,
    )
}

/** 卡片里的那一块：展开、收回；全屏时这里留着黑底占位，画面挪到全屏那层。 */
@Composable
internal fun DlInline(d: Dl) {
    AnimatedVisibility(
        DlPlay.id == d.id,
        enter = expandVertically(tween(360, easing = Curves.decel)) + fadeIn(tween(240)),
        exit = shrinkVertically(tween(260, easing = Curves.accel)) + fadeOut(tween(160)),
    ) {
        Box(Modifier.padding(top = 10.dp).fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Color.Black)) {
            val pl = DlPlay.player
            if (pl != null && !DlPlay.full && DlPlay.fail == null) PlayerSurface(d, pl, mini = true)
            PlayerNote(d)
            // 放不了时控件不在了，留一个收起
            if (DlPlay.fail != null && !DlPlay.fixing) Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) { Chip("收起", { DlPlay.close() }) { SvgIcon(Ic("M6 6l12 12M18 6L6 18"), Page.ink, 13.dp, stroke = 1.8f) } }
        }
    }
}

/** 全屏：横视频横过来、收起系统栏；返回或 ↙ 回到卡片。 */
@Composable
internal fun DlFull(d: Dl?) {
    val pl = DlPlay.player
    if (!DlPlay.full || pl == null || d == null) return
    BackHandler { DlPlay.full = false }
    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        val a = ctx.activity()
        a?.let { WindowInsetsControllerCompat(it.window, it.window.decorView).apply { hide(WindowInsetsCompat.Type.systemBars()); systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE } }
        if (pl.videoSize.width >= pl.videoSize.height) a?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            a?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            a?.let { WindowInsetsControllerCompat(it.window, it.window.decorView).show(WindowInsetsCompat.Type.systemBars()) }
        }
    }
    val k by animateFloatAsState(1f, tween(260, easing = Curves.decel), label = "dlfull")
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = k }.background(Color.Black)) { PlayerSurface(d, pl, mini = false); PlayerNote(d) }
}

@Composable
private fun PlayerNote(d: Dl) {
    DlPlay.fail?.let { f -> FailNote(d, f); return }
    // 在读：等 300ms 还没好才露出一个小转圈（一闪而过的不打扰），不再写字挡在画面中间
    val wait = DlPlay.loading || DlPlay.buffering
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(wait) { if (wait) { delay(300); show = true } else show = false }
    val a by animateFloatAsState(if (show && wait) 1f else 0f, tween(180), label = "wait")
    if (a > 0f) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(Modifier.size(44.dp).graphicsLayer { alpha = a }.background(Color(0x8C1A1D23), CircleShape), contentAlignment = Alignment.Center) { LoadRing(FpWarm, 22.dp) }
    }
}

/** 画面 + 手势 + 控件。mini 是卡片里的小控件，否则是全屏那套。 */
@Composable
private fun PlayerSurface(d: Dl, pl: ExoPlayer, mini: Boolean) {
    val p = remember(pl) { PlayerState() }
    val ctl = remember(pl) { DlPlay.Ctl(pl) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(pl) { while (true) { p.sync(DlPlay.state(pl)); delay(250) } }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner, pl) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) pl.pause() }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }
    LaunchedEffect(p.shown, p.poke, p.playing, p.menu) { if (p.shown && p.playing && !p.menu) { delay(2600); p.shown = false } }
    Box(Modifier.fillMaxSize()) {
        val s = p.state
        val ratio = if (s != null && s.vw > 0 && s.vh > 0) s.vw.toFloat() / s.vh else 16f / 9f
        Box(Modifier.align(Alignment.Center).aspectRatio(ratio, matchHeightConstraintsFirst = !mini || ratio < 16f / 9f)) {
            if (mini) AndroidView(factory = { c -> TextureView(c).also { it.keepScreenOn = true; pl.setVideoTextureView(it) } }, onRelease = { pl.clearVideoTextureView(it) }, modifier = Modifier.fillMaxSize())
            else AndroidView(factory = { c -> android.view.SurfaceView(c).also { it.keepScreenOn = true; pl.setVideoSurfaceView(it) } }, onRelease = { pl.clearVideoSurfaceView(it) }, modifier = Modifier.fillMaxSize())
        }
        VideoGesture(p, ctl, haptic, scope)
        val ca by animateFloatAsState(if (p.shown) 1f else 0f, tween(220, easing = Curves.standard), label = "dlctl")
        val now = p.now(); val dur = s?.d ?: 0.0
        if (ca < .99f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(if (dur > 0) (now / dur).toFloat().coerceIn(0f, 1f) else 0f).height(2.dp).graphicsLayer { alpha = (1f - ca) * .85f }.background(FpWarm))
        if (ca > .01f) {
            if (mini) MiniControls(d, p, ctl, now, dur, Modifier.graphicsLayer { alpha = ca })
            else Controls(p, ctl, d.title, now, dur, Modifier.graphicsLayer { alpha = ca }, onExit = { DlPlay.full = false }, onDownload = null, extra = dlText(d))
        }
        GestureMarks(p)
    }
}

@Composable
private fun MiniControls(d: Dl, p: PlayerState, ctl: VidCtl, now: Double, dur: Double, modifier: Modifier) {
    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x730A0B0E), Color.Transparent, Color.Transparent, Color(0x9E0A0B0E)))))
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(d.title, Page.ink, 13.sp, Modifier.weight(1f).padding(start = 2.dp, end = 8.dp), family = Serif, maxLines = 1)
            dlText(d)?.let { Txt(it, FpWarm, 11.5.sp, Modifier.padding(end = 6.dp), maxLines = 1) }
            Chip("收起", { DlPlay.close() }) { SvgIcon(Ic("M6 6l12 12M18 6L6 18"), Page.ink, 13.dp, stroke = 1.8f) }
        }
        PressSurface({ ctl.toggle(); p.setPaused(!(p.state?.p ?: true)); p.poke++ }, Modifier.align(Alignment.Center).size(48.dp), shape = CircleShape, fill = FpGlass, pressScale = .92f, label = if (p.playing) "暂停" else "播放") {
            PlayGlyph(p.playing, Modifier.align(Alignment.Center).size(20.dp))
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
            ProgressBar(p, ctl, now, dur)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(if (dur > 0) fmtTime(now) + " / " + fmtTime(dur) else "正在读取", Page.ink, 11.5.sp, maxLines = 1)
                Spacer(Modifier.weight(1f))
                Chip("全屏", { DlPlay.full = true; p.poke++ }) { SvgIcon(Ic("M14 5h5v5M10 19H5v-5M19 5l-6 6M5 19l6-6"), Page.ink, 14.dp, stroke = 1.8f) }
            }
            Spacer(Modifier.width(1.dp).height(2.dp))
        }
    }
}

private fun dlText(d: Dl): String? = when (d.state) {
    3 -> null
    5 -> "已暂停 ${(d.frac * 100).toInt()}%"
    4 -> "没下成"
    2 -> "正在合成"
    else -> "还在下 ${(d.frac * 100).toInt()}%"
}

