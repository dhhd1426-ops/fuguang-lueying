package com.fuguang.lueying

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
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
import androidx.compose.runtime.derivedStateOf
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
        MotionTools.INSPECT -> "正在查看参考视频"; MotionTools.FRAMES -> "正在细看视频动作"
        MotionTools.RENDER -> "正在检查动画过程"; MotionTools.COMPARE -> "正在对照参考画面"
        Lessons.STEP -> "想起上次"; QwenSearch.STEP -> "正在上网查"; Fold.LOOK -> Fold.liveTitle()
        Memory.REMEMBER -> "正在记下"; Memory.READ -> "正在翻记忆"; Memory.FORGET -> "正在忘掉"; Recall.TOOL -> "正在翻看以前的聊天"; Recall.OPEN -> "正在回忆"; Recall.AUTO -> "想起之前聊过的"; WebSearch.TOOL -> "正在上网查"
        Skills.OPEN -> "正在翻开本事"; Skills.FIND -> "正在 GitHub 上找本事"; Skills.LEARN -> "等你点头"
        Materials.OPEN -> "正在打开网页"; Materials.LOOK -> "正在看素材"; VideoParse.TOOL -> "正在解析抖音"; VideoParse.SAVE -> "正在存视频"
        PanTools.OPEN -> "正在打开网盘分享"; PanTools.GET -> "正在下网盘里的文件"
        in ClockTools.names -> ClockTools.liveTitle(n)
        in WebHand.names -> WebHand.liveTitle(n)
        SentFiles.TOOL -> "正在打包文件"; SentPics.TOOL -> "正在拿图"; Pacts.SET -> "正在记约定"; Pacts.DROP -> "正在取消约定"; Works.MAKE -> "正在动手做"; WorkJobs.START -> "正在交代"; WorkJobs.STOP -> "正在停下"; WhaleTools.ACT -> "正在动一下"; WorkJobs.RESUME -> "正在接着做"; "get_work_status" -> "正在查做到哪"; Works.EDIT -> "正在改作品"; Works.READ -> "正在看原稿"; Steps.TOOL -> "正在理要做的事"; "list_todos" -> "正在查看羽的待办"; "read_note" -> "正在读羽的笔记"
        else -> if (Mcp.owns(n)) Mcp.note(n).trimEnd('…') else "正在查阅羽的笔记"
    }

    fun doneTitle(n: String, a: JSONObject): String = when (n) {
        MotionTools.INSPECT -> "查看了视频概览"; MotionTools.FRAMES -> "查看了视频帧图"
        MotionTools.RENDER -> "检查了动画过程"; MotionTools.COMPARE -> "对照了参考画面"
        Lessons.STEP -> "想起上次"; QwenSearch.STEP -> "用千问自带搜索查过了"; Fold.LOOK -> Fold.doneTitle(a)
        Memory.REMEMBER -> "记下了一条"; Memory.READ -> "翻了 ${a.optJSONArray("ids")?.length() ?: 1} 条记忆"; Memory.FORGET -> "忘掉了一些"; Recall.TOOL -> "翻了以前的聊天"; Recall.OPEN -> "回忆起那段聊天"; Recall.AUTO -> "想起之前聊过的"; WebSearch.TOOL -> "上网查了「${a.optString("query").trim().take(16)}」"
        Skills.OPEN -> a.optJSONArray("names")?.takeIf { it.length() > 1 }?.let { arr -> "翻开了 ${arr.length()} 样本事，合成一种做法" } ?: "翻开了「${(a.optJSONArray("names")?.optString(0) ?: a.optString("name")).trim().take(12)}」"; Skills.FIND -> "在 GitHub 上找了「${a.optString("query").trim().take(16)}」"
        Skills.LEARN -> if (Skills.has(SkillHub.cand(a.optString("repo"), a.optString("path")))) "学会了「${a.optString("title").trim().take(12)}」" else "没学「${a.optString("title").trim().take(12)}」"
        Works.MAKE -> (if (a.optString("kind") == "board") "搭好了画板" else "做好了作品") + "「${a.optString("title").trim().ifEmpty { "未命名" }.take(20)}」"
        Works.EDIT -> "改好了「${workTitle(a)}」"
        Pacts.SET -> (if (a.optString("id").isNotBlank()) "改了约定" else "记下了一条约定") + "「${a.optString("title").trim().take(12)}」"
        Pacts.DROP -> "取消了一条约定"
        SentFiles.TOOL -> a.optJSONArray("files").let { arr -> val n = arr?.length() ?: 0; val first = arr?.optJSONObject(0)?.optString("name")?.trim().orEmpty().take(16); if (n > 1) "发了「$first」等 $n 份文件" else "发了文件「$first」" }
        SentPics.TOOL -> "发了 ${a.optJSONArray("images")?.length() ?: 1} 张图"
        WorkJobs.START -> (if (a.optString("id").isNotBlank()) "交代了大改" else "交代了作品") + "「${a.optString("title").trim().ifEmpty { "未命名" }.take(20)}」，后台在写"
        WorkJobs.STOP -> "停下了后台在做的作品"
        WhaleTools.ACT -> "在屏幕上" + listOf(a.optString("move"), a.optString("mood"), a.optString("face")).filter { it.isNotBlank() }.joinToString("、").ifEmpty { "动了一下" }
        WorkJobs.RESUME -> "让后台从存着的稿接着做"
        "get_work_status" -> "查了做到哪"
        Materials.OPEN -> Materials.doneTitle(a.optString("url"))
        VideoParse.TOOL, VideoParse.SAVE -> VideoParse.doneTitle(n, a)
        PanTools.OPEN, PanTools.GET -> PanTools.doneTitle(n, a)
        in ClockTools.names -> ClockTools.doneTitle(n, a)
        in WebHand.names -> WebHand.doneTitle(n, a)
        Materials.LOOK -> "看了 ${a.optJSONArray("urls")?.length() ?: 0} 件素材"
        Works.READ -> "看了原稿"; Steps.TOOL -> Steps.doneTitle(a); "list_todos" -> "看了羽的待办"; "read_note" -> "读了一篇羽的笔记"
        else -> if (Mcp.owns(n)) Mcp.note(n).replace("正在用", "用了").trimEnd('…') else "查了羽的笔记"
    }

    fun failTitle(n: String, a: JSONObject): String =
        if (n in MotionTools.names) "画面检查未完成" else if (n == Steps.TOOL) "要做的事没记上" else if (n == Works.EDIT) "「${workTitle(a)}」没改成" else "作品「${a.optString("title").trim().ifEmpty { "未命名" }.take(20)}」没做成"

    private fun workTitle(a: JSONObject) = a.optString("title").trim().ifEmpty { Works.get(a.optString("id").trim())?.title ?: "作品" }.take(20)

    fun detail(n: String, a: JSONObject): String = when (n) {
        in MotionTools.names -> a.optString("source").ifBlank { "指定时刻的作品画面" }
        Lessons.STEP -> a.optString("text")
        Fold.LOOK -> Fold.detail(a)
        Memory.REMEMBER, Memory.FORGET -> a.optString("text")
        Memory.READ -> Memory.titles(a)
        Recall.TOOL, "search_notes", WebSearch.TOOL, Skills.FIND -> a.optString("query")
        Skills.OPEN -> a.optJSONArray("names")?.let { arr -> (0 until arr.length()).joinToString("、") { arr.optString(it) } } ?: a.optString("name")
        Skills.LEARN -> a.optString("repo") + "/" + a.optString("path")
        Materials.OPEN -> a.optString("url")
        VideoParse.TOOL, VideoParse.SAVE -> Douyin.link(a.optString("text")) ?: a.optString("text")
        PanTools.OPEN, PanTools.GET -> Pan.link(a.optString("text"))?.link ?: a.optString("text")
        Works.EDIT -> "改 ${a.optJSONArray("edits")?.length() ?: 0} 处"
        SentFiles.TOOL -> a.optJSONArray("files")?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.joinToString("、") { o -> o.optString("name").trim() + "." + o.optString("format") } }.orEmpty()
        SentPics.TOOL -> a.optJSONArray("images")?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.joinToString("、") { o -> o.optString("caption").trim().ifEmpty { o.optString("url").substringAfterLast('/').take(24) } } }.orEmpty()
        Works.MAKE -> when (a.optString("kind")) { "slides" -> "作品 · 幻灯片"; "doc" -> "作品 · 文档"; "board" -> "画板"; else -> "作品 · 网页" }
        else -> ""
    }.trim().replace('\n', ' ').take(80)

    /** 作品用和作品卡同一个图标（圆 + 方）。 */
    fun icon(n: String): Ic = when (n) {
        in MotionTools.names -> Icons.play
        Lessons.STEP -> bulb
        Memory.REMEMBER, Memory.READ -> bookmark; Memory.FORGET -> eraser; Recall.TOOL, Recall.AUTO -> bubble; Recall.OPEN, Fold.LOOK -> folder; WebSearch.TOOL, Materials.OPEN, QwenSearch.STEP -> globe; Materials.LOOK, SentPics.TOOL -> picture; VideoParse.TOOL -> Icons.play; VideoParse.SAVE, PanTools.GET -> saveIcon; PanTools.OPEN -> globe
        Skills.OPEN, Skills.FIND, Skills.LEARN -> skillIcon
        Works.MAKE, Works.EDIT -> shapes; Works.READ, SentFiles.TOOL -> page; Pacts.SET, Pacts.DROP -> alarmIcon; "list_todos", Steps.TOOL -> todo
        ClockTools.ALARM, ClockTools.NEXT, ClockTools.OFF -> alarmIcon
        in ClockTools.names -> calIcon
        in WebHand.names -> globe
        else -> if (Mcp.owns(n)) plug else feather
    }

    private val alarmIcon = Ic("M12 20a7 7 0 1 0 0-14 7 7 0 0 0 0 14ZM12 9.5V13l2.2 1.6M4.5 5.5 7 3.5M19.5 5.5 17 3.5")
    private val calIcon = Ic("M5 6h14a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1ZM4 10.5h16M8.5 4v4M15.5 4v4")
    val clock = Ic("M3.5 12a8.5 8.5 0 1 0 2.6-6.1M3.5 4.5v3.7h3.7M12 7.5V12l3 2")
    val chevron = Ic("M9.5 6l6 6-6 6")
    private val globe = Ic("M4 12h16M12 4c2.4 2.5 2.4 13.5 0 16M12 4c-2.4 2.5-2.4 13.5 0 16", circles = listOf(floatArrayOf(12f, 12f, 8f)))
    private val picture = Ic("M4 5h16v14H4ZM4 16l4.5-4 3.5 3 3-2.5L20 16", circles = listOf(floatArrayOf(9f, 9.5f, 1.6f)))
    val bulb = Ic("M9.5 18h5M10.5 21h3M12 3a6 6 0 0 0-3.5 10.9V16h7v-2.1A6 6 0 0 0 12 3Z")
    private val bookmark = Ic("M7 4h10a1 1 0 0 1 1 1v15l-6-3.5L6 20V5a1 1 0 0 1 1-1Z")
    private val eraser = Ic("M8.5 19.5h11M4.8 14.7l9.9-9.9a1.5 1.5 0 0 1 2.1 0l2.4 2.4a1.5 1.5 0 0 1 0 2.1L10.3 18.2a1.5 1.5 0 0 1-2.1 0l-3.4-3.4ZM9.6 9.9l4.5 4.5")
    private val folder = Ic("M4 6.5h6l2 2h8v9.5a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 18ZM9 14h6")
    private val bubble = Ic("M5 5.5h14v10H10l-4 3v-3H5ZM9 10.5h6")
    private val shapes = Ic("M12.5 12.5h7.5v7.5h-7.5Z", circles = listOf(floatArrayOf(9f, 9f, 5f)))
    private val page = Ic("M14 3H6v18h12V7ZM14 3v4h4M9 12h6M9 15.5h6")
    private val todo = Ic("M10 7h9M10 12h9M10 17h9M4.5 7l1.2 1.2L7.8 6M4.5 12l1.2 1.2 2.1-2.2M4.5 17l1.2 1.2 2.1-2.2")
    private val feather = Ic("M19 5c-6 0-11 5-11 11v3M19 5c0 7-4 11-11 11M8 19l-3 2")
    private val plug = Ic("M9 3v5M15 3v5M6.5 8h11v3a5.5 5.5 0 0 1-11 0ZM12 16.5V21")
}

