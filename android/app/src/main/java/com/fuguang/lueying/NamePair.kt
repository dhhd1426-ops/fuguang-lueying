package com.fuguang.lueying

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.exp
import kotlin.math.roundToInt

private val warm = Color(0xFFE9B48E)
private val ink = Color(0xFFFAF5EE)
private val camera = Ic("M4 8h3l2-2.5h6L17 8h3v11H4Z", circles = listOf(floatArrayOf(12f, 13f, 3.4f)))
private val photo = Ic("M4 5h16v14H4ZM4 16l4.5-4.5 4 4 2.5-2.5L20 18")
private val letterIc = Ic("M9.5 15.5V9h5", circles = listOf(floatArrayOf(12f, 12f, 8f)))
private val meFill = Brush.linearGradient(listOf(warm, Color(0xFFB97F62)))

/** 心电的形状：u 是 0–1 的位置，回一个向上为正的高度（dp）。小 P 波、尖 R 波、回落 S、平缓的 T。 */
private fun beat(u: Float): Float {
    val x = u - .5f
    fun g(c: Float, w: Float) = exp(-((x - c) * (x - c)) / w)
    return 6f * g(-.16f, .0016f) - 4f * g(-.035f, .0002f) + 16f * g(0f, .00035f) - 7f * g(.03f, .0003f) + 5f * g(.17f, .002f)
}

/**
 * 名片里的两个人：左边她，右边你（头像 + 名字），中间一道心电，下面「认识第 N 天 · M 次聊天」。
 * 打开这一页走一遍：她的头像先轻跳，一点光从她那边沿线画出一个心跳，走到你这边你的头像跳一下，然后线淡下来停住。
 */
@Composable
internal fun NamePair(p: Persona, a: String, u: String, onA: (String) -> Unit, onU: (String) -> Unit) {
    val her = remember(p.id) { Animatable(1f) }
    val me = remember { Animatable(1f) }
    val head = remember(p.id) { Animatable(0f) }
    val settle = remember(p.id) { Animatable(0f) }
    var box by remember { mutableStateOf(Rect.Zero) }
    var ha by remember { mutableStateOf(Rect.Zero) }
    var ma by remember { mutableStateOf(Rect.Zero) }
    val scope = rememberCoroutineScope()
    fun pulse(x: Animatable<Float, *>) = scope.launch { x.snapTo(1f); x.animateTo(1.08f, tween(110)); x.animateTo(1f, spring(dampingRatio = .4f, stiffness = 520f)) }

    LaunchedEffect(p.id) {
        delay(260); pulse(her)
        delay(240)
        head.animateTo(1f, tween(1300, easing = CubicBezierEasing(.25f, .6f, .35f, 1f)))
        pulse(me)
        settle.animateTo(1f, tween(500))
    }
    // 换了头像：落下的那颗轻弹一下
    LaunchedEffect(AvatarCrop.landed) {
        if (AvatarCrop.landed > 0) pulse(if (AvatarCrop.who == Avatars.ME) me else her)
    }

    Box(Modifier.fillMaxWidth().onGloballyPositioned { box = it.boundsInRoot() }) {
        val d = LocalDensity.current.density
        Canvas(Modifier.matchParentSize()) {
            if (ha == Rect.Zero || ma == Rect.Zero) return@Canvas
            val x1 = ha.right - box.left + 8 * d; val x2 = ma.left - box.left - 8 * d
            val y = ha.center.y - box.top
            val len = x2 - x1
            if (len <= 0) return@Canvas
            val h = head.value
            val hx = x1 + len * h
            val rest = .3f
            val st = settle.value
            val yAt = { x: Float -> y - beat((x - x1) / len) * d }
            var x = x1
            val step = 2 * d
            while (x < hx && x < x2) {
                val glow = maxOf(rest, .95f * exp(-(hx - x) / (len * .35f)))
                val al = glow + (rest - glow) * st
                drawLine(warm.copy(alpha = al), Offset(x, yAt(x)), Offset(x + step, yAt(x + step)), 1.4f * d, StrokeCap.Round)
                x += step
            }
            if (h in 0.001f..0.999f) {
                drawCircle(warm.copy(alpha = .35f), 6 * d, Offset(hx, yAt(hx)))
                drawCircle(ink, 2.2f * d, Offset(hx, yAt(hx)))
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 18.dp, bottom = 14.dp)) {
            Side(Modifier.weight(1f), p.id, a.trim().take(1).ifEmpty { "助理" }, SolidColor(tintOf(p)), "助理名称", a, onA, her) { ha = it }
            Spacer(Modifier.width(64.dp))
            Side(Modifier.weight(1f), Avatars.ME, u.trim().takeLast(1).ifEmpty { "用户" }, meFill, "用户名称", u, onU, me) { ma = it }
        }
        if (ha != Rect.Zero) {
            // 线下面两行小字：第几天、聊了几次（在两颗头像中间的空里，不碰相机角标）
            Column(
                Modifier.align(Alignment.TopCenter).offset { IntOffset(0, (ha.center.y - box.top + 20 * d).roundToInt()) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (p.firstAt > 0) {
                    Txt("第 ${(System.currentTimeMillis() - p.firstAt) / 86_400_000L + 1} 天", Page.muted.copy(alpha = .75f), 11.sp, family = Serif, spacing = ls(1.3f, 11f), maxLines = 1)
                    Txt("${p.chats} 次聊天", Page.muted.copy(alpha = .45f), 9.5.sp, spacing = ls(.8f, 9.5f), maxLines = 1)
                } else Txt("还没聊过", Page.muted.copy(alpha = .5f), 10.5.sp, family = Serif, maxLines = 1)
            }
        }
    }
}

/** 一边：头像（点了换图）和宋体名字。名字空着时才露出「助理名称 / 用户名称」。 */
@Composable
private fun Side(
    modifier: Modifier, id: String, letter: String, fill: Brush, hint: String, value: String,
    onChange: (String) -> Unit, scale: Animatable<Float, *>, onAt: (Rect) -> Unit,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        AvatarButton(id, letter, fill, scale, onAt)
        Spacer(Modifier.height(12.dp))
        NameEdit(value, hint, onChange)
    }
}

