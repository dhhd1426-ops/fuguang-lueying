package com.fuguang.lueying

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.foundation.border
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * 照 Kelivo 样子的一套小部件（助理设置、世界书用）：蓝紫色点缀、色块卡片、一行一个设置、iOS 式开关、底部弹层。
 * 颜色跟着深浅主题走；动效用 dongxiaosheji 的 token（decel 进、accel 出，按下 90ms）。
 */
object K {
    private val dark get() = !Skin.light
    val acc get() = if (dark) Color(0xFFB5C2FF) else Color(0xFF4A5BC4)
    val onAcc get() = if (dark) Color(0xFF1C1D22) else Color.White
    val card get() = if (dark) Color(0xFF2A2B30) else Color(0xFFF4F4F7)
    val card2 get() = if (dark) Color(0xFF34353B) else Color(0xFFE7E8EE)
    val well get() = if (dark) Color(0xFF1F2025) else Color(0xFFE9EAEF)
    val pill get() = if (dark) Color(0xFF3A3C46) else Color.White
    val tabBar get() = if (dark) Color(0xFF2B2C32) else Color(0xFFEDEEF2)
    val ink get() = if (dark) Color(0xFFE7E7EA) else Color(0xFF1C1D22)
    val sub get() = if (dark) Color(0xFF9B9CA4) else Color(0xFF6E6F77)
    val dim get() = if (dark) Color(0xFF6E6F77) else Color(0xFFB4B5BC)
    val line get() = if (dark) Color(0x10FFFFFF) else Color(0x14000000)
    val danger = Color(0xFFFF8A80)
    val decel = CubicBezierEasing(.05f, .7f, .1f, 1f)
    val accel = CubicBezierEasing(.3f, 0f, .8f, .15f)
    val std = CubicBezierEasing(.2f, 0f, 0f, 1f)
}

