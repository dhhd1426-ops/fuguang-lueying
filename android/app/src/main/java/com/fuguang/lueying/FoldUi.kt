package com.fuguang.lueying

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * 前情的界面（第 394 版，原型 https://claude.ai/artifact/Jr9YNGMkbogtrGgpf1GcKY 第 5 版）：
 * - 聊天里一条分隔线插在整理过的和原样留着的中间：整理时小点呼吸「正在整理前面的 N 条」，好了换成「前面 N 条整理成了 M 段 ›」；
 * - 点它从那里长出前情卡（CenterCardLayer）：要求（「−」删，4 秒内能撤销）、几段前情（点开看写了什么、「看原话」滚过去亮一下）、重新整理；
 * - 记忆页「前情」卡点进去：提示词框，右上角模型胶囊（方案 1：点了列表从底下升起）。说明字一概不加。
 */

object FoldUi {
    /** 「看原话」：滚到这几条（序号从 1 数）。 */
    var jump by mutableStateOf<IntRange?>(null)
    /** 滚到以后亮一下的那几条。 */
    var glow by mutableStateOf<IntRange?>(null)
}

object FoldCard : FloatCard() {
    var conv by mutableStateOf("")

    fun show(id: String, at: Offset) { conv = id; from = at; open = true }
}

private val caret = Ic("M6.5 9.5l5.5 5.5 5.5-5.5")
private val minus = Ic("M6 12h12")

/** 文字换掉：旧的往上淡出（140ms 加速），新的从下面上来（240ms 减速）。 */
@Composable
internal fun SwapText(text: String, color: androidx.compose.ui.graphics.Color, size: androidx.compose.ui.unit.TextUnit, modifier: Modifier = Modifier, family: FontFamily = Sans) {
    AnimatedContent(
        text,
        modifier,
        transitionSpec = {
            (slideInVertically(tween(240, easing = Curves.decel)) { it / 3 } + fadeIn(tween(240, easing = Curves.decel))) togetherWith
                (slideOutVertically(tween(140, easing = Curves.accel)) { -it / 3 } + fadeOut(tween(140, easing = Curves.accel)))
        },
        label = "foldSwap",
    ) { Txt(it, color, size, maxLines = 1, family = family) }
}

/** 整理时呼吸的小点（不整理时不起无限动画）。 */
@Composable
private fun BreathDot(busy: Boolean) {
    if (busy) {
        val inf = rememberInfiniteTransition(label = "foldBreath")
        val a = inf.animateFloat(1f, .25f, infiniteRepeatable(tween(550, easing = Curves.standard), RepeatMode.Reverse), label = "foldDot")
        Box(Modifier.size(6.dp).graphicsLayer { alpha = a.value; val s = .7f + .3f * a.value; scaleX = s; scaleY = s }.background(Skin.accent, CircleShape))
    } else Box(Modifier.size(6.dp).background(Skin.accent, CircleShape))
}