/** 时间线上的一个节点。placeholder：Thinking…（等这段想完 / 等整理）；prose：原文型的一段原话。 */
class TrailItem(val key: String, val tool: ToolStep?, val title: String, val detail: String, val live: Boolean, val placeholder: Boolean = false, val tidy: Boolean = false, val prose: Boolean = false)

/** 一次模型响应的思考，历史记录用已保存的工具位置还原。 */
internal const val THINK_ROUND_END = "_think_round_end"
class ThinkUnit(val text: String, val start: Int, val done: Boolean)

fun thinkUnits(raw: String, steps: List<ToolStep>, live: Boolean): List<ThinkUnit> {
    val markers = steps.filter { it.name == THINK_ROUND_END }
    val cuts = (markers.ifEmpty { steps }).map { it.at.coerceIn(0, raw.length) }.distinct().sorted()
    return (listOf(0) + cuts + raw.length).zipWithNext().mapIndexedNotNull { index, (start, end) ->
        val text = raw.substring(start, end).trim()
        text.takeIf { it.isNotEmpty() }?.let { ThinkUnit(it, start, !live || index < cuts.size) }
    }
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
        if (st.name != THINK_ROUND_END) tool(j, st)
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
            got != null -> got.firstOrNull()?.let { st -> out += TrailItem("d$k", null, st.title, got.joinToString("\n") { it.detail.ifBlank { it.title } }, false, tidy = true) }
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
    if (live && steps.none { !it.done } && out.lastOrNull()?.live != true) out += TrailItem("p${units.size}", null, "Thinking", "", true, placeholder = true)
    return out
}

