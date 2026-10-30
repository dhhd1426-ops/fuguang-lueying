package com.fuguang.lueying

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 记忆页（第 342 版，原型 UutPJs8S1aAkaypJsCXygR 第 3 版）：
 * - 四本合成一个框，按时间排；每条底下小字开头写着是哪一类（[kindZh]）。
 * - 没有 ✕：长按一条，那一行变灰，下面贴一张小菜单，顶上一行灰字是这条，下面只有红色「删除」；点了在视觉中心弹「忘掉这条？」确认（ConvMenu 的 [Sheet]）。删了 4 秒内能撤销。
 * - 轻点一条：整页推进来一张信纸（[LetterScreen]），四边玫瑰藤蔓，正文是她写的那段话，一段一段排。
 */
object MemMenu {
    var mem by mutableStateOf<Mem?>(null)
    var anchor by mutableStateOf(Rect.Zero)
    /** 按住的那一行（菜单、确认开着时一直灰着）。 */
    var held by mutableStateOf<String?>(null)
    var deleting by mutableStateOf<Mem?>(null)
    fun open(m: Mem, at: Rect) { anchor = at; held = m.id; mem = m }
    fun close() { mem = null; if (deleting == null) held = null }
}

private val trash = Ic("M4.5 7h15M9.5 7V5h5v2M6.5 7l1 12.5h9l1-12.5M10 11v5M14 11v5")
private val back = Ic("M14.5 6l-6 6 6 6")

/** 一条记忆：按下当帧淡一层；长按变灰色块，出菜单。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MemLine(m: Mem, onOpen: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val held = MemMenu.held == m.id
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val haptic = LocalHapticFeedback.current
    val k by animateFloatAsState(if (held) 1f else if (pressed) .35f else 0f, tween(if (pressed || held) 60 else 220), label = "memHeld")
    val dim = m.kind == Lessons.KIND && m.failed >= 2
    Column(
        Modifier.fillMaxWidth()
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .background(veil(.14f * k), RoundedCornerShape(12.dp))
            .combinedClickable(src, null, onClickLabel = "打开", onLongClickLabel = "删除", onClick = onOpen, onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                MemMenu.open(m, bounds)
            })
            .padding(horizontal = 8.dp, vertical = 12.dp),
    ) {
        MemText(m, dim, false)
        Spacer(Modifier.height(3.dp))
        Txt(
            kindZh(m.kind) + " · " + (if (m.by == "you") "你让我记的" else if (m.kind == Lessons.KIND) Lessons.state(m) else "我自己记下的") + " · " + ago(m.at) + (if (m.moment) " · ✦ 想留着的" else ""),
            if ((m.by == "you" || m.moment || (m.kind == Lessons.KIND && (m.habit || m.used > 0))) && !dim) Skin.accent.copy(alpha = .9f) else Page.muted, 12.sp, maxLines = 1,
        )
    }
}

/** 记忆页最上层：长按菜单 + 删除确认。放在助理页标签下面那块里。 */
@Composable
internal fun MemMenuLayer(p: Persona, onDeleted: (Mem, Int) -> Unit) {
    val scope = rememberCoroutineScope()
    DisposableEffect(p.id) { onDispose { MemMenu.mem = null; MemMenu.deleting = null; MemMenu.held = null } }
    var origin by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { origin = it.boundsInRoot().topLeft }) {
        val density = LocalDensity.current
        val fullH = with(density) { maxHeight.toPx() }
        val fullW = with(density) { maxWidth.toPx() }
        val t = remember { Animatable(0f) }
        var shown by remember { mutableStateOf<Mem?>(null) }
        var at by remember { mutableStateOf(Rect.Zero) }
        LaunchedEffect(MemMenu.mem) {
            val m = MemMenu.mem
            if (m != null) { shown = m; at = MemMenu.anchor.translate(-origin.x, -origin.y); t.animateTo(1f, tween(180, easing = Curves.decel)) }
            else { t.animateTo(0f, tween(140, easing = Curves.accel)); shown = null }
        }
        BackHandler(MemMenu.mem != null) { MemMenu.close() }
        val m = shown
        if (m != null && t.value > 0f) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { MemMenu.close() } })
            val menuH = with(density) { (44 + 12 + 29).dp.toPx() }
            val gap = with(density) { 6.dp.toPx() }
            var top = at.bottom + gap
            var below = true
            if (top + menuH > fullH - with(density) { 24.dp.toPx() }) { top = (at.top - menuH - gap).coerceAtLeast(0f); below = false }
            val right = at.right - with(density) { 4.dp.toPx() }
            Column(
                Modifier
                    .offset { IntOffset(0, top.roundToInt()) }
                    .padding(end = with(density) { (fullW - right).coerceAtLeast(0f).toDp() })
                    .align(Alignment.TopEnd)
                    .graphicsLayer {
                        alpha = t.value
                        val s = .94f + .06f * t.value; scaleX = s; scaleY = s
                        translationY = (1f - t.value) * (if (below) -6.dp.toPx() else 6.dp.toPx())
                        transformOrigin = TransformOrigin(.85f, if (below) 0f else 1f)
                    }
                    .widthIn(min = 150.dp, max = 220.dp)
                    .shadow(18.dp, RoundedCornerShape(16.dp), ambientColor = Color.Black.copy(alpha = .4f), spotColor = Color.Black.copy(alpha = .4f))
                    .background(Skin.pop, RoundedCornerShape(16.dp))
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(vertical = 6.dp),
            ) {
                Txt(m.text, Page.muted, 12.sp, Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 5.dp).widthIn(max = 190.dp), maxLines = 1)
                MenuItem(trash, "删除", red = true, filled = false) {
                    scope.launch { delay(90); MemMenu.deleting = m; MemMenu.close() }
                }
            }
        }
        Sheet(MemMenu.deleting != null, onDismiss = { MemMenu.deleting = null; MemMenu.held = null }) {
            val d = MemMenu.deleting
            Txt("忘掉这条？", Page.ink, 16.sp)
            Spacer(Modifier.height(8.dp))
            Txt("「${d?.text.orEmpty()}」删掉以后，她就不会再想起这件事了。", Page.muted, 13.5.sp, lineHeight = 22.sp, align = TextAlign.Center)
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SheetButton("取消", false, Modifier.weight(1f)) { MemMenu.deleting = null; MemMenu.held = null }
                SheetButton("删除", true, Modifier.weight(1f), red = true) {
                    val x = d ?: return@SheetButton
                    val i = p.items.indexOf(x)
                    Memory.remove(p, x); onDeleted(x, i)
                    MemMenu.deleting = null; MemMenu.held = null
                }
            }
        }
    }
}

