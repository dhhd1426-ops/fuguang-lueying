package com.fuguang.lueying

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp

@Composable
fun Letters(
    letters: List<Letter>,
    w: Dp,
    h: Dp,
    scroll: ScrollState,
    onClose: () -> Unit,
    onOpen: (Int) -> Unit,
    onToggleTheme: (Offset) -> Unit,
) {
    val p = LocalPalette.current
    val narrow = w < 600.dp
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(if (expanded) 0.dp else 26.dp)
    val side = if (expanded) ((w - 700.dp) / 2).coerceAtLeast(24.dp) else if (narrow) 20.dp else 36.dp

    Column(
        Modifier
            .width(if (expanded) w else min(800.dp, w - 32.dp))
            .height(if (expanded) h else min(850.dp, h * .9f))
            .clip(shape)
            .background(p.lettersBg)
            .swallowTaps()
            .then(if (expanded) Modifier.windowInsetsPadding(WindowInsets.systemBars) else Modifier),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(
                    start = if (expanded) side else if (narrow) 24.dp else 36.dp,
                    end = if (expanded) side else if (narrow) 16.dp else 28.dp,
                    top = if (narrow) 18.dp else 24.dp,
                    bottom = if (narrow) 14.dp else 18.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Txt("留言", p.lettersInk, 20.sp, spacing = ls(5f, 20f))
                Spacer(Modifier.height(10.dp))
                Txt("写给你", p.lettersSub, 10.sp, spacing = ls(3f, 10f))
            }
            TextButton(if (expanded) "还原 ↙" else "全屏 ↗", p.lettersAction, 11f, label = "全屏") { expanded = !expanded }
            Spacer(Modifier.width(if (narrow) 8.dp else 12.dp))
            ThemeToggle(onToggleTheme)
            CloseButton(p.lettersInk, "关闭留言", onClose)
        }

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(start = side, end = side, top = 4.dp, bottom = if (narrow) 28.dp else 30.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = if (narrow) 4.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt("留给你的话", p.lettersIntro, 10.sp, Modifier.weight(1f), spacing = ls(2f, 10f))
                Txt("%02d / LETTERS".format(letters.size), p.lettersIntro, 8.sp, spacing = ls(3f, 8f))
            }
            Spacer(Modifier.height(if (narrow) 20.dp else 22.dp))
            Column(verticalArrangement = Arrangement.spacedBy(if (narrow) 12.dp else 14.dp)) {
                letters.forEachIndexed { i, letter ->
                    LetterCard(i, letter, narrow) { onOpen(i) }
                }
            }
            Txt("·", p.end, 22.sp, Modifier.fillMaxWidth().padding(top = 26.dp), family = FontFamily.Serif, align = TextAlign.Center)
        }
    }
}

@Composable
private fun LetterCard(index: Int, letter: Letter, narrow: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Pressable(onClick, Modifier.fillMaxWidth(), label = "阅读${letter.author.removePrefix("——")}的完整留言") { pressed ->
        val lift by animateDpAsState(if (pressed) (-2).dp else 0.dp, tween(200), label = "lift")
        val shape = RoundedCornerShape(16.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { translationY = lift.toPx() }
                .clip(shape)
                .background(if (pressed) p.cardBgPressed else p.cardBg)
                .border(1.dp, if (pressed) p.cardBorderPressed else p.cardBorder, shape)
                .padding(horizontal = if (narrow) 22.dp else 30.dp, vertical = if (narrow) 22.dp else 24.dp),
        ) {
            Txt("%02d".format(index + 1), p.number, 9.sp, spacing = ls(3f, 9f))
            Spacer(Modifier.height(14.dp))
            val qs = if (narrow) 18f else 20f
            Txt(
                letter.quote, p.quote, qs.sp,
                spacing = ls(.6f, qs),
                lineHeight = (qs * if (narrow) 1.95f else 1.9f).sp,
                family = Serif,
                maxLines = 4,
            )
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Txt("展开 ↗", p.open, 10.sp, Modifier.weight(1f), spacing = ls(1f, 10f))
                Txt(letter.author, p.author, 12.sp, spacing = ls(2f, 12f))
            }
        }
    }
}

@Composable
fun LetterDetail(letters: List<Letter>, index: Int, w: Dp, h: Dp, onIndex: (Int) -> Unit, onClose: () -> Unit) {
    val p = LocalPalette.current
    val narrow = w < 600.dp
    val count = letters.size
    val shape = RoundedCornerShape(24.dp)
    val threshold = with(LocalDensity.current) { 80.dp.toPx() }
    var drag by remember { mutableFloatStateOf(0f) }

    Column(
        Modifier
            .width(min(650.dp, w - 32.dp))
            .heightIn(max = h * .86f)
            .graphicsLayer { translationX = drag * .25f }
            .clip(shape)
            .background(p.detailBg)
            .border(1.dp, p.detailBorder, shape)
            .swallowTaps()
            // 左右滑动切换上一封 / 下一封
            .pointerInput(index) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (drag < -threshold && index < count - 1) onIndex(index + 1)
                        if (drag > threshold && index > 0) onIndex(index - 1)
                        drag = 0f
                    },
                    onDragCancel = { drag = 0f },
                ) { _, dx -> drag += dx }
            },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = if (narrow) 22.dp else 28.dp, end = if (narrow) 10.dp else 16.dp, top = if (narrow) 10.dp else 14.dp, bottom = if (narrow) 0.dp else 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt("写给你", p.detailHead, 11.sp, Modifier.weight(1f), spacing = ls(3f, 11f))
            CloseButton(p.detailInk, "关闭悬浮卡片", onClose)
        }
        Crossfade(index, Modifier.weight(1f, fill = false), animationSpec = tween(220), label = "letter") { i ->
            val letter = letters[i]
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(start = if (narrow) 26.dp else 40.dp, end = if (narrow) 26.dp else 40.dp, bottom = if (narrow) 24.dp else 28.dp),
            ) {
                Txt("“", p.detailMark, 72.sp, Modifier.height(56.dp), family = FontFamily.Serif, lineHeight = 86.sp)
                Spacer(Modifier.height(18.dp))
                val qs = if (narrow) 17f else 19f
                Txt(letter.quote, p.detailInk, qs.sp, spacing = ls(.6f, qs), lineHeight = (qs * if (narrow) 2f else 2.05f).sp, family = Serif)
                Spacer(Modifier.height(30.dp))
                Txt(letter.author, p.detailAuthor, 12.sp, Modifier.fillMaxWidth().padding(bottom = 8.dp), spacing = ls(2f, 12f), align = TextAlign.End)
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .topLine(p.detailLine)
                .padding(horizontal = if (narrow) 14.dp else 20.dp, vertical = if (narrow) 2.dp else 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton("← 上一封", p.detailButton, 11f, enabled = index > 0, label = "上一条留言") { onIndex(index - 1) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Txt("%02d / %02d".format(index + 1, count), p.detailCount, 9.sp, spacing = ls(2f, 9f))
            }
            TextButton("下一封 →", p.detailButton, 11f, enabled = index < count - 1, label = "下一条留言") { onIndex(index + 1) }
        }
    }
}