/** Kelivo 用的 Lucide 图标（24 格，线宽 1.8）。 */
object KI {
    val therm = Ic("M14 14.76V3.5a2.5 2.5 0 0 0-5 0v11.26a4.5 4.5 0 1 0 5 0Z")
    val wand = Ic("M21.64 3.64l-1.28-1.28a1.21 1.21 0 0 0-1.72 0L2.36 18.64a1.21 1.21 0 0 0 0 1.72l1.28 1.28a1.2 1.2 0 0 0 1.72 0L21.64 5.36a1.2 1.2 0 0 0 0-1.72M14 7l3 3M5 6v4M19 14v4M10 2v2M7 8H3M21 16h-4M11 3H9")
    val msgs = Ic("M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z")
    val brain = Ic("M12 5a3 3 0 1 0-5.997.125 4 4 0 0 0-2.526 5.77 4 4 0 0 0 .556 6.588A4 4 0 1 0 12 18ZM12 5a3 3 0 1 1 5.997.125 4 4 0 0 1 2.526 5.77 4 4 0 0 1-.556 6.588A4 4 0 1 1 12 18ZM15 13a4.5 4.5 0 0 1-3-4 4.5 4.5 0 0 1-3 4M12 18V5")
    val hash = Ic("M4 9h16M4 15h16M10 3 8 21M16 3l-2 18")
    val user = Ic("M20 21a8 8 0 0 0-16 0", listOf(floatArrayOf(12f, 8f, 5f)))
    val aa = Ic("M3 17 7.5 6.5 12 17M4.8 13h5.4M20.5 10.5V17M20.5 13.75a3.25 3.25 0 1 1-6.5 0 3.25 3.25 0 0 1 6.5 0Z")
    val zap = Ic("M4 14a1 1 0 0 1-.78-1.63l9.9-10.2a.5.5 0 0 1 .86.46l-1.92 6.02A1 1 0 0 0 13 10h7a1 1 0 0 1 .78 1.63l-9.9 10.2a.5.5 0 0 1-.86-.46l1.92-6.02A1 1 0 0 0 11 14z")
    val chat = Ic("M7.9 20A9 9 0 1 0 4 16.1L2 22Z")
    val image = Ic("M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2ZM21 15l-3.09-3.09a2 2 0 0 0-2.82 0L6 21", listOf(floatArrayOf(9f, 9f, 2f)))
    val chev = Ic("M9 18l6-6-6-6")
    val info = Ic("M12 16v-4M12 8h.01", listOf(floatArrayOf(12f, 12f, 10f)))
    val doc = Ic("M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7ZM14 2v4a2 2 0 0 0 2 2h4")
    val book = Ic("M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7ZM14 2v4a2 2 0 0 0 2 2h4M8 13h8M8 17h5")
    val fold = Ic("M14 9a2 2 0 0 1-2 2H6l-4 4V4a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2zM18 9h2a2 2 0 0 1 2 2v11l-4-4h-6a2 2 0 0 1-2-2v-1")
    val recall = Ic("M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8M3 3v5h5M12 7v5l4 2")
    val sparkle = Ic("M9.94 14.06 4 20M12 3l1.9 5.8L20 11l-6.1 2.2L12 19l-1.9-5.8L4 11l6.1-2.2Z")
    val globe = Ic("M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20M2 12h20", listOf(floatArrayOf(12f, 12f, 10f)))
    val pic = Ic("M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2ZM21 15l-3-3L6 21", listOf(floatArrayOf(9f, 9f, 2f)))
    val clock = Ic("M12 6v6l4 2", listOf(floatArrayOf(12f, 12f, 10f)))
    val pact = Ic("M8 2v4M16 2v4M3 10h18M5 4h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2Z")
    val film = Ic("M4 2h16a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2ZM7 2v20M17 2v20M2 12h20M2 7h5M2 17h5M17 17h5M17 7h5")
    val cloud = Ic("M17.5 19H9a7 7 0 1 1 6.71-9h1.79a4.5 4.5 0 1 1 0 9Z")
    val hand = Ic("M18 11V6a2 2 0 0 0-4 0M14 10V4a2 2 0 0 0-4 0v2M10 10.5V6a2 2 0 0 0-4 0v8M18 8a2 2 0 1 1 4 0v6a8 8 0 0 1-8 8h-2c-2.8 0-4.5-.86-5.99-2.34l-3.6-3.6a2 2 0 0 1 2.83-2.82L7 15")
    val ask = Ic("M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3M12 17h.01", listOf(floatArrayOf(12f, 12f, 10f)))
    val calc = Ic("M6 2h12a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2ZM8 6h8M16 14v4M16 10h.01M12 10h.01M8 10h.01M12 14h.01M8 14h.01M12 18h.01M8 18h.01")
    val clip = Ic("M9 2h6a1 1 0 0 1 1 1v2a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1ZM16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2")
    val speak = Ic("M11 5 6 9H2v6h4l5 4zM15.54 8.46a5 5 0 0 1 0 7.07M19.07 4.93a10 10 0 0 1 0 14.14")
    val pin = Ic("M20 10c0 6-8 12-8 12s-8-6-8-12a8 8 0 0 1 16 0Z", listOf(floatArrayOf(12f, 10f, 3f)))
    val sun = Ic("M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M6.34 17.66l-1.41 1.41M19.07 4.93l-1.41 1.41", listOf(floatArrayOf(12f, 12f, 4f)))
    val plus = Ic("M12 5v14M5 12h14")
    val trash = Ic("M3 6h18M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2")
    val server = Ic("M4 2h16a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2ZM4 14h16a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-4a2 2 0 0 1 2-2ZM6 6h.01M6 18h.01")
    val check = Ic("M20 6 9 17l-5-5")
    val worldBook = Ic("M4 19.5v-15A2.5 2.5 0 0 1 6.5 2H20v20H6.5a2.5 2.5 0 0 1 0-5H20")
    val quote = Ic("M16 3a2 2 0 0 0-2 2v6a2 2 0 0 0 2 2 1 1 0 0 1 1 1v1a2 2 0 0 1-2 2 1 1 0 0 0-1 1v2a1 1 0 0 0 1 1 6 6 0 0 0 6-6V5a2 2 0 0 0-2-2zM5 3a2 2 0 0 0-2 2v6a2 2 0 0 0 2 2 1 1 0 0 1 1 1v1a2 2 0 0 1-2 2 1 1 0 0 0-1 1v2a1 1 0 0 0 1 1 6 6 0 0 0 6-6V5a2 2 0 0 0-2-2z")
    fun group(key: String) = when (key) {
        "globe" -> globe; "sparkle" -> sparkle; "doc" -> doc; "pic" -> pic; "clock" -> clock; "pact" -> pact; "book" -> book; "film" -> film
        "cloud" -> cloud; "hand" -> hand; "ask" -> ask; "clip" -> clip; "speak" -> speak; "calc" -> calc; "pin" -> pin; "sun" -> sun; else -> sparkle
    }
}

@Composable
fun KCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(bottom = 14.dp).clip(RoundedCornerShape(20.dp)).background(K.card), content = content)
}

