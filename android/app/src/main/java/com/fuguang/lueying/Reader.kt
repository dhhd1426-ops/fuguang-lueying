package com.fuguang.lueying

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce

/** 阅读状态放在弹层外面，关掉再打开时停在原处（网站的 dialog 不销毁内容）。 */
@Stable
class ReaderState(private val prefs: Prefs, initial: Luoying) {
    var data by mutableStateOf(initial)
    var view by mutableStateOf("all")
    var query by mutableStateOf("")
    var current by mutableStateOf<String?>(null)
    var favorites by mutableStateOf(prefs.favorites)
    var size by mutableIntStateOf(prefs.fontSize)
    var expanded by mutableStateOf(false)
    var showDirectory by mutableStateOf(false)
    var restore by mutableStateOf(false)
    var loaded by mutableStateOf(false)
    var selectSerial by mutableIntStateOf(0)
    val scroll = ScrollState(0)
    val list = LazyListState()

    fun filtered(): List<Chapter> = data.chapters.filter { c ->
        (view == "all" || (view == "favorites" && c.id in favorites) || c.group == view) &&
            (query.isEmpty() || (c.title + " " + c.text).lowercase().contains(query.lowercase()))
    }

    fun open() {
        if (loaded) return
        loaded = true
        val last = prefs.lastChapter
        select(if (data.chapters.any { it.id == last }) last!! else data.chapters.first().id, restore = true)
    }

    fun select(id: String, restore: Boolean = false) {
        if (data.chapters.none { it.id == id }) return
        current?.let { prefs.setProgress(it, scroll.value) }
        current = id
        this.restore = restore
        showDirectory = false
        prefs.lastChapter = id
        selectSerial++
    }

    fun chooseView(v: String) {
        view = v
        if (v == "original") {
            current?.let { prefs.setProgress(it, scroll.value) }
            showDirectory = false
            restore = false
            selectSerial++
        } else {
            val list = filtered()
            if (list.isNotEmpty() && list.none { it.id == current }) select(list.first().id)
            else if (current != null) { restore = true; selectSerial++ }
        }
    }

    fun move(step: Int) {
        val list = filtered()
        val i = list.indexOfFirst { it.id == current }
        list.getOrNull(i + step)?.let { select(it.id) }
    }

    fun toggleFavorite() {
        val id = current ?: return
        favorites = if (id in favorites) favorites - id else favorites + id
        prefs.favorites = favorites
    }

    fun changeSize(delta: Int) {
        size = (size + delta).coerceIn(14, 22)
        prefs.fontSize = size
    }

    fun saveProgress() { current?.let { if (view != "original") prefs.setProgress(it, scroll.value) } }
    fun savedProgress(id: String) = prefs.progress(id)
}

