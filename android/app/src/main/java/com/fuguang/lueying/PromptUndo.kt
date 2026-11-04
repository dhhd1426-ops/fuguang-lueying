package com.fuguang.lueying

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 系统提示词的撤销 / 重做（第 387 版，原型 https://claude.ai/artifact/M8CHuAkmEWcAD5KcDjHUsa 第 3 版方案 B）。
 *
 * - 打字停 0.8 秒、换行、一次增减 20 字以上、恢复默认、「换成这段」各算一步；最多 50 步。
 * - 只在有没保存的改动时露出 ↶ ↷；保存（或离开时自动存）就收起，历史也清掉（[reset]）。
 * - 一次少了 20 字以上：↶ 自己长成杏色「撤回 N 字」，6 秒后缩回圆钮；不另弹提示条。
 * - 撤回 / 重做回来的那段字淡杏色亮一下再褪（[flash]），光标停在那段末尾。
 */
@Stable
internal class PromptUndo(start: String) {
    private val steps = mutableStateListOf(start)
    var at by mutableIntStateOf(0)
        private set
    private var lastAt = 0L

    /** 刚回来的那段（在当前文字里的起止）；[flashKey] 变一次亮一次。 */
    var flash by mutableStateOf<IntRange?>(null)
        private set
    var flashKey by mutableIntStateOf(0)
        private set

    /** 大于 0：撤销钮长成「撤回 N 字」。 */
    var lost by mutableIntStateOf(0)
    var lostKey by mutableIntStateOf(0)
        private set

    val canUndo get() = at > 0
    val canRedo get() = at < steps.lastIndex

    private fun push(s: String) {
        while (steps.lastIndex > at) steps.removeAt(steps.lastIndex)
        steps.add(s)
        if (steps.size > 51) steps.removeAt(0)
        at = steps.lastIndex
    }

    private fun check(old: String, new: String) {
        val n = old.length - new.length
        if (n >= 20) { lost = n; lostKey++ } else if (new.length > old.length) lost = 0
    }

    /** 手打的改动。 */
    fun typed(old: String, new: String) {
        if (old == new) return
        flash = null
        val now = SystemClock.uptimeMillis()
        val big = abs(new.length - old.length) >= 20 || (old.isNotEmpty() && new.isEmpty())
        val nl = new.count { it == '\n' } > old.count { it == '\n' }
        if (big || nl || now - lastAt > 800) push(new) else steps[at] = new
        lastAt = if (big) 0L else now
        check(old, new)
    }

    /** 一下子整段换掉（恢复默认、换成这段）。 */
    fun replaced(old: String, new: String) {
        if (old == new) return
        flash = null
        push(new); lastAt = 0L
        check(old, new)
    }

    /** 往回 / 往前一步；返回新文字和光标位置。 */
    fun go(d: Int, from: String): Pair<String, Int>? {
        val n = at + d
        if (n !in steps.indices) return null
        at = n; lastAt = 0L; lost = 0
        val to = steps[n]
        var s = 0
        while (s < from.length && s < to.length && from[s] == to[s]) s++
        var e = 0
        while (e < from.length - s && e < to.length - s && from[from.length - 1 - e] == to[to.length - 1 - e]) e++
        val end = to.length - e
        flash = if (end > s) s until end else null
        flashKey++
        return to to end
    }

    /** 保存了：历史清掉，从这一版重新记。 */
    fun reset(s: String) {
        steps.clear(); steps.add(s); at = 0; lastAt = 0L; lost = 0; flash = null
    }
}

private val standard = CubicBezierEasing(.2f, 0f, 0f, 1f)
private val decel = CubicBezierEasing(.05f, .7f, .1f, 1f)
private val accel = CubicBezierEasing(.3f, 0f, .8f, .15f)

