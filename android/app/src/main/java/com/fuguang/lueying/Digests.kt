package com.fuguang.lueying

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/*
 * 聊天摘要（第 363 版，原型 https://claude.ai/artifact/9wcvNyRWC8mbSBUexNC7FF 第 2 版）：
 * 助理页「记忆」标签里「记忆」下面一张入口卡；点进去按天分组的网格（宽屏四列 / 中三列 / 手机两列，照记忆本），
 * 每格是这段聊天的标题 + 聊了哪几个主题（小结里「【…】」那几行）；点进去是沉浸式的详细摘要（照记忆详情）。
 * 没写好的格子写明状态：正在写（呼吸）/ 还没写 · 现在写 / 没写出来：原因 · 重试；顶上「补写没写的 N 段」。
 */

private val back = Ic("M14.5 6l-6 6 6 6")
private val menuIc = Ic("M4 7h16M4 12h16M4 17h16")

/** 一段小结里的主题行：「【换季买外套（2026年10月28日）】」→「换季买外套」。没有主题行的，取第一句。 */
internal fun gistTopics(g: String): List<String> {
    val ts = g.lines().map { it.trim() }.filter { it.startsWith("【") && it.endsWith("】") }
        .map { it.removePrefix("【").removeSuffix("】").replace(Regex("[（(][^（()）]*[)）]$"), "").trim() }.filter { it.isNotEmpty() }
    return ts.ifEmpty { listOfNotNull(g.lines().firstOrNull { it.isNotBlank() }?.let { firstSentence(it.trim(), 24) }) }
}

private fun dayLabel(t: Long): String {
    val now = Calendar.getInstance()
    val c = Calendar.getInstance().apply { timeInMillis = t }
    val same = c.get(Calendar.YEAR) == now.get(Calendar.YEAR)
    val dy = now.get(Calendar.DAY_OF_YEAR) - c.get(Calendar.DAY_OF_YEAR)
    return when {
        same && dy == 0 -> "今天"
        same && dy == 1 -> "昨天"
        same -> SimpleDateFormat("M月d日", Locale.CHINA).format(Date(t))
        else -> SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(Date(t))
    }
}

