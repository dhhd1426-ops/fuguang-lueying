package com.fuguang.lueying

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URLEncoder

/**
 * 内置浏览器的主页（方案三「浮现」，原型 https://claude.ai/artifact/XQHALyryApNXKZ4Y4yGbtU）：
 * 只有宋体斜体的 Amor 和一条搜索框，居中稍偏上。每次回到主页走一遍：四个字母依次浮上来（每个晚 90ms，640ms decel），
 * 搜索框随后从中间展开（晚 380ms，460ms decel），然后停住。
 * 输入网址直接打开，别的字用 Bing 搜；有字时右边长出杏色前往钮，下面给「打开 …」「搜索「…」」。聚焦时底下划出一道杏色细线。
 */
@Composable
internal fun BrowserHome(play: Int, focus: Int, onGo: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    val fr = remember { FocusRequester() }
    val fm = LocalFocusManager.current
    val letters = remember { "Amor".map { Animatable(0f) } }
    val grow = remember { Animatable(0f) }
    LaunchedEffect(play) {
        letters.forEach { it.snapTo(0f) }; grow.snapTo(0f)
        letters.forEachIndexed { i, a -> launch { delay(90L * i); a.animateTo(1f, tween(640, easing = Curves.decel)) } }
        launch { delay(380); grow.animateTo(1f, tween(460, easing = Curves.decel)) }
    }
    LaunchedEffect(focus) { if (focus > 0) runCatching { fr.requestFocus() } }
    fun submit(v: String) { val t = v.trim(); if (t.isEmpty()) return; q = ""; fm.clearFocus(); onGo(target(t)) }

    Column(
        Modifier.fillMaxSize().background(Color(0xFF121418)).padding(horizontal = 22.dp).padding(bottom = 70.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Row(Modifier.padding(bottom = 28.dp)) {
            "Amor".forEachIndexed { i, c ->
                val a = letters[i].value
                androidx.compose.foundation.text.BasicText(c.toString(), Modifier.graphicsLayer { alpha = a; translationY = (1f - a) * 8.dp.toPx() },
                    style = TextStyle(color = Page.INK, fontSize = 58.sp, fontFamily = Serif, fontStyle = FontStyle.Italic, letterSpacing = .02.em))
            }
        }
        val g = grow.value
        Box(
            Modifier.fillMaxWidth().height(50.dp).graphicsLayer { alpha = g; scaleX = .6f + .4f * g; transformOrigin = TransformOrigin.Center }
                .background(if (focused) Color(0xFF262B33) else Color(0xFF22262D), RoundedCornerShape(25.dp)),
        ) {
            Row(Modifier.fillMaxSize().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SvgIcon(Ic("M20 20l-4.2-4.2", circles = listOf(floatArrayOf(11f, 11f, 6.5f))), Page.INK.copy(alpha = .5f), 17.dp, stroke = 1.9f)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (q.isEmpty()) Txt("搜索，或输入网址", Page.INK.copy(alpha = .38f), 15.sp, maxLines = 1)
                    BasicTextField(
                        q, { q = it }, Modifier.fillMaxWidth().focusRequester(fr).onFocusChanged { focused = it.isFocused },
                        singleLine = true, textStyle = TextStyle(fontFamily = Sans, color = Page.INK, fontSize = 15.sp), cursorBrush = SolidColor(FpWarm),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { submit(q) }),
                    )
                }
                val gs by animateFloatAsState(if (q.isNotBlank()) 1f else 0f, tween(200, easing = Curves.decel), label = "go")
                if (gs > 0f) PressSurface({ submit(q) }, Modifier.size(34.dp).graphicsLayer { scaleX = gs; scaleY = gs }, shape = CircleShape, fill = FpWarm, pressScale = .9f, label = "前往") {
                    SvgIcon(Ic("M5 12h13M13 6l6 6-6 6"), Color(0xFF2A211B), 16.dp, stroke = 2.2f, modifier = Modifier.align(Alignment.Center))
                }
            }
            val line by animateFloatAsState(if (focused) 1f else 0f, tween(320, easing = Curves.decel), label = "line")
            if (line > 0f) Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp).fillMaxWidth().height(1.5.dp).graphicsLayer { scaleX = line }.background(FpWarm, RoundedCornerShape(1.dp)))
        }
        // 建议：像网址的给「打开」，总是给「搜索」
        val t = q.trim()
        val sa by animateFloatAsState(if (t.isNotEmpty()) 1f else 0f, tween(200, easing = Curves.decel), label = "sug")
        Column(Modifier.fillMaxWidth().padding(top = 8.dp).height(100.dp).graphicsLayer { alpha = sa; translationY = (1f - sa) * -4.dp.toPx() }) {
            if (sa > 0f && t.isNotEmpty()) {
                if (looksUrl(t)) Suggest(Ic("M4 12h16M12 4c2.4 2.5 2.4 13.5 0 16M12 4c-2.4 2.5-2.4 13.5 0 16", circles = listOf(floatArrayOf(12f, 12f, 8f))), "打开 " + t.removePrefix("https://").removePrefix("http://"), null) { submit(t) }
                Suggest(Ic("M20 20l-4.2-4.2", circles = listOf(floatArrayOf(11f, 11f, 6.5f))), "搜索「$t」", "Bing") { fm.clearFocus(); q = ""; onGo(bing(t)) }
            }
        }
        Txt("用 Bing 搜索", Page.INK.copy(alpha = .32f), 11.5.sp, Modifier.graphicsLayer { alpha = g })
    }
}

@Composable
private fun Suggest(icon: Ic, text: String, tag: String?, onClick: () -> Unit) {
    PressSurface(onClick, Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), fill = Color.Transparent, pressScale = .98f, label = text) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            SvgIcon(icon, Page.INK.copy(alpha = .5f), 15.dp, stroke = 1.9f)
            Spacer(Modifier.width(10.dp))
            Txt(text, Page.INK, 14.sp, Modifier.weight(1f), maxLines = 1)
            if (tag != null) Txt(tag, Page.MUTED, 12.sp, maxLines = 1)
        }
    }
}

private val urlLike = Regex("^(https?://)?([\\w-]+\\.)+[a-zA-Z]{2,}(:\\d+)?(/\\S*)?$")
private fun looksUrl(s: String) = !s.contains(' ') && urlLike.matches(s)
private fun bing(s: String) = "https://www.bing.com/search?q=" + URLEncoder.encode(s, "UTF-8")
/** 像网址就补上 https:// 直接开，不像就去搜。 */
private fun target(s: String) = if (looksUrl(s)) (if (s.startsWith("http://") || s.startsWith("https://")) s else "https://$s") else bing(s)

