package com.fuguang.lueying

import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val SidePanel get() = if (Skin.frost) rgba(18, 21, 27, .72f) else Skin.panel
internal val SideWarm = Color(0xFFE9B48E)
internal val SideFaint get() = Skin.ink.copy(alpha = .34f)
internal val SideInk get() = Skin.ink.copy(alpha = .86f)

/** 侧栏里用到的小图标。 */
internal object SideIcons {
    val search = Ic("M20 20l-4.2-4.2", circles = listOf(floatArrayOf(11f, 11f, 6.5f)))
    val chat = Ic("M5 5.5h14v10H10l-4 3v-3H5Z")
    val work = Ic("M12.5 12.5h7.5v7.5h-7.5Z", circles = listOf(floatArrayOf(9f, 9f, 5f)))
    val pen = Ic("M13.5 6.5l4 4M5 19l1-4.5L15.5 5a1.5 1.5 0 0 1 2 0l1.5 1.5a1.5 1.5 0 0 1 0 2L9.5 18z")
    val swap = Ic("M3.5 19c.8-3.2 3-5 5.5-5s4.7 1.8 5.5 5M16 6.5h5M19 4l2 2.5-2 2.5M21 15h-5M18 12.5l-2 2.5 2 2.5", circles = listOf(floatArrayOf(9f, 8.5f, 3.2f)))
    val next = Ic("M9.5 6l6 6-6 6")
    val back = Ic("M14.5 6l-6 6 6 6")
    val plus = Ic("M12 5v14M5 12h14")
    val board = Ic("M4 4h16v6H4ZM4 13h7v7H4ZM14 13h6v7h-6Z")
}

/** 这段对话的图标：置顶是四角星，做过作品是作品图标，其余是对话气泡。 */
internal fun convIcon(c: Conv): Ic = when {
    c.pinned -> Icons.spark
    c.msgs.any { it.works.isNotEmpty() } -> SideIcons.work
    else -> SideIcons.chat
}

/**
 * 聊天页左侧栏（照 Claude App）：现在这位助理的名字（点开切换）+ 搜索 → 作品 / 画板 / 手记 / 下载 → 已置顶 → 她的最近对话 → 查看全部；
 * 底下左边是你的头像（去「我的」），右边「新对话」。长按一条对话：置顶 / 删除。
 */
@Composable
fun HistoryMenu(
    history: ConvStore,
    current: String?,
    busy: Boolean,
    onOpen: (Conv) -> Unit,
    onNew: () -> Unit,
    onWorks: () -> Unit,
    onBoards: () -> Unit,
    onJournal: () -> Unit,
    onMine: () -> Unit,
    onAssistants: () -> Unit,
) {
    var all by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = all) { all = false }
    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            all,
            transitionSpec = {
                if (targetState) (slideInHorizontally(tween(340, easing = Curves.standard)) { it } + fadeIn(tween(240))) togetherWith
                    (slideOutHorizontally(tween(340, easing = Curves.standard)) { -it / 4 } + fadeOut(tween(200)))
                else (slideInHorizontally(tween(340, easing = Curves.standard)) { -it / 4 } + fadeIn(tween(240))) togetherWith
                    (slideOutHorizontally(tween(340, easing = Curves.standard)) { it } + fadeOut(tween(200)))
            },
            label = "side",
        ) { showAll ->
            if (showAll) AllConvs(history, current, busy, onBack = { all = false }, onOpen = onOpen)
            else SideMain(history, current, busy, onSearch = { all = true }, onOpen, onNew, onWorks, onBoards, onJournal, onAssistants)
        }
        SideFoot(Modifier.align(Alignment.BottomCenter), onMine, onNew)
    }
}

