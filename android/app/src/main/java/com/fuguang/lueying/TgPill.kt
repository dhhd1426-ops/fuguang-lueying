package com.fuguang.lueying

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 方案一「长条里展开」：复制了 Telegram 消息链接回到 App，顶上滑下长条（封面 · 标题 · 存下来 / 让她看看 / ×）。
 * 要登录的点「授权后下」：从按钮长出授权弹窗（TgAuth），授权好缩回、接着下。一条有好几个视频的点「选要存的」：长条原地往下长出勾选。
 * 下好画 ✓、停一拍迸光点 + 轻震，2.6 秒后自己收。点 × 或返回：一次收完。
 */
object TgPill {
    var pill by mutableStateOf<String?>(null)
    var showing by mutableStateOf(false)
    /** 长条下面展开着：pick 勾选 · null 收着（登录在悬浮卡片 TgAuth 里）。 */
    var open by mutableStateOf<String?>(null)
    /** 「让她看看」：发一条消息（聊天页挂上）。 */
    var ask: ((String) -> Unit)? = null
    var into by mutableStateOf(false)

    fun show(link: String, expand: String? = null) { pill = link; open = expand; into = false; showing = true }
    fun dismiss() { showing = false; open = null }
}

@Composable
fun TgPillLayer() {
    val it = TgStore.item(TgPill.pill)
    if (TgPill.showing && TgPill.open != null) BackHandler { TgPill.dismiss() }
    Box(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 56.dp, start = 12.dp, end = 12.dp), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            TgPill.showing && it != null,
            enter = slideInVertically(spring(.78f, 420f)) { h -> -h / 3 - 80 } + fadeIn(tween(220)),
            exit = if (TgPill.into) scaleOut(tween(200, easing = Curves.accel), .9f) + slideOutVertically(tween(220, easing = Curves.accel)) { h -> h / 2 } + fadeOut(tween(200))
                else slideOutVertically(tween(220, easing = Curves.accel)) { h -> -h / 4 - 40 } + scaleOut(tween(220, easing = Curves.accel), .97f) + fadeOut(tween(180)),
        ) {
            val item = it ?: return@AnimatedVisibility
            val seen = remember(item) { item.doneTick }
            LaunchedEffect(item.doneTick) { if (item.doneTick > seen) { kotlinx.coroutines.delay(2860); if (item.save == 2) TgPill.dismiss() } }
            Column(Modifier.widthIn(max = 460.dp).fillMaxWidth().glass(RoundedCornerShape(16.dp), 22.dp, fill = rgba(30, 33, 42, .86f), border = Color.Transparent)) {
                TgBody(item, pill = true)
                AnimatedVisibility(TgPill.open != null, enter = expandVertically(tween(380, easing = Curves.decel), Alignment.Top) + fadeIn(tween(240, 80)),
                    exit = shrinkVertically(tween(240, easing = Curves.accel), Alignment.Top) + fadeOut(tween(160))) {
                    val act = LocalContext.current as? Activity
                    Box(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                        TgPick(item) { TgPill.open = null; TgStore.download(item, act) }
                    }
                }
            }
        }
    }
}

/** 长条和聊天卡片共用的那一块：封面、哪条、进度、按钮。pill = false 是聊天卡片（没有 ×，要展开时把长条叫下来）。 */
@Composable
internal fun TgBody(item: TgItem, pill: Boolean) {
    val act = LocalContext.current as? Activity
    val haptic = LocalHapticFeedback.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    var burst by remember { mutableStateOf<Offset?>(null) }
    val dx = remember { Animatable(0f) }
    val seen = remember(item) { item.doneTick }
    LaunchedEffect(item.save) { if (item.save == 3) dx.animateTo(0f, keyframes { durationMillis = 300; -6f at 75; 6f at 150; -3f at 225 }) }
    LaunchedEffect(item.doneTick) {
        if (item.doneTick > seen) { kotlinx.coroutines.delay(260); burst = Offset(size.width / 2f, size.height / 2f); haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
    }
    Box(Modifier.fillMaxWidth().onSizeChanged { size = it }.graphicsLayer { translationX = dx.value * density }) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.Top) {
            Cover(item)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Kicker(item)
                Spacer(Modifier.height(3.dp))
                Txt(item.title.ifBlank { item.link }, Page.ink, 13.5.sp, maxLines = 1)
                Txt(whoLine(item), Page.muted, 11.sp, Modifier.padding(top = 1.dp), maxLines = 1)
                Spacer(Modifier.height(8.dp))
                when (item.save) {
                    1 -> Progress(item)
                    2 -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Btn("在下载里看", false) { Downloads.open = true; TgPill.dismiss() } }
                    else -> if (TgPill.open == null || !pill) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val n = item.vids.size
                        val login = item.needLogin && Td.state != "ready"
                        when {
                            item.stage == 2 -> Btn("再认一次", true) { TgStore.open(item) }
                            login -> Box(Modifier.onGloballyPositioned { c -> TgAuth.from = c.boundsInRoot().center }) {
                                Btn("授权后下", true, enabled = item.stage == 1) { TgAuth.show(item) }
                            }
                            n > 1 -> Btn("选要存的", true) { if (pill) TgPill.open = "pick" else TgPill.show(item.link, "pick") }
                            else -> Btn(if (item.save == 3) "再试一次" else "存下来", true, enabled = item.stage == 1 && n > 0) { TgStore.download(item, act) }
                        }
                        if (pill) Btn("让她看看", false) { TgPill.into = true; TgPill.ask?.invoke("看看这条 Telegram：" + item.link); TgPill.dismiss() }
                    }
                }
            }
            if (pill) Pressable({ TgPill.into = false; TgPill.dismiss() }, Modifier.padding(start = 4.dp), label = "不用") { _ ->
                SvgIcon(Icons.close, Page.muted, 16.dp, modifier = Modifier.padding(4.dp))
            }
        }
        burst?.let { b -> Burst(b, Modifier.matchParentSize()) { burst = null } }
    }
}

