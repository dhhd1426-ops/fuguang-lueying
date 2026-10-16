package com.fuguang.lueying

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/* 浮动小卡片用的一套小部件：按压反馈、四段按钮、输入框、开关、标题。曲线照 dongxiaosheji 的动效 token。 */

object Curves {
    val standard = CubicBezierEasing(.2f, 0f, 0f, 1f)
    val decel = CubicBezierEasing(.05f, .7f, .1f, 1f)
    val accel = CubicBezierEasing(.3f, 0f, .8f, .15f)
}

val CardWell = rgba(0, 0, 0, .26f)
val CardBlock = rgba(255, 255, 255, .045f)
val CardBlockPress = rgba(255, 255, 255, .09f)
val CardRose = css("#e8a1a1")
private val warm = rgba(233, 180, 142, 1f)
private val warmInk = css("#2a211b")

private class RippleDrop(val at: Offset) { val t = Animatable(0f) }

/**
 * 按压反馈：手指落下当帧下沉，从指尖漾开一圈涟漪；松开带一点回弹。
 * [enabled] 为 false 时不响应，但不变淡（处理中的按钮要保持原样）。
 */
@Composable
fun PressSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(50),
    fill: Color = CardBlock,
    pressedFill: Color = CardBlockPress,
    ripple: Color = Page.ink,
    pressScale: Float = .95f,
    enabled: Boolean = true,
    label: String? = null,
    edge: Color = Color.Transparent,
    content: @Composable BoxScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) pressScale else 1f,
        if (pressed) tween(80) else spring(dampingRatio = .45f, stiffness = 600f), label = "press",
    )
    val bg by animateColorAsState(if (pressed) pressedFill else fill, tween(if (pressed) 80 else 220), label = "fill")
    val drops = remember { mutableStateListOf<RippleDrop>() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(source) {
        source.interactions.collect { i ->
            if (i is PressInteraction.Press) {
                val d = RippleDrop(i.pressPosition)
                drops += d
                scope.launch { d.t.animateTo(1f, tween(560, easing = Curves.decel)); drops -= d }
            }
        }
    }
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(bg)
            .border(1.dp, edge, shape)
            .drawBehind {
                drops.forEach { d ->
                    val t = d.t.value
                    drawCircle(ripple.copy(alpha = .16f * (1f - t)), size.maxDimension * 1.1f * t, d.at)
                }
            }
            .clickable(source, null, enabled = enabled, onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** 转圈：处理中。 */
@Composable
fun LoadRing(color: Color, size: Dp = 13.dp) {
    val t = rememberInfiniteTransition(label = "ring")
    val a by t.animateFloat(0f, 360f, infiniteRepeatable(tween(800, easing = LinearEasing)), label = "a")
    Canvas(Modifier.size(size)) { drawArc(color, a, 270f, false, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round)) }
}

/** 一笔画出来的 ✓。 */
@Composable
fun DrawnCheck(color: Color, size: Dp = 16.dp) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(90); p.animateTo(1f, tween(340, easing = Curves.decel)) }
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension / 24f
        val a = Offset(5f * s, 12.5f * s); val b = Offset(9.5f * s, 17f * s); val c = Offset(19f * s, 7.5f * s)
        val l1 = (b - a).getDistance(); val l2 = (c - b).getDistance()
        val d = p.value * (l1 + l2); val w = 2.dp.toPx()
        if (d > 0f) drawLine(color, a, if (d < l1) a + (b - a) * (d / l1) else b, w, StrokeCap.Round)
        if (d > l1) drawLine(color, b, b + (c - b) * ((d - l1) / l2), w, StrokeCap.Round)
    }
}

enum class BtnTone { Plain, Primary, Quiet, Ok, Bad }

/**
 * 四段按钮：按下下沉 + 涟漪 → 松开回弹 → 处理中（[busy]，不再响应）→ 结果写在按钮上。
 * [state] 变化时文字交叉过渡：旧的往上淡出，新的从下面上来。[shake] 每加一次抖一下（失败）。
 */
