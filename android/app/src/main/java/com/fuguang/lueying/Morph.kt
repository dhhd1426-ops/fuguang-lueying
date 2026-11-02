package com.fuguang.lueying

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 会变形的按钮（第 379 版，原型 https://claude.ai/artifact/V8FnfRLAXjmBryEUQTd4cT 第 2 版）。
 *
 * 第 377 版是在按钮上另盖一层深色胶囊，用户说「太生硬，像拼上去的」。现在按钮自己变：
 * - 同一个按钮、同一圈边框，按下当帧缩到 .96。
 * - 图标位（20dp）原地换：图标缩小淡走，转圈 / 进度环接上；成功一笔画出勾，失败弹出「!」（弹簧阻尼 .5）。
 * - 字位原地换：旧字往上淡出（140ms 加速），新字从下面上来（240ms 减速，晚 50ms）；等结果时字轻轻明暗呼吸。
 * - 宽度跟着字走 gentle 弹簧（k260，阻尼 .806，约 2% 过冲），旁边的东西顺着让开。
 * - 成功：按钮透出 16% 点缀色、字变点缀色；失败：透出 14% 危险色，左右晃两下（k900，阻尼 .3），写原因和「点我重试」，点按钮本身就重来。
 * - 结果停 1.6 秒（失败 3 秒；带动作的成功 2.6 秒，点按钮跑动作），然后原路变回去。
 * [float]：图标按钮放在固定的格子里，变形时往左长出去盖在旁边（不推挤布局），底色换成不透明的弹层色。
 */
@Stable
class Morph {
    enum class Look { Idle, Busy, Ok, Info, Bad }

    sealed interface End
    /** 成功：主字、淡字；info 是「没变化」那种灰勾；action 不空时停 2.6 秒，期间点按钮跑它（比如「发给…」）。 */
    class Ok(val text: String, val note: String = "", val info: Boolean = false, val action: (() -> Unit)? = null) : End
    class Bad(val text: String, val note: String = "点我重试") : End

    var look by mutableStateOf(Look.Idle)
        private set
    var text by mutableStateOf("")
        private set
    var note by mutableStateOf("")
        private set
    var clock by mutableStateOf(false)
        private set
    /** 进度环（0–1）；null 就转圈。 */
    var progress by mutableStateOf<Float?>(null)
    var running by mutableStateOf(false)
        private set
    internal var startedAt by mutableLongStateOf(0L)
    internal val shake = Animatable(0f)
    internal var density = 1f

    private var tap: CompletableDeferred<Unit>? = null
    /** 按钮被点：正等着「重试」或等着点结果时由这里接住，返回 true。 */
    internal fun consumeTap(): Boolean { val t = tap ?: return false; t.complete(Unit); return true }

    private suspend fun waitTap(ms: Long): Boolean {
        val d = CompletableDeferred<Unit>(); tap = d
        val hit = withTimeoutOrNull(ms) { d.await() } != null
        tap = null
        return hit
    }

    private fun show(l: Look, t: String, n: String = "", c: Boolean = false) { look = l; text = t; note = n; clock = c }
    fun reset() { show(Look.Idle, ""); progress = null }

    /**
     * 要等的事。[work] 里可以改 [progress]。[revert] 为 false 时结果留着不变回去（按钮马上要换成别的样子时用）。成功返回 true。
     */
    suspend fun run(busy: String, clock: Boolean = false, revert: Boolean = true, work: suspend Morph.() -> End): Boolean {
        if (running) return false
        running = true
        try {
            while (true) {
                progress = null; startedAt = System.currentTimeMillis()
                show(Look.Busy, busy, c = clock)
                when (val e = work()) {
                    is Ok -> {
                        show(if (e.info) Look.Info else Look.Ok, e.text, e.note)
                        val hit = waitTap(if (e.action != null) 2600L else 1600L)
                        if (hit && e.action != null) e.action.invoke()
                        if (revert) reset()
                        return true
                    }
                    is Bad -> {
                        show(Look.Bad, e.text, e.note)
                        val again = coroutineScope {
                            launch { shake.snapTo(0f); shake.animateTo(0f, spring(dampingRatio = .3f, stiffness = 900f), initialVelocity = -520f * density) }
                            waitTap(3000L)
                        }
                        if (!again) { reset(); return false }
                    }
                }
            }
            return false
        } finally {
            running = false; tap = null
            if (look == Look.Busy) reset()
        }
    }

    /** 不用等的结果：直接报一句，停 1.6 秒变回去；点按钮提前变回去。 */
    suspend fun flash(text: String, note: String = "", info: Boolean = false) {
        if (running) return
        running = true
        try { show(if (info) Look.Info else Look.Ok, text, note); waitTap(1600L) } finally { running = false; tap = null; reset() }
    }
}

private data class Face(val look: Morph.Look, val text: String, val note: String, val clock: Boolean)