/** 聊天里的分隔线。busyAt 不是 null：正在整理到那一条。 */
@Composable
fun FoldDivider(convId: String, f: FoldData?, busyAt: Int?) {
    val busy = busyAt != null
    val n = busyAt ?: f?.upto ?: return
    val grow = remember { Animatable(if (busy) 0f else 1f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, tween(420, easing = Curves.decel)) }
    var at by remember { mutableStateOf(Offset.Zero) }
    val held = FoldCard.open && FoldCard.conv == convId
    val text = if (busy) "正在整理前面的 $n 条" else "前面 $n 条整理成了 ${f?.segs?.size ?: 0} 段"
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).graphicsLayer { scaleX = grow.value; transformOrigin = TransformOrigin(1f, .5f) }.background(veil(.1f)))
        Spacer(Modifier.width(10.dp))
        Pressable(
            { if (!busy && f != null) FoldCard.show(convId, at) },
            Modifier.padding(vertical = 6.dp).onGloballyPositioned { at = it.boundsInRoot().center }
                .graphicsLayer { alpha = grow.value; translationY = (1f - grow.value) * 4.dp.toPx() },
            label = "前情",
        ) { pr ->
            val s by animateFloatAsState(if (pr) .96f else 1f, if (pr) tween(70) else tween(180, easing = Curves.standard), label = "foldPillPress")
            val ca by animateFloatAsState(if (busy) 0f else .7f, tween(240, easing = Curves.standard), label = "foldChevron")
            Row(
                Modifier.graphicsLayer { scaleX = s; scaleY = s }.background(veil(if (pr || held) .12f else .06f), RoundedCornerShape(14.dp))
                    .heightIn(min = 32.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BreathDot(busy)
                Spacer(Modifier.width(7.dp))
                SwapText(text, if (pr || held) Page.ink else Page.muted, 12.5.sp)
                Spacer(Modifier.width(5.dp))
                SvgIcon(Trail.chevron, Page.muted, 11.dp, modifier = Modifier.graphicsLayer { alpha = ca })
            }
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).graphicsLayer { scaleX = grow.value; transformOrigin = TransformOrigin(0f, .5f) }.background(veil(.1f)))
    }
}

/** 几块拼成一组：头尾外角 16、里角 6。 */
private fun joined(i: Int, n: Int) = when {
    n <= 1 -> RoundedCornerShape(16.dp)
    i == 0 -> RoundedCornerShape(16.dp, 16.dp, 6.dp, 6.dp)
    i == n - 1 -> RoundedCornerShape(6.dp, 6.dp, 16.dp, 16.dp)
    else -> RoundedCornerShape(6.dp)
}

