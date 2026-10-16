package com.fuguang.lueying

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 手记：和「我的」同一种悬浮卡片。首页写此刻手记，下面「翻一翻」浮光 / 掠影 / 绯句；
 * 掠影是目录 → 正文（或原始对话），绯句是留言列表 → 一封，都在卡片里推进。
 */
object NoteCard : FloatCard() {
    var screen by mutableStateOf("note")
    var chapter by mutableStateOf<String?>(null)
    var letter by mutableIntStateOf(0)
    var group by mutableStateOf("all")
    var query by mutableStateOf("")

    fun back(): Boolean {
        screen = when (screen) {
            "scene", "toc", "letters" -> "note"
            "read", "talks" -> "toc"
            "letter" -> "letters"
            else -> return false
        }
        return true
    }
}

private fun depth(s: String) = when (s) {
    "note" -> 0
    "scene", "toc", "letters" -> 1
    else -> 2
}

internal val NoteWarm = Color(0xFFE9B48E)
internal val NoteEyebrow = Color(0xFFC9A385)
private val chevron = Ic("M9.5 6l6 6-6 6")
private val backIc = Ic("M14.5 6l-6 6 6 6")
private val pen = Ic("M13.5 6.5l4 4M5 19l1-4.5L15.5 5a1.5 1.5 0 0 1 2 0l1.5 1.5a1.5 1.5 0 0 1 0 2L9.5 18z")

@Composable
fun NoteCardLayer(store: ContentStore, prefs: Prefs) {
    val reading = remember { NoteReading(prefs) }
    CenterCardLayer(
        NoteCard, NoteCard.screen, ::depth,
        onBack = { NoteCard.back() },
        onClosed = { NoteCard.screen = "note"; NoteCard.query = "" },
    ) { screen, rise ->
        when (screen) {
            "note" -> NoteHome(store, prefs, rise)
            "scene" -> ScenePage()
            "toc" -> TocPage(store.luoying, reading)
            "read" -> ReadPage(store.luoying, reading)
            "talks" -> TalksPage(store.luoying)
            "letters" -> LettersPage(store.letters)
            "letter" -> LetterPage(store.letters)
        }
    }
}

/** 卡片里二级页的页头：‹ 标题，下面一行小字；右边让出 ↗ 的位置。 */
@Composable
internal fun CardPageHead(title: String, sub: String = "", onBack: () -> Unit = { NoteCard.back() }) {
    Row(Modifier.fillMaxWidth().padding(end = 40.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        PressSurface(onBack, Modifier.size(40.dp), shape = CircleShape, fill = Color.Transparent, pressScale = .9f, label = "返回") {
            SvgIcon(backIc, Page.ink, 20.dp, stroke = 1.6f, modifier = Modifier.align(Alignment.Center))
        }
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Txt(title, Page.ink, 19.sp, spacing = ls(2.6f, 19f), family = Serif, maxLines = 1)
            if (sub.isNotEmpty()) Txt(sub, Page.muted.copy(alpha = .6f), 10.5.sp, Modifier.padding(top = 2.dp), spacing = ls(2.5f, 10.5f), maxLines = 1)
        }
    }
}

@Composable
private fun NoteHome(store: ContentStore, prefs: Prefs, t: Float) {
    Column(Modifier.widthIn(max = 600.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 40.dp, bottom = 14.dp).mineRise(0, t), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).background(NoteWarm.copy(alpha = .14f), CircleShape), contentAlignment = Alignment.Center) {
                SvgIcon(pen, NoteWarm, 17.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Txt("手记", Page.ink, 19.sp, spacing = ls(2.6f, 19f), family = Serif)
                Spacer(Modifier.height(2.dp))
                Txt(SimpleDateFormat("M月d日 EEE", Locale.CHINA).format(Date()), Page.muted.copy(alpha = .62f), 11.5.sp, maxLines = 1)
            }
        }
        NoteField(prefs, Modifier.mineRise(1, t))
        Txt("翻一翻", Page.muted, 11.sp, Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp).mineRise(2, t), spacing = ls(2.75f, 11f))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            NoteRow(3, t, "浮", "浮光", "背景场景 · WLOP-Sky2-海琴烟", false) { NoteCard.screen = "scene" }
            val chapters = store.luoying.chapters
            val last = chapters.firstOrNull { it.id == prefs.lastChapter }
            NoteRow(4, t, "掠", "掠影", "${chapters.size} 篇" + (last?.let { " · 上次读到「${it.title}」" } ?: ""), store.newLuoying) {
                store.markLuoyingSeen(); NoteCard.screen = "toc"
            }
            val letters = store.letters
            NoteRow(5, t, "绯", "绯句", "${letters.size} 封留言" + (letters.lastOrNull()?.let { " · 最近一封来自${it.author.removePrefix("——")}" } ?: ""), store.newLetters) {
                store.markLettersSeen(); NoteCard.screen = "letters"
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun NoteRow(i: Int, t: Float, glyph: String, title: String, sub: String, dot: Boolean, onClick: () -> Unit) {
    MineRow(i, t, title, sub, onClick, glyph = { Txt(glyph, NoteWarm, 17.sp, family = Serif) }) {
        if (dot) {
            Box(Modifier.size(6.dp).background(NoteWarm, CircleShape))
            Spacer(Modifier.width(8.dp))
        }
        SvgIcon(chevron, Page.muted.copy(alpha = .6f), 15.dp, stroke = 1.8f)
    }
}

/** 此刻手记：停手 0.6 秒自动存在本机。 */
@Composable
private fun NoteField(prefs: Prefs, modifier: Modifier) {
    var text by remember { mutableStateOf(prefs.note) }
    var saved by remember { mutableStateOf(false) }
    LaunchedEffect(text) {
        if (text == prefs.note) return@LaunchedEffect
        delay(600)
        prefs.note = text
        saved = true
        delay(1600)
        saved = false
    }
    Column(modifier) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = TextStyle(color = Page.ink, fontSize = 14.5.sp, lineHeight = 25.sp),
            cursorBrush = SolidColor(NoteWarm),
            modifier = Modifier.fillMaxWidth().heightIn(min = 118.dp)
                .background(rgba(0, 0, 0, .22f), RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) Txt("今天，想留下什么？", Page.muted.copy(alpha = .55f), 14.5.sp)
                    inner()
                }
            },
        )
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 8.dp)) {
            Txt(if (saved) "存好了" else "只存在这台手机上", Page.muted.copy(alpha = .6f), 11.5.sp, Modifier.weight(1f))
            Txt("停手就自动存", Page.muted.copy(alpha = .6f), 11.5.sp)
        }
    }
}

/** 浮光：原来「晚风与浮光」卡片里的内容。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScenePage() {
    Column(Modifier.widthIn(max = 600.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
        CardPageHead("浮光", "晚风与浮光")
        rememberPoster()?.let {
            Image(it, "当前背景场景", Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.FillWidth)
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("WLOP-Sky2-海琴烟", "30 秒循环", "3300 × 1532", "静音播放").forEach {
                Txt(it, Page.muted, 11.5.sp, Modifier.background(CardBlock, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 5.dp))
            }
        }
        Txt("使用你提供的壁纸包中的视频作为动态背景。", Page.ink.copy(alpha = .8f), 13.5.sp, Modifier.padding(horizontal = 8.dp, vertical = 16.dp), lineHeight = 24.sp)
    }
}

/** 卡片底部一条操作栏：贴底，上面一道细线。 */
@Composable
internal fun ColumnScope.CardBottomBar(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(rgba(255, 255, 255, .06f)))
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) { content() }
}
