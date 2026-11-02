package com.fuguang.lueying

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * 变形胶囊（第 377 版，原型 https://claude.ai/artifact/AP7TLzHk22SVxngCmybTsv 第 1 版、VDAUMyKCSFbcBhmTNZ4M4W 第 3 版）。
 *
 * 要等结果的按钮按下后，从按钮的位置、大小、颜色长成一枚深色胶囊（Skin.toast），盖住它所在的那块（一行、一张卡、一排按钮），
 * 报进度和结果，再缩回按钮的样子淡掉，结果留在按钮和旁边的字上。
 * - 尺寸、位置：gentle 弹簧（k260，阻尼 .806，约 2% 过冲），从当前值和速度接着走，可打断。
 * - 颜色：soft 弹簧（k180，阻尼 .894），按钮色 → 胶囊色，Compose 的 lerp 走 Oklab 不发灰。
 * - 内容：出 200ms 加速 + 缩到 .94，入 320ms 减速、晚 70ms，新旧重叠。
 * - 失败：抖 2–3 下（k900，阻尼 .3，初速 -700dp/s），圆变红出「!」，带「重试」；2.8 秒没点就收回。
 * - 成功：圆里画勾（260ms，晚 220ms），停 1.2 秒；点胶囊提前收回。
 * - 收回：先缩回按钮的位置、大小、颜色（380ms），再 140ms 淡掉露出按钮。
 *
 * 用法：容器 Box 里放 [CapsuleLayer]（matchParentSize），按钮加 [capsuleOrigin]，点下去在协程里调 [play] 或 [flash]。
 * 容器里原来的字照 [Capsule.cover] 淡出（盖住多少退多少）。
 */
@Stable
class Capsule {
    enum class Kind { Busy, Ok, Info, Bad }

    /** 胶囊里现在显示什么；null 是没有内容（刚出来收圆时、收回时）。 */
    class Show(val kind: Kind, val title: String, val note: String = "", val action: String = "", val clock: Boolean = false)

    sealed interface End
    /** 成功：标题、小字；action 不空就在胶囊里放个按钮（比如「发给…」），点了跑 onAction。 */
    class Done(val title: String, val note: String = "", val action: String = "", val onAction: (() -> Unit)? = null) : End
    class Failed(val title: String, val note: String = "") : End

    var show by mutableStateOf<Show?>(null)
        private set
    /** 收成转圈小圆那一下。 */
    var squeezing by mutableStateOf(false)
        private set
    /** 进度环（0–1）；null 就是转圈。 */
    var progress by mutableStateOf<Float?>(null)
    /** 进度那一步在做什么，跟在标题后面。 */
    var step by mutableStateOf("")
    var active by mutableStateOf(false)
        private set

    internal var originR by mutableStateOf(Rect.Zero)
    internal var hostR by mutableStateOf(Rect.Zero)
    internal var density = 1f
    internal var pillPx = 0f
    internal var startedAt by mutableLongStateOf(0L)

    internal val x = Animatable(0f)
    internal val y = Animatable(0f)
    internal val w = Animatable(0f)
    internal val h = Animatable(0f)
    internal val tone = Animatable(0f)
    internal val alpha = Animatable(0f)
    internal val shake = Animatable(0f)

    /** 等人点：true 是胶囊里的按钮（重试 / 发给…），false 是点了胶囊别处（提前收回）。 */
    private var tap: CompletableDeferred<Boolean>? = null
    internal fun tapped(act: Boolean) { tap?.complete(act) }

    /** 胶囊盖住了容器多少（0–1）。 */
    val cover: Float
        get() {
            if (!active) return 0f
            val ow = originR.width; val hw = hostR.width
            if (hw <= ow + 1f) return 0f
            return (alpha.value * (w.value - ow) / (hw - ow)).coerceIn(0f, 1f)
        }

    private val gentle = spring<Float>(dampingRatio = .806f, stiffness = 260f)
    private val soft = spring<Float>(dampingRatio = .894f, stiffness = 180f)

    private fun local() = originR.translate(-hostR.left, -hostR.top)