/**
 * [label] 平常的字（[quiet] 的图标按钮不显示，只做无障碍名）；[icon] 平常的图标，可以没有。
 * [primary] 点缀色实心按钮；[small] 34dp 高的小按钮；[quiet] 平常没底没边的图标按钮；[float] 见 [Morph]。
 */
@Composable
fun MorphPill(
    m: Morph, label: String, icon: Ic?, onClick: () -> Unit, modifier: Modifier = Modifier,
    primary: Boolean = false, small: Boolean = false, quiet: Boolean = false, float: Boolean = false, enabled: Boolean = true,
) {
    m.density = androidx.compose.ui.platform.LocalDensity.current.density
    val idle = m.look == Morph.Look.Idle
    val h = when { small -> 34.dp; quiet -> 44.dp; else -> 46.dp }
    val fs = if (small) 13.5.sp else 14.5.sp
    val spec = tween<Color>(260, easing = Curves.standard)
    val base = when {
        primary -> Skin.accent.copy(alpha = .9f)
        float && !idle -> Skin.pop
        quiet && idle -> Color.Transparent
        else -> veil(if (m.look == Morph.Look.Busy) .08f else .06f)
    }
    val tintC = when (m.look) {
        Morph.Look.Ok -> if (primary) Color.Transparent else Skin.accent.copy(alpha = .16f)
        Morph.Look.Bad -> Skin.danger.copy(alpha = .14f)
        else -> Color.Transparent
    }
    val edgeC = when {
        m.look == Morph.Look.Ok && !primary -> Skin.accent.copy(alpha = .45f)
        m.look == Morph.Look.Bad -> Skin.danger.copy(alpha = .45f)
        primary || (quiet && idle) -> Color.Transparent
        else -> Page.line
    }
    val inkC = when {
        m.look == Morph.Look.Bad -> Skin.danger
        primary -> Skin.onAccent
        m.look == Morph.Look.Ok -> Skin.accent
        else -> Page.ink
    }
    val fill by animateColorAsState(base, spec, label = "mFill")
    val tint by animateColorAsState(tintC, spec, label = "mTint")
    val edge by animateColorAsState(edgeC, spec, label = "mEdge")
    val ink by animateColorAsState(inkC, spec, label = "mInk")
    val hasText = !(quiet && idle)
    val padS by animateDpAsState(if (quiet) 12.dp else if (small) 14.dp else 20.dp, spring(dampingRatio = .806f, stiffness = 260f), label = "mPadS")
    val padE by animateDpAsState(if (!hasText) 12.dp else if (small) 15.dp else 22.dp, spring(dampingRatio = .806f, stiffness = 260f), label = "mPadE")
    val showIcon = icon != null || !idle
    val shape = RoundedCornerShape(50)

    val pill: @Composable (Boolean) -> Unit = { pressed ->
        val k by animateFloatAsState(if (pressed) .96f else 1f, if (pressed) tween(70) else spring(dampingRatio = .745f, stiffness = 520f), label = "mPress")
        Row(
            Modifier.height(h)
                .graphicsLayer { scaleX = k; scaleY = k; translationX = m.shake.value }
                .clip(shape).background(fill).background(tint).border(1.dp, edge, shape)
                .padding(start = padS, end = padE)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedVisibility(showIcon, enter = fadeIn(tween(200, 60)) + expandHorizontally(spring(dampingRatio = .806f, stiffness = 260f)), exit = fadeOut(tween(140)) + shrinkHorizontally(spring(dampingRatio = .806f, stiffness = 260f))) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Glyph(m, icon, ink)
                    if (hasText) Spacer(Modifier.width(if (small) 8.dp else 10.dp))
                }
            }
            AnimatedContent(
                Face(m.look, if (idle) (if (quiet) "" else label) else m.text, m.note, m.clock),
                contentKey = { listOf(it.look, it.text, it.note) },
                transitionSpec = {
                    (fadeIn(tween(240, 50, Curves.decel)) + slideInVertically(tween(320, 50, Curves.decel)) { it / 2 }) togetherWith
                        (fadeOut(tween(140, easing = Curves.accel)) + slideOutVertically(tween(180, easing = Curves.accel)) { -it / 2 }) using
                        SizeTransform(clip = false) { _, _ -> spring(dampingRatio = .806f, stiffness = 260f, visibilityThreshold = IntSize.VisibilityThreshold) }
                },
                contentAlignment = Alignment.CenterStart,
                label = "mFace",
            ) { f ->
                if (f.text.isEmpty()) Box(Modifier.size(0.dp, 1.dp))
                else Row(verticalAlignment = Alignment.CenterVertically) {
                    val breathe = if (f.look == Morph.Look.Busy) {
                        val tr = rememberInfiniteTransition(label = "mBreathe")
                        tr.animateFloat(1f, .55f, infiniteRepeatable(tween(900, easing = Curves.standard), RepeatMode.Reverse), label = "mB").value
                    } else 1f
                    Txt(f.text, ink, fs, Modifier.graphicsLayer { alpha = breathe }, maxLines = 1, weight = FontWeight.Medium)
                    if (f.note.isNotEmpty()) Txt(" · ${f.note}", ink.copy(alpha = ink.alpha * .62f), fs, maxLines = 1)
                    if (f.clock) {
                        var ms by remember { mutableLongStateOf(0L) }
                        LaunchedEffect(Unit) { while (true) withFrameMillis { ms = System.currentTimeMillis() - m.startedAt } }
                        Txt(" $ms ms", ink.copy(alpha = ink.alpha * .62f), fs, maxLines = 1)
                    }
                }
            }
        }
    }

    val press: () -> Unit = { if (!m.consumeTap() && !m.running) onClick() }
    if (float) {
        // 格子大小不变，按钮从右边往左长出去
        Box(modifier.size(h), contentAlignment = Alignment.CenterEnd) {
            Box(Modifier.wrapContentWidth(Alignment.End, unbounded = true)) {
                Pressable(press, enabled = enabled, label = label) { pr -> pill(pr) }
            }
        }
    } else {
        Pressable(press, modifier, enabled = enabled, label = label) { pr -> pill(pr) }
    }
}

