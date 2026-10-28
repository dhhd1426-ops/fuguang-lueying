package com.fuguang.lueying

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * 新对话还没说话时正中的问候（照 Claude App：上面一行名字，下面一句问候）。
 * 上面是杏色手写「Vignette」，一笔一笔写出来（VignetteMark.kt）；
 * 下面是她自己想的一句（Greet.kt），用衬线字，等名字写到八成时从下往上淡入。
 */
@Composable
internal fun ChatHello(compact: Boolean, store: AssistantStore, chat: ChatState) {
    val her = Memory.active
    val born = remember { System.currentTimeMillis() }
    var line by remember(her.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(her.id) { line = Greet.line(her, store, chat.history) }
    val shown = remember { Animatable(0f) }
    LaunchedEffect(line) {
        if (line == null) return@LaunchedEffect
        delay(((InkPlan.total * .82f).toLong() - (System.currentTimeMillis() - born)).coerceAtLeast(0L))
        shown.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 8.dp)) {
        VignetteMark(if (compact) 116.dp else 128.dp, Skin.accent, toy = remember { Skin.toy })
        Spacer(Modifier.height(6.dp))
        val text = line.orEmpty()
        val base = if (compact) 23f else 26f
        // 长一点的句子字号往下收，始终一行
        val fs = if (text.length > 10) maxOf(base - 6f, base - (text.length - 10) * 1.1f) else base
        Txt(
            text, Page.ink.copy(alpha = .94f), fs.sp,
            Modifier.graphicsLayer { alpha = shown.value; translationY = (1f - shown.value) * 6.dp.toPx() },
            spacing = (fs * .02f).sp,
            family = FontFamily.Serif,
            align = TextAlign.Center,
            maxLines = 1,
            shadow = if (Skin.light) null else Shadow(Color.Black.copy(alpha = .35f), blurRadius = 18f),
        )
    }
}
