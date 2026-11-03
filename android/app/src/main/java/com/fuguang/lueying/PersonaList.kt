package com.fuguang.lueying

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * 第 381 版：去线框，用色块表达（原型 QVnTjXPLdjY6eLbUt7TiRg 第 4 版、Bvvrc1FEaNqjXgG43Zr65Q 第 5 版）。
 * 卡片不再描边，靠一块比底色深一点的磨砂色块和背景分开；凹下去的东西（输入框、搜索框、分段标签的槽）用更暗的 well。
 */
internal val blockFill: Color get() = Skin.tint(.55f)
internal val blockPress: Color get() = Skin.tint(.72f)
internal val wellFill: Color get() = Skin.well(.24f)

@Composable
internal fun Modifier.block(shape: Shape, blur: Dp, fill: Color = blockFill): Modifier = glass(shape, blur, fill = fill, border = Color.Transparent)

private val backIc = Ic("M14.5 6l-6 6 6 6")
private val nextIc = Ic("M9.5 6l6 6-6 6")
private val plusIc = Ic("M12 6v12M6 12h12")
private val lidIc = Ic("M4.5 7h15M9.5 7V5.2c0-.7.5-1.2 1.2-1.2h2.6c.7 0 1.2.5 1.2 1.2V7")
private val canIc = Ic("M6.5 7l.8 11.3c.1 1 .9 1.7 1.9 1.7h5.6c1 0 1.8-.7 1.9-1.7L17.5 7M10.2 10.5v6M13.8 10.5v6")

private const val ROW_H = 72
private const val ROW_GAP = 10

/**
 * 助理列表（第 381 版）。
 * - 点一下进她的详情；长按 0.42 秒：震一下、卡片浮起来、进编辑态并勾上她，不松手就能直接拖着排序。
 * - 编辑态：右边的 › 依次（每行晚 35ms）交叉过渡成圆勾；点卡片勾选 / 取消（按多久都算点），手指挪过 touch slop 才浮起来拖。
 * - 右上角 + 转 135° 淡出，垃圾桶带回弹长出来；有勾选时变红、盖子掀开 14°。没勾或全勾时按钮晃一下并提示（至少留一位）。
 * - 点垃圾桶：确认小卡从垃圾桶那里长出来，带回弹落在正中（k380 阻尼 .74），背后压暗；取消原路缩回（240ms accel）。
 * - 删除：选中的几行依次（间隔 40ms）往左收拢淡出，删完退出编辑，底下 4 秒可撤销。
 * - 左上角返回变 ✕，点它或系统返回键退出编辑。
 */
