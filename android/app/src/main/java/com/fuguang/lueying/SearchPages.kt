package com.fuguang.lueying

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val warm: Color get() = Skin.accent
private val globe = Ic("M4 12h16M12 4c2.4 2.5 2.4 13.5 0 16M12 4c-2.4 2.5-2.4 13.5 0 16", circles = listOf(floatArrayOf(12f, 12f, 8f)))
private val chevron = Ic("M9.5 6l6 6-6 6")
private val check = Ic("M5 12.5l4.5 4.5L19 7.5")
private val plus = Ic("M12 5v14M5 12h14")
private val play = Ic("M8 5.5v13l10.5-6.5Z")

/** 「我的 → 功能」：她会的本事。现在只有联网搜索，以后新加的也放这一页。 */
@Composable
internal fun FeaturesPage(onBack: () -> Unit, onSearch: () -> Unit, onSkills: () -> Unit) {
    Column(Modifier.widthIn(max = 560.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
        CardPageHead("功能", onBack = onBack)
        val src = WebSearch.source(WebSearch.use)
        MineRow(0, 1f, "联网搜索", if (WebSearch.on) "${src.name} · 最多 ${WebSearch.max} 条" else "关着", onSearch, glyph = { SvgIcon(globe, warm, 19.dp) }) {
            if (WebSearch.on) { Box(Modifier.size(6.dp).background(warm, CircleShape)); Spacer(Modifier.width(7.dp)) }
            Txt(if (WebSearch.on) "开着" else "关着", Page.muted, 13.sp)
            Spacer(Modifier.width(4.dp))
            SvgIcon(chevron, Page.muted.copy(alpha = .6f), 15.dp, stroke = 1.8f)
        }
        Spacer(Modifier.height(6.dp))
        val mine = Skills.of(); val on = mine.count { it.on }; val hers = mine.count { it.by == "her" }
        MineRow(1, 1f, "技能", if (!Skills.on) "关着" else if (mine.isEmpty()) "还没有 · 她能自己找来装" else "$on 个在用" + (if (hers > 0) " · 她自己装了 $hers 个" else ""), onSkills, glyph = { SvgIcon(skillIcon, warm, 19.dp) }) {
            if (Skills.on) { Box(Modifier.size(6.dp).background(warm, CircleShape)); Spacer(Modifier.width(7.dp)) }
            Txt(if (Skills.on) "开着" else "关着", Page.muted, 13.sp)
            Spacer(Modifier.width(4.dp))
            SvgIcon(chevron, Page.muted.copy(alpha = .6f), 15.dp, stroke = 1.8f)
        }
        Txt("她会的本事放在这里。以后新加的（比如看图、读文件）也在这一页。", Page.muted.copy(alpha = .55f), 11.5.sp, Modifier.padding(start = 8.dp, top = 12.dp), lineHeight = 20.sp)
    }
}

/** 一家的小方块：底色 + 字。 */
@Composable
private fun Logo(s: SearchSource, size: Dp) {
    Box(Modifier.size(size).background(Color(s.tint), RoundedCornerShape(size * .3f)), contentAlignment = Alignment.Center) {
        Txt(s.letter, Skin.onAccent, (size.value * (if (s.letter.length > 1) .36f else .44f)).sp, family = Serif, maxLines = 1)
    }
}

@Composable
private fun Label(text: String) = Txt(text, Page.muted, 11.sp, Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp), spacing = ls(2.75f, 11f))

/**
 * 「联网搜索」：开关；用哪一家（点一家打 ✓；要 Key 的右边 › 进设置；没填 Key 的点了直接进设置）；
 * 「添加一家」原地展开一格格；通用：最多几条结果、最多等几秒。
 */