private fun collapsedProseItems(raw: String, steps: List<ToolStep>, live: Boolean): List<TrailItem> {
    return proseItems(raw, steps, live).map { item ->
        if (item.placeholder) TrailItem(item.key, null, "", "", true, prose = true) else item
    }
}

// Timeline colors are paired with the sheet, including the light gradient palettes.
private val trailSecondary: Color get() = if (Skin.id == Skin.LIGHT) Color(0xFF66645E) else Page.muted
private val trailActive: Color get() = if (Skin.light) Color(0xFF9E4B2D) else Color(0xFFF0A67C)
private val trailDoneDot: Color get() = if (Skin.light) Color(0xFF89847D) else Color(0xFF85817E)
private val trailLine: Color get() = if (Skin.light) Color.Black.copy(alpha = .17f) else Color.White.copy(alpha = .24f)
private val trailError: Color get() = if (Skin.light) Color(0xFFA33E39) else Skin.danger

/** Thinking + 三个点依次跳（照原型：每个点隔 150ms，上跳 4dp，1.2 秒一轮）。 */
@Composable
fun ThinkingDots(color: Color = Page.muted, size: androidx.compose.ui.unit.TextUnit = 14.sp, modifier: Modifier = Modifier) {
    if (Toy.still()) {
        Txt("Thinking…", color, size, modifier, maxLines = 1)
        return
    }
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

private val archivedTeamTools = setOf("spawn_team", "team_look", "team_tell", "team_stop")

/**
 * 聊天里的一行（照 Claude App）。摘要型：还没有哪一步整理好时是 Thinking…，之后是最新那一步的结论（想着时流光），想完换时钟图标；
 * 原文型：想着时是最新一句的流光字，想完是「思考了 N 秒」。点开看过程。fetch：这一行负责请帮手整理（正在想的和刚想完的那条）。
 */
@Composable
fun ThinkRow(raw: String, savedSteps: List<ToolStep>, live: Boolean, summary: Boolean, thinkMs: Long = 0L, fetch: Boolean = false, onClick: () -> Unit) {
    val steps = remember(savedSteps) { savedSteps.filterNot { it.name in archivedTeamTools } }
    if (raw.isBlank() && steps.isEmpty()) return
    if (summary && fetch) LaunchedEffect(raw, steps.size, live) { requestTidy(raw, steps, live, AttachLook.store) }
    // 原文变了才重新切段（以前每次重组都切一遍）；整理结果到了 derivedStateOf 自己会更新
    val items by remember(raw, steps, live, summary) { derivedStateOf { if (summary) summaryItems(raw, steps, live) else emptyList() } }
    // 旧消息还没整理过（这一行不负责整理）：先用原文最后一段的第一句，点开抽屉时才整理
    val stale = summary && !live && !fetch && items.any { it.placeholder }
    val pending = summary && !stale && items.any { it.placeholder }
    // 最新一个看得到的节点（整理好的一步，或正在用的工具）
    val busy = live || pending
    // 整理完了却一步都没有（都是闲话）：用原文最后一段的第一句
    val head = if (summary) items.lastOrNull { !it.placeholder }?.title.orEmpty().ifEmpty { if (!busy) thinkSummary(raw).ifEmpty { "思考过程" } else "" } else ""
    val hitArea = Modifier.padding(bottom = 10.dp).then(
        if (Skin.frost) Modifier.background(Color(0xE614181F), RoundedCornerShape(9.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp) else Modifier
    )
    Pressable(onClick, hitArea, label = "查看思考过程") { pressed ->
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
                    // 没想、只用了工具（或只想起了上次）：写最后一步的标题
                    val t = if (thinkMs >= 100) "思考了 " + "%.1f".format(thinkMs / 1000f) + " 秒" else if (raw.isBlank()) steps.lastOrNull()?.title.orEmpty().ifEmpty { "思考过程" } else "思考过程"
                    Txt(t, if (pressed) Page.ink else Page.muted, 14.sp, Modifier.weight(1f, fill = false), maxLines = 1)
                }
            }
            if (!busy || !summary) { Spacer(Modifier.width(6.dp)); SvgIcon(Trail.chevron, Page.muted, 14.dp) }
        }
    }
}

