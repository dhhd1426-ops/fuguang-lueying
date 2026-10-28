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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal val skillWarm: Color get() = Skin.accent
internal val skillInk: Color get() = Skin.onAccent
internal val skillBad = Color(0xFFD79A94)
private val tints = listOf(0xFFE9B48E, 0xFFA9B6CC, 0xFF9FB79A, 0xFFC9BC97, 0xFFB3A6D9, 0xFFD98A6A)
internal val skillIcon = Ic("M6 4.5h9l3 3v12H6ZM15 4.5v3h3M12 10.2l.9 1.9 2 .3-1.45 1.4.35 2-1.8-.95-1.8.95.35-2-1.45-1.4 2-.3Z")
private val plus = Ic("M12 5v14M5 12h14")
private val findIc = Ic("M20 20l-4.5-4.5", circles = listOf(floatArrayOf(11f, 11f, 6f)))

/** 移除后的撤销：哪一个、原来排第几、哪位助理的。 */
internal object SkillUndo { var last by mutableStateOf<Triple<Skill, Int, String>?>(null) }

/** 技能的小方块：按名字挑一个底色，写第一个字。 */
@Composable
internal fun SkillTile(id: String, title: String, size: Dp) {
    val c = Color(tints[Math.floorMod(id.hashCode(), tints.size)])
    Box(Modifier.size(size).background(c, RoundedCornerShape(size * .29f)), contentAlignment = Alignment.Center) {
        Txt(title.take(1).ifEmpty { "技" }, skillInk, (size.value * .44f).sp, family = Serif, maxLines = 1)
    }
}

@Composable
private fun Label(text: String, count: Int? = null) = Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 16.dp, bottom = 8.dp)) {
    Txt(text, Page.muted, 11.sp, Modifier.weight(1f), spacing = ls(2.75f, 11f))
    if (count != null) Txt("$count", Page.muted.copy(alpha = .6f), 11.sp)
}

@Composable
private fun SwitchRow(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.padding(top = 6.dp).fillMaxWidth().background(CardBlock, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Txt(title, Page.ink, 15.sp)
            Txt(sub, Page.muted, 12.sp, Modifier.padding(top = 3.dp), maxLines = 2)
        }
        Spacer(Modifier.width(10.dp))
        CardSwitch(on, title, onChange)
    }
}

