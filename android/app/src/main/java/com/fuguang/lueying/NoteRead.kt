package com.fuguang.lueying

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first

/** 掠影读到哪、字号、收藏：存在本机，关掉卡片再开还在原处。 */
@Stable
class NoteReading(private val prefs: Prefs) {
    var size by mutableIntStateOf(prefs.fontSize)
    var favorites by mutableStateOf(prefs.favorites)
    var last by mutableStateOf(prefs.lastChapter)

    fun open(id: String) { last = id; prefs.lastChapter = id; NoteCard.chapter = id }
    fun resize(d: Int) { size = (size + d).coerceIn(14, 22); prefs.fontSize = size }
    fun toggle(id: String) { favorites = if (id in favorites) favorites - id else favorites + id; prefs.favorites = favorites }
    fun progress(id: String) = prefs.progress(id)
    fun keep(id: String, at: Int) = prefs.setProgress(id, at)

    fun filtered(all: List<Chapter>): List<Chapter> = all.filter { c ->
        val g = NoteCard.group
        (g == "all" || (g == "favorites" && c.id in favorites) || c.group == g) &&
            (NoteCard.query.isEmpty() || (c.title + " " + c.text).contains(NoteCard.query, ignoreCase = true))
    }
}

/** 某篇在自己分组里是第几篇。 */
private fun numberOf(all: List<Chapter>, c: Chapter) = all.takeWhile { it.id != c.id }.count { it.group == c.group } + 1

