package com.fuguang.lueying

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val Warm = Color(0xFFE9B48E)
val TranslateIc = Ic("M4 5h8M8 3v2M5.5 5c.8 3 3 5.5 6 7M10.5 5c-.6 3-2.6 6-6 8M13 21l4-10 4 10M14.5 17.5h5")

/** 字幕的字：白字、黑描边、一点阴影，什么背景上都看得清（底色模式下不描边）。 */
@Composable
private fun SubText(text: String, size: TextUnit, weight: FontWeight, color: Color, band: Boolean) {
    val base = TextStyle(fontSize = size, lineHeight = size * 1.45f, fontWeight = weight, textAlign = TextAlign.Center, letterSpacing = .02.sp)
    Box(contentAlignment = Alignment.Center) {
        if (!band) BasicText(text, style = base.copy(color = Color.Black.copy(alpha = .75f), drawStyle = Stroke(width = 5f)))
        BasicText(text, style = base.copy(color = color, shadow = if (band) null else Shadow(Color.Black.copy(alpha = .7f), Offset(0f, 2f), 8f)))
    }
}

/**
 * 悬浮字幕：像电影字幕，没有框，居中压在下面，只显示当前这一句；上面大字是翻译，下面小字是原文。
 * 换句：旧的往上淡出（220ms accel），新的从下面浮上来（280ms decel）。
 * 点一下：上面浮出一排小钮，3 秒后收起；按住上下拖换位置；「收起」变成右下角一颗会呼吸的小球。
 */
@Composable
fun BoxScope.LiveCaptionOverlay(bottomGap: androidx.compose.ui.unit.Dp) {
    if (!Live.on) return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var ctl by remember { mutableIntStateOf(0) }       // 每点一次加一，3 秒后收
    var showCtl by remember { mutableStateOf(false) }
    LaunchedEffect(ctl) { if (ctl > 0) { showCtl = true; delay(3000); showCtl = false } }
    var dy by remember { mutableFloatStateOf(0f) }
    val sizes = listOf(17.sp, 19.sp, 22.sp)
    val big = sizes[Live.fz.coerceIn(0, 2)]

    if (Live.folded) {
        val k by animateFloatAsState(1f, tween(340, easing = Curves.decel), label = "orb")
        PressSurface({ Live.folded = false }, Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = bottomGap + 8.dp).size(48.dp).graphicsLayer { scaleX = k; scaleY = k },
            shape = CircleShape, fill = Color(0xDB0E1014), pressScale = .9f, label = "展开字幕", edge = Warm.copy(alpha = .35f)) {
            SvgIcon(TranslateIc, Warm, 20.dp, stroke = 1.7f)
        }
        return
    }
    Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = bottomGap + 10.dp)
            .offset { IntOffset(0, dy.roundToInt()) }
            .pointerInput(Unit) { detectTapGestures { ctl++ } }
            .pointerInput(Unit) { detectVerticalDragGestures { _, d -> dy = (dy + d).coerceIn(-size.height * 6f, 0f) } },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 一排小钮
        val ca by animateFloatAsState(if (showCtl) 1f else 0f, tween(if (showCtl) 260 else 200, easing = Curves.decel), label = "ctl")
        if (ca > 0f) Row(
            Modifier.padding(bottom = 8.dp).graphicsLayer { alpha = ca; translationY = (1f - ca) * 6.dp.toPx() }
                .background(Color(0xE6141619), RoundedCornerShape(50)).padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.padding(start = 8.dp, end = 4.dp).size(6.dp).background(Color(0xFFFF6B5A), CircleShape))
            Txt(Live.to.let { if (it == "中文") "→ 中" else if (it == "English") "→ 英" else "→ 日" }, Color.White.copy(alpha = .55f), 11.5.sp, Modifier.padding(end = 4.dp))
            listOf(
                (if (Live.showO) "原文 开" else "原文 关") to { Live.showO = !Live.showO; Live.save() },
                "字号" to { Live.fz = (Live.fz + 1) % 3; Live.save() },
                (if (Live.band) "底色 开" else "底色") to { Live.band = !Live.band; Live.save() },
                "收起" to { Live.folded = true; showCtl = false },
                "停" to { Live.stop(ctx.applicationContext) },
            ).forEach { (t, act) ->
                PressSurface({ act(); ctl++ }, Modifier.height(30.dp), shape = RoundedCornerShape(50), fill = Color.Transparent, pressScale = .92f, label = t) {
                    Txt(t, Color.White.copy(alpha = .86f), 12.sp, Modifier.padding(horizontal = 10.dp), maxLines = 1)
                }
            }
        }
        // 字幕本身
        AnimatedContent(
            Live.seq,
            transitionSpec = {
                (fadeIn(tween(280, easing = Curves.decel)) + slideInVertically(tween(280, easing = Curves.decel)) { it / 6 }) togetherWith
                    (fadeOut(tween(220, easing = Curves.accel)) + slideOutVertically(tween(220, easing = Curves.accel)) { -it / 6 })
            },
            label = "sub",
        ) { _ ->
            val trans = Live.trans; val orig = Live.orig
            Column(
                Modifier.widthIn(max = 640.dp).then(if (Live.band) Modifier.background(Color.Black.copy(alpha = .45f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp) else Modifier),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when {
                    orig.isEmpty() && trans.isEmpty() -> SubText(Live.status, big * .7f, FontWeight.Medium, Color.White.copy(alpha = .8f), Live.band)
                    else -> {
                        if (trans.isNotEmpty() && trans != orig) SubText(trans, big, FontWeight.SemiBold, Color.White, Live.band)
                        if (Live.showO || trans.isEmpty() || trans == orig) SubText(orig, if (trans.isEmpty() || trans == orig) big * .85f else big * .66f, FontWeight.Medium, Color.White.copy(alpha = .82f), Live.band)
                    }
                }
            }
        }
        if (Live.prep in 0f..0.999f) Box(Modifier.padding(top = 6.dp).width(120.dp).height(3.dp).background(Color.White.copy(alpha = .2f), RoundedCornerShape(2.dp))) {
            Box(Modifier.fillMaxWidth(Live.prep).height(3.dp).background(Warm, RoundedCornerShape(2.dp)))
        }
    }
}

