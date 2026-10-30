package com.fuguang.lueying

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.pow

/*
 * 她发的文件卡（第 348 版，原型 2dkXurrZ5wtRtivmwhnq3b 第 9 版）：
 * 左边 40×46 的角标，右边文件名和「Word · 9 KB」。
 * 刚发的先「正在打包」：角标是灰底，里面一块格式色的小方块按 Toy 的变形弹跳跳（一拍 1200ms，缩到 0.2、跳高压一半）；
 * 统一 1.8 秒——第二次落地那一拍换形换成圆，落地后挤压回弹做完（90ms），圆从落地点涨开铺满角标（340ms 减速），就是亮起来的格式色；
 * 然后「DOC」浮上来、折角翻下、副标题换字、卡片轻轻一顿（1.02）。真打包超过 1.8 秒就接着跳，好了的下一次落地再亮。
 * 以前发的、重开 App 之后，直接是亮着的样子。
 */

private const val MIN_MS = 1800L
private const val SETTLE = 90L
private const val GROW = 340L
private const val TOY_F = .2f
private const val TOY_J = .5f

@Composable
internal fun SentFileCard(ref: String) {
    val f = SentFiles.fmt(ref)
    val col = SentFiles.color(f)
    val still = Toy.still()
    val anim = remember(ref) { ref in SentFiles.fresh && !still }
    var landed by remember(ref) { mutableStateOf(!anim) }
    // 时钟记在文件上（SentFiles.born / last）：卡片被重画（回复收进聊天记录、切走再切回）也接着跳，不从头来
    val born = remember(ref) { SentFiles.born.getOrPut(ref) { System.nanoTime() } }
    val ms = remember(ref) { mutableLongStateOf(((System.nanoTime() - born) / 1_000_000L).coerceAtLeast(0L)) }
    var final by remember(ref) { mutableIntStateOf(SentFiles.last[ref] ?: -1) }
    val bump = remember(ref) { Animatable(1f) }
    val tagIn = remember(ref) { Animatable(if (anim) 0f else 1f) }
    // 落下来那一下只在刚放上去时做；重画时已经落过了就直接在位
    val rise = remember(ref) { Animatable(if (anim && ms.longValue < 320L) 0f else 1f) }
    val fail = SentFiles.fail(ref)
    val ok = SentFiles.ok(ref)

    LaunchedEffect(ref) {
        if (!anim) { if (ref in SentFiles.fresh) SentFiles.fresh.remove(ref); return@LaunchedEffect }
        if (rise.value < 1f) launch { rise.animateTo(1f, tween(320, easing = Curves.decel)) }
        while (true) {
            val acc = withFrameNanos { t -> ((t - born) / 1_000_000L).coerceAtLeast(0L) }
            ms.longValue = acc
            if (SentFiles.fail(ref) != null) break
            val c = acc / Toy.PERIOD; val land = c * Toy.PERIOD + Toy.PERIOD / 2
            // 换形开始前就定下这一拍是最后一拍：落地落成圆
            if (final < 0 && acc < land && acc >= land - 90 && land >= MIN_MS && SentFiles.ok(ref)) { final = c.toInt(); SentFiles.last[ref] = final }
            if (final >= 0 && acc >= final * Toy.PERIOD + Toy.PERIOD / 2 + SETTLE + GROW) break
        }
        landed = true
        SentFiles.fresh.remove(ref)
        launch { tagIn.animateTo(1f, tween(200, 40, Curves.decel)) }
        if (SentFiles.fail(ref) == null) { bump.animateTo(1.02f, tween(110, easing = Curves.standard)); bump.animateTo(1f, tween(150, easing = Curves.standard)) }
    }

    Pressable({ if (landed && ok) SentFiles.sheet = ref }, Modifier.widthIn(max = 300.dp).fillMaxWidth().padding(vertical = 4.dp), label = "打开文件 ${SentFiles.name(ref)}") { pressed ->
        val k by animateFloatAsState(if (pressed) .97f else 1f, tween(if (pressed) 70 else 180, easing = Curves.standard), label = "fileK")
        Row(
            Modifier.fillMaxWidth().graphicsLayer {
                // 落下来：上浮 10dp、从 0.96 放大、淡入（320ms 减速）；按下 0.97；亮起时一顿 1.02
                val s = k * bump.value * (.96f + .04f * rise.value); scaleX = s; scaleY = s
                alpha = rise.value; translationY = (1f - rise.value) * 10.dp.toPx()
            }
                .background(veil(if (pressed) .1f else .07f), RoundedCornerShape(16.dp))
                .padding(start = 10.dp, top = 10.dp, bottom = 10.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp, 46.dp).clip(RoundedCornerShape(8.dp)).background(if (landed && fail == null) col else veil(.12f)), contentAlignment = Alignment.BottomCenter) {
                if (!landed) ToyBadge(ms, final, col)
                if (landed && fail == null) {
                    // 右上折角
                    Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = tagIn.value }) {
                        val s = 11.dp.toPx()
                        drawRoundRect(Color.White.copy(alpha = .4f), Offset(size.width - s, -4.dp.toPx()), Size(s + 4.dp.toPx(), s + 4.dp.toPx()), CornerRadius(4.dp.toPx()))
                    }
                    Txt(
                        SentFiles.tag(f), Color.White, 10.sp,
                        Modifier.padding(bottom = 6.dp).graphicsLayer { alpha = tagIn.value; translationY = (1f - tagIn.value) * 4.dp.toPx() },
                        weight = FontWeight.Medium, spacing = .4.sp,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Txt(SentFiles.name(ref), Page.ink, 14.sp, maxLines = 1)
                val sub = when {
                    fail != null && landed -> "没打包成：$fail"
                    !landed -> "正在打包 ${SentFiles.label(f)}…"
                    else -> SentFiles.label(f) + " · " + Attach.kb(SentFiles.file(ref)?.length() ?: 0L)
                }
                Crossfade(sub, animationSpec = tween(140), label = "fileSub") { s -> Txt(s, if (fail != null && landed) Skin.danger else Page.muted, 12.sp, maxLines = 1) }
                if (!landed) {
                    Box(Modifier.padding(top = 3.dp).size(120.dp, 2.dp).clip(RoundedCornerShape(1.dp)).background(veil(.12f))) {
                        Box(Modifier.fillMaxSize().graphicsLayer {
                            val p = (ms.longValue / MIN_MS.toFloat()).coerceIn(0f, 1f)
                            scaleX = p; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, .5f)
                        }.background(Skin.accent))
                    }
                }
            }
        }
    }
}

