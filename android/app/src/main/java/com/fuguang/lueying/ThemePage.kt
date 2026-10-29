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

/** 「我的 → 主题」：四行，每行一张小样；点了当场换，返回后那一行写着新名字。选了渐变，下面展开八套配色（ThemeGrad.kt）。 */
@Composable
internal fun ThemePage(onBack: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Column(Modifier.widthIn(max = 560.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
        CardPageHead("主题", onBack = onBack)
        Spacer(Modifier.height(4.dp))
        Column(Modifier.spot("skin")) {
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
        androidx.compose.animation.AnimatedVisibility(
            Skin.grad,
            enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(320, easing = Curves.decel)) + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(240, 80)),
            exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(240, easing = Curves.accel)) + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(140)),
        ) { GradPalettes() }
        }
        Txt("侧栏、悬浮卡片和聊天页跟着换。暗黑优雅、白色、渐变不放背景画面；看视频、浏览网页的地方一直是深色。", Page.muted.copy(alpha = .8f), 11.5.sp, Modifier.padding(horizontal = 8.dp, vertical = 8.dp), lineHeight = 19.sp)
        Column(Modifier.spot("rail")) { SwitchSection() }
        Column(Modifier.spot("card")) { CardSection() }
        Column(Modifier.spot("icon")) { IconSection() }
        Spacer(Modifier.height(8.dp))
    }
}

/** 换助理的方式（第 305 版）：连体卡片（默认）/ 竖排头像，和主题一样一行一张小样，点了当场换。 */
@Composable
private fun SwitchSection() {
    val haptic = LocalHapticFeedback.current
    Txt("换助理的方式", Page.muted, 12.sp, Modifier.padding(start = 8.dp, top = 14.dp, bottom = 10.dp), spacing = ls(1f, 12f))
    listOf(false to ("连体卡片" to "点名字，从名字下面展开"), true to ("竖排头像" to "侧栏左边一排，一点就换")).forEach { (rail, t) ->
        val on = Skin.rail == rail
        PressSurface(
            { if (!on) { Skin.useRail(rail); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } },
            Modifier.fillMaxWidth().padding(bottom = 7.dp), shape = RoundedCornerShape(16.dp), pressScale = .985f, label = t.first,
        ) {
            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                SwitchMini(rail, on)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Txt(t.first, Page.ink, 15.sp, maxLines = 1)
                    Spacer(Modifier.height(3.dp))
                    Txt(t.second, Page.muted, 12.sp, maxLines = 1)
                }
                val k by animateFloatAsState(if (on) 1f else 0f, spring(.5f, 500f), label = "sk")
                if (k > .01f) Box(Modifier.scale(k)) { DrawnCheck(SideWarm, 18.dp) }
                Spacer(Modifier.width(6.dp))
            }
        }
    }
    Txt("只改侧栏顶上换助理的样子，别的都不变。", Page.muted.copy(alpha = .8f), 11.5.sp, Modifier.padding(horizontal = 8.dp, vertical = 8.dp), lineHeight = 19.sp)
}

/** 卡片样式（第 331 版）：整页（默认）/ 悬浮卡片，一行一张小样，点了当场换。 */
@Composable
private fun CardSection() {
    val haptic = LocalHapticFeedback.current
    Txt("卡片样式", Page.muted, 12.sp, Modifier.padding(start = 8.dp, top = 14.dp, bottom = 10.dp), spacing = ls(1f, 12f))
    listOf(true to ("整页" to "像手机页面，‹ 一级一级往回退"), false to ("悬浮卡片" to "浮在中间，↗ 能临时展开")).forEach { (whole, t) ->
        val on = Skin.wholeCards == whole
        PressSurface(
            { if (!on) { Skin.useWhole(whole); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } },
            Modifier.fillMaxWidth().padding(bottom = 7.dp), shape = RoundedCornerShape(16.dp), pressScale = .985f, label = t.first,
        ) {
            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                CardMini(whole, on)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Txt(t.first, Page.ink, 15.sp, maxLines = 1)
                    Spacer(Modifier.height(3.dp))
                    Txt(t.second, Page.muted, 12.sp, maxLines = 1)
                }
                val k by animateFloatAsState(if (on) 1f else 0f, spring(.5f, 500f), label = "ck")
                if (k > .01f) Box(Modifier.scale(k)) { DrawnCheck(SideWarm, 18.dp) }
                Spacer(Modifier.width(6.dp))
            }
        }
    }
}

