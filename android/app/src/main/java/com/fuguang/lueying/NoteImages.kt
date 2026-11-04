package com.fuguang.lueying

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 羽的笔记图（"笔记id/图片id"）和她发的图（SentPics，"pic:…"）都从这里读。 */
suspend fun refBitmap(ref: String, max: Int): android.graphics.Bitmap? = if (SentPics.isRef(ref)) SentPics.bitmap(ref, max) else Yu.bitmap(ref, max)

/** 回复下面的图：羽的笔记图是一排小方块（可以横着滑）；她发的图一张就大图、两张以上两列方块。点开看大图。 */
@Composable
fun NoteImageRow(refs: List<String>, onOpen: (String) -> Unit) {
    if (refs.isEmpty()) return
    val (pics, notes) = refs.partition { SentPics.isRef(it) }
    if (pics.isNotEmpty()) SentPicBlock(pics, onOpen)
    if (notes.isEmpty()) return
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(start = 2.dp, top = 4.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        notes.forEach { ref ->
            val bmp by produceState<android.graphics.Bitmap?>(null, ref) { value = Yu.bitmap(ref, 360) }
            // 图片读出来之前是一块淡色块，读到后淡入
            val a by animateFloatAsState(if (bmp != null) 1f else 0f, tween(260), label = "thumb")
            Box(
                Modifier.size(84.dp).clip(RoundedCornerShape(14.dp)).background(rgba(255, 255, 255, .06f))
                    .pointerInput(ref) { detectTapGestures { onOpen(ref) } },
            ) {
                bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize().alpha(a), contentScale = ContentScale.Crop) }
            }
        }
    }
}

/**
 * 她发的图（原型 MkwUcuJbKmQhytTfb2GNvE）：一张占 76% 宽、按原图比例（夹在 1:2 到 2:1 之间），下面一行说明；
 * 两张以上 86% 宽两列方块，外角 18、里角 8、缝 6，单数时最后一张占整行（2:1）。
 */
@Composable
private fun SentPicBlock(refs: List<String>, onOpen: (String) -> Unit) {
    Column(Modifier.padding(start = 2.dp, top = 4.dp, bottom = 12.dp)) {
        if (refs.size == 1) {
            val ref = refs[0]
            val wh by produceState<Pair<Int, Int>?>(null, ref, SentPics.ok(ref)) { value = SentPics.size(ref) }
            val ratio = wh?.let { (w, h) -> (w.toFloat() / h).coerceIn(.5f, 2f) } ?: (4f / 3f)
            PicTile(ref, RoundedCornerShape(18.dp), Modifier.fillMaxWidth(.76f).aspectRatio(ratio), onOpen)
            val cap = remember(ref) { SentPics.caption(ref) }
            if (cap.isNotEmpty()) Txt(cap, Page.muted, 12.sp, Modifier.padding(start = 2.dp, top = 6.dp), maxLines = 2)
            return@Column
        }
        val big = 18.dp; val small = 8.dp
        val rows = refs.chunked(2)
        Column(Modifier.fillMaxWidth(.86f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEachIndexed { ri, row ->
                val top = ri == 0; val bottom = ri == rows.lastIndex
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (row.size == 1) {
                        PicTile(row[0], RoundedCornerShape(if (top) big else small, if (top) big else small, big, big), Modifier.weight(1f).aspectRatio(2f), onOpen)
                    } else row.forEachIndexed { ci, ref ->
                        val left = ci == 0
                        val shape = RoundedCornerShape(
                            topStart = if (top && left) big else small, topEnd = if (top && !left) big else small,
                            bottomEnd = if (bottom && !left) big else small, bottomStart = if (bottom && left) big else small,
                        )
                        PicTile(ref, shape, Modifier.weight(1f).aspectRatio(1f), onOpen)
                    }
                }
            }
        }
    }
}

/** 一格图：拿到之前淡色块一明一暗地闪；拿到后 260ms 淡入、460ms 从 1.04 收到 1；没拿到写一句。按下缩到 .97。 */
@Composable
private fun PicTile(ref: String, shape: Shape, modifier: Modifier, onOpen: (String) -> Unit) {
    val ready = SentPics.ok(ref)
    val fail = SentPics.fail(ref)
    val bmp by produceState<android.graphics.Bitmap?>(null, ref, ready) { if (ready) value = SentPics.bitmap(ref, 1080) }
    val a by animateFloatAsState(if (bmp != null) 1f else 0f, tween(260), label = "pic")
    val sc by animateFloatAsState(if (bmp != null) 1f else 1.04f, tween(460, easing = Curves.decel), label = "picScale")
    val pulse = if (bmp == null && fail == null) {
        val t = rememberInfiniteTransition(label = "wait")
        t.animateFloat(.05f, .1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse").value
    } else .06f
    Pressable({ if (bmp != null) onOpen(ref) }, modifier, enabled = true, label = "看大图") { pressed ->
        val ps by animateFloatAsState(if (pressed && bmp != null) .97f else 1f, tween(if (pressed) 80 else 200), label = "picPress")
        Box(
            Modifier.fillMaxSize().graphicsLayer { scaleX = ps; scaleY = ps }.clip(shape).background(veil(pulse)),
            contentAlignment = Alignment.Center,
        ) {
            bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize().graphicsLayer { alpha = a; scaleX = sc; scaleY = sc }, contentScale = ContentScale.Crop) }
            if (fail != null) Txt("这张图没拿到", Page.muted, 12.sp, Modifier.padding(8.dp), align = TextAlign.Center)
        }
    }
}

