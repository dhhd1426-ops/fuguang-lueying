package com.fuguang.lueying

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 书架上一块画板：颜色、封面字、做完几件、他改过几处、前几块积木的样子。 */
class BoardInfo(val id: String, val title: String, val glyph: String, val acc: Color, val done: Int, val of: Int, val edits: Int, val updated: Long, val shape: List<String>)

private val TONES = mapOf("apricot" to 0xFFE9B48E, "sea" to 0xFF8FB8DC, "moss" to 0xFFA8C49A, "rose" to 0xFFDBA3AB, "dusk" to 0xFFB7A8DE, "sand" to 0xFFD8C49A, "mist" to 0xFFA9C4C8)
private val Base = Color(0xFF1B1D24)

object Boards {
    fun info(w: Work): BoardInfo? {
        val o = Works.source(w.id) ?: return null
        val b = o.optJSONObject("board") ?: return null
        val tone = b.optString("tone", "apricot")
        val acc = if (Regex("^#[0-9a-fA-F]{6}$").matches(tone)) Color(android.graphics.Color.parseColor(tone)) else Color(TONES[tone] ?: TONES.getValue("apricot"))
        val blocks = b.optJSONArray("blocks") ?: JSONArray()
        var glyph = ""; var done = 0; var of = 0
        fun walk(a: JSONArray?) {
            if (a == null) return
            for (i in 0 until a.length()) {
                val x = a.optJSONObject(i) ?: continue
                when (x.optString("type")) {
                    "cover" -> if (glyph.isEmpty()) glyph = x.optString("glyph").trim().take(1)
                    "todo" -> x.optJSONArray("items")?.let { it2 -> for (j in 0 until it2.length()) { of++; if (it2.optJSONObject(j)?.optBoolean("done") == true) done++ } }
                    "kanban" -> x.optJSONArray("cols")?.let { cols ->
                        for (j in 0 until cols.length()) { val n = cols.optJSONObject(j)?.optJSONArray("cards")?.length() ?: 0; of += n; if (j == cols.length() - 1) done += n }
                    }
                }
                walk(x.optJSONArray("children"))
                x.optJSONArray("cols")?.let { c -> for (j in 0 until c.length()) walk(c.optJSONArray(j)) }
            }
        }
        walk(blocks)
        val shape = (0 until blocks.length()).mapNotNull { blocks.optJSONObject(it)?.optString("type") }.filter { it != "cover" }.take(5)
        return BoardInfo(w.id, w.title, glyph.ifEmpty { w.title.take(1).ifEmpty { "画" } }, acc, done, of, o.optJSONArray("edits")?.length() ?: 0, w.updated, shape)
    }

    fun list(): List<BoardInfo> = Works.list().filter { it.kind == "board" }.mapNotNull { info(it) }
}

private fun day(t: Long): String {
    val today = SimpleDateFormat("yyyyMMdd", Locale.CHINA)
    val d = today.format(Date(t)); val n = today.format(Date())
    val y = today.format(Date(System.currentTimeMillis() - 86_400_000))
    return when (d) { n -> "今天"; y -> "昨天"; else -> SimpleDateFormat("M月d日", Locale.CHINA).format(Date(t)) }
}

/**
 * 侧栏「画板」：只放她搭的画板（计划、安排、日程、清单），两列封面，新的在前。
 * 从下面升上来、点空白落回去（和「作品」一样）；上面筛选：全部 / 没做完 / 你改过；最后一格「让她搭一块」。
 */