/** 「功能 → 技能」：两个开关；你装的、她自己装的；从 GitHub 添加（原地展开，贴链接或搜词）。 */
@Composable
internal fun SkillsPage(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val all = Skills.of()
    var adding by remember { mutableStateOf(false) }
    Column(Modifier.widthIn(max = 560.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
        CardPageHead("技能", onBack = onBack)
        SwitchRow("让她用技能", "用得上时她自己翻开看怎么做", Skills.on) { Skills.on = it; Skills.save() }
        SwitchRow("她能自己找来装", if (Skills.self) "从 GitHub 上找 · 装之前先问你" else "只用你装的", Skills.self) { Skills.self = it; Skills.save() }
        val mine = all.filter { it.by != "her" }; val hers = all.filter { it.by == "her" }
        Label("你装的", mine.size)
        Column(Modifier.fillMaxWidth().background(CardBlock, RoundedCornerShape(18.dp)).padding(6.dp)) {
            mine.forEach { SkillRow(it, onOpen) }
            Pressable({ adding = !adding }, Modifier.fillMaxWidth(), label = "从 GitHub 添加") { pr ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    SvgIcon(plus, if (pr) Page.ink else Page.muted, 16.dp, stroke = 1.7f)
                    Spacer(Modifier.width(10.dp))
                    Txt("从 GitHub 添加", if (pr) Page.ink else Page.muted, 13.5.sp)
                }
            }
            AnimatedVisibility(
                adding,
                enter = expandVertically(spring(dampingRatio = .85f, stiffness = 420f), expandFrom = Alignment.Top) + fadeIn(tween(200)),
                exit = shrinkVertically(tween(220, easing = Curves.standard), shrinkTowards = Alignment.Top) + fadeOut(tween(140)),
            ) { AddSkill { adding = false; haptic.performHapticFeedback(HapticFeedbackType.LongPress) } }
        }
        if (hers.isNotEmpty()) {
            Label("她自己装的", hers.size)
            Column(Modifier.fillMaxWidth().background(CardBlock, RoundedCornerShape(18.dp)).padding(6.dp)) { hers.forEach { SkillRow(it, onOpen) } }
        }
        SkillUndo.last?.let { (s, i, pid) ->
            LaunchedEffect(s) { delay(4500); if (SkillUndo.last?.first == s) SkillUndo.last = null }
            Row(Modifier.padding(top = 12.dp).fillMaxWidth().background(Skin.pop, RoundedCornerShape(14.dp)).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt("移除了「${s.title}」", Page.ink, 13.sp, Modifier.weight(1f), maxLines = 1)
                PressSurface({ Skills.restore(s, i, pid); SkillUndo.last = null }, fill = Color.Transparent, pressScale = .94f, label = "撤销") {
                    Txt("撤销", skillWarm, 13.sp, Modifier.padding(horizontal = 12.dp, vertical = 11.dp))
                }
            }
        }
        Txt("只读说明和文字参考，脚本在手机上跑不了。每位助理各自一份。", Page.muted.copy(alpha = .55f), 11.5.sp, Modifier.padding(start = 8.dp, top = 12.dp, bottom = 8.dp), lineHeight = 20.sp)
    }
}

@Composable
private fun SkillRow(s: Skill, onOpen: (String) -> Unit) {
    val dim = if (s.on) 1f else .45f
    Row(verticalAlignment = Alignment.CenterVertically) {
        PressSurface({ onOpen(s.id) }, Modifier.weight(1f), shape = RoundedCornerShape(14.dp), fill = Color.Transparent, pressScale = .98f, label = s.title) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.graphicsLayer { alpha = dim }) { SkillTile(s.id, s.title, 34.dp) }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f).graphicsLayer { alpha = dim }) {
                    Txt(s.title, Page.ink, 14.5.sp, maxLines = 1)
                    Txt(s.shown.ifBlank { s.repo }, Page.muted, 11.5.sp, Modifier.padding(top = 2.dp), maxLines = 1)
                }
            }
        }
        Box(Modifier.padding(end = 6.dp)) { CardSwitch(s.on, "用「${s.title}」") { Skills.update(s.copy(on = it)) } }
    }
}


/** 贴链接或搜一个词 → 找到的一个个浮出，点「装上」。 */
@Composable
private fun AddSkill(onAdded: () -> Unit) {
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var hits by remember { mutableStateOf<List<SkillCand>?>(null) }
    var err by remember { mutableStateOf("") }
    Column(Modifier.padding(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { CardField(q, { q = it }, "贴链接，或搜英文词：poster") }
            Spacer(Modifier.width(8.dp))
            PressSurface(
                {
                    if (busy) return@PressSurface
                    val t = q.trim().ifEmpty { "poster" }
                    busy = true; err = ""; hits = null
                    scope.launch {
                        runCatching { if (SkillHub.looksLikeLink(t)) SkillHub.fromLink(t) else SkillHub.find(t) }
                            .onSuccess { hits = it; if (it.isEmpty()) err = "没找到带 SKILL.md 的技能，换个词试试" }
                            .onFailure { err = it.message ?: "没连上 GitHub" }
                        busy = false
                    }
                },
                Modifier.size(42.dp), shape = CircleShape, fill = skillWarm, pressedFill = skillWarm.copy(alpha = .85f), pressScale = .92f, label = "找",
            ) { if (busy) LoadRing(skillInk, 17.dp) else SvgIcon(findIc, skillInk, 17.dp, stroke = 1.9f) }
        }
        if (err.isNotEmpty()) Txt(err, skillBad, 12.sp, Modifier.padding(start = 6.dp, top = 8.dp))
        hits?.let { l -> if (l.isNotEmpty()) Txt("在 GitHub 上找到 ${l.size} 个", Page.muted.copy(alpha = .6f), 11.5.sp, Modifier.padding(start = 6.dp, top = 10.dp, bottom = 4.dp)) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            hits?.forEachIndexed { i, c -> key(c.repo + c.path) { HitRow(c, i, onAdded) } }
        }
    }
}