@Composable
fun FoldCardLayer() {
    val scope = rememberCoroutineScope()
    CenterCardLayer(FoldCard, "fold", { 0 }, onBack = { false }, onClosed = { FoldCard.conv = "" }) { _, rise ->
        val store = Chats.current?.history
        val id = FoldCard.conv
        val f = store?.get(id)?.fold
        val busy = id in Fold.busy
        val leaving = remember { mutableStateListOf<String>() }
        var undo by remember { mutableStateOf<Pair<String, Int>?>(null) }
        var lastUndo by remember { mutableStateOf("") }
        var restored by remember { mutableStateOf("") }
        var openSeg by remember { mutableIntStateOf(-1) }
        LaunchedEffect(undo) { if (undo != null) { delay(4000); undo = null } }
        // 重新整理：按钮自己变成「整理中…」，好了写一下「好了」再变回来
        var was by remember { mutableStateOf(busy) }
        var done by remember { mutableStateOf(false) }
        LaunchedEffect(busy) { if (was && !busy) { done = true; delay(1200); done = false }; was = busy }
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(horizontal = 6.dp)) {
                Column(Modifier.padding(end = 40.dp, bottom = 12.dp).mineRise(0, rise)) {
                    Txt("前情", Page.ink, 17.sp, family = Serif)
                    if (f != null) {
                        Spacer(Modifier.height(2.dp))
                        Txt("前面 ${f.upto} 条 · ${f.segs.size} 段", Page.muted, 11.5.sp, maxLines = 1)
                    }
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (f == null) {
                        Txt("这段聊天还没有前情。", Page.muted, 14.sp, Modifier.mineRise(1, rise), lineHeight = 23.sp)
                        return@Column
                    }
                    if (f.rules.isNotEmpty()) Column(Modifier.mineRise(1, rise), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Txt("要求", Page.muted, 11.5.sp, Modifier.padding(start = 8.dp, bottom = 3.dp), spacing = 1.4.sp)
                        f.rules.forEachIndexed { i, r ->
                            key(r) {
                                val vis = remember { MutableTransitionState(r != restored) }
                                vis.targetState = r !in leaving
                                AnimatedVisibility(
                                    visibleState = vis,
                                    enter = expandVertically(tween(320, easing = Curves.decel)) + fadeIn(tween(320, easing = Curves.decel)),
                                    exit = shrinkVertically(tween(280, easing = Curves.accel)) + fadeOut(tween(200, easing = Curves.accel)),
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().background(veil(.06f), joined(i, f.rules.size)).heightIn(min = 46.dp).padding(start = 12.dp, end = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Txt(r, Page.ink, 14.sp, Modifier.weight(1f).padding(vertical = 10.dp), lineHeight = 21.sp)
                                        Pressable({
                                            if (r in leaving) return@Pressable
                                            leaving += r; undo = r to i; lastUndo = r; restored = ""
                                            scope.launch { delay(300); store?.let { Fold.dropRule(it, id, r) }; leaving -= r }
                                        }, label = "删掉这条") { pr ->
                                            val k by animateFloatAsState(if (pr) .88f else 1f, if (pr) tween(70) else tween(180, easing = Curves.standard), label = "ruleMinus")
                                            Box(
                                                Modifier.size(42.dp).graphicsLayer { scaleX = k; scaleY = k }
                                                    .background(if (pr) Skin.danger.copy(alpha = .14f) else veil(0f), RoundedCornerShape(12.dp)),
                                                contentAlignment = Alignment.Center,
                                            ) { SvgIcon(minus, if (pr) Skin.danger else Page.muted, 16.dp, stroke = 1.7f) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Column(Modifier.mineRise(2, rise), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        f.segs.forEachIndexed { i, s ->
                            val open = openSeg == i
                            Column(Modifier.fillMaxWidth().background(veil(.05f), joined(i, f.segs.size))) {
                                Pressable({ openSeg = if (open) -1 else i }, Modifier.fillMaxWidth(), label = s.title) { pr ->
                                    val rot by animateFloatAsState(if (open) 90f else 0f, tween(240, easing = Curves.standard), label = "segChevron")
                                    Row(
                                        Modifier.fillMaxWidth().background(veil(if (pr) .06f else 0f), joined(i, f.segs.size)).heightIn(min = 48.dp).padding(horizontal = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Txt(s.title, Page.ink, 14.sp, Modifier.weight(1f), maxLines = 1)
                                        Txt("第 ${s.from}–${s.to} 条", Page.muted, 11.5.sp, family = FontFamily.Monospace, maxLines = 1)
                                        Spacer(Modifier.width(8.dp))
                                        SvgIcon(Trail.chevron, Page.muted, 12.dp, modifier = Modifier.graphicsLayer { rotationZ = rot })
                                    }
                                }
                                AnimatedVisibility(
                                    open,
                                    enter = expandVertically(tween(320, easing = Curves.decel)) + fadeIn(tween(260, 60, Curves.decel)),
                                    exit = shrinkVertically(tween(220, easing = Curves.accel)) + fadeOut(tween(160, easing = Curves.accel)),
                                ) {
                                    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        s.body.lines().filter { it.isNotBlank() }.forEach { line ->
                                            Txt(line.trim(), if (line.trim().startsWith("还没办完")) Skin.accent else Page.ink.copy(alpha = .82f), 13.5.sp, lineHeight = 23.sp, family = Serif)
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        PressSurface(
                                            { FoldUi.jump = s.from..s.to; FoldCard.open = false },
                                            Modifier.height(36.dp), shape = RoundedCornerShape(12.dp), fill = veil(.08f), pressedFill = veil(.14f), pressScale = .96f, label = "看原话",
                                        ) { Box(Modifier.fillMaxHeight().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) { Txt("看原话", Page.ink, 12.5.sp) } }
                                    }
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp).mineRise(3, rise), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PressSurface(
                        { if (!busy && store != null) Fold.redo(store, id) }, Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(14.dp),
                        fill = veil(.07f), pressedFill = veil(.13f), pressScale = .96f, enabled = f != null && !busy, label = "重新整理",
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            SwapText(if (busy) "整理中…" else if (done) "好了" else "重新整理", Page.ink.copy(alpha = .85f), 14.sp)
                        }
                    }
                    PressSurface(
                        { FoldCard.open = false }, Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(14.dp),
                        fill = Skin.accent.copy(alpha = .92f), pressedFill = Skin.accent.copy(alpha = .75f), pressScale = .96f, label = "好",
                    ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Txt("好", Skin.onAccent, 14.sp) } }
                }
            }
            // 删掉一条要求后的撤销条：4 秒（写全名：这里是 Box，不要被外面的 ColumnScope 版本拦走）
            androidx.compose.animation.AnimatedVisibility(
                undo != null,
                Modifier.align(Alignment.BottomCenter).padding(start = 18.dp, end = 18.dp, bottom = 62.dp),
                enter = slideInVertically(tween(300, easing = Curves.decel)) { it / 2 } + fadeIn(tween(300, easing = Curves.decel)),
                exit = slideOutVertically(tween(200, easing = Curves.accel)) { it / 2 } + fadeOut(tween(200, easing = Curves.accel)),
            ) {
                Row(
                    Modifier.fillMaxWidth().shadow(12.dp, RoundedCornerShape(14.dp)).background(Skin.pop, RoundedCornerShape(14.dp)).padding(start = 14.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Txt("删掉了「" + (if (lastUndo.length > 9) lastUndo.take(9) + "…" else lastUndo) + "」", Page.ink, 13.sp, Modifier.weight(1f), maxLines = 1)
                    Pressable({
                        val u = undo ?: return@Pressable
                        undo = null
                        restored = u.first
                        store?.let { Fold.putRule(it, id, u.first, u.second) }
                    }, label = "撤销") { pr ->
                        Txt("撤销", Skin.accent, 13.sp, Modifier.background(veil(if (pr) .1f else 0f), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 12.dp))
                    }
                }
            }
        }
    }
}

/* ---------- 记忆页「前情」卡点进去：提示词 + 右上角模型胶囊 ---------- */

@Composable
internal fun ColumnScope.FoldScreen(store: AssistantStore, compact: Boolean, blur: Dp, nav: MineNav) {
    var draft by remember { mutableStateOf(FoldModel.text) }
    var pick by remember { mutableStateOf(false) }
    // 停手 0.6 秒自动存；离开时也存
    fun commit() {
        val t = draft.trim()
        val v = if (t.isEmpty() || t == FoldModel.DEFAULT) "" else t
        if (v != FoldModel.prompt) { FoldModel.prompt = v; FoldModel.save() }
    }
    LaunchedEffect(draft) { delay(600); commit() }
    DisposableEffect(Unit) { onDispose { commit() } }
    Box(Modifier.weight(1f).fillMaxWidth()) {
        Column(Modifier.fillMaxSize()) {
            val own = FoldModel.own(store)
            Title("前情", compact, onBack = { nav.back() }) {
                ModelChip(if (own) FoldModel.pid else MemModel.pick(store).first, if (own) FoldModel.model else "跟着记忆模型", pick) { pick = true }
            }
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                textStyle = TextStyle(fontFamily = Sans, color = Page.ink, fontSize = 14.5.sp, lineHeight = 25.sp),
                cursorBrush = SolidColor(Page.accent),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxSize().glass(RoundedCornerShape(20.dp), blur, fill = Skin.tint(.42f)).padding(18.dp)) { inner() }
                },
            )
            val changed = draft.trim() != FoldModel.DEFAULT
            val ra by animateFloatAsState(if (changed) 1f else 0f, tween(if (changed) 220 else 160, easing = if (changed) Curves.decel else Curves.accel), label = "foldReset")
            Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.CenterEnd) {
                if (ra > 0f) Pressable({ if (changed) draft = FoldModel.DEFAULT }, Modifier.graphicsLayer { alpha = ra }, label = "恢复默认") { pr ->
                    Txt("恢复默认", Skin.danger, 12.5.sp, Modifier.background(veil(if (pr) .1f else .05f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
        }
        val (mid, mm) = MemModel.pick(store)
        ModelSheet(pick, store, "跟着记忆模型", mid, store.config(mid).name + " / " + mm, FoldModel.own(store), FoldModel.pid, FoldModel.model, { id, m -> FoldModel.use(id, m) }) { pick = false }
    }
}

/** 标题右边：logo + 用哪个模型 + ⌄（打开列表时转过来）。 */
@Composable
internal fun ModelChip(logo: String, label: String, held: Boolean, onClick: () -> Unit) {
    Pressable(onClick, Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp), label = "选模型") { pr ->
        val s by animateFloatAsState(if (pr) .95f else 1f, if (pr) tween(70) else tween(180, easing = Curves.standard), label = "modelChipPress")
        val rot by animateFloatAsState(if (held) 180f else 0f, tween(240, easing = Curves.standard), label = "modelChipCaret")
        Row(
            Modifier.graphicsLayer { scaleX = s; scaleY = s }.background(veil(if (pr || held) .13f else .07f), RoundedCornerShape(12.dp))
                .height(36.dp).padding(start = 7.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProviderLogo(logo, provider(logo).letter, 22.dp)
            Spacer(Modifier.width(7.dp))
            SwapText(label, Page.ink, 13.sp, Modifier.widthIn(max = 132.dp))
            Spacer(Modifier.width(6.dp))
            SvgIcon(caret, Page.muted, 12.dp, stroke = 1.7f, modifier = Modifier.graphicsLayer { rotationZ = rot })
        }
    }
}

/**
 * 选模型：从底下升起（380ms 减速），收回 260ms 加速；选好停一拍自己收回。
 * 第一行是「跟着…」（own = false 时打勾），下面每家配好的型号。
 */
@Composable
internal fun ModelSheet(
    open: Boolean, store: AssistantStore, followLabel: String, followPid: String, followSub: String,
    own: Boolean, pid: String, model: String, onPick: (String, String) -> Unit, onDismiss: () -> Unit,
) {
    val t = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(open) { t.animateTo(if (open) 1f else 0f, if (open) tween(380, easing = Curves.decel) else tween(260, easing = Curves.accel)) }
    BackHandler(open) { onDismiss() }
    if (!open && t.value <= 0f) return
    fun choose(id: String, m: String) { onPick(id, m); scope.launch { delay(260); onDismiss() } }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = t.value }.background(Skin.scrim(.4f)).pointerInput(open) { if (open) detectTapGestures { onDismiss() } })
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 460.dp)
                .graphicsLayer { translationY = (1f - t.value) * size.height * 1.04f }
                .shadow(18.dp, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(Skin.pop, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 14.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp).background(veil(.18f), RoundedCornerShape(2.dp)))
            Spacer(Modifier.height(10.dp))
            Txt("用哪个模型", Page.ink, 15.sp, Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ModelRow(followPid, followLabel, followSub, !own) { choose("", "") }
                store.order.filter { store.config(it).configured }.forEach { id ->
                    val cfg = store.config(id)
                    cfg.models.ifEmpty { ProviderConfig.DEFAULT_MODELS[id].orEmpty() }.forEach { m ->
                        ModelRow(id, cfg.name, m, own && pid == id && model == m) { choose(id, m) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelRow(id: String, name: String, sub: String, on: Boolean, onClick: () -> Unit) {
    val k by animateFloatAsState(if (on) 1f else 0f, tween(220, easing = Curves.standard), label = "foldModelCheck")
    Pressable(onClick, Modifier.fillMaxWidth(), label = "$name $sub") { pr ->
        Row(
            Modifier.fillMaxWidth().heightIn(min = 54.dp).background(veil(if (pr) .08f else 0f), RoundedCornerShape(14.dp)).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProviderLogo(id, provider(id).letter, 28.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Txt(name, Page.ink, 14.5.sp, maxLines = 1)
                if (sub.isNotEmpty()) Txt(sub, Page.muted, 12.sp, maxLines = 1, family = FontFamily.Monospace)
            }
            CheckDot(k, size = 22.dp)
        }
    }
}
