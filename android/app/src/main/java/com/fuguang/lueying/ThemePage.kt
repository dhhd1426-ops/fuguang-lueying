package com.fuguang.lueying

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 「我的 → 主题」：三行，每行一张小样；点了当场换，返回后那一行写着新名字。 */
@Composable
internal fun ThemePage(onBack: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Column(Modifier.widthIn(max = 560.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
        CardPageHead("主题", onBack = onBack)
        Spacer(Modifier.height(4.dp))
        Skin.options.forEach { o ->
            val on = Skin.id == o.id
            PressSurface(
                { if (!on) { Skin.set(o.id); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } },
                Modifier.fillMaxWidth().padding(bottom = 7.dp), shape = RoundedCornerShape(16.dp), pressScale = .985f, label = o.name,
            ) {
                Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    ThemeMini(o.id, on)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Txt(o.name, Page.ink, 15.sp, maxLines = 1)
                        Spacer(Modifier.height(3.dp))
                        Txt(o.sub, Page.muted, 12.sp, maxLines = 1)
                    }
                    val k by animateFloatAsState(if (on) 1f else 0f, spring(.5f, 500f), label = "tk")
                    if (k > .01f) Box(Modifier.scale(k)) { DrawnCheck(SideWarm, 18.dp) }
                    Spacer(Modifier.width(6.dp))
                }
            }
        }
        Txt("侧栏、悬浮卡片和聊天页跟着换。暗黑优雅、白色不放背景画面；看视频、浏览网页的地方一直是深色。", Page.muted.copy(alpha = .8f), 11.5.sp, Modifier.padding(horizontal = 8.dp, vertical = 8.dp), lineHeight = 19.sp)
    }
}

/** 一张小样：左边侧栏、三道字、右下角新对话药丸；选中的外面一圈杏色。 */
@Composable
private fun ThemeMini(id: String, on: Boolean) {
    val ring by animateFloatAsState(if (on) 1f else 0f, label = "ring")
    val (ground, side, line, pill) = when (id) {
        Skin.DARK -> listOf(Brush.linearGradient(listOf(Color(0xFF1C1C1C), Color(0xFF1C1C1C))), Color(0xFF131313), Color(0xFF4A4A4A), Color(0xFFF5F4EF))
        Skin.LIGHT -> listOf(Brush.linearGradient(listOf(Color(0xFFD9D8D2), Color(0xFFD9D8D2))), Color(0xFFF4F3EE), Color(0xFFB9B8B0), Color(0xFF151513))
        else -> listOf(Brush.linearGradient(listOf(Color(0xFF6C4F5C), Color(0xFF2A3050), Color(0xFF5A4A40))), rgba(40, 40, 52, .55f), rgba(255, 255, 255, .35f), Color(0xFFF4F1EA))
    }
    Canvas(Modifier.size(60.dp).clip(RoundedCornerShape(13.dp))) {
        drawRect(ground as Brush)
        drawRect(side as Color, size = Size(size.width * .58f, size.height))
        val lw = 4.dp.toPx()
        listOf(.2f to .3f, .36f to .38f, .52f to .26f).forEach { (y, w) ->
            drawRoundRect(line as Color, Offset(size.width * .1f, size.height * y), Size(size.width * w, lw), CornerRadius(lw / 2))
        }
        drawRoundRect(pill as Color, Offset(size.width * .61f, size.height * .79f), Size(size.width * .3f, size.height * .12f), CornerRadius(size.height))
        if (ring > 0f) drawRoundRect(SideWarm.copy(alpha = ring), size = size, cornerRadius = CornerRadius(13.dp.toPx()), style = Stroke(2.5.dp.toPx()))
    }
}

/** 「主题」那一行的图标：一个半明半暗的圆。 */
@Composable
internal fun ThemeGlyph(c: Color) {
    Canvas(Modifier.size(18.dp)) {
        val r = size.minDimension / 2 - 1.dp.toPx()
        drawCircle(c, r, style = Stroke(1.5.dp.toPx()))
        drawArc(c, 90f, 180f, true, Offset(center.x - r, center.y - r), Size(r * 2, r * 2))
    }
}
