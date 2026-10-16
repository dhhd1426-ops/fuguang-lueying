package com.fuguang.lueying

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * 设定条目（提示词页下半）：一行一条，点开在原地展开编辑，再点收回。
 * 改动停手 0.4 秒自动存。
 */

private val warm = css("#e9b48e")
private val cardFill = rgba(30, 34, 42, .42f)
private val field = rgba(0, 0, 0, .22f)

@Composable
fun EntriesSection(p: Persona, blur: Dp) {
    var open by remember(p.id) { mutableStateOf<String?>(null) }
    SectionLabel("设 定 条 目") {
        Pressable({ open = Entries.create(p).id }, label = "添加条目") { pr ->
            Txt("＋ 添加", warm, 12.5.sp, Modifier.background(rgba(233, 180, 142, if (pr) .2f else .1f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 7.dp))
        }
    }
    if (p.entries.isEmpty()) {
        Txt(
            "一段她读到的话，你定什么时候出现、放在哪。比如「提到米饭时，她记得你们约好不糟蹋米饭」。放得离最后一句越近，她越当回事。",
            Page.muted, 12.5.sp, Modifier.padding(horizontal = 6.dp), lineHeight = 20.sp,
        )
        return
    }
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp), blur, fill = cardFill).animateContentSize(spring(.9f, 500f))) {
        p.entries.forEachIndexed { i, e ->
            if (i > 0) Box(Modifier.padding(horizontal = 18.dp).fillMaxWidth().height(1.dp).background(rgba(255, 255, 255, .06f)))
            EntryRow(e, open == e.id) { open = if (open == e.id) null else e.id }
            AnimatedVisibility(open == e.id, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                EntryEditor(p, e) { open = null }
            }
        }
    }
}

@Composable
private fun EntryRow(e: Entry, open: Boolean, onTap: () -> Unit) {
    Pressable(onTap, Modifier.fillMaxWidth(), label = e.name.ifBlank { "未命名条目" }) { pr ->
        Row(
            Modifier.fillMaxWidth().background(if (pr || open) rgba(255, 255, 255, .04f) else Color.Transparent).padding(horizontal = 18.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).background(if (e.on) warm else rgba(255, 255, 255, .2f), CircleShape))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Txt(e.name.ifBlank { "未命名条目" }, if (e.on) Page.ink else Page.muted, 15.sp, maxLines = 1, family = Serif)
                Spacer(Modifier.height(2.dp))
                Txt(e.summary, Page.muted, 12.sp, maxLines = 1)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryEditor(p: Persona, start: Entry, onClose: () -> Unit) {
    var e by remember(start.id) { mutableStateOf(start) }
    LaunchedEffect(e) { if (e != start) { kotlinx.coroutines.delay(400); Entries.update(p, e) } }
    Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 18.dp)) {
        Label("名字（只有你看得到）")
        Input(e.name, "比如：米饭的规矩", single = true) { e = e.copy(name = it.take(24)) }
        Label("她读到的")
        Input(e.body, "用「你…」来写，比如：你们约好了谁都不许糟蹋米饭", minH = 84.dp) { e = e.copy(body = it.take(1200)) }
        val hits = Identity.found(e.body)
        if (hits.isNotEmpty()) Txt("「${hits.joinToString("、")}」会让她觉得自己在演", css("#e39a8f"), 12.sp, Modifier.padding(top = 6.dp))
        Label("什么时候出现")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceChip("一直在", e.always) { e = e.copy(always = true) }
            ChoiceChip("提到这些词时", !e.always) { e = e.copy(always = false) }
        }
        if (!e.always) {
            Spacer(Modifier.height(8.dp))
            Input(e.keys, "米饭, 剩饭, 吃饭", single = true) { e = e.copy(keys = it.take(200)) }
            Txt("往回看最近 4 句，有一个词出现就带上。", Page.muted, 11.5.sp, Modifier.padding(top = 6.dp))
        }
        Label("放在哪")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceChip("她是谁之前", e.pos == "before") { e = e.copy(pos = "before") }
            ChoiceChip("她是谁之后", e.pos == "after") { e = e.copy(pos = "after") }
            ChoiceChip("对话开头", e.pos == "top") { e = e.copy(pos = "top") }
            ChoiceChip("对话里", e.pos == "depth") { e = e.copy(pos = "depth") }
        }
        if (e.pos == "depth") {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { FeelSlider(e.depth.toFloat(), 0f, 8f, 1f, ticks = 9, onDone = {}) { e = e.copy(depth = it.toInt()) } }
                Spacer(Modifier.width(12.dp))
                Txt(if (e.depth == 0) "最后" else "倒数第 ${e.depth} 句", warm, 15.sp, family = Serif)
            }
            DepthStrip(e.depth, e.voice == "self")
        }
        Txt(placeNote(e), Page.muted, 11.5.sp, Modifier.padding(top = 8.dp), lineHeight = 18.sp)
        Label("用谁的口吻")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceChip("旁白", e.voice == "aside") { e = e.copy(voice = "aside") }
            ChoiceChip("她心里想的", e.voice == "self") { e = e.copy(voice = "self") }
        }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChoiceChip(if (e.on) "开着" else "关着", e.on) { e = e.copy(on = !e.on) }
            Spacer(Modifier.weight(1f))
            Pressable({ Entries.remove(p, e); onClose() }, label = "删掉这条") { pr ->
                Txt("删掉", css("#e39a8f"), 13.sp, Modifier.background(rgba(227, 154, 143, if (pr) .18f else .08f), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 7.dp))
            }
        }
    }
}

