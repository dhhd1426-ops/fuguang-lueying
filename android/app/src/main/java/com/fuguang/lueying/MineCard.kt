package com.fuguang.lueying

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
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
    /** 去二级页：卡片直接淡出，不走「缩回卡片 → 收进头像」。 */
    var leaving = false
    fun go() { leaving = true; open = false }
}

private val arrowOut = Ic("M7 17L17 7M9 7h8v8")
private val person = Ic("M5.5 19.5c.9-3.4 3.4-5.3 6.5-5.3s5.6 1.9 6.5 5.3", circles = listOf(floatArrayOf(12f, 8.5f, 3.4f)))
private val chevron = Ic("M9.5 6l6 6-6 6")
private val warm = Color(0xFFE9B48E)
private val warmInk = Color(0xFF2A211B)
private val cardFill = Color(0xFF22262F)
private const val RISE_MS = 570f

/**
 * 「我的」：从头像长成停在视觉中心（略偏上）的卡片，原路缩回头像；右上角 ↗ 展开成整页，转成 ↙ 再点缩回卡片。
 * 整页时关掉：先缩回卡片，再收进头像。onGo 去助理 / MCP 这些二级页。
 */
@Composable
fun MineCardLayer(onGo: (String) -> Unit) {
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
            if (MineCard.leaving) {
                p.animateTo(0f, tween(160, easing = Curves.accel))
                f.snapTo(0f); MineCard.full = false; MineCard.leaving = false
            } else {
                if (f.value > 0f) { MineCard.full = false; f.animateTo(0f, tween(440, easing = Curves.standard)) }
                p.animateTo(0f, tween(200, easing = Curves.accel))
            }
        }
    }
    LaunchedEffect(MineCard.full) {
        if (MineCard.open) f.animateTo(if (MineCard.full) 1f else 0f, tween(440, easing = Curves.standard))
    }
    BackHandler(enabled = MineCard.open) { if (MineCard.full) MineCard.full = false else MineCard.open = false }
    if (!MineCard.open && p.value == 0f) return

    val density = LocalDensity.current
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val pv = p.value
        val fv = f.value
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = pv.coerceIn(0f, 1f) }
                .background(rgba(6, 8, 12, .55f))
                .pointerInput(Unit) { detectTapGestures { MineCard.open = false } },
        )
        var natH by remember { mutableStateOf(0.dp) }
        val cardW = min(340.dp, maxWidth - 40.dp)
        val left0 = (maxWidth - cardW) / 2
        val top0 = ((maxHeight * .94f - natH) / 2).coerceAtLeast(statusTop + 12.dp)
        val x = lerp(left0, 0.dp, fv)
        val y = lerp(top0, 0.dp, fv)
        val w = lerp(cardW, maxWidth, fv)
        val h = lerp(natH, maxHeight, fv)
        Box(
            Modifier.offset(x, y).width(w)
                .then(if (fv > 0f) Modifier.height(h) else Modifier.onSizeChanged { natH = with(density) { it.height.toDp() } })
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
            Column(
                Modifier.fillMaxWidth()
                    .then(if (fv > 0f) Modifier.fillMaxSize() else Modifier)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = 12.dp, end = 12.dp,
                        top = lerp(18.dp, statusTop + 22.dp, fv),
                        bottom = 12.dp + navBottom * fv,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                    MineCardBody(fv, rise.value, onGo)
                }
            }
        }
    }
}

/** 一项按顺序浮出：开卡后 70ms 起，每项晚 40ms，各走 340ms。 */
private fun Modifier.rise(i: Int, t: Float) = graphicsLayer {
    val k = ((t * RISE_MS - (70 + 40 * i)) / 340f).coerceIn(0f, 1f)
    val e = Curves.decel.transform(k)
    alpha = e
    translationY = (1f - e) * 8.dp.toPx()
}