    private suspend fun snapToOrigin() {
        val o = local()
        x.snapTo(o.left); y.snapTo(o.top); w.snapTo(o.width); h.snapTo(o.height)
        tone.snapTo(0f); shake.snapTo(0f); alpha.snapTo(1f)
    }

    private fun kotlinx.coroutines.CoroutineScope.grow() {
        val ph = pillPx
        launch { x.animateTo(0f, gentle) }
        launch { y.animateTo((hostR.height - ph) / 2f, gentle) }
        launch { w.animateTo(hostR.width, gentle) }
        launch { h.animateTo(ph, gentle) }
        launch { tone.animateTo(1f, soft) }
    }

    private suspend fun collapse() = coroutineScope {
        show = null; squeezing = false
        val o = local()
        launch { x.animateTo(o.left, gentle) }
        launch { y.animateTo(o.top, gentle) }
        launch { w.animateTo(o.width, gentle) }
        launch { h.animateTo(o.height, gentle) }
        launch { tone.animateTo(0f, soft) }
        delay(380)
        alpha.animateTo(0f, tween(140, easing = Curves.accel))
    }

    /** 等 [ms] 毫秒：点了按钮 true，点了别处 false，没点 null。 */
    private suspend fun waitTap(ms: Long): Boolean? {
        val d = CompletableDeferred<Boolean>(); tap = d
        val hit = withTimeoutOrNull(ms) { d.await() }
        tap = null
        return hit
    }

    /**
     * 要等的事：[squeeze] 先收成转圈的小圆（整行宽的按钮用）；[busy] 胶囊里的字；[clock] 右边走毫秒数。
     * [work] 里可以改 [progress]、[step]。成功返回 true。
     */
    suspend fun play(busy: String, squeeze: Boolean = false, clock: Boolean = false, work: suspend Capsule.() -> End): Boolean {
        if (active) return false
        active = true
        try {
            return coroutineScope {
                snapToOrigin()
                if (squeeze) {
                    squeezing = true
                    val o = local(); val c = o.height
                    launch { x.animateTo(o.left + (o.width - c) / 2f, gentle) }
                    launch { w.animateTo(c, gentle) }
                    delay(260)
                    squeezing = false
                }
                while (true) {
                    progress = null; step = ""; startedAt = System.currentTimeMillis()
                    show = Show(Kind.Busy, busy, clock = clock)
                    grow()
                    when (val end = work()) {
                        is Done -> {
                            show = Show(Kind.Ok, end.title, end.note, end.action)
                            val hit = waitTap(if (end.action.isNotEmpty()) 2600L else 1200L)
                            collapse()
                            if (hit == true && end.onAction != null) end.onAction.invoke()
                            return@coroutineScope true
                        }
                        is Failed -> {
                            show = Show(Kind.Bad, end.title, end.note, "重试")
                            launch { shake.snapTo(0f); shake.animateTo(0f, spring(dampingRatio = .3f, stiffness = 900f), initialVelocity = -700f * density) }
                            if (waitTap(2800L) != true) { collapse(); return@coroutineScope false }
                        }
                    }
                }
                false
            }
        } finally {
            withContext(NonCancellable) { if (alpha.value > 0f) alpha.snapTo(0f) }
            show = null; squeezing = false; active = false; tap = null
        }
    }

    /** 不用等的结果（开关切过去）：160ms 后从按钮长出来报一句，1.1 秒后收回；点胶囊提前收回。[on] 决定圆是点缀色还是灰。 */
    suspend fun flash(title: String, note: String = "", on: Boolean = true) {
        if (active) return
        active = true
        try {
            coroutineScope {
                delay(160)
                snapToOrigin()
                show = Show(if (on) Kind.Ok else Kind.Info, title, note)
                grow()
                waitTap(1480L)
                collapse()
            }
        } finally {
            withContext(NonCancellable) { if (alpha.value > 0f) alpha.snapTo(0f) }
            show = null; active = false; tap = null
        }
    }
}

/** 按钮记下自己在哪：胶囊从这里长出来，也缩回这里。 */
fun Modifier.capsuleOrigin(cap: Capsule): Modifier = onGloballyPositioned { cap.originR = it.boundsInRoot() }

/**
 * 胶囊画在这里：放进容器 Box，matchParentSize。[from] 是按钮那时的底色（收回时也照它变回去）；[pill] 胶囊高度。
 */
