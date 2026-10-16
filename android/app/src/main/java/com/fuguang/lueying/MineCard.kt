package com.fuguang.lueying

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** 「我的」卡片的开关：侧栏头像记下自己的中心（卡片从这里长出、缩回这里），点一下 open。 */
object MineCard {
    var open by mutableStateOf(false)
    var full by mutableStateOf(false)
    var from by mutableStateOf(Offset.Unspecified)
}

private val arrowOut = Ic("M7 17L17 7M9 7h8v8")
private val person = Ic("M5.5 19.5c.9-3.4 3.4-5.3 6.5-5.3s5.6 1.9 6.5 5.3", circles = listOf(floatArrayOf(12f, 8.5f, 3.4f)))
private val chevron = Ic("M9.5 6l6 6-6 6")
private val warm = Color(0xFFE9B48E)
private val warmInk = Color(0xFF2A211B)
private val cardFill = Color(0xFF22262F)
private const val RISE_MS = 570f

/** 页面深度：往深处走新页从右边进，往回走从左边进。 */
private fun depth(s: String) = when {
    s == "mine" -> 0
    s == "assistant" || s == "mcp" -> 1
    s == "persona" || s.startsWith("mcp/") -> 2
    s == "providers" -> 3
    else -> 4
}

/**
 * 「我的」：从头像长成停在视觉中心（略偏上）的卡片，原路缩回头像。助理、记忆、MCP 这些二级页也在卡片里一页页推进，
 * 卡片高度跟着走（首页贴内容，二级页 80% 屏高）。右上角 ↗ 随时展开成整页，转成 ↙ 再点缩回；整页时关掉先缩回卡片再收进头像。
 */
@Composable
fun MineCardLayer(store: AssistantStore, nav: MineNav) {
    val p = remember { Animatable(0f) }
    val f = remember { Animatable(0f) }
    val rise = remember { Animatable(0f) }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(MineCard.open) {
        if (MineCard.open) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            launch { rise.snapTo(0f); rise.animateTo(1f, tween(RISE_MS.toInt(), easing = LinearEasing)) }
            p.animateTo(1f, spring(dampingRatio = .7f, stiffness = 380f))
        } else {
            if (f.value > 0f) { MineCard.full = false; f.animateTo(0f, tween(440, easing = Curves.standard)) }
            p.animateTo(0f, tween(200, easing = Curves.accel))
            nav.screen = "mine"; nav.persona = ""
        }
    }
    LaunchedEffect(MineCard.full) {
        if (MineCard.open) f.animateTo(if (MineCard.full) 1f else 0f, tween(440, easing = Curves.standard))
    }
    BackHandler(enabled = MineCard.open) {
        when {
            MineCard.full -> MineCard.full = false
            nav.screen != "mine" -> nav.back()
            else -> MineCard.open = false
        }
    }
    if (!MineCard.open && p.value == 0f) return

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        val pv = p.value
        val fv = f.value
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = pv.coerceIn(0f, 1f) }
                .background(rgba(6, 8, 12, .55f))
                .pointerInput(Unit) { detectTapGestures { MineCard.open = false } },
        )
        var natH by remember { mutableStateOf(0.dp) }
        val sub = nav.screen != "mine"
        val target = if (sub) min(maxHeight * .8f, 760.dp) else natH.coerceAtMost(maxHeight * .8f)
        val hAnim = remember { Animatable(target, Dp.VectorConverter) }
        LaunchedEffect(target) {
            if (p.value < .05f || hAnim.value == 0.dp) hAnim.snapTo(target)
            else hAnim.animateTo(target, tween(380, easing = Curves.standard))
        }
        val cardH = hAnim.value
        val cardW = min(if (sub) 400.dp else 340.dp, maxWidth - 32.dp)
        val wAnim by animateDpAsState(cardW, tween(380, easing = Curves.standard), label = "cardW")
        val left0 = (maxWidth - wAnim) / 2
        val top0 = ((maxHeight * .94f - cardH) / 2).coerceAtLeast(statusTop + 12.dp)
        val x = lerp(left0, 0.dp, fv)
        val y = lerp(top0, 0.dp, fv)
        val w = lerp(wAnim, maxWidth, fv)
        val h = lerp(cardH, maxHeight, fv)
        Box(
            Modifier.offset(x, y).size(w, h)
                .graphicsLayer {
                    val s = .16f + .84f * pv
                    scaleX = s; scaleY = s
                    alpha = (pv * 1.8f).coerceIn(0f, 1f)
                    val from = MineCard.from
                    transformOrigin = if (from.isSpecified && size.width > 0f && size.height > 0f)
                        TransformOrigin((from.x - x.toPx()) / size.width, (from.y - y.toPx()) / size.height)
                    else TransformOrigin(.5f, 1.2f)
                    shape = RoundedCornerShape(lerp(24.dp, 0.dp, fv))
                    clip = true
                    shadowElevation = 24.dp.toPx() * (1f - fv)
                }
                .background(cardFill)
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            val pad = Modifier.fillMaxSize().padding(
                start = 14.dp, end = 14.dp,
                top = lerp(18.dp, statusTop + 22.dp, fv),
                bottom = 12.dp + navBottom * fv,
            )
            AnimatedContent(
                nav.screen,
                Modifier.fillMaxSize(),
                transitionSpec = {
                    val fwd = depth(targetState) >= depth(initialState)
                    val dx = if (fwd) 1 else -1
                    (slideInHorizontally(tween(320, easing = Curves.decel)) { dx * it / 9 } + fadeIn(tween(260)))
                        .togetherWith(slideOutHorizontally(tween(240, easing = Curves.accel)) { -dx * it / 9 } + fadeOut(tween(180)))
                        .using(SizeTransform(clip = false) { _, _ -> snap() })
                },
                contentAlignment = Alignment.TopCenter,
                label = "minePage",
            ) { screen ->
                Box(pad, contentAlignment = Alignment.TopCenter) {
                    if (screen == "mine") {
                        Column(
                            Modifier.widthIn(max = 560.dp).fillMaxWidth()
                                .wrapContentHeight(Alignment.Top, unbounded = true)
                                .onSizeChanged { natH = with(density) { it.height.toDp() } + 30.dp },
                        ) { MineCardBody(rise.value) { nav.screen = it } }
                    } else {
                        Column(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                            CompositionLocalProvider(LocalTitleEnd provides 36.dp) { MineSub(store, screen, true, 0.dp, nav) }
                        }
                    }
                }
            }
            // ↗ 钉在右上角，换页也不动
            PressSurface(
                { MineCard.full = !MineCard.full },
                Modifier.align(Alignment.TopEnd).padding(top = lerp(4.dp, statusTop + 8.dp, fv), end = 4.dp).size(44.dp),
                shape = CircleShape, fill = Color.Transparent, pressScale = .9f,
                label = if (MineCard.full) "缩回卡片" else "展开成整页",
            ) {
                SvgIcon(arrowOut, Page.muted, 17.dp, stroke = 1.5f, modifier = Modifier.align(Alignment.Center).graphicsLayer { rotationZ = 180f * fv })
            }
        }
        // 添加记忆的卡片：画在最上层
        MemoryCardHost()
    }
}

