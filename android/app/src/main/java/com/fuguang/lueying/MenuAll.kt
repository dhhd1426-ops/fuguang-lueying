package com.fuguang.lueying

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 多久以前：刚刚 / 5分钟前 / 3小时前 / 昨天 / 4天前 / 10月3日。 */
internal fun ago(t: Long, now: Long = System.currentTimeMillis()): String {
    val m = (now - t) / 60_000
    return when {
        m < 1 -> "刚刚"
        m < 60 -> "${m}分钟前"
        m < 24 * 60 -> "${m / 60}小时前"
        m < 48 * 60 -> "昨天"
        m < 7 * 24 * 60 -> "${m / (24 * 60)}天前"
        else -> java.text.SimpleDateFormat("M月d日", java.util.Locale.CHINA).format(java.util.Date(t))
    }
}

/** 字里命中的词标成杏色。 */
private fun marked(text: String, q: String): AnnotatedString = buildAnnotatedString {
    if (q.isEmpty()) { append(text); return@buildAnnotatedString }
    var i = 0
    while (true) {
        val k = text.indexOf(q, i, ignoreCase = true)
        if (k < 0) { append(text.substring(i)); break }
        append(text.substring(i, k))
        pushStyle(SpanStyle(color = SideWarm)); append(text.substring(k, k + q.length)); pop()
        i = k + q.length
    }
}

/** 「查看全部」：所有对话，按时间排；上面一个搜索框，标题和聊天内容都能搜。 */
@Composable
internal fun AllConvs(history: ConvStore, current: String?, busy: Boolean, onBack: () -> Unit, onOpen: (Conv) -> Unit) {
    var q by remember { mutableStateOf("") }
    // 默认只看现在这位助理的；点「全部助理」看所有人的（点开别人的对话会换到她那边）
    var everyone by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val key = q.trim()
    val rows = remember(key, everyone, Memory.activeId, history.all.size, history.all.firstOrNull()?.updated) {
        (if (everyone) history.visible() else history.of()).sortedByDescending { it.updated }.mapNotNull { c ->
            if (key.isEmpty() || c.title.contains(key, true)) c to ""
            else c.msgs.firstOrNull { it.text.contains(key, true) }?.let { m ->
                // 内容里命中：带出命中处前后一小段
                val k = m.text.indexOf(key, ignoreCase = true)
                c to m.text.substring((k - 8).coerceAtLeast(0), (k + key.length + 16).coerceAtMost(m.text.length)).replace('\n', ' ')
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(top = 22.dp)) {
        Box(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
            Pressable(onBack, Modifier.align(Alignment.CenterStart), label = "返回") { p ->
                SvgIcon(SideIcons.back, if (p) Page.muted else Page.ink, 20.dp, stroke = 1.6f, modifier = Modifier.padding(8.dp))
            }
            Txt("全部对话", Page.ink, 17.sp, Modifier.align(Alignment.Center), spacing = ls(3f, 17f), family = Serif, maxLines = 1)
        }
        Row(
            Modifier.padding(horizontal = 14.dp).fillMaxWidth().background(veil(.06f), RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SvgIcon(SideIcons.search, Page.muted, 18.dp, stroke = 1.5f)
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                q, { q = it }, Modifier.weight(1f).focusRequester(focus), singleLine = true,
                textStyle = TextStyle(fontFamily = Sans, color = Page.ink, fontSize = 14.5.sp), cursorBrush = SolidColor(SideWarm),
                decorationBox = { inner -> Box { if (q.isEmpty()) Txt("搜索标题和内容", Page.muted, 14.5.sp); inner() } },
            )
        }
        Row(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
            ScopeChip(!everyone, "只看 ${Memory.active.name}", { PersonaDot(Memory.active, 16.dp) }) { everyone = false }
            ScopeChip(everyone, "全部助理") { everyone = true }
        }
        if (rows.isEmpty()) {
            Txt(if (key.isEmpty()) "还没有对话" else "没有找到「$key」", SideFaint, 13.5.sp, Modifier.fillMaxWidth().padding(top = 36.dp).padding(horizontal = 16.dp))
        }
        LazyColumn(Modifier.weight(1f).padding(top = 10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 10.dp, end = 10.dp, bottom = 96.dp)) {
            items(rows, key = { it.first.id }) { (c, hit) ->
                val on = c.id == current
                Pressable({ onOpen(c) }, Modifier.fillMaxWidth(), label = c.title) { p ->
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (on) SideWarm.copy(alpha = .13f) else if (p) veil(.05f) else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        val owner = if (everyone) Memory.get(c.who) else null
                        if (owner != null) Box(Modifier.padding(top = 1.dp)) { PersonaDot(owner, 19.dp) }
                        else SvgIcon(convIcon(c), if (on) SideWarm else SideFaint, 17.dp, stroke = 1.4f, modifier = Modifier.padding(top = 3.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            androidx.compose.foundation.text.BasicText(
                                marked(c.title.ifBlank { "新对话" }, key), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = TextStyle(fontFamily = Sans, color = SideInk, fontSize = 15.sp, fontWeight = FontWeight.Normal),
                            )
                            val meta = ago(c.updated) + (if (busy && on) " · 她在回答" else "")
                            androidx.compose.foundation.text.BasicText(
                                buildAnnotatedString { append(meta); if (hit.isNotEmpty()) { append(" · "); append(marked(hit, key)) } },
                                Modifier.padding(top = 3.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = TextStyle(fontFamily = Sans, color = SideFaint, fontSize = 12.sp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 「查看全部」顶上的范围：只看这一位 / 全部助理。 */
@Composable
private fun ScopeChip(on: Boolean, text: String, lead: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Pressable(onClick, label = text) { p ->
        Row(
            Modifier.background(if (on) SideWarm.copy(alpha = .16f) else veil(if (p) .09f else .05f), RoundedCornerShape(50))
                .padding(start = if (lead != null) 5.dp else 13.dp, end = 13.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (lead != null) { lead(); Spacer(Modifier.width(6.dp)) }
            Txt(text, if (on) Page.ink else Page.muted, 12.5.sp, Modifier.widthIn(max = 150.dp), maxLines = 1)
        }
    }
}
