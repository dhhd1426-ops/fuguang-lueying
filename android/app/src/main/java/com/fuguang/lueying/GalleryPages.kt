package com.fuguang.lueying

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private val warm = Color(0xFFE9B48E)
private val okInk = Color(0xFF9CC4A4)
private val chevron = Ic("M9.5 6l6 6-6 6")
private val plus = Ic("M12 5v14M5 12h14")
private val play = Ic("M8 5.5v13l10.5-6.5Z")

@Composable
private fun Logo(s: GallerySource, size: Dp) {
    Box(Modifier.size(size).background(Color(s.tint), RoundedCornerShape(size * .3f)), contentAlignment = Alignment.Center) {
        Txt(s.letter, Color(0xFF2A211B), (size.value * .44f).sp, family = Serif, maxLines = 1)
    }
}

@Composable
private fun Label(text: String) = Txt(text, Page.muted, 11.sp, Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp), spacing = ls(2.75f, 11f))

/** Key 的状态小药丸：不要 Key / 已填 Key / 缺 Key。 */
@Composable
private fun StatePill(s: GallerySource) {
    val (t, c) = when { s.free -> "不要 Key" to okInk; Gallery.ready(s.id) -> "已填 Key" to okInk; else -> "缺 Key" to warm }
    Txt(t, c, 11.sp, Modifier.background(rgba(255, 255, 255, .06f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp), maxLines = 1)
}

/**
 * 「图库」（方案一 · 列表）：总开关；在用的每家一行（名字、一句介绍、Key 的状态、›）；「添加一家」原地展开三列格子；署名说明。
 */
@Composable
internal fun GalleryPage(onBack: () -> Unit, onOpen: (String) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    Column(Modifier.widthIn(max = 560.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
        CardPageHead("图库", onBack = onBack)
        Row(Modifier.fillMaxWidth().background(CardBlock, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Txt("让她用图库", Page.ink, 15.sp)
                Txt("一般配图先在这里找，拿高清原图", Page.muted, 12.sp, Modifier.padding(top = 3.dp))
            }
            CardSwitch(Gallery.on, "图库") { Gallery.on = it; Gallery.save() }
        }
        Label("在用的")
        Column(Modifier.fillMaxWidth().background(CardBlock, RoundedCornerShape(18.dp)).padding(6.dp)) {
            if (Gallery.used.isEmpty()) Txt("一家都没加，点下面「添加一家」", Page.muted, 12.5.sp, Modifier.padding(10.dp))
            Gallery.used.forEach { id ->
                val s = Gallery.source(id)
                PressSurface({ onOpen(id) }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), fill = Color.Transparent, pressScale = .98f, label = "设置 ${s.name}") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Logo(s, 32.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Txt(s.name, Page.ink, 14.5.sp, maxLines = 1)
                            Txt(s.sub, Page.muted, 11.5.sp, Modifier.padding(top = 2.dp), maxLines = 1)
                        }
                        StatePill(s)
                        Spacer(Modifier.width(4.dp))
                        SvgIcon(chevron, Page.muted.copy(alpha = .6f), 15.dp, stroke = 1.8f)
                    }
                }
            }
            Pressable({ picking = !picking }, Modifier.fillMaxWidth(), label = "添加一家") { pr ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    SvgIcon(plus, if (pr) Page.ink else Page.muted, 16.dp, stroke = 1.7f)
                    Spacer(Modifier.width(10.dp))
                    Txt("添加一家", if (pr) Page.ink else Page.muted, 13.5.sp)
                }
            }
            AnimatedVisibility(
                picking,
                enter = expandVertically(spring(dampingRatio = .85f, stiffness = 420f), expandFrom = Alignment.Top) + fadeIn(tween(200)),
                exit = shrinkVertically(tween(220, easing = Curves.standard), shrinkTowards = Alignment.Top) + fadeOut(tween(140)),
            ) {
                val rest = Gallery.sources.filter { it.id !in Gallery.used }
                Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (rest.isEmpty()) Txt("都加上了", Page.muted, 12.sp, Modifier.padding(8.dp))
                    rest.chunked(3).forEach { line ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            line.forEach { s ->
                                PressSurface({ picking = false; Gallery.add(s.id); if (!s.free) onOpen(s.id) }, Modifier.weight(1f), shape = RoundedCornerShape(14.dp), pressScale = .95f, label = "添加 ${s.name}") {
                                    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Logo(s, 30.dp)
                                        Txt(s.name, Page.ink.copy(alpha = .86f), 12.sp, Modifier.padding(top = 6.dp), maxLines = 1)
                                        Txt(if (s.free) "不要 Key" else "要 Key", Page.muted.copy(alpha = .6f), 10.sp, maxLines = 1)
                                    }
                                }
                            }
                            repeat(3 - line.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
        Txt("作品里用了图库的图，每张下面会写上作者和图库名。", Page.muted.copy(alpha = .55f), 11.5.sp, Modifier.padding(start = 8.dp, top = 12.dp, bottom = 8.dp))
    }
}

/** 某一家：介绍、Key（停手就存）、去拿 Key、试搜一下（三列小图依次浮上来）、移除。 */
@Composable
internal fun GallerySourcePage(id: String, onBack: () -> Unit) {
    val s = Gallery.source(id)
    val scope = rememberCoroutineScope()
    var key by remember(id) { mutableStateOf(Gallery.keys[id].orEmpty()) }
    var q by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var pics by remember { mutableStateOf<List<Pic>?>(null) }
    var err by remember { mutableStateOf("") }
    Column(Modifier.widthIn(max = 560.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
        CardPageHead(s.name, s.sub, onBack = onBack)
        val about = when {
            s.remote -> "它要求直接用它给的链接，图不会存进手机，作品要联网才看得到。"
            id == "iconify" -> "200 多套开源图标，拿到的是 SVG，颜色跟着作品走。"
            else -> "用到的图会存进手机，断网也能看。"
        }
        Txt(about, Page.muted, 12.5.sp, Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp), lineHeight = 20.sp)
        if (!s.free) {
            Label("API KEY")
            CardField(key, { key = it; Gallery.setKey(id, it) }, "贴进来，自动保存")
            if (s.site.isNotEmpty()) Pressable({ InBrowser.open("https://${s.site}") }, label = "去 ${s.site} 拿 Key") { pr ->
                Txt("去 ${s.site.substringBefore('/').removePrefix("www.")} 拿 Key ↗", warm.copy(alpha = if (pr) .7f else 1f), 12.5.sp, Modifier.padding(start = 8.dp, top = 12.dp))
            }
        }
        Label("试搜一下")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { CardField(q, { q = it }, if (id == "iconify") "比如：coffee" else "比如：autumn cafe") }
            Spacer(Modifier.width(8.dp))
            PressSurface(
                {
                    if (busy) return@PressSurface
                    if (!Gallery.ready(id)) { err = "还没填 Key，先去拿一个"; return@PressSurface }
                    busy = true; err = ""; pics = null
                    scope.launch {
                        val word = q.trim().ifEmpty { if (id == "iconify") "coffee" else "autumn cafe" }
                        runCatching { Gallery.search(id, word, if (id == "iconify") "icon" else "photo", "", 6) }
                            .onSuccess { pics = it; if (it.isEmpty()) err = "没找到，换个英文词试试" }
                            .onFailure { err = it.message ?: "没连上" }
                        busy = false
                    }
                },
                Modifier.size(44.dp), shape = CircleShape, fill = warm, pressedFill = warm.copy(alpha = .85f), pressScale = .92f, label = "试搜",
            ) { if (busy) LoadRing(Color(0xFF2A211B), 18.dp) else SvgIcon(play, Color(0xFF2A211B), 16.dp, stroke = 1.6f) }
        }
        if (err.isNotEmpty()) Txt(err, Color(0xFFD79A94), 12.sp, Modifier.padding(start = 8.dp, top = 10.dp))
        pics?.takeIf { it.isNotEmpty() }?.let { list ->
            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                list.chunked(3).forEachIndexed { r, line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        line.forEachIndexed { c, p -> PicTile(p, r * 3 + c, Modifier.weight(1f)) }
                        repeat(3 - line.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        Pressable({ Gallery.remove(id); onBack() }, label = "移除这一家") { pr ->
            Txt("移除这一家", Color(0xFFD79A94).copy(alpha = if (pr) .6f else 1f), 13.sp, Modifier.padding(start = 8.dp, top = 18.dp, bottom = 8.dp))
        }
    }
}

/** 试搜的一格：小图解出来就浮上来（240ms，一格晚 40ms）；图标没有小图，写它的名字。 */
@Composable
private fun PicTile(p: Pic, i: Int, modifier: Modifier) {
    val bmp by produceState<Bitmap?>(null, p.thumb) { value = if (p.thumb.isEmpty()) null else loadThumb(p.thumb) }
    var shown by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { delay(i * 40L); shown = true }
    val t by animateFloatAsState(if (shown && (bmp != null || p.thumb.isEmpty())) 1f else 0f, tween(240, easing = Curves.decel), label = "tile")
    Box(
        modifier.aspectRatio(if (p.kind == "图标") 1f else .75f).graphicsLayer { alpha = t; scaleX = .94f + .06f * t; scaleY = .94f + .06f * t }
            .clip(RoundedCornerShape(10.dp)).background(CardBlock),
        contentAlignment = Alignment.Center,
    ) {
        bmp?.let { Image(it.asImageBitmap(), p.author, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (p.thumb.isEmpty()) Txt(p.author.substringAfter(':'), warm, 11.sp, Modifier.padding(6.dp), maxLines = 2)
        else if (p.author.isNotBlank()) Txt(
            p.author, Color.White.copy(alpha = .92f), 10.sp,
            Modifier.align(Alignment.BottomStart).fillMaxWidth().background(rgba(0, 0, 0, .45f)).padding(horizontal = 6.dp, vertical = 3.dp), maxLines = 1,
        )
    }
}

private suspend fun loadThumb(u: String): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val c = (URL(u).openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 12000; setRequestProperty("User-Agent", Materials.UA) }
        val b = try { c.inputStream.use { it.readBytes() } } finally { c.disconnect() }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(b, 0, b.size, o)
        var s = 1; while (o.outWidth / (s * 2) >= 240) s *= 2
        BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = s })
    }.getOrNull()
}
