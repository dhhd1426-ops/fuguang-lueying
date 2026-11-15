package com.fuguang.lueying

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val WhaleTail = Ic("M12 20.5c0-4.6.6-7.9 2.2-10.3M12 20.5c0-4.6-.6-7.9-2.2-10.3M14.2 10.2c1.6-2.4 4.2-3.4 6.8-2.9-1 2.7-3.5 3.9-6.8 2.9zM9.8 10.2C8.2 7.8 5.6 6.8 3 7.3c1 2.7 3.5 3.9 6.8 2.9z")

private val Out = CubicBezierEasing(.2f, 0f, 0f, 1f)
private val MOODS = listOf("开心", "喜欢", "得意", "害羞", "温柔", "调皮", "难过", "生气", "紧张", "思考", "犯困", "惊讶")
private val FACES = listOf("笑", "吐舌头", "吐舌头眨眼")
private val OUTFIT = listOf("pan" to "钢盆", "glasses" to "黑框眼镜", "monocle" to "单片眼镜", "skirt" to "短裙", "shoes" to "脱鞋", "tail" to "无尾巴")
private val BODY = listOf(0f to "大号", .4f to "中号", 1f to "小号")
private val IDLE = listOf("关", "少", "适中", "多")

/** 聊天状态 → 她的反应。放在主页里，跟着 ChatState 走。 */
@Composable
internal fun WhaleHooks(chat: ChatState) {
    if (!Whale.active) return
    val empty = chat.messages.isEmpty() && !chat.busy
    LaunchedEffect(empty, Whale.ready) { Whale.chat(!empty) }
    LaunchedEffect(Unit) {
        var last = 0L
        snapshotFlow { chat.draft }.collect {
            val now = System.currentTimeMillis()
            if (it.isNotBlank() && now - last > 1200) { last = now; Whale.typing() }
        }
    }
    LaunchedEffect(Unit) {
        var seen = 0
        var was = false
        snapshotFlow { Triple(chat.busy, chat.partial.length, chat.messages.size) }.collect { (busy, len, _) ->
            if (busy && !was) { seen = 0; Whale.think() }
            if (busy && len > seen) { Whale.talk(len - seen); seen = len }
            if (!busy && was) {
                chat.messages.lastOrNull()?.takeIf { it.role == "assistant" }?.let { Whale.replied(it.text) }
                seen = 0
            }
            was = busy
        }
    }
}

/** 「我的 → 鲸鱼娘」 */
@Composable
internal fun WhalePage(onBack: () -> Unit, onPlace: () -> Unit, onDress: () -> Unit) {
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) Whale.import(u, scope) }
    Column(Modifier.widthIn(max = 560.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
        CardPageHead("鲸鱼娘", "大肥鱼 · Live2D", onBack = onBack)
        if (!Whale.hasModel) {
            Block {
                Txt("还没有模型", Page.ink, 15.sp)
                Txt("模型文件不随应用分发。选择你下载的 DeepSeek.zip，会解压到应用私有目录（约 60MB），之后不需要联网。", Page.muted, 12.5.sp, Modifier.padding(top = 4.dp), lineHeight = 20.sp)
                Spacer(Modifier.height(10.dp))
                Btn(if (Whale.importing) "导入中…" else "选择 DeepSeek.zip", !Whale.importing) { pick.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
                Note()
            }
        } else {
            Toggle("显示鲸鱼娘", "在主页陪你聊天", Whale.show) { Whale.show = it; Whale.save() }
            Toggle("情绪表情", "按她回复的内容换表情和动作", Whale.mood) { Whale.mood = it; Whale.save() }
            Toggle("点击互动", "点头、点身体、点尾巴都有反应", Whale.touch) { Whale.touch = it; Whale.save() }
            Block {
                Txt("待机动作", Page.ink, 15.sp)
                Txt("没聊天时她自己做的小动作", Page.muted, 12.sp, Modifier.padding(top = 3.dp))
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IDLE.forEachIndexed { i, s -> Chip(s, Whale.idle == i) { Whale.idle = i; Whale.save() } }
                }
            }
            Go("位置与大小", "回到主页拖动她，双指缩放", Whale.show, onPlace)
            Go("换装间", "装扮、体型，试动作和表情", Whale.show, onDress)
            Block {
                Txt("模型文件", Page.ink, 15.sp)
                val state = when {
                    Whale.error.isNotEmpty() -> Whale.error
                    Whale.prepping -> Whale.phase.ifEmpty { "正在整理贴图…" }
                    !Whale.show -> "已导入，存在应用私有目录"
                    Whale.ready -> Whale.phase.ifEmpty { "已就绪" }
                    else -> "加载中" + if (Whale.phase.isNotEmpty()) "：${Whale.phase}" else "…"
                }
                Txt(state, if (Whale.error.isNotEmpty()) Page.accent else Page.muted, 12.sp, Modifier.padding(top = 3.dp), lineHeight = 19.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn(if (Whale.importing) "导入中…" else "重新导入", !Whale.importing) { pick.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
                    Btn("删除模型", !Whale.importing) { Whale.remove() }
                }
                Note()
            }
        }
        Txt(
            "原画及拆分：神宫凉子　Live2D 建模：霓虹猫_NeonNeko\n模型仅供个人使用，不随本应用分发。\n蓝鲸动作与舞蹈：本仓库作者自制。\n使用 Live2D Cubism Core（Live2D Inc.）、pixi.js、pixi-live2d-display、FULASKA（均为 MIT）。",
            Page.muted, 11.5.sp, Modifier.padding(horizontal = 8.dp, vertical = 16.dp), lineHeight = 20.sp,
        )
    }
}

@Composable
private fun Note() {
    if (Whale.importMsg.isNotEmpty()) Txt(Whale.importMsg, if (Whale.importing) Page.muted else Page.accent, 12.sp, Modifier.padding(top = 8.dp))
}

@Composable
private fun Block(content: @Composable () -> Unit) {
    Column(Modifier.padding(top = 6.dp).fillMaxWidth().background(CardBlock, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp)) { content() }
}

@Composable
private fun Toggle(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.padding(top = 6.dp).fillMaxWidth().background(CardBlock, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Txt(title, Page.ink, 15.sp)
            Txt(sub, Page.muted, 12.sp, Modifier.padding(top = 3.dp), maxLines = 2)
        }
        Spacer(Modifier.width(10.dp))
        CardSwitch(on, title, onChange)
    }
}

