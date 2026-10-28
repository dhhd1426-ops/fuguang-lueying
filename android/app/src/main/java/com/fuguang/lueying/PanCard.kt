package com.fuguang.lueying

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * 方案三「聊天卡片」：网盘分享落在消息下面（你发的链接挂你的消息，她 open_pan 过的挂她的回复）。
 * 里面和长条展开是同一块 [PanPane]，读同一份 [PanItem]，所以长条上下到一半，卡上看到的是同一个进度。
 */
@Composable
fun PanCard(it: PanItem) {
    Box(Modifier.widthIn(max = 440.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(veil(.055f)).padding(top = 8.dp)) {
        PanPane(it) { }
    }
}
