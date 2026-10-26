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

private val saveIcon = Ic("M12 4v11m-4.5-4.5L12 15l4.5-4.5M5 19h14")

/** 每种工具在时间线上的说法和图标。 */
object Trail {
    fun liveTitle(n: String): String = when (n) {
        Memory.REMEMBER -> "正在记下"; Memory.FORGET -> "正在忘掉"; Recall.TOOL -> "正在翻看以前的聊天"; WebSearch.TOOL -> "正在上网查"
        Skills.OPEN -> "正在翻开本事"; Skills.FIND -> "正在 GitHub 上找本事"; Skills.LEARN -> "等你点头"
        Materials.OPEN -> "正在打开网页"; Materials.LOOK -> "正在看素材"; Gallery.TOOL -> "正在图库里找"; VideoParse.TOOL -> "正在解析抖音"; VideoParse.SAVE -> "正在存视频"
        PanTools.OPEN -> "正在打开网盘分享"; PanTools.GET -> "正在下网盘里的文件"
        Works.MAKE -> "正在动手做"; WorkJobs.START -> "正在交代"; Works.EDIT -> "正在改作品"; Works.READ -> "正在看原稿"; "list_todos" -> "正在查看羽的待办"; "read_note" -> "正在读羽的笔记"
        else -> if (Mcp.owns(n)) Mcp.note(n).trimEnd('…') else "正在查阅羽的笔记"
    }

    fun doneTitle(n: String, a: JSONObject): String = when (n) {
        Memory.REMEMBER -> "记下了一条"; Memory.FORGET -> "忘掉了一些"; Recall.TOOL -> "翻了以前的聊天"; WebSearch.TOOL -> "上网查了「${a.optString("query").trim().take(16)}」"
        Skills.OPEN -> a.optJSONArray("names")?.takeIf { it.length() > 1 }?.let { arr -> "翻开了 ${arr.length()} 样本事，合成一种做法" } ?: "翻开了「${(a.optJSONArray("names")?.optString(0) ?: a.optString("name")).trim().take(12)}」"; Skills.FIND -> "在 GitHub 上找了「${a.optString("query").trim().take(16)}」"
        Skills.LEARN -> if (Skills.has(SkillHub.cand(a.optString("repo"), a.optString("path")))) "学会了「${a.optString("title").trim().take(12)}」" else "没学「${a.optString("title").trim().take(12)}」"
        Works.MAKE -> (if (a.optString("kind") == "board") "搭好了画板" else "做好了作品") + "「${a.optString("title").trim().ifEmpty { "未命名" }.take(20)}」"
        Works.EDIT -> "改好了「${workTitle(a)}」"
        WorkJobs.START -> (if (a.optString("id").isNotBlank()) "交代了大改" else "交代了作品") + "「${a.optString("title").trim().ifEmpty { "未命名" }.take(20)}」，后台在写"
        Materials.OPEN -> Materials.doneTitle(a.optString("url"))
        Gallery.TOOL -> Gallery.doneTitle(a.optString("query"))
        VideoParse.TOOL, VideoParse.SAVE -> VideoParse.doneTitle(n, a)
        PanTools.OPEN, PanTools.GET -> PanTools.doneTitle(n, a)
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
        VideoParse.TOOL, VideoParse.SAVE -> Douyin.link(a.optString("text")) ?: a.optString("text")
        PanTools.OPEN, PanTools.GET -> Pan.link(a.optString("text"))?.link ?: a.optString("text")
        Gallery.TOOL -> a.optString("query")
        Works.EDIT -> "改 ${a.optJSONArray("edits")?.length() ?: 0} 处"
        Works.MAKE -> when (a.optString("kind")) { "slides" -> "作品 · 幻灯片"; "doc" -> "作品 · 文档"; "board" -> "画板"; else -> "作品 · 网页" }
        else -> ""
    }.trim().replace('\n', ' ').take(80)

