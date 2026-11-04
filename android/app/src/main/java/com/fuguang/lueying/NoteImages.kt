package com.fuguang.lueying

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 羽的笔记图（"笔记id/图片id"）和她发的图（SentPics，"pic:…"）都从这里读。 */
suspend fun refBitmap(ref: String, max: Int): android.graphics.Bitmap? = if (SentPics.isRef(ref)) SentPics.bitmap(ref, max) else Yu.bitmap(ref, max)

/** 回复下面的图：羽的笔记图是一排小方块（可以横着滑）；她发的图一张就大图、两张以上两列方块。点开看大图。 */
@Composable
fun NoteImageRow(refs: List<String>, onOpen: (String) -> Unit) {
    if (refs.isEmpty()) return
    val (pics, notes) = refs.partition { SentPics.isRef(it) }
    if (pics.isNotEmpty()) SentPicBlock(pics, onOpen)
    if (notes.isEmpty()) return
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(start = 2.dp, top = 4.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        notes.forEach { ref ->
            val bmp by produceState<android.graphics.Bitmap?>(null, ref) { value = Yu.bitmap(ref, 360) }
            // 图片读出来之前是一块淡色块，读到后淡入
            val a by animateFloatAsState(if (bmp != null) 1f else 0f, tween(260), label = "thumb")
            Box(
                Modifier.size(84.dp).clip(RoundedCornerShape(14.dp)).background(rgba(255, 255, 255, .06f))
                    .pointerInput(ref) { detectTapGestures { onOpen(ref) } },
            ) {
                bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize().alpha(a), contentScale = ContentScale.Crop) }
            }
        }
    }
}

/*
 * 她发的图（第 387 版，原型 MkwUcuJbKmQhytTfb2GNvE 第 5 版）：
 * - 每张先占一格（还不知道尺寸，方块），格子里是文件卡那个变形弹跳（Toy，f 0.45、跳高压一半）；
 * - 拿到一张，那一格 320ms 减速变成原图比例：一张时 76% 宽按原图长宽；多张时两张一行一样高、宽按比例分（weight = 比例），不裁成方块；
 * - 跳满一拍、比例变完后，下一次落地：小球变圆从底部往上弹（560ms，三次方减速），走过的地方把图一寸寸带出来，最后 25% 淡掉；
 * - 拿不到的那格 220ms 淡出缩到 .92 后拿掉，回复收进聊天记录时也不存（Chat.kt 收尾时过滤）；
 * - 透明底的图（四个角透明）垫浅色纸底、整张放下留 14% 边；普通图铺满裁边。
 * 时钟记在 SentPics（born / ready / gone）上，格子重画也接着来；带出来以后就从 fresh 里拿掉，之后是静止的图。
 */
private const val RATIO_MS = 320L
private const val REVEAL_MS = 560L
private const val GONE_MS = 220L
internal val Paper = Color(0xFFF3F1EC)

private fun ease3(x: Float): Float { val t = x.coerceIn(0f, 1f); return 1f - (1f - t) * (1f - t) * (1f - t) }

/** 一格的节拍（毫秒，从 born 起算）。null = 不用动（以前发的，或已经带出来了）。 */
private class Plan(val ready: Long?, val gone: Long?, val reveal: Long?)

private fun plan(ref: String): Plan? {
    if (ref !in SentPics.fresh) return null
    val b = SentPics.born[ref] ?: return null
    val r = SentPics.ready[ref]?.let { (it - b) / 1_000_000L }
    val g = SentPics.gone[ref]?.let { (it - b) / 1_000_000L }
    // 第一次「跳满一拍、比例也变完」之后的落地点（落地在每拍的正中）
    val rv = r?.let { rr -> val from = maxOf(Toy.PERIOD, rr + RATIO_MS); ((from - Toy.PERIOD / 2 + Toy.PERIOD - 1) / Toy.PERIOD) * Toy.PERIOD + Toy.PERIOD / 2 }
    return Plan(r, g, rv)
}