@Composable
private fun SideMain(
    history: ConvStore, current: String?, busy: Boolean, onSearch: () -> Unit, onOpen: (Conv) -> Unit, onNew: () -> Unit,
    onWorks: () -> Unit, onBoards: () -> Unit, onJournal: () -> Unit, onAssistants: () -> Unit,
) {
    var editing by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(top = 22.dp)) {
        AssistantTitle(history, onSearch, onAssistants)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 10.dp, end = 10.dp, bottom = 96.dp)) {
            val all = remember(Works.version) { Works.list() }
            val works = all.count { it.kind != "board" }
            val boards = remember(Works.version) { Boards.list() }
            SideRow(SideIcons.work, "作品", onWorks) { if (works > 0) Txt(works.toString(), SideFaint, 11.5.sp) }
            // 画板：计划、安排、日程。你改过、她还没读的，数字前亮一个杏色小点
            SideRow(SideIcons.board, "画板", onBoards) {
                if (boards.any { it.edits > 0 }) { Box(Modifier.size(6.dp).background(css("#e9b48e"), androidx.compose.foundation.shape.CircleShape)); Spacer(Modifier.width(8.dp)) }
                if (boards.isNotEmpty()) Txt(boards.size.toString(), SideFaint, 11.5.sp)
            }
            SideRow(SideIcons.pen, "手记", onJournal, modifier = Modifier.onGloballyPositioned { NoteCard.from = it.boundsInRoot().center })
            // 下载：右边有在下的进度圈 / 没看过的杏色小点；点了从右边滑进下载页（侧栏留着，返回回到这里）
            SideRow(Ic("M12 4v11M7.5 10.5L12 15l4.5-4.5M5 19h14"), "下载", { Downloads.open = true }) { DlSideTrail() }
            // 一道不碰边的细线
            Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth().height(1.dp).background(veil(.07f)))
            // 换了助理：她的对话列表交叉换上来（旧的淡出上移，新的淡入从下浮起）
            AnimatedContent(
                Memory.activeId,
                transitionSpec = { (fadeIn(tween(260, 90)) + androidx.compose.animation.slideInVertically(tween(320, 90, Curves.standard)) { it / 24 }) togetherWith fadeOut(tween(140)) },
                label = "hers",
            ) { who ->
                val hers = history.of(who)
                val pinnedOf = hers.filter { it.pinned }
                val recentOf = hers.filter { !it.pinned }.sortedByDescending { it.updated }.take(7)
                Column {
                    if (hers.isEmpty()) {
                        Txt("还没有和${Memory.get(who)?.name.orEmpty()}的对话。点下面「新对话」开始。", SideFaint, 13.sp, Modifier.padding(horizontal = 12.dp, vertical = 16.dp), lineHeight = 22.sp)
                    }
                    if (pinnedOf.isNotEmpty()) {
                        SideLabel("已置顶")
                        pinnedOf.forEach { c -> ConvLine(c, c.id == current, busy && c.id == current, editing == c.id, history, onOpen, { editing = if (editing == c.id) null else c.id }) { if (c.id == current) onNew() } }
                    }
                    if (recentOf.isNotEmpty()) {
                        SideLabel("最近对话")
                        recentOf.forEach { c -> ConvLine(c, c.id == current, busy && c.id == current, editing == c.id, history, onOpen, { editing = if (editing == c.id) null else c.id }) { if (c.id == current) onNew() } }
                    }
                    if (hers.isNotEmpty()) {
                        Pressable(onSearch, label = "查看全部对话") { p ->
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Txt("查看全部", if (p) Page.ink else Page.muted, 13.5.sp)
                                Spacer(Modifier.width(4.dp))
                                SvgIcon(SideIcons.next, Page.muted, 13.dp, stroke = 1.8f)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SideLabel(text: String) {
    Txt(text, SideFaint, 12.5.sp, Modifier.padding(start = 12.dp, top = 14.dp, bottom = 6.dp), spacing = ls(1f, 12.5f))
}

@Composable
internal fun SideRow(icon: Ic, label: String, onClick: () -> Unit, selected: Boolean = false, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Pressable(onClick, modifier.fillMaxWidth(), label = label) { p ->
        Row(
            Modifier.fillMaxWidth()
                .background(if (selected || p) veil(if (p) .06f else .08f) else Color.Transparent, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SvgIcon(icon, Page.muted, 20.dp, stroke = 1.4f)
            Spacer(Modifier.width(14.dp))
            Txt(label, SideInk, 15.sp, Modifier.weight(1f), maxLines = 1)
            trailing()
        }
    }
}

/** 一条对话：一行字，前面图标；正在看的是淡杏色块，她还在回答的带一颗小橙点。长按出「置顶 / 删除」。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ConvLine(
    c: Conv, on: Boolean, live: Boolean, editing: Boolean, history: ConvStore,
    onOpen: (Conv) -> Unit, onLong: () -> Unit, onDeletedCurrent: () -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth()
                .background(if (on) SideWarm.copy(alpha = .13f) else Color.Transparent, RoundedCornerShape(12.dp))
                .combinedClickable(remember { MutableInteractionSource() }, null, onClick = { onOpen(c) }, onLongClick = onLong)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SvgIcon(convIcon(c), if (on) SideWarm else SideFaint, 17.dp, stroke = 1.4f)
            Spacer(Modifier.width(12.dp))
            Txt(c.title.ifBlank { "新对话" }, if (on) Page.ink else SideInk, 14.5.sp, Modifier.weight(1f), maxLines = 1)
            if (live) Box(Modifier.padding(start = 8.dp).size(6.dp).background(Color(0xFFD97757), CircleShape))
        }
        if (editing) {
            Row(Modifier.padding(start = 40.dp, top = 2.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MenuAction(if (c.pinned) "取消置顶" else "置顶") { history.togglePin(c.id); onLong() }
                MenuAction("删除", danger = true) { history.delete(c.id); onLong(); onDeletedCurrent() }
            }
        }
    }
}

/** 底部：左边你的头像（去「我的」），右边「新对话」；后面一层渐暗托着，列表从它下面滑过。 */
@Composable
private fun SideFoot(modifier: Modifier, onMine: () -> Unit, onNew: () -> Unit) {
    Row(
        modifier.fillMaxWidth()
            .drawBehind { drawRect(Brush.verticalGradient(0f to Color.Transparent, .45f to SidePanel, 1f to SidePanel)) }
            .padding(start = 16.dp, end = 14.dp, top = 30.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val me = Memory.userName.trim().let { if (it.isEmpty()) "你" else it.takeLast(1) }
        Pressable(onMine, Modifier.onGloballyPositioned { MineCard.from = it.boundsInRoot().center }, label = "我的") { p ->
            Box(
                Modifier.size(42.dp)
                    .drawBehind {
                        if (MineCard.open) drawCircle(SideWarm.copy(alpha = .55f), size.minDimension / 2 + 4.5.dp.toPx(), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                    }
                    .graphicsLayer { alpha = if (p) .7f else 1f },
                contentAlignment = Alignment.Center,
            ) { AvatarFace(Avatars.ME, me, Brush.linearGradient(listOf(SideWarm, Color(0xFFB97F62))), 42.dp) }
        }
        Spacer(Modifier.weight(1f))
        Pressable(onNew, label = "新对话") { p ->
            Row(
                Modifier.background(Page.ink.copy(alpha = if (p) .8f else 1f), RoundedCornerShape(50)).padding(horizontal = 18.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SvgIcon(SideIcons.plus, Skin.onInk, 15.dp, stroke = 2f)
                Spacer(Modifier.width(8.dp))
                Txt("新对话", Skin.onInk, 14.5.sp, weight = FontWeight.Medium)
            }
        }
    }
}

@Composable
internal fun MenuAction(text: String, danger: Boolean = false, onClick: () -> Unit) {
    Pressable(onClick, label = text) { pressed ->
        Txt(
            text, if (danger) Color(0xFFD79A94) else Page.ink, 12.sp,
            Modifier.background(veil(if (pressed) .12f else .06f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
