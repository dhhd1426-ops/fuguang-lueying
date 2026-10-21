package com.fuguang.lueying

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val Warm = css("#e9b48e")

/** 下好的里面你看过哪些：侧栏「下载」那行有没下好、你还没看的，亮一个杏色小点。存 prefs `dl-seen`。 */
object DlSeen {
    private var prefs: Prefs? = null
    var ids by mutableStateOf<Set<String>?>(null)

    /** 在 Downloads.init 之后调。 */
    fun init(p: Prefs) { prefs = p; ids = p.string("dl-seen")?.split(',')?.filter { it.isNotEmpty() }?.toSet(); if (ids == null) mark() }

    /** 打开下载页：现在下好的都算看过。第一次装上这一版时，以前下好的也都算看过。 */
    fun mark() {
        val s = Downloads.list.filter { it.state == 3 }.map { it.id }.toSet()
        ids = s; prefs?.putString("dl-seen", s.joinToString(","))
    }

    val unseen: Boolean get() {
        val s = ids.orEmpty()
        return Downloads.list.any { it.state == 3 && it.id !in s }
    }
}

/** 侧栏「下载」右边：有在下的是小进度圈 +「N 个在下」；都下好了是（没看过的）杏色小点 + 总数；没有下载就空着。 */
@Composable
fun DlSideTrail() {
    val list = Downloads.list
    if (list.isEmpty()) return
    val run = list.filter { it.state != 3 && it.state != 4 && it.state != 5 }
    if (run.isNotEmpty()) {
        val total = run.sumOf { it.total.coerceAtLeast(0) }
        val f = if (total > 0) run.sumOf { it.done } / total.toFloat() else run.map { it.frac }.average().toFloat()
        MiniRing(f.coerceIn(0f, 1f))
        Spacer(Modifier.width(7.dp))
        Txt("${run.size} 个在下", SideFaint, 11.5.sp, maxLines = 1)
    } else {
        if (DlSeen.unseen) { Box(Modifier.size(6.dp).background(Warm, CircleShape)); Spacer(Modifier.width(8.dp)) }
        Txt(list.size.toString(), SideFaint, 11.5.sp)
    }
}

@Composable
private fun MiniRing(f: Float) {
    val k = remember { Animatable(f) }
    LaunchedEffect(f) { k.animateTo(f, tween(500, easing = Curves.standard)) }
    Canvas(Modifier.size(15.dp)) {
        val w = 2.dp.toPx()
        val tl = Offset(w / 2, w / 2)
        val sz = androidx.compose.ui.geometry.Size(size.width - w, size.height - w)
        drawArc(rgba(255, 255, 255, .13f), 0f, 360f, false, tl, sz, style = Stroke(w))
        drawArc(Warm, -90f, 360f * k.value, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
    }
}

/**
 * 下载页里的两栏：左「正在下载」（在下、暂停、出错的），右「已完成」（新的在上）。
 * 色块跟着手指滑；打开时有在下的停左边，都下好了停右边；下完一个从左边淡出，「已完成」的数字跳一下。
 */
@Composable
fun DlTabs(row: @Composable (Dl, Int) -> Unit) {
    val all = Downloads.list
    val going = all.filter { it.state != 3 }
    val done = all.filter { it.state == 3 }.sortedByDescending { it.at }
    val pager = rememberPagerState(initialPage = if (going.isEmpty() && done.isNotEmpty()) 1 else 0) { 2 }
    val scope = rememberCoroutineScope()
    // 「已完成」多了一个：数字弹一下
    val bump = remember { Animatable(1f) }
    var last by remember { mutableStateOf(done.size) }
    LaunchedEffect(done.size) {
        if (done.size > last) { bump.snapTo(1.45f); bump.animateTo(1f, spring(dampingRatio = .45f, stiffness = 420f)) }
        last = done.size
    }
    DlSeg(listOf("正在下载" to going.size, "已完成" to done.size), pager.currentPage + pager.currentPageOffsetFraction, bump.value) { scope.launch { pager.animateScrollToPage(it) } }
    HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
        val items = if (page == 0) going else done
        if (items.isEmpty()) Txt(
            if (page == 0) "现在没有在下的。\n在内置浏览器里看到视频，右下角点「下载视频」。" else "下好的会排在这里。",
            Page.muted, 13.sp, Modifier.fillMaxWidth().padding(top = 80.dp, start = 30.dp, end = 30.dp), lineHeight = 22.sp, align = TextAlign.Center,
        )
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(items, key = { _, d -> d.id }) { i, d -> Box(Modifier.animateItem()) { row(d, i) } }
            item(key = "foot") {
                Txt("加了版权保护的视频下不了。下载的视频自己看，别拿去传播。", Page.muted.copy(alpha = .5f), 11.5.sp, Modifier.padding(top = 10.dp, start = 4.dp, bottom = 40.dp))
            }
        }
    }
}

@Composable
private fun DlSeg(tabs: List<Pair<String, Int>>, pos: Float, bump: Float, onPick: (Int) -> Unit) {
    var w by remember { mutableStateOf(0f) }
    val d = LocalDensity.current
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 8.dp)
            .background(rgba(255, 255, 255, .05f), RoundedCornerShape(14.dp)).padding(3.dp)
            .onSizeChanged { w = it.width.toFloat() },
    ) {
        val tw = w / tabs.size
        Box(Modifier.graphicsLayer { translationX = pos.coerceIn(0f, 1f) * tw }.width(with(d) { tw.toDp() }).height(36.dp).background(rgba(255, 255, 255, .09f), RoundedCornerShape(11.dp)))
        Row(Modifier.fillMaxWidth()) {
            tabs.forEachIndexed { i, (t, n) ->
                val near = (1f - kotlin.math.abs(pos - i)).coerceIn(0f, 1f)
                Pressable({ onPick(i) }, Modifier.weight(1f), label = t) { _ ->
                    Row(Modifier.fillMaxWidth().height(36.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Txt(t, lerp(Page.muted, Page.ink, near), 13.5.sp, maxLines = 1)
                        if (n > 0) Txt(
                            n.toString(), if (i == 1 && bump > 1.01f) Warm else SideFaint, 12.sp,
                            Modifier.padding(start = 5.dp).graphicsLayer { val s = if (i == 1) bump else 1f; scaleX = s; scaleY = s },
                        )
                    }
                }
            }
        }
    }
}
