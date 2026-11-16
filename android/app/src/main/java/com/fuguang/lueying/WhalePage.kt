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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.roundToInt

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
    androidx.activity.compose.BackHandler(enabled = Whale.editing) { Whale.edit(false) }
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
        // 口型跟着屏幕上打出来的字（shown），不跟网络到货（partial）：回复一口气到货时，字还在打她也还在说
        snapshotFlow { Triple(chat.busy, ceil(chat.shown).toInt(), chat.messages.size) }.collect { (busy, len, _) ->
            if (busy && !was) { seen = 0; Whale.think() }
            if (busy && len > seen) { Whale.talk(len - seen); seen = len }
            if (!busy && was) {
                chat.messages.lastOrNull()?.takeIf { it.role == "assistant" }?.let { Whale.replied(it.text) }
                WhaleTools.flush()
                seen = 0
            }
            was = busy
        }
    }
}

/**
 * 盖在她身上的一层：只有她占的那块接触摸（点、摸头、长按拖动），原样转给页面；别处照常归对话列表。
 * 放在标题栏和输入框之间那一块里，所以不会挡输入框。
 */
@Composable
internal fun WhaleTouch() {
    if (!Whale.onStage || Whale.editing || (!Whale.touch && !Whale.drag)) return
    var origin by remember { mutableStateOf(Offset.Zero) }
    val at = remember { IntArray(2) }
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
        Box(
            Modifier
                .layout { m, c ->
                    val r = Whale.rect()
                    val l = ((r?.get(0) ?: 0f) - origin.x).roundToInt().coerceIn(0, c.maxWidth)
                    val t = ((r?.get(1) ?: 0f) - origin.y).roundToInt().coerceIn(0, c.maxHeight)
                    val e = ((r?.get(2) ?: 0f) - origin.x).roundToInt().coerceIn(l, c.maxWidth)
                    val b = ((r?.get(3) ?: 0f) - origin.y).roundToInt().coerceIn(t, c.maxHeight)
                    at[0] = l; at[1] = t
                    val p = m.measure(Constraints.fixed(e - l, b - t))
                    layout(c.maxWidth, c.maxHeight) { p.place(l, t) }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val d = awaitFirstDown(requireUnconsumed = false)
                        d.consume()
                        val ox = origin.x + at[0]; val oy = origin.y + at[1]
                        Whale.ptr("down", ox + d.position.x, oy + d.position.y)
                        try {
                            while (true) {
                                val ch = awaitPointerEvent().changes.firstOrNull { it.id == d.id } ?: break
                                ch.consume()
                                if (!ch.pressed) break
                                Whale.ptr("move", ox + ch.position.x, oy + ch.position.y)
                            }
                        } finally {
                            Whale.ptr("up", 0f, 0f)
                        }
                    }
                },
        )
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
            Toggle("长按拖动", "按住她不放，拖到想放的地方，松手就留在那儿", Whale.drag) { Whale.drag = it; Whale.save() }
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
        androidx.activity.compose.BackHandler(enabled = Whale.dressing) { Whale.dress(false) }
        val shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
        // 面板铺满整个宽度、贴到屏幕底边、不透明（不再让下面的输入框从两头露出来、字透上来）；内容仍限宽居中
        Box(
            Modifier.fillMaxWidth()
                .onGloballyPositioned { Whale.sheet(it.positionInWindow().y) }
                .shadow(18.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = .10f), spotColor = Color.Black.copy(alpha = .14f))
                .background(Skin.sheet.copy(alpha = 1f), shape)
                .pointerInput(Unit) { detectTapGestures { } }
                .windowInsetsPadding(WindowInsets.navigationBars),
            contentAlignment = Alignment.TopCenter,
        ) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp).background(Skin.line, RoundedCornerShape(2.dp)))
            Spacer(Modifier.height(10.dp))
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
}

/**
 * 盖在上面、但不挡触摸：命中测试时让下面的兄弟层也收到同一次触摸（Compose 1.7+ 的 sharePointerInputWithSiblings）。
 * 她的 WebView 平时 dispatchTouchEvent 回 false，不会把触摸吃掉。
 */
internal fun Modifier.passTouches(): Modifier = this then PassTouches

private object PassTouches : ModifierNodeElement<PassTouchesNode>() {
    override fun create() = PassTouchesNode()
    override fun update(node: PassTouchesNode) {}
    override fun hashCode() = 7301
    override fun equals(other: Any?) = other === this
}

private class PassTouchesNode : Modifier.Node(), PointerInputModifierNode {
    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {}
    override fun onCancelPointerInput() {}
    override fun sharePointerInputWithSiblings() = true
}
