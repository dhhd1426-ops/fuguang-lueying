package com.fuguang.lueying

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * 「更多模型」小卡片：从按钮里长出来，浮在选择模型抽屉上方。
 * 里面是供应商列表 ⇄ 某一家的配置 / 模型。点空白缩回按钮；在里面选了模型，卡片和抽屉一起沉下去。
 */
class ProviderCardState {
    var open by mutableStateOf(false)
    /** 选了模型：一起沉下去，而不是缩回按钮。 */
    var sink by mutableStateOf(false)
    /** 正在看哪家；null = 列表。 */
    var pid by mutableStateOf<String?>(null)
    /** 详情页画的是哪家（滑走的途中 pid 已经是 null，内容还要留着）。 */
    var shown by mutableStateOf("deepseek")
    var tab by mutableIntStateOf(0)
    var query by mutableStateOf("")
    /** 配置页正在改的设置，和打开时的样子（比较它俩判断有没有改动）。 */
    var draft by mutableStateOf<ProviderConfig?>(null)
    var base by mutableStateOf<ProviderConfig?>(null)
    /** 点空白时替用户存下的那一句提示。 */
    var note by mutableStateOf<String?>(null)
    val listScroll = ScrollState(0)

    fun show() { pid = null; query = ""; sink = false; open = true }

    fun openDetail(store: AssistantStore, id: String) {
        val c = store.config(id)
        shown = id; draft = c; base = c; tab = 0; pid = id
    }

    fun dirty(): Boolean { val d = draft ?: return false; val b = base ?: return false; return !d.sameSettings(b) }

    /** 有改动就存：只写配置页管的那几项，模型列表等以存储里的为准。 */
    fun commit(store: AssistantStore): Boolean {
        val id = pid ?: return false
        val d = draft ?: return false
        if (!dirty()) return false
        val now = store.config(id)
        store.update(id, now.copy(name = d.name.trim().ifBlank { provider(id).name }, key = d.key, base = d.base, path = d.path, enabled = d.enabled, balance = d.balance))
        base = d
        return true
    }

    fun setTab(store: AssistantStore, t: Int) { if (tab == 0 && t != 0) commit(store); tab = t }

    fun back(store: AssistantStore) { commit(store); pid = null }

    /** 点空白：顺手把没保存的存好，缩回按钮。 */
    fun dismiss(store: AssistantStore) {
        val id = pid
        if (id != null && commit(store)) note = "已替你保存 ${store.config(id).name} 的设置"
        open = false
    }
}

private fun ProviderConfig.sameSettings(o: ProviderConfig) =
    name == o.name && key == o.key && base == o.base && path == o.path && enabled == o.enabled && balance == o.balance

/**
 * 画在抽屉所在的整屏 Box 里。[anchor] 是「更多模型」按钮在这个 Box 里的位置，卡片底边贴着它往上长。
 */
@Composable
fun ProviderCardLayer(state: ProviderCardState, store: AssistantStore, anchor: Rect?, onPicked: () -> Unit) {
    val t by animateFloatAsState(
        if (state.open) 1f else 0f,
        if (state.open) spring(dampingRatio = .8f, stiffness = 380f) else tween(if (state.sink) 260 else 230, easing = Ease.accel),
        label = "card",
    )
    LaunchedEffect(state.open) { if (!state.open) { delay(300); state.sink = false } }
    if (t <= 0f && !state.open) return
    val density = LocalDensity.current
    var host by remember { mutableStateOf(IntSize.Zero) }
    Box(Modifier.fillMaxSize().onSizeChanged { host = it }) {
        // 遮罩：点空白收起
        Box(
            Modifier.fillMaxSize().alpha(t.coerceIn(0f, 1f)).background(rgba(4, 6, 10, .38f))
                .pointerInput(Unit) { detectTapGestures { state.dismiss(store) } },
        )
        if (anchor == null || host == IntSize.Zero) return@Box
        val gap = with(density) { 10.dp.toPx() }
        val side = with(density) { 14.dp.toPx() }
        val top = WindowInsets.safeDrawing.getTop(density) + with(density) { 56.dp.toPx() }
        val bottom = anchor.top - gap
        val h = (bottom - top).coerceAtLeast(with(density) { 280.dp.toPx() })
        val w = (host.width - side * 2).coerceAtMost(with(density) { 520.dp.toPx() })
        val left = (host.width - w) / 2f
        val cardTop = bottom - h
        val px = ((anchor.center.x - left) / w).coerceIn(0f, 1f)
        val py = (h + with(density) { 30.dp.toPx() }) / h
        val alpha = (t / .45f).coerceIn(0f, 1f)
        Box(
            Modifier
                .offset { IntOffset(left.toInt(), cardTop.toInt()) }
                .size(with(density) { w.toDp() }, with(density) { h.toDp() })
                .graphicsLayer {
                    if (state.sink) {
                        translationY = (1f - t) * host.height * .55f
                        scaleX = .98f + .02f * t; scaleY = scaleX
                    } else {
                        scaleX = .5f + .5f * t; scaleY = scaleX
                        transformOrigin = TransformOrigin(px, py)
                    }
                    this.alpha = alpha
                    shadowElevation = 18.dp.toPx() * alpha
                    shape = RoundedCornerShape(24.dp)
                    clip = true
                }
                .background(rgba(28, 32, 40, .97f))
                .pointerInput(Unit) { detectTapGestures { } },
        ) { CardPanes(state, store, w, onPicked) }
    }
}

@Composable
private fun CardPanes(state: ProviderCardState, store: AssistantStore, w: Float, onPicked: () -> Unit) {
    val d by animateFloatAsState(if (state.pid != null) 1f else 0f, tween(320, easing = Ease.standard), label = "pane")
    if (d < 1f) {
        Box(Modifier.fillMaxSize().graphicsLayer { translationX = -.22f * w * d; alpha = 1f - d }) {
            ProviderListPane(state, store)
        }
    }
    if (d > 0f) {
        Box(Modifier.fillMaxSize().graphicsLayer { translationX = .22f * w * (1f - d); alpha = d }) {
            ProviderDetailPane(state, store, onPicked)
        }
    }
}

@Composable
private fun ProviderListPane(state: ProviderCardState, store: AssistantStore) {
    Column(Modifier.fillMaxSize()) {
        CardHeader("模型供应商", null)
        CardField(state.query, { state.query = it }, "搜索供应商", Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp))
        val q = state.query.trim()
        val list = store.ordered().filter { q.isEmpty() || it.name.contains(q, true) || store.config(it.id).name.contains(q, true) }
        var viewport by remember { mutableIntStateOf(0) }
        Column(
            Modifier.weight(1f).fillMaxWidth().onSizeChanged { viewport = it.height }
                .verticalScroll(state.listScroll).padding(start = 12.dp, end = 12.dp, bottom = 14.dp),
        ) {
            if (list.isEmpty()) Txt("没有叫「$q」的供应商", Page.muted.copy(alpha = .55f), 13.sp, Modifier.fillMaxWidth().padding(vertical = 18.dp), align = TextAlign.Center)
            SortableProviders(store, list, sortable = q.isEmpty(), scroll = state.listScroll, viewport = viewport) { state.openDetail(store, it) }
            if (q.isEmpty()) Txt("长按拖动，调整顺序", Page.muted.copy(alpha = .5f), 12.sp, Modifier.fillMaxWidth().padding(top = 4.dp), align = TextAlign.Center)
        }
    }
}
