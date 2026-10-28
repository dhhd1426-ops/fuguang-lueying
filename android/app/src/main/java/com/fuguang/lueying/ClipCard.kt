package com.fuguang.lueying

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.sp

internal val ClipWarm: Color get() = Skin.accent
internal val ClipBad = Color(0xFFE07A6E)
private val OnWarm: Color get() = Skin.onAccent

/** 挂在消息下面的抖音卡（和作品卡同一个位置）。 */
@Composable
fun ClipCards(links: List<String>) {
    if (links.isEmpty()) return
    Column(Modifier.padding(top = 10.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        links.forEach { l ->
            // 闹钟卡、日程卡（ClockCards.kt）也挂在这里
            if (isClockCard(l)) { ClockCard(l); return@forEach }
            VideoStore.clip(l)?.let { ClipCard(it) }
            // 网盘分享也挂在这里（方案三「聊天卡片」）；重开 App 后第一次看到就重新打开
            if (VideoStore.clip(l) == null) androidx.compose.runtime.remember(l) { PanStore.item(l) ?: PanStore.offer(l) }?.let { PanCard(it) }
            // Telegram 链接：同一个位置挂卡片；重开 App 后第一次看到就重新认
            if (VideoStore.clip(l) == null && PanStore.item(l) == null) androidx.compose.runtime.remember(l) { TgStore.item(l) ?: TgStore.offer(l) }?.let { TgCard(it) }
        }
    }
}

/**
 * 一张卡：封面 16:10（左上「抖音」、右下时长；图集写张数）、标题、@作者、选项、「存到相册」。
 * 按钮四段反馈（照 dongxiaosheji 01-done-burst，和备份条同一套）：按下缩一点 → 进度从左填满按钮 → 画 ✓、停一拍迸杏色光点 + 轻震 → 底下浮出「打开 · 发给…」。
 * 她用 save_video 存时，卡上走的是同一套：读的是同一条 Clip。
 */
@Composable
fun ClipCard(c: Clip) {
    val ctx = LocalContext.current
    val act = ctx as? Activity
    val haptic = LocalHapticFeedback.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    var burst by remember { mutableStateOf<Offset?>(null) }
    val dx = remember { Animatable(0f) }
    val seenTick = remember { c.doneTick }
    LaunchedEffect(c.doneTick) {
        if (c.doneTick > seenTick) { kotlinx.coroutines.delay(260); burst = Offset(size.width / 2f, size.height - 34f * ctx.resources.displayMetrics.density); haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
    }
    LaunchedEffect(c.save) { if (c.save == 3) dx.animateTo(0f, keyframes { durationMillis = 300; -5f at 75; 5f at 150; -2f at 225 }) }

    Box(Modifier.widthIn(max = 440.dp).fillMaxWidth().onSizeChanged { size = it }) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(veil(.055f))) {
            Cover(c)
            Meta(c)
            if (c.item != null) Picks(c)
            Box(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp).graphicsLayer { translationX = dx.value * density }) {
                Column {
                    SaveButton(c) { VideoStore.save(c, act) }
                    if (c.save == 3) Txt("没成：${c.saveWhy}", ClipBad, 11.5.sp, Modifier.padding(top = 6.dp), maxLines = 2)
                    AnimatedVisibility(c.save == 2, enter = fadeIn(tween(260, 160)) + expandVertically(tween(280, 80, Curves.standard)), exit = fadeOut(tween(120))) {
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Txt(if (c.outMime.startsWith("image") && c.pick == 0) "存在 相册 / 浮光掠影 · ${c.item?.images?.size ?: 0} 张" else "存在 相册 / 浮光掠影", Page.muted, 11.5.sp, Modifier.weight(1f), maxLines = 1)
                            Small("打开", onLong = { openClip(ctx, c) }) { if (ClipPlay.playable(c) && !c.outMime.startsWith("image")) { ClipPlay.play(ctx, c) } else openClip(ctx, c) }
                            Spacer(Modifier.width(6.dp))
                            Small("发给…") { shareClip(ctx, c) }
                        }
                    }
                }
            }
        }
        burst?.let { Burst(it, Modifier.matchParentSize()) { burst = null } }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Cover(c: Clip) {
    val i = c.item
    val ctx = LocalContext.current
    val mine = ClipPlay.isMine(c)
    val req = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // 正在放的是这张：点小窗时把它滑回来；卡片离开（换对话、删掉）就停
    LaunchedEffect(mine) { if (mine) ClipPlay.bring = { scope.launch { req.bringIntoView() } } }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { if (ClipPlay.isMine(c)) { DlPlay.close(); ClipPlay.mini = false } } }
    Box(
        Modifier.fillMaxWidth().aspectRatio(16 / 10f).background(if (mine) Color.Black else veil(.04f))
            .bringIntoViewRequester(req)
            .onGloballyPositioned { co -> if (mine) { val b = co.boundsInWindow(); ClipPlay.mini = b.height < co.size.height * .35f } }
            .then(if (!mine && i != null && ClipPlay.playable(c)) Modifier.clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) { ClipPlay.play(ctx, c) } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (mine) { ClipPlay.playing()?.let { DlEmbed(it, surface = !ClipPlay.mini) }; return@Box }
        val pic = c.cover
        when {
            pic != null -> Image(pic, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            i == null && c.parse == 0 -> Shimmer()
            i == null -> Txt("封面没拿到", Page.muted, 12.sp)
            else -> LoadRing(ClipWarm.copy(alpha = .7f), 16.dp)
        }
        if (i != null) {
            Tag("抖音", Modifier.align(Alignment.TopStart))
            val right = if (i.images.isNotEmpty()) "图集 · ${i.images.size} 张" else if (i.seconds > 0) "%d:%02d".format(i.seconds / 60, i.seconds % 60) else null
            right?.let { Tag(it, Modifier.align(Alignment.BottomEnd)) }
            if (ClipPlay.playable(c)) Box(Modifier.size(52.dp).background(Color(0x80101216), androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                PlayGlyph(false, Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun Shimmer() {
    val t = rememberInfiniteTransition(label = "clipShimmer")
    val a by t.animateFloat(.03f, .09f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Box(Modifier.fillMaxSize().background(veil(a)))
}

@Composable
private fun Tag(text: String, m: Modifier) {
    Txt(text, Page.INK, 10.5.sp, m.padding(8.dp).background(Color.Black.copy(alpha = .45f), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.dp))
}

@Composable
private fun Meta(c: Clip) {
    val i = c.item
    Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp)) {
        when {
            i != null -> {
                Txt(i.title, Page.ink, 15.sp, family = Serif, lineHeight = 22.sp, maxLines = 2)
                if (i.author.isNotBlank()) Txt("@${i.author}", Page.muted, 11.5.sp, Modifier.padding(top = 2.dp), maxLines = 1)
            }
            c.parse == 0 -> Txt("正在认这条抖音", Page.muted, 13.sp)
            else -> Txt("认不出这条，文案看不到", Page.muted, 13.sp)
        }
        if (i == null) Txt(c.link.substringAfter("://"), Page.muted.copy(alpha = .45f), 11.sp, Modifier.padding(top = 2.dp), maxLines = 1)
    }
}

@Composable
private fun Picks(c: Clip) {
    val i = c.item ?: return
    val opts = if (i.images.isNotEmpty()) listOf("${i.images.size} 张原图" to 0, "封面" to 2) else listOf("无水印 · 720p" to 0, "只要声音" to 1, "封面" to 2)
    Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        opts.forEach { (label, k) ->
            val on = c.pick == k
            PressSurface(
                { if (c.save != 1) { if (c.pick != k && c.save == 2) c.save = 0; c.pick = k } }, Modifier.height(26.dp),
                fill = if (on) ClipWarm.copy(alpha = .18f) else veil(.07f), pressedFill = veil(.12f), pressScale = .95f, label = label,
            ) { Txt(label, if (on) ClipWarm else Page.muted, 11.5.sp, Modifier.padding(horizontal = 10.dp)) }
        }
    }
}

/** 主按钮：没存 / 在存（进度填满按钮）/ 存好了（画 ✓）/ 没成（再试一次）。 */
@Composable
internal fun SaveButton(c: Clip, onSave: () -> Unit) {
    val i = c.item
    val f by animateFloatAsState(c.frac, tween(200), label = "clipFrac")
    val fill = when (c.save) { 1 -> veil(.12f); 2 -> ClipWarm.copy(alpha = .16f); 3 -> ClipBad.copy(alpha = .14f); else -> ClipWarm }
    val ink = when (c.save) { 1 -> Page.ink; 2 -> ClipWarm; 3 -> ClipBad; else -> OnWarm }
    val idle = when {
        i == null && c.parse == 2 -> "试试存"
        c.pick == 1 -> "存成 m4a"
        c.pick == 2 -> "存封面"
        i != null && i.images.isNotEmpty() -> "存 ${i.images.size} 张原图"
        else -> "存到相册"
    }
    PressSurface(
        { if (c.save == 0 || c.save == 3) onSave() }, Modifier.fillMaxWidth().height(40.dp), shape = RoundedCornerShape(11.dp),
        enabled = i != null || c.parse == 2, fill = fill, pressedFill = fill.copy(alpha = fill.alpha * .85f), pressScale = .97f, label = idle,
    ) {
        if (c.save == 1) Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(f.coerceIn(0f, 1f)).background(ClipWarm.copy(alpha = .4f)))
        AnimatedContent(c.save, transitionSpec = { fadeIn(tween(220, 60)) togetherWith fadeOut(tween(120)) }, modifier = Modifier.align(Alignment.Center), label = "clipBtn") { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (s) {
                    1 -> { LoadRing(ClipWarm, 14.dp); Spacer(Modifier.width(8.dp)); Txt("${(c.frac * 100).toInt()}%", ink, 14.sp) }
                    2 -> { DrawnCheck(ClipWarm, 16.dp); Spacer(Modifier.width(6.dp)); Txt("存好了", ink, 14.sp) }
                    3 -> Txt("再试一次", ink, 14.sp)
                    else -> Txt(idle, ink, 14.sp, weight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                }
            }
        }
    }
}

/** 小按钮；onLong：长按（「打开」长按用别的播放器）。 */
@Composable
private fun Small(text: String, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    var down by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (down) .95f else 1f, tween(120), label = "small")
    Box(
        Modifier.height(26.dp).graphicsLayer { scaleX = sc; scaleY = sc }.clip(RoundedCornerShape(8.dp)).background(veil(if (down) .14f else .08f))
            .pointerInput(onLong) { detectTapGestures(onPress = { down = true; tryAwaitRelease(); down = false }, onLongPress = onLong?.let { f -> { _ -> f() } }, onTap = { onClick() }) },
        contentAlignment = Alignment.Center,
    ) { Txt(text, Page.ink, 12.sp, Modifier.padding(horizontal = 10.dp)) }
}

fun openClip(ctx: Context, c: Clip) {
    if (c.out.isEmpty()) return
    runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(c.out), c.outMime.ifBlank { "video/mp4" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

fun shareClip(ctx: Context, c: Clip) {
    if (c.out.isEmpty()) return
    runCatching {
        val send = Intent(Intent.ACTION_SEND).setType(c.outMime.ifBlank { "video/mp4" }).putExtra(Intent.EXTRA_STREAM, Uri.parse(c.out)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "发给…").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
