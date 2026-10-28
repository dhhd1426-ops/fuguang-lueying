package com.fuguang.lueying

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 对话的菜单（第 327 版，原型 StUfZknX1Xjdadg3p6ykyX）：
 * - 聊天页右上角 ⋮：顶上一行灰字是对话标题，下面分享 / 重命名 / 收藏 / 删除，从 ⋮ 下面长出来，底下压一层淡暗。
 * - 侧栏长按一条对话：那一行变灰色块，菜单贴在它下面（下面放不下翻到上面），重命名 / 置顶 / 删除，不压暗。
 * 收藏和置顶是同一个标记（Conv.pinned），收藏的对话在侧栏「已置顶」里。
 * 点菜单外面、按返回都关；进 180ms 减速，出 140ms 加速。选一项先暗一下（90ms）再收起去做。
 * 重命名、删除确认是中间弹出的卡片；删了 4 秒内能撤销。都画在聊天页最上层（[ConvMenuLayer]）。
 */
object ConvMenu {
    /** 菜单开着的是哪段、从哪里出来。 */
    var conv by mutableStateOf<Conv?>(null)
    var side by mutableStateOf(false)
    var anchor by mutableStateOf(Rect.Zero)
    /** 侧栏里按住的那一行（菜单、重命名、删除弹层开着时一直亮着）。 */
    var held by mutableStateOf<String?>(null)
    var renaming by mutableStateOf<Conv?>(null)
    var deleting by mutableStateOf<Conv?>(null)
    /** ⋮ 的位置。 */
    var moreAt = Rect.Zero

    fun openTop(c: Conv) { side = false; anchor = moreAt; conv = c }
    fun openSide(c: Conv, at: Rect) { side = true; anchor = at; held = c.id; conv = c }
    fun close() { conv = null; if (renaming == null && deleting == null) held = null }
}

private val shareIc = Ic("M12 15V4M8 8l4-4 4 4M5 12v6.5A1.5 1.5 0 0 0 6.5 20h11a1.5 1.5 0 0 0 1.5-1.5V12")
private val renameIc = Ic("M14.5 5.5l4 4M5 19l1-4.5L15.5 5a1.4 1.4 0 0 1 2 0l1.5 1.5a1.4 1.4 0 0 1 0 2L9.5 18z")
private const val STAR = "M12 3.8l2.5 5.1 5.6.8-4 3.9.9 5.6L12 16.6l-5 2.6.9-5.6-4-3.9 5.6-.8z"
private val starIc = Ic(STAR)
private val pinIc = Ic("M14.5 4l5.5 5.5-2.5.8-3.3 3.3-.6 3.9L7.5 11.4l3.9-.6 3.3-3.3zM9.5 14.5L4.5 19.5")
private val trashIc = Ic("M4.5 7h15M9.5 7V5h5v2M6.5 7l1 12.5h9l1-12.5M10 11v5M14 11v5")
private val clearIc = Ic("M7 7l10 10M17 7L7 17")
private val danger get() = if (Skin.light) Color(0xFFD0564F) else Color(0xFFE8807A)

/** 右上角 ⋮：这段对话里有话了才出现，从新对话按钮右边让出位置；菜单开着时保持按下的底色。 */
@Composable
internal fun MoreButton(conv: Conv?, compact: Boolean) {
    val shown by animateFloatAsState(if (conv != null) 1f else 0f, tween(240, easing = Curves.standard), label = "more")
    if (shown <= 0.01f) return
    val round = if (compact) 40.dp else 46.dp
    val open = ConvMenu.conv != null && !ConvMenu.side
    Box(Modifier.width(round * shown).height(round).graphicsLayer { alpha = shown }, contentAlignment = Alignment.Center) {
        Pressable({ conv?.let { ConvMenu.openTop(it) } }, Modifier.size(round).onGloballyPositioned { ConvMenu.moreAt = it.boundsInRoot() }, label = "更多") { pressed ->
            val p by animateFloatAsState(if (pressed || open) 1f else 0f, if (pressed) tween(80) else tween(220, easing = Curves.standard), label = "moreP")
            Box(Modifier.size(round).graphicsLayer { alpha = p; val s = .7f + .3f * p; scaleX = s; scaleY = s }.background(veil(.1f), CircleShape))
            Canvas(Modifier.size(22.dp).graphicsLayer { val s = 1f - .08f * (if (pressed) 1f else 0f); scaleX = s; scaleY = s }) {
                val c = Page.ink.copy(alpha = .86f); val r = 1.75.dp.toPx()
                listOf(.23f, .5f, .77f).forEach { y -> drawCircle(c, r, Offset(size.width / 2, size.height * y)) }
            }
        }
    }
}

