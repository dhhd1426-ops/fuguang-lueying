package com.fuguang.lueying

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 思考过程（照 Claude App 的逻辑）：思考的段落和用过的工具排在同一条时间线上。
 * 聊天里只有一行：进行中是三点动效 + 最新一句，结束换成时钟图标；点开是可拖的抽屉。
 */

/** 用过的一个工具。at：开始用时思考原文写到第几个字，用来和思考段落排先后。 */
data class ToolStep(val at: Int, val name: String, val title: String, val detail: String = "", val done: Boolean = true)

/** 每种工具在时间线上的说法和图标。 */
object Trail {
    fun liveTitle(n: String): String = when (n) {
        Memory.REMEMBER -> "正在记下"; Memory.FORGET -> "正在忘掉"; Recall.TOOL -> "正在翻看以前的聊天"; WebSearch.TOOL -> "正在上网查"
        Skills.OPEN -> "正在翻开本事"; Skills.FIND -> "正在 GitHub 上找本事"; Skills.LEARN -> "等你点头"
        Materials.OPEN -> "正在打开网页"; Materials.LOOK -> "正在看素材"; Gallery.TOOL -> "正在图库里找"
        Works.MAKE -> "正在动手做"; Works.EDIT -> "正在改作品"; Works.READ -> "正在看原稿"; "list_todos" -> "正在查看羽的待办"; "read_note" -> "正在读羽的笔记"
        else -> if (Mcp.owns(n)) Mcp.note(n).trimEnd('…') else "正在查阅羽的笔记"
    }

    fun doneTitle(n: String, a: JSONObject): String = when (n) {
        Memory.REMEMBER -> "记下了一条"; Memory.FORGET -> "忘掉了一些"; Recall.TOOL -> "翻了以前的聊天"; WebSearch.TOOL -> "上网查了「${a.optString("query").trim().take(16)}」"
        Skills.OPEN -> a.optJSONArray("names")?.takeIf { it.length() > 1 }?.let { arr -> "翻开了 ${arr.length()} 样本事，合成一种做法" } ?: "翻开了「${(a.optJSONArray("names")?.optString(0) ?: a.optString("name")).trim().take(12)}」"; Skills.FIND -> "在 GitHub 上找了「${a.optString("query").trim().take(16)}」"
        Skills.LEARN -> if (Skills.has(SkillHub.cand(a.optString("repo"), a.optString("path")))) "学会了「${a.optString("title").trim().take(12)}」" else "没学「${a.optString("title").trim().take(12)}」"
        Works.MAKE -> (if (a.optString("kind") == "board") "搭好了画板" else "做好了作品") + "「${a.optString("title").trim().ifEmpty { "未命名" }.take(20)}」"
        Works.EDIT -> "改好了「${workTitle(a)}」"
        Materials.OPEN -> Materials.doneTitle(a.optString("url"))
        Gallery.TOOL -> Gallery.doneTitle(a.optString("query"))
        Materials.LOOK -> "看了 ${a.optJSONArray("urls")?.length() ?: 0} 件素材"
        Works.READ -> "看了原稿"; "list_todos" -> "看了羽的待办"; "read_note" -> "读了一篇羽的笔记"
        else -> if (Mcp.owns(n)) Mcp.note(n).replace("正在用", "用了").trimEnd('…') else "查了羽的笔记"
    }

    fun failTitle(n: String, a: JSONObject): String =
        if (n == Works.EDIT) "「${workTitle(a)}」没改成" else "作品「${a.optString("title").trim().ifEmpty { "未命名" }.take(20)}」没做成"

    private fun workTitle(a: JSONObject) = a.optString("title").trim().ifEmpty { Works.get(a.optString("id").trim())?.title ?: "作品" }.take(20)

    fun detail(n: String, a: JSONObject): String = when (n) {
        Memory.REMEMBER, Memory.FORGET -> a.optString("text")
        Recall.TOOL, "search_notes", WebSearch.TOOL, Skills.FIND -> a.optString("query")
        Skills.OPEN -> a.optJSONArray("names")?.let { arr -> (0 until arr.length()).joinToString("、") { arr.optString(it) } } ?: a.optString("name")
        Skills.LEARN -> a.optString("repo") + "/" + a.optString("path")
        Materials.OPEN -> a.optString("url")
        Gallery.TOOL -> a.optString("query")
        Works.EDIT -> "改 ${a.optJSONArray("edits")?.length() ?: 0} 处"
        Works.MAKE -> when (a.optString("kind")) { "slides" -> "作品 · 幻灯片"; "doc" -> "作品 · 文档"; "board" -> "画板"; else -> "作品 · 网页" }
        else -> ""
    }.trim().replace('\n', ' ').take(80)

