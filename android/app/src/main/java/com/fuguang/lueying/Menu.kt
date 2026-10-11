package com.fuguang.lueying

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Panel = Color(0xFF14171D)
private val Warm = Color(0xFFE9B48E)
private val Sel = Color(0x24E9B48E)
private val Faint = Color(0xFF5F5C59)
private val Dim = Color(0xFF8E8984)

/**
 * 左侧菜单里的对话记录（定稿：4 + 2，滑动用 2 + 3）：
 * 浮光标识 → 置顶小签 → 按日分组的时光轴（日期吸顶、竖线随滑动点亮、上下边缘渐隐）→ 新对话。
 * 长按一条对话：置顶 / 删除。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryMenu(history: ConvStore, current: String?, onOpen: (Conv) -> Unit, onNew: () -> Unit) {
    val days = history.days()
    val pinned = history.all.filter { it.pinned }
    var editing by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()

    // 每个列表项属于第几组（标题也算），用来决定竖线点亮到哪
    val groupOf = remember(days) {
        buildList { days.forEachIndexed { g, (_, cs) -> add(g); repeat(cs.size) { add(g) } } }
    }
    val litGroup by remember(groupOf) { derivedStateOf { groupOf.getOrElse(list.firstVisibleItemIndex) { 0 } } }

    Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 30.dp, bottom = 18.dp)) {
        Row(Modifier.padding(start = 6.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            SvgIcon(Icons.spark, Warm, 20.dp, stroke = 1.1f)
            Spacer(Modifier.width(8.dp))
            Txt("浮光", Page.ink, 20.sp, spacing = ls(6f, 20f), family = Serif)
        }
        if (pinned.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pinned.forEach { c ->
                    Pressable({ onOpen(c) }, label = c.title) { pressed ->
                        Row(
                            Modifier.background(rgba(255, 255, 255, if (pressed) .1f else .055f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Txt("✦", Warm, 11.sp)
                            Spacer(Modifier.width(5.dp))
                            Txt(firstSentence(c.title, 8), Page.ink.copy(alpha = .8f), 12.sp, maxLines = 1)
                        }
                    }
                }
            }
        }

        if (days.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(top = 30.dp), contentAlignment = Alignment.TopCenter) {
                Txt("还没有对话", Dim, 13.sp)
            }
        } else {
            LazyColumn(
                state = list,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // 上下边缘渐隐：能往上滑时上沿淡出，能往下滑时下沿淡出
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        val h = size.height
                        val top = if (list.canScrollBackward) 40.dp.toPx() / h else 0f
                        val bot = if (list.canScrollForward) 52.dp.toPx() / h else 0f
                        drawRect(
                            Brush.verticalGradient(
                                0f to (if (top > 0f) Color.Transparent else Color.Black),
                                top to Color.Black,
                                (1f - bot) to Color.Black,
                                1f to (if (bot > 0f) Color.Transparent else Color.Black),
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            ) {
                days.forEachIndexed { g, (label, convs) ->
                    val lit = g <= litGroup
                    val lastGroup = g == days.lastIndex
                    stickyHeader(key = "h$label") {
                        Row(Modifier.fillMaxWidth().background(Panel).padding(top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
                                if (g == 0 || lit) Box(Modifier.size(15.dp).background(Warm.copy(alpha = if (lit) .16f else 0f), CircleShape))
                                Box(Modifier.size(7.dp).background(if (lit) Warm else Color(0xFF2A2D33), CircleShape))
                            }
                            Spacer(Modifier.width(6.dp))
                            Txt(label, if (lit) Page.ink.copy(alpha = .7f) else Dim, 12.sp, spacing = ls(1f, 12f), family = Serif)
                        }
                    }
                    convs.forEachIndexed { k, c ->
                        val tailOfAll = lastGroup && k == convs.lastIndex
                        item(key = c.id) {
                            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                                // 时光轴竖线：经过的日期变成暖色
                                Box(
                                    Modifier.width(22.dp).fillMaxHeight().drawBehind {
                                        val x = size.width / 2
                                        val end = if (tailOfAll) size.height * .5f else size.height
                                        drawLine(if (lit) Warm.copy(alpha = .55f) else Color.White.copy(alpha = .1f), Offset(x, 0f), Offset(x, end), 1.dp.toPx())
                                    },
                                )
                                Spacer(Modifier.width(6.dp))
                                Column(Modifier.weight(1f).padding(bottom = 4.dp)) {
                                    val on = c.id == current
                                    Column(
                                        Modifier
                                            .fillMaxWidth()
                                            .background(if (on) Sel else Color.Transparent, RoundedCornerShape(12.dp))
                                            .combinedClickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null,
                                                onClick = { editing = null; onOpen(c) },
                                                onLongClick = { editing = if (editing == c.id) null else c.id },
                                            )
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                    ) {
                                        Row(verticalAlignment = Alignment.Top) {
                                            Txt(c.title.ifBlank { "新对话" }, if (on) Page.ink else Page.ink.copy(alpha = .78f), 14.5.sp, Modifier.weight(1f), maxLines = 1)
                                            Spacer(Modifier.width(8.dp))
                                            Txt(if (c.pinned) "✦" else ConvStore.clock(c.updated), if (c.pinned) Warm else Faint, 10.5.sp, Modifier.padding(top = 3.dp))
                                        }
                                        val pv = c.preview
                                        if (pv.isNotEmpty()) {
                                            Spacer(Modifier.height(3.dp))
                                            Txt(pv, if (on) Dim else Faint, 11.5.sp, maxLines = 1)
                                        }
                                    }
                                    if (editing == c.id) {
                                        Row(Modifier.padding(start = 8.dp, top = 4.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            MenuAction(if (c.pinned) "取消置顶" else "置顶") { history.togglePin(c.id); editing = null }
                                            MenuAction("删除", danger = true) {
                                                history.delete(c.id); editing = null
                                                if (c.id == current) onNew()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (!lastGroup) item(key = "gap$label") {
                        Box(Modifier.fillMaxWidth().height(10.dp)) {
                            Box(Modifier.width(22.dp).fillMaxHeight().drawBehind {
                                drawLine(if (g < litGroup) Warm.copy(alpha = .55f) else Color.White.copy(alpha = .1f), Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 1.dp.toPx())
                            })
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
            Pressable(onNew, label = "新对话") { pressed ->
                Row(
                    Modifier.background(rgba(255, 255, 255, if (pressed) .1f else .055f), RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SvgIcon(Ic("M12 5v14M5 12h14"), Page.ink, 15.dp)
                    Spacer(Modifier.width(6.dp))
                    Txt("新对话", Page.ink, 13.sp, weight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun MenuAction(text: String, danger: Boolean = false, onClick: () -> Unit) {
    Pressable(onClick, label = text) { pressed ->
        Txt(
            text, if (danger) Color(0xFFD79A94) else Page.ink, 12.sp,
            Modifier.background(rgba(255, 255, 255, if (pressed) .12f else .06f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