@Composable
fun KSection(text: String) { Txt(text, K.sub, 13.sp, Modifier.padding(start = 6.dp, top = 4.dp, bottom = 8.dp)) }

/** 一行设置：图标、标题、小字、右边的值或开关、箭头。 */
@Composable
fun KRow(
    title: String,
    icon: Ic? = null,
    sub: String? = null,
    value: String? = null,
    enabled: Boolean = true,
    divider: Boolean = false,
    tag: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    var pressed by remember { mutableStateOf(false) }
    val bg by animateColorAsState(if (pressed && onClick != null) K.card2 else K.card, tween(if (pressed) 80 else 220), label = "krow")
    Box(
        Modifier.fillMaxWidth().background(bg).graphicsLayer { alpha = if (enabled) 1f else .45f }
            .then(if (onClick != null && enabled) Modifier.pointerInput(onClick) {
                detectTapGestures(onPress = { pressed = true; tryAwaitRelease(); pressed = false }, onTap = { onClick() })
            } else Modifier),
    ) {
        if (divider) Box(Modifier.padding(start = if (icon != null) 60.dp else 18.dp, end = 18.dp).fillMaxWidth().height(1.dp).background(K.line))
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { SvgIcon(icon, K.ink.copy(alpha = .9f), 24.dp, stroke = 1.8f); Spacer(Modifier.width(14.dp)) }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt(title, K.ink, 15.5.sp, maxLines = 2)
                    if (tag != null) { Spacer(Modifier.width(6.dp)); KTag(tag, true) }
                }
                if (!sub.isNullOrEmpty()) Txt(sub, K.sub, 12.5.sp, Modifier.padding(top = 2.dp))
            }
            if (value != null) { Spacer(Modifier.width(10.dp)); Txt(value, K.sub, 14.sp, Modifier.widthIn(max = 220.dp), maxLines = 1) }
            if (trailing != null) { Spacer(Modifier.width(10.dp)); trailing() }
            else if (onClick != null) { Spacer(Modifier.width(6.dp)); SvgIcon(KI.chev, K.sub, 16.dp, stroke = 2.2f) }
        }
    }
}

@Composable
fun KTag(text: String, acc: Boolean = false) {
    Txt(text, if (acc) K.acc else K.sub, 11.sp, Modifier.background(if (acc) K.acc.copy(alpha = .2f) else K.card2, RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 1.5.dp), maxLines = 1)
}

/** iOS 式开关：按下拇指变宽，开关时弹一下（k 380 阻尼约 .6）。 */
@Composable
fun KSwitch(on: Boolean, label: String = "", onChange: (Boolean) -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val x by animateDpAsState(if (on) 24.dp else 4.dp, spring(dampingRatio = .62f, stiffness = 520f), label = "ksw")
    val w by animateDpAsState(if (pressed) 28.dp else 24.dp, tween(120), label = "kswW")
    val track by animateColorAsState(if (on) K.acc else K.well, tween(220, easing = K.std), label = "kswT")
    val knob by animateColorAsState(if (on) K.onAcc else K.dim, tween(220, easing = K.std), label = "kswK")
    Box(
        Modifier.size(52.dp, 32.dp).clip(RoundedCornerShape(50)).background(track)
            .pointerInput(on) { detectTapGestures(onPress = { pressed = true; tryAwaitRelease(); pressed = false }, onTap = { onChange(!on) }) },
    ) {
        Box(Modifier.offset(x = if (on && pressed) x - 4.dp else x, y = 4.dp).size(w, 24.dp).clip(RoundedCornerShape(50)).background(knob))
    }
}

@Composable
fun KButton(text: String, modifier: Modifier = Modifier, primary: Boolean = false, color: Color? = null, onClick: () -> Unit) {
    Pressable(onClick, modifier, label = text) { pr ->
        val s by animateFloatAsState(if (pr) .97f else 1f, tween(90), label = "kbtn")
        Box(
            Modifier.fillMaxWidth().graphicsLayer { scaleX = s; scaleY = s }.clip(RoundedCornerShape(14.dp))
                .background(if (primary) K.acc else K.card2).padding(vertical = 11.dp),
            contentAlignment = Alignment.Center,
        ) { Txt(text, color ?: if (primary) K.onAcc else K.ink, 14.5.sp, maxLines = 1) }
    }
}

@Composable
fun KChip(text: String, onClick: () -> Unit) {
    Pressable(onClick, label = text) { pr ->
        Txt(text, K.sub, 12.5.sp, Modifier.clip(RoundedCornerShape(10.dp)).background(if (pr) K.pill else K.card2).padding(horizontal = 10.dp, vertical = 6.dp), maxLines = 1)
    }
}