@Composable
internal fun ColumnScope.DigestsScreen(p: Persona, compact: Boolean, nav: MineNav) {
    val store = Chats.current?.history
    var menu by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    Title("聊天摘要", compact, onBack = { nav.back() }) {
        Pressable({ menu = !menu }, label = "摘要设置") { pr ->
            SvgIcon(menuIc, if (pr) Page.muted else Page.ink, 24.dp, modifier = Modifier.padding(6.dp))
        }
    }
    val convs = store?.of(p.id).orEmpty().filter { c -> c.msgs.any { it.role == "user" } }.sortedByDescending { it.updated }
    val missing = convs.filter { it.gist.isBlank() && it.id !in Reflect.writing }
    Box(Modifier.weight(1f).fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val cols = when { maxWidth >= 640.dp -> 4; maxWidth >= 460.dp -> 3; else -> 2 }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
                Txt("每段聊天聊了什么。她翻以前的聊天时先看这些。", Page.muted, 13.sp, Modifier.padding(start = 2.dp, bottom = 14.dp), lineHeight = 20.sp)
                if (convs.isEmpty()) {
                    Txt("你们还没有聊过天。聊过以后，每段聊了什么都会写在这儿。", Page.ink.copy(alpha = .7f), 14.sp,
                        Modifier.fillMaxWidth().padding(top = 40.dp, start = 12.dp, end = 12.dp), lineHeight = 24.sp, align = androidx.compose.ui.text.style.TextAlign.Center)
                    return@Column
                }
                Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt("${convs.size} 段聊天 · ${convs.count { it.gist.isNotBlank() }} 段有摘要", Page.muted, 13.sp, Modifier.weight(1f), maxLines = 1)
                    if (missing.isNotEmpty() || Reflect.filling) {
                        Pressable({ if (!Reflect.filling) Reflect.digestAll(missing.map { it.id }) }, label = "补写没写的摘要") { pr ->
                            Txt(
                                if (Reflect.filling) "正在补写…" else "补写没写的 ${missing.size} 段", Skin.onAccent, 13.5.sp,
                                Modifier.background(Skin.accent.copy(alpha = if (pr || Reflect.filling) .75f else 1f), RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
                                maxLines = 1,
                            )
                        }
                    }
                }
                var idx = 0
                convs.groupBy { dayLabel(it.updated) }.forEach { (day, list) ->
                    Txt(day, Page.muted, 13.sp, Modifier.padding(start = 4.dp, top = 4.dp, bottom = 10.dp), maxLines = 1)
                    list.chunked(cols).forEach { row ->
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { c -> DigestCell(c, idx++, Modifier.weight(1f).fillMaxHeight()) { nav.screen = "digest/${c.id}" } }
                            repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
        DigestMenu(menu, { menu = false }, nav)
    }
}

/** 一格：标题、主题（小圆点）、底下时间和句数；没写好的写状态。进场一格格浮上来（间隔 40ms，420ms 减速），按下缩一点。 */
@Composable
private fun DigestCell(c: Conv, index: Int, modifier: Modifier, onOpen: () -> Unit) {
    val enter = remember(c.id) { Animatable(0f) }
    LaunchedEffect(c.id) { delay(40L * index.coerceAtMost(12)); enter.animateTo(1f, tween(420, easing = Curves.decel)) }
    val done = c.gist.isNotBlank()
    val busy = c.id in Reflect.writing
    val fail = Reflect.gistFail[c.id]
    val foot = ConvStore.clock(c.updated) + " · ${c.msgs.count { it.text.isNotBlank() }} 句"
    Pressable({ if (done) onOpen() }, modifier.graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * 12.dp.toPx() }, label = c.title) { pr ->
        val k by animateFloatAsState(if (pr && done) 1f else 0f, tween(if (pr) 70 else 200), label = "cell")
        Column(
            Modifier.fillMaxSize().heightIn(min = 140.dp).graphicsLayer { val s = 1f - .02f * k; scaleX = s; scaleY = s }
                .background(Skin.cardFill(.5f + .2f * k), RoundedCornerShape(18.dp))
                .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 12.dp),
        ) {
            Txt(c.title.ifBlank { "新对话" }, Page.ink, 14.5.sp, Modifier.fillMaxWidth(), weight = FontWeight.Medium, lineHeight = 21.sp, maxLines = 2)
            Spacer(Modifier.height(8.dp))
            Column(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    done -> gistTopics(c.gist).take(4).forEach { t ->
                        Row(Modifier.padding(bottom = 4.dp)) {
                            Box(Modifier.padding(top = 7.dp, end = 7.dp).size(4.dp).background(Skin.accent, CircleShape))
                            Txt(t, Page.ink.copy(alpha = .78f), 12.5.sp, lineHeight = 18.sp, maxLines = 2)
                        }
                    }
                    busy -> {
                        val a by rememberInfiniteTransition(label = "gist").animateFloat(.45f, 1f, infiniteRepeatable(tween(900, easing = Curves.standard), RepeatMode.Reverse), label = "b")
                        Txt("正在写摘要…", Page.muted, 12.5.sp, Modifier.graphicsLayer { alpha = a }, lineHeight = 19.sp)
                    }
                    else -> {
                        Txt(if (fail != null) "没写出来：$fail" else "还没写", if (fail != null) Skin.danger else Page.muted, 12.5.sp, lineHeight = 19.sp, maxLines = 3)
                        Spacer(Modifier.height(8.dp))
                        Pressable({ Reflect.digestNow(c.id) }, label = if (fail != null) "重试" else "现在写") { bp ->
                            Txt(if (fail != null) "重试" else "现在写", Page.ink, 12.5.sp,
                                Modifier.background(veil(if (bp) .14f else .07f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 5.dp), maxLines = 1)
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Txt(foot, Page.muted, 11.5.sp, maxLines = 1)
        }
    }
}

/** 点开一格：沉浸式，直接铺在主题背景上（照记忆详情）。小字「日期 时间 · 句数 · 几点更新」、标题，主题一节一节，没办完的灰字。 */
@Composable
internal fun ColumnScope.DigestScreen(c: Conv, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp, end = LocalTitleEnd.current), verticalAlignment = Alignment.CenterVertically) {
        Pressable(onBack, label = "返回") { pr ->
            SvgIcon(back, if (pr) Page.muted else Page.ink, 24.dp, modifier = Modifier.padding(end = 10.dp, top = 4.dp, bottom = 4.dp))
        }
    }
    val rise = remember(c.id) { Animatable(0f) }
    LaunchedEffect(c.id) { delay(80); rise.animateTo(1f, tween(420, easing = Curves.decel)) }
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
            .graphicsLayer { alpha = rise.value; translationY = (1f - rise.value) * 10.dp.toPx() }
            .padding(start = 4.dp, end = 4.dp, bottom = 32.dp),
    ) {
        val meta = listOfNotNull(
            dayLabel(c.updated) + " " + ConvStore.clock(c.updated),
            "${c.msgs.count { it.text.isNotBlank() }} 句",
            if (c.gistAt > 0) SimpleDateFormat("M月d日 H:mm", Locale.CHINA).format(Date(c.gistAt)) + " 更新" else null,
        ).joinToString(" · ")
        Txt(meta, Page.muted, 13.sp, Modifier.padding(top = 8.dp, bottom = 8.dp), maxLines = 1)
        Txt(c.title.ifBlank { "新对话" }, Page.ink, 24.sp, Modifier.fillMaxWidth().padding(bottom = 22.dp), weight = FontWeight.SemiBold, lineHeight = 34.sp)
        var first = true
        c.gist.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            when {
                line.startsWith("【") && line.endsWith("】") ->
                    Txt(line.removePrefix("【").removeSuffix("】"), Page.ink, 16.sp, Modifier.fillMaxWidth().padding(top = if (first) 0.dp else 14.dp, bottom = 8.dp), weight = FontWeight.Medium, lineHeight = 25.sp)
                line.startsWith("还没办完") || line.startsWith("还没办的") ->
                    Txt(line, Page.muted, 15.sp, Modifier.fillMaxWidth().padding(bottom = 14.dp), lineHeight = 28.sp)
                else -> Txt(line, Page.ink, 16.sp, Modifier.fillMaxWidth().padding(bottom = 14.dp), lineHeight = 31.sp)
            }
            first = false
        }
    }
}

