package com.fuguang.lueying

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val chevron = Ic("M9.5 6l6 6-6 6")
private val sync = Ic("M19 8a7.5 7.5 0 0 0-13.4-1.6M5 4v3h3M5 16a7.5 7.5 0 0 0 13.4 1.6M19 20v-3h-3")

private fun day(t: Long): String {
    val d = System.currentTimeMillis() - t
    return when {
        d < 60_000 -> "刚刚"
        d < 86_400_000 && SimpleDateFormat("d", Locale.CHINA).format(Date(t)) == SimpleDateFormat("d", Locale.CHINA).format(Date()) -> "今天"
        else -> SimpleDateFormat("M月d日", Locale.CHINA).format(Date(t))
    }
}

@Composable
private fun Fact(text: String, warm: Boolean = false) =
    Txt(text, if (warm) skillWarm else Page.muted, 11.5.sp, Modifier.background(if (warm) skillWarm.copy(alpha = .1f) else CardBlock, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp), maxLines = 1)

/** 一个技能：谁什么时候装的、用过几次、什么时候用、她翻开看到的、在用、检查更新、移除。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SkillDetailPage(id: String, onBack: () -> Unit) {
    val s = Skills.get(id)
    if (s == null) { LaunchedEffect(id) { onBack() }; return }
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var upd by remember { mutableIntStateOf(0) }   // 0 检查更新、1 在看、2 已是最新、3 更新好了、4 失败
    var shake by remember { mutableIntStateOf(0) }
    var updErr by remember { mutableStateOf("") }
    Column(Modifier.widthIn(max = 560.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
        CardPageHead("", onBack = onBack)
        Column(Modifier.padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SkillTile(s.id, s.title, 52.dp)
            Txt(s.title, Page.ink, 21.sp, Modifier.padding(top = 2.dp), spacing = ls(2f, 21f), family = Serif, maxLines = 2)
            Pressable({ runCatching { uri.openUri("https://github.com/${s.repo}" + if (s.path.isEmpty()) "" else "/tree/HEAD/${s.path}") } }, label = "在 GitHub 上看") { pr ->
                Txt("${s.repo}${if (s.path.isEmpty()) "" else "/" + s.path} ↗", Page.muted.copy(alpha = if (pr) .4f else .6f), 11.5.sp, maxLines = 2)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Fact("${if (s.by == "her") "她" else "你"} ${day(s.at)} 装的", warm = s.by == "her")
                Fact(if (s.used > 0) "用过 ${s.used} 次" else "还没用过")
                Fact("${s.files} 个文件")
                if (s.script) Fact("脚本用不了")
            }
        }
        if (s.shown.isNotBlank()) {
            Txt("什么时候用", Page.muted, 11.sp, Modifier.padding(start = 8.dp, top = 18.dp, bottom = 8.dp), spacing = ls(2.75f, 11f))
            Txt(s.shown, Page.ink.copy(alpha = .86f), 13.sp, Modifier.fillMaxWidth().background(CardBlock, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp), lineHeight = 22.sp)
        }
        Column(Modifier.padding(top = 6.dp).fillMaxWidth().background(CardBlock, RoundedCornerShape(16.dp))) {
            val turn by animateFloatAsState(if (open) 90f else 0f, spring(dampingRatio = .8f, stiffness = 500f), label = "turn")
            Pressable({ open = !open }, Modifier.fillMaxWidth(), label = if (open) "收起说明" else "看她翻开看到的") { pr ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt("她翻开看到的", Page.ink.copy(alpha = if (pr) .7f else .86f), 13.sp, Modifier.weight(1f))
                    SvgIcon(chevron, Page.muted.copy(alpha = .6f), 15.dp, stroke = 1.8f, modifier = Modifier.graphicsLayer { rotationZ = turn })
                }
            }
            AnimatedVisibility(
                open,
                enter = expandVertically(spring(dampingRatio = .85f, stiffness = 380f), expandFrom = Alignment.Top) + fadeIn(tween(200)),
                exit = shrinkVertically(tween(220, easing = Curves.standard), shrinkTowards = Alignment.Top) + fadeOut(tween(140)),
            ) {
                Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                    val body = remember(s.id, upd) { Skills.body(s) }
                    SelectionContainer { Txt(body.take(8000).ifBlank { "（说明文件不见了，试试检查更新）" } + if (body.length > 8000) "\n……" else "", Page.muted, 12.sp, lineHeight = 20.sp) }
                    val files = remember(s.id, upd) { Skills.fileNames(s) }
                    if (files.isNotEmpty()) Txt("附带：" + files.take(12).joinToString("、") + if (files.size > 12) " 等 ${files.size} 个" else "", Page.muted.copy(alpha = .55f), 11.sp, Modifier.padding(top = 10.dp), lineHeight = 18.sp)
                }
            }
        }
        Row(Modifier.padding(top = 6.dp).fillMaxWidth().background(CardBlock, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt("在用", Page.ink, 15.sp, Modifier.weight(1f))
            CardSwitch(s.on, "用「${s.title}」") { Skills.update(s.copy(on = it)) }
        }
        Row(Modifier.padding(top = 10.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CardButton(
                upd, if (upd == 4) BtnTone.Bad else BtnTone.Quiet, "检查更新",
                {
                    if (upd == 1) return@CardButton
                    upd = 1; updErr = ""
                    scope.launch {
                        runCatching { Skills.refresh(s) }
                            .onSuccess { changed -> upd = if (changed) 3 else 2 }
                            .onFailure { updErr = it.message ?: "没连上 GitHub"; upd = 4; shake++ }
                        delay(2200); if (upd != 1) upd = 0
                    }
                },
                Modifier.weight(1f), busy = upd == 1, shake = shake,
            ) { st, ink ->
                when (st) {
                    1 -> BtnLabel("在看", ink) { LoadRing(ink, 14.dp) }
                    2 -> BtnLabel("已是最新", ink) { DrawnCheck(ink, 15.dp) }
                    3 -> BtnLabel("更新好了", ink) { DrawnCheck(ink, 15.dp) }
                    4 -> BtnLabel("没连上", ink)
                    else -> BtnLabel("检查更新", ink) { SvgIcon(sync, ink, 15.dp, stroke = 1.7f) }
                }
            }
            CardButton(0, BtnTone.Bad, "移除", {
                val pid = Memory.active.id
                SkillUndo.last = Triple(s, Skills.remove(s, pid), pid)
                onBack()
            }, Modifier.weight(1f)) { _, ink -> BtnLabel("移除", ink) }
        }
        if (updErr.isNotEmpty()) Txt(updErr, skillBad, 12.sp, Modifier.padding(start = 8.dp, top = 8.dp))
        Spacer(Modifier.height(10.dp))
    }
}