@Composable
private fun Go(title: String, sub: String, enabled: Boolean, onClick: () -> Unit) {
    PressSurface(onClick, Modifier.padding(top = 6.dp).fillMaxWidth(), shape = RoundedCornerShape(16.dp), pressScale = .985f, enabled = enabled, label = title) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Txt(title, if (enabled) Page.ink else Page.muted, 15.sp)
                Txt(if (enabled) sub else "先打开「显示鲸鱼娘」", Page.muted, 12.sp, Modifier.padding(top = 3.dp))
            }
            SvgIcon(Ic("M9.5 6l6 6-6 6"), Page.muted.copy(alpha = .6f), 15.dp, stroke = 1.8f)
        }
    }
}

@Composable
private fun Btn(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    PressSurface(onClick, enabled = enabled, fill = Page.ink.copy(alpha = if (enabled) .9f else .3f), pressedFill = Page.ink.copy(alpha = .75f), label = text) {
        Txt(text, Skin.onInk, 13.5.sp, Modifier.padding(horizontal = 16.dp, vertical = 9.dp), maxLines = 1)
    }
}

@Composable
private fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    PressSurface(onClick, fill = if (on) Page.ink.copy(alpha = .9f) else CardWell, pressedFill = if (on) Page.ink.copy(alpha = .75f) else CardBlockPress, label = text) {
        Txt(text, if (on) Skin.onInk else Page.ink, 13.sp, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), maxLines = 1)
    }
}

/** 换装间：主页上的底部面板，她站到上半屏实时预览。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BoxScope.WhaleDressSheet() {
    AnimatedVisibility(
        Whale.dressing && Whale.active,
        Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically(tween(320, easing = Out)) { it } + fadeIn(tween(200)),
        exit = slideOutVertically(tween(220, easing = Out)) { it } + fadeOut(tween(160)),
    ) {
        var tab by remember { mutableIntStateOf(0) }
        var moves by remember { mutableStateOf(listOf<String>()) }
        LaunchedEffect(Whale.ready) { Whale.routines { moves = it } }
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth()
                .background(Skin.page.copy(alpha = .96f), RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt("换装间", Page.ink, 17.sp, family = Serif, modifier = Modifier.weight(1f))
                Btn("完成") { Whale.dress(false) }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("装扮", "体型", "试动作", "试表情").forEachIndexed { i, s -> Chip(s, tab == i) { tab = i } }
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(150.dp).verticalScroll(rememberScrollState())) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    when (tab) {
                        0 -> OUTFIT.forEach { (k, s) ->
                            Chip(s, k in Whale.outfit) {
                                Whale.outfit = if (k in Whale.outfit) Whale.outfit - k else Whale.outfit + k
                                Whale.save()
                            }
                        }
                        1 -> BODY.forEach { (v, s) -> Chip(s, Whale.body == v) { Whale.body = v; Whale.save() } }
                        2 -> {
                            Chip("跳舞", false) { Whale.routine("@dance") }
                            moves.forEach { m -> Chip(m, false) { Whale.routine(m) } }
                            if (moves.isEmpty()) Txt(if (Whale.ready) "" else "她还在加载…", Page.muted, 12.sp)
                        }
                        else -> {
                            MOODS.forEach { m -> Chip(m, false) { Whale.moodOn(m) } }
                            FACES.forEach { f -> Chip(f, false) { Whale.face(f) } }
                        }
                    }
                }
            }
        }
    }
}