/** 图标位：平常的图标 / 转圈 / 进度环 / 画勾 / !，原地缩放着换。 */
@Composable
private fun Glyph(m: Morph, icon: Ic?, ink: Color) {
    val kind = when (m.look) { Morph.Look.Idle -> 0; Morph.Look.Busy -> 1; Morph.Look.Ok, Morph.Look.Info -> 2; Morph.Look.Bad -> 3 }
    AnimatedContent(
        kind,
        transitionSpec = {
            (fadeIn(tween(220, 60, Curves.decel)) + scaleIn(spring(dampingRatio = .5f, stiffness = 420f), .5f)) togetherWith
                (fadeOut(tween(160, easing = Curves.accel)) + scaleOut(tween(220, easing = Curves.accel), .5f))
        },
        contentAlignment = Alignment.Center,
        label = "mGlyph",
    ) { k ->
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when (k) {
                0 -> icon?.let { SvgIcon(it, ink, 20.dp, stroke = 1.6f) }
                1 -> {
                    val p = m.progress
                    if (p == null) Spin(ink) else Ring(p, ink)
                }
                2 -> Tick(ink)
                else -> Txt("!", ink, 16.sp, weight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Spin(c: Color) {
    val tr = rememberInfiniteTransition(label = "mSpin")
    val deg by tr.animateFloat(0f, 360f, infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Restart), label = "mDeg")
    Canvas(Modifier.size(17.dp)) {
        val sw = 2.dp.toPx(); val r = size.minDimension / 2 - sw / 2
        val tl = Offset(center.x - r, center.y - r); val sz = Size(r * 2, r * 2)
        drawArc(c.copy(alpha = c.alpha * .22f), 0f, 360f, false, tl, sz, style = Stroke(sw))
        rotate(deg) { drawArc(c, 0f, 100f, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round)) }
    }
}

@Composable
private fun Ring(p: Float, c: Color) {
    val arc by animateFloatAsState(p, tween(420, easing = Curves.decel), label = "mArc")
    Canvas(Modifier.size(17.dp)) {
        val sw = 2.dp.toPx(); val r = size.minDimension / 2 - sw / 2
        val tl = Offset(center.x - r, center.y - r); val sz = Size(r * 2, r * 2)
        drawArc(c.copy(alpha = c.alpha * .22f), 0f, 360f, false, tl, sz, style = Stroke(sw))
        drawArc(c, -90f, 360f * arc, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
    }
}

/** 一笔画出的勾：先短后长，280ms 减速，晚 160ms。 */
@Composable
private fun Tick(c: Color) {
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(160); t.animateTo(1f, tween(280, easing = Curves.decel)) }
    Canvas(Modifier.size(18.dp)) {
        val s = size.width / 20f
        val a = Offset(4.5f * s, 10.4f * s); val b = Offset(8.1f * s, 14f * s); val e = Offset(15.5f * s, 6.4f * s)
        val l1 = (b - a).getDistance(); val l2 = (e - b).getDistance()
        val d = t.value * (l1 + l2); val sw = 2.1.dp.toPx()
        if (d > 0f) drawLine(c, a, if (d < l1) a + (b - a) * (d / l1) else b, sw, StrokeCap.Round)
        if (d > l1) drawLine(c, b, b + (e - b) * ((d - l1) / l2), sw, StrokeCap.Round)
    }
}

/** 跟着按钮一起退开的东西（模型卡的小字、行的副标题）：按钮不在平常样子时淡到 [low]。 */
@Composable
fun morphDim(m: Morph, low: Float = 0f): Float {
    val a by animateFloatAsState(if (m.look == Morph.Look.Idle) 1f else low, tween(220, easing = Curves.standard), label = "mDim")
    return a
}

