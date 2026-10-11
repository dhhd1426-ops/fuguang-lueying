package com.fuguang.lueying

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 回复下面一排羽的笔记图片缩略图，可以横着滑；点开看大图。 */
@Composable
fun NoteImageRow(refs: List<String>, onOpen: (String) -> Unit) {
    if (refs.isEmpty()) return
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(start = 2.dp, top = 4.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        refs.forEach { ref ->
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

/** 看大图：深色底，图片居中；点一下或往上下拖开就收起，拖的时候图片跟着手指走。 */
@Composable
fun NoteImageViewer(ref: String?, onClose: () -> Unit) {
    var last by remember { mutableStateOf<String?>(null) }
    if (ref != null) last = ref
    val shown by animateFloatAsState(if (ref != null) 1f else 0f, tween(if (ref != null) 240 else 200), label = "viewer")
    if (shown <= 0f || last == null) return
    val cur = last!!
    val bmp by produceState<android.graphics.Bitmap?>(null, cur) { value = Yu.bitmap(cur, 2048) }
    val drag = remember(cur) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(ref) { if (ref != null) drag.snapTo(0f) }
    androidx.activity.compose.BackHandler(enabled = ref != null) { onClose() }
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
                it.asImageBitmap(), "笔记图片",
                Modifier.fillMaxSize().padding(18.dp)
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
}