    /** 作品用和作品卡同一个图标（圆 + 方）。 */
    fun icon(n: String): Ic = when (n) {
        Memory.REMEMBER -> bookmark; Memory.FORGET -> eraser; Recall.TOOL -> bubble; WebSearch.TOOL, Materials.OPEN -> globe; Materials.LOOK, Gallery.TOOL -> picture
        Skills.OPEN, Skills.FIND, Skills.LEARN -> skillIcon
        Works.MAKE, Works.EDIT -> shapes; Works.READ -> page; "list_todos" -> todo
        else -> if (Mcp.owns(n)) plug else feather
    }

    val clock = Ic("M3.5 12a8.5 8.5 0 1 0 2.6-6.1M3.5 4.5v3.7h3.7M12 7.5V12l3 2")
    val chevron = Ic("M9.5 6l6 6-6 6")
    private val globe = Ic("M4 12h16M12 4c2.4 2.5 2.4 13.5 0 16M12 4c-2.4 2.5-2.4 13.5 0 16", circles = listOf(floatArrayOf(12f, 12f, 8f)))
    private val picture = Ic("M4 5h16v14H4ZM4 16l4.5-4 3.5 3 3-2.5L20 16", circles = listOf(floatArrayOf(9f, 9.5f, 1.6f)))
    private val bookmark = Ic("M7 4h10a1 1 0 0 1 1 1v15l-6-3.5L6 20V5a1 1 0 0 1 1-1Z")
    private val eraser = Ic("M8.5 19.5h11M4.8 14.7l9.9-9.9a1.5 1.5 0 0 1 2.1 0l2.4 2.4a1.5 1.5 0 0 1 0 2.1L10.3 18.2a1.5 1.5 0 0 1-2.1 0l-3.4-3.4ZM9.6 9.9l4.5 4.5")
    private val bubble = Ic("M5 5.5h14v10H10l-4 3v-3H5ZM9 10.5h6")
    private val shapes = Ic("M12.5 12.5h7.5v7.5h-7.5Z", circles = listOf(floatArrayOf(9f, 9f, 5f)))
    private val page = Ic("M14 3H6v18h12V7ZM14 3v4h4M9 12h6M9 15.5h6")
    private val todo = Ic("M10 7h9M10 12h9M10 17h9M4.5 7l1.2 1.2L7.8 6M4.5 12l1.2 1.2 2.1-2.2M4.5 17l1.2 1.2 2.1-2.2")
    private val feather = Ic("M19 5c-6 0-11 5-11 11v3M19 5c0 7-4 11-11 11M8 19l-3 2")
    private val plug = Ic("M9 3v5M15 3v5M6.5 8h11v3a5.5 5.5 0 0 1-11 0ZM12 16.5V21")
}

/** 时间线上的一个节点。key 让「思考中」长成那段总结时是同一个节点。 */
class TrailItem(val key: String, val tool: ToolStep?, val title: String, val detail: String, val live: Boolean, val placeholder: Boolean = false)

private val sentenceEnd = Regex("[。！？!?\\n]")

/** 把思考原文和工具排成一条时间线。live：这轮还在想（还没开始写回答）。 */
fun trailItems(raw: String, steps: List<ToolStep>, live: Boolean): List<TrailItem> {
    val segs = thinkSegments(raw)
    var from = 0
    val starts = segs.map { s -> val i = raw.indexOf(s, from).let { if (it < 0) from else it }; from = i + s.length; i }
    val running = steps.any { !it.done }
    val lastToolAt = steps.lastOrNull()?.at ?: -1
    val out = mutableListOf<TrailItem>()
    fun seg(k: Int) {
        val s = segs[k]
        val writing = live && !running && k == segs.lastIndex && starts[k] >= lastToolAt
        val head = firstSentence(s, 24)
        val rest = s.removePrefix(Regex("[^。！？!?\\n]+[。！？!?]?").find(s)?.value.orEmpty()).trim().replace('\n', ' ')
        if (writing && !sentenceEnd.containsMatchIn(s)) out += TrailItem("t$k", null, "思考中", "", true, placeholder = true)
        else out += TrailItem("t$k", null, head, rest, writing)
    }
    var k = 0
    steps.forEachIndexed { j, st ->
        while (k < segs.size && starts[k] < st.at) seg(k++)
        out += TrailItem("s$j", st, st.title, st.detail, !st.done)
    }
    while (k < segs.size) seg(k++)
    // 还在想、手上没在用工具、也没有正在写的那段：末尾一个「思考中」，下一段到了就长成它
    if (live && !running && out.lastOrNull()?.live != true) out += TrailItem("t${segs.size}", null, "思考中", "", true, placeholder = true)
    return out
}