/** 思考过程抽屉：半屏打开，上拉全屏，下拉关；开着时实时长内容、自动跟到最新。 */
@Composable
fun ThinkDrawer(open: Boolean, raw: String, savedSteps: List<ToolStep>, live: Boolean, summary: Boolean, onClose: () -> Unit) {
    val steps = remember(savedSteps) { savedSteps.filterNot { it.name in archivedTeamTools } }
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
            if (open) { off = sheetPx; visible = true; go(half, 320) }
            else if (visible) go(sheetPx, 220) { visible = false }
        }
        // off 每帧都在变：只在 graphicsLayer 里读，拖动时不重建整个抽屉（以前每拖一帧都把思考全文重新切段、重排一遍，按住拖会卡住）
        if (!visible) return@BoxWithConstraints
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = ((sheetPx - off) / (sheetPx - half)).coerceIn(0f, 1f) }.background(Skin.scrim(.5f)).pointerInput(Unit) { detectTapGestures { onClose() } })
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
        // 内容变了才重新切段；derivedStateOf 跟着整理结果（ThinkTidy 的 state map）自己更新
        val items by remember(raw, steps, live, summary) {
            derivedStateOf { mergeRepeats(collapsedProseItems(raw, steps, live)) }
        }
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
            // 后台作品那一步也默认折叠（以前默认展开），点开才看里面一步步
            val openJobs = remember { mutableStateOf(setOf<String>()) }
            LaunchedEffect(items.size, items.lastOrNull()?.title?.length) {
                val near = scroll.maxValue - scroll.value < 160
                withFrameNanos { }
                if (near) scroll.animateScrollTo(scroll.maxValue)
            }
            Column(Modifier.weight(1f).nestedScroll(nested).verticalScroll(scroll).padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 40.dp)) {
                val stepNodes = items.filter { s -> s.tool?.name == Steps.TOOL && s.detail.isNotEmpty() }
                items.forEachIndexed { i, it ->
                    androidx.compose.runtime.key(it.key) {
                        val art = it.tool?.takeIf { step ->
                            step.name == WorkJobs.START && step.detail.startsWith(WorkJobs.PREFIX)
                        }?.let { step -> WorkJobs.keyOf(step.detail) }
                        val last = i == items.lastIndex
                        if (it.prose) {
                            ThoughtTrailNode(it.title, it.live, last, summary)
                        }
                        else if (art != null) {
                            val saved by androidx.compose.runtime.produceState(emptyList<ArtworkRuns.Entry>(), art) {
                                value = ArtworkRuns.load(art)
                            }
                            val runs = ArtworkRuns.live(art) ?: saved
                            val expanded = art in openJobs.value
                            val js = WorkJobs.state[art]
                            val meta = when {
                                js == null -> null
                                js.done != null -> "做好了"
                                js.fail != null -> if (js.stopped) (if (js.revision > 0) "停在第 ${js.revision} 稿" else "停下了") else "没做成"
                                js.revision > 0 -> "第 ${js.revision} 稿"
                                else -> js.stage
                            }
                            TrailNode(TrailItem(it.key, it.tool, it.title, "", it.live), last && (!expanded || runs.isEmpty()), expanded, expandable = true, meta = meta) {
                                openJobs.value = if (expanded) openJobs.value - art else openJobs.value + art
                            }
                            // 展开后里面的步骤缩进一层，每一步同样默认折叠
                            AnimatedVisibility(expanded, enter = trailEnter(),
                                exit = trailExit()) {
                                Column(Modifier.padding(start = 22.dp)) {
                                    if (runs.isEmpty()) Txt(js?.fail ?: js?.stage ?: "暂无执行记录", trailSecondary, 13.sp, Modifier.padding(start = 38.dp, bottom = 14.dp))
                                    runs.forEachIndexed { index, run ->
                                        androidx.compose.runtime.key(run.id) {
                                            val end = last && index == runs.lastIndex
                                            if (run.kind == "thought") ThoughtTrailNode(run.output, run.status == "running", end, tidy = true)
                                            else RenderTrailNode(art, run, end)
                                        }
                                    }
                                }
                            }
                        } else TrailNode(it, last, it.key in opened.value, stepsAt = stepNodes.indexOfFirst { s -> s.key == it.key },
                            stepNodes = stepNodes, stepsLive = live) {
                            opened.value = if (it.key in opened.value) opened.value - it.key else opened.value + it.key
                        }
                    }
                }
            }
        }
    }
}