    /** 作品用和作品卡同一个图标（圆 + 方）。 */
    fun icon(n: String): Ic = when (n) {
        Memory.REMEMBER -> bookmark; Memory.FORGET -> eraser; Recall.TOOL -> bubble; WebSearch.TOOL, Materials.OPEN -> globe; Materials.LOOK, Gallery.TOOL -> picture; VideoParse.TOOL -> Icons.play; VideoParse.SAVE, PanTools.GET -> saveIcon; PanTools.OPEN -> globe
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

/** 时间线上的一个节点。placeholder：Thinking…（等这段想完 / 等整理）；prose：原文型的一段原话。 */
class TrailItem(val key: String, val tool: ToolStep?, val title: String, val detail: String, val live: Boolean, val placeholder: Boolean = false, val tidy: Boolean = false, val prose: Boolean = false)

/** 思考原文的一段：按工具的位置和空行切开。done：这一段已经想完（后面还有字、隔着空行或工具，或者这轮想完了）。 */
class ThinkUnit(val text: String, val start: Int, val done: Boolean)

private val blank = Regex("\\n\\s*\\n")

fun thinkUnits(raw: String, steps: List<ToolStep>, live: Boolean): List<ThinkUnit> {
    val cuts = steps.map { it.at.coerceIn(0, raw.length) }
    val bounds = (listOf(0) + cuts + raw.length)
    val out = mutableListOf<ThinkUnit>()
    for (i in 0 until bounds.size - 1) {
        val a = bounds[i]; val b = bounds[i + 1].coerceAtLeast(a)
        val piece = raw.substring(a, b)
        var from = 0
        blank.findAll(piece).map { it.range }.plusElement(piece.length until piece.length).forEach { r ->
            val text = piece.substring(from, r.first).trim()
            if (text.isNotEmpty()) {
                // 后面隔着空行、后面是工具、或者这轮想完了：这一段想完了
                val closed = !live || r.first < piece.length || i < bounds.size - 2
                out += ThinkUnit(text, a + from, closed)
            }
            from = r.last + 1
        }
    }
    return out
}

/** 整理不成时用原文顶上：第一句当标题，其余当理由。 */
private fun fallback(key: String, u: String): TrailItem {
    val head = firstSentence(u, 24)
    val rest = u.removePrefix(Regex("[^。！？!?\\n]+[。！？!?]?").find(u)?.value.orEmpty()).trim().replace('\n', ' ')
    return TrailItem(key, null, head, rest, false)
}

/** 工具和这几段排在一起：按位置，工具排在它开始时写到的地方后面。 */
private inline fun interleave(units: List<ThinkUnit>, steps: List<ToolStep>, unit: (Int, ThinkUnit) -> Unit, tool: (Int, ToolStep) -> Unit) {
    var k = 0
    steps.forEachIndexed { j, st ->
        while (k < units.size && units[k].start < st.at) { unit(k, units[k]); k++ }
        tool(j, st)
    }
    while (k < units.size) { unit(k, units[k]); k++ }
}

/**
 * 摘要型的时间线（照 Claude App）：想完的每一段换成整理好的几步；还在想、还在整理的地方是一个 Thinking…，
 * 一路是「Thinking… → 一步 → Thinking… → 一步」。原话不出。
 */
fun summaryItems(raw: String, steps: List<ToolStep>, live: Boolean): List<TrailItem> {
    val units = thinkUnits(raw, steps, live)
    val out = mutableListOf<TrailItem>()
    fun wait(key: String) { if (out.lastOrNull()?.placeholder != true) out += TrailItem(key, null, "Thinking", "", true, placeholder = true) }
    interleave(units, steps, { k, u ->
        if (!u.done) { wait("w$k"); return@interleave }
        val got = ThinkTidy.get(u.text)
        when {
            got != null -> got.forEachIndexed { j, st -> out += TrailItem("d$k-$j", null, st.title, st.detail, false, tidy = true) }
            ThinkTidy.failed(u.text) -> out += fallback("f$k", u.text)
            else -> wait("w$k")
        }
    }) { j, st -> out += TrailItem("s$j", st, st.title, st.detail, !st.done) }
    // 还在想、手上没在用工具：末尾一个 Thinking…，下一步整理好了就接在它前面
    if (live && steps.none { !it.done }) wait("w-end")
    return out
}

/** 想完的段请帮手整理（前面整理出的标题一起带上，别重复）。 */
fun requestTidy(raw: String, steps: List<ToolStep>, live: Boolean, store: AssistantStore?) {
    val prev = mutableListOf<String>()
    thinkUnits(raw, steps, live).forEach { u ->
        if (!u.done) return@forEach
        val got = ThinkTidy.get(u.text)
        if (got == null) ThinkTidy.request(u.text, prev.takeLast(6), store) else prev += got.map { it.title }
    }
}

/** 原文型：整段原话照原样分段，工具排在原来的位置。 */
fun proseItems(raw: String, steps: List<ToolStep>, live: Boolean): List<TrailItem> {
    val units = thinkUnits(raw, steps, live)
    val out = mutableListOf<TrailItem>()
    interleave(units, steps, { k, u -> out += TrailItem("p$k", null, u.text, "", live && !u.done, prose = true) }) { j, st ->
        out += TrailItem("s$j", st, st.title, st.detail, !st.done)
    }
    if (live && steps.none { !it.done } && out.lastOrNull()?.live != true) out += TrailItem("w-end", null, "Thinking", "", true, placeholder = true)
    return out
}

private val TrailApricot = Color(0xFFE9B48E)
private val TrailLive = Color(0xFFD97757)

/** Thinking + 三个点依次跳（照原型：每个点隔 150ms，上跳 4dp，1.2 秒一轮）。 */
@Composable
fun ThinkingDots(color: Color = Page.muted, size: androidx.compose.ui.unit.TextUnit = 14.sp, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "dots")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = androidx.compose.animation.core.LinearEasing)), label = "dt")
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Txt("Thinking", color, size, maxLines = 1)
        repeat(3) { i ->
            // 每个点在自己那 25% 里上去再下来
            val p = ((t - i * .125f + 1f) % 1f)
            val up = if (p < .5f) kotlin.math.sin(p / .5f * Math.PI).toFloat() else 0f
            Box(
                Modifier.padding(start = if (i == 0) 3.dp else 2.5.dp, bottom = 5.dp)
                    .graphicsLayer { translationY = -4.dp.toPx() * up; alpha = .45f + .55f * up }
                    .size(3.5.dp).background(color, CircleShape),
            )
        }
    }
}