@Composable
internal fun ColumnScope.AssistantList(compact: Boolean, blur: Dp, nav: MineNav) {
    var edit by remember { mutableStateOf(false) }
    val sel = remember { mutableStateListOf<String>() }
    val going = remember { mutableStateListOf<String>() }
    val fresh = remember { mutableStateListOf<String>() }
    var asking by remember { mutableStateOf(false) }
    var undo by remember { mutableStateOf<List<Triple<Persona, Int, Boolean>>?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }
    var trashAt by remember { mutableStateOf(Offset.Zero) }
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val shake = remember { Animatable(0f) }

    fun leave() { edit = false; sel.clear() }
    fun toggle(id: String) {
        if (id in sel) sel.remove(id) else sel.add(id)
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    fun nudge(msg: String) {
        hint = msg; undo = null
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        val d = density.density
        scope.launch {
            shake.animateTo(0f, keyframes {
                durationMillis = 360
                -5f * d at 72
                4f * d at 144
                -3f * d at 216
                2f * d at 288
            })
        }
    }
    androidx.activity.compose.BackHandler(enabled = edit && !asking) { leave() }
    LaunchedEffect(undo) { if (undo != null) { delay(4000); undo = null } }
    LaunchedEffect(hint) { if (hint != null) { delay(1600); hint = null } }
    LaunchedEffect(fresh.size) { if (fresh.isNotEmpty()) { delay(600); fresh.clear() } }

    // ---- 页头：‹ ↔ ✕，标题换字，+ ↔ 垃圾桶 ----
    Row(Modifier.fillMaxWidth().padding(bottom = 22.dp, end = LocalTitleEnd.current), verticalAlignment = Alignment.CenterVertically) {
        Pressable({ if (edit) leave() else nav.back() }, label = if (edit) "退出编辑" else "返回") { pressed ->
            val e by animateFloatAsState(if (edit) 1f else 0f, tween(260, easing = Curves.standard), label = "backX")
            val ink = if (pressed) Page.muted else Page.ink
            Box(Modifier.padding(end = 10.dp, top = 4.dp, bottom = 4.dp).size(24.dp)) {
                SvgIcon(backIc, ink, 24.dp, modifier = Modifier.graphicsLayer { alpha = 1f - e; rotationZ = 90f * e; val k = 1f - .4f * e; scaleX = k; scaleY = k })
                SvgIcon(Icons.close, ink, 24.dp, stroke = 1.5f, modifier = Modifier.graphicsLayer { alpha = e; rotationZ = -90f * (1f - e); val k = .6f + .4f * e; scaleX = k; scaleY = k })
            }
        }
        val fs = if (compact) 24f else 28f
        val title = when { !edit -> "助理"; sel.isEmpty() -> "选择助理"; else -> "已选 ${sel.size} 位" }
        AnimatedContent(
            title, Modifier.weight(1f),
            transitionSpec = {
                (fadeIn(tween(200, 60)) + slideInVertically(tween(260, easing = Curves.standard)) { it / 2 }) togetherWith
                    (fadeOut(tween(140)) + slideOutVertically(tween(200, easing = Curves.accel)) { -it / 2 }) using SizeTransform(clip = false)
            },
            contentAlignment = Alignment.CenterStart, label = "title",
        ) { t ->
            val big = t == "助理"
            Txt(t, Page.ink, (if (big) fs else fs * .8f).sp, spacing = ls(if (big) 4f else 1.5f, fs), family = Serif, maxLines = 1)
        }
        val armed = edit && sel.isNotEmpty()
        val m by animateFloatAsState(if (edit) 1f else 0f, spring(dampingRatio = .62f, stiffness = 380f), label = "plusTrash")
        val lid by animateFloatAsState(if (armed) 1f else 0f, spring(dampingRatio = .5f, stiffness = 500f), label = "lid")
        val disc by animateColorAsState(
            when { asking -> Skin.danger.copy(alpha = .32f); armed -> Skin.danger.copy(alpha = .22f); edit -> veil(.08f); else -> Skin.accent.copy(alpha = .14f) },
            tween(220), label = "disc",
        )
        val ic by animateColorAsState(when { armed || asking -> Skin.danger; edit -> Page.muted; else -> Skin.accent }, tween(220), label = "ic")
        Pressable(
            {
                when {
                    !edit -> { val n = Memory.create(); nav.open(n.id, 0) }
                    sel.isEmpty() -> nudge("先勾选要删的助理")
                    sel.size >= Memory.all.size -> nudge("至少要留一位助理")
                    else -> { hint = null; asking = true }
                }
            },
            Modifier.onGloballyPositioned { trashAt = it.boundsInWindow().center }.graphicsLayer { translationX = shake.value },
            label = if (edit) "删除选中的助理" else "新建助理",
        ) { p ->
            val k by animateFloatAsState(if (p) .9f else 1f, if (p) tween(70) else spring(dampingRatio = .5f, stiffness = 600f), label = "discPress")
            Box(Modifier.size(36.dp).graphicsLayer { scaleX = k; scaleY = k }.background(disc, CircleShape), contentAlignment = Alignment.Center) {
                SvgIcon(plusIc, ic, 18.dp, stroke = 1.7f, modifier = Modifier.graphicsLayer {
                    alpha = (1f - m * 1.4f).coerceIn(0f, 1f); rotationZ = 135f * m; val s = 1f - .5f * m.coerceIn(0f, 1f); scaleX = s; scaleY = s
                })
                Box(Modifier.size(19.dp).graphicsLayer {
                    alpha = m.coerceIn(0f, 1f); val s = .55f + .45f * m; scaleX = s; scaleY = s; rotationZ = -25f * (1f - m)
                }) {
                    SvgIcon(canIc, ic, 19.dp, stroke = 1.7f)
                    SvgIcon(lidIc, ic, 19.dp, stroke = 1.7f, modifier = Modifier.graphicsLayer {
                        transformOrigin = TransformOrigin(5f / 24f, 7f / 24f); rotationZ = -14f * lid; translationY = -1.dp.toPx() * lid
                    })
                }
            }
        }
    }

    // ---- 列表 ----
    Box(Modifier.weight(1f).fillMaxWidth()) {
        val list = Memory.all.toList()
        val step = with(density) { (ROW_H + ROW_GAP).dp.toPx() }
        var dragId by remember { mutableStateOf<String?>(null) }
        var raw by remember { mutableFloatStateOf(0f) }
        var settling by remember { mutableStateOf(false) }
        var snapBack by remember { mutableStateOf(false) }
        var slot by remember { mutableStateOf(-1) }
        val drop = remember { Animatable(0f) }
        val from = list.indexOfFirst { it.id == dragId }
        // 手指带着走，拖出两头打三五折
        val dy = if (from < 0) 0f else {
            val lo = -from * step
            val hi = (list.lastIndex - from) * step
            when { raw < lo -> lo + (raw - lo) * .35f; raw > hi -> hi + (raw - hi) * .35f; else -> raw }
        }
        val target = if (from < 0) -1 else (from + kotlin.math.round(dy / step).toInt()).coerceIn(0, list.lastIndex)
        LaunchedEffect(target) {
            if (dragId != null && !settling && target != slot) {
                if (slot >= 0) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                slot = target
            }
        }
        fun end() {
            if (dragId == null || settling) return
            settling = true
            val f = from; val to = target.coerceAtLeast(0)
            val start = dy
            scope.launch {
                drop.snapTo(start)
                drop.animateTo((to - f) * step, spring(dampingRatio = .7f, stiffness = 420f))
                snapBack = true
                Memory.move(f, to, persist = true)
                dragId = null; raw = 0f; slot = -1; settling = false
                withFrameNanos { }
                snapBack = false
            }
        }
        val endRef = rememberUpdatedState<() -> Unit>({ end() })
        val editRef = rememberUpdatedState(edit)

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            list.forEachIndexed { i, p ->
                key(p.id) {
                    val vis = remember(p.id) { MutableTransitionState(p.id !in fresh) }
                    vis.targetState = p.id !in going
                    val gi = going.indexOf(p.id).coerceAtLeast(0)
                    AnimatedVisibility(
                        vis,
                        enter = expandVertically(spring(dampingRatio = .806f, stiffness = 260f)) + fadeIn(tween(240, 80)) + scaleIn(tween(320, 80, Curves.standard), .96f),
                        exit = shrinkVertically(tween(300, gi * 40, Curves.accel)) + fadeOut(tween(200, gi * 40, Curves.accel)) +
                            slideOutHorizontally(tween(300, gi * 40, Curves.accel)) { -it / 16 },
                    ) {
                        val shift = when {
                            from < 0 || i == from -> 0f
                            target < from && i in target until from -> step
                            target > from && i in from + 1..target -> -step
                            else -> 0f
                        }
                        val moved by animateFloatAsState(shift, if (snapBack) snap() else tween(240, easing = Curves.standard), label = "shift")
                        val mine = p.id == dragId
                        val lifted = mine && !settling
                        val lift by animateFloatAsState(
                            if (lifted) 1f else 0f,
                            if (lifted) spring(dampingRatio = .5f, stiffness = 500f) else tween(260, easing = Curves.standard), label = "lift",
                        )
                        var pressed by remember { mutableStateOf(false) }
                        Box(
                            Modifier.padding(bottom = ROW_GAP.dp)
                                .zIndex(if (mine) 1f else 0f)
                                .graphicsLayer {
                                    translationY = if (mine) (if (settling) drop.value else dy) else moved
                                    val s = 1f + .03f * lift; scaleX = s; scaleY = s
                                    shadowElevation = 18.dp.toPx() * lift
                                    shape = RoundedCornerShape(20.dp); clip = false
                                }
                                .pointerInput(p.id) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        val slop = viewConfiguration.touchSlop
                                        pressed = true
                                        var lift0 = false
                                        if (!editRef.value) {
                                            // 0.42 秒内松手是点；先挪开就让给滚动；按满就进编辑并浮起来
                                            val r: Int? = withTimeoutOrNull(420L) {
                                                while (true) {
                                                    val ev = awaitPointerEvent()
                                                    val c = ev.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull 2
                                                    if (!c.pressed) return@withTimeoutOrNull 1
                                                    if ((c.position - down.position).getDistance() > slop) return@withTimeoutOrNull 2
                                                }
                                                @Suppress("UNREACHABLE_CODE") 0
                                            }
                                            when (r) {
                                                null -> {
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    edit = true; undo = null
                                                    if (p.id !in sel) sel.add(p.id)
                                                    dragId = p.id; raw = 0f; slot = Memory.all.indexOf(p)
                                                    lift0 = true
                                                }
                                                1 -> { pressed = false; nav.open(p.id, 2) }
                                                else -> pressed = false
                                            }
                                        } else {
                                            // 编辑态：按多久都算点；挪过 slop 才浮起来拖
                                            while (true) {
                                                val ev = awaitPointerEvent()
                                                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                                if (!c.pressed) { pressed = false; toggle(p.id); break }
                                                if ((c.position - down.position).getDistance() > slop) {
                                                    c.consume()
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    dragId = p.id; raw = 0f; slot = Memory.all.indexOf(p)
                                                    lift0 = true; break
                                                }
                                            }
                                        }
                                        if (lift0) {
                                            pressed = false
                                            while (true) {
                                                val ev = awaitPointerEvent()
                                                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                                if (!c.pressed) break
                                                raw += c.positionChange().y
                                                c.consume()
                                            }
                                            endRef.value()
                                        }
                                        pressed = false
                                    }
                                },
                        ) {
                            PersonaCard(p, i, edit, p.id in sel, pressed, lift, blur)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // 底下的提示条：删完 4 秒可撤销；没勾 / 全勾时一句提示
        val msg = undo?.let { u -> if (u.size == 1) "已删除 ${u[0].first.name} 和她的对话" else "已删除 ${u.size} 位助理和她们的对话" } ?: hint
        androidx.compose.animation.AnimatedVisibility(
            msg != null,
            Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            enter = slideInVertically(tween(320, easing = Curves.decel)) { it } + fadeIn(tween(220)),
            exit = slideOutVertically(tween(200, easing = Curves.accel)) { it } + fadeOut(tween(160)),
        ) {
            var last by remember { mutableStateOf("") }
            if (msg != null) last = msg
            Row(
                Modifier.widthIn(max = 520.dp).fillMaxWidth().background(Skin.toast, RoundedCornerShape(16.dp))
                    .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp).height(40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Txt(last, Skin.toastInk, 13.5.sp, Modifier.weight(1f), maxLines = 1)
                if (undo != null) Pressable({
                    undo?.sortedBy { it.second }?.forEach { (p, i, was) -> fresh.add(p.id); Memory.restore(p, i, was) }
                    undo = null
                }, label = "撤销") { pr ->
                    Txt("撤销", Skin.accent, 13.5.sp, Modifier.background(if (pr) Skin.toastInk.copy(alpha = .1f) else Color.Transparent, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 9.dp))
                }
            }
        }
    }

    val names = Memory.all.filter { it.id in sel }.map { it.name }
    DeleteAsk(asking, names, trashAt, onNo = { asking = false }) {
        val ids = sel.toList()
        val packs = Memory.all.mapIndexedNotNull { i, p -> if (p.id in ids) Triple(p, i, p.id == Memory.activeId) else null }
        asking = false
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        going.addAll(ids)
        scope.launch {
            delay(320L + ids.size * 40L)
            packs.forEach { Memory.delete(it.first) }
            going.removeAll(ids)
            leave()
            undo = packs
        }
    }
}

@Composable
private fun PersonaCard(p: Persona, i: Int, edit: Boolean, selected: Boolean, pressed: Boolean, lift: Float, blur: Dp) {
    val e by animateFloatAsState(if (edit) 1f else 0f, tween(220, if (edit) i * 35 else 0, Curves.standard), label = "edit")
    val s by animateFloatAsState(if (selected) 1f else 0f, spring(dampingRatio = .6f, stiffness = 500f), label = "sel")
    val fill by animateColorAsState(
        when {
            lift > .5f -> Skin.pop
            selected -> lerp(blockFill, Skin.accent.copy(alpha = blockFill.alpha), .18f)
            pressed -> blockPress
            else -> blockFill
        },
        tween(if (pressed) 80 else 220), label = "fill",
    )
    val on = p.id == Memory.activeId
    Row(
        Modifier.fillMaxWidth().height(ROW_H.dp).block(RoundedCornerShape(20.dp), blur, fill).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Initial(p.name, 44.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(p.name, Page.ink, 16.sp, maxLines = 1, family = Serif)
                if (on) {
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(5.dp).background(Skin.accent, CircleShape))
                    Spacer(Modifier.width(5.dp))
                    Txt("正在陪你", Skin.accent, 11.5.sp, maxLines = 1)
                }
            }
            Spacer(Modifier.height(4.dp))
            Txt(
                listOfNotNull(if (p.memOn) "记得 ${p.items.size} 件事" else "记忆已关", if (p.chats > 0) "聊过 ${p.chats} 次" else null).joinToString(" · "),
                Page.muted, 12.5.sp, maxLines = 1,
            )
        }
        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
            SvgIcon(nextIc, Page.muted, 18.dp, modifier = Modifier.graphicsLayer { alpha = 1f - e; translationX = -8.dp.toPx() * e })
            CheckDot(s, Modifier.graphicsLayer { alpha = e; val k = .5f + .5f * e; scaleX = k; scaleY = k })
        }
    }
}