@Composable
fun KField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, hint: String = "", lines: Int = 1, number: Boolean = false, mono: Boolean = false) {
    BasicTextField(
        value, onChange,
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(K.well).padding(horizontal = 14.dp, vertical = 12.dp),
        textStyle = TextStyle(color = K.ink, fontSize = 14.5.sp, lineHeight = 22.sp, fontFamily = if (mono) androidx.compose.ui.text.font.FontFamily.Monospace else Sans),
        cursorBrush = SolidColor(K.acc),
        singleLine = lines == 1,
        minLines = lines,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        decorationBox = { inner -> Box { if (value.isEmpty()) Txt(hint, K.dim, 14.5.sp); inner() } },
    )
}

@Composable
fun KLabel(text: String) { Txt(text, K.sub, 12.5.sp, Modifier.padding(start = 2.dp, top = 12.dp, bottom = 6.dp)) }

/** 分段选择（注入位置、角色）。 */
@Composable
fun <T> KSeg(options: List<Pair<T, String>>, selected: (T) -> Boolean, onPick: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(K.well).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        options.forEach { (v, l) ->
            val on = selected(v)
            val bg by animateColorAsState(if (on) K.pill else Color.Transparent, tween(220, easing = K.std), label = "kseg")
            Pressable({ onPick(v) }, Modifier.weight(1f), label = l) { _ ->
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(bg).padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Txt(l, if (on) K.acc else K.sub, 13.sp, maxLines = 1)
                }
            }
        }
    }
}

/** 选中打勾的一行（弹层里的选项）。 */
@Composable
fun KOption(title: String, on: Boolean, sub: String? = null, onClick: () -> Unit) {
    val a by animateFloatAsState(if (on) 1f else 0f, spring(dampingRatio = .6f, stiffness = 520f), label = "kopt")
    Pressable(onClick, Modifier.fillMaxWidth(), label = title) { pr ->
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (pr) K.card2 else Color.Transparent).padding(horizontal = 4.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Txt(title, K.ink, 15.sp)
                if (!sub.isNullOrEmpty()) Txt(sub, K.sub, 12.sp, Modifier.padding(top = 2.dp))
            }
            SvgIcon(KI.check, K.acc, 20.dp, stroke = 2.2f, modifier = Modifier.graphicsLayer { alpha = a; scaleX = .6f + .4f * a; scaleY = scaleX })
        }
    }
}

/** 勾选框（世界书多选、聊天里选世界书）。 */
@Composable
fun KCheck(on: Boolean) {
    val bg by animateColorAsState(if (on) K.acc else Color.Transparent, tween(180), label = "kck")
    val a by animateFloatAsState(if (on) 1f else 0f, spring(dampingRatio = .6f, stiffness = 520f), label = "kckA")
    Box(
        Modifier.size(22.dp).clip(RoundedCornerShape(7.dp)).background(bg)
            .border(1.6.dp, if (on) K.acc else K.dim, RoundedCornerShape(7.dp)),
        contentAlignment = Alignment.Center,
    ) { SvgIcon(KI.check, K.onAcc, 14.dp, stroke = 3f, modifier = Modifier.graphicsLayer { alpha = a; scaleX = .5f + .5f * a; scaleY = scaleX }) }
}

/** 底部弹层：从下面升起 340ms 减速、收回 220ms 加速，背后变暗；把手往下拖 80dp 就关。 */
class KSheetState {
    var content by mutableStateOf<(@Composable ColumnScope.() -> Unit)?>(null)
    var shown by mutableStateOf(false)
    fun open(c: @Composable ColumnScope.() -> Unit) { content = c; shown = true }
    fun close() { shown = false }
}

@Composable
fun BoxScope.KSheetHost(s: KSheetState) {
    val t by animateFloatAsState(if (s.shown) 1f else 0f, if (s.shown) tween(340, easing = K.decel) else tween(220, easing = K.accel), label = "ksheet",
        finishedListener = { if (!s.shown) s.content = null })
    val c = s.content ?: return
    var drag by remember { mutableFloatStateOf(0f) }
    var h by remember { mutableFloatStateOf(0f) }
    val d = LocalDensity.current
    Box(Modifier.matchParentSize().graphicsLayer { alpha = t }.background(Color(0x99000000)).pointerInput(Unit) { detectTapGestures { s.close() } })
    Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 620.dp)
            .onSizeChanged { h = it.height.toFloat() }
            .graphicsLayer { translationY = (1f - t) * (h + 40f) + drag }
            .shadow(24.dp, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).background(K.card)
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Box(
            Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp).pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { if (drag > with(d) { 80.dp.toPx() }) s.close(); drag = 0f },
                    onDragCancel = { drag = 0f },
                ) { _, dy -> drag = (drag + dy).coerceAtLeast(0f) }
            },
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.size(40.dp, 4.dp).clip(RoundedCornerShape(50)).background(K.ink.copy(alpha = .2f))) }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, bottom = 26.dp)) { c() }
    }
}