/**
 * 聊天里的一行（照 Claude App）。摘要型：还没有哪一步整理好时是 Thinking…，之后是最新那一步的结论（想着时流光），想完换时钟图标；
 * 原文型：想着时是最新一句的流光字，想完是「思考了 N 秒」。点开看过程。fetch：这一行负责请帮手整理（正在想的和刚想完的那条）。
 */
@Composable
fun ThinkRow(raw: String, steps: List<ToolStep>, live: Boolean, summary: Boolean, thinkMs: Long = 0L, fetch: Boolean = false, onClick: () -> Unit) {
    if (raw.isBlank() && steps.isEmpty()) return
    if (summary && fetch) LaunchedEffect(raw, steps.size, live) { requestTidy(raw, steps, live, AttachLook.store) }
    val items = if (summary) summaryItems(raw, steps, live) else emptyList()
    // 旧消息还没整理过（这一行不负责整理）：先用原文最后一段的第一句，点开抽屉时才整理
    val stale = summary && !live && !fetch && items.any { it.placeholder }
    val pending = summary && !stale && items.any { it.placeholder }
    // 最新一个看得到的节点（整理好的一步，或正在用的工具）
    val busy = live || pending
    // 整理完了却一步都没有（都是闲话）：用原文最后一段的第一句
    val head = if (summary) items.lastOrNull { !it.placeholder }?.title.orEmpty().ifEmpty { if (!busy) thinkSummary(raw).ifEmpty { "思考过程" } else "" } else ""
    Pressable(onClick, Modifier.padding(bottom = 10.dp), label = "查看思考过程") { pressed ->
        Row(Modifier.padding(start = 2.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                summary && head.isEmpty() -> ThinkingDots(Page.muted, 14.sp, Modifier.weight(1f, fill = false))
                summary && busy -> ThinkingMotion(head, Modifier.weight(1f, fill = false), size = 14.sp, showDots = false)
                summary -> {
                    SvgIcon(Trail.clock, Page.muted, 16.dp, stroke = 1.5f); Spacer(Modifier.width(8.dp))
                    Txt(head, if (pressed) Page.ink else Page.muted, 14.sp, Modifier.weight(1f, fill = false), maxLines = 1)
                }
                live -> ThinkingMotion(latestSentence(raw, 24).ifEmpty { steps.lastOrNull()?.title.orEmpty() }, Modifier.weight(1f, fill = false), size = 14.sp)
                else -> {
                    SvgIcon(Trail.clock, Page.muted, 16.dp, stroke = 1.5f); Spacer(Modifier.width(8.dp))
                    val t = if (thinkMs >= 100) "思考了 " + "%.1f".format(thinkMs / 1000f) + " 秒" else "思考过程"
                    Txt(t, if (pressed) Page.ink else Page.muted, 14.sp, Modifier.weight(1f, fill = false), maxLines = 1)
                }
            }
            if (!busy || !summary) { Spacer(Modifier.width(6.dp)); SvgIcon(Trail.chevron, Page.muted, 14.dp) }
        }
    }
}