@Composable
private fun SentPicBlock(all: List<String>, onOpen: (String) -> Unit) {
    val anim = all.any { it in SentPics.fresh }
    val now = remember { androidx.compose.runtime.mutableLongStateOf(System.nanoTime()) }
    if (anim) LaunchedEffect(Unit) { while (true) androidx.compose.runtime.withFrameNanos { now.longValue = it } }
    // 以前发的图的比例：从缓存或文件头读一次
    val dims by produceState(emptyMap<String, Float>(), all) {
        value = all.associateWith { r -> (SentPics.dims[r] ?: SentPics.size(r))?.let { (w, h) -> w.toFloat() / h.coerceAtLeast(1) } ?: 1f }
    }
    val t = now.longValue
    fun ms(r: String): Long = SentPics.born[r]?.let { ((t - it) / 1_000_000L).coerceAtLeast(0L) } ?: 0L
    val plans = all.associateWith { plan(it) }
    // 带出来了 / 缩完了：从 fresh 拿掉
    androidx.compose.runtime.SideEffect {
        plans.forEach { (r, p) ->
            if (p == null) return@forEach
            val m = ms(r)
            if ((p.reveal != null && m >= p.reveal + REVEAL_MS) || (p.gone != null && m >= p.gone + GONE_MS)) SentPics.fresh.remove(r)
        }
    }
    val shown = all.filter { r ->
        val p = plans[r]
        when {
            p?.gone != null -> ms(r) < p.gone + GONE_MS
            p != null -> true
            else -> SentPics.fail(r) == null
        }
    }
    if (shown.isEmpty()) return
    fun ratio(r: String, lo: Float): Float {
        val real = (SentPics.dims[r]?.let { (w, h) -> w.toFloat() / h.coerceAtLeast(1) } ?: dims[r] ?: 1f).coerceIn(lo, 2.2f)
        val p = plans[r] ?: return real
        val rd = p.ready ?: return 1f
        return 1f + (real - 1f) * ease3((ms(r) - rd) / RATIO_MS.toFloat())
    }
    val big = 18.dp; val small = 8.dp
    Column(Modifier.padding(start = 2.dp, top = 4.dp, bottom = 12.dp)) {
        if (all.size == 1) {
            val r = shown[0]
            PicCell(r, RoundedCornerShape(big), Modifier.fillMaxWidth(.76f).aspectRatio(ratio(r, .6f)), plans[r], ms(r), onOpen)
            val cap = remember(r) { SentPics.caption(r) }
            val p = plans[r]
            if (cap.isNotEmpty() && (p == null || (p.reveal != null && ms(r) >= p.reveal + REVEAL_MS))) Txt(cap, Page.muted, 12.sp, Modifier.padding(start = 2.dp, top = 6.dp), maxLines = 2)
            return@Column
        }
        val rows = shown.chunked(2)
        Column(Modifier.fillMaxWidth(.86f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEachIndexed { ri, row ->
                val top = ri == 0; val bottom = ri == rows.lastIndex
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEachIndexed { ci, r ->
                        val left = ci == 0; val right = ci == row.lastIndex
                        val shape = RoundedCornerShape(
                            topStart = if (top && left) big else small, topEnd = if (top && right) big else small,
                            bottomEnd = if (bottom && right) big else small, bottomStart = if (bottom && left) big else small,
                        )
                        val k = ratio(r, if (row.size == 1) .75f else .5f)
                        PicCell(r, shape, Modifier.weight(k).aspectRatio(k), plans[r], ms(r), onOpen)
                    }
                }
            }
        }
    }
}

