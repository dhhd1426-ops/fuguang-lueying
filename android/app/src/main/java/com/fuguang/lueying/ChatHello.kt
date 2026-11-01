package com.fuguang.lueying

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * 新对话还没说话时正中的问候（照 Claude App：上面一行名字，下面一句问候）。
 * 上面是杏色手写「Vignette」，一笔一笔写出来（VignetteMark.kt）；
 * 下面是她自己想的一句（Greet.kt），用衬线字，打字机出场（原型 P4fvohkBDmfqfjPa2urDm6 第 5 版）：
 * 名字写到八成、句子也想好了，就一个字一个字弹出来，每字 [CHAR] 毫秒、节奏均匀；
 * 杏色竖线光标跟着一格一格跳，打完灭、亮、灭、亮（每下 [HALF] 毫秒），第二下亮完直接收掉。
 * 整句先按全长排好，只在绘制时裁到打到的位置，句子不跟着往左挪。系统关了动画就整句直接出来、没有光标。
 */
private const val CHAR = 90L
private const val HALF = 420L

@Composable
internal fun ChatHello(compact: Boolean, store: AssistantStore, chat: ChatState) {
    val her = Memory.active
    val born = remember { System.currentTimeMillis() }
    var line by remember(her.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(her.id) { line = Greet.line(her, store, chat.history) }
    val still = Toy.still()
    // 打字开始后过了多少毫秒；-1 = 还没开始。只在绘制里读
    val clock = remember { mutableLongStateOf(-1L) }
    LaunchedEffect(line) {
        val text = line ?: return@LaunchedEffect
        clock.longValue = -1L
        delay(((InkPlan.total * .82f).toLong() - (System.currentTimeMillis() - born)).coerceAtLeast(0L))
        val end = text.length * CHAR + HALF * 4
        if (still) { clock.longValue = end; return@LaunchedEffect }
        val t0 = withFrameMillis { it }
        clock.longValue = 0L
        while (clock.longValue < end) withFrameMillis { clock.longValue = it - t0 }
        clock.longValue = end
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 8.dp)) {
        VignetteMark(if (compact) 116.dp else 128.dp, Skin.accent)
        Spacer(Modifier.height(6.dp))
        val text = line.orEmpty()
        val base = if (compact) 23f else 26f
        // 长一点的句子字号往下收，始终一行
        val fs = if (text.length > 10) maxOf(base - 6f, base - (text.length - 10) * 1.1f) else base
        var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
        val caret = Skin.accent
        BasicText(
            text,
            Modifier.drawWithContent {
                val t = clock.longValue
                if (t < 0L || text.isEmpty()) return@drawWithContent
                val n = text.length
                val typed = minOf(n, (t / CHAR + 1).toInt())
                val lay = layout
                if (typed >= n || lay == null) drawContent()
                else {
                    val x = lay.getHorizontalPosition(typed, true)
                    clipRect(left = -size.width, right = x, top = -size.height, bottom = size.height * 2) { this@drawWithContent.drawContent() }
                }
                // 光标：打字时常亮；打完后灭-亮-灭-亮，然后收掉
                val done = n * CHAR
                val on = if (t < done) true else { val k = (t - done) / HALF; k < 4 && k % 2 == 1L }
                if (on && lay != null && !still) {
                    val x = lay.getHorizontalPosition(typed, true) + 1.dp.toPx()
                    val line0 = lay.getLineTop(0); val line1 = lay.getLineBottom(0)
                    val h = (line1 - line0) * .62f
                    val top = line0 + (line1 - line0 - h) / 2f
                    drawRoundRect(caret, Offset(x, top), Size(1.6.dp.toPx(), h), CornerRadius(1.dp.toPx()))
                }
            },
            style = TextStyle(
                color = Page.ink.copy(alpha = .94f), fontSize = fs.sp, letterSpacing = (fs * .02f).sp,
                fontFamily = FontFamily.Serif, textAlign = TextAlign.Center,
                shadow = if (Skin.light) null else Shadow(Color.Black.copy(alpha = .35f), blurRadius = 18f),
            ),
            onTextLayout = { layout = it },
            maxLines = 1,
        )
    }
}