/** 开始前选一下：听什么、翻成什么、原文要不要一起显示。 */
@Composable
fun BoxScope.TranslateSheet(open: Boolean, onClose: () -> Unit, onStart: () -> Unit) {
    val k by animateFloatAsState(if (open) 1f else 0f, tween(if (open) 340 else 220, easing = if (open) Curves.decel else Curves.accel), label = "trs")
    if (k <= 0f) return
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = k }.background(Color(0x80000000)).pointerInput(Unit) { detectTapGestures { onClose() } })
    Column(
        Modifier.align(Alignment.BottomCenter).widthIn(max = 640.dp).fillMaxWidth()
            .graphicsLayer { translationY = (1f - k) * size.height }
            .background(Color(0xFF1F232A), RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .pointerInput(Unit) { detectTapGestures { } }
            .windowInsetsPadding(WindowInsets.navigationBars).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp).size(40.dp, 4.dp).background(Color.White.copy(alpha = .25f), RoundedCornerShape(2.dp)))
        Txt("同声翻译", Page.ink, 16.sp)
        Txt("把听到的话认成字，她一句句翻给你，像字幕一样浮在下面。", Page.muted, 12.sp, Modifier.padding(top = 2.dp, bottom = 12.dp), lineHeight = 18.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Triple("page", "网页里的声音", "视频、直播、播客说的话"), Triple("mic", "我和别人说的话", "用麦克风，当面翻译")).forEach { (id, t, s) ->
                val on = Live.src == id
                PressSurface({ Live.src = id; Live.save() }, Modifier.weight(1f), shape = RoundedCornerShape(14.dp), fill = if (on) Warm.copy(alpha = .13f) else Color.White.copy(alpha = .05f), pressScale = .97f, label = t) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Txt(t, if (on) Page.ink else Page.ink.copy(alpha = .8f), 14.sp, maxLines = 1)
                        Txt(s, Page.muted.copy(alpha = .6f), 11.5.sp, Modifier.padding(top = 3.dp), maxLines = 2)
                    }
                }
            }
        }
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Txt("翻成", Page.muted, 12.5.sp, Modifier.padding(end = 2.dp))
            listOf("中文", "English", "日本語").forEach { l -> ChoiceChip(l, Live.to == l) { Live.to = l; Live.save() } }
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Txt("原文", Page.muted, 12.5.sp, Modifier.padding(end = 2.dp))
            ChoiceChip(if (Live.showO) "一起显示" else "不显示", Live.showO) { Live.showO = !Live.showO; Live.save() }
        }
        Txt(
            if (Live.src == "page") "第一次用要点一下系统的「开始录制」，只录这个 App 自己放出来的声音，不录画面。认字在手机上做，录音不上传；认的是中文和英文。"
            else "要允许用麦克风。认字在手机上做，录音不上传；认的是中文和英文。",
            Page.muted.copy(alpha = .55f), 11.5.sp, Modifier.padding(top = 10.dp, bottom = 12.dp), lineHeight = 18.sp,
        )
        CardButton(0, BtnTone.Primary, "开始翻译", onStart, Modifier.fillMaxWidth()) { _, ink -> BtnLabel("开始翻译", ink) { SvgIcon(TranslateIc, ink, 16.dp, stroke = 1.8f) } }
    }
}