/** 一格：弹跳 → 小球往上带出图 → 静止的图；拿不到时淡出缩小。按下缩到 .97。 */
@Composable
private fun PicCell(ref: String, shape: Shape, modifier: Modifier, p: Plan?, ms: Long, onOpen: (String) -> Unit) {
    val ok = SentPics.ok(ref)
    val bmp by produceState(SentPics.cached(ref, 1080), ref, ok) { if (ok && value == null) value = SentPics.bitmap(ref, 1080) }
    val img = remember(bmp) { bmp?.asImageBitmap() }
    val clear = ok && SentPics.clear(ref)
    val still = Toy.still()
    val path = remember { androidx.compose.ui.graphics.Path() }
    val accent = Skin.accent
    val done = p == null || (p.reveal != null && ms >= p.reveal + REVEAL_MS)
    val gk = if (p?.gone != null) ((ms - p.gone) / GONE_MS.toFloat()).coerceIn(0f, 1f) else 0f
    Pressable({ if (done && img != null) onOpen(ref) }, modifier.graphicsLayer { alpha = 1f - gk; val s = 1f - .08f * gk; scaleX = s; scaleY = s }, label = "看大图") { pressed ->
        val ps by animateFloatAsState(if (pressed && done) .97f else 1f, tween(if (pressed) 80 else 200), label = "picPress")
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize().graphicsLayer { scaleX = ps; scaleY = ps }.clip(shape).background(veil(.07f))) {
            fun pic() {
                val im = img ?: return
                if (clear) {
                    drawRect(Paper)
                    val pad = minOf(size.width, size.height) * .14f
                    val k = minOf((size.width - 2 * pad) / im.width, (size.height - 2 * pad) / im.height)
                    val w = im.width * k; val h = im.height * k
                    drawImage(im, dstOffset = androidx.compose.ui.unit.IntOffset(((size.width - w) / 2).toInt(), ((size.height - h) / 2).toInt()),
                        dstSize = androidx.compose.ui.unit.IntSize(w.toInt(), h.toInt()), filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium)
                } else {
                    // 铺满裁边：取原图中间和格子同比例的一块
                    val k = maxOf(size.width / im.width, size.height / im.height)
                    val sw = (size.width / k).toInt().coerceIn(1, im.width); val sh = (size.height / k).toInt().coerceIn(1, im.height)
                    drawImage(im, srcOffset = androidx.compose.ui.unit.IntOffset((im.width - sw) / 2, (im.height - sh) / 2), srcSize = androidx.compose.ui.unit.IntSize(sw, sh),
                        dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()), filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium)
                }
            }
            val f = .45f * density
            val cx = size.width / 2f
            val gy = size.height - 16.dp.toPx()
            val rv = p?.reveal
            when {
                done -> pic()
                p?.gone != null -> Unit
                rv != null && ms >= rv && img != null -> {
                    val u = ((ms - rv) / REVEAL_MS.toFloat()).coerceIn(0f, 1f)
                    val e = ease3(u)
                    val rad = 15.5f * f
                    val by = gy - (gy + rad * 2f) * e
                    val top = (by - rad * .2f).coerceAtLeast(0f)
                    clipRect(top = top) { pic() }
                    val a = 1f - ((u - .75f) / .25f).coerceIn(0f, 1f)
                    val rx = rad * (.9f + .1f * u); val ry = rad * (1.15f - .15f * u)
                    drawOval(accent, androidx.compose.ui.geometry.Offset(cx - rx, by - ry), androidx.compose.ui.geometry.Size(rx * 2f, ry * 2f), alpha = a)
                }
                still -> with(Toy) { rest(path, cx, gy, f, accent) }
                else -> with(Toy) { bounce(path, ms, cx, gy, f, .5f, accent) }
            }
        }
    }
}

