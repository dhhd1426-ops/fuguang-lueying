package com.fuguang.lueying

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import kotlinx.coroutines.withContext

private val Warm: Color get() = Skin.accent
private val Ink2 = Color(0xFF2A211B)
private val Sheet = Color(0xFF1F232A)
private val down = Ic("M12 4v11M7.5 10.5L12 15l4.5-4.5M5 19h14")
private val playIc = Ic("M8 5.5v13l10.5-6.5Z")
private val pauseIc = Ic("M9 6v12M15 6v12")
private val back = Ic("M14.5 6l-6 6 6 6")
private val trash = Ic("M5 7h14M10 7V4.5h4V7M7 7l1 13h8l1-13")
private val retry = Ic("M19 8a7.5 7.5 0 1 0 1 5M19 4v4h-4")

internal fun mb(b: Long) = if (b <= 0) "" else if (b >= 1 shl 30) "%.2f GB".format(b / 1073741824.0) else "%.1f MB".format(b / 1048576.0)

/** 下载视频：从下面升起，选哪一个（有几个时）、清晰度、存到相册 / 只在 Wi-Fi。 */
@Composable
fun DownloadSheet(open: Boolean, title: String, pageUrl: String, onDone: (String) -> Unit) {
    val k by animateFloatAsState(if (open) 1f else 0f, tween(if (open) 340 else 220, easing = if (open) Curves.decel else Curves.accel), label = "dlsheet")
    if (k <= 0f) return
    val ctx = LocalContext.current
    // 先问 yt-dlp 认不认得这一页（认得就用它：清晰度全、带大小）；同时列出自己嗅探到的
    var yt by remember(pageUrl) { mutableStateOf<YtDlp.Info?>(null) }
    var ytAsked by remember(pageUrl) { mutableStateOf(false) }
    var ytLoading by remember(pageUrl) { mutableStateOf(false) }
    LaunchedEffect(open, pageUrl) {
        if (open && !ytAsked && pageUrl.startsWith("http")) { ytAsked = true; ytLoading = true; yt = YtDlp.info(pageUrl, Sniffer.found.firstOrNull()?.headers.orEmpty()); ytLoading = false }
    }
    val ytm = yt?.let { Sniffer.Media("ytdlp", pageUrl, headers = Sniffer.found.firstOrNull()?.headers.orEmpty(), page = pageUrl) }
    val items = listOfNotNull(ytm) + Sniffer.found.toList()
    var pick by remember { mutableIntStateOf(0) }
    val m = items.getOrNull(pick.coerceAtMost((items.size - 1).coerceAtLeast(0)))
    var qs by remember(m) { mutableStateOf<List<Pair<String, String?>>?>(null) }
    val sizes = remember(yt) { yt?.fmts?.associate { it.label to it.size }.orEmpty() + ("只要声音" to (yt?.audioSize ?: 0L)) }
    var q by remember(m) { mutableIntStateOf(0) }
    var audioOnly by remember(m) { mutableStateOf(false) }
    var gallery by remember { mutableStateOf(true) }
    var wifi by remember { mutableStateOf(false) }
    LaunchedEffect(m, yt) {
        if (m == null) return@LaunchedEffect
        qs = if (m.kind == "ytdlp") yt?.fmts?.map { it.label to it.height.toString() }?.ifEmpty { listOf("最清楚" to "0") } else Downloads.qualities(m)
    }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = k }.background(Color(0x80000000)).pointerInput(Unit) { detectTapGestures { onDone("") } })
        Column(
            Modifier.align(Alignment.BottomCenter).widthIn(max = 640.dp).fillMaxWidth()
                .graphicsLayer { translationY = (1f - k) * size.height }
                .background(Sheet, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .pointerInput(Unit) { detectTapGestures { } }
                .windowInsetsPadding(WindowInsets.navigationBars).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp).size(40.dp, 4.dp).background(Color.White.copy(alpha = .25f), RoundedCornerShape(2.dp)))
            Txt(title.ifBlank { "这一页的视频" }, Page.INK, 16.sp, maxLines = 2)
            if (m == null) {
                if (ytLoading) Row(Modifier.padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) { LoadRing(Warm, 15.dp); Spacer(Modifier.width(8.dp)); Txt("在认这一页的视频…", Page.MUTED, 13.sp) }
                else Txt("这一页还没认出能下的视频。先让视频放起来，再点一次。", Page.MUTED, 13.sp, Modifier.padding(vertical = 18.dp), lineHeight = 21.sp)
                return@Column
            }
            Txt(
                when (m.kind) { "ytdlp" -> "认出来了：${yt?.title?.take(30).orEmpty()} · 画面和声音会合成一个 mp4"; "dash" -> "画面和声音分开传的，下完自动合成一个文件"; "hls" -> "分段视频，下完自动拼成一个文件"; else -> if (m.size > 0) "整段文件 · " + mb(m.size) else "整段文件" },
                Page.MUTED, 12.sp, Modifier.padding(top = 2.dp, bottom = 12.dp),
            )
            if (items.size > 1) Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items.take(5).forEachIndexed { i, it2 -> ChoiceChip(if (it2.kind == "ytdlp") "智能识别" else "视频 ${i + 1}" + if (it2.kind == "hls") " · 分段" else "", pick == i) { pick = i } }
            }
            val list = qs
            if (list == null) Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) { LoadRing(Warm, 15.dp); Spacer(Modifier.width(8.dp)); Txt("在看有哪几档清晰度…", Page.MUTED, 13.sp) }
            else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val rows = list.map { it.first } + if (m.kind != "hls") listOf("只要声音") else emptyList()
                rows.forEachIndexed { i, name ->
                    val on = if (name == "只要声音") audioOnly else !audioOnly && q == i
                    PressSurface({ if (name == "只要声音") audioOnly = true else { audioOnly = false; q = i } }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                        fill = if (on) Warm.copy(alpha = .13f) else Color.White.copy(alpha = .05f), pressScale = .98f, label = name) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                            val r by animateFloatAsState(if (on) 1f else 0f, tween(200), label = "r")
                            Canvas(Modifier.size(18.dp)) {
                                val w = 1.5.dp.toPx()
                                drawCircle(if (on) Warm else Color.White.copy(alpha = .32f), size.minDimension / 2 - w / 2, style = Stroke(w))
                                if (r > 0f) drawCircle(Warm, size.minDimension / 2 * .5f * r)
                            }
                            Spacer(Modifier.width(10.dp))
                            Txt(name, Page.INK, 14.sp, Modifier.weight(1f), maxLines = 1)
                            val sz = if (m.kind == "ytdlp") sizes[name] ?: 0L else 0L
                            Txt(listOf(if (sz > 0) "约 " + mb(sz) else "", when { name == "只要声音" -> "m4a"; i == 0 && list.size > 1 -> "最清楚"; else -> "" }).filter { it.isNotEmpty() }.joinToString(" · "), Page.MUTED, 12.sp)
                        }
                    }
                }
            }
            Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChoiceChip("存到相册", gallery) { gallery = !gallery }
                ChoiceChip("只在 Wi-Fi 下载", wifi) { wifi = !wifi }
            }
            val label = if (audioOnly) "只要声音" else list?.getOrNull(q)?.first ?: "原画"
            CardButton(0, BtnTone.Primary, "下载", {
                val act = ctx as? android.app.Activity ?: (ctx as? android.content.ContextWrapper)?.baseContext as? android.app.Activity
                Downloads.start(act, title, m, label, if (audioOnly) null else list?.getOrNull(q)?.second, audioOnly, gallery, wifi)
                onDone("开始下载，切到别的页面也会接着下")
            }, Modifier.fillMaxWidth()) { _, ink -> BtnLabel("下载 $label", ink) { SvgIcon(down, ink, 16.dp, stroke = 2f) } }
        }
    }
}

