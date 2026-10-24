package com.fuguang.lueying

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/** 网站 SVG sprite 里的图标，24×24 视口、描边绘制。 */
object Icons {
    val spark = Ic("M12 2c0 7-3 10-10 10 7 0 10 3 10 10 0-7 3-10 10-10-7 0-10-3-10-10Z")
    val settings = Ic("M4 7h16M4 17h16", circles = listOf(floatArrayOf(9f, 7f, 3f), floatArrayOf(16f, 17f, 3f)))
    val pause = Ic("M9 5v14M15 5v14")
    val play = Ic("M8 4 20 12 8 20Z")
    val expand = Ic("M8 3H3v5m13-5h5v5M3 16v5h5m13-5v5h-5")
    val eye = Ic("M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12Z", circles = listOf(floatArrayOf(12f, 12f, 3f)))
    val note = Ic("M14 3H5v18h14V9m-5-6v6h5M8 13h8m-8 4h6")
    val arrow = Ic("M4 12h16m-6-6 6 6-6 6")
    val close = Ic("M6 6 18 18M6 18 18 6")
}

class Ic(val d: String, val circles: List<FloatArray> = emptyList())

@Composable
fun SvgIcon(icon: Ic, color: Color, size: Dp = 20.dp, stroke: Float = 1.4f, modifier: Modifier = Modifier) {
    val path = remember(icon) { PathParser().parsePathString(icon.d).toPath() }
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        scale(s, pivot = Offset.Zero) {
            val st = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawPath(path, color, style = st)
            icon.circles.forEach { c -> drawCircle(color, c[2], Offset(c[0], c[1]), style = st) }
        }
    }
}

/** 文字：默认继承网站的字距与行高习惯。 */
@Composable
fun Txt(
    text: String,
    color: Color,
    size: TextUnit = 12.sp,
    modifier: Modifier = Modifier,
    spacing: TextUnit = 0.sp,
    lineHeight: TextUnit = TextUnit.Unspecified,
    family: FontFamily = Sans,
    weight: FontWeight = FontWeight.Normal,
    align: TextAlign = TextAlign.Start,
    maxLines: Int = Int.MAX_VALUE,
    shadow: androidx.compose.ui.graphics.Shadow? = null,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontSize = size,
            letterSpacing = spacing,
            lineHeight = lineHeight,
            fontFamily = family,
            fontWeight = weight,
            textAlign = align,
            shadow = shadow,
        ),
        overflow = TextOverflow.Ellipsis,
        maxLines = maxLines,
    )
}

/** CSS 的 letter-spacing 用 px 写，换算成 em 以便和 sp 字号一起缩放。 */
fun ls(px: Float, size: Float): TextUnit = (px / size).em

/**
 * 按钮：无涟漪，按下时按网站 :hover/:active 的反馈变化（变亮、上浮），禁用时 25% 透明。
 */
@Composable
fun Pressable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String? = null,
    content: @Composable (pressed: Boolean) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        modifier
            .alpha(if (enabled) 1f else .3f)
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                onClickLabel = label,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) { content(pressed && enabled) }
}

val LocalHaze = staticCompositionLocalOf<HazeState?> { null }

/** 网站 .glass：半透明 + 背景模糊 + 细边。 */
@Composable
fun Modifier.glass(shape: Shape, blur: Dp, fill: Color = Page.glassFill, border: Color = Page.glassBorder): Modifier {
    val haze = LocalHaze.current
    val style = HazeStyle(
        backgroundColor = Page.ground,
        tints = listOf(HazeTint(fill)),
        blurRadius = blur,
        noiseFactor = 0f,
        fallbackTint = HazeTint(fill.copy(alpha = .35f)),
    )
    return this
        .clip(shape)
        .then(if (haze != null && blur > 0.dp) Modifier.hazeEffect(haze, style) else Modifier.background(fill, shape))
        .border(1.dp, border, shape)
}

/** 透明磨砂主题的侧栏、悬浮卡片：把后面的背景画面磨砂了透上来，再压一层 fill。没有画面可透时就是实底。 */
@Composable
fun Modifier.frost(shape: Shape, fill: Color, blur: Dp = 30.dp): Modifier {
    val haze = LocalHaze.current ?: return this.background(fill.copy(alpha = (fill.alpha + .4f).coerceAtMost(1f)), shape)
    val style = HazeStyle(
        backgroundColor = Page.ground,
        tints = listOf(HazeTint(fill)),
        blurRadius = blur,
        noiseFactor = 0f,
        fallbackTint = HazeTint(fill.copy(alpha = (fill.alpha + .4f).coerceAtMost(1f))),
    )
    return this.clip(shape).hazeEffect(haze, style)
}

/** 网站 input[type=range]：细轨 + 圆点，可拖动、可点按定位。 */
@Composable
fun RangeSlider(value: Int, min: Int, max: Int, accent: Color, track: Color, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .pointerInput(min, max) {
                fun set(x: Float) {
                    val f = (x / size.width).coerceIn(0f, 1f)
                    onChange((min + f * (max - min)).toInt())
                }
                detectTapGestures { set(it.x) }
            }
            .pointerInput(min, max, "drag") {
                detectHorizontalDragGestures { change, _ ->
                    val f = (change.position.x / size.width).coerceIn(0f, 1f)
                    onChange((min + f * (max - min)).toInt())
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val f = (value - min).toFloat() / (max - min)
        val w = maxWidth
        Box(Modifier.fillMaxWidth().height(4.dp).background(track, CircleShape))
        Box(Modifier.fillMaxWidth(f).height(4.dp).background(accent, CircleShape))
        val knob = 18.dp
        Box(
            Modifier
                .offset(x = (w - knob) * f)
                .size(knob)
                .background(accent, CircleShape),
        )
    }
}

/** 0→1 的进出场动画值。 */
@Composable
fun appear(visible: Boolean, ms: Int = 220): Float {
    val v by animateFloatAsState(if (visible) 1f else 0f, tween(ms), label = "appear")
    return v
}

@Suppress("unused")
@Composable
fun dpToPx(dp: Dp): Float = with(LocalDensity.current) { dp.toPx() }