/** 看大图：深色底，图片居中；点一下或往上下拖开就收起，拖的时候图片跟着手指走。她发的图底下多「存到相册」「分享」。 */
@Composable
fun NoteImageViewer(ref: String?, onClose: () -> Unit) {
    var last by remember { mutableStateOf<String?>(null) }
    if (ref != null) last = ref
    val shown by animateFloatAsState(if (ref != null) 1f else 0f, tween(if (ref != null) 240 else 200), label = "viewer")
    if (shown <= 0f || last == null) return
    val cur = last!!
    val bmp by produceState<android.graphics.Bitmap?>(null, cur) { value = refBitmap(cur, 2048) }
    val drag = remember(cur) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(ref) { if (ref != null) drag.snapTo(0f) }
    androidx.activity.compose.BackHandler(enabled = ref != null) { onClose() }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .pointerInput(cur) { detectTapGestures { onClose() } }
                .pointerInput(cur) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (abs(drag.value) > size.height * .14f) onClose()
                            else scope.launch { drag.animateTo(0f, tween(220)) }
                        },
                    ) { ch, dy -> ch.consume(); scope.launch { drag.snapTo(drag.value + dy) } }
                },
            contentAlignment = Alignment.Center,
        ) {
            val fade = (1f - abs(drag.value) / 900f).coerceIn(.25f, 1f)
            Box(Modifier.fillMaxSize().alpha(shown * fade).background(rgba(6, 7, 9, .94f)))
            bmp?.let {
                // 透明底的图（她发的 logo 这类）垫浅色纸底，不然黑色的图在深色底上看不见
                val clear = SentPics.isRef(cur) && SentPics.clear(cur)
                Box(
                    Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = if (SentPics.isRef(cur)) 120.dp else 18.dp)
                        .graphicsLayer {
                            alpha = shown
                            translationY = drag.value
                            val sc = (.94f + .06f * shown) * (1f - abs(drag.value) / 4000f)
                            scaleX = sc; scaleY = sc
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.aspectRatio(it.width.toFloat() / it.height.coerceAtLeast(1))
                            .then(if (clear) Modifier.background(Paper, RoundedCornerShape(12.dp)).padding(22.dp) else Modifier),
                    ) { Image(it.asImageBitmap(), "图片", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                }
            }
        }
        if (SentPics.isRef(cur)) PicActions(cur, shown, Modifier.align(Alignment.BottomCenter))
    }
}

/** 看大图的底永远是深色，字和按钮不跟主题。 */
private val viewerInk = Color(0xFFF2EEE8)
private val viewerMuted = Color(0xFFB4AFA7)
private val viewerBtn = Color(1f, 1f, 1f, .12f)
private val viewerBtnPress = Color(1f, 1f, 1f, .2f)

/** 大图底下：说明、存到相册、分享。比图晚 120ms 从下面 12dp 升上来；存好了按钮变杏色写「已存好」。 */
@Composable
private fun PicActions(ref: String, shown: Float, modifier: Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var saved by remember(ref) { mutableStateOf<String?>(null) }
    var busy by remember(ref) { mutableStateOf(false) }
    val rise by animateFloatAsState(if (shown >= 1f) 1f else 0f, tween(320, delayMillis = 120, easing = Curves.decel), label = "acts")
    val cap = remember(ref) { SentPics.caption(ref) }
    Column(
        modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 26.dp)
            .graphicsLayer { alpha = rise * shown; translationY = (1f - rise) * 12.dp.toPx() },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (cap.isNotEmpty()) Txt(cap, viewerMuted, 13.sp, Modifier.padding(start = 22.dp, end = 22.dp, bottom = 12.dp), align = TextAlign.Center, maxLines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val done = saved == ""
            PressSurface(
                {
                    if (busy || done) return@PressSurface
                    busy = true
                    scope.launch { saved = SentPics.save(ctx, ref) ?: ""; busy = false }
                },
                shape = RoundedCornerShape(50), fill = if (done) Skin.accent else viewerBtn, pressedFill = if (done) Skin.accent else viewerBtnPress, pressScale = .95f, label = "存到相册",
            ) {
                Txt(
                    when { done -> "已存好"; saved != null -> "没存上：$saved"; busy -> "正在存…"; else -> "存到相册" },
                    if (done) Skin.onAccent else viewerInk, 14.sp, Modifier.padding(horizontal = 18.dp, vertical = 11.dp), maxLines = 1,
                )
            }
            PressSurface({ SentPics.share(ctx, ref) }, shape = RoundedCornerShape(50), fill = viewerBtn, pressedFill = viewerBtnPress, pressScale = .95f, label = "分享") {
                Txt("分享", viewerInk, 14.sp, Modifier.padding(horizontal = 18.dp, vertical = 11.dp))
            }
        }
    }
}