/** 下载页：从右边滑进来；每个一行，进度圈、速度、暂停继续；好了能播、分享、删除。 */
@Composable
fun DownloadsLayer() {
    val open = Downloads.open
    // 打开时才挂上返回：挂得最晚，排在内置浏览器等别的返回前面（以前浏览器先接走了返回手势）
    if (open) BackHandler { Downloads.open = false }
    // 关掉下载页时，正在放的也停掉
    LaunchedEffect(open) { if (!open) { DlPlay.close(); risen.clear() } else openedAt = System.currentTimeMillis() }
    // 开着下载页、刚关上：下好的都算看过（侧栏那颗杏色小点灭掉）
    LaunchedEffect(open, Downloads.list.count { it.state == 3 }) { if (open) DlSeen.mark() }
    val k by animateFloatAsState(if (open) 1f else 0f, tween(if (open) 340 else 220, easing = if (open) Curves.decel else Curves.accel), label = "dls")
    if (k <= 0f) return
    val ctx = LocalContext.current
    Box(Modifier.fillMaxSize().graphicsLayer { translationX = (1f - k) * size.width }.background(Color(0xFF121418)).pointerInput(Unit) { detectTapGestures { } }) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                PressSurface({ Downloads.open = false }, Modifier.size(44.dp), shape = CircleShape, fill = Color.Transparent, pressScale = .88f, label = "返回") { SvgIcon(back, Page.INK, 20.dp, stroke = 1.8f) }
                Txt("下载", Page.INK, 17.sp, Modifier.weight(1f), family = Serif, align = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.width(44.dp))
            }
            // 两栏：左「正在下载」，右「已完成」（DownloadTabs.kt）
            Box(Modifier.weight(1f).windowInsetsPadding(WindowInsets.navigationBars)) {
                Column {
                    DlTabs { d, i -> DlRow(d, i) { u, act ->
                        when (act) {
                            "play" -> DlPlay.toggle(ctx, d)
                            "share" -> runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(if (d.audioOnly) "audio/*" else "video/*").putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), d.title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }
                    } }
                }
            }
        }
        // 删完的提示：浮上来停 2.4 秒
        LaunchedEffect(Downloads.note) { if (Downloads.note != null) { delay(2400); Downloads.note = null } }
        val na by animateFloatAsState(if (Downloads.note != null) 1f else 0f, tween(240, easing = Curves.decel), label = "note")
        var lastNote by remember { mutableStateOf("") }
        Downloads.note?.let { lastNote = it }
        if (na > 0f) Txt(lastNote, Page.INK, 13.sp, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 28.dp)
            .graphicsLayer { alpha = na; translationY = (1f - na) * 10.dp.toPx() }.background(Color(0xFF3A404C), RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 9.dp), maxLines = 1)
        DlFull(Downloads.list.firstOrNull { it.id == DlPlay.id })
    }
}