/* ---------- 记忆本的折角卡片（第 345 版，原型 EzmAr4Lo7ucsmJG6FEV4as 第 9 版） ---------- */

/** 右上角折掉一角的纸：其余三角是圆角。 */
internal class NoteShape(private val cut: Dp, private val r: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val c = with(density) { cut.toPx() }; val rr = with(density) { r.toPx() }
        val w = size.width; val h = size.height
        return Outline.Generic(Path().apply {
            moveTo(rr, 0f); lineTo(w - c, 0f); lineTo(w, c); lineTo(w, h - rr)
            quadraticTo(w, h, w - rr, h); lineTo(rr, h)
            quadraticTo(0f, h, 0f, h - rr); lineTo(0f, rr)
            quadraticTo(0f, 0f, rr, 0f); close()
        })
    }
}

/** 五角星，中心 (cx, cy)，外半径 r。 */
private fun star(cx: Float, cy: Float, r: Float) = Path().apply {
    for (i in 0 until 10) {
        val a = -PI / 2 + i * PI / 5
        val rad = if (i % 2 == 0) r else r * .45f
        val x = cx + (cos(a) * rad).toFloat(); val y = cy + (sin(a) * rad).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/**
 * 一张记忆卡：卡里是正文（第一段亮一些），卡下是标题和「类别 · 日期」。人和规矩右上角一颗星。
 * 进场一张张浮上来（间隔 40ms，420ms 减速）；按下缩一点；长按变暗、出删除菜单（[MemMenu]）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NoteCard(m: Mem, kids: Int, index: Int, modifier: Modifier, onOpen: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val held = MemMenu.held == m.id
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val haptic = LocalHapticFeedback.current
    val k by animateFloatAsState(if (held) 1f else if (pressed) .5f else 0f, tween(if (pressed || held) 60 else 220), label = "noteHeld")
    val enter = remember(m.id) { Animatable(0f) }
    LaunchedEffect(m.id) { delay(40L * index.coerceAtMost(12)); enter.animateTo(1f, tween(420, easing = Curves.decel)) }
    val shape = remember { NoteShape(30.dp, 18.dp) }
    val paras = MemTree.body(m).split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    Column(
        modifier
            .graphicsLayer {
                alpha = enter.value
                translationY = (1f - enter.value) * 12.dp.toPx()
                val s = 1f - .03f * k; scaleX = s; scaleY = s
            }
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .combinedClickable(src, null, onClickLabel = "打开", onLongClickLabel = "删除", onClick = onOpen, onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                MemMenu.open(m, bounds)
            }),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            Column(
                Modifier.fillMaxSize().background(Skin.note, shape)
                    .drawBehind {
                        // 折角：右上角那一小块三角，颜色比纸深一层
                        val c = 30.dp.toPx()
                        val fold = Path().apply { moveTo(size.width - c, 0f); lineTo(size.width - c, c - 6.dp.toPx()); quadraticTo(size.width - c, c, size.width - c + 6.dp.toPx(), c); lineTo(size.width, c); close() }
                        drawPath(fold, Skin.noteFold)
                    }
                    .drawWithContent { drawContent(); if (k > 0f) drawRect(Skin.scrim(.28f * k)) }
                    .padding(start = 14.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
            ) {
                val style = TextStyle(fontSize = 13.sp, lineHeight = 21.sp)
                androidx.compose.foundation.text.BasicText(
                    androidx.compose.ui.text.buildAnnotatedString {
                        paras.forEachIndexed { i, t ->
                            if (i == 0) pushStyle(androidx.compose.ui.text.SpanStyle(color = Page.ink, fontWeight = FontWeight.Medium)) else pushStyle(androidx.compose.ui.text.SpanStyle(color = Page.muted))
                            append(t); if (i < paras.lastIndex) append(if (i == 0) "\n" else "")
                            pop()
                        }
                    },
                    Modifier.fillMaxWidth().padding(end = 12.dp),
                    style = style.copy(fontFamily = Sans), maxLines = 7, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            if (m.rule) Canvas(Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-6).dp).size(20.dp)) {
                drawPath(star(size.width / 2, size.height / 2, size.minDimension / 2), Skin.accent)
            }
        }
        Txt(Memory.ownWords(m.text), Page.ink, 14.sp, Modifier.fillMaxWidth().padding(top = 10.dp), weight = FontWeight.SemiBold, align = TextAlign.Center, lineHeight = 20.sp, maxLines = 2)
        Txt(
            listOfNotNull(if (m.pin) "常驻" else null, if (m.span) "$kids 份" else null, MemTree.kindZh(m.kind), MemTree.date(m)).joinToString(" · "),
            if (m.pin) Skin.accent else Page.muted, 11.5.sp, Modifier.fillMaxWidth().padding(top = 3.dp), align = TextAlign.Center, maxLines = 1,
        )
    }
}

/* ---------- 信纸 ---------- */

/**
 * 点开一条记忆（第 346 版，原型 EzmAr4Lo7ucsmJG6FEV4as 第 11 版）：沉浸式，直接铺在软件主题背景上，不要信纸、不要框。
 * 上面一行小字「日期 · 类别」，下面标题，再下面正文一段一段排（不缩进）；周、月、年那份下面列着挂在它底下的，点了往下翻。
 */
@Composable
internal fun ColumnScope.LetterScreen(p: Persona, m: Mem, onKid: (Mem) -> Unit = {}, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp, end = LocalTitleEnd.current), verticalAlignment = Alignment.CenterVertically) {
        Pressable(onBack, label = "返回") { pr ->
            SvgIcon(back, if (pr) Page.muted else Page.ink, 24.dp, modifier = Modifier.padding(end = 10.dp, top = 4.dp, bottom = 4.dp))
        }
    }
    val rise = remember(m.id) { Animatable(0f) }
    LaunchedEffect(m.id) { delay(80); rise.animateTo(1f, tween(420, easing = Curves.decel)) }
    val paras = MemTree.body(m).split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    val kids = MemTree.kids(p, m)
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
            .graphicsLayer { alpha = rise.value; translationY = (1f - rise.value) * 10.dp.toPx() }
            .padding(start = 4.dp, end = 4.dp, bottom = 32.dp),
    ) {
        Txt(
            listOfNotNull(MemTree.date(m), MemTree.kindZh(m.kind), if (m.pin) "常驻" else null).filter { it.isNotEmpty() }.joinToString(" · "),
            Page.muted, 13.sp, Modifier.padding(top = 8.dp, bottom = 8.dp), maxLines = 1,
        )
        Txt(Memory.ownWords(m.text), Page.ink, 24.sp, Modifier.fillMaxWidth().padding(bottom = 22.dp), weight = FontWeight.SemiBold, lineHeight = 34.sp)
        paras.forEach { t ->
            Txt(t, Page.ink, 16.sp, Modifier.fillMaxWidth().padding(bottom = 14.dp), lineHeight = 31.sp)
        }
        if (kids.isNotEmpty()) {
            Txt("这" + (when (m.kind) { "week" -> "一周"; "month" -> "个月"; else -> "一年" }) + "里", Page.muted, 13.sp, Modifier.padding(top = 18.dp, bottom = 6.dp))
            kids.forEach { k ->
                Pressable({ onKid(k) }, Modifier.fillMaxWidth().padding(vertical = 4.dp), label = k.text) { pr ->
                    Row(
                        Modifier.fillMaxWidth().background(Skin.cardFill(if (pr) .7f else .5f), RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Txt(Memory.ownWords(k.text), Page.ink, 14.5.sp, Modifier.weight(1f), maxLines = 1)
                        Txt(MemTree.date(k), Page.muted, 12.5.sp, Modifier.padding(start = 10.dp), maxLines = 1)
                    }
                }
            }
        }
    }
}