/** 聊天里那一行用的字：最后一个节点；末尾只是「思考中」时，用上一段的总结。 */
private fun headline(items: List<TrailItem>): String {
    val last = items.lastOrNull() ?: return ""
    if (!last.placeholder) return last.title
    return items.dropLast(1).lastOrNull { it.tool == null }?.title ?: last.title
}

private val TrailApricot = Color(0xFFE9B48E)
private val TrailLive = Color(0xFFD97757)

/** 聊天里的一行：进行中三点 + 流光字，结束换时钟图标；点开看过程。 */
@Composable
fun ThinkRow(raw: String, steps: List<ToolStep>, live: Boolean, onClick: () -> Unit) {
    val items = remember(raw, steps, live) { trailItems(raw, steps, live) }
    if (items.isEmpty()) return
    val text = headline(items)
    Pressable(onClick, Modifier.padding(bottom = 10.dp), label = "查看思考过程") { pressed ->
        Row(Modifier.padding(start = 2.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Crossfade(live, Modifier.size(17.dp), animationSpec = tween(280), label = "lead") { l ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (l) ThinkingMotion("", size = 14.sp) else SvgIcon(Trail.clock, Page.muted, 16.dp, stroke = 1.5f)
                }
            }
            Spacer(Modifier.width(8.dp))
            if (live) ThinkingMotion(text, Modifier.weight(1f, fill = false), size = 14.sp, showDots = false)
            else Txt(text, if (pressed) Page.ink else Page.muted, 14.sp, Modifier.weight(1f, fill = false), maxLines = 1)
            Spacer(Modifier.width(6.dp))
            SvgIcon(Trail.chevron, Page.muted, 14.dp)
        }
    }
}

