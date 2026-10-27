package com.fuguang.lueying

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 记忆页里的一条：她写的那段话（Diary.kt）。第一句是总起，稍亮一点；后面正常。
 * 还没写好时先显示原来那句短的，正在写的下面一道杏色流光。
 */
@Composable
internal fun MemText(m: Mem, dim: Boolean, open: Boolean) {
    val ink = if (dim) Page.muted else Page.ink
    val d = m.diary
    if (d.isBlank()) {
        Txt(m.text, ink, 14.5.sp, lineHeight = 22.sp, maxLines = if (open) Int.MAX_VALUE else 2)
        if (m.id in Diary.writing) { Spacer(Modifier.height(8.dp)); Sheen() }
        return
    }
    val cut = d.indexOfFirst { it == '。' || it == '！' || it == '？' }.let { if (it < 0) d.length else it + 1 }
    val s = buildAnnotatedString {
        withStyle(SpanStyle(color = ink, fontWeight = FontWeight.Medium)) { append(d.substring(0, cut)) }
        withStyle(SpanStyle(color = ink.copy(alpha = .76f))) { append(d.substring(cut)) }
    }
    BasicText(
        s, Modifier.fillMaxWidth(),
        style = TextStyle(fontSize = 14.5.sp, lineHeight = 25.sp, fontFamily = Sans),
        maxLines = if (open) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis,
    )
}

/** 正在写：一道杏色光从左往右走。 */
@Composable
private fun Sheen() {
    val t = rememberInfiniteTransition(label = "sheen")
    val x by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "x")
    Column {
        listOf(1f, .62f).forEach { w ->
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxWidth(w).height(10.dp).background(
                    Brush.linearGradient(
                        listOf(veil(.05f), rgba(233, 180, 142, .22f), veil(.05f)),
                        start = Offset(-400f + x * 1200f, 0f), end = Offset(x * 1200f, 0f),
                    ),
                    RoundedCornerShape(5.dp),
                ),
            )
            Spacer(Modifier.height(6.dp))
        }
    }
}
