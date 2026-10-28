package com.fuguang.lueying

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch

/**
 * 供应商列表，长按排序：按住约 0.4 秒，那一行浮起来（放大、变亮、带影子，轻震），跟着手指走；
 * 别的行让出位置，每换一格轻震一下；到头有阻力，贴近边缘自动滚；松手带一点回弹落进新位置。
 */
@Composable
fun SortableProviders(
    store: AssistantStore,
    list: List<Provider>,
    sortable: Boolean,
    scroll: ScrollState,
    viewport: Int,
    onOpen: (String) -> Unit,
) {
    val density = LocalDensity.current
    val gap = with(density) { 6.dp.toPx() }
    val edge = with(density) { 48.dp.toPx() }
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val heights = remember { mutableStateMapOf<String, Int>() }
    var dragId by remember { mutableStateOf<String?>(null) }
    var raw by remember { mutableFloatStateOf(0f) }
    var settling by remember { mutableStateOf(false) }
    var snapBack by remember { mutableStateOf(false) }
    var slot by remember { mutableIntStateOf(-1) }
    val drop = remember { Animatable(0f) }

    val from = list.indexOfFirst { it.id == dragId }
    val h = list.map { (heights[it.id] ?: 0).toFloat() }
    val tops = FloatArray(list.size).also { a -> var y = 0f; list.indices.forEach { i -> a[i] = y; y += h[i] + gap } }
    // 手指带着走的距离，到头了打三折
    val dy = if (from < 0) 0f else {
        val lo = tops[0] - tops[from]
        val hi = tops.last() + h.last() - tops[from] - h[from]
        when { raw < lo -> lo - (lo - raw) * .3f; raw > hi -> hi + (raw - hi) * .3f; else -> raw }
    }
    val center = if (from < 0) 0f else tops[from] + h[from] / 2f + dy
    fun targetOf(): Int {
        if (from < 0) return -1
        var to = from
        list.indices.forEach { i ->
            val ci = tops[i] + h[i] / 2f
            if (i < from && center < ci) to--
            if (i > from && center > ci) to++
        }
        return to
    }
    val target = targetOf()
    LaunchedEffect(target) { if (dragId != null && !settling && target != slot) { if (slot >= 0) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); slot = target } }

    // 贴近可视区上下边缘：自动滚，行跟着手指不动
    val edgeSpeed = rememberUpdatedState {
        if (from < 0) 0f else {
            val top = tops[from] + dy - scroll.value
            when {
                top < edge -> -(edge - top) / 5f
                top + h[from] > viewport - edge -> (top + h[from] - viewport + edge) / 5f
                else -> 0f
            }
        }
    }
    LaunchedEffect(dragId, settling) {
        while (dragId != null && !settling) {
            withFrameNanos { }
            val v = edgeSpeed.value()
            if (v != 0f) raw += scroll.scrollBy(v)
        }
    }

    fun end() {
        if (dragId == null || settling) return
        settling = true
        val to = targetOf().coerceIn(0, list.lastIndex)
        var off = 0f
        if (to > from) for (i in from + 1..to) off += h[i] + gap else for (i in to until from) off -= h[i] + gap
        val start = dy
        scope.launch {
            drop.snapTo(start)
            drop.animateTo(off, spring(dampingRatio = .7f, stiffness = 420f))
            snapBack = true
            store.move(from, to)
            dragId = null; raw = 0f; slot = -1; settling = false
            withFrameNanos { }
            snapBack = false
        }
    }

    val endRef = rememberUpdatedState<() -> Unit>({ end() })
    val ids = rememberUpdatedState(list.map { it.id })
    Column {
        list.forEachIndexed { i, p ->
            key(p.id) {
                val shift = if (from < 0 || i == from) 0f else {
                    val ci = tops[i] + h[i] / 2f
                    when { i < from && center < ci -> h[from] + gap; i > from && center > ci -> -(h[from] + gap); else -> 0f }
                }
                val moved by animateFloatAsState(shift, if (snapBack) snap() else tween(240, easing = Curves.standard), label = "shift")
                val lifted = p.id == dragId && !settling
                val lift by animateFloatAsState(if (lifted) 1f else 0f, if (lifted) spring(dampingRatio = .5f, stiffness = 500f) else tween(260, easing = Curves.standard), label = "lift")
                val mine = p.id == dragId
                Box(
                    Modifier
                        .padding(bottom = 6.dp)
                        .onSizeChanged { heights[p.id] = it.height }
                        .zIndex(if (mine) 1f else 0f)
                        .then(
                            if (sortable) Modifier.pointerInput(p.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        dragId = p.id; raw = 0f; slot = ids.value.indexOf(p.id)
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onDrag = { change, amount -> change.consume(); raw += amount.y },
                                    onDragEnd = { endRef.value() },
                                    onDragCancel = { endRef.value() },
                                )
                            } else Modifier,
                        )
                        .graphicsLayer {
                            translationY = if (mine) (if (settling) drop.value else dy) else moved
                            scaleX = 1f + .035f * lift; scaleY = scaleX
                            shadowElevation = 16.dp.toPx() * lift
                            shape = RoundedCornerShape(16.dp)
                        },
                ) { ProviderRow(store, p, lift) { if (dragId == null) onOpen(p.id) } }
            }
        }
    }
}

@Composable
private fun ProviderRow(store: AssistantStore, p: Provider, lift: Float, onClick: () -> Unit) {
    val c = store.config(p.id)
    PressSurface(
        onClick, Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        fill = lerp(CardBlock, liftFill, lift), pressedFill = CardBlockPress, pressScale = .985f, label = c.name,
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(36.dp)) { Txt(p.letter, Page.ink, 20.sp, family = Serif) }
            Column(Modifier.weight(1f)) {
                Txt(c.name, Page.ink, 15.sp, weight = FontWeight.Medium, maxLines = 1)
                Spacer(Modifier.height(5.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    when {
                        c.enabled && c.configured -> CardTag("启用", filled = true)
                        c.configured -> CardTag("未启用")
                        else -> CardTag("未配置")
                    }
                    CardTag("${c.models.size} 个模型")
                    val bal = store.balances[p.id]
                    if (bal != null) CardTag(bal.substringBefore(" ·")) else if (p.note.isNotEmpty()) CardTag(p.note)
                }
            }
            SvgIcon(Ic("M9.5 6l6 6-6 6"), Page.muted, 18.dp)
        }
    }
}

/** 拖起来的那一张：浅色主题是白色面板，深色主题是浅一点的灰。 */
private val liftFill: androidx.compose.ui.graphics.Color get() = if (Skin.light) Skin.pop else androidx.compose.ui.graphics.Color(60, 64, 74).copy(alpha = .97f)