private fun placeNote(e: Entry): String = when (e.pos) {
    "before" -> if (e.always) "放在她是谁前面，每一轮都在。" else "按词出现的条目会放到对话开头，免得开头每轮都变。"
    "after" -> if (e.always) "紧跟在她是谁后面，每一轮都在。" else "按词出现的条目会放到对话开头，免得开头每轮都变。"
    "top" -> "贴在这段对话的第一句前面。"
    else -> if (e.depth == 0) "贴在最后一句后面，她最后读到，最当回事。" else "贴在倒数第 ${e.depth} 句前面。越靠近最后一句，她越当回事。"
}

/** 一小条示意：最近 5 句里，条目落在哪。 */
@Composable
private fun DepthStrip(depth: Int, self: Boolean) {
    val roles = listOf("你", "她", "你", "她", "你")
    Column(Modifier.fillMaxWidth().padding(top = 10.dp).animateContentSize(spring(.85f, 600f)), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        @Composable fun hit() = Txt(
            if (self) "她心里想的" else "旁白", warm, 11.sp,
            Modifier.fillMaxWidth().background(rgba(233, 180, 142, .14f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
        )
        roles.forEachIndexed { i, r ->
            if (depth == roles.size - i) hit()
            val mine = r == "你"
            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                Txt(
                    if (i == roles.size - 1) "最后一句" else r, Page.muted, 10.5.sp,
                    Modifier.fillMaxWidth(.66f).background(rgba(255, 255, 255, if (mine) .07f else .04f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        if (depth == 0) hit()
        if (depth > roles.size) Txt("（更早的地方）", Page.muted, 10.5.sp)
    }
}

@Composable
private fun Label(t: String) = Txt(t, Page.muted, 11.5.sp, Modifier.padding(top = 16.dp, bottom = 8.dp), spacing = ls(1.5f, 11.5f))

@Composable
private fun Input(value: String, hint: String, single: Boolean = false, minH: Dp = 0.dp, onChange: (String) -> Unit) {
    BasicTextField(
        value = value, onValueChange = onChange, singleLine = single,
        textStyle = TextStyle(color = Page.ink, fontSize = 14.5.sp, lineHeight = 23.sp),
        cursorBrush = SolidColor(warm),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().heightIn(min = minH).background(field, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp)) {
                if (value.isEmpty()) Txt(hint, Page.muted.copy(alpha = .55f), 14.sp, maxLines = if (single) 1 else 3)
                inner()
            }
        },
    )
}
