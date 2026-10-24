package com.fuguang.lueying

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Warm = ClipWarm
private val Bad = ClipBad
private val OnWarmInk = Color(0xFF2A211B)

/**
 * 网盘分享里面那一块：长条展开和聊天卡片共用。头（哪家 · 标题）→ 要登录 / 借道说明 → 文件树 → 底部摘要 + 主按钮。
 * 主按钮四段反馈（照 dongxiaosheji 01-done-burst）：进度填满按钮 → 画 ✓ → 停一拍迸杏色光点 + 轻震 → 「打开 · 在下载里看」。
 */
@Composable
fun PanPane(it: PanItem, head: Boolean = true, onClose: () -> Unit) {
    val act = LocalContext.current.activity()
    val haptic = LocalHapticFeedback.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    var burst by remember { mutableStateOf<Offset?>(null) }
    val dx = remember { Animatable(0f) }
    val seen = remember { it.doneTick }
    LaunchedEffect(it.doneTick) {
        if (it.doneTick > seen) { kotlinx.coroutines.delay(260); burst = Offset(size.width * .78f, size.height - 30f); haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
    }
    LaunchedEffect(it.save, it.stage) { if (it.save == 3 || it.stage == 2) dx.animateTo(0f, keyframes { durationMillis = 300; -5f at 75; 5f at 150; -2f at 225 }) }

    Box(Modifier.fillMaxWidth().onSizeChanged { s -> size = s }) {
        Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp, top = 4.dp).graphicsLayer { translationX = dx.value * density }, verticalArrangement = Arrangement.spacedBy(9.dp)) {
            // 长条里顶上那行已经写了哪家和标题，这里不再重复
            if (head) Head(it)
            AnimatedContent(it.stage, transitionSpec = { fadeIn(tween(240, 60)) togetherWith fadeOut(tween(120)) }, label = "panStage") { st ->
                when (st) {
                    0 -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { repeat(3) { i -> Box(Modifier.fillMaxWidth().height(32.dp).clip(RoundedCornerShape(9.dp)).background(Color.White.copy(alpha = .06f - i * .015f))) } }
                    2 -> Broken(it, onClose)
                    else -> Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        if (PanStore.needLogin(it) && it.save != 2) LoginRow(it)
                        if (it.share.disk.borrow && it.save == 0) Note("${it.share.disk.label}不让直接下。会先临时放进你网盘里的「浮光临时」，拿到地址就删，你不用管。")
                        Files(it)
                        Foot(it, act)
                    }
                }
            }
        }
        burst?.let { b -> Burst(b, Modifier.matchParentSize()) { burst = null } }
    }
}

@Composable
private fun Head(it: PanItem) {
    val d = it.share.disk
    Row(verticalAlignment = Alignment.CenterVertically) {
        DiskMark(d)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Txt(it.title.ifBlank { if (it.stage == 2) "这个分享" else "正在看里面有什么" }, Page.INK, 15.sp, family = Serif, lineHeight = 21.sp, maxLines = 2)
            val sub = d.label + if (it.share.code.isNotEmpty()) " · 提取码 ${it.share.code} 已带上" else ""
            Txt(sub, Page.MUTED, 11.5.sp, Modifier.padding(top = 1.dp), maxLines = 1)
        }
    }
}

@Composable
internal fun DiskMark(d: Disk, s: Int = 34) {
    Box(Modifier.size(s.dp).clip(RoundedCornerShape((s * .3f).dp)).background(Color(d.color)), contentAlignment = Alignment.Center) {
        Txt(d.glyph, OnWarmInk, if (d.glyph.length > 2) 11.sp else 14.sp, family = Serif)
    }
}

@Composable
private fun Note(t: String) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(Color.White.copy(alpha = .04f)).padding(horizontal = 11.dp, vertical = 9.dp)) {
        Txt("◇", Warm, 12.sp); Spacer(Modifier.width(8.dp)); Txt(t, Page.MUTED, 12.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun LoginRow(it: PanItem) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(Warm.copy(alpha = .09f)).padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(if (it.waitingLogin) "登好了会自己回来" else "${it.share.disk.label}要先登录一次", Page.INK, 13.sp, Modifier.weight(1f))
        PanSmall(if (it.waitingLogin) "再开一次" else "去登录") { PanStore.login(it) }
    }
}