@Composable
internal fun SearchPage(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val haptic = LocalHapticFeedback.current
    var picking by remember { mutableStateOf(false) }
    Column(Modifier.widthIn(max = 560.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
        CardPageHead("联网搜索", onBack = onBack)
        Row(Modifier.fillMaxWidth().background(CardBlock, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Txt("让她联网搜索", Page.ink, 15.sp)
                Txt("问到最新的事、价格、新闻时，她自己去查", Page.muted, 12.sp, Modifier.padding(top = 3.dp))
            }
            CardSwitch(WebSearch.on, "联网搜索") { WebSearch.on = it; WebSearch.save() }
        }
        Label("用哪一家")
        Column(Modifier.fillMaxWidth().background(CardBlock, RoundedCornerShape(18.dp)).padding(6.dp)) {
            WebSearch.added.forEach { id ->
                val s = WebSearch.source(id)
                val on = WebSearch.use == id
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PressSurface(
                        {
                            WebSearch.use = id; WebSearch.save(); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            if (!WebSearch.ready(id)) onOpen(id)
                        },
                        Modifier.weight(1f), shape = RoundedCornerShape(14.dp), fill = if (on) warm.copy(alpha = .12f) else Color.Transparent, pressScale = .98f, label = "用 ${s.name}",
                    ) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Logo(s, 32.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Txt(s.name, Page.ink, 14.5.sp, maxLines = 1)
                                Txt(if (WebSearch.ready(id)) s.sub else if (s.needUrl) "还没填地址" else "还没填 Key", Page.muted, 11.5.sp, Modifier.padding(top = 2.dp), maxLines = 1)
                            }
                            if (on) SvgIcon(check, warm, 18.dp, stroke = 1.8f)
                        }
                    }
                    if (!s.free) PressSurface({ onOpen(id) }, Modifier.size(36.dp), shape = CircleShape, fill = Color.Transparent, pressScale = .9f, label = "设置 ${s.name}") {
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
                val rest = WebSearch.sources.filter { it.id !in WebSearch.added }
                Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (rest.isEmpty()) Txt("都加上了", Page.muted, 12.sp, Modifier.padding(8.dp))
                    rest.chunked(3).forEach { line ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            line.forEach { s ->
                                PressSurface({ picking = false; WebSearch.add(s.id); if (!s.free) onOpen(s.id) }, Modifier.weight(1f), shape = RoundedCornerShape(14.dp), pressScale = .95f, label = "添加 ${s.name}") {
                                    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Logo(s, 30.dp)
                                        Txt(s.name, Page.ink.copy(alpha = .86f), 12.sp, Modifier.padding(top = 6.dp), maxLines = 1)
                                        Txt(s.sub, Page.muted.copy(alpha = .6f), 10.sp, maxLines = 1)
                                    }
                                }
                            }
                            repeat(3 - line.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
        Label("通用")
        StepRow("最多几条结果", WebSearch.max, 1..20) { WebSearch.max = it; WebSearch.save() }
        Spacer(Modifier.height(6.dp))
        StepRow("最多等几秒", WebSearch.wait, 2..30) { WebSearch.wait = it; WebSearch.save() }
        Txt("搜到的网页她会在回答里注明出处。", Page.muted.copy(alpha = .55f), 11.5.sp, Modifier.padding(start = 8.dp, top = 12.dp, bottom = 8.dp))
    }
}

@Composable
private fun StepRow(title: String, v: Int, range: IntRange, onChange: (Int) -> Unit) {
    val haptic = LocalHapticFeedback.current
    Row(Modifier.fillMaxWidth().background(CardBlock, RoundedCornerShape(16.dp)).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(title, Page.ink, 15.sp, Modifier.weight(1f))
        listOf(-1, 1).forEach { d ->
            if (d == 1) Txt("$v", Page.ink, 15.sp, Modifier.width(32.dp), maxLines = 1, align = androidx.compose.ui.text.style.TextAlign.Center)
            val ok = (v + d) in range
            PressSurface({ if (ok) { onChange(v + d); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } }, Modifier.size(36.dp), shape = CircleShape, fill = Color.Transparent, pressScale = .88f, enabled = ok, label = if (d < 0) "少一点" else "多一点") {
                Txt(if (d < 0) "−" else "+", Page.ink.copy(alpha = if (ok) .86f else .3f), 18.sp)
            }
        }
    }
}

/** 某一家：Key、地址（停手就存）、这家自己的选项、去拿 Key、试搜一下、移除。 */
@Composable
internal fun SearchSourcePage(id: String, onBack: () -> Unit) {
    val s = WebSearch.source(id)
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var c by remember(id) { mutableStateOf(WebSearch.cfgOf(id)) }
    fun put(n: SearchCfg) { c = n; WebSearch.setCfg(id, n) }
    var q by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var hits by remember { mutableStateOf<List<Hit>?>(null) }
    var err by remember { mutableStateOf("") }
    Column(Modifier.widthIn(max = 560.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
        CardPageHead(s.name, s.sub, onBack = onBack)
        if (!s.free) {
            Label("API KEY")
            CardField(c.key, { put(c.copy(key = it.trim())) }, if (s.needUrl) "不需要可以空着" else if (id == "deepseek") "空着就用聊天里 DeepSeek 的 Key" else "贴进来，自动保存")
            val borrowed = if (id == "deepseek" && c.key.isBlank()) WebSearch.keyOf(id) else ""
            if (borrowed.isNotEmpty()) Row(Modifier.padding(start = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(warm, CircleShape))
                Spacer(Modifier.width(7.dp))
                Txt("正在用聊天里的 Key · sk-…${borrowed.takeLast(4)}", warm, 11.5.sp)
            }
            Label(if (s.needUrl) "地址" else "自定义地址（可选）")
            CardField(c.url, { put(c.copy(url = it.trim())) }, if (s.needUrl) "https://你的 searxng 地址" else "用官方地址就空着")
        }
        if (s.opts.isNotEmpty()) {
            Label(s.optName)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { s.opts.forEachIndexed { i, (name, _) -> ChoiceChip(name, c.opt == i) { put(c.copy(opt = i)) } } }
            if (s.optHint.isNotEmpty()) Txt(s.optHint, Page.muted.copy(alpha = .55f), 11.5.sp, Modifier.padding(start = 8.dp, top = 6.dp))
        }
        if (s.site.isNotEmpty()) Pressable({ InBrowser.open("https://${s.site}") }, label = "去 ${s.site} 拿 Key") { pr ->
            Txt("去 ${s.site.substringBefore('/')} 拿 Key ↗", warm.copy(alpha = if (pr) .7f else 1f), 12.5.sp, Modifier.padding(start = 8.dp, top = 12.dp))
        }
        Label("试搜一下")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { CardField(q, { q = it }, "比如：今天杭州天气") }
            Spacer(Modifier.width(8.dp))
            PressSurface(
                {
                    if (busy) return@PressSurface
                    busy = true; err = ""; hits = null
                    scope.launch {
                        runCatching { WebSearch.search(id, q.trim().ifEmpty { "今天杭州天气" }, minOf(WebSearch.max, 5)) }
                            .onSuccess { hits = it; if (it.isEmpty()) err = "没查到东西，换个词试试" }
                            .onFailure { err = it.message ?: "没连上" }
                        busy = false
                    }
                },
                Modifier.size(44.dp), shape = CircleShape, fill = warm, pressedFill = warm.copy(alpha = .85f), pressScale = .92f, label = "试搜",
            ) { if (busy) LoadRing(Skin.onAccent, 18.dp) else SvgIcon(play, Skin.onAccent, 16.dp, stroke = 1.6f) }
        }
        if (err.isNotEmpty()) Txt(err, Color(0xFFD79A94), 12.sp, Modifier.padding(start = 8.dp, top = 10.dp))
        hits?.forEach { h ->
            Column(Modifier.padding(top = 6.dp).fillMaxWidth().background(CardBlock, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
                Txt(h.title, Page.ink, 13.5.sp, maxLines = 2)
                Txt(h.url.removePrefix("https://").removePrefix("http://").substringBefore('/'), warm.copy(alpha = .8f), 11.sp, Modifier.padding(top = 2.dp), maxLines = 1)
                if (h.text.isNotBlank()) Txt(h.text, Page.muted, 12.sp, Modifier.padding(top = 4.dp), maxLines = 3, lineHeight = 19.sp)
            }
        }
        Pressable({ WebSearch.remove(id); onBack() }, label = "移除这一家") { pr ->
            Txt("移除这一家", Color(0xFFD79A94).copy(alpha = if (pr) .6f else 1f), 13.sp, Modifier.padding(start = 8.dp, top = 18.dp, bottom = 8.dp))
        }
    }
}