@Composable
fun <S> CardButton(
    state: S,
    tone: BtnTone,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    shake: Int = 0,
    content: @Composable (S, Color) -> Unit,
) {
    val fillTarget = when (tone) {
        BtnTone.Plain -> rgba(255, 255, 255, .09f)
        BtnTone.Primary -> warm.copy(alpha = .92f)
        BtnTone.Quiet, BtnTone.Ok -> CardBlock
        BtnTone.Bad -> CardRose.copy(alpha = .12f)
    }
    val pressTarget = when (tone) {
        BtnTone.Primary -> css("#f4c9a8")
        BtnTone.Plain -> rgba(255, 255, 255, .15f)
        else -> CardBlockPress
    }
    val inkTarget = when (tone) {
        BtnTone.Primary -> warmInk
        BtnTone.Quiet -> Page.muted
        BtnTone.Ok -> warm
        BtnTone.Bad -> CardRose
        BtnTone.Plain -> Page.ink
    }
    val fill by animateColorAsState(fillTarget, tween(220, easing = Curves.standard), label = "btnFill")
    val ink by animateColorAsState(inkTarget, tween(220, easing = Curves.standard), label = "btnInk")
    val dx = remember { Animatable(0f) }
    LaunchedEffect(shake) {
        if (shake > 0) dx.animateTo(0f, keyframes { durationMillis = 300; -4f at 75; 4f at 150; -2f at 225 })
    }
    PressSurface(
        onClick, modifier.height(44.dp).graphicsLayer { translationX = dx.value * density },
        fill = fill, pressedFill = pressTarget, ripple = ink, enabled = !busy, label = label,
    ) {
        AnimatedContent(
            state,
            transitionSpec = {
                (slideInVertically(tween(200, easing = Curves.standard)) { it / 2 } + fadeIn(tween(160))) togetherWith
                    (slideOutVertically(tween(200, easing = Curves.standard)) { -it / 2 } + fadeOut(tween(140))) using SizeTransform(clip = false)
            },
            label = "btnLabel",
        ) { s -> Row(verticalAlignment = Alignment.CenterVertically) { content(s, ink) } }
    }
}

/** 按钮里的一行：可选图标 + 文字。 */
@Composable
fun BtnLabel(text: String, ink: Color, lead: (@Composable () -> Unit)? = null) {
    if (lead != null) { lead(); androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp)) }
    Txt(text, ink, 13.5.sp, maxLines = 1)
}

/** 输入框：色块，不用框线。 */
@Composable
fun CardField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = "",
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = Page.ink, fontSize = 14.sp),
        cursorBrush = SolidColor(Page.accent),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().background(CardWell, RoundedCornerShape(12.dp)).padding(horizontal = 13.dp, vertical = 11.dp)) {
                if (value.isEmpty()) Txt(placeholder, Page.muted.copy(alpha = .5f), 14.sp, maxLines = 1)
                inner()
            }
        },
    )
}

/** 开关：圆点带一点回弹滑过去。 */
@Composable
fun CardSwitch(on: Boolean, label: String, onChange: (Boolean) -> Unit) {
    val x by animateFloatAsState(if (on) 1f else 0f, spring(dampingRatio = .6f, stiffness = 500f), label = "sw")
    val track by animateColorAsState(if (on) warm.copy(alpha = .9f) else rgba(255, 255, 255, .12f), tween(220), label = "track")
    val knob by animateColorAsState(if (on) warmInk else css("#cfccc6"), tween(200), label = "knob")
    Pressable({ onChange(!on) }, label = label) { _ ->
        Box(Modifier.width(46.dp).height(26.dp).background(track, RoundedCornerShape(50)).padding(3.dp)) {
            Box(Modifier.graphicsLayer { translationX = x * 20.dp.toPx() }.size(20.dp).background(knob, CircleShape))
        }
    }
}

/** 卡片里的标题：可选返回箭头 + 宋体。 */
@Composable
fun CardHeader(text: String, onBack: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            PressSurface(onBack, Modifier.padding(end = 8.dp).size(34.dp), shape = CircleShape, fill = Color.Transparent, pressScale = .9f, label = "返回") {
                SvgIcon(Ic("M14.5 6l-6 6 6 6"), Page.ink, 18.dp)
            }
        }
        Txt(text, Page.ink, 19.sp, spacing = ls(3.8f, 19f), family = Serif, maxLines = 1)
    }
}

/** 一行小标签：启用 / 未配置 / 2 个模型。 */
@Composable
fun CardTag(text: String, filled: Boolean = false) {
    if (filled) Txt(text, css("#2a2f38"), 11.sp, Modifier.background(Page.ink.copy(alpha = .9f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp), maxLines = 1)
    else Txt(text, Page.muted, 11.5.sp, maxLines = 1)
}