@Composable
fun BoxScope.CapsuleLayer(cap: Capsule, from: Color, pill: Dp = 46.dp) {
    val d = LocalDensity.current
    cap.density = d.density
    cap.pillPx = with(d) { pill.toPx() }
    Box(Modifier.matchParentSize().onGloballyPositioned { cap.hostR = it.boundsInRoot() }) {
        if (cap.alpha.value <= 0f && !cap.active) return@Box
        val t = cap.tone.value.coerceIn(0f, 1f)
        val bg = lerp(from, Skin.toast, t)
        val hostW = with(d) { cap.hostR.width.toDp() }
        // 内容按胶囊长满时的宽度排好，胶囊长的时候只是露出更多，字不挤
        val o = cap.originR
        val rel = if (cap.hostR.width > 0f) (o.center.x - cap.hostR.left) / cap.hostR.width else .5f
        val anchor = when { rel > .62f -> Alignment.End; rel < .38f -> Alignment.Start; else -> Alignment.CenterHorizontally }
        Box(
            Modifier
                .offset { IntOffset((cap.x.value + cap.shake.value).roundToInt(), cap.y.value.roundToInt()) }
                .wrapContentSize(Alignment.TopStart, unbounded = true)
                .size(with(d) { cap.w.value.coerceAtLeast(0f).toDp() }, with(d) { cap.h.value.coerceAtLeast(0f).toDp() })
                .graphicsLayer { alpha = cap.alpha.value }
                .shadow((10f * t).dp, CircleShape, clip = false, ambientColor = Color.Black.copy(alpha = .3f * t), spotColor = Color.Black.copy(alpha = .4f * t))
                .clip(CircleShape)
                .background(bg)
                .pointerInput(Unit) { detectTapGestures { cap.tapped(false) } },
            contentAlignment = Alignment.Center,
        ) {
            val sq by animateFloatAsState(if (cap.squeezing) 1f else 0f, tween(if (cap.squeezing) 200 else 140), label = "capSq")
            if (sq > 0f) Spinner(Modifier.size(20.dp).graphicsLayer { alpha = sq }, Page.ink)
            AnimatedContent(
                cap.show,
                contentKey = { it?.kind },
                transitionSpec = {
                    (fadeIn(tween(320, 70, Curves.decel)) + scaleIn(tween(320, 70, Curves.decel), .94f)) togetherWith
                        (fadeOut(tween(200, easing = Curves.accel)) + scaleOut(tween(200, easing = Curves.accel), .94f)) using SizeTransform(clip = false)
                },
                contentAlignment = Alignment.Center,
                label = "capShow",
            ) { s ->
                if (s == null) Box(Modifier.size(1.dp))
                else Box(Modifier.fillMaxHeight().wrapContentWidth(anchor, unbounded = true).width(hostW)) {
                    PillContent(cap, s, pill)
                }
            }
        }
    }
}