/** 圆勾：没勾是一块淡色圆，勾上填点缀色、勾从左往右画出来。 */
@Composable
internal fun CheckDot(s: Float, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val off = veil(.10f)
    val on = Skin.accent
    val ink = Skin.onAccent
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2f * (1f + .06f * s)
        drawCircle(lerp(off, on, s.coerceIn(0f, 1f)), r)
        val t = s.coerceIn(0f, 1f)
        if (t > 0f) {
            val u = this.size.minDimension / 24f
            val a = Offset(7.4f * u, 12.3f * u); val b = Offset(10.6f * u, 15.5f * u); val c = Offset(16.8f * u, 8.9f * u)
            val l1 = (b - a).getDistance(); val l2 = (c - b).getDistance()
            val len = (l1 + l2) * t
            val w = 2f * u
            if (len <= l1) drawLine(ink, a, a + (b - a) * (len / l1), w, StrokeCap.Round)
            else {
                drawLine(ink, a, b, w, StrokeCap.Round)
                drawLine(ink, b, b + (c - b) * ((len - l1) / l2), w, StrokeCap.Round)
            }
        }
    }
}

/** 删除确认：从垃圾桶 [from]（窗口坐标）长出来，落在正中；关掉时缩回去。 */
@Composable
private fun DeleteAsk(open: Boolean, names: List<String>, from: Offset, onNo: () -> Unit, onYes: () -> Unit) {
    val t = remember { Animatable(0f) }
    var shown by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    if (open && names.isNotEmpty()) title = if (names.size == 1) "删除 ${names[0]}？" else "删除 ${names.size} 位助理？"
    LaunchedEffect(open) {
        if (open) { shown = true; t.animateTo(1f, spring(dampingRatio = .74f, stiffness = 380f)) }
        else if (shown) { t.animateTo(0f, tween(240, easing = Curves.accel)); shown = false }
    }
    if (!shown && !open) return
    Popup(
        popupPositionProvider = remember {
            object : PopupPositionProvider {
                override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset.Zero
            }
        },
        onDismissRequest = onNo,
        properties = PopupProperties(focusable = true),
    ) {
        var center by remember { mutableStateOf(Offset.Zero) }
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = t.value.coerceIn(0f, 1f) }
                .background(Color.Black.copy(alpha = .5f))
                .pointerInput(Unit) { detectTapGestures { if (open) onNo() } },
        )
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.padding(horizontal = 40.dp).widthIn(max = 320.dp).fillMaxWidth()
                    .onGloballyPositioned { center = it.boundsInWindow().center }
                    .graphicsLayer {
                        val k = t.value
                        alpha = k.coerceIn(0f, 1f)
                        val sc = .16f + .84f * k; scaleX = sc; scaleY = sc
                        if (center != Offset.Zero && from != Offset.Zero) {
                            translationX = (from.x - center.x) * (1f - k); translationY = (from.y - center.y) * (1f - k)
                        }
                    }
                    .pointerInput(Unit) { detectTapGestures { } }
                    .background(Skin.pop, RoundedCornerShape(22.dp))
                    .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Txt(title, Page.ink, 17.sp, family = Serif, maxLines = 1)
                Spacer(Modifier.height(5.dp))
                Txt("对话和记得的事一起删掉，4 秒内可撤销", Page.muted, 12.5.sp, maxLines = 2)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PressSurface(onNo, Modifier.weight(1f).height(40.dp), RoundedCornerShape(12.dp), fill = veil(.08f), pressedFill = veil(.14f), pressScale = .96f, label = "取消") {
                        Txt("取消", Page.ink, 14.sp, Modifier.align(Alignment.Center))
                    }
                    PressSurface(
                        { if (open) onYes() }, Modifier.weight(1f).height(40.dp), RoundedCornerShape(12.dp),
                        fill = Skin.danger.copy(alpha = .9f), pressedFill = Skin.danger.copy(alpha = .7f), ripple = Color.White, pressScale = .96f, label = "删除",
                    ) {
                        Txt("删除", if (Skin.light) Color.White else Color(0xFF2A1513), 14.sp, Modifier.align(Alignment.Center), weight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}
