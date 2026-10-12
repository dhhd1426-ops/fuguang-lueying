package com.fuguang.lueying

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * 添加记忆（方案 3）：卡片从「＋」里放大出来，停在键盘上方；
 * 记住或取消时，再顺着原路缩回「＋」，新的一条随后落进时间线。
 * 卡片画在「我的」整页最上层，遮罩盖满整屏。
 */
object MemoryCard {
    var persona by mutableStateOf<Persona?>(null)
    /** 记进哪一本：diary / about / pref。 */
    var book by mutableStateOf("diary")
    var open by mutableStateOf(false)
    /** 「＋」的中心，窗口坐标。 */
    var origin by mutableStateOf(Offset.Zero)
    /** 卡片收回「＋」的次数，「＋」听着它轻轻跳一下。 */
    var landed by mutableIntStateOf(0)
    /** 刚记住的那条，时间线等卡片收回后再让它落下。 */
    var justAdded by mutableStateOf("")

    fun show(p: Persona, book: String, at: Offset) {
        if (open) return
        persona = p; this.book = book; origin = at; open = true
    }
}

private val warm = css("#e9b48e")
private val inEase = CubicBezierEasing(.55f, 0f, .7f, .35f)

@Composable
fun MemoryCardHost() {
    val p = MemoryCard.persona ?: return
    val prog = remember { Animatable(0f) }
    val shake = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var center by remember { mutableStateOf(Offset.Zero) }
    var editing by remember { mutableStateOf(0) }

    LaunchedEffect(MemoryCard.open) {
        if (MemoryCard.open) {
            launch { kotlinx.coroutines.delay(140); runCatching { focus.requestFocus() } }
            prog.animateTo(1f, spring(dampingRatio = .76f, stiffness = 360f))
        } else {
            prog.animateTo(0f, tween(320, easing = inEase))
            MemoryCard.landed++
            MemoryCard.persona = null
        }
    }
    val close: () -> Unit = {
        focusManager.clearFocus(); keyboard?.hide()
        MemoryCard.open = false
    }
    val save: () -> Unit = {
        if (title.isBlank() && body.isBlank()) {
            scope.launch {
                for (x in listOf(-9f, 8f, -5f, 3f, 0f)) shake.animateTo(x, tween(55))
            }
            runCatching { focus.requestFocus() }
            Unit
        } else {
            val one = title.isBlank() || body.isBlank()
            Memory.add(p, MemoryCard.book, if (title.isBlank()) body else title, if (one) "" else body)?.let { MemoryCard.justAdded = it.id }
            close()
        }
    }
    androidx.activity.compose.BackHandler(enabled = MemoryCard.open) { close() }

    Box(Modifier.fillMaxSize()) {
        // 遮罩：跟着卡片一起深、一起浅
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = prog.value.coerceIn(0f, 1f) }
                .background(rgba(5, 7, 11, .74f))
                .pointerInput(Unit) { detectTapGestures { close() } },
        )
        Box(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth()
                    // 先量出卡片本来在哪，再按进度从「＋」的位置放大过来；键盘升降时路线也跟着改
                    .onGloballyPositioned { center = it.boundsInWindow().center }
                    .graphicsLayer {
                        val v = prog.value
                        val s = .04f + .96f * v
                        scaleX = s; scaleY = s
                        val o = MemoryCard.origin
                        translationX = (o.x - center.x) * (1f - v) + shake.value * density
                        translationY = (o.y - center.y) * (1f - v)
                        alpha = (v * 2.6f).coerceIn(0f, 1f)
                    }
                    .background(rgba(35, 39, 49, .985f), RoundedCornerShape(26.dp))
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 16.dp)
                    .graphicsLayer { alpha = ((prog.value - .45f) / .5f).coerceIn(0f, 1f) },
            ) {
                Txt(
                    when (MemoryCard.book) { "about" -> "关于你"; "pref" -> "你的偏好"; else -> "让 ${p.name} 记住" },
                    Page.ink, 17.sp, family = Serif,
                )
                Spacer(Modifier.height(14.dp))
                val shape = RoundedCornerShape(16.dp)
                BasicTextField(
                    value = title, onValueChange = { title = it.take(40) }, singleLine = true,
                    textStyle = TextStyle(color = Page.ink, fontSize = 16.sp, fontFamily = Serif),
                    cursorBrush = SolidColor(warm),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { if (it.isFocused) editing = 0 },
                    decorationBox = { inner ->
                        Box(
                            Modifier.fillMaxWidth().background(rgba(0, 0, 0, .24f), shape)
                                .border(1.dp, if (editing == 0) warm.copy(alpha = .4f) else Color.Transparent, shape)
                                .padding(horizontal = 14.dp, vertical = 13.dp),
                        ) {
                            if (title.isEmpty()) Txt(
                                when (MemoryCard.book) { "about" -> "比如：我在济南，是工程师"; "pref" -> "比如：回答简短一点"; else -> "比如：答应周末陪他看完那部片" },
                                Page.muted.copy(alpha = .6f), 15.sp, family = Serif, maxLines = 1,
                            )
                            inner()
                        }
                    },
                )
                Spacer(Modifier.height(10.dp))
                BasicTextField(
                    value = body, onValueChange = { body = it.take(500) },
                    textStyle = TextStyle(color = Page.ink, fontSize = 14.sp, lineHeight = 22.sp),
                    cursorBrush = SolidColor(warm),
                    modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) editing = 1 },
                    decorationBox = { inner ->
                        Box(
                            Modifier.fillMaxWidth().heightIn(min = 76.dp).background(rgba(0, 0, 0, .24f), shape)
                                .border(1.dp, if (editing == 1) warm.copy(alpha = .4f) else Color.Transparent, shape)
                                .padding(14.dp),
                        ) {
                            if (body.isEmpty()) Txt("补充细节，可以不写", Page.muted.copy(alpha = .6f), 14.sp)
                            inner()
                        }
                    },
                )
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Pressable(save, label = "记住") { pr ->
                        Box(
                            Modifier.background(warm.copy(alpha = if (pr) .75f else .92f), RoundedCornerShape(50))
                                .padding(horizontal = 30.dp, vertical = 12.dp),
                        ) { Txt("记住", css("#2a211b"), 15.sp) }
                    }
                }
            }
        }
    }
}