private val renderCommandIcon = Ic("M5 7l5 5-5 5m8 0h6")

@Composable
private fun RenderTrailNode(job: String, run: ArtworkRuns.Entry, last: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val title = when {
        run.kind == "motion" -> run.output.lineSequence().firstOrNull().orEmpty()
        run.kind in setOf("draft", "present", "review") -> run.output
        run.status == "running" -> "正在渲染预览"
        run.status == "success" -> "已运行渲染"
        run.status == "failed" -> "渲染失败"
        else -> "渲染已停止"
    }
    val tint = when (run.status) {
        "running" -> trailActive
        "failed" -> trailError
        else -> trailSecondary
    }
    // 右边的小缩略图：看视频 / 对照的帧图拼图，或这一稿的渲染图
    val thumb = remember(run.id, run.status) {
        MotionEvidence.firstImage(run.output)
            ?: if (run.kind == "render" && run.status == "success" && run.version > 0) ArtworkJob.directory(job)?.let { java.io.File(it, "${run.version}.png") }?.takeIf { it.isFile } else null
    }
    val rail = if (thumb != null) 38.dp else 26.dp
    Pressable({ expanded = !expanded }, Modifier.fillMaxWidth(), label = title) { pressed ->
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)
            .background(if (pressed) veil(.04f) else Color.Transparent, RoundedCornerShape(8.dp))) {
            Box(Modifier.width(26.dp).fillMaxHeight()) {
                TrailConnector(last, rail / 2 + 16.dp)
                Box(Modifier.align(Alignment.TopCenter).padding(top = (rail - 26.dp) / 2).size(26.dp).background(veil(.06f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center) {
                    SvgIcon(renderCommandIcon, tint, 15.dp, stroke = 1.5f)
                }
            }
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp, bottom = 18.dp)) {
                Row(Modifier.fillMaxWidth().height(rail), verticalAlignment = Alignment.CenterVertically) {
                    Txt(title, if (run.status == "failed") tint else Page.ink.copy(alpha = .94f), 15.sp, Modifier.weight(1f), maxLines = 1)
                    if (run.ms > 0) { Spacer(Modifier.width(8.dp)); Txt("%.1f 秒".format(run.ms / 1000.0), trailSecondary, 12.sp, maxLines = 1) }
                    if (thumb != null) { Spacer(Modifier.width(8.dp)); TrailThumb(thumb) }
                    Spacer(Modifier.width(8.dp))
                    TrailChevron(expanded)
                }
                AnimatedVisibility(expanded, enter = trailEnter(),
                    exit = trailExit()) {
                    val action = when (run.kind) {
                        "draft" -> "write_draft"
                        "review" -> "make_work · WebView"
                        "present" -> if (run.version > 0) "present_artwork" else "present_work"
                        "motion" -> "video · 帧图"
                        else -> "render_preview · WebView"
                    }
                    val detail = if (run.version > 0) "$action · 第 ${run.version} 稿" else action
                    StepBox(Modifier.padding(top = 8.dp)) {
                        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Txt(detail, trailSecondary, 12.sp, Modifier.weight(1f).horizontalScroll(rememberScrollState()), family = FontFamily.Monospace, maxLines = 1)
                            Pressable({
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("执行步骤", detail))
                            }, label = "复制执行步骤") {
                                Txt("复制", trailSecondary, 12.sp, Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                            }
                        }
                        val body = run.output.lineSequence().drop(if (run.kind == "motion") 1 else 0).joinToString("\n").trim()
                        if (body.isNotBlank()) {
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Skin.workLine))
                            Txt(body, tint, 13.sp, Modifier.padding(12.dp), lineHeight = 20.sp)
                        }
                        if (thumb != null) TrailImage(thumb)
                        Box(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) { MotionEvidence.Button(run.output) }
                    }
                }
            }
        }
    }
}

/** 展开后的白底圆角卡（照 Claude App）：实底 + 1dp 细线，四种主题都看得清。 */
@Composable
private fun StepBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(modifier.fillMaxWidth().background(Skin.workFill, shape).border(1.dp, Skin.workLine, shape).clip(shape)) { content() }
}

/** 展开 / 收起的过渡：往下长出 250ms、收回 200ms（离开比进入快）；系统关了动画就直接切换（动效指南第 3 节）。 */
@Composable
private fun trailEnter(): androidx.compose.animation.EnterTransition =
    if (Toy.still()) androidx.compose.animation.EnterTransition.None else expandVertically(tween(250, easing = Curves.standard)) + fadeIn(tween(150))

@Composable
private fun trailExit(): androidx.compose.animation.ExitTransition =
    if (Toy.still()) androidx.compose.animation.ExitTransition.None else shrinkVertically(tween(200, easing = Curves.standard)) + fadeOut(tween(100))

