package com.fuguang.lueying

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val warm = Color(0xFFE9B48E)
private val bad = Color(0xFFD79A94)
private val stripFill = Color(0xFF323844)
private val down = Ic("M12 4v10M8 10.5l4 4 4-4M5 16.5v2.5h14v-2.5")
private val doc = Ic("M6 4h8l4 4v12H6ZM14 4v4h4M9 13h6M9 16.5h4")
private val box = Ic("M4 8l8-4 8 4v8l-8 4-8-4ZM4 8l8 4 8-4M12 12v8")
private val chevron = Ic("M9.5 6l6 6-6 6")

/** 光点能飞出长条的余量。 */
private val SPILL_TOP = 90.dp
private val SPILL_SIDE = 24.dp

private sealed interface Phase {
    object Choose : Phase
    data class Work(val pack: Boolean, val step: Int) : Phase
    data class Done(val pack: Boolean, val r: Backup.Result) : Phase
    data class Fail(val pack: Boolean, val why: String) : Phase
}

/**
 * 「我的」卡片里的「备份」行。点一下，从这一行上方长出一条和它一样宽的扁长条：左「备份数据」右「打包」。
 * 选一块：另一块退开，换成一行进度圈 + 步骤字 → 走满画出 ✓、轻弹，停一拍杏色光点迸开、轻震 → 写「存在 下载/浮光掠影」+「发给… / 好」。
 * 点「好」或空白：长条缩回这一行，副标题换成「刚刚备份 · 3.8 MB」。
 */
@Composable
internal fun BackupRow(i: Int, t: Float) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val shown = remember { MutableTransitionState(false) }
    var phase by remember { mutableStateOf<Phase>(Phase.Choose) }
    var line by remember { mutableStateOf(Backup.lastLine()) }
    var sizes by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var rowW by remember { mutableStateOf(0) }
    val working = phase is Phase.Work

    fun close() { if (!working) shown.targetState = false }
    // 收回去以后再换副标题、复位
    LaunchedEffect(shown.isIdle, shown.currentState) {
        if (shown.isIdle && !shown.currentState) { line = Backup.lastLine(); phase = Phase.Choose }
    }
    fun start(pack: Boolean) {
        phase = Phase.Work(pack, -1)
        scope.launch {
            delay(380)
            var last = System.currentTimeMillis()
            phase = runCatching {
                val r = Backup.run(ctx, pack) { s ->
                    // 每一步至少停一会儿，让人看得见
                    val gap = System.currentTimeMillis() - last
                    if (gap < 420) delay(420 - gap)
                    last = System.currentTimeMillis()
                    phase = Phase.Work(pack, s)
                }
                delay(260)
                Phase.Done(pack, r)
            }.getOrElse { e ->
                Phase.Fail(pack, if (e is Backup.NoRoom) "空间不够，还差 ${Backup.mb(e.short)}" else "没存成：${e.message?.take(18) ?: "出了点问题"}")
            }
            if (phase is Phase.Fail) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    Box(Modifier.onSizeChanged { rowW = it.width }) {
        MineRow(i, t, "备份", "", {
            if (shown.targetState) close()
            else { shown.targetState = true; scope.launch { sizes = Backup.sizes(ctx) } }
        }, glyph = { SvgIcon(down, warm, 19.dp) }, subSlot = {
            // 副标题换字时交叉过渡（收回以后才换成「刚刚备份」）
            AnimatedContent(
                line,
                transitionSpec = { (fadeIn(tween(260, 60)) + slideInVertically(tween(300, 60, Curves.standard)) { it / 2 }) togetherWith (fadeOut(tween(140)) + slideOutVertically(tween(160)) { -it / 2 }) },
                label = "line",
            ) { Txt(it, if (it.startsWith("刚刚")) warm else Page.muted, 12.sp, maxLines = 1) }
        }) {
            SvgIcon(chevron, Page.muted.copy(alpha = .6f), 15.dp, stroke = 1.8f)
        }

        if (shown.currentState || shown.targetState) {
            val d = LocalDensity.current
            val spillTop = with(d) { SPILL_TOP.roundToPx() }
            val spillSide = with(d) { SPILL_SIDE.roundToPx() }
            val gap = with(d) { 8.dp.roundToPx() }
            Popup(
                popupPositionProvider = remember(spillTop, spillSide, gap) {
                    object : PopupPositionProvider {
                        override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
                            IntOffset(anchorBounds.left - spillSide, anchorBounds.top - popupContentSize.height - gap)
                    }
                },
                onDismissRequest = { close() },
                properties = PopupProperties(focusable = true, clippingEnabled = false),
            ) {
                BackupStrip(shown, phase, sizes, with(d) { rowW.toDp() }, onPick = ::start, onClose = ::close, onAgain = { phase = Phase.Choose }, onShare = { r -> Backup.share(ctx, r) })
            }
        }
    }
}

@Composable
private fun BackupStrip(
    shown: MutableTransitionState<Boolean>, phase: Phase, sizes: Pair<Long, Long>?, width: androidx.compose.ui.unit.Dp,
    onPick: (Boolean) -> Unit, onClose: () -> Unit, onAgain: () -> Unit, onShare: (Backup.Result) -> Unit,
) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    var burstAt by remember { mutableStateOf<Offset?>(null) }
    Box(Modifier.width(width + SPILL_SIDE * 2).onGloballyPositioned { origin = it.boundsInRoot().topLeft }) {
        Column(Modifier.padding(start = SPILL_SIDE, end = SPILL_SIDE, top = SPILL_TOP)) {
            AnimatedVisibility(
                shown,
                enter = fadeIn(tween(200)) + scaleIn(spring(dampingRatio = .72f, stiffness = 520f), .94f, TransformOrigin(.5f, 1f)),
                exit = fadeOut(tween(140, easing = Curves.accel)) + scaleOut(tween(200, easing = Curves.accel), .94f, TransformOrigin(.5f, 1f)),
            ) {
                Column(Modifier.fillMaxWidth().background(stripFill, RoundedCornerShape(20.dp)).padding(6.dp).animateContentSize(spring(dampingRatio = .9f, stiffness = 500f))) {
                    AnimatedContent(
                        phase is Phase.Choose,
                        transitionSpec = { (fadeIn(tween(240, 120)) + slideInVertically(tween(280, 120, Curves.standard)) { it / 6 }) togetherWith fadeOut(tween(150, easing = Curves.accel)) },
                        label = "strip",
                    ) { choosing ->
                        if (choosing) Tiles(sizes, onPick)
                        else WorkLine(phase, onCheck = { burstAt = it - origin })
                    }
                    val end = phase is Phase.Done || phase is Phase.Fail
                    AnimatedVisibility(end, enter = fadeIn(tween(260, 160)) + expandVertically(tween(280, 80, Curves.standard)), exit = fadeOut(tween(120))) {
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            when (phase) {
                                is Phase.Done -> { Pill("发给…", false, Modifier.weight(1f)) { onShare(phase.r) }; Pill("好", true, Modifier.weight(1f), onClose) }
                                is Phase.Fail -> { Pill("算了", false, Modifier.weight(1f), onClose); Pill("再试一次", true, Modifier.weight(1f), onAgain) }
                                else -> {}
                            }
                        }
                    }
                }
            }
        }
        burstAt?.let { Burst(it, Modifier.matchParentSize()) { burstAt = null } }
    }
}