@OptIn(FlowPreview::class)
@Composable
fun Reader(state: ReaderState, w: Dp, h: Dp, onClose: () -> Unit) {
    val p = LocalPalette.current
    val narrow = w < 720.dp
    LaunchedEffect(Unit) { state.open() }

    // 选中章节后回到顶部，或恢复上次读到的位置
    LaunchedEffect(state.selectSerial) {
        withFrameNanos { }
        withFrameNanos { }
        val target = if (state.restore && state.view != "original") state.current?.let { state.savedProgress(it) } ?: 0 else 0
        state.scroll.scrollTo(target)
    }
    // 滚动停下 200ms 后记下进度
    LaunchedEffect(state) {
        snapshotFlow { state.scroll.value }.debounce(200).collect { state.saveProgress() }
    }

    val shape = when {
        state.expanded -> RoundedCornerShape(0.dp)
        narrow -> RoundedCornerShape(15.dp)
        else -> RoundedCornerShape(20.dp)
    }
    val boxW = when {
        state.expanded -> w
        narrow -> w - 20.dp
        else -> min(1120.dp, w - 36.dp)
    }
    val boxH = when {
        state.expanded -> h
        narrow -> h * .92f
        else -> min(820.dp, h * .86f)
    }

    Column(
        Modifier
            .width(boxW)
            .height(boxH)
            .clip(shape)
            .background(p.readerBg)
            .then(if (state.expanded) Modifier else Modifier.border(1.dp, p.readerBorder, shape))
            .swallowTaps()
            .then(if (state.expanded) Modifier.windowInsetsPadding(WindowInsets.systemBars) else Modifier),
    ) {
        // 顶栏
        Row(
            Modifier
                .fillMaxWidth()
                .bottomLine(p.readerLine)
                .padding(horizontal = if (narrow) 18.dp else 26.dp, vertical = if (narrow) 12.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                val bs = if (narrow) 18f else 21f
                Txt("掠影", p.readerInk, bs.sp, spacing = ls(4f, bs))
                if (!narrow) {
                    Spacer(Modifier.width(13.dp))
                    Txt("文字里的旧日光影", p.brandSmall, 9.sp, Modifier.padding(bottom = 4.dp), spacing = ls(2f, 9f))
                }
            }
            if (narrow) {
                TextButton(if (state.showDirectory) "正文" else "目录", p.menuInk, 11f, label = "目录") {
                    state.showDirectory = !state.showDirectory
                }
                Spacer(Modifier.width(10.dp))
            }
            TextButton(if (state.expanded) "还原 ↙" else "全屏 ↗", p.fullInk, if (narrow) 10f else 11f, label = if (state.expanded) "退出全屏阅读" else "全屏阅读") {
                state.expanded = !state.expanded
            }
            Spacer(Modifier.width(if (narrow) 4.dp else 8.dp))
            CloseButton(p.readerInk, "关闭阅读") {
                state.saveProgress()
                onClose()
            }
        }

        Row(Modifier.weight(1f).fillMaxWidth()) {
            val sideVisible = !narrow || state.showDirectory
            val mainVisible = !narrow || !state.showDirectory
            if (sideVisible) {
                Sidebar(
                    state,
                    narrow,
                    if (narrow) Modifier.fillMaxSize() else Modifier.width(280.dp).fillMaxHeight().rightLine(p.readerLine),
                )
            }
            if (mainVisible) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        SelectionContainer {
                            Column(
                                Modifier
                                    .fillMaxSize()
                                    .verticalScroll(state.scroll)
                                    .padding(
                                        start = if (narrow) 22.dp else 42.dp,
                                        end = if (narrow) 22.dp else 42.dp,
                                        top = if (narrow) 25.dp else 34.dp,
                                        bottom = if (narrow) 35.dp else 42.dp,
                                    ),
                            ) {
                                Article(state, narrow)
                            }
                        }
                    }
                    Controls(state, narrow)
                }
            }
        }
    }
}

@Composable
private fun Article(state: ReaderState, narrow: Boolean) {
    val p = LocalPalette.current
    val original = state.view == "original"
    val chapter = state.data.chapters.firstOrNull { it.id == state.current }
    val meta: String
    val title: String
    val notice: String
    if (original) {
        meta = "CLAUDE SHARE / ${state.data.messages.size} 条"
        title = "原始对话"
        notice = "按原始顺序保留与剧情、散文相关的提问与回复，已略去无关对话。"
    } else if (chapter != null) {
        meta = "${chapter.group} / ${"%,d".format(chapter.characters)} 字"
        title = chapter.title
        notice = ""
    } else {
        meta = "LOADING"; title = "正在打开掠影…"; notice = ""
    }
    Txt(meta, p.meta, 10.sp, spacing = ls(2f, 10f))
    Spacer(Modifier.height(16.dp))
    val ts = if (narrow) 24f else 28f
    Txt(title, p.readerInk, ts.sp, spacing = ls(2f, ts), lineHeight = (ts * 1.55f).sp, family = Serif)
    Spacer(Modifier.height(22.dp))
    if (notice.isNotEmpty()) {
        Txt(notice, p.notice, 10.sp, lineHeight = 19.sp)
        Spacer(Modifier.height(24.dp))
    }
    if (original) {
        state.data.messages.forEachIndexed { i, m ->
            Column(Modifier.fillMaxWidth().bottomLine(p.readerLine).padding(vertical = 18.dp)) {
                Txt(
                    "%02d · %s".format(i + 1, if (m.role == "user") "你的提问" else "Claude 回复"),
                    p.role, 10.sp, spacing = ls(2f, 10f),
                )
                Spacer(Modifier.height(12.dp))
                Paragraphs(m.text, state.size, narrow)
            }
        }
    } else if (chapter != null) {
        Paragraphs(chapter.text, state.size, narrow)
    }
}