/** › 点开时转 90°。 */
@Composable
private fun TrailChevron(open: Boolean) {
    val turn by animateFloatAsState(if (open) 90f else 0f, tween(if (Toy.still()) 0 else 250, easing = Curves.standard), label = "chev")
    SvgIcon(Trail.chevron, trailSecondary, 13.dp, modifier = Modifier.graphicsLayer { rotationZ = turn })
}

/** 行右边 64×40 的小缩略图：后台解码、缩到 128 宽，免得抽屉卡。 */
@Composable
private fun TrailThumb(file: java.io.File) {
    val img by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, file) { value = ThumbCache.get(file, 128) }
    Box(Modifier.size(64.dp, 40.dp).clip(RoundedCornerShape(8.dp)).background(veil(.06f)).border(1.dp, Skin.workLine, RoundedCornerShape(8.dp))) {
        img?.let { androidx.compose.foundation.Image(it, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
    }
}

/** 展开后的大图（卡片全宽）。 */
@Composable
private fun TrailImage(file: java.io.File) {
    val img by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, file) { value = ThumbCache.get(file, 960) }
    img?.let { androidx.compose.foundation.Image(it, null, Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(8.dp)), contentScale = androidx.compose.ui.layout.ContentScale.FillWidth) }
}

/** 缩略图缓存：按文件 + 宽度，最多 48 张。 */
private object ThumbCache {
    private val cache = object : LinkedHashMap<String, androidx.compose.ui.graphics.ImageBitmap>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, androidx.compose.ui.graphics.ImageBitmap>?) = size > 48
    }
    suspend fun get(file: java.io.File, width: Int): androidx.compose.ui.graphics.ImageBitmap? {
        val k = file.path + "@" + width + "@" + file.lastModified()
        synchronized(cache) { cache[k] }?.let { return it }
        val got = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(file.path, o)
                var sample = 1
                while (o.outWidth / (sample * 2) >= width) sample *= 2
                android.graphics.BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        } ?: return null
        synchronized(cache) { cache[k] = got }
        return got
    }
}

/** 连着同一个工具、同一个标题、都做完了的几步合成一行「· N 次」；有失败的标题不同，不会合进来。 */
private fun mergeRepeats(items: List<TrailItem>): List<TrailItem> {
    val out = mutableListOf<TrailItem>()
    var n = 1
    fun flush() {
        if (n > 1) { val x = out.removeAt(out.lastIndex); out += TrailItem(x.key, x.tool, "${x.title} · $n 次", x.detail, false) }
        n = 1
    }
    for (it in items) {
        val prev = out.lastOrNull()
        val same = prev != null && n >= 1 && it.tool != null && prev.tool != null && !it.live && !prev.live &&
            it.tool.name == prev.tool.name && it.tool.name != Steps.TOOL && it.tool.done && prev.tool.done && it.title == prev.title.substringBefore(" · ")
        if (same && prev != null) {
            n++
            if (it.detail.isNotBlank() && it.detail !in prev.detail) out[out.lastIndex] = TrailItem(prev.key, prev.tool, prev.title, (prev.detail + "\n" + it.detail).trim(), false)
        } else { flush(); out += it }
    }
    flush()
    return out
}

/**
 * 一个节点（全抽屉统一的样子）：左边小圆点（想）或方块图标（做，正在做时杏色呼吸）；一行标题 15sp，右边 meta、有内容才有 ›。
 * 默认折叠，点了内容往下长出来：想的那步是灰字理由，做的那步是白底圆角卡（照 Claude App）。
 */
