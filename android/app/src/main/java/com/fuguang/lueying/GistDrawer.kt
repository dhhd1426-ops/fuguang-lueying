package com.fuguang.lueying

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「这段聊了什么」（第 359 版，原型 https://claude.ai/artifact/AKE7EcqcXVcPex1eKnoCmB 第 1 版）：
 * ⋮ 菜单里点开，从下面升起，样子照思考过程抽屉（92% 高、26dp 圆角、拖动条、× 和居中标题）。
 * 里面是她翻以前的聊天时看的那份小结（[Conv.gist]），只读。进 320ms 减速，出 240ms 加速；往下拖过 18% 或快速下滑就关。
 */
@Composable
internal fun GistDrawer(conv: Conv?, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var shown by remember { mutableStateOf<Conv?>(null) }
    if (conv != null) shown = conv
    BackHandler(conv != null) { onClose() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val hPx = constraints.maxHeight.toFloat()
        val sheetPx = hPx * .92f
        var off by remember { mutableFloatStateOf(Float.NaN) }
        var visible by remember { mutableStateOf(false) }
        val job = remember { arrayOfNulls<Job>(1) }
        fun go(target: Float, ms: Int, decel: Boolean, then: () -> Unit = {}) {
            job[0]?.cancel()
            job[0] = scope.launch {
                animate(off, target, animationSpec = tween(ms, easing = if (decel) Curves.decel else Curves.accel)) { v, _ -> off = v }
                then()
            }
        }
        LaunchedEffect(conv != null) {
            if (conv != null) { visible = true; if (off.isNaN() || off >= sheetPx) off = sheetPx; go(0f, 320, true) }
            else if (visible) go(sheetPx, 240, false) { visible = false; shown = null }
        }
        val c = shown
        if (!visible || off.isNaN() || c == null) return@BoxWithConstraints
        val scrim = (1f - off / sheetPx).coerceIn(0f, 1f)
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = scrim }.background(Skin.scrim(.4f)).pointerInput(Unit) { detectTapGestures { onClose() } })
        Column(
            Modifier.align(Alignment.BottomCenter).widthIn(max = 640.dp).fillMaxWidth().height(maxHeight * .92f)
                .graphicsLayer { translationY = off }
                .background(Skin.sheet, RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .draggable(
                        rememberDraggableState { d -> job[0]?.cancel(); off = (off + d).coerceAtLeast(0f) },
                        Orientation.Vertical,
                        onDragStopped = { v -> if (v > 1400f || off > hPx * .18f) onClose() else go(0f, 240, true) },
                    )
                    .padding(start = 22.dp, end = 22.dp, top = 10.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(4.dp).background(veil(.25f), RoundedCornerShape(2.dp)))
                Spacer(Modifier.height(14.dp))
                Box(Modifier.fillMaxWidth()) {
                    Pressable(onClose, Modifier.align(Alignment.CenterStart), label = "关闭") { _ ->
                        SvgIcon(Icons.close, Page.ink, 20.dp, modifier = Modifier.padding(6.dp))
                    }
                    Txt("这段聊了什么", Page.ink, 17.sp, Modifier.align(Alignment.Center).padding(horizontal = 44.dp), maxLines = 1)
                }
                Spacer(Modifier.height(14.dp))
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 2.dp, bottom = 40.dp)) {
                // 还没有：打开就当场补写（第 361 版）；写的时候是「正在写…」，一口一口地呼吸
                LaunchedEffect(c.id, conv != null) { if (conv != null && c.gist.isBlank()) Reflect.digestNow(c.id) }
                if (c.gist.isBlank()) {
                    val busy = c.id in Reflect.writing
                    val breath by rememberInfiniteTransition(label = "gist").animateFloat(.45f, 1f, infiniteRepeatable(tween(900, easing = Curves.standard), RepeatMode.Reverse), label = "b")
                    Txt(if (busy) "正在写这段的小结…" else "这段还没有小结。聊完停一会儿，她会写上。", Page.muted, 15.sp,
                        Modifier.fillMaxWidth().padding(top = 72.dp, start = 30.dp, end = 30.dp).graphicsLayer { alpha = if (busy) breath else 1f }, lineHeight = 27.sp, align = TextAlign.Center)
                } else {
                    val at = if (c.gistAt > 0) SimpleDateFormat("M月d日 H:mm", Locale.CHINA).format(Date(c.gistAt)) + " 更新 · " else ""
                    Txt(at + "她翻以前的聊天时看这份", Page.muted, 12.5.sp, Modifier.fillMaxWidth().padding(bottom = 18.dp), align = TextAlign.Center)
                    var first = true
                    c.gist.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
                        when {
                            // 话题行：「【换季买外套（2026年10月22日）】」
                            line.startsWith("【") && line.endsWith("】") -> {
                                Txt(line.removePrefix("【").removeSuffix("】"), Page.ink, 15.sp, Modifier.padding(top = if (first) 0.dp else 14.dp, bottom = 6.dp), lineHeight = 24.sp, weight = FontWeight.Medium)
                            }
                            line.startsWith("还没办完") || line.startsWith("还没办的") ->
                                Txt(line, Page.muted, 15.sp, Modifier.padding(bottom = 10.dp), lineHeight = 27.sp)
                            else -> Txt(line, Page.ink.copy(alpha = .9f), 16.sp, Modifier.padding(bottom = 10.dp), lineHeight = 29.6.sp)
                        }
                        first = false
                    }
                }
            }
        }
    }
}
