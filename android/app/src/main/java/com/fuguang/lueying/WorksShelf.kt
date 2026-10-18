package com.fuguang.lueying

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 侧栏「作品」：网页、动画、动效、幻灯片、文档（画板另在「画板」里），新的在前；点一件全屏看。从下面升上来，点空白落回去。 */
@Composable
fun WorksShelf(open: Boolean, onClose: () -> Unit, onOpen: (String) -> Unit) {
    val t by animateFloatAsState(if (open) 1f else 0f, tween(if (open) 320 else 220, easing = Curves.standard), label = "shelf")
    if (t <= 0f) return
    val ids = remember(open, Works.version) { Works.list().filter { it.kind != "board" }.map { it.id } }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        val h = maxHeight
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = t }.background(rgba(6, 9, 14, .5f)).pointerInput(Unit) { detectTapGestures { onClose() } })
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth().height(h * .82f)
                .graphicsLayer { translationY = (1f - t) * h.toPx() * .5f; alpha = t }
                .background(rgba(20, 24, 31, .97f), RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(start = 20.dp, end = 20.dp, top = 10.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 14.dp).widthIn(min = 40.dp, max = 40.dp).height(4.dp).background(rgba(255, 255, 255, .25f), RoundedCornerShape(2.dp)))
            Box(Modifier.fillMaxWidth()) {
                Pressable(onClose, Modifier.align(Alignment.CenterStart), label = "关闭") { _ ->
                    SvgIcon(Icons.close, Page.ink, 20.dp, modifier = Modifier.padding(6.dp))
                }
                Txt("作品", Page.ink, 16.5.sp, Modifier.align(Alignment.Center), family = Serif, weight = FontWeight.SemiBold, maxLines = 1)
            }
            Spacer(Modifier.height(6.dp))
            if (ids.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
                    Txt("还没有作品。让她画点什么、做个页面，就会出现在这里。", Page.muted, 13.sp, lineHeight = 22.sp)
                }
            } else Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 30.dp)) {
                WorkCards(ids, onOpen)
            }
        }
    }
}