@Composable
private fun TrailNode(it: TrailItem, last: Boolean, open: Boolean, delay: Int = 0, expandable: Boolean = false, meta: String? = null,
    stepsAt: Int = -1, stepNodes: List<TrailItem> = emptyList(), stepsLive: Boolean = false, onToggle: () -> Unit) {
    // Thinking… 走开得快一点（离开比进入快）
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(360, delayMillis = delay, easing = Curves.standard)) }
    val dot by animateColorAsState(if (it.live) trailActive else trailDoneDot, tween(400), label = "dot")
    val tint by animateColorAsState(if (it.live) trailActive else trailSecondary, tween(400), label = "tint")
    val breath = rememberInfiniteTransition(label = "breath").animateFloat(.12f, .2f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "b")
    // 「要做的事」：最新那张一直摊开，之前的只留小点，点一下才摊开
    val steps = if (stepsAt >= 0) remember(it.detail) { Steps.decode(it.detail) } else null
    val newest = steps != null && stepsAt == stepNodes.lastIndex
    val has = !it.placeholder && (expandable || it.detail.isNotEmpty()) && !newest
    Pressable({ if (has) onToggle() }, Modifier.fillMaxWidth().graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * 6.dp.toPx() }, label = it.title) { pressed ->
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(if (pressed && has) veil(.04f) else Color.Transparent, RoundedCornerShape(12.dp))) {
            Box(Modifier.width(26.dp).fillMaxHeight()) {
                TrailConnector(last, if (it.tool != null) 29.dp else 21.dp)
                if (it.tool != null) {
                    Box(
                        Modifier.align(Alignment.TopCenter).size(26.dp)
                            .background(if (it.live) trailActive.copy(alpha = breath.value) else veil(.06f), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) { SvgIcon(Trail.icon(it.tool.name), tint, 15.dp, stroke = 1.5f) }
                } else Box(Modifier.padding(top = 9.dp).align(Alignment.TopCenter).size(8.dp).background(dot, CircleShape))
            }
            Column(Modifier.weight(1f).padding(start = 12.dp, bottom = 18.dp, end = 4.dp)) {
                Row(Modifier.fillMaxWidth().height(26.dp), verticalAlignment = Alignment.CenterVertically) {
                    when {
                        it.placeholder -> ThinkingDots(trailSecondary, 15.sp, Modifier.weight(1f))
                        it.live -> Box(Modifier.weight(1f)) { ThinkingMotion(it.title, size = 15.sp, showDots = false, ink = Page.ink) }
                        else -> Txt(it.title, Page.ink.copy(alpha = .94f), 15.sp, Modifier.weight(1f), maxLines = 1)
                    }
                    if (steps != null && !it.live) StepPips(steps)
                    if (!meta.isNullOrBlank()) { Spacer(Modifier.width(8.dp)); Txt(meta, if (it.live) trailActive else trailSecondary, 12.sp, maxLines = 1) }
                    if (has) { Spacer(Modifier.width(8.dp)); TrailChevron(open) }
                }
                if (steps != null) AnimatedVisibility(newest || open, enter = trailEnter(), exit = trailExit()) {
                    val before = remember(stepsAt, stepNodes) { stepNodes.getOrNull(stepsAt - 1)?.let { b -> Steps.decode(b.detail) } }
                    StepsList(steps, before, move = newest && stepsLive)
                } else AnimatedVisibility(open && it.detail.isNotEmpty(), enter = trailEnter(),
                    exit = trailExit()) {
                    Column {
                        if (it.tool == null) Txt(it.detail, trailSecondary, 14.sp, Modifier.padding(top = 4.dp), lineHeight = 24.sp)
                        else StepBox(Modifier.padding(top = 8.dp)) {
                            Txt(it.detail, Page.ink.copy(alpha = .86f), 13.5.sp, Modifier.padding(12.dp), lineHeight = 21.sp)
                        }
                        MotionEvidence.Button(it.detail)
                    }
                }
            }
        }
    }
}

/** Completed connectors are stable; a newly available next row draws only this segment. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.TrailConnector(last: Boolean, top: androidx.compose.ui.unit.Dp) {
    val still = Toy.still()
    val grow = remember { Animatable(if (last) 0f else 1f) }
    LaunchedEffect(last, still) {
        if (last || still) grow.snapTo(if (last) 0f else 1f)
        else grow.animateTo(1f, tween(250, easing = Curves.standard))
    }
    if (!last) Box(Modifier.align(Alignment.TopCenter).padding(top = top, bottom = 2.dp)
        .width(1.dp).fillMaxHeight().graphicsLayer {
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(.5f, 0f)
            scaleY = grow.value
        }.background(trailLine))
}

/**
 * 一段思考（原文型，或后台那支笔的思考）：标题一行，点开看全文。
 * tidy：后台的思考（多半是英文）交给帮手整理成中文——标题用整理好的结论，展开是整理好的理由，原话不出；
 * 只在这一行真的画出来（作品那步展开了）时才请求，按原文缓存。
 */
@Composable
private fun ThoughtTrailNode(text: String, live: Boolean, last: Boolean, summary: Boolean = false, tidy: Boolean = false) {
    var expanded by remember { mutableStateOf(false) }
    val still = Toy.still()
    val first = remember(text) { text.trim() }
    if (tidy && !live) LaunchedEffect(first) { ThinkTidy.request(first, emptyList(), AttachLook.store) }
    val tidied = if (summary || tidy) ThinkTidy.get(first)?.takeIf { it.isNotEmpty() } else null
    val label = tidied?.firstOrNull()?.title ?: firstSentence(first, 46).ifBlank { "思考摘要" }
    val body = tidied?.joinToString("\n") { it.detail.ifBlank { it.title } } ?: text
    val expandable = body.trim().trimEnd('。', '.', '！', '!') != label.trimEnd('。', '.', '！', '!')
    val dot by animateColorAsState(if (live) trailActive else trailDoneDot,
        tween(if (still) 0 else 185, easing = Curves.standard), label = "thoughtDot")
    val arrival = remember { Animatable(if (live && !still) 0f else 1f) }
    LaunchedEffect(Unit) {
        arrival.animateTo(1f, tween(if (still) 0 else 195, delayMillis = if (live && !still) 100 else 0,
            easing = Curves.standard))
    }
    Pressable({ if (!live && expandable) expanded = !expanded }, Modifier.fillMaxWidth(),
        label = if (expanded) "收起思考" else "展开思考") { pressed ->
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)
            .background(if (pressed && expandable) veil(.04f) else Color.Transparent, RoundedCornerShape(8.dp))) {
            Box(Modifier.width(26.dp).fillMaxHeight()) {
                TrailConnector(last, 21.dp)
                Box(Modifier.align(Alignment.TopCenter).padding(top = 9.dp).size(8.dp)
                    .graphicsLayer {
                        alpha = arrival.value
                        scaleX = .55f + .45f * arrival.value
                        scaleY = scaleX
                    }.background(dot, CircleShape))
            }
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp, bottom = 18.dp)) {
                Crossfade(live, animationSpec = tween(if (still) 0 else 180), label = "thoughtSummary") { pending ->
                    Row(Modifier.fillMaxWidth().height(26.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (pending) ThinkingDots(trailSecondary, 15.sp)
                        else {
                            Txt(label, Page.ink.copy(alpha = .94f), 15.sp, Modifier.weight(1f), maxLines = 1)
                            if (expandable) { Spacer(Modifier.width(8.dp)); TrailChevron(expanded) }
                        }
                    }
                }
                AnimatedVisibility(expanded && !live,
                    enter = if (still) androidx.compose.animation.EnterTransition.None else expandVertically(tween(250, easing = Curves.standard)) + fadeIn(tween(150)),
                    exit = if (still) androidx.compose.animation.ExitTransition.None else shrinkVertically(tween(200, easing = Curves.standard)) + fadeOut(tween(100))) {
                    Txt(body, trailSecondary, 14.sp, Modifier.padding(top = 4.dp), lineHeight = 24.sp)
                }
            }
        }
    }
}