/** 打不开：按原因写一句 + 一件能做的事 + 灰色原因码。 */
@Composable
private fun Broken(it: PanItem, onClose: () -> Unit) {
    val e = it.err
    var code by remember { mutableStateOf(it.share.code) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt(e?.why ?: "没打开", Page.INK, 13.sp, lineHeight = 19.sp)
        e?.code?.let { c -> Txt(c, Page.MUTED.copy(alpha = .45f), 10.5.sp) }
        when (e?.kind) {
            PanError.Kind.CODE -> Row(verticalAlignment = Alignment.CenterVertically) {
                Txt("提取码", Page.MUTED, 12.5.sp); Spacer(Modifier.width(8.dp))
                BasicTextField(code, { v -> code = v.filter { ch -> ch.isLetterOrDigit() }.take(8) }, Modifier.width(84.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = .08f)).padding(horizontal = 9.dp, vertical = 7.dp),
                    textStyle = TextStyle(fontFamily = Sans, color = Page.INK, fontSize = 13.sp, letterSpacing = 2.sp), singleLine = true, cursorBrush = SolidColor(Warm))
                Spacer(Modifier.width(8.dp)); PanSmall("再试") { PanStore.retry(it, code) }
            }
            PanError.Kind.LOGIN -> LoginRow(it)
            PanError.Kind.GONE -> Row { PanSmall("好") { onClose() } }
            else -> Row { PanSmall("再试一次") { PanStore.open(it) } }
        }
    }
}

@Composable
private fun Files(it: PanItem) {
    Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
        it.nodes.forEach { n -> Node(it, n) }
    }
}

@Composable
private fun Node(it: PanItem, n: PanNode) {
    val st = PanStore.state(n)
    val busy = it.save == 1
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .then(Modifier.padding(start = (n.depth * 18).dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PressSurface({ if (!busy) { if (n.f.dir) PanStore.expand(it, n) else PanStore.flip(n) } }, Modifier.weight(1f).height(38.dp), RoundedCornerShape(10.dp),
            fill = Color.Transparent, pressedFill = Color.White.copy(alpha = .06f), pressScale = .985f, label = n.f.name) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val rot by animateFloatAsState(if (n.open) 90f else 0f, tween(240, easing = Curves.standard), label = "car")
                Box(Modifier.size(14.dp).graphicsLayer { rotationZ = rot; alpha = if (n.f.dir) 1f else 0f }, contentAlignment = Alignment.Center) { Txt("›", Page.MUTED, 14.sp) }
                Spacer(Modifier.width(4.dp))
                Check(st) { if (!busy) PanStore.flip(n) }
                Spacer(Modifier.width(9.dp))
                Box(Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(Color.White.copy(alpha = .06f)), contentAlignment = Alignment.Center) {
                    if (n.loading) LoadRing(Warm, 11.dp) else Txt(kindGlyph(n.f), Page.MUTED, 10.sp)
                }
                Spacer(Modifier.width(9.dp))
                Txt(n.f.name, Page.INK, 13.5.sp, Modifier.weight(1f), maxLines = 1)
                Txt(if (n.f.dir) (n.kids?.let { k -> "${k.size} 个" } ?: "") else Pan.size(n.f.size), Page.MUTED, 11.5.sp, Modifier.padding(start = 8.dp, end = 4.dp))
            }
        }
    }
    AnimatedVisibility(n.open && n.kids != null, enter = expandVertically(tween(280, easing = Curves.decel)) + fadeIn(tween(200, 60)), exit = shrinkVertically(tween(200, easing = Curves.accel)) + fadeOut(tween(140))) {
        Column { n.kids?.forEach { k -> Node(it, k) } }
    }
}

/** 勾选框：勾上时杏色填满、✓ 一笔画出；勾了一部分是半杏色一道横。 */
@Composable
private fun Check(st: Int, onClick: () -> Unit) {
    val bg by animateColorAsState(when (st) { 1 -> Warm; 2 -> Warm.copy(alpha = .5f); else -> Color.White.copy(alpha = .12f) }, tween(160), label = "ck")
    val p by animateFloatAsState(if (st == 1) 1f else 0f, tween(200, easing = Curves.decel), label = "ckp")
    Pressable(onClick, Modifier.size(28.dp), label = "勾选") { _ ->
        Canvas(Modifier.size(19.dp).clip(RoundedCornerShape(6.dp)).background(bg)) {
            val s = size.minDimension / 12f; val w = 2.2f * s
            if (st == 2) drawLine(OnWarmInk, Offset(3.2f * s, 6f * s), Offset(8.8f * s, 6f * s), w, StrokeCap.Round)
            val a = Offset(2.3f * s, 6.2f * s); val b = Offset(4.7f * s, 8.6f * s); val c = Offset(9.7f * s, 3.3f * s)
            val l1 = (b - a).getDistance(); val l2 = (c - b).getDistance(); val d = p * (l1 + l2)
            if (d > 0f) drawLine(OnWarmInk, a, if (d < l1) a + (b - a) * (d / l1) else b, w, StrokeCap.Round)
            if (d > l1) drawLine(OnWarmInk, b, b + (c - b) * ((d - l1) / l2), w, StrokeCap.Round)
        }
    }
}

private fun kindGlyph(f: PanFile): String {
    if (f.dir) return "夹"
    val m = Pan.mime(f.name)
    return when { m.startsWith("video") -> "片"; m.startsWith("audio") -> "音"; m.startsWith("image") -> "图"; m.contains("zip") || m.contains("rar") || m.contains("7z") -> "包"; else -> "文" }
}

