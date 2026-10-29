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

/* ---------- 信纸 ---------- */

// 信纸是一张纸，不跟主题换颜色（像照片看图一直是深色一样）
private val PaperBg = Color(0xFFF4EDDD)
private val PaperVine = Color(0xFFA8946C)
private val PaperInk = Color(0xFF4A3E2C)
private val PaperMuted = Color(0xFF8F7F62)

private val CN = listOf("", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十")
private fun cn(n: Int) = when { n <= 10 -> CN[n]; n < 20 -> "十" + CN[n - 10]; else -> CN[n / 10] + "十" + CN[n % 10] }

/** 落款：三个字的中文名去掉姓（陆嘉静 → 嘉静），别的照原样。 */
private fun sign(name: String): String {
    val n = name.trim()
    return if (n.length == 3 && n.all { it in '一'..'鿿' }) n.substring(1) else n.ifEmpty { "她" }
}

/**
 * 点开一条记忆：整页一张米色信纸（推进来时纸再从下面浮上来 500ms 减速），四边玫瑰藤蔓（[vine]，照原型用代码画，不放图片）。
 * 上面中文日期、这条是哪一类、一道小花饰；正文是她写的那段话（[Mem.diary]，还没写好就用记下的那句和细节），按换行分段、首行缩进两字；右下落款。
 */
@Composable
internal fun ColumnScope.LetterScreen(p: Persona, m: Mem, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp, end = LocalTitleEnd.current), verticalAlignment = Alignment.CenterVertically) {
        Pressable(onBack, label = "返回") { pr ->
            SvgIcon(back, if (pr) Page.muted else Page.ink, 24.dp, modifier = Modifier.padding(end = 10.dp, top = 4.dp, bottom = 4.dp))
        }
    }
    val rise = remember(m.id) { Animatable(0f) }
    LaunchedEffect(m.id) { delay(120); rise.animateTo(1f, tween(500, easing = Curves.decel)) }
    val paras = (m.diary.ifBlank { listOf(m.text, m.detail).filter { it.isNotBlank() }.joinToString("\n") })
        .split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    val date = if (m.at > 0) Calendar.getInstance().apply { timeInMillis = m.at }.let { c -> cn(c.get(Calendar.MONTH) + 1) + "月" + cn(c.get(Calendar.DAY_OF_MONTH)) + "日" } else ""
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val minH = maxHeight - 12.dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
            Column(
                Modifier.fillMaxWidth().heightIn(min = minH)
                    .graphicsLayer { alpha = rise.value; translationY = (1f - rise.value) * 14.dp.toPx() }
                    .shadow(12.dp, RoundedCornerShape(6.dp), ambientColor = PaperInk.copy(alpha = .35f), spotColor = PaperInk.copy(alpha = .35f))
                    .background(PaperBg, RoundedCornerShape(6.dp))
                    .drawBehind { vine(PaperVine) }
                    .padding(start = 46.dp, end = 46.dp, top = 60.dp, bottom = 62.dp),
            ) {
                if (date.isNotEmpty()) Txt(date, PaperMuted, 12.5.sp, Modifier.fillMaxWidth(), spacing = 1.sp, family = FontFamily.Serif, align = TextAlign.Center)
                Txt(kindZh(m.kind), PaperMuted.copy(alpha = .8f), 11.5.sp, Modifier.fillMaxWidth().padding(top = 4.dp), family = FontFamily.Serif, align = TextAlign.Center)
                Canvas(Modifier.padding(top = 14.dp, bottom = 18.dp).size(60.dp, 10.dp).align(Alignment.CenterHorizontally)) {
                    val y = size.height / 2; val w = .8.dp.toPx()
                    drawLine(PaperVine, Offset(2.dp.toPx(), y), Offset(22.dp.toPx(), y), w)
                    drawLine(PaperVine, Offset(38.dp.toPx(), y), Offset(58.dp.toPx(), y), w)
                    drawCircle(PaperVine, 2.4.dp.toPx(), Offset(size.width / 2, y), style = Stroke(w))
                    drawCircle(PaperVine, .9.dp.toPx(), Offset(size.width / 2, y))
                }
                val style = TextStyle(fontFamily = FontFamily.Serif, fontSize = 15.5.sp, lineHeight = 31.sp, color = PaperInk, textIndent = TextIndent(firstLine = 31.sp))
                paras.forEachIndexed { i, t ->
                    if (i > 0) Spacer(Modifier.height(8.dp))
                    BasicText(t, Modifier.fillMaxWidth(), style = style)
                }
                Txt("—— " + sign(p.name), PaperMuted, 14.sp, Modifier.fillMaxWidth().padding(top = 24.dp), family = FontFamily.Serif, align = TextAlign.End)
            }
        }
    }
}