/** 「要做的事」标题右边：一项一个小点。空心没做，杏色在做，实心做完。 */
@Composable
private fun StepPips(items: List<Steps.Item>) {
    Row(Modifier.padding(start = 8.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        items.forEach { s ->
            val c = if (s.state == Steps.DOING) trailActive else trailDoneDot
            val k = if (s.state == Steps.DOING) 1.25f else 1f
            androidx.compose.foundation.Canvas(Modifier.size(6.dp).graphicsLayer { scaleX = k; scaleY = k }) {
                val sw = 1.2.dp.toPx()
                if (s.state == Steps.PENDING) drawCircle(c, size.minDimension / 2 - sw / 2, style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
                else drawCircle(c)
            }
        }
    }
}

/** 「要做的事」下面的小清单。before 是上一张：只有变了的那几项动，等节点落定（360ms）再动。 */
@Composable
private fun StepsList(items: List<Steps.Item>, before: List<Steps.Item>?, move: Boolean) {
    val animate = move && before != null && !Toy.still()
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        var fresh = 0
        val seen = HashMap<String, Int>()
        items.forEach { s ->
            val nth = seen.merge(s.text, 1, Int::plus) ?: 1
            val was = before?.firstOrNull { b -> b.text == s.text }?.state
            val isNew = animate && was == null
            val order = if (isNew) fresh++ else 0
            androidx.compose.runtime.key(s.text, nth) {
                StepRow(s, if (animate) was ?: s.state else s.state, isNew, order, breathe = move && s.state == Steps.DOING)
            }
        }
    }
}

@Composable
private fun StepRow(s: Steps.Item, from: String, isNew: Boolean, order: Int, breathe: Boolean) {
    val enter = remember { Animatable(if (isNew) 0f else 1f) }
    val done = remember { Animatable(if (from == Steps.DONE) 1f else 0f) }
    val doing = remember { Animatable(if (from == Steps.DOING) 1f else 0f) }
    LaunchedEffect(s.state) {
        launch { enter.animateTo(1f, tween(280, delayMillis = 360 + order * 40, easing = Curves.decel)) }
        // 先打勾，停一拍，下一项再亮
        launch { doing.animateTo(if (s.state == Steps.DOING) 1f else 0f, tween(240, delayMillis = if (s.state == Steps.DOING && from != Steps.DOING) 620 else 360, easing = Curves.standard)) }
        done.animateTo(if (s.state == Steps.DONE) 1f else 0f, tween(300, delayMillis = 360, easing = Curves.decel))
    }
    val breath = if (breathe) rememberInfiniteTransition(label = "stepBreath").animateFloat(1f, .45f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "sb") else null
    val ring = trailDoneDot
    val act = trailActive
    val hole = Skin.sheet
    Row(Modifier.fillMaxWidth().graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * 6.dp.toPx() }.padding(vertical = 5.dp)) {
        androidx.compose.foundation.Canvas(Modifier.padding(top = 3.dp).size(16.dp)) {
            val u = size.minDimension / 16f
            val r = size.minDimension / 2
            val sw = 1.4.dp.toPx()
            val d = done.value
            val g = doing.value
            drawCircle(androidx.compose.ui.graphics.lerp(ring, act, g), r - sw / 2, style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
            if (g > 0f) drawCircle(act.copy(alpha = act.alpha * (breath?.value ?: 1f)), (r - 4f * u) * g)
            if (d > 0f) {
                drawCircle(ring.copy(alpha = ring.alpha * d), r)
                // 勾：两段，按长度依次画出来
                val a = Offset(4.6f * u, 8.2f * u); val b = Offset(6.8f * u, 10.4f * u); val c = Offset(11.4f * u, 5.6f * u)
                val l1 = (b - a).getDistance(); val l2 = (c - b).getDistance()
                val len = (l1 + l2) * d
                val w = 1.8f * u
                drawLine(hole, a, a + (b - a) * (len / l1).coerceAtMost(1f), w, androidx.compose.ui.graphics.StrokeCap.Round)
                if (len > l1) drawLine(hole, b, b + (c - b) * ((len - l1) / l2).coerceAtMost(1f), w, androidx.compose.ui.graphics.StrokeCap.Round)
            }
        }
        Spacer(Modifier.width(10.dp))
        val base = if (s.state == Steps.PENDING && from == Steps.PENDING) Page.ink.copy(alpha = .82f) else Page.ink
        Txt(s.text, androidx.compose.ui.graphics.lerp(base, Page.muted, done.value), 14.5.sp,
            Modifier.weight(1f, fill = false).drawWithContent {
                drawContent()
                val k = ((done.value - .3f) / .7f).coerceIn(0f, 1f)
                if (k > 0f) drawLine(ring, Offset(0f, size.height / 2), Offset(size.width * k, size.height / 2), 1.dp.toPx())
            }, lineHeight = 22.sp)
    }
}
