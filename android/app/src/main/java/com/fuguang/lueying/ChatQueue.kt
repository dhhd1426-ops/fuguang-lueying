package com.fuguang.lueying

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val QueueApricot = Color(0xFFE9B48E)

/**
 * 她在做事时你发的话（照 Claude App）：排在聊天末尾，是你自己的气泡，最下面一个杏色「立即发送」。
 * 她做完手上一步就收到，气泡进到对话里；点气泡可以「改一下」（拿回输入框）或「撤回」。
 */
@Composable
fun QueuedBubbles(chat: ChatState, compact: Boolean, onSendNow: () -> Unit) {
    var picked by remember { mutableIntStateOf(-1) }
    val items = chat.queue.toList()
    if (picked >= items.size) picked = -1
    Column(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalAlignment = Alignment.End) {
        items.forEachIndexed { i, text ->
            androidx.compose.runtime.key(i, text) {
                val appear = remember { MutableTransitionState(false).apply { targetState = true } }
                AnimatedVisibility(
                    visibleState = appear,
                    enter = fadeIn(tween(320)) + slideInVertically(tween(360, easing = Curves.standard)) { it / 3 },
                ) {
                    Pressable({ picked = if (picked == i) -1 else i }, Modifier.padding(bottom = 6.dp), label = "排队中的消息：$text") { _ ->
                        UserBubble(text, compact)
                    }
                }
                AnimatedVisibility(
                    picked == i,
                    enter = expandVertically(tween(220)) + fadeIn(tween(220)),
                    exit = shrinkVertically(tween(180)) + fadeOut(tween(160)),
                ) {
                    Row(Modifier.padding(end = 4.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        QueueAction("改一下", Page.muted) {
                            chat.queue.removeAt(i); picked = -1
                            chat.draft = if (chat.draft.isBlank()) text else chat.draft + "\n" + text
                            chat.focusTick++
                        }
                        QueueAction("撤回", Page.muted) { chat.queue.removeAt(i); picked = -1 }
                    }
                }
            }
        }
        if (items.isNotEmpty()) QueueAction("立即发送", QueueApricot, Modifier.padding(end = 4.dp, bottom = 12.dp), onSendNow)
    }
}

@Composable
private fun QueueAction(text: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Pressable(onClick, modifier, label = text) { pressed ->
        Txt(text, if (pressed) color.copy(alpha = .6f) else color, 13.5.sp, Modifier.padding(vertical = 4.dp), maxLines = 1)
    }
}
