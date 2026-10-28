package com.fuguang.lueying

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** 长按消息：哪一条、它在屏幕上的位置。 */
class MsgPress(val index: Int, val bounds: Rect)

/** 给一条消息加上长按：记下自己的位置，按住约半秒就弹出菜单（带一下轻震）。 */
@Composable
fun Modifier.longPressMsg(index: Int, selected: Boolean, onLong: (MsgPress) -> Unit): Modifier {
    val haptic = LocalHapticFeedback.current
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val lift by animateFloatAsState(if (selected) 1f else 0f, tween(180), label = "lift")
    return this
        .onGloballyPositioned { bounds = it.boundsInRoot() }
        .graphicsLayer { val s = 1f + .02f * lift; scaleX = s; scaleY = s }
        .background(veil(.06f * lift), RoundedCornerShape(14.dp))
        .pointerInput(index) {
            detectTapGestures(onLongPress = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onLong(MsgPress(index, bounds))
            })
        }
}

private fun msgTime(t: Long): String {
    if (t <= 0) return ""
    val now = java.util.Calendar.getInstance()
    val c = java.util.Calendar.getInstance().apply { timeInMillis = t }
    val today = now.get(java.util.Calendar.YEAR) == c.get(java.util.Calendar.YEAR) && now.get(java.util.Calendar.DAY_OF_YEAR) == c.get(java.util.Calendar.DAY_OF_YEAR)
    return java.text.SimpleDateFormat(if (today) "HH:mm" else "M 月 d 日 HH:mm", java.util.Locale.CHINA).format(java.util.Date(t))
}

/**
 * 长按菜单（方案 1）：玻璃小卡片，统一靠右（顺手）；在消息下方，放不下就放上方。
 * 点暗处收起。你的消息：复制 / 选择文字 / 编辑重发；Amor 的回复：复制 / 选择文字 / 重新回答（只有最后一条）。
 */
@Composable
fun MsgMenu(
    press: MsgPress?,
    msg: ChatMsg?,
    isLast: Boolean,
    side: Dp,
    onClose: () -> Unit,
    onCopy: () -> Unit,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onRegen: () -> Unit,
) {
    var last by remember { mutableStateOf<Pair<MsgPress, ChatMsg>?>(null) }
    if (press != null && msg != null) last = press to msg
    val shown by animateFloatAsState(if (press != null) 1f else 0f, tween(if (press != null) 200 else 150), label = "msgMenu")
    androidx.activity.compose.BackHandler(enabled = press != null) { onClose() }
    if (shown <= 0f || last == null) return
    val (p, m) = last!!
    val user = m.role == "user"
    val items = buildList {
        add(Triple("复制", Ic("M9 9.5A1.5 1.5 0 0 1 10.5 8h8A1.5 1.5 0 0 1 20 9.5v9a1.5 1.5 0 0 1-1.5 1.5h-8A1.5 1.5 0 0 1 9 18.5zM15 8V5.5A1.5 1.5 0 0 0 13.5 4h-8A1.5 1.5 0 0 0 4 5.5v9A1.5 1.5 0 0 0 5.5 16H9"), onCopy))
        add(Triple("选择文字", Ic("M4 7V5h10v2M9 5v14M7 19h4M14 12v-1.5h6V12M17 10.5V19M15.5 19h3"), onSelect))
        if (user) add(Triple("编辑重发", Ic("M13.5 6.5l4 4M5 19l1-4.5L15.5 5a1.5 1.5 0 0 1 2 0l1.5 1.5a1.5 1.5 0 0 1 0 2L9.5 18z"), onEdit))
        else if (isLast) add(Triple("重新回答", Ic("M19 12a7 7 0 1 1-2.1-5M19 4.5V9h-4.5"), onRegen))
    }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val d = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        Box(Modifier.fillMaxSize().alpha(shown).background(Skin.scrim(.42f)).pointerInput(Unit) { detectTapGestures { onClose() } })
        val time = msgTime(m.at)
        val cardH = with(d) { ((if (time.isNotEmpty()) 34.dp else 6.dp) + 48.dp * items.size + 12.dp).toPx() }
        val gap = with(d) { 8.dp.toPx() }
        val maxH = constraints.maxHeight.toFloat()
        val below = p.bounds.bottom - origin.y + gap
        val y = if (below + cardH < maxH - with(d) { 24.dp.toPx() }) below else (p.bounds.top - origin.y - gap - cardH).coerceAtLeast(gap)
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .padding(end = side)
                .offset { IntOffset(0, y.roundToInt()) }
                .width(208.dp)
                .graphicsLayer { alpha = shown; val s = .92f + .08f * shown; scaleX = s; scaleY = s; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0f) }
                .background(Skin.pop, RoundedCornerShape(18.dp))
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(vertical = 6.dp),
        ) {
            if (time.isNotEmpty()) Txt(time, Page.muted, 12.sp, Modifier.padding(start = 18.dp, top = 6.dp, bottom = 8.dp))
            items.forEach { (label, icon, act) ->
                Pressable({ onClose(); act() }, Modifier.fillMaxWidth(), label = label) { pressed ->
                    Row(
                        Modifier.fillMaxWidth().background(if (pressed) veil(.05f) else androidx.compose.ui.graphics.Color.Transparent)
                            .padding(horizontal = 18.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SvgIcon(icon, Page.ink.copy(alpha = .78f), 19.dp)
                        Spacer(Modifier.width(14.dp))
                        Txt(label, Page.ink, 15.sp)
                    }
                }
            }
        }
    }
}

/** 选择文字：从底部升起一页，只放这条消息，手指自由选取；点「完成」或返回键收起。 */
@Composable
fun SelectTextPage(text: String?, onClose: () -> Unit) {
    var last by remember { mutableStateOf("") }
    if (text != null) last = text
    val shown by animateFloatAsState(if (text != null) 1f else 0f, tween(if (text != null) 300 else 220, easing = androidx.compose.animation.core.CubicBezierEasing(.2f, .8f, .2f, 1f)), label = "select")
    androidx.activity.compose.BackHandler(enabled = text != null) { onClose() }
    if (shown <= 0f) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val h = maxHeight
        Column(
            Modifier.fillMaxSize()
                .graphicsLayer { translationY = (1f - shown) * h.toPx() }
                .background(Skin.sheet)
                .pointerInput(Unit) { detectTapGestures { } }
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt("选择文字", Page.ink, 20.sp, Modifier.weight(1f), family = Serif)
                Pressable(onClose, label = "完成") { pr ->
                    Txt("完成", Skin.accent.copy(alpha = if (pr) .7f else 1f), 15.sp, Modifier.padding(8.dp))
                }
            }
            SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Txt(last, Page.ink.copy(alpha = .9f), 16.sp, lineHeight = 30.sp)
            }
        }
    }
}

/** 底部一闪而过的小提示，比如「已复制」。 */
@Composable
fun BoxToast(text: String?, modifier: Modifier, onGone: () -> Unit) {
    var last by remember { mutableStateOf("") }
    if (text != null) last = text
    val shown by animateFloatAsState(if (text != null) 1f else 0f, tween(220), label = "toast")
    LaunchedEffect(text) { if (text != null) { kotlinx.coroutines.delay(1400); onGone() } }
    if (shown <= 0f) return
    Txt(
        last, Skin.toastInk, 13.5.sp,
        modifier.graphicsLayer { alpha = shown; translationY = (1f - shown) * 24f }
            .background(Skin.toast, RoundedCornerShape(50)).padding(horizontal = 18.dp, vertical = 9.dp),
    )
}
