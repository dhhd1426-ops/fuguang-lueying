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

/* 「我的」卡片首页的行：色块、左边方形图标、右边状态。 */

private val warm = Color(0xFFE9B48E)
private val warmInk = Color(0xFF2A211B)

@Composable
internal fun MineRow(
    i: Int, t: Float, title: String, sub: String, onClick: () -> Unit,
    glyph: @Composable () -> Unit,
    subSlot: (@Composable () -> Unit)? = null,
    trailing: @Composable () -> Unit,
) {
    PressSurface(onClick, Modifier.fillMaxWidth().mineRise(i, t), shape = RoundedCornerShape(16.dp), pressScale = .985f, label = title) {
        RowBody(title, sub, glyph, subSlot, trailing)
    }
}

@Composable
private fun RowBody(title: String, sub: String, glyph: @Composable () -> Unit, subSlot: (@Composable () -> Unit)? = null, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).background(rgba(233, 180, 142, .1f), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) { glyph() }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Txt(title, Page.ink, 15.sp, maxLines = 1)
            Spacer(Modifier.height(3.dp))
            if (subSlot != null) subSlot() else Txt(sub, Page.muted, 12.sp, maxLines = 1)
        }
        Spacer(Modifier.width(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) { trailing() }
    }
}

/** 羽：连接会跳到羽那边授权，回来再查一次状态。 */
@Composable
internal fun MineYuRow(i: Int, t: Float) {
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
    Box(Modifier.fillMaxWidth().mineRise(i, t).background(CardBlock, RoundedCornerShape(16.dp))) {
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