@Composable
private fun MineCardBody(fv: Float, t: Float, onGo: (String) -> Unit) {
    val me = Memory.userName.trim()
    Row(Modifier.fillMaxWidth().padding(start = 6.dp, bottom = 14.dp).rise(0, t), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(38.dp).graphicsLayer { scaleX = 1f + .15f * fv; scaleY = scaleX }
                .background(Brush.linearGradient(listOf(warm, Color(0xFFB97F62))), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Txt(me.takeLast(1).ifEmpty { "你" }, warmInk, 16.sp, family = Serif) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Txt("我的", Page.ink, 19.sp, spacing = ls(2.6f, 19f), family = Serif)
            Spacer(Modifier.height(2.dp))
            Txt("${me.ifEmpty { "你" }} · 在这台手机上", Page.muted.copy(alpha = .62f), 11.5.sp, maxLines = 1)
        }
        PressSurface(
            { MineCard.full = !MineCard.full },
            Modifier.align(Alignment.Top).offset(x = 6.dp, y = (-14).dp).size(44.dp),
            shape = CircleShape, fill = Color.Transparent, pressScale = .9f,
            label = if (MineCard.full) "缩回卡片" else "展开成整页",
        ) {
            SvgIcon(arrowOut, Page.muted, 17.dp, stroke = 1.5f, modifier = Modifier.align(Alignment.Center).graphicsLayer { rotationZ = 180f * fv })
        }
    }
    CardRow(1, t, "助理", "名字、提示词、记忆", { onGo("assistant") }, glyph = { SvgIcon(person, warm, 19.dp) }) {
        PersonaDot(Memory.active, 20.dp)
        Spacer(Modifier.width(6.dp))
        Txt(Memory.active.name, Page.muted, 13.5.sp, Modifier.widthIn(max = 96.dp), maxLines = 1)
        Spacer(Modifier.width(4.dp))
        SvgIcon(chevron, Page.muted.copy(alpha = .6f), 15.dp, stroke = 1.8f)
    }
    Txt("连接器", Page.muted, 11.sp, Modifier.padding(start = 8.dp, top = 14.dp, bottom = 8.dp).rise(2, t), spacing = ls(2.75f, 11f))
    YuRow(3, t)
    Spacer(Modifier.height(6.dp))
    val n = Mcp.servers.size
    val on = Mcp.enabled().size
    CardRow(
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

@Composable
private fun CardRow(
    i: Int, t: Float, title: String, sub: String, onClick: () -> Unit,
    glyph: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
) {
    PressSurface(onClick, Modifier.fillMaxWidth().rise(i, t), shape = RoundedCornerShape(16.dp), pressScale = .985f, label = title) {
        RowBody(title, sub, glyph, trailing)
    }
}

@Composable
private fun RowBody(title: String, sub: String, glyph: @Composable () -> Unit, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).background(rgba(233, 180, 142, .1f), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) { glyph() }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Txt(title, Page.ink, 15.sp, maxLines = 1)
            Spacer(Modifier.height(3.dp))
            Txt(sub, Page.muted, 12.sp, maxLines = 1)
        }
        Spacer(Modifier.width(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) { trailing() }
    }
}

/** 羽：连接会跳到羽那边授权，回来再查一次状态。 */
@Composable
private fun YuRow(i: Int, t: Float) {
    val scope = rememberCoroutineScope()
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        scope.launch { Yu.refresh() }
        onPauseOrDispose { }
    }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { scope.launch { Yu.refresh() } }
    val state = Yu.state
    val sub = when (state) { "on" -> "助理可以查阅 ${Yu.notes} 篇笔记"; "missing" -> "需要先安装羽"; else -> "让助理读取你的笔记" }
    Box(Modifier.fillMaxWidth().rise(i, t).background(CardBlock, RoundedCornerShape(16.dp))) {
        RowBody("羽", sub, glyph = { Txt("羽", warm, 17.sp, family = Serif) }) {
            when (state) {
                "on" -> {
                    Box(Modifier.size(6.dp).background(warm, CircleShape))
                    Spacer(Modifier.width(7.dp))
                    Txt("已连接", Page.muted, 13.sp)
                }
                "missing" -> Txt("未安装", Page.muted, 13.sp)
                else -> PressSurface(
                    { runCatching { launcher.launch(Yu.connectIntent()) }.onFailure { scope.launch { Yu.refresh() } } },
                    fill = warm, pressedFill = warm.copy(alpha = .75f), ripple = warmInk, pressScale = .93f, label = "连接羽",
                ) { Txt("连接", warmInk, 13.5.sp, Modifier.padding(horizontal = 15.dp, vertical = 8.dp)) }
            }
        }
    }
}