/** 思考过程抽屉：半屏打开，上拉全屏，下拉关；开着时实时长内容、自动跟到最新。 */
@Composable
fun ThinkDrawer(open: Boolean, raw: String, steps: List<ToolStep>, live: Boolean, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val hPx = constraints.maxHeight.toFloat()
        val sheetPx = hPx * .92f
        val half = sheetPx - hPx * .58f
        var off by remember { mutableFloatStateOf(Float.NaN) }
        var visible by remember { mutableStateOf(false) }
        val job = remember { arrayOfNulls<Job>(1) }
        fun go(target: Float, ms: Int, then: () -> Unit = {}) {
            job[0]?.cancel()
            job[0] = scope.launch {
                animate(off, target, animationSpec = tween(ms, easing = Curves.standard)) { v, _ -> off = v }
                then()
            }
        }
        fun drag(d: Float) { job[0]?.cancel(); off = if (off + d < 0f) off + d / 3f else off + d }
        fun settle(v: Float) = when {
            v > 1400f || off > half + hPx * .18f -> onClose()
            v < -1000f || off < half * .5f -> go(0f, 300)
            else -> go(half, 300)
        }
        LaunchedEffect(open) {
            if (open) { visible = true; off = sheetPx; go(half, 320) }
            else if (visible) go(sheetPx, 220) { visible = false }
        }
        if (!visible || off.isNaN()) return@BoxWithConstraints
        val scrim = ((sheetPx - off) / (sheetPx - half)).coerceIn(0f, 1f)
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = scrim }.background(rgba(6, 9, 14, .5f)).pointerInput(Unit) { detectTapGestures { onClose() } })
        val nested = remember(half) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (available.y < 0f && off > 0f && source == NestedScrollSource.UserInput) {
                        val ny = (off + available.y).coerceAtLeast(0f); val used = ny - off; drag(used); return Offset(0f, used)
                    }
                    return Offset.Zero
                }
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (available.y > 0f && source == NestedScrollSource.UserInput) { drag(available.y); return Offset(0f, available.y) }
                    return Offset.Zero
                }
                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (off != 0f && off != half) { settle(available.y); return available }
                    return Velocity.Zero
                }
            }
        }
        val items = trailItems(raw, steps, live)
        val tools = steps.size
        Column(
            Modifier.align(Alignment.BottomCenter).widthIn(max = 640.dp).fillMaxWidth().height(maxHeight * .92f)
                .graphicsLayer { translationY = off }
                .background(rgba(20, 24, 31, .97f), RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Column(
                Modifier.fillMaxWidth().draggable(rememberDraggableState { drag(it) }, Orientation.Vertical, onDragStopped = { settle(it) })
                    .padding(start = 22.dp, end = 22.dp, top = 10.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(4.dp).background(rgba(255, 255, 255, .25f), RoundedCornerShape(2.dp)))
                Spacer(Modifier.height(14.dp))
                Box(Modifier.fillMaxWidth()) {
                    Pressable(onClose, Modifier.align(Alignment.CenterStart), label = "关闭") { _ ->
                        SvgIcon(Icons.close, Page.ink, 20.dp, modifier = Modifier.padding(6.dp))
                    }
                    Crossfade(if (tools > 0) "用了 $tools 个工具" else "摘要", Modifier.align(Alignment.Center), label = "title") { t ->
                        Txt(t, Page.ink, 16.5.sp, Modifier.padding(horizontal = 44.dp), family = Serif, weight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
            val scroll = rememberScrollState()
            val opened = remember { mutableStateOf(setOf<String>()) }
            LaunchedEffect(items.size, items.lastOrNull()?.title) {
                val near = scroll.maxValue - scroll.value < 160
                withFrameNanos { }
                if (near) scroll.animateScrollTo(scroll.maxValue)
            }
            Column(Modifier.weight(1f).nestedScroll(nested).verticalScroll(scroll).padding(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 40.dp)) {
                items.forEachIndexed { i, it ->
                    androidx.compose.runtime.key(it.key) {
                        TrailNode(it, i == items.lastIndex, it.key in opened.value) {
                            opened.value = if (it.key in opened.value) opened.value - it.key else opened.value + it.key
                        }
                    }
                }
            }
        }
    }
}

/** 一个节点：思考是小圆点（正在想的是橙点），工具是方块图标（正在用时杏色呼吸）；点一下原地展开全文。 */
@Composable
private fun TrailNode(it: TrailItem, last: Boolean, open: Boolean, onToggle: () -> Unit) {
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(360, easing = Curves.standard)) }
    val dot by animateColorAsState(if (it.live) TrailLive else rgba(255, 255, 255, .32f), tween(400), label = "dot")
    val tint by animateColorAsState(if (it.live) TrailApricot else Page.muted, tween(400), label = "tint")
    val breath = rememberInfiniteTransition(label = "breath").animateFloat(.12f, .2f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "b")
    Pressable({ if (!it.live) onToggle() }, Modifier.fillMaxWidth().graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * 6.dp.toPx() }, label = it.title) { pressed ->
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(if (pressed) rgba(255, 255, 255, .04f) else Color.Transparent, RoundedCornerShape(12.dp))) {
            Box(Modifier.width(26.dp).fillMaxHeight()) {
                val top = if (it.tool != null) 32.dp else 21.dp
                if (!last) Box(Modifier.padding(top = top, bottom = 4.dp).align(Alignment.TopCenter).width(1.dp).fillMaxHeight().background(rgba(255, 255, 255, .12f)))
                if (it.tool != null) {
                    Box(
                        Modifier.align(Alignment.TopCenter).size(26.dp)
                            .background(if (it.live) TrailApricot.copy(alpha = breath.value) else rgba(255, 255, 255, .06f), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) { SvgIcon(Trail.icon(it.tool.name), tint, 15.dp, stroke = 1.5f) }
                } else Box(Modifier.padding(top = 8.dp).align(Alignment.TopCenter).size(7.dp).background(dot, CircleShape))
            }
            Column(Modifier.weight(1f).padding(start = 12.dp, bottom = 22.dp, end = 6.dp, top = if (it.tool != null) 1.5.dp else 0.dp)) {
                if (it.live) ThinkingMotion(it.title, size = 14.5.sp, showDots = false, ink = Page.ink)
                else Txt(it.title, Page.ink.copy(alpha = .92f), 14.5.sp, lineHeight = 23.sp, maxLines = if (open) Int.MAX_VALUE else 1)
                if (it.detail.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Txt(it.detail, Page.muted, 12.5.sp, lineHeight = 19.sp, maxLines = if (open) Int.MAX_VALUE else if (it.tool != null) 1 else 2)
                }
            }
        }
    }
}