@Composable
private fun Foot(it: PanItem, act: android.app.Activity?) {
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val fs = PanStore.picked(it)
                Txt(if (it.save == 2) "${it.got} 个下好了 · ${Pan.size(it.gotBytes)}" else PanStore.summary(it), Page.MUTED, 12.sp, maxLines = 1)
                val where = when { fs.isEmpty() -> ""; fs.all { f -> Torrent.isMedia(f.name) } -> "存进相册「浮光掠影」"; fs.any { f -> Torrent.isMedia(f.name) } -> "视频进相册，其他进下载"; else -> "存进 下载/浮光掠影" }
                if (where.isNotEmpty() && it.save != 2) Txt(where, Page.MUTED.copy(alpha = .5f), 11.sp, maxLines = 1)
            }
            Spacer(Modifier.width(10.dp))
            if (it.save == 2) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PanSmall("打开") { openPan(ctx, it) }
                PanSmall("在下载里看") { Downloads.open = true }
            } else MainButton(it) {
                when {
                    it.save == 1 -> PanStore.stop(it)
                    PanStore.needLogin(it) -> PanStore.login(it)
                    else -> PanStore.download(it, act)
                }
            }
        }
        // 百度只给超级会员提速：说清楚是谁限的，免得以为卡住了
        if (it.save == 1 && it.share.disk == Disk.BAIDU && !PanBaiduLocate.svip && it.speed in 1 until 600_000)
            Txt("百度只给超级会员提速，普通账号多开反被限得更慢", Page.MUTED.copy(alpha = .55f), 11.sp, maxLines = 1)
        if (it.save == 3) it.why?.let { e ->
            Txt(e.why, Bad, 12.sp, lineHeight = 17.sp, maxLines = 3)
            Txt(e.code, Page.MUTED.copy(alpha = .45f), 10.5.sp)
        }
    }
}

@Composable
private fun MainButton(it: PanItem, onClick: () -> Unit) {
    val f by animateFloatAsState(it.frac, tween(200), label = "panFrac")
    val empty = PanStore.picked(it).isEmpty() && PanStore.pickedDirs(it).isEmpty()
    val fill = when (it.save) { 1 -> Color(0xFF3A3430); 3 -> Bad.copy(alpha = .16f); else -> Warm }
    val ink = when (it.save) { 1 -> Page.INK; 3 -> Bad; else -> OnWarmInk }
    PressSurface(onClick, Modifier.width(156.dp).height(40.dp), RoundedCornerShape(20.dp), enabled = !empty || it.save == 1,
        fill = fill, pressedFill = fill.copy(alpha = fill.alpha * .85f), pressScale = .97f, label = "下载") {
        if (it.save == 1) Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(f.coerceIn(0f, 1f)).background(Warm.copy(alpha = .38f)))
        AnimatedContent(it.save, transitionSpec = { fadeIn(tween(220, 60)) togetherWith fadeOut(tween(120)) }, label = "panBtn") { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (s) {
                    1 -> { LoadRing(Warm, 13.dp); Spacer(Modifier.width(7.dp)); Txt(if (it.frac > 0f) "${(it.frac * 100).toInt()}%" + (if (it.speed > 0) " · " + rate(it.speed) else "") else it.step.ifBlank { "准备" }, ink, 13.sp, maxLines = 1) }
                    2 -> { DrawnCheck(Warm, 16.dp); Spacer(Modifier.width(5.dp)); Txt("下好了", Warm, 14.sp) }
                    3 -> Txt("再试一次", ink, 14.sp)
                    else -> Txt(if (PanStore.needLogin(it)) "先登录" else "下载", ink, 14.sp, weight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
internal fun PanSmall(text: String, onClick: () -> Unit) {
    PressSurface(onClick, Modifier.height(30.dp), RoundedCornerShape(9.dp), fill = Color.White.copy(alpha = .08f), pressedFill = Color.White.copy(alpha = .14f), pressScale = .95f, label = text) {
        Txt(text, Page.INK, 12.5.sp, Modifier.padding(horizontal = 12.dp))
    }
}

/** 打开下好的第一个；多个时打开下载页。 */
private fun openPan(ctx: Context, it: PanItem) {
    val ds = it.dls.mapNotNull { id -> Downloads.list.firstOrNull { d -> d.id == id && d.state == 3 } }
    if (ds.size != 1) { Downloads.open = true; return }
    val d = ds.first(); val u = Downloads.uri(d) ?: return
    runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, d.media.mime.ifBlank { "*/*" }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { Downloads.open = true }
}

/** 速度：「820 KB/s」「2.4 MB/s」。 */
private fun rate(b: Long): String = if (b >= 1_000_000) "%.1f MB/s".format(b / 1e6) else "${maxOf(1, b / 1000)} KB/s"