/** 回来的那段字的淡杏色底：900ms 褪掉；褪完不再套变换。 */
@Composable
internal fun rememberUndoFlash(u: PromptUndo, color: Color): VisualTransformation {
    val a = remember(u) { Animatable(0f) }
    LaunchedEffect(u, u.flashKey) {
        if (u.flashKey > 0 && u.flash != null) { a.snapTo(1f); a.animateTo(0f, tween(900, easing = standard)) }
    }
    val r = u.flash
    val k = a.value
    if (r == null || k <= 0f) return VisualTransformation.None
    return VisualTransformation { t ->
        val s = r.first.coerceIn(0, t.length)
        val e = (r.last + 1).coerceIn(s, t.length)
        val b = AnnotatedString.Builder(t)
        b.addStyle(SpanStyle(background = color.copy(alpha = .32f * k)), s, e)
        TransformedText(b.toAnnotatedString(), OffsetMapping.Identity)
    }
}

/** ↶ ↷：有没保存的改动时从左边淡入长出来（240ms 减速），收起 180ms 加速。 */
@Composable
internal fun UndoPair(u: PromptUndo, show: Boolean, warm: Color, onWarm: Color, onGo: (Int) -> Unit) {
    LaunchedEffect(u, u.lostKey) { if (u.lost > 0) { delay(6000); u.lost = 0 } }
    LaunchedEffect(show) { if (!show) u.lost = 0 }
    AnimatedVisibility(
        show,
        enter = fadeIn(tween(240, easing = decel)) + scaleIn(tween(240, easing = decel), initialScale = .92f, transformOrigin = TransformOrigin(0f, .5f)),
        exit = fadeOut(tween(180, easing = accel)) + scaleOut(tween(180, easing = accel), targetScale = .92f, transformOrigin = TransformOrigin(0f, .5f)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            UndoButton(-1, u.canUndo, u.lost, warm, onWarm, onGo)
            Spacer(Modifier.width(4.dp))
            UndoButton(1, u.canRedo, 0, warm, onWarm, onGo)
        }
    }
}

@Composable
private fun UndoButton(d: Int, enabled: Boolean, lost: Int, warm: Color, onWarm: Color, onGo: (Int) -> Unit) {
    val wide = lost > 0
    var shown by remember { mutableIntStateOf(0) } // 缩回去时字还留着最后那个数
    if (lost > 0) shown = lost
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val rot = remember { Animatable(0f) }
    val fg by animateColorAsState(if (wide) onWarm else Page.ink, tween(240, easing = standard), label = "undoFg")
    Pressable(
        {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            scope.launch { rot.snapTo(12f * d); rot.animateTo(0f, spring(dampingRatio = .5f, stiffness = 600f)) }
            onGo(d)
        },
        enabled = enabled,
        label = if (d < 0) "撤销" else "重做",
    ) { pr ->
        val bg by animateColorAsState(
            when { wide -> warm; pr -> blockPress; else -> blockFill }, tween(if (pr) 70 else 200, easing = standard), label = "undoBg",
        )
        Row(
            Modifier.graphicsLayer { val s = if (pr) .94f else 1f; scaleX = s; scaleY = s }
                .animateContentSize(spring(dampingRatio = .806f, stiffness = 260f))
                .height(34.dp).background(bg, CircleShape),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(18.dp).graphicsLayer { rotationZ = rot.value }) {
                    val u = size.width / 24f
                    val p = Path().apply {
                        moveTo(9f * u, 14f * u); lineTo(4f * u, 9f * u); lineTo(9f * u, 4f * u)
                        moveTo(4f * u, 9f * u); lineTo(14.5f * u, 9f * u)
                        arcTo(Rect(Offset(9f * u, 9f * u), Offset(20f * u, 20f * u)), -90f, 180f, false)
                        lineTo(11f * u, 20f * u)
                    }
                    scale(if (d < 0) 1f else -1f, 1f) {
                        drawPath(p, fg, style = Stroke(1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
            }
            AnimatedVisibility(
                wide,
                enter = expandHorizontally(tween(320, easing = decel)) + fadeIn(tween(220, 140, decel)),
                exit = shrinkHorizontally(tween(240, easing = accel)) + fadeOut(tween(140, easing = accel)),
            ) {
                Txt("撤回 $shown 字", fg, 13.sp, Modifier.padding(end = 12.dp), maxLines = 1)
            }
        }
    }
}