@Composable
private fun Tiles(sizes: Pair<Long, Long>?, onPick: (Boolean) -> Unit) {
    var picked by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(picked) { picked?.let { delay(200); onPick(it) } }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(false, true).forEach { pack ->
            val gone by animateFloatAsState(if (picked != null && picked != pack) 1f else 0f, tween(200, easing = Curves.standard), label = "gone")
            PressSurface(
                { if (picked == null) picked = pack },
                Modifier.weight(1f).graphicsLayer { alpha = 1f - gone; scaleX = 1f - .06f * gone; scaleY = 1f - .06f * gone },
                shape = RoundedCornerShape(15.dp),
                fill = if (picked == pack) warm.copy(alpha = .14f) else Color.White.copy(alpha = .05f),
                pressedFill = Color.White.copy(alpha = .09f),
                pressScale = .97f,
                label = if (pack) "打包：备份数据加上安装包" else "备份数据",
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph(if (pack) box else doc)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Txt(if (pack) "打包" else "备份数据", Page.ink, 14.sp, maxLines = 1)
                        Txt(sizes?.let { Backup.mb(if (pack) it.second else it.first) } ?: "……", Page.muted.copy(alpha = .62f), 11.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun Glyph(icon: Ic) {
    Box(Modifier.size(32.dp).background(warm.copy(alpha = .12f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) { SvgIcon(icon, warm, 17.dp) }
}

/** 进行中的那一行：图标、标题、步骤字，右边进度圈 → ✓ 或红圈。 */
@Composable
private fun WorkLine(phase: Phase, onCheck: (Offset) -> Unit) {
    val pack = when (phase) { is Phase.Work -> phase.pack; is Phase.Done -> phase.pack; is Phase.Fail -> phase.pack; else -> false }
    val steps = if (pack) listOf("整理对话和记忆", "打包作品", "写入安装包", "存进下载") else listOf("整理对话和记忆", "打包作品", "存进下载")
    val (title, sub) = when (phase) {
        is Phase.Done -> (if (pack) "打包好了" else "备份好了") to "存在 下载/${Backup.FOLDER}"
        is Phase.Fail -> "没存成" to phase.why
        is Phase.Work -> (if (pack) "打包" else "备份数据") to (if (phase.step < 0) "准备中" else steps[(phase.step + 1).coerceAtMost(steps.lastIndex)])
        else -> "" to ""
    }
    val frac = when (phase) { is Phase.Work -> (phase.step + 1f) / steps.size; is Phase.Fail -> .55f; else -> 1f }
    val shake = remember { Animatable(0f) }
    LaunchedEffect(phase is Phase.Fail) {
        if (phase is Phase.Fail) shake.animateTo(0f, keyframes { durationMillis = 360; -5f at 70; 4f at 160; -2f at 250; 0f at 360 })
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 46.dp).graphicsLayer { translationX = shake.value * density }.padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(if (pack) box else doc)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            AnimatedContent(title, transitionSpec = { fadeIn(tween(220, 60)) togetherWith fadeOut(tween(120)) }, label = "t") { Txt(it, Page.ink, 14.sp, maxLines = 1) }
            AnimatedContent(
                sub,
                transitionSpec = { (fadeIn(tween(240, 60)) + slideInVertically(tween(280, 60, Curves.standard)) { it / 2 }) togetherWith (fadeOut(tween(130)) + slideOutVertically(tween(160)) { -it / 2 }) },
                label = "s",
            ) { Txt(it, Page.muted, 11.5.sp, maxLines = 1) }
        }
        Spacer(Modifier.width(8.dp))
        Ring(frac, phase is Phase.Done, phase is Phase.Fail, onCheck)
    }
}

/** 进度圈：转到 frac；完成时圈淡去、一笔画出 ✓、整颗轻弹，停一拍再报光点的位置。 */
@Composable
private fun Ring(frac: Float, done: Boolean, failed: Boolean, onCheck: (Offset) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val arc by animateFloatAsState(frac, tween(380, easing = Curves.standard), label = "arc")
    val ringA by animateFloatAsState(if (done) 0f else 1f, tween(180), label = "ringA")
    val tick = remember { Animatable(0f) }
    val pop = remember { Animatable(1f) }
    var center by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(done) {
        if (!done) return@LaunchedEffect
        launch { pop.snapTo(.86f); pop.animateTo(1f, spring(dampingRatio = .38f, stiffness = 520f)) }
        tick.animateTo(1f, tween(240, easing = Curves.decel))
        delay(120)
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        onCheck(center)
    }
    val color = if (failed) bad else warm
    Canvas(Modifier.size(32.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value }.onGloballyPositioned { center = it.boundsInRoot().center }) {
        val w = 2.dp.toPx()
        val r = size.minDimension / 2 - w
        drawCircle(color.copy(alpha = .16f * ringA), r, style = Stroke(w))
        drawArc(color.copy(alpha = ringA), -90f, 360f * arc, false, topLeft = Offset(w, w), size = androidx.compose.ui.geometry.Size(r * 2, r * 2), style = Stroke(w, cap = StrokeCap.Round))
        if (tick.value > 0f) {
            val s = size.minDimension / 32f
            val a = Offset(10.5f * s, 16.5f * s); val b = Offset(14.5f * s, 20.5f * s); val c = Offset(22f * s, 12f * s)
            val l1 = (b - a).getDistance(); val l2 = (c - b).getDistance(); val p = tick.value * (l1 + l2)
            val st = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round)
            drawLine(warm, a, if (p < l1) a + (b - a) * (p / l1) else b, st.width, StrokeCap.Round)
            if (p > l1) drawLine(warm, b, b + (c - b) * ((p - l1) / l2), st.width, StrokeCap.Round)
        }
    }
}

@Composable
private fun Pill(text: String, main: Boolean, modifier: Modifier, onClick: () -> Unit) {
    PressSurface(onClick, modifier.height(38.dp), fill = if (main) warm else Color.White.copy(alpha = .07f), pressedFill = if (main) warm.copy(alpha = .85f) else Color.White.copy(alpha = .12f), pressScale = .96f, label = text) {
        Txt(text, if (main) Color(0xFF2A211B) else Page.ink.copy(alpha = .86f), 13.5.sp)
    }
}