/** 这次打开下载页已经浮出过的行：滑出屏幕再滑回来不再淡入一遍（以前上下滑会像刷新了一下）。关掉下载页清空。 */
private val risen = HashSet<String>()
/** 打开下载页的时刻：只有这一刻就在屏幕上的行才浮出；之后滑进来的行直接显示（第一次快速往下滑时，新行等着延迟会空一下）。 */
private var openedAt = 0L

@Composable
private fun DlRow(d: Dl, i: Int, onAct: (android.net.Uri, String) -> Unit) {
    val first = remember { risen.add(d.id) && System.currentTimeMillis() - openedAt < 400 }
    val rise = remember { Animatable(if (first) 0f else 1f) }
    LaunchedEffect(Unit) { if (first) { delay(40L * i.coerceAtMost(8)); rise.animateTo(1f, tween(360, easing = Curves.decel)) } }
    val ctx = LocalContext.current
    Column(
        Modifier.graphicsLayer { alpha = rise.value; translationY = (1f - rise.value) * 8.dp.toPx() }.fillMaxWidth()
            .background(Color.White.copy(alpha = .04f), RoundedCornerShape(16.dp)).padding(10.dp),
    ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // 缩略图：点了这一行展开成播放器，边下边看；正在看时盖一层「在看」
        val watching = DlPlay.id == d.id
        PressSurface({ DlPlay.toggle(ctx, d) }, Modifier.width(76.dp).aspectRatio(16f / 9f), shape = RoundedCornerShape(8.dp), fill = Color(0xFF3A2A20), pressScale = .95f, label = if (watching) "收起播放" else "边下边看") {
            SvgIcon(if (d.audioOnly) Ic("M9 18V6l10-2v12M9 18a2.5 2.5 0 1 1-5 0 2.5 2.5 0 0 1 5 0ZM19 16a2.5 2.5 0 1 1-5 0 2.5 2.5 0 0 1 5 0Z") else playIc, Warm.copy(alpha = .8f), 18.dp, stroke = 1.6f, modifier = Modifier.align(Alignment.Center))
            val wa by animateFloatAsState(if (watching) 1f else 0f, tween(200), label = "watch")
            if (wa > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = wa }.background(Color(0x8C121418)), contentAlignment = Alignment.Center) { Txt("在看", Warm, 10.5.sp, maxLines = 1) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Txt(d.title, Page.INK, 13.5.sp, maxLines = 1)
            val line = when (d.state) {
                3 -> listOf(d.quality, mb(d.total), if (d.out.startsWith("content://")) "在相册里" else "在 App 里").filter { it.isNotBlank() }.joinToString(" · ")
                4 -> "没下成：" + d.error
                5 -> "已暂停 · ${(d.frac * 100).toInt()}%"
                6 -> "等 Wi-Fi"
                2 -> d.note.ifBlank { "正在合成…" }
                0 -> "排队中"
                else -> listOf(d.quality, if (d.total > 0) mb(d.done).ifBlank { "0 MB" } + " / " + mb(d.total) else mb(d.done), if (d.speed > 0) DownloadService.speed(d.speed) else "", if (d.media.kind == "magnet") d.note else "").filter { it.isNotBlank() }.joinToString(" · ")
            }
            // 磁力：点状态字展开排查信息（连上几个人、Tracker 哪些能用），每秒刷新
            var diag by remember { mutableStateOf(false) }
            val magnet = d.media.kind == "magnet"
            Txt(line, if (d.state == 4) Color(0xFFD79A94) else Page.MUTED.copy(alpha = .7f), 11.5.sp,
                Modifier.padding(top = 3.dp).then(if (magnet) Modifier.pointerInput(Unit) { detectTapGestures { diag = !diag } } else Modifier), maxLines = 2)
            if (magnet && diag) {
                var text by remember { mutableStateOf("") }
                LaunchedEffect(Unit) { while (true) { text = withContext(kotlinx.coroutines.Dispatchers.IO) { Thunder.diag(d.id) ?: Torrent.diag(d.id) }; delay(1000) } }
                val ctx2 = LocalContext.current
                // 长按复制（连磁力链接一起），发过来好照着排查
                Txt(text + "\n长按复制", Page.MUTED.copy(alpha = .8f), 10.5.sp, Modifier.padding(top = 6.dp).background(Color.White.copy(alpha = .04f), RoundedCornerShape(8.dp))
                    .pointerInput(Unit) { detectTapGestures(onLongPress = {
                        (ctx2.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                            .setPrimaryClip(android.content.ClipData.newPlainText("磁力排查", d.title + "\n" + d.media.url + "\n" + text))
                        Downloads.note = "排查信息已复制"
                    }) }.padding(8.dp), lineHeight = 16.sp)
            }
            // 磁力一直没进度：回原网页下在线那份
            if (magnet && d.state in setOf(1, 4, 5) && d.done < (1 shl 20) && d.media.page.startsWith("http")) Row(Modifier.padding(top = 8.dp)) {
                SmallAct("回网页下在线版", false) { Downloads.open = false; DlPlay.close(); InBrowser.open(d.media.page) }
            }
            if (d.state == 3) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val u = Downloads.uri(d)
                SmallAct("播放", true) { u?.let { onAct(it, "play") } }
                SmallAct("分享", false) { u?.let { onAct(it, "share") } }
                SmallAct("删除", false) { Downloads.delete(d) }
            } else if (d.state != 4) {
                val p by animateFloatAsState(d.frac, tween(300), label = "p")
                Box(Modifier.padding(top = 7.dp).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = .08f), RoundedCornerShape(2.dp))) {
                    Box(Modifier.fillMaxWidth(p).fillMaxHeight().background(Warm, RoundedCornerShape(2.dp)))
                }
            }
        }
        if (d.state != 3) {
            Spacer(Modifier.width(8.dp))
            val p by animateFloatAsState(d.frac, tween(300), label = "ring")
            PressSurface({ when (d.state) { 4, 5 -> Downloads.resume(d); else -> Downloads.pause(d) } }, Modifier.size(40.dp), shape = CircleShape, fill = Color.Transparent, pressScale = .88f,
                label = if (d.state in setOf(4, 5)) "继续" else "暂停") {
                Canvas(Modifier.size(34.dp)) {
                    val s = 2.5.dp.toPx()
                    drawCircle(Color.White.copy(alpha = .08f), size.minDimension / 2 - s / 2, style = Stroke(s))
                    drawArc(Warm, -90f, 360f * p, false, Offset(s / 2, s / 2), androidx.compose.ui.geometry.Size(size.width - s, size.height - s), style = Stroke(s, cap = StrokeCap.Round))
                }
                SvgIcon(when (d.state) { 4 -> retry; 5 -> playIc; else -> pauseIc }, Warm, 14.dp, stroke = 2f)
            }
            if (d.state in setOf(4, 5)) PressSurface({ Downloads.delete(d) }, Modifier.size(36.dp), shape = CircleShape, fill = Color.Transparent, pressScale = .88f, label = "删除") { SvgIcon(trash, Page.MUTED, 16.dp, stroke = 1.6f) }
        }
    }
    DlInline(d)
    }
}

@Composable
private fun SmallAct(text: String, main: Boolean, onClick: () -> Unit) {
    PressSurface(onClick, Modifier.height(30.dp), shape = RoundedCornerShape(10.dp), fill = if (main) Warm else Color.White.copy(alpha = .07f), pressScale = .94f, label = text) {
        Txt(text, if (main) Ink2 else Page.INK, 12.sp, Modifier.padding(horizontal = 12.dp), maxLines = 1)
    }
}