/** 入口卡上的图标：和「记忆」那张同一个底（点缀色浅底 52dp），里面两只叠着的对话气泡，前面那只第一行用点缀色；按下前面那只翘一下。 */
@Composable
internal fun DigestIcon(pressed: Boolean) {
    val light = Skin.light
    val ac = Skin.accent
    val ink = Page.ink
    val muted = Page.muted
    val lift by animateFloatAsState(if (pressed) 1f else 0f, tween(260, easing = Curves.decel), label = "digestLift")
    val backB = remember { PathParser().parsePathString("M12 9.5h12.5a2.5 2.5 0 0 1 2.5 2.5v7a2.5 2.5 0 0 1-2.5 2.5H23v3l-3.5-3H12a2.5 2.5 0 0 1-2.5-2.5v-7A2.5 2.5 0 0 1 12 9.5Z").toPath() }
    val front = remember { PathParser().parsePathString("M7.5 6h12.5A2.5 2.5 0 0 1 22.5 8.5v7A2.5 2.5 0 0 1 20 18h-7.5L9 21v-3H7.5A2.5 2.5 0 0 1 5 15.5v-7A2.5 2.5 0 0 1 7.5 6Z").toPath() }
    Box(
        Modifier.size(52.dp).clip(RoundedCornerShape(15.dp))
            .background(Brush.verticalGradient(listOf(ac.copy(alpha = if (light) .22f else .2f), ac.copy(alpha = if (light) .34f else .1f)))),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawLine(Color.White.copy(alpha = if (light) .7f else .08f), Offset(15.dp.toPx(), .5.dp.toPx()), Offset(size.width - 15.dp.toPx(), .5.dp.toPx()), 1.dp.toPx())
            val u = 40.dp.toPx() / 32f
            val ox = (size.width - 40.dp.toPx()) / 2f; val oy = (size.height - 40.dp.toPx()) / 2f
            val thin = Stroke(1.1.dp.toPx() / u, join = StrokeJoin.Round)
            val edge = ink.copy(alpha = ink.alpha * (if (light) .45f else .4f))
            translate(ox, oy) {
                scale(u, u, Offset.Zero) {
                    drawPath(backB, if (light) Color.White.copy(alpha = .7f) else Color.White.copy(alpha = .07f))
                    drawPath(backB, edge.copy(alpha = edge.alpha * .7f), style = thin)
                }
                translate(-.4f * u * lift, -1.4f * u * lift) {
                    scale(u, u, Offset.Zero) {
                        drawPath(front, if (light) Color.White else Color.White.copy(alpha = .13f))
                        drawPath(front, edge, style = thin)
                        drawLine(ac, Offset(9f, 10.5f), Offset(15f, 10.5f), 1.6f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                        drawLine(muted, Offset(9f, 13.8f), Offset(18f, 13.8f), 1.4f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    }
                }
            }
        }
    }
}