private fun whoLine(it: TgItem): String {
    val bytes = it.vids.filter { v -> v.on }.sumOf { v -> v.size.coerceAtLeast(0) }
    return listOfNotNull(it.who.takeIf { w -> w.isNotBlank() }, bytes.takeIf { b -> b > 0 }?.let { b -> mb(b) }).joinToString(" · ")
}


@Composable
private fun Kicker(it: TgItem) {
    val n = it.vids.size
    val secs = it.vids.firstOrNull()?.seconds ?: 0
    val text = when {
        it.save == 1 -> "正在下 ${(it.frac * 100).toInt()}%"
        it.save == 3 -> "没成：" + it.why
        it.stage == 0 -> "剪贴板里有一条 Telegram，正在认"
        it.stage == 2 -> it.why
        it.needLogin && Td.state != "ready" -> if (it.l.private) "私有频道，授权 Telegram 后能下" else "这条要授权 Telegram 才能下"
        n == 0 -> "这条里没有视频"
        n > 1 -> "剪贴板里有一条 Telegram · $n 个视频"
        else -> "剪贴板里有一条 Telegram 视频" + (secs.takeIf { s -> s > 0 }?.let { s -> " · %d:%02d".format(s / 60, s % 60) } ?: "")
    }
    AnimatedContent(it.save == 2, transitionSpec = { fadeIn(tween(220, 80)) togetherWith fadeOut(tween(120)) }, label = "tgKick") { done ->
        if (done) Row(verticalAlignment = Alignment.CenterVertically) {
            DrawnCheck(ClipWarm, 14.dp); Spacer(Modifier.width(4.dp)); Txt("存在 相册 / 浮光掠影", ClipWarm, 11.sp, maxLines = 1)
        } else Txt(text, if (it.save == 3 || it.stage == 2) ClipBad else ClipWarm, 11.sp, spacing = .3.sp, maxLines = 1)
    }
}

@Composable
private fun Cover(it: TgItem) {
    Box(Modifier.size(44.dp, 58.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = .06f)), contentAlignment = Alignment.Center) {
        it.cover?.let { c -> Image(c, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
            ?: if (it.stage == 0) LoadRing(ClipWarm.copy(alpha = .8f), 14.dp) else Txt("TG", Page.muted, 11.sp)
        it.vids.firstOrNull()?.seconds?.takeIf { s -> s > 0 && it.vids.size == 1 }?.let { s ->
            Txt("%d:%02d".format(s / 60, s % 60), Page.ink, 9.sp, Modifier.align(Alignment.BottomEnd).padding(3.dp).background(Color.Black.copy(alpha = .45f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp))
        }
    }
}

@Composable
private fun Progress(it: TgItem) {
    val f by animateFloatAsState(it.frac, tween(200), label = "tgFrac")
    Box(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = .08f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(f.coerceIn(0f, 1f)).background(ClipWarm))
    }
    Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
        Txt(if (it.speed > 0) mb(it.speed) + "/s" else "", Page.muted, 10.5.sp, Modifier.weight(1f))
        Pressable({ TgStore.stop(it) }, label = "停下") { _ -> Txt("停下", Page.muted, 10.5.sp) }
    }
}

/** 一条里有好几个视频：勾要哪几个（默认全勾），下面是合计和「存这 N 个」。 */
@Composable
private fun TgPick(it: TgItem, onGo: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        it.vids.forEach { v ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { v.on = !v.on }.padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).background(if (v.on) ClipWarm else Color.White.copy(alpha = .1f)), contentAlignment = Alignment.Center) {
                    if (v.on) DrawnCheck(Color(0xFF2A211B), 14.dp)
                }
                Spacer(Modifier.width(10.dp))
                Txt(v.name.substringBeforeLast('.'), Page.ink, 13.sp, Modifier.weight(1f), maxLines = 1)
                Txt(listOfNotNull(v.seconds.takeIf { s -> s > 0 }?.let { s -> "%d:%02d".format(s / 60, s % 60) }, v.size.takeIf { s -> s > 0 }?.let { s -> mb(s) }).joinToString(" · "), Page.muted, 11.5.sp)
            }
        }
        val on = it.vids.count { v -> v.on }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(if (on == 0) "一个都没选" else "$on 个 · 存进 相册/浮光掠影", Page.muted, 11.5.sp, Modifier.weight(1f))
            Btn(if (on == 0) "存" else "存这 $on 个", true, enabled = on > 0, onClick = onGo)
        }
    }
}

@Composable
private fun Btn(text: String, main: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    PressSurface(
        onClick, Modifier.height(30.dp), shape = RoundedCornerShape(8.dp), enabled = enabled,
        fill = if (main) ClipWarm else Color.White.copy(alpha = .08f), pressedFill = if (main) ClipWarm.copy(alpha = .85f) else Color.White.copy(alpha = .14f),
        pressScale = .95f, label = text,
    ) { Txt(text, if (main) Color(0xFF2A211B) else Page.ink, 12.sp, Modifier.padding(horizontal = 12.dp)) }
}

/** 挂在消息下面的 Telegram 卡（和抖音卡、网盘卡同一个位置）。 */
@Composable
internal fun TgCard(it: TgItem) {
    Box(Modifier.widthIn(max = 420.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(rgba(30, 33, 42, .7f))) { TgBody(it, pill = false) }
}