/** 玫瑰藤蔓边框：离纸边 22dp 沿四边一条起伏的藤，隔一段两片叶子、隔一段一朵小玫瑰（几圈错开的弧），四角各一簇。 */
private fun DrawScope.vine(c: Color) {
    val u = 1.dp.toPx()
    val w = size.width; val h = size.height; val m = 22 * u
    var seed = 7
    fun rnd(): Float { seed = (seed * 9301 + 49297) % 233280; return seed / 233280f }
    fun rose(x: Float, y: Float, r: Float) {
        val rot = rnd() * 360f
        for (i in 0 until 5) {
            val rr = r * (1 - i * .18f)
            drawArc(c, rot + i * 74.5f, 252f, false, Offset(x - rr, y - rr), Size(2 * rr, 2 * rr), style = Stroke(.9f * u))
        }
        drawCircle(c, r * .18f, Offset(x, y))
    }
    fun leaf(x: Float, y: Float, a: Float, l: Float) {
        val path = Path().apply { moveTo(0f, 0f); quadraticTo(l * .5f, -l * .42f, l, 0f); quadraticTo(l * .5f, l * .42f, 0f, 0f); close() }
        translate(x, y) {
            rotate((a * 180f / PI).toFloat(), pivot = Offset.Zero) {
                drawPath(path, c.copy(alpha = c.alpha * .55f))
                drawLine(c, Offset.Zero, Offset(l * .85f, 0f), .5f * u)
            }
        }
    }
    fun edge(x0: Float, y0: Float, x1: Float, y1: Float) {
        val len = hypot(x1 - x0, y1 - y0)
        val ux = (x1 - x0) / len; val uy = (y1 - y0) / len
        val nx = -uy; val ny = ux
        val amp = 4.5f * u; val wl = 46f * u
        val path = Path()
        var t = 0f
        while (t <= len) {
            val o = sin(t / wl * 2 * PI).toFloat() * amp
            val px = x0 + ux * t + nx * o; val py = y0 + uy * t + ny * o
            if (t == 0f) path.moveTo(px, py) else path.lineTo(px, py)
            t += 2 * u
        }
        drawPath(path, c, style = Stroke(u))
        val base = atan2(uy, ux)
        t = 34 * u
        while (t < len - 30 * u) {
            val o = sin(t / wl * 2 * PI).toFloat() * amp
            val px = x0 + ux * t + nx * o; val py = y0 + uy * t + ny * o
            val idx = (t / wl).roundToInt()
            val side = if (idx % 2 != 0) 1f else -1f
            leaf(px, py, base + side * 1f, (9 + rnd() * 3) * u)
            leaf(px, py, base + PI.toFloat() - side * .9f, (7 + rnd() * 3) * u)
            if (idx % 2 == 0) rose(px + nx * side * 9 * u, py + ny * side * 9 * u, (5.2f + rnd() * 1.4f) * u)
            t += wl
        }
    }
    edge(m + 16 * u, m, w - m - 16 * u, m)
    edge(w - m, m + 16 * u, w - m, h - m - 16 * u)
    edge(w - m - 16 * u, h - m, m + 16 * u, h - m)
    edge(m, h - m - 16 * u, m, m + 16 * u)
    listOf(Offset(m, m), Offset(w - m, m), Offset(w - m, h - m), Offset(m, h - m)).forEachIndexed { i, p ->
        val a = i * PI.toFloat() / 2
        rose(p.x, p.y, 8 * u)
        leaf(p.x, p.y, a + .3f, 11 * u)
        leaf(p.x, p.y, a + 1.27f, 11 * u)
        val ca = cos(a); val sa = sin(a); val cb = cos(a + PI.toFloat() / 2); val sb = sin(a + PI.toFloat() / 2)
        rose(p.x + ca * 14 * u + cb * 4 * u, p.y + sa * 14 * u + sb * 4 * u, 4.5f * u)
        rose(p.x + cb * 14 * u + ca * 4 * u, p.y + sb * 14 * u + sa * 4 * u, 4.5f * u)
    }
}