@Composable
internal fun TocPage(data: Luoying, r: NoteReading) {
    Column(Modifier.widthIn(max = 600.dp).fillMaxSize()) {
        CardPageHead("掠影", "文字里的旧日光影")
        SearchBox()
        val groups = listOf("all" to "全部") + Content.groups.map { it to it } + listOf("favorites" to "收藏")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            groups.forEach { (key, label) ->
                val n = when (key) { "all" -> data.chapters.size; "favorites" -> r.favorites.size; else -> data.chapters.count { it.group == key } }
                Chip("$label $n", NoteCard.group == key) { NoteCard.group = key }
            }
            if (data.messages.isNotEmpty()) Chip("对话 ${data.messages.size}", false) { NoteCard.screen = "talks" }
        }
        val list = r.filtered(data.chapters).asReversed()
        if (list.isEmpty()) {
            Txt(if (NoteCard.group == "favorites" && NoteCard.query.isEmpty()) "还没收藏。读的时候点「收藏」。" else "没找到。换个词试试。", Page.muted.copy(alpha = .6f), 13.sp, Modifier.fillMaxWidth().padding(vertical = 28.dp), align = androidx.compose.ui.text.style.TextAlign.Center)
        }
        LazyColumn(Modifier.weight(1f)) {
            items(list.distinctBy { it.id }, key = { it.id }) { c ->
                val cur = c.id == r.last
                PressSurface({ r.open(c.id); NoteCard.screen = "read" }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), fill = androidx.compose.ui.graphics.Color.Transparent, pressScale = .99f, label = c.title) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 11.dp)) {
                        Txt("${c.group} · %02d".format(numberOf(data.chapters, c)), Page.muted.copy(alpha = .5f), 10.5.sp, spacing = ls(1.5f, 10.5f))
                        Spacer(Modifier.height(3.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Highlighted(c.title, NoteCard.query, if (cur) NoteWarm else Page.ink.copy(alpha = .86f), Modifier.weight(1f, fill = false))
                            if (cur) Txt("  读到这里", NoteWarm, 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchBox() {
    Row(Modifier.fillMaxWidth().background(Skin.well(.22f), RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        SvgIcon(SideIcons.search, Page.muted, 16.dp, stroke = 1.5f)
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            NoteCard.query, { NoteCard.query = it }, Modifier.weight(1f), singleLine = true,
            textStyle = TextStyle(fontFamily = Sans, color = Page.ink, fontSize = 14.sp), cursorBrush = SolidColor(NoteWarm),
            decorationBox = { inner -> Box { if (NoteCard.query.isEmpty()) Txt("搜索标题或正文", Page.muted.copy(alpha = .55f), 14.sp); inner() } },
        )
    }
}

@Composable
private fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    PressSurface(onClick, fill = if (on) NoteWarm.copy(alpha = .16f) else CardBlock, pressScale = .95f, label = text) {
        Txt(text, if (on) NoteWarm else Page.muted, 12.5.sp, Modifier.padding(horizontal = 12.dp, vertical = 7.dp), maxLines = 1)
    }
}

@Composable
private fun Highlighted(text: String, q: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    val s = buildAnnotatedString {
        if (q.isEmpty()) { append(text); return@buildAnnotatedString }
        var i = 0
        while (i < text.length) {
            val j = text.indexOf(q, i, ignoreCase = true)
            if (j < 0) { append(text.substring(i)); break }
            append(text.substring(i, j))
            withStyle(SpanStyle(color = NoteWarm)) { append(text.substring(j, j + q.length)) }
            i = j + q.length
        }
    }
    BasicText(s, modifier, style = TextStyle(color = color, fontSize = 15.5.sp, fontFamily = Serif), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
}

@Composable
internal fun ReadPage(data: Luoying, r: NoteReading) {
    val list = r.filtered(data.chapters).ifEmpty { data.chapters }
    val c = data.chapters.firstOrNull { it.id == NoteCard.chapter } ?: return
    val i = list.indexOfFirst { it.id == c.id }
    val scroll = remember(c.id) { ScrollState(0) }
    LaunchedEffect(c.id) {
        val want = r.progress(c.id)
        if (want > 0) { androidx.compose.runtime.snapshotFlow { scroll.maxValue }.first { it > 0 }; scroll.scrollTo(want) }
    }
    DisposableEffect(c.id) { onDispose { r.keep(c.id, scroll.value) } }
    Column(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
        CardPageHead("掠影")
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 6.dp)) {
            Txt("${c.group} / %02d · ${c.characters} 字".format(numberOf(data.chapters, c)), NoteEyebrow, 11.sp, spacing = ls(3f, 11f))
            val fs = r.size.toFloat()
            Txt(c.title, Page.ink, (fs * 1.55f).sp, Modifier.padding(top = 12.dp, bottom = 16.dp), spacing = ls(1.5f, fs * 1.55f), lineHeight = (fs * 2.1f).sp, family = Serif)
            val parts = remember(c.id) { c.text.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() } }
            parts.forEach { para ->
                val stamp = para.startsWith("【")
                BasicText(
                    remember(para) { if (stamp) AnnotatedString(para) else bold(para) },
                    Modifier.padding(bottom = (fs * 1.05f).dp),
                    style = TextStyle(color = if (stamp) Page.muted.copy(alpha = .7f) else Page.ink.copy(alpha = .86f), fontSize = fs.sp, lineHeight = (fs * 2.05f).sp, fontFamily = Serif),
                )
            }
            Spacer(Modifier.height(12.dp))
        }
        CardBottomBar {
            BarText("← 上一篇", i > 0) { list.getOrNull(i - 1)?.let { r.open(it.id) } }
            Spacer(Modifier.weight(1f))
            BarText("A−", r.size > 14) { r.resize(-1) }
            BarText("A+", r.size < 22) { r.resize(1) }
            val fav = c.id in r.favorites
            BarText(if (fav) "已收藏" else "收藏", true, if (fav) NoteWarm else null) { r.toggle(c.id) }
            val pct = if (scroll.maxValue > 0) scroll.value * 100 / scroll.maxValue else 100
            Txt("$pct%", Page.muted, 11.sp, Modifier.width(36.dp).padding(start = 4.dp))
            Spacer(Modifier.weight(1f))
            BarText("下一篇 →", i in 0 until list.size - 1) { list.getOrNull(i + 1)?.let { r.open(it.id) } }
        }
    }
}

@Composable
internal fun BarText(text: String, enabled: Boolean, color: androidx.compose.ui.graphics.Color? = null, onClick: () -> Unit) {
    PressSurface(onClick, fill = androidx.compose.ui.graphics.Color.Transparent, enabled = enabled, pressScale = .92f, label = text) {
        Txt(text, color ?: if (enabled) Page.ink.copy(alpha = .86f) else Page.muted.copy(alpha = .35f), 12.5.sp, Modifier.padding(horizontal = 7.dp, vertical = 8.dp), maxLines = 1)
    }
}

/** 原始对话：按原来的顺序，你的话靠右成气泡，回答在左边。 */
@Composable
internal fun TalksPage(data: Luoying) {
    Column(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
        CardPageHead("掠影", "${data.messages.size} 条原始对话")
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(data.messages) { m ->
                if (m.role == "user") {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        Txt(m.text, Page.ink, 14.sp, Modifier.widthIn(max = 260.dp).background(veil(.08f), RoundedCornerShape(16.dp)).padding(horizontal = 13.dp, vertical = 8.dp), lineHeight = 22.sp)
                    }
                } else {
                    Txt(m.text, Page.ink.copy(alpha = .82f), 14.sp, Modifier.padding(horizontal = 4.dp), lineHeight = 25.sp)
                }
            }
        }
    }
}

/** 正文里的 **加粗**：换成稍亮的字。 */
private fun bold(s: String): AnnotatedString = buildAnnotatedString {
    var last = 0
    Regex("\\*\\*(.+?)\\*\\*").findAll(s).forEach { m ->
        append(s.substring(last, m.range.first))
        withStyle(SpanStyle(color = Page.ink, fontWeight = FontWeight.Medium)) { append(m.groupValues[1]) }
        last = m.range.last + 1
    }
    append(s.substring(last))
}