/** 聊天页最上层：菜单、重命名、删除确认、撤销提示。 */
@Composable
internal fun ConvMenuLayer(history: ConvStore) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var undo by remember { mutableStateOf<Conv?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var wasCurrent by remember { mutableStateOf(false) }
    var origin by remember { mutableStateOf(Offset.Zero) }

    fun share(c: Conv) {
        val who = Memory.all.firstOrNull { it.id == c.who }?.name ?: "她"
        val me = Memory.userName.trim().ifEmpty { "我" }
        val text = c.title + "\n\n" + c.msgs.filter { it.text.isNotBlank() }.joinToString("\n\n") { (if (it.role == "user") me else who) + "：" + it.text.trim() }
        runCatching {
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, c.title).putExtra(Intent.EXTRA_TEXT, text), "分享这段对话").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { note = "这台手机没有能分享文字的应用" }
    }

    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { origin = it.boundsInRoot().topLeft }) {
        val density = LocalDensity.current
        val fullH = with(density) { maxHeight.toPx() }
        val fullW = with(density) { maxWidth.toPx() }

        // ---------- 菜单 ----------
        val t = remember { Animatable(0f) }
        var shown by remember { mutableStateOf<Conv?>(null) }
        var sideNow by remember { mutableStateOf(false) }
        var at by remember { mutableStateOf(Rect.Zero) }
        LaunchedEffect(ConvMenu.conv) {
            val c = ConvMenu.conv
            if (c != null) { shown = c; sideNow = ConvMenu.side; at = ConvMenu.anchor.translate(-origin.x, -origin.y); t.animateTo(1f, tween(180, easing = Curves.decel)) }
            else { t.animateTo(0f, tween(140, easing = Curves.accel)); shown = null }
        }
        BackHandler(ConvMenu.conv != null) { ConvMenu.close() }
        val c = shown
        if (c != null && t.value > 0f) {
            val live = history.get(c.id) ?: c
            // 点菜单外面就关；⋮ 那边底下压一层淡暗，侧栏不压
            Box(
                Modifier.fillMaxSize()
                    .background(if (sideNow) Color.Transparent else Skin.scrim(.28f * t.value))
                    .pointerInput(Unit) { detectTapGestures { ConvMenu.close() } },
            )
            val items = if (sideNow) listOf("rename", "pin", "del") else listOf("share", "rename", "pin", "del")
            val menuH = with(density) { (items.size * 44 + 12 + (if (sideNow) 0 else 29)).dp.toPx() }
            val gap = with(density) { 6.dp.toPx() }
            var top = if (sideNow) at.bottom + gap else at.bottom + with(density) { 2.dp.toPx() }
            var below = true
            if (sideNow && top + menuH > fullH - with(density) { 24.dp.toPx() }) { top = at.top - menuH - gap; below = false }
            val right = if (sideNow) at.right - with(density) { 4.dp.toPx() } else at.right
            Column(
                Modifier
                    .offset { IntOffset(0, top.roundToInt()) }
                    .padding(end = with(density) { (fullW - right).coerceAtLeast(0f).toDp() })
                    .align(Alignment.TopEnd)
                    .graphicsLayer {
                        alpha = t.value
                        val s = .94f + .06f * t.value; scaleX = s; scaleY = s
                        translationY = (1f - t.value) * (if (below) -6.dp.toPx() else 6.dp.toPx())
                        transformOrigin = TransformOrigin(.85f, if (below) 0f else 1f)
                    }
                    .widthIn(min = 128.dp, max = 168.dp)
                    .shadow(18.dp, RoundedCornerShape(16.dp), ambientColor = Color.Black.copy(alpha = .4f), spotColor = Color.Black.copy(alpha = .4f))
                    .background(Skin.pop, RoundedCornerShape(16.dp))
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(vertical = 6.dp),
            ) {
                if (!sideNow) Txt(live.title.ifBlank { "新对话" }, Page.muted, 12.sp, Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 5.dp).widthIn(max = 140.dp), maxLines = 1)
                items.forEach { k ->
                    val (ic, label) = when (k) {
                        "share" -> shareIc to "分享"
                        "rename" -> renameIc to "重命名"
                        "pin" -> if (sideNow) pinIc to (if (live.pinned) "取消置顶" else "置顶") else starIc to (if (live.pinned) "取消收藏" else "收藏")
                        else -> trashIc to "删除"
                    }
                    MenuItem(ic, label, k == "del", filled = k == "pin" && !sideNow && live.pinned) {
                        scope.launch {
                            delay(90)
                            when (k) {
                                "share" -> { ConvMenu.held = null; ConvMenu.close(); share(live) }
                                "rename" -> { ConvMenu.renaming = live; ConvMenu.close() }
                                "pin" -> {
                                    history.togglePin(live.id)
                                    if (!sideNow) delay(200) // 收藏在菜单里翻过去，看到变了再收起
                                    ConvMenu.held = null; ConvMenu.close()
                                    note = if (sideNow) (if (live.pinned) "取消置顶了" else "置顶了") else (if (live.pinned) "取消收藏了" else "收藏了，在侧栏「已置顶」里")
                                }
                                else -> { ConvMenu.deleting = live; ConvMenu.close() }
                            }
                        }
                    }
                }
            }
        }

        // ---------- 重命名 ----------
        Sheet(ConvMenu.renaming != null, onDismiss = { ConvMenu.renaming = null; ConvMenu.held = null }) {
            val r = ConvMenu.renaming
            var v by remember(r?.id) { mutableStateOf(TextFieldValue(r?.title.orEmpty(), TextRange(0, r?.title.orEmpty().length))) }
            val focus = remember { FocusRequester() }
            LaunchedEffect(r?.id) { if (r != null) { delay(200); runCatching { focus.requestFocus() } } }
            fun save() {
                val id = r?.id ?: return
                val changed = v.text.trim().isNotEmpty() && v.text.trim() != r.title
                history.rename(id, v.text)
                ConvMenu.renaming = null; ConvMenu.held = null
                if (changed) note = "改好了"
            }
            Txt("重命名", Page.ink, 16.sp)
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().background(Skin.well(.22f), RoundedCornerShape(14.dp)).padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = v, onValueChange = { v = it.copy(text = it.text.replace("\n", "").take(40)) }, singleLine = true,
                    textStyle = TextStyle(fontFamily = Sans, color = Page.ink, fontSize = 15.sp),
                    cursorBrush = SolidColor(Skin.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier.weight(1f).padding(vertical = 14.dp).focusRequester(focus),
                )
                PressSurface({ v = TextFieldValue("") }, Modifier.size(40.dp), shape = CircleShape, fill = Color.Transparent, label = "清空") {
                    SvgIcon(clearIc, Page.muted, 14.dp, stroke = 2f)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SheetButton("取消", false, Modifier.weight(1f)) { ConvMenu.renaming = null; ConvMenu.held = null }
                SheetButton("保存", true, Modifier.weight(1f), enabled = v.text.isNotBlank()) { save() }
            }
        }

        // ---------- 删除确认 ----------
        Sheet(ConvMenu.deleting != null, onDismiss = { ConvMenu.deleting = null; ConvMenu.held = null }) {
            val d = ConvMenu.deleting
            Txt("删除这段对话？", Page.ink, 16.sp)
            Spacer(Modifier.height(8.dp))
            Txt("「${d?.title.orEmpty()}」和里面的聊天记录都会删掉。", Page.muted, 13.5.sp, lineHeight = 22.sp, align = androidx.compose.ui.text.style.TextAlign.Center)
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SheetButton("取消", false, Modifier.weight(1f)) { ConvMenu.deleting = null; ConvMenu.held = null }
                SheetButton("删除", true, Modifier.weight(1f), red = true) {
                    val x = d ?: return@SheetButton
                    wasCurrent = Chats.current?.convId == x.id
                    history.get(x.id)?.let { undo = it }
                    history.delete(x.id)
                    if (wasCurrent) Chats.fresh()
                    ConvMenu.deleting = null; ConvMenu.held = null
                }
            }
        }

        // ---------- 提示 / 撤销 ----------
        LaunchedEffect(undo) { if (undo != null) { delay(4000); undo = null } }
        LaunchedEffect(note) { if (note != null) { delay(1800); note = null } }
        val tip = if (undo != null) "删掉了这段对话" else note
        val tt by animateFloatAsState(if (tip != null) 1f else 0f, tween(if (tip != null) 240 else 180, easing = if (tip != null) Curves.decel else Curves.accel), label = "tip")
        var lastTip by remember { mutableStateOf("") }
        if (tip != null) lastTip = tip
        if (tt > 0f) {
            Row(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 120.dp, start = 16.dp, end = 16.dp)
                    .graphicsLayer { alpha = tt; translationY = (1f - tt) * 10.dp.toPx() }
                    .widthIn(max = 520.dp).fillMaxWidth()
                    .background(Skin.toast, RoundedCornerShape(16.dp))
                    .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp).height(40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Txt(lastTip, Skin.toastInk, 13.5.sp, Modifier.weight(1f), maxLines = 1)
                if (undo != null) Pressable({
                    undo?.let { u -> history.restore(u); if (wasCurrent) history.get(u.id)?.let { Chats.open(it) } }
                    undo = null
                }, label = "撤销") { pr ->
                    Txt("撤销", Skin.accent, 13.5.sp, Modifier.background(if (pr) Skin.toastInk.copy(alpha = .1f) else Color.Transparent, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 9.dp))
                }
            }
        }
    }
}

