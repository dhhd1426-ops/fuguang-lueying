package com.fuguang.lueying

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** 内置浏览器里点到的磁力链接 / 种子文件。key：magnet: 链接或 .torrent 地址。 */
object Magnet {
    class Ask(val key: String, val page: String) { val file get() = !Torrent.isMagnet(key) }
    var ask by mutableStateOf<Ask?>(null)
}

private val Warm: Color get() = Skin.accent
private val Sheet: Color get() = Skin.sheet.copy(alpha = 1f)
private val Rose: Color get() = Skin.danger
private val magnetIc = Ic("M6 4h4v8a2 2 0 0 0 4 0V4h4v8a6 6 0 0 1-12 0Z M6 8h4 M14 8h4")
private val playIc = Ic("M8 5.5v13l10.5-6.5Z")
private val down = Ic("M12 4v11M7.5 10.5L12 15l4.5-4.5M5 19h14")

/**
 * 磁力下载面板：从下面升起（340ms decel，收 220ms accel）。先「在找种子信息 · N 秒」，拿到后列出里面的文件（默认只勾视频），
 * 选好点「下载 · 7.33 GB」交给下载页；有视频时还能「边下边看」：开始下，同时打开下载页里那一行的播放器，读到哪先下哪。
 */
@Composable
fun MagnetSheet(online: Int = 0, onOnline: () -> Unit = {}, onToast: (String) -> Unit) {
    val ask = Magnet.ask
    var last by remember { mutableStateOf<Magnet.Ask?>(null) }
    if (ask != null) last = ask
    val open = ask != null
    if (open) BackHandler { Magnet.ask = null }
    val k by animateFloatAsState(if (open) 1f else 0f, tween(if (open) 340 else 220, easing = if (open) Curves.decel else Curves.accel), label = "magnet")
    val a = last ?: return
    if (k <= 0f) return
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var meta by remember(a) { mutableStateOf<Torrent.Meta?>(null) }
    var failed by remember(a) { mutableStateOf(false) }
    var tries by remember(a) { mutableIntStateOf(0) }
    var secs by remember(a) { mutableIntStateOf(0) }
    var sel by remember(a) { mutableStateOf(setOf<Int>()) }
    var gallery by remember { mutableStateOf(true) }
    var wifi by remember { mutableStateOf(false) }
    val already = remember(a) { Downloads.list.firstOrNull { it.media.kind == "magnet" && it.media.url == a.key } }
    LaunchedEffect(a, tries) {
        if (already != null) return@LaunchedEffect
        failed = false; secs = 0
        val job = launch { while (true) { delay(1000); secs++ } }
        val m = if (a.file) Torrent.fetchFile(a.key, a.page) else Torrent.fetch(a.key, File(ctx.cacheDir, "magnet"))
        job.cancel()
        if (m == null) failed = true else { meta = m; sel = Torrent.defaults(m) }
    }
    val m = meta
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = k }.background(Skin.scrim(.5f)).pointerInput(Unit) { detectTapGestures { Magnet.ask = null } })
        Column(
            Modifier.align(Alignment.BottomCenter).widthIn(max = 640.dp).fillMaxWidth()
                .graphicsLayer { translationY = (1f - k) * size.height }
                .background(Sheet, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .pointerInput(Unit) { detectTapGestures { } }
                .windowInsetsPadding(WindowInsets.navigationBars).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp).size(40.dp, 4.dp).background(veil(.25f), RoundedCornerShape(2.dp)))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).background(Warm.copy(alpha = .12f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) { SvgIcon(magnetIc, Warm, 17.dp, stroke = 1.7f) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Txt(m?.name ?: Torrent.nameOf(a.key).ifBlank { if (a.file) "种子文件" else "磁力链接" }, Page.ink, 15.sp, family = Serif, maxLines = 2)
                    val sub = when {
                        already != null -> "已经在下载里了"
                        m != null -> "${m.items.size} 个文件 · 共 ${mb(m.items.sumOf { it.size })}"
                        failed -> "没找到"
                        else -> "磁力"
                    }
                    Txt(sub, Page.muted, 12.sp, Modifier.padding(top = 2.dp), maxLines = 1)
                }
            }
            Spacer(Modifier.size(12.dp))
            val phase = when { already != null -> 3; m != null -> 2; failed -> 1; else -> 0 }
            AnimatedContent(phase, transitionSpec = { fadeIn(tween(240, 80)) togetherWith fadeOut(tween(140)) }, label = "phase") { ph ->
                when (ph) {
                    0 -> Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LoadRing(Warm, 15.dp); Spacer(Modifier.width(9.dp))
                            Txt("在找种子信息 · $secs 秒", Page.ink, 13.5.sp)
                        }
                        val nodes = remember(secs) { Torrent.nodes() }
                        Txt((if (nodes > 0) "已连上 $nodes 个节点。" else "正在连 DHT 网络。") + "冷门的种子可能要等一两分钟。", Page.muted, 12.sp, Modifier.padding(top = 6.dp, start = 24.dp), lineHeight = 19.sp)
                    }
                    1 -> Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Txt("等了一分半钟，没找到分享这个种子的人。可能太冷门，或者这个网络连不上 DHT。", Rose, 13.sp, lineHeight = 20.sp)
                        Spacer(Modifier.size(12.dp))
                        CardButton(0, BtnTone.Plain, "再找一次", { tries++ }, Modifier.fillMaxWidth()) { _, ink -> BtnLabel("再找一次", ink) }
                    }
                    3 -> CardButton(0, BtnTone.Primary, "去下载页看", { Magnet.ask = null; Downloads.open = true }, Modifier.fillMaxWidth().padding(vertical = 6.dp)) { _, ink -> BtnLabel("去下载页看", ink) }
                    else -> if (m != null) Files(m, sel) { i -> sel = if (i in sel) sel - i else sel + i }
                }
            }
            // 网页上本来就有在线视频：直接下在线那份，一般比磁力快得多（不靠有没有人做种）
            if (online > 0 && already == null) PressSurface({ Magnet.ask = null; onOnline() }, Modifier.fillMaxWidth().padding(top = 10.dp), shape = RoundedCornerShape(14.dp),
                fill = veil(.05f), pressScale = .98f, label = "下在线视频") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    SvgIcon(playIc, Warm, 14.dp, stroke = 2f); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Txt("这页能在线放（认出 $online 个视频）", Page.ink, 13.5.sp, maxLines = 1)
                        Txt("下在线那份通常快得多，不用等人做种", Page.muted, 11.5.sp, Modifier.padding(top = 2.dp), maxLines = 1)
                    }
                    Txt("换这个", Warm, 12.5.sp, maxLines = 1)
                }
            }
            if (m == null || already != null) return@Column
            Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChoiceChip("存到相册", gallery) { gallery = !gallery }
                ChoiceChip("只在 Wi-Fi 下载", wifi) { wifi = !wifi }
            }
            val picked = m.items.filter { it.i in sel }
            val size = picked.sumOf { it.size }
            fun go(watch: Boolean) {
                if (picked.isEmpty()) return
                val act = ctx.activity()
                val label = if (picked.size == 1) "1 个文件" else "${picked.size} 个文件"
                Downloads.start(act, m.name, Sniffer.Media("magnet", a.key, page = a.page, size = size, name = m.name), label, picked.joinToString(",") { "${it.i}" }, false, gallery, wifi)
                Magnet.ask = null
                if (watch) { val d = Downloads.list.first(); Downloads.open = true; scope.launch { delay(380); DlPlay.open(ctx, d) } }
                else onToast("开始下载，切到别的页面也会接着下")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (picked.any { it.video }) CardButton(0, BtnTone.Plain, "边下边看", { go(true) }, Modifier.weight(1f)) { _, ink -> BtnLabel("边下边看", ink) { SvgIcon(playIc, ink, 14.dp, stroke = 2f) } }
                CardButton(picked.isEmpty(), if (picked.isEmpty()) BtnTone.Quiet else BtnTone.Primary, "下载", { go(false) }, Modifier.weight(1.3f)) { none, ink ->
                    BtnLabel(if (none) "先选一个文件" else "下载 · " + mb(size), ink) { if (!none) SvgIcon(down, ink, 16.dp, stroke = 2f) }
                }
            }
        }
    }
}

