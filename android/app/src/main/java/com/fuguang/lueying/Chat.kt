package com.fuguang.lueying

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 问候用的细意大利斜体（Cormorant Garamond Light Italic，打包时下载生成）。 */
private val Greeting = FontFamily(Font(R.font.cormorant_light_italic, FontWeight.Light, FontStyle.Italic))

private object ChatIcons {
    val newChat = Ic("M12 3.5a8.5 8.5 0 1 1-4.3 15.8L3.5 20.5l1.2-4.1A8.5 8.5 0 0 1 12 3.5zM12 8.5v7M8.5 12h7")
    val plus = Ic("M12 5v14M5 12h14")
    val mic = Ic("M5.5 11.5a6.5 6.5 0 0 0 13 0M12 18v2.5M9 6.5a3 3 0 0 1 6 0v5a3 3 0 0 1-6 0Z")
    val wave = Ic("M4 10v4M8 7v10M12 4.5v15M16 7v10M20 10v4")
}

/**
 * 底部导航“聊天”页：背景模糊压暗，正中一颗浮光四角星 + 斜体问候 + 一道短暖线，
 * 下面是玻璃输入框。目前只有界面，还不能发送。
 */
@Composable
fun ChatPage(
    compact: Boolean,
    tiny: Boolean,
    width: Dp,
    blur: Dp,
    hour: Int,
    title: String,
    subtitle: String,
    onHome: () -> Unit,
    onMine: () -> Unit,
    onOpen: (ModalKind) -> Unit,
) {
    val side = if (compact) (if (tiny) 18.dp else 24.dp) else (width * .052f)
    Box(Modifier.fillMaxSize().background(rgba(14, 17, 23, .38f))) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(start = side, end = side, top = if (compact) 26.dp else 38.dp, bottom = if (compact) 22.dp else 30.dp),
        ) {
            ChatHeader(compact, title, subtitle)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Hello(compact, hour)
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ChatInput(compact, blur, Modifier.widthIn(max = 720.dp).fillMaxWidth())
            }
            BottomNav(compact, active = "聊天", onHome = onHome, onChat = {}, onMine = onMine, onOpen = onOpen)
        }
    }
}

@Composable
private fun ChatHeader(compact: Boolean, title: String, subtitle: String) {
    Box(Modifier.fillMaxWidth()) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            val ts = if (compact) 17f else 19f
            Txt(title, Page.ink, ts.sp, spacing = ls(1f, ts), weight = FontWeight.Medium)
            Spacer(Modifier.height(5.dp))
            Txt(subtitle, Page.muted, 10.sp, maxLines = 1)
        }
        Pressable({}, Modifier.align(Alignment.CenterEnd), label = "新对话") { pressed ->
            SvgIcon(ChatIcons.newChat, if (pressed) Page.ink else Page.ink.copy(alpha = .85f), 22.dp, modifier = Modifier.padding(6.dp))
        }
    }
}

@Composable
private fun Hello(compact: Boolean, hour: Int) {
    val text = when (hour) {
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        else -> "Good evening"
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        SvgIcon(Icons.spark, Page.accent.copy(alpha = .85f), if (compact) 24.dp else 28.dp, stroke = .9f)
        Spacer(Modifier.height(if (compact) 16.dp else 20.dp))
        val fs = if (compact) 34f else 42f
        Txt(text, Page.ink.copy(alpha = .86f), fs.sp, spacing = ls(1.5f, fs), family = Greeting, weight = FontWeight.Light, maxLines = 1)
        Spacer(Modifier.height(if (compact) 14.dp else 18.dp))
        Box(Modifier.width(34.dp).height(1.dp).background(Page.accent.copy(alpha = .55f)))
    }
}

/** 玻璃输入框：占位文字、加号、模型、麦克风、暖色语音键。暂不可用。 */
@Composable
private fun ChatInput(compact: Boolean, blur: Dp, modifier: Modifier) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .glass(shape, blur, fill = rgba(30, 34, 42, .42f))
            .padding(start = 16.dp, end = 12.dp, top = 15.dp, bottom = 12.dp),
    ) {
        Txt("回信给 $title…", Page.muted, if (compact) 14.sp else 15.sp, Modifier.padding(start = 4.dp))
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box {
                Box(
                    Modifier.size(36.dp).border(1.dp, Page.line, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { SvgIcon(ChatIcons.plus, Page.ink, 18.dp) }
                Box(Modifier.align(Alignment.TopEnd).size(7.dp).background(css("#c98a86"), CircleShape))
            }
            Row(
                Modifier.background(rgba(255, 255, 255, .07f), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Txt("deepseek-flash", Page.ink, 12.sp, maxLines = 1)
                Txt(" · 自动", Page.muted, 12.sp, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            SvgIcon(ChatIcons.mic, Page.ink.copy(alpha = .85f), 21.dp)
            Box(
                Modifier.size(42.dp).background(rgba(233, 180, 142, .9f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { SvgIcon(ChatIcons.wave, css("#2a211b"), 20.dp, stroke = 1.6f) }
        }
    }
}