/** 思考过程抽屉：半屏打开，上拉全屏，下拉关；开着时实时长内容、自动跟到最新。 */
@Composable
fun ThinkDrawer(open: Boolean, raw: String, steps: List<ToolStep>, live: Boolean, summary: Boolean, onClose: () -> Unit) {
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
        // 摘要型：打开着就把想完的段请帮手整理（旧消息第一次打开时才整理）
        if (summary) LaunchedEffect(open, raw, steps.size, live) { if (open) requestTidy(raw, steps, live, AttachLook.store) }
        val items = if (summary) summaryItems(raw, steps, live) else proseItems(raw, steps, live)
        Column(
            Modifier.align(Alignment.BottomCenter).widthIn(max = 640.dp).fillMaxWidth().height(maxHeight * .92f)
                .graphicsLayer { translationY = off }
                .background(Skin.sheet, RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Column(
                Modifier.fillMaxWidth().draggable(rememberDraggableState { drag(it) }, Orientation.Vertical, onDragStopped = { settle(it) })
                    .padding(start = 22.dp, end = 22.dp, top = 10.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(4.dp).background(veil(.25f), RoundedCornerShape(2.dp)))
                Spacer(Modifier.height(14.dp))
                Box(Modifier.fillMaxWidth()) {
                    Pressable(onClose, Modifier.align(Alignment.CenterStart), label = "关闭") { _ ->
                        SvgIcon(Icons.close, Page.ink, 20.dp, modifier = Modifier.padding(6.dp))
                    }
                    // 照 Claude App：摘要型叫「摘要」，原文型叫「思考过程」，常规字重
                    Txt(if (summary) "摘要" else "思考过程", Page.ink, 17.sp, Modifier.align(Alignment.Center).padding(horizontal = 44.dp), maxLines = 1)
                }
                Spacer(Modifier.height(20.dp))
            }
            val scroll = rememberScrollState()
            val opened = remember { mutableStateOf(setOf<String>()) }
            LaunchedEffect(items.size, items.lastOrNull()?.title?.length) {
                val near = scroll.maxValue - scroll.value < 160
                withFrameNanos { }
                if (near) scroll.animateScrollTo(scroll.maxValue)
            }
            Column(Modifier.weight(1f).nestedScroll(nested).verticalScroll(scroll).padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 40.dp)) {
                items.forEachIndexed { i, it ->
                    androidx.compose.runtime.key(it.key) {
                        if (it.prose) ProseNode(it)
                        else TrailNode(it, i == items.lastIndex || items.getOrNull(i + 1)?.prose == true, it.key in opened.value) {
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
private fun TrailNode(it: TrailItem, last: Boolean, open: Boolean, delay: Int = 0, onToggle: () -> Unit) {
    // Thinking… 走开得快一点（离开比进入快）
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(360, delayMillis = delay, easing = Curves.standard)) }
    val dot by animateColorAsState(if (it.live) TrailLive else veil(.32f), tween(400), label = "dot")
    val tint by animateColorAsState(if (it.live) TrailApricot else Page.muted, tween(400), label = "tint")
    val breath = rememberInfiniteTransition(label = "breath").animateFloat(.12f, .2f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "b")
    Pressable({ if (!it.live) onToggle() }, Modifier.fillMaxWidth().graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * 6.dp.toPx() }, label = it.title) { pressed ->
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(if (pressed) veil(.04f) else Color.Transparent, RoundedCornerShape(12.dp))) {
            // 照 Claude App 图二：圆点对齐标题第一行，竖线从圆点下面连到下一步；标题是结论，下面是理由，整理好的不截断
            Box(Modifier.width(26.dp).fillMaxHeight()) {
                val top = if (it.tool != null) 32.dp else 22.dp
                if (!last) Box(Modifier.padding(top = top, bottom = 4.dp).align(Alignment.TopCenter).width(1.dp).fillMaxHeight().background(veil(.13f)))
                if (it.tool != null) {
                    Box(
                        Modifier.align(Alignment.TopCenter).size(26.dp)
                            .background(if (it.live) TrailApricot.copy(alpha = breath.value) else veil(.06f), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) { SvgIcon(Trail.icon(it.tool.name), tint, 15.dp, stroke = 1.5f) }
                } else Box(Modifier.padding(top = 8.dp).align(Alignment.TopCenter).size(8.dp).background(dot, CircleShape))
            }
            Column(Modifier.weight(1f).padding(start = 12.dp, bottom = 26.dp, end = 4.dp, top = if (it.tool != null) 1.dp else 0.dp)) {
                val full = open || it.tidy
                if (it.placeholder) ThinkingDots(Page.muted, 15.sp)
                else if (it.live) ThinkingMotion(it.title, size = 15.5.sp, showDots = false, ink = Page.ink)
                else Txt(it.title, Page.ink.copy(alpha = .94f), 15.5.sp, lineHeight = 24.sp, weight = FontWeight.Medium, maxLines = if (full) Int.MAX_VALUE else 1)
                if (it.detail.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Txt(it.detail, Page.muted, 14.sp, lineHeight = 25.sp, maxLines = if (full) Int.MAX_VALUE else if (it.tool != null) 1 else 2)
                }
            }
        }
    }
}

/** 原文型的一段原话：照原样，不截断；正在写的那段末尾一个橙点。 */
@Composable
private fun ProseNode(it: TrailItem) {
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(300, easing = Curves.standard)) }
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp).graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * 4.dp.toPx() }, verticalAlignment = Alignment.Bottom) {
        Txt(it.title, Page.ink.copy(alpha = .78f), 14.5.sp, Modifier.weight(1f, fill = false), lineHeight = 27.sp)
        if (it.live) {
            val a by rememberInfiniteTransition(label = "cur").animateFloat(1f, .3f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "c")
            Box(Modifier.padding(start = 6.dp, bottom = 9.dp).size(6.dp).graphicsLayer { alpha = a }.background(TrailLive, CircleShape))
        }
    }
}