@Composable
private fun HitRow(c: SkillCand, i: Int, onAdded: () -> Unit) {
    val rise = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(50L * i); rise.animateTo(1f, tween(340, easing = Curves.decel)) }
    Row(Modifier.graphicsLayer { alpha = rise.value; translationY = (1f - rise.value) * 8.dp.toPx() }.fillMaxWidth().background(veil(.035f), RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        SkillTile(c.id, c.name.ifBlank { c.id }, 30.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Txt(c.name.ifBlank { c.id }, Page.ink, 13.5.sp, maxLines = 1)
            Txt(c.repo + (if (c.stars.isNotEmpty()) " · ★ ${c.stars}" else ""), Page.muted, 11.sp, Modifier.padding(top = 2.dp), maxLines = 1)
            if (c.desc.isNotBlank()) Txt(c.desc, Page.muted.copy(alpha = .7f), 11.sp, Modifier.padding(top = 3.dp), maxLines = 2, lineHeight = 17.sp)
        }
        Spacer(Modifier.width(8.dp))
        GetButton("装上", Skills.has(c), { Skills.install(c, "you") }, onAdded)
    }
}

/**
 * 「装上」：按下下沉 → 转圈 → 一笔画出 ✓ → 停一拍迸出杏色光点 + 轻震；失败写原因、抖一下。
 * [done] 一开始就装过了：直接是 ✓。
 */
@Composable
internal fun GetButton(text: String, done: Boolean, work: suspend () -> Unit, onDone: () -> Unit, wide: Boolean = false) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var state by remember { mutableStateOf(if (done) 2 else 0) }   // 0 等着、1 在装、2 装好、3 没装上
    var burst by remember { mutableStateOf<Offset?>(null) }
    var center by remember { mutableStateOf(Offset.Zero) }
    var err by remember { mutableStateOf("") }
    var shake by remember { mutableStateOf(0) }
    Column(horizontalAlignment = Alignment.End) {
        Box(contentAlignment = Alignment.Center) {
            CardButton(
                state, if (state == 2) BtnTone.Primary else BtnTone.Ok, text,
                {
                    if (state == 1 || state == 2) return@CardButton
                    state = 1; err = ""
                    scope.launch {
                        runCatching { work() }
                            .onSuccess {
                                state = 2; delay(420); burst = center
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                delay(700); onDone()
                            }
                            .onFailure { err = it.message ?: "没装上"; state = 3; shake++ }
                    }
                },
                Modifier.onSizeChanged { center = Offset(it.width / 2f, it.height / 2f) }.then(if (wide) Modifier.widthIn(min = 92.dp) else Modifier.widthIn(min = 64.dp)),
                busy = state == 1, shake = shake,
            ) { s, ink ->
                Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    when (s) {
                        1 -> LoadRing(ink, 15.dp)
                        2 -> DrawnCheck(ink, 17.dp)
                        3 -> BtnLabel("再试", ink)
                        else -> BtnLabel(text, ink)
                    }
                }
            }
            burst?.let { at -> Burst(at, Modifier.matchParentSize()) { burst = null } }
        }
        if (err.isNotEmpty()) Txt(err, skillBad, 11.sp, Modifier.padding(top = 4.dp).widthIn(max = 160.dp), maxLines = 2)
    }
}