@Composable
private fun PillContent(cap: Capsule, s: Capsule.Show, pill: Dp) {
    val ink = Skin.toastInk
    val muted = Skin.toastInk.copy(alpha = .55f)
    val disc = pill - 16.dp
    Row(
        Modifier.fillMaxSize().padding(start = 8.dp, end = if (s.action.isNotEmpty()) 8.dp else 16.dp).semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Disc(cap, s.kind, disc)
        Spacer(Modifier.width(10.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Txt(s.title, ink, 14.sp, maxLines = 1, weight = FontWeight.Medium)
            if (s.kind == Capsule.Kind.Busy) {
                AnimatedContent(
                    cap.step,
                    transitionSpec = { (fadeIn(tween(240, 40, Curves.decel)) + slideInVertically(tween(260, 40, Curves.decel)) { it / 2 }) togetherWith (fadeOut(tween(120)) + slideOutVertically(tween(140)) { -it / 2 }) },
                    label = "capStep",
                ) { st -> if (st.isNotEmpty()) Txt(" · $st", ink, 14.sp, maxLines = 1) else Box(Modifier) }
            }
        }
        if (s.note.isNotEmpty()) { Spacer(Modifier.width(8.dp)); Txt(s.note, muted, 13.sp, maxLines = 1) }
        if (s.clock) {
            var ms by remember { mutableLongStateOf(0L) }
            LaunchedEffect(Unit) { while (true) withFrameMillis { ms = System.currentTimeMillis() - cap.startedAt } }
            Spacer(Modifier.width(8.dp)); Txt("$ms ms", muted, 13.sp, maxLines = 1)
        }
        if (s.action.isNotEmpty()) {
            Spacer(Modifier.width(10.dp))
            Pressable({ cap.tapped(true) }, label = s.action) { pr ->
                Txt(
                    s.action, Skin.toast, 12.5.sp,
                    Modifier.graphicsLayer { val k = if (pr) .94f else 1f; scaleX = k; scaleY = k }
                        .background(ink.copy(alpha = if (pr) .85f else 1f), RoundedCornerShape(50)).padding(horizontal = 13.dp, vertical = 7.dp),
                    maxLines = 1, weight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** 胶囊左边的圆：转圈 / 进度环 / 画勾 / 红圈「!」。 */
@Composable
private fun Disc(cap: Capsule, kind: Capsule.Kind, size: Dp) {
    val fill = when (kind) {
        Capsule.Kind.Bad -> Skin.danger
        Capsule.Kind.Info -> Skin.toastInk.copy(alpha = .28f)
        else -> Skin.accent
    }
    val mark = Skin.onAccent
    Box(Modifier.size(size).background(fill, CircleShape), contentAlignment = Alignment.Center) {
        when (kind) {
            Capsule.Kind.Busy -> {
                val p = cap.progress
                if (p == null) Spinner(Modifier.size(size * .62f), mark)
                else {
                    val arc by animateFloatAsState(p, tween(420, easing = Curves.decel), label = "capArc")
                    Canvas(Modifier.size(size * .62f)) {
                        val sw = 2.2.dp.toPx(); val r = this.size.minDimension / 2 - sw / 2
                        val tl = Offset(center.x - r, center.y - r); val sz = Size(r * 2, r * 2)
                        drawArc(mark.copy(alpha = .3f), 0f, 360f, false, tl, sz, style = Stroke(sw))
                        drawArc(mark, -90f, 360f * arc, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
                    }
                }
            }
            Capsule.Kind.Ok, Capsule.Kind.Info -> {
                val tick = remember { Animatable(0f) }
                LaunchedEffect(Unit) { delay(220); tick.animateTo(1f, tween(260, easing = Curves.decel)) }
                val c = if (kind == Capsule.Kind.Ok) mark else Skin.toastInk
                Canvas(Modifier.size(size * .56f)) {
                    val s = this.size.width / 16f
                    val a = Offset(3.5f * s, 8.4f * s); val b = Offset(6.3f * s, 11.2f * s); val e = Offset(12.5f * s, 4.8f * s)
                    val l1 = (b - a).getDistance(); val l2 = (e - b).getDistance()
                    val dd = tick.value * (l1 + l2); val sw = 2.dp.toPx()
                    if (dd > 0f) drawLine(c, a, if (dd < l1) a + (b - a) * (dd / l1) else b, sw, StrokeCap.Round)
                    if (dd > l1) drawLine(c, b, b + (e - b) * ((dd - l1) / l2), sw, StrokeCap.Round)
                }
            }
            Capsule.Kind.Bad -> {
                val pop = remember { Animatable(.4f) }
                LaunchedEffect(Unit) { delay(160); pop.animateTo(1f, spring(dampingRatio = .45f, stiffness = 520f)) }
                Txt("!", Skin.toastInk, 15.sp, Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value; alpha = ((pop.value - .4f) / .3f).coerceIn(0f, 1f) }, weight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Spinner(modifier: Modifier, color: Color) {
    val turn = rememberInfiniteTransition(label = "capSpin")
    val deg by turn.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "capDeg")
    Canvas(modifier) {
        val sw = 2.2.dp.toPx(); val r = size.minDimension / 2 - sw / 2
        rotate(deg) {
            drawArc(color, 0f, 110f, false, Offset(center.x - r, center.y - r), Size(r * 2, r * 2), style = Stroke(sw, cap = StrokeCap.Round))
        }
    }
}