/** 菜单里一项：44dp 高，图标 + 字；按下当帧暗一下。 */
@Composable
private fun MenuItem(ic: Ic, label: String, red: Boolean, filled: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val bg by animateFloatAsState(if (pressed) 1f else 0f, tween(if (pressed) 60 else 180), label = "mi")
    val col = if (red) danger else Page.ink
    val fill by animateFloatAsState(if (filled) 1f else 0f, tween(200, easing = Curves.standard), label = "star")
    Row(
        Modifier.fillMaxWidth().height(44.dp).background(veil(.08f * bg))
            .clickable(src, null, role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(start = 14.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(18.dp)) {
            if (fill > 0f) {
                val path = remember { PathParser().parsePathString(STAR).toPath() }
                Canvas(Modifier.size(18.dp).graphicsLayer { alpha = fill }) {
                    val k = size.minDimension / 24f
                    scale(k, k, pivot = Offset.Zero) { drawPath(path, col) }
                }
            }
            SvgIcon(ic, if (red) danger else Page.ink.copy(alpha = .8f), 18.dp, stroke = 1.6f)
        }
        Spacer(Modifier.width(10.dp))
        Txt(label, col, 14.sp, maxLines = 1)
    }
}

/**
 * 弹出卡片（第 331 版，原型 XzZHhDoKrNXhGwYGF4hSNg 第 2 版）：重命名、删除确认在视觉中心弹出（偏上一点），键盘弹起来时停在键盘上面那块的视觉中心。
 * 进：缩放 .94 → 1、淡入 260ms 减速；出：160ms 加速收回。点卡片外面、按返回都等于取消。
 */
@Composable
private fun Sheet(open: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val t = remember { Animatable(0f) }
    LaunchedEffect(open) { t.animateTo(if (open) 1f else 0f, if (open) tween(260, easing = Curves.decel) else tween(160, easing = Curves.accel)) }
    BackHandler(open) { onDismiss() }
    if (t.value <= 0f && !open) return
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Skin.scrim(.4f * t.value)).pointerInput(Unit) { detectTapGestures { onDismiss() } })
        // 视觉中心：比几何正中偏上一点（卡片中心在可用高度的 42% 处），正中放会显得往下坠
        Box(Modifier.fillMaxSize().imePadding().navigationBarsPadding().padding(horizontal = 22.dp), contentAlignment = androidx.compose.ui.BiasAlignment(0f, -.16f)) {
            Column(
                Modifier.widthIn(max = 340.dp).fillMaxWidth()
                    .graphicsLayer { alpha = t.value.coerceIn(0f, 1f); val k = .94f + .06f * t.value; scaleX = k; scaleY = k }
                    .shadow(24.dp, RoundedCornerShape(22.dp), ambientColor = Color.Black.copy(alpha = .25f), spotColor = Color.Black.copy(alpha = .25f))
                    .background(Skin.pop, RoundedCornerShape(22.dp))
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { content() }
        }
    }
}

@Composable
private fun SheetButton(text: String, main: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, red: Boolean = false, onClick: () -> Unit) {
    PressSurface(
        onClick, modifier.height(44.dp).graphicsLayer { alpha = if (enabled) 1f else .4f }, shape = RoundedCornerShape(14.dp),
        fill = if (!main) Skin.well(.22f) else if (red) danger else Skin.accent,
        pressedFill = if (!main) veil(.1f) else (if (red) danger else Skin.accent).copy(alpha = .8f),
        enabled = enabled, label = text,
    ) {
        Txt(text, if (!main) Page.ink else if (red) Color.White else Skin.onAccent, 14.5.sp)
    }
}