/** 按空行分段；以【开头的是场景时间戳：和正文同字体同字号，只是颜色变淡、不加框。**粗体**照网站显示。 */
@Composable
private fun Paragraphs(text: String, size: Int, narrow: Boolean) {
    val p = LocalPalette.current
    val parts = remember(text) { text.split(Regex("\\n\\s*\\n")) }
    parts.forEach { para ->
        val stamp = para.startsWith("【")
        BasicText(
            text = remember(para, p.strong, stamp) {
                if (stamp) AnnotatedString(para) else bold(para, p.strong)
            },
            style = TextStyle(
                color = if (stamp) p.stamp else p.text,
                fontSize = size.sp,
                lineHeight = (size * if (narrow) 2.05f else 2.15f).sp,
                letterSpacing = .02.em,
                fontFamily = Serif,
            ),
        )
        Spacer(Modifier.height((size * 1.1f).dp))
    }
}

private fun bold(s: String, strong: Color): AnnotatedString = buildAnnotatedString {
    val re = Regex("\\*\\*(.+?)\\*\\*")
    var last = 0
    re.findAll(s).forEach { m ->
        append(s.substring(last, m.range.first))
        withStyle(SpanStyle(color = strong, fontWeight = FontWeight.Medium)) { append(m.groupValues[1]) }
        last = m.range.last + 1
    }
    append(s.substring(last))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Sidebar(state: ReaderState, narrow: Boolean, modifier: Modifier) {
    val p = LocalPalette.current
    Column(modifier.padding(start = 14.dp, end = 14.dp, top = if (narrow) 16.dp else 20.dp, bottom = 10.dp)) {
        val shape = RoundedCornerShape(9.dp)
        BasicTextField(
            value = state.query,
            onValueChange = { state.query = it },
            singleLine = true,
            textStyle = TextStyle(color = p.searchInk, fontSize = 12.sp),
            cursorBrush = SolidColor(p.favorite),
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(p.searchBg)
                .border(1.dp, p.searchBorder, shape)
                .padding(horizontal = 12.dp, vertical = 11.dp),
            decorationBox = { inner ->
                Box {
                    if (state.query.isEmpty()) Txt("搜索标题或正文", p.placeholder, 12.sp)
                    inner()
                }
            },
        )
        Spacer(Modifier.height(14.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf("all" to "全部", "写作方法" to "写作方法", "场景练习" to "场景练习", "连续故事" to "连续故事", "favorites" to "收藏", "original" to "原始对话")
                .forEach { (key, label) ->
                    val sel = state.view == key
                    Pressable({ state.chooseView(key) }, label = label) { pressed ->
                        Txt(
                            label,
                            if (sel) p.tabSelInk else if (pressed) p.readerInk else p.tabInk,
                            (if (narrow) 11 else 10).sp,
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (sel) p.tabSelBg else Color.Transparent)
                                .padding(horizontal = 8.dp, vertical = 7.dp),
                        )
                    }
                }
        }
        Spacer(Modifier.height(14.dp))
        if (state.view == "original") {
            Txt("按原始顺序保留 ${state.data.messages.size} 条与故事、散文相关的对话，可在右侧阅读。", p.tabInk, 12.sp, Modifier.padding(horizontal = 10.dp, vertical = 25.dp), lineHeight = 20.sp)
            return@Column
        }
        val items = state.filtered()
        if (items.isEmpty()) {
            Txt(if (state.query.isNotEmpty()) "没有找到匹配的内容。" else "暂无收藏，阅读时点击「收藏」。", p.tabInk, 12.sp, Modifier.padding(horizontal = 10.dp, vertical = 25.dp))
            return@Column
        }
        LazyColumn(Modifier.weight(1f), state = state.list) {
            items(items, key = { it.id }) { c ->
                val active = c.id == state.current
                Pressable({ state.select(c.id) }, Modifier.fillMaxWidth().padding(vertical = 2.dp), label = c.title) { pressed ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (active) p.itemActiveBg else if (pressed) p.itemPressBg else Color.Transparent)
                            .then(if (active) Modifier.leftBar(p.itemActiveBar) else Modifier)
                            .padding(horizontal = 10.dp, vertical = 11.dp),
                    ) {
                        val no = state.data.chapters.indexOf(c) + 1
                        Txt("${c.group} · ${"%02d".format(no)}", p.itemSmall, 8.sp, spacing = ls(1f, 8f))
                        Spacer(Modifier.height(6.dp))
                        val fs = if (narrow) 14f else 12f
                        Txt(c.title, p.itemStrong, fs.sp, lineHeight = (fs * 1.6f).sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun Controls(state: ReaderState, narrow: Boolean) {
    val p = LocalPalette.current
    val original = state.view == "original"
    val list = state.filtered()
    val i = list.indexOfFirst { it.id == state.current }
    val max = state.scroll.maxValue
    val pct = if (max in 1 until Int.MAX_VALUE) (state.scroll.value * 100f / max).toInt().coerceIn(0, 100) else 0
    Row(
        Modifier
            .fillMaxWidth()
            .topLine(p.readerLine)
            .padding(horizontal = if (narrow) 12.dp else 26.dp, vertical = if (narrow) 6.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (narrow) 8.dp else 16.dp),
    ) {
        TextButton("← 上一篇", p.controls, 10f, enabled = !original && i > 0, label = "上一篇") { state.move(-1) }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(if (narrow) 10.dp else 6.dp)) {
            TextButton("A−", p.controls, 12f, label = "缩小字号") { state.changeSize(-1) }
            TextButton("A＋", p.controls, 12f, label = "增大字号") { state.changeSize(1) }
        }
        if (!original) {
            val fav = state.current in state.favorites
            TextButton(if (fav) "已收藏" else "收藏", p.favorite, 11f, label = "收藏") { state.toggleFavorite() }
        }
        Txt("$pct%", p.controls, 10.sp)
        if (!narrow) {
            Box(Modifier.widthIn(max = 160.dp).weight(1f).height(2.dp).background(p.meterBg)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(pct / 100f).background(p.meterFill))
            }
        }
        Spacer(Modifier.weight(1f))
        TextButton("下一篇 →", p.controls, 10f, enabled = !original && i >= 0 && i < list.size - 1, label = "下一篇") { state.move(1) }
    }
}

@Composable
fun TextButton(text: String, color: Color, size: Float, enabled: Boolean = true, label: String? = null, onClick: () -> Unit) {
    Pressable(onClick, Modifier.height(44.dp), enabled = enabled, label = label) { pressed ->
        Txt(text, if (pressed) color.copy(alpha = .6f) else color, size.sp, Modifier.padding(horizontal = 6.dp), maxLines = 1)
    }
}

fun Modifier.bottomLine(c: Color): Modifier = drawBehind {
    drawLine(c, Offset(0f, size.height - .5f), Offset(size.width, size.height - .5f), 1.dp.toPx())
}

fun Modifier.topLine(c: Color): Modifier = drawBehind {
    drawLine(c, Offset(0f, .5f), Offset(size.width, .5f), 1.dp.toPx())
}

fun Modifier.rightLine(c: Color): Modifier = drawBehind {
    drawLine(c, Offset(size.width - .5f, 0f), Offset(size.width - .5f, size.height), 1.dp.toPx())
}

fun Modifier.leftBar(c: Color): Modifier = drawBehind {
    drawRect(c, size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height))
}