@Composable
fun KSheetTitle(text: String, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(text, K.ink, 16.sp, Modifier.weight(1f), weight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        trailing?.invoke()
    }
}

/** Kelivo 的滑杆：细槽、点缀色填充、白色圆拇指，拖着时放大。 */
@Composable
fun KSlider(value: Float, min: Float, max: Float, step: Float, onChange: (Float) -> Unit) {
    var w by remember { mutableFloatStateOf(1f) }
    var dragging by remember { mutableStateOf(false) }
    val frac = ((value - min) / (max - min)).coerceIn(0f, 1f)
    val sc by animateFloatAsState(if (dragging) 1.15f else 1f, tween(120), label = "kslider")
    fun at(x: Float): Float { val raw = min + (x / w).coerceIn(0f, 1f) * (max - min); return ((raw - min) / step).roundToInt() * step + min }
    Box(
        Modifier.fillMaxWidth().height(44.dp).onSizeChanged { w = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(min, max, step) { detectTapGestures { onChange(at(it.x)) } }
            .pointerInput(min, max, step) {
                detectHorizontalDragGestures(onDragStart = { dragging = true; onChange(at(it.x)) }, onDragEnd = { dragging = false }, onDragCancel = { dragging = false }) { ch, _ -> ch.consume(); onChange(at(ch.position.x)) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(K.well))
        Box(Modifier.fillMaxWidth(frac).height(4.dp).clip(RoundedCornerShape(50)).background(K.acc))
        Box(
            Modifier.graphicsLayer { translationX = frac * w - 12.dp.toPx(); scaleX = sc; scaleY = sc }
                .size(24.dp).shadow(3.dp, CircleShape).clip(CircleShape).background(Color.White),
        )
    }
}

/** Kelivo 的标签条：可横滑，选中的那块胶囊滑过去（弹簧，轻微过冲）。 */
@Composable
fun KTabs(tabs: List<String>, pos: Float, onPick: (Int) -> Unit) {
    val d = LocalDensity.current
    val xs = remember(tabs) { FloatArray(tabs.size) }
    val ws = remember(tabs) { FloatArray(tabs.size) }
    var ready by remember(tabs) { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val i0 = pos.toInt().coerceIn(0, tabs.lastIndex)
    val i1 = (i0 + 1).coerceAtMost(tabs.lastIndex)
    val f = pos - i0
    androidx.compose.runtime.LaunchedEffect(pos.roundToInt(), ready) {
        if (!ready) return@LaunchedEffect
        val i = pos.roundToInt().coerceIn(0, tabs.lastIndex)
        val target = (xs[i] + ws[i] / 2 - scroll.viewportSize / 2f).roundToInt().coerceIn(0, scroll.maxValue)
        scroll.animateScrollTo(target, tween(320, easing = K.decel))
    }
    Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).clip(RoundedCornerShape(22.dp)).background(K.tabBar)) {
        Box(Modifier.horizontalScroll(scroll).padding(5.dp)) {
            if (ready) Box(
                Modifier.graphicsLayer { translationX = xs[i0] + (xs[i1] - xs[i0]) * f }
                    .width(with(d) { (ws[i0] + (ws[i1] - ws[i0]) * f).toDp() }).height(38.dp)
                    .clip(RoundedCornerShape(17.dp)).background(K.pill),
            )
            Row {
                tabs.forEachIndexed { i, t ->
                    val near = (1f - kotlin.math.abs(pos - i)).coerceIn(0f, 1f)
                    Box(
                        Modifier.height(38.dp).onSizeChanged { ws[i] = it.width.toFloat() }
                            .onPlaced { xs[i] = it.positionInParent().x; if (i == tabs.lastIndex && !ready) ready = true }
                            .clickable(remember { MutableInteractionSource() }, null) { onPick(i) }
                            .padding(horizontal = 18.dp),
                        contentAlignment = Alignment.Center,
                    ) { Txt(t, androidx.compose.ui.graphics.lerp(K.sub, K.acc, near), 14.5.sp, maxLines = 1) }
                }
            }
        }
    }
}

