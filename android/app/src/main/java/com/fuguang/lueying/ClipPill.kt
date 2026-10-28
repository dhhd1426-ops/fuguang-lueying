package com.fuguang.lueying

import android.app.Activity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Warm: Color get() = ClipWarm
private val Bad = ClipBad

/**
 * 剪贴板里的抖音：顶上滑下的长条。只管「复制了就提醒」：封面 + 标题 +「存下来 / 让她看看 / ×」。
 * 存下来：长条里走进度，好了画 ✓、停一拍迸光点 + 轻震，2.6 秒后自己收；没成写原因、抖一下。
 * 让她看看：长条向下收进聊天，直接发出一条消息，卡片落在那条消息下面（同一条 Clip）。
 */
@Composable
fun ClipPillLayer() {
    val act = LocalContext.current as? Activity
    val haptic = LocalHapticFeedback.current
    val c = VideoStore.clip(ClipLink.pill)
    var into by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 56.dp, start = 12.dp, end = 12.dp), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            ClipLink.showing && c != null,
            enter = slideInVertically(spring(.78f, 420f)) { -it - 80 } + fadeIn(tween(220)),
            exit = if (into) scaleOut(tween(200, easing = Curves.accel), .9f) + slideOutVertically(tween(220, easing = Curves.accel)) { it / 2 } + fadeOut(tween(200))
                else slideOutVertically(tween(220, easing = Curves.accel)) { -it - 80 } + fadeOut(tween(200)),
        ) {
            val clip = c ?: return@AnimatedVisibility
            var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
            var burst by remember { mutableStateOf<Offset?>(null) }
            val dx = remember { Animatable(0f) }
            val seen = remember(clip) { clip.doneTick }
            LaunchedEffect(clip.save) { if (clip.save == 3) dx.animateTo(0f, keyframes { durationMillis = 300; -6f at 75; 6f at 150; -3f at 225 }) }
            LaunchedEffect(clip.doneTick) {
                if (clip.doneTick > seen) {
                    kotlinx.coroutines.delay(260); burst = Offset(size.width / 2f, size.height / 2f); haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    kotlinx.coroutines.delay(2600); if (clip.save == 2) ClipLink.dismiss()
                }
            }
            Box(Modifier.widthIn(max = 460.dp).fillMaxWidth().onSizeChanged { size = it }.graphicsLayer { translationX = dx.value * density }) {
                Row(
                    Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp), 22.dp, fill = Skin.cardFill(.78f), border = Color.Transparent).padding(10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Cover(clip)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) { Body(clip, act) { into = true; ClipLink.handOver() } }
                    Pressable({ into = false; ClipLink.dismiss() }, Modifier.padding(start = 4.dp), label = "不用") { _ ->
                        SvgIcon(Icons.close, Page.muted, 16.dp, modifier = Modifier.padding(4.dp))
                    }
                }
                burst?.let { Burst(it, Modifier.matchParentSize()) { burst = null } }
            }
        }
    }
}

@Composable
private fun Cover(c: Clip) {
    val pic = c.cover
    Box(Modifier.size(44.dp, 58.dp).clip(RoundedCornerShape(8.dp)).background(veil(.06f)), contentAlignment = Alignment.Center) {
        if (pic != null) Image(pic, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        else if (c.parse == 0) LoadRing(Warm.copy(alpha = .8f), 14.dp)
        val n = c.item?.images?.size ?: 0
        if (n > 1) Txt("$n 张", Page.INK, 9.sp, Modifier.align(Alignment.BottomEnd).padding(3.dp).background(Color.Black.copy(alpha = .45f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp))
    }
}

@Composable
private fun Body(c: Clip, act: Activity?, onAsk: () -> Unit) {
    val i = c.item
    val kicker = when {
        c.save == 1 -> "正在存 ${(c.frac * 100).toInt()}%"
        c.save == 3 -> "没成：" + c.saveWhy
        i == null && c.parse == 0 -> "剪贴板里有一条抖音，正在认"
        i == null -> "认不出这条，点存下来换个办法试"
        i.images.isNotEmpty() -> "剪贴板里有一条抖音 · 图集"
        else -> "剪贴板里有一条抖音" + (i.seconds.takeIf { it > 0 }?.let { " · %d:%02d".format(it / 60, it % 60) } ?: "")
    }
    AnimatedContent(c.save == 2, transitionSpec = { fadeIn(tween(220, 80)) togetherWith fadeOut(tween(120)) }, label = "kicker") { done ->
        if (done) Row(verticalAlignment = Alignment.CenterVertically) {
            DrawnCheck(Warm, 14.dp); Spacer(Modifier.width(4.dp))
            Txt("存在 相册 / 浮光掠影", Warm, 11.sp, maxLines = 1)
        } else Txt(kicker, if (c.save == 3) Bad else Warm, 11.sp, spacing = .3.sp, maxLines = 1)
    }
    Spacer(Modifier.height(3.dp))
    Txt(i?.title ?: c.link, Page.ink, 13.5.sp, maxLines = 1)
    i?.author?.takeIf { it.isNotBlank() }?.let { Txt("@$it", Page.muted, 11.sp, Modifier.padding(top = 1.dp), maxLines = 1) }
    Spacer(Modifier.height(8.dp))
    when (c.save) {
        1 -> Progress(c)
        2 -> Spacer(Modifier.height(2.dp))
        else -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val n = i?.images?.size ?: 0
            Btn(if (c.save == 3) "再试一次" else if (n > 0) "存 $n 张原图" else "存下来", true, enabled = i != null || c.parse == 2) { c.pick = 0; VideoStore.save(c, act, 0) }
            Btn("让她看看", false, onClick = onAsk)
        }
    }
}

@Composable
private fun Progress(c: Clip) {
    val f by animateFloatAsState(c.frac, tween(200), label = "clipFrac")
    Box(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(veil(.08f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(f.coerceIn(0f, 1f)).background(Warm))
    }
}

@Composable
private fun Btn(text: String, main: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    PressSurface(
        onClick, Modifier.height(30.dp), shape = RoundedCornerShape(8.dp), enabled = enabled,
        fill = if (main) Warm else veil(.08f), pressedFill = if (main) Warm.copy(alpha = .85f) else veil(.14f),
        pressScale = .95f, label = text,
    ) { Txt(text, if (main) Skin.onAccent else Page.ink, 12.sp, Modifier.padding(horizontal = 12.dp)) }
}