/** 看大图：深色底，图片居中；点一下或往上下拖开就收起，拖的时候图片跟着手指走。她发的图底下多「存到相册」「分享」。 */
@Composable
fun NoteImageViewer(ref: String?, onClose: () -> Unit) {
    var last by remember { mutableStateOf<String?>(null) }
    if (ref != null) last = ref
    val shown by animateFloatAsState(if (ref != null) 1f else 0f, tween(if (ref != null) 240 else 200), label = "viewer")
    if (shown <= 0f || last == null) return
    val cur = last!!
    val bmp by produceState<android.graphics.Bitmap?>(null, cur) { value = refBitmap(cur, 2048) }
    val drag = remember(cur) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(ref) { if (ref != null) drag.snapTo(0f) }
    androidx.activity.compose.BackHandler(enabled = ref != null) { onClose() }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .pointerInput(cur) { detectTapGestures { onClose() } }
                .pointerInput(cur) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (abs(drag.value) > size.height * .14f) onClose()
                            else scope.launch { drag.animateTo(0f, tween(220)) }
                        },
                    ) { ch, dy -> ch.consume(); scope.launch { drag.snapTo(drag.value + dy) } }
                },
            contentAlignment = Alignment.Center,
        ) {
            val fade = (1f - abs(drag.value) / 900f).coerceIn(.25f, 1f)
            Box(Modifier.fillMaxSize().alpha(shown * fade).background(rgba(6, 7, 9, .94f)))
            bmp?.let {
                Image(
                    it.asImageBitmap(), "图片",
                    Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = if (SentPics.isRef(cur)) 120.dp else 18.dp)
                        .graphicsLayer {
                            alpha = shown
                            translationY = drag.value
                            val sc = (.94f + .06f * shown) * (1f - abs(drag.value) / 4000f)
                            scaleX = sc; scaleY = sc
                        },
                    contentScale = ContentScale.Fit,
                )
            }
        }
        if (SentPics.isRef(cur)) PicActions(cur, shown, Modifier.align(Alignment.BottomCenter))
    }
}

/** 看大图的底永远是深色，字和按钮不跟主题。 */
private val viewerInk = Color(0xFFF2EEE8)
private val viewerMuted = Color(0xFFB4AFA7)
private val viewerBtn = Color(1f, 1f, 1f, .12f)
private val viewerBtnPress = Color(1f, 1f, 1f, .2f)

/** 大图底下：说明、存到相册、分享。比图晚 120ms 从下面 12dp 升上来；存好了按钮变杏色写「已存好」。 */
@Composable
private fun PicActions(ref: String, shown: Float, modifier: Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var saved by remember(ref) { mutableStateOf<String?>(null) }
    var busy by remember(ref) { mutableStateOf(false) }
    val rise by animateFloatAsState(if (shown >= 1f) 1f else 0f, tween(320, delayMillis = 120, easing = Curves.decel), label = "acts")
    val cap = remember(ref) { SentPics.caption(ref) }
    Column(
        modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 26.dp)
            .graphicsLayer { alpha = rise * shown; translationY = (1f - rise) * 12.dp.toPx() },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (cap.isNotEmpty()) Txt(cap, viewerMuted, 13.sp, Modifier.padding(start = 22.dp, end = 22.dp, bottom = 12.dp), align = TextAlign.Center, maxLines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val done = saved == ""
            PressSurface(
                {
                    if (busy || done) return@PressSurface
                    busy = true
                    scope.launch { saved = SentPics.save(ctx, ref) ?: ""; busy = false }
                },
                shape = RoundedCornerShape(50), fill = if (done) Skin.accent else viewerBtn, pressedFill = if (done) Skin.accent else viewerBtnPress, pressScale = .95f, label = "存到相册",
            ) {
                Txt(
                    when { done -> "已存好"; saved != null -> "没存上：$saved"; busy -> "正在存…"; else -> "存到相册" },
                    if (done) Skin.onAccent else viewerInk, 14.sp, Modifier.padding(horizontal = 18.dp, vertical = 11.dp), maxLines = 1,
                )
            }
            PressSurface({ SentPics.share(ctx, ref) }, shape = RoundedCornerShape(50), fill = viewerBtn, pressedFill = viewerBtnPress, pressScale = .95f, label = "分享") {
                Txt("分享", viewerInk, 14.sp, Modifier.padding(horizontal = 18.dp, vertical = 11.dp))
            }
        }
    }
}