/** 整页：一整块铺满、顶上一道字；悬浮：深底中间一张小卡。 */
@Composable
private fun CardMini(whole: Boolean, on: Boolean) {
    val ring by animateFloatAsState(if (on) 1f else 0f, label = "cring")
    Canvas(Modifier.size(60.dp).clip(RoundedCornerShape(13.dp))) {
        val u = size.width / 60f
        drawRect(Color(0xFF1C1D22))
        if (whole) {
            drawRect(Color(0xFF34363E))
            drawRoundRect(Color(0xFF8A8D97), Offset(8 * u, 10 * u), Size(26 * u, 4 * u), CornerRadius(2 * u))
            listOf(20f, 30f, 40f).forEach { y -> drawRoundRect(Color(0xFF4A4D57), Offset(8 * u, y * u), Size(44 * u, 6 * u), CornerRadius(3 * u)) }
        } else {
            drawRoundRect(Color(0xFF34363E), Offset(9 * u, 11 * u), Size(42 * u, 38 * u), CornerRadius(7 * u))
            drawRoundRect(Color(0xFF8A8D97), Offset(14 * u, 16 * u), Size(18 * u, 3 * u), CornerRadius(1.5f * u))
            listOf(24f, 32f, 40f).forEach { y -> drawRoundRect(Color(0xFF4A4D57), Offset(14 * u, y * u), Size(32 * u, 4 * u), CornerRadius(2 * u)) }
        }
        if (ring > 0f) drawRoundRect(SideWarm.copy(alpha = ring), size = size, cornerRadius = CornerRadius(13.dp.toPx()), style = Stroke(2.5.dp.toPx()))
    }
}

/** 两张小样：连体卡片是一块带三行的色块；竖排是左边三颗头像、右边几道字。 */
@Composable
private fun SwitchMini(rail: Boolean, on: Boolean) {
    val ring by animateFloatAsState(if (on) 1f else 0f, label = "sring")
    val bg = Color(0xFF1C1D22); val dim = Color(0xFF5B5F6B); val line = Color(0xFF4A4D57); val block = Color(0xFF34363E)
    Canvas(Modifier.size(60.dp).clip(RoundedCornerShape(13.dp))) {
        drawRect(bg)
        val u = size.width / 60f
        if (rail) {
            drawRoundRect(SideWarm, Offset(7 * u, 9 * u), Size(11 * u, 11 * u), CornerRadius(4 * u))
            drawCircle(dim, 5.5f * u, Offset(12.5f * u, 30.5f * u)); drawCircle(dim, 5.5f * u, Offset(12.5f * u, 46.5f * u))
            drawRect(Color(0xFF2C2E35), Offset(21 * u, 6 * u), Size(1 * u, 48 * u))
            listOf(11f to 26f, 24f to 30f, 33f to 30f, 42f to 22f).forEachIndexed { i, (y, w) ->
                drawRoundRect(if (i == 0) Color(0xFF8A8D97) else line, Offset(25 * u, y * u), Size(w * u, 4 * u), CornerRadius(2 * u))
            }
        } else {
            drawRoundRect(block, Offset(7 * u, 7 * u), Size(38 * u, 46 * u), CornerRadius(7 * u))
            listOf(11f, 24f, 35f).forEachIndexed { i, y ->
                drawCircle(if (i == 0) SideWarm else Color(0xFF6A6E7A), 3 * u, Offset(14 * u, (y + 3) * u))
                drawRoundRect(if (i == 0) Color(0xFFA7AAB3) else Color(0xFF6A6E7A), Offset(20 * u, (y + 1.5f) * u), Size(18 * u, 3 * u), CornerRadius(1.5f * u))
            }
        }
        if (ring > 0f) drawRoundRect(SideWarm.copy(alpha = ring), size = size, cornerRadius = CornerRadius(13.dp.toPx()), style = Stroke(2.5.dp.toPx()))
    }
}

/** 一张小样：左边侧栏、三道字、右下角新对话药丸；选中的外面一圈杏色。 */
@Composable
private fun ThemeMini(id: String, on: Boolean) {
    val ring by animateFloatAsState(if (on) 1f else 0f, label = "ring")
    val (ground, side, line, pill) = when (id) {
        Skin.DARK -> listOf(Brush.linearGradient(listOf(Color(0xFF1C1C1C), Color(0xFF1C1C1C))), Color(0xFF131313), Color(0xFF4A4A4A), Color(0xFFF5F4EF))
        Skin.LIGHT -> listOf(Brush.linearGradient(listOf(Color(0xFFD9D8D2), Color(0xFFD9D8D2))), Color(0xFFF4F3EE), Color(0xFFB9B8B0), Color(0xFF151513))
        Skin.GRAD -> Grad.at(Skin.pal).let { g -> listOf(Brush.verticalGradient(listOf(g.a, g.b)), Color.White.copy(alpha = .55f), g.ink.copy(alpha = .35f), g.k) }
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