/** 角标里的变形弹跳；最后一拍落成圆，再涨开铺满。 */
@Composable
private fun ToyBadge(ms: androidx.compose.runtime.MutableLongState, final: Int, col: Color) {
    val path = remember { Path() }
    Canvas(Modifier.fillMaxSize()) {
        val t = ms.longValue
        val f = TOY_F * density
        val cx = size.width / 2f
        val gy = size.height - 9.dp.toPx()
        val fl = if (final < 0) Long.MAX_VALUE else final * Toy.PERIOD + Toy.PERIOD / 2
        with(Toy) {
            when {
                t < fl -> {
                    var b = beat(t)
                    if (final >= 0 && t / Toy.PERIOD == final.toLong()) b = Toy.Beat(b.up, b.rot, b.prev, 3, b.m, b.sx, b.sy)
                    shape(path, cx, gy - b.up * f * TOY_J + (1 - b.sy) * 16f * f, f, b.prev, b.next, b.m, b.rot, b.sx, b.sy, col)
                }
                t < fl + SETTLE -> {
                    val b = beat(t)
                    shape(path, cx, gy + (1 - b.sy) * 16f * f, f, b.prev, 3, b.m, b.rot, b.sx, b.sy, col)
                }
                else -> {
                    val u = ((t - fl - SETTLE) / GROW.toFloat()).coerceIn(0f, 1f)
                    val e = 1f - (1f - u).pow(4)
                    val r0 = 15.5f * f
                    val cover = hypot(cx, gy) + 2.dp.toPx()
                    drawCircle(col, r0 + (cover - r0) * e, Offset(cx, gy))
                }
            }
        }
    }
}

/** 文件卡点开：从下面升起的面板——几行预览，打开 / 分享 / 存到手机。放在 App 最上层。 */
@Composable
fun SentFileLayer() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val t = remember { Animatable(0f) }
    var shown by remember { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(SentFiles.sheet) {
        val r = SentFiles.sheet
        if (r != null) { shown = r; t.animateTo(1f, tween(300, easing = Curves.decel)) } else { t.animateTo(0f, tween(220, easing = Curves.accel)); shown = null }
    }
    BackHandler(SentFiles.sheet != null) { SentFiles.sheet = null }
    Box(Modifier.fillMaxSize()) {
        val ref = shown
        if (ref != null && t.value > 0f) {
            Box(Modifier.fillMaxSize().background(Skin.scrim(.4f * t.value)).pointerInput(Unit) { detectTapGestures { SentFiles.sheet = null } })
            Column(
                Modifier.align(Alignment.BottomCenter).widthIn(max = 560.dp).fillMaxWidth()
                    .graphicsLayer { translationY = (1f - t.value) * size.height }
                    .background(Skin.sheet, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                    .pointerInput(Unit) { detectTapGestures { } }
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 14.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(veil(.16f)))
                Spacer(Modifier.height(12.dp))
                val pv = remember(ref) { SentFiles.preview(ref) }
                Column(Modifier.padding(horizontal = 4.dp).fillMaxWidth().background(Skin.cardFill(.9f), RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Txt(SentFiles.name(ref), Page.muted, 11.5.sp, maxLines = 1)
                    Spacer(Modifier.height(4.dp))
                    Txt(pv.trim().ifEmpty { "（没有预览）" }, Page.ink, 13.sp, lineHeight = 21.sp, maxLines = 5)
                }
                Spacer(Modifier.height(6.dp))
                listOf<Triple<String, String, () -> Unit>>(
                    Triple("打开", "交给 WPS、Word 或阅读器", { SentFiles.sheet = null; SentFiles.open(ctx, ref)?.let { toast = it } }),
                    Triple("分享", "发到微信、邮件…", { SentFiles.sheet = null; SentFiles.share(ctx, ref) }),
                    Triple("存到手机", "下载 / Vignette", { SentFiles.sheet = null; scope.launch { toast = SentFiles.save(ctx, ref) } }),
                ).forEach { (name, note, act) ->
                    Pressable(act, Modifier.fillMaxWidth(), label = name) { p ->
                        Row(
                            Modifier.fillMaxWidth().background(veil(if (p) .07f else 0f), RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Txt(name, Page.ink, 15.sp, Modifier.weight(1f))
                            Txt(note, Page.muted, 12.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
        BoxToast(toast, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 28.dp)) { toast = null }
    }
}