/** 一项按顺序浮出：开卡后 70ms 起，每项晚 40ms，各走 340ms。 */
internal fun Modifier.mineRise(i: Int, t: Float) = graphicsLayer {
    val k = ((t * RISE_MS - (70 + 40 * i)) / 340f).coerceIn(0f, 1f)
    val e = Curves.decel.transform(k)
    alpha = e
    translationY = (1f - e) * 8.dp.toPx()
}

@Composable
private fun MineCardBody(t: Float, onGo: (String) -> Unit) {
    val me = Memory.userName.trim()
    Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 40.dp, bottom = 14.dp).mineRise(0, t), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(38.dp)
                .background(Brush.linearGradient(listOf(warm, Color(0xFFB97F62))), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Txt(me.takeLast(1).ifEmpty { "你" }, warmInk, 16.sp, family = Serif) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Txt("我的", Page.ink, 19.sp, spacing = ls(2.6f, 19f), family = Serif)
            Spacer(Modifier.height(2.dp))
            Txt("${me.ifEmpty { "你" }} · 在这台手机上", Page.muted.copy(alpha = .62f), 11.5.sp, maxLines = 1)
        }
    }
    MineRow(1, t, "助理", "名字、提示词、记忆", { onGo("assistant") }, glyph = { SvgIcon(person, warm, 19.dp) }) {
        PersonaDot(Memory.active, 20.dp)
        Spacer(Modifier.width(6.dp))
        Txt(Memory.active.name, Page.muted, 13.5.sp, Modifier.widthIn(max = 96.dp), maxLines = 1)
        Spacer(Modifier.width(4.dp))
        SvgIcon(chevron, Page.muted.copy(alpha = .6f), 15.dp, stroke = 1.8f)
    }
    Txt("连接器", Page.muted, 11.sp, Modifier.padding(start = 8.dp, top = 14.dp, bottom = 8.dp).mineRise(2, t), spacing = ls(2.75f, 11f))
    MineYuRow(3, t)
    Spacer(Modifier.height(6.dp))
    val n = Mcp.servers.size
    val on = Mcp.enabled().size
    MineRow(
        4, t, "MCP 服务",
        if (n == 0) "地图、车票、搜索……接上就能用" else Mcp.servers.filter { it.enabled }.joinToString("、") { it.name }.ifEmpty { "都关着" },
        { onGo("mcp") },
        glyph = { Txt("M", warm, 16.sp, family = Serif) },
    ) {
        if (n > 0) {
            Box(Modifier.size(6.dp).background(if (on > 0) warm else Page.muted.copy(alpha = .4f), CircleShape))
            Spacer(Modifier.width(7.dp))
            Txt("$on 个", Page.muted, 13.sp)
            Spacer(Modifier.width(4.dp))
        }
        SvgIcon(chevron, Page.muted.copy(alpha = .6f), 15.dp, stroke = 1.8f)
    }
}