/** 头像按钮：没图时右下角一颗杏色相机，点了直接开相册；有图时点了下面长出「换一张 | 用首字」。 */
@Composable
private fun AvatarButton(id: String, letter: String, fill: Brush, scale: Animatable<Float, *>, onAt: (Rect) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var at by remember { mutableStateOf(Rect.Zero) }
    val menu = remember { MutableTransitionState(false) }
    val has = Avatars.version.let { Avatars.get(id) != null }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { Avatars.load(ctx, uri)?.let { AvatarCrop.open(it, id, at) } }
    }
    fun choose() = pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    val cam = remember { Animatable(if (has) 0f else 1f) }
    LaunchedEffect(has) { cam.animateTo(if (has) 0f else 1f, spring(dampingRatio = .5f, stiffness = 500f)) }
    Box {
        Pressable({ if (has) menu.targetState = !menu.targetState else choose() }, label = if (id == Avatars.ME) "换你的头像" else "换她的头像") { pr ->
            Box(
                Modifier.size(54.dp).onGloballyPositioned { at = it.boundsInRoot(); onAt(at) }
                    .graphicsLayer { val s = scale.value * (if (pr) .94f else 1f); scaleX = s; scaleY = s },
            ) {
                AvatarFace(id, letter, fill, 54.dp)
                Box(
                    Modifier.align(Alignment.BottomEnd).offset(3.dp, 3.dp).size(19.dp)
                        .graphicsLayer { scaleX = cam.value; scaleY = cam.value; alpha = cam.value.coerceIn(0f, 1f) }
                        .background(Color(0xFF2D323C), CircleShape).padding(2.dp).background(warm, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { SvgIcon(camera, Color(0xFF2A211B), 11.dp, stroke = 2.2f) }
            }
        }
        if (menu.currentState || menu.targetState) {
            val gap = with(LocalDensity.current) { 10.dp.roundToPx() }
            Popup(
                popupPositionProvider = remember(gap) {
                    object : PopupPositionProvider {
                        override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
                            IntOffset(
                                (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(12, (windowSize.width - popupContentSize.width - 12).coerceAtLeast(12)),
                                anchorBounds.bottom + gap,
                            )
                    }
                },
                onDismissRequest = { menu.targetState = false },
                properties = PopupProperties(focusable = true),
            ) {
                AnimatedVisibility(
                    menu,
                    enter = fadeIn(tween(200)) + scaleIn(spring(dampingRatio = .72f, stiffness = 520f), .94f, TransformOrigin(.5f, 0f)),
                    exit = fadeOut(tween(140, easing = Curves.accel)) + scaleOut(tween(200, easing = Curves.accel), .94f, TransformOrigin(.5f, 0f)),
                ) {
                    Row(Modifier.width(210.dp).background(Color(0xFF323844), RoundedCornerShape(18.dp)).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        MenuTile("换一张", photo, Modifier.weight(1f)) { menu.targetState = false; choose() }
                        MenuTile("用首字", letterIc, Modifier.weight(1f)) { menu.targetState = false; Avatars.clear(id); scope.launch { scale.snapTo(.92f); scale.animateTo(1f, spring(dampingRatio = .4f, stiffness = 520f)) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuTile(text: String, icon: Ic, modifier: Modifier, onClick: () -> Unit) {
    PressSurface(onClick, modifier.height(40.dp), shape = RoundedCornerShape(13.dp), fill = Color.White.copy(alpha = .05f), pressedFill = Color.White.copy(alpha = .1f), pressScale = .96f, label = text) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SvgIcon(icon, warm, 16.dp, stroke = 1.6f)
            Spacer(Modifier.width(8.dp))
            Txt(text, Page.ink, 13.5.sp, maxLines = 1)
        }
    }
}

private val pen = Ic("M13.5 6.5l4 4M5 19l1-4.5L15.5 5a1.5 1.5 0 0 1 2 0l1.5 1.5a1.5 1.5 0 0 1 0 2L9.5 18z")
private val ok = Ic("M5 12.5l4.5 4.5L19 7.5")

/**
 * 名字：宋体，按字宽收着、居中，右边一支淡淡的笔。点笔进入编辑并全选；编辑时笔换成杏色 ✓，点 ✓ 或回车收起。
 * 左边垫一支笔宽的空，名字看起来还在正中。
 */
@Composable
private fun NameEdit(value: String, hint: String, onChange: (String) -> Unit) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val fm = androidx.compose.ui.platform.LocalFocusManager.current
    var editing by remember { mutableStateOf(false) }
    var tf by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(value)) }
    LaunchedEffect(value) { if (value != tf.text) tf = tf.copy(text = value) }
    val style = TextStyle(color = Page.ink, fontSize = 19.sp, fontFamily = Serif, letterSpacing = 1.sp)
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val d = LocalDensity.current
    val w = with(d) { (measurer.measure(tf.text.ifEmpty { hint }, style.copy(fontSize = if (tf.text.isEmpty()) 15.sp else 19.sp)).size.width + 6).toDp() }.coerceAtMost(112.dp)
    val swap by androidx.compose.animation.core.animateFloatAsState(if (editing) 1f else 0f, spring(dampingRatio = .5f, stiffness = 520f), label = "pen")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(24.dp))
        BasicTextField(
            value = tf, onValueChange = { tf = it; if (it.text != value) onChange(it.text) }, singleLine = true,
            textStyle = style.copy(textAlign = TextAlign.Center),
            cursorBrush = SolidColor(warm),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { fm.clearFocus() }),
            modifier = Modifier.width(w).focusRequester(focus).onFocusChanged { editing = it.isFocused },
            decorationBox = { inner ->
                Box(Modifier.drawBehind { if (editing) drawLine(warm.copy(alpha = .5f), Offset(0f, size.height), Offset(size.width, size.height), 1f) }, contentAlignment = Alignment.Center) {
                    if (tf.text.isEmpty()) Txt(hint, Page.muted.copy(alpha = .45f), 15.sp, family = Serif, spacing = ls(2f, 15f), maxLines = 1)
                    inner()
                }
            },
        )
        Pressable({
            if (editing) fm.clearFocus()
            else { tf = tf.copy(selection = androidx.compose.ui.text.TextRange(0, tf.text.length)); runCatching { focus.requestFocus() } }
        }, Modifier.size(24.dp, 30.dp), label = if (editing) "改好了" else "改名字") { pr ->
            Box(contentAlignment = Alignment.Center) {
                SvgIcon(pen, Page.muted.copy(alpha = if (pr) .8f else .45f), 14.dp, stroke = 1.6f, modifier = Modifier.graphicsLayer { val k = 1f - swap * .6f; scaleX = k; scaleY = k; alpha = 1f - swap })
                SvgIcon(ok, warm, 15.dp, stroke = 2f, modifier = Modifier.graphicsLayer { val k = .4f + .6f * swap; scaleX = k; scaleY = k; alpha = swap.coerceIn(0f, 1f) })
            }
        }
    }
}