/** 种子里的文件：大的在上，一行一个，点整行勾选；每行晚 40ms 浮上来。 */
@Composable
private fun Files(m: Torrent.Meta, sel: Set<Int>, onFlip: (Int) -> Unit) {
    val items = remember(m) { m.items.sortedByDescending { it.size } }
    Column(Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEachIndexed { n, it ->
            val rise = remember { Animatable(0f) }
            LaunchedEffect(Unit) { delay(40L * n.coerceAtMost(8)); rise.animateTo(1f, tween(320, easing = Curves.decel)) }
            val on = it.i in sel
            PressSurface({ onFlip(it.i) }, Modifier.fillMaxWidth().graphicsLayer { alpha = rise.value; translationY = (1f - rise.value) * 8.dp.toPx() },
                shape = RoundedCornerShape(14.dp), fill = if (on) Warm.copy(alpha = .13f) else veil(.05f), pressScale = .98f, label = it.name) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val c by animateFloatAsState(if (on) 1f else 0f, tween(220, easing = Curves.decel), label = "c")
                    Canvas(Modifier.size(18.dp)) {
                        val w = 1.5.dp.toPx(); val r = CornerRadius(5.dp.toPx())
                        if (c < 1f) drawRoundRect(veil(.32f), Offset(w / 2, w / 2), androidx.compose.ui.geometry.Size(size.width - w, size.height - w), r, style = Stroke(w))
                        if (c > 0f) {
                            drawRoundRect(Warm.copy(alpha = c), cornerRadius = r)
                            val s = size.minDimension / 24f; val a = Offset(6f * s, 12.5f * s); val b = Offset(10f * s, 16.5f * s); val e = Offset(18f * s, 8f * s)
                            val ink = Skin.onAccent
                            drawLine(ink, a, a + (b - a) * (c * 2f).coerceAtMost(1f), 2.dp.toPx(), StrokeCap.Round)
                            if (c > .5f) drawLine(ink, b, b + (e - b) * ((c - .5f) * 2f), 2.dp.toPx(), StrokeCap.Round)
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Txt(it.name, if (on) Page.ink else Page.ink.copy(alpha = .78f), 13.5.sp, maxLines = 2)
                        Txt(listOf(mb(it.size).ifBlank { "很小" }, if (it.video) "视频" else Torrent.ext(it.name).uppercase()).filter { s -> s.isNotBlank() }.joinToString(" · "), Page.muted.copy(alpha = .7f), 11.5.sp, Modifier.padding(top = 2.dp), maxLines = 1)
                    }
                }
            }
        }
    }
}