@Composable
fun BoardShelf(open: Boolean, onClose: () -> Unit, onOpen: (String) -> Unit, onAsk: () -> Unit) {
    val t by animateFloatAsState(if (open) 1f else 0f, tween(if (open) 320 else 220, easing = if (open) Curves.standard else Curves.accel), label = "boards")
    if (t <= 0f) return
    val all = remember(open, Works.version) { Boards.list() }
    var f by remember { mutableStateOf(0) }
    val show = all.filter { b -> when (f) { 1 -> b.of > 0 && b.done < b.of; 2 -> b.edits > 0; else -> true } }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        val h = maxHeight
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = t }.background(rgba(6, 9, 14, .5f)).pointerInput(Unit) { detectTapGestures { onClose() } })
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth().height(h * .82f)
                .graphicsLayer { translationY = (1f - t) * h.toPx() * .5f; alpha = t }
                .background(rgba(20, 24, 31, .97f), RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(start = 18.dp, end = 18.dp, top = 10.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 14.dp).width(40.dp).height(4.dp).background(rgba(255, 255, 255, .25f), RoundedCornerShape(2.dp)))
            Box(Modifier.fillMaxWidth()) {
                Pressable(onClose, Modifier.align(Alignment.CenterStart), label = "关闭") { _ ->
                    SvgIcon(Icons.close, Page.ink, 20.dp, modifier = Modifier.padding(6.dp))
                }
                Txt("画板", Page.ink, 16.5.sp, Modifier.align(Alignment.Center), family = Serif, weight = FontWeight.SemiBold, maxLines = 1)
            }
            if (all.isNotEmpty()) Row(Modifier.padding(top = 12.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("全部", "没做完", "你改过").forEachIndexed { i, s -> ChoiceChip(s, f == i) { f = i } }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 10.dp, bottom = 30.dp)) {
                if (all.isEmpty()) Txt("还没有画板。\n计划、安排、日程、清单，让她搭一块：读书会、搬家、这周吃什么都行。", Page.muted, 13.sp, Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 10.dp), lineHeight = 22.sp, align = androidx.compose.ui.text.style.TextAlign.Center)
                val cells: List<BoardInfo?> = show + listOf(null)
                cells.chunked(2).forEachIndexed { r, pair ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEachIndexed { c, b ->
                            Box(Modifier.weight(1f)) {
                                androidx.compose.runtime.key(b?.id ?: "ask") {
                                    if (b != null) BoardCell(b, r * 2 + c) { onOpen(b.id) } else AskCell(r * 2 + c, onAsk)
                                }
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** 一格一格依次浮上来：每格晚 45ms。 */
@Composable
private fun riseIn(i: Int): Animatable<Float, AnimationVector1D> {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(45L * i.coerceAtMost(8)); a.animateTo(1f, tween(380, easing = Curves.decel)) }
    return a
}

@Composable
private fun BoardCell(b: BoardInfo, i: Int, onClick: () -> Unit) {
    val k = riseIn(i)
    Column(Modifier.graphicsLayer { alpha = k.value; translationY = (1f - k.value) * 10.dp.toPx() }) {
        PressSurface(onClick, Modifier.fillMaxWidth().aspectRatio(3f / 4f), shape = RoundedCornerShape(16.dp), fill = lerp(Base, b.acc, .1f), pressedFill = lerp(Base, b.acc, .16f), ripple = b.acc, pressScale = .97f, label = "打开画板 ${b.title}", edge = rgba(255, 255, 255, .06f)) {
            Column(Modifier.fillMaxSize().padding(horizontal = 11.dp, vertical = 12.dp)) {
                Txt(b.glyph, b.acc, 30.sp, family = Serif, maxLines = 1)
                Spacer(Modifier.height(6.dp))
                Txt(b.title, Page.ink, 12.sp, family = Serif, maxLines = 1)
                Spacer(Modifier.height(8.dp))
                MiniBlocks(b)
            }
            if (b.edits > 0) Txt("你改过", css("#e9b48e"), 10.sp, Modifier.align(Alignment.TopEnd).padding(9.dp).background(rgba(0, 0, 0, .35f), RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 2.dp), maxLines = 1)
            if (b.of > 0) Box(Modifier.align(Alignment.BottomCenter).padding(11.dp).fillMaxWidth().height(3.dp).background(rgba(255, 255, 255, .1f), RoundedCornerShape(2.dp))) {
                Box(Modifier.fillMaxWidth(b.done.toFloat() / b.of).fillMaxHeight().background(b.acc, RoundedCornerShape(2.dp)))
            }
        }
        Spacer(Modifier.height(8.dp))
        Txt(b.title, Page.ink, 13.5.sp, maxLines = 1)
        Txt(day(b.updated) + if (b.of > 0) " · ${b.done}/${b.of}" else "", Page.muted.copy(alpha = .6f), 11.sp, Modifier.padding(top = 2.dp), maxLines = 1)
    }
}

/** 缩小的积木轮廓：标题一道短杏色、段落一道长灰线、待办是方框加线、看板 / 分栏 / 卡片墙是三小块。 */
@Composable
private fun MiniBlocks(b: BoardInfo) {
    val line = rgba(255, 255, 255, .1f)
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        b.shape.forEach { s ->
            when (s) {
                "heading" -> Box(Modifier.fillMaxWidth(.45f).height(5.dp).background(b.acc.copy(alpha = .7f), RoundedCornerShape(3.dp)))
                "todo" -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).border(1.dp, b.acc, RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(4.dp))
                    Box(Modifier.fillMaxWidth(.7f).height(5.dp).background(line, RoundedCornerShape(3.dp)))
                }
                "kanban", "columns", "gallery", "week", "table" -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(3) { Box(Modifier.weight(1f).height(20.dp).background(rgba(255, 255, 255, .07f), RoundedCornerShape(5.dp))) }
                }
                "progress" -> Box(Modifier.fillMaxWidth().height(4.dp).background(b.acc.copy(alpha = .4f), RoundedCornerShape(2.dp)))
                "divider" -> Box(Modifier.fillMaxWidth().height(1.dp).background(line))
                else -> Box(Modifier.fillMaxWidth(.8f).height(5.dp).background(line, RoundedCornerShape(3.dp)))
            }
        }
    }
}

@Composable
private fun AskCell(i: Int, onAsk: () -> Unit) {
    val k = riseIn(i)
    PressSurface(onAsk, Modifier.graphicsLayer { alpha = k.value; translationY = (1f - k.value) * 10.dp.toPx() }.fillMaxWidth().aspectRatio(3f / 4f), shape = RoundedCornerShape(16.dp), fill = Color.Transparent, pressScale = .97f, label = "让她搭一块", edge = rgba(255, 255, 255, .14f)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            SvgIcon(SideIcons.plus, css("#e9b48e"), 22.dp, stroke = 1.6f)
            Spacer(Modifier.height(8.dp))
            Txt("让她搭一块", Page.muted, 12.5.sp, maxLines = 1)
        }
    }
}
