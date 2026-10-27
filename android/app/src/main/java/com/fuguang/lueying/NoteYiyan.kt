package com.fuguang.lueying

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val checkIc = Ic("M5 12.5l4.5 4.5L19 7")
private val crossIc = Ic("M7 7l10 10M17 7L7 17")

/** 手记首页「一言」那一行的小字。 */
internal fun yiyanSub(): String =
    if (Yiyan.items.isEmpty()) "还没有，写下第一句放到桌面上"
    else "${Yiyan.items.size} 句" + (Yiyan.text()?.let { " · 桌面上「$it」" } ?: "")

/**
 * 一言本：像记笔记一样。顶上写一句，回车或 ✓ 加进去（新的在上，桌面马上换成它）；
 * 点一句原地改，停手 0.4 秒自动存；× 删掉，底下能撤销；从 txt 导入，一行一句。
 */
@Composable
internal fun YiyanPage() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    var draft by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf("点一句就能改") }
    var undo by remember { mutableStateOf<Triple<YLine, Int, Boolean>?>(null) }
    LaunchedEffect(undo) { if (undo != null) { delay(4000); undo = null } }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
        val n = text?.let { Yiyan.importText(it) } ?: -1
        hint = when {
            n < 0 -> "这个文件读不出来，换一个 txt 试试"
            n == 0 -> "文件里的句子都已经有了"
            else -> "导入了 $n 句"
        }
        if (n > 0) scope.launch { list.animateScrollToItem(0) }
    }
    fun submit() {
        if (draft.isBlank()) return
        Yiyan.add(draft)
        draft = ""
        hint = "放到桌面上了"
        scope.launch { list.animateScrollToItem(0) }
    }

    Box(Modifier.widthIn(max = 600.dp).fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            CardPageHead("一言", if (Yiyan.items.isEmpty()) "桌面小组件每 2 小时换一句" else "${Yiyan.items.size} 句 · 每 2 小时换一句")
            WriteRow(draft, { draft = it }, ::submit)
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp),
                state = list,
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                if (Yiyan.items.isEmpty()) item("empty") {
                    Txt(
                        "还没有一言。\n在上面写一句，它就会出现在桌面上。", Page.muted.copy(alpha = .7f), 13.sp,
                        Modifier.fillMaxWidth().padding(vertical = 40.dp), lineHeight = 22.sp,
                        align = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
                items(Yiyan.items, key = { it.id }) { l ->
                    LineRow(l, l.id == Yiyan.cur, Modifier.animateItem()) { Yiyan.remove(l.id)?.let { undo = it } }
                }
            }
            CardBottomBar {
                Txt(hint, Page.muted.copy(alpha = .65f), 12.sp, Modifier.weight(1f).padding(start = 6.dp), maxLines = 1)
                PressSurface({ pick.launch(arrayOf("text/plain")) }, fill = Color.Transparent, label = "从文件导入") {
                    Txt("从文件导入", Page.muted, 12.5.sp, Modifier.padding(horizontal = 12.dp, vertical = 12.dp))
                }
            }
        }
        AnimatedVisibility(
            undo != null,
            Modifier.align(Alignment.BottomCenter).padding(bottom = 52.dp),
            enter = slideInVertically(tween(240, easing = Curves.decel)) { it / 2 } + fadeIn(tween(240)),
            exit = slideOutVertically(tween(180, easing = Curves.accel)) { it / 2 } + fadeOut(tween(180)),
        ) {
            Row(
                Modifier.fillMaxWidth().background(rgba(47, 49, 54, .97f), RoundedCornerShape(16.dp))
                    .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Txt("删掉了一句", Page.INK, 13.5.sp, Modifier.weight(1f))
                Pressable({ undo?.let { (l, i, was) -> Yiyan.restore(l, i, was) }; undo = null }, label = "撤销") { pr ->
                    Txt("撤销", NoteWarm, 13.5.sp, Modifier.background(if (pr) Color.White.copy(alpha = .06f) else Color.Transparent, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 9.dp))
                }
            }
        }
    }
}

/** 顶上写一句的那一行：有字时 ✓ 亮成杏色。 */
@Composable
private fun WriteRow(text: String, onChange: (String) -> Unit, onDone: () -> Unit) {
    val lit = text.isNotBlank()
    val k by animateFloatAsState(if (lit) 1f else 0f, tween(180, easing = Curves.standard), label = "lit")
    Row(
        Modifier.fillMaxWidth().background(Skin.well(.22f), RoundedCornerShape(14.dp)).padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = text,
            onValueChange = { onChange(it.replace("\n", "").take(60)) },
            singleLine = true,
            textStyle = TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, color = Page.ink, fontSize = 15.sp),
            cursorBrush = SolidColor(NoteWarm),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier.weight(1f).padding(vertical = 10.dp),
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) Txt("写一句，放到桌面上", Page.muted.copy(alpha = .55f), 14.sp)
                    inner()
                }
            },
        )
        PressSurface(
            onDone, Modifier.size(40.dp), shape = RoundedCornerShape(12.dp),
            fill = NoteWarm.copy(alpha = k), pressedFill = NoteWarm.copy(alpha = .8f * k), enabled = lit, label = "加进一言",
        ) {
            SvgIcon(checkIc, if (lit) Color(0xFF2A1D14) else Page.muted.copy(alpha = .5f), 18.dp, stroke = 2f)
        }
    }
}

/** 一句：点了原地改，停手 0.4 秒存；桌面上那句下面标「桌面上」。 */
@Composable
private fun LineRow(l: YLine, onDesk: Boolean, modifier: Modifier, onDelete: () -> Unit) {
    var text by remember(l.id) { mutableStateOf(l.t) }
    val focus = LocalFocusManager.current
    LaunchedEffect(text) {
        if (text == l.t || text.isBlank()) return@LaunchedEffect
        delay(400)
        Yiyan.edit(l.id, text)
    }
    Row(modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f).padding(start = 10.dp, top = 10.dp, bottom = 10.dp)) {
            BasicTextField(
                value = text,
                onValueChange = { text = it.replace("\n", "").take(60) },
                textStyle = TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, color = Page.ink, fontSize = 15.sp, lineHeight = 25.sp),
                cursorBrush = SolidColor(NoteWarm),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth(),
            )
            AnimatedVisibility(onDesk, enter = fadeIn(tween(220)), exit = fadeOut(tween(160))) {
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(5.dp).background(NoteWarm, CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Txt("桌面上", NoteWarm, 11.sp)
                }
            }
        }
        PressSurface(onDelete, Modifier.size(44.dp).scale(.92f), shape = CircleShape, fill = Color.Transparent, label = "删掉这句") {
            SvgIcon(crossIc, Page.muted.copy(alpha = .7f), 15.dp, stroke = 1.8f)
        }
    }
}
