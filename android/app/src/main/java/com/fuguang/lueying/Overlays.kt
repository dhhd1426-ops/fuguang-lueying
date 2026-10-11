package com.fuguang.lueying

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.max

/** 网站主题切换的整页扩散：旧画面留在圆外，新主题从按钮处向外铺开。 */
@Composable
fun ThemeReveal(light: Boolean, content: @Composable (reveal: (Offset, () -> Unit) -> Unit) -> Unit) {
    val layer = rememberGraphicsLayer()
    var old by remember { mutableStateOf<ImageBitmap?>(null) }
    var center by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var busy by remember { mutableStateOf(false) }
    val radius = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    val reveal: (Offset, () -> Unit) -> Unit = { c, apply ->
        if (!busy) {
            busy = true
            scope.launch {
                try {
                    old = runCatching { layer.toImageBitmap() }.getOrNull()
                    center = c
                    radius.snapTo(0f)
                    apply()
                    if (old != null) {
                        val far = max(
                            max(hypot(c.x, c.y), hypot(size.width - c.x, c.y)),
                            max(hypot(c.x, size.height - c.y), hypot(size.width - c.x, size.height - c.y)),
                        )
                        radius.animateTo(far, tween(720, easing = CubicBezierEasing(.22f, .7f, .2f, 1f)))
                    }
                } finally {
                    old = null
                    busy = false
                }
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .drawWithContent {
                layer.record { this@drawWithContent.drawContent() }
                val snapshot = old
                if (snapshot == null) {
                    drawLayer(layer)
                } else {
                    val circle = Path().apply { addOval(Rect(center, radius.value)) }
                    clipPath(circle, ClipOp.Difference) { drawImage(snapshot) }
                    clipPath(circle) { drawLayer(layer) }
                }
            },
    ) { content(reveal) }
}

/** 半月 / 太阳 图标：月牙的缺口滑走，光芒旋转放大（网站 theme.css 的过渡）。 */
@Composable
fun ThemeToggle(onToggle: (Offset) -> Unit) {
    val p = LocalPalette.current
    val t by animateFloatAsState(if (p.light) 1f else 0f, tween(500, easing = CubicBezierEasing(.2f, .8f, .2f, 1f)), label = "icon")
    var center by remember { mutableStateOf(Offset.Zero) }
    val rays = remember { PathParser().parsePathString("M16 2v3m0 22v3M2 16h3m22 0h3M6 6l2 2m16 16l2 2M6 26l2-2M24 8l2-2").toPath() }
    Pressable(
        onClick = { onToggle(center) },
        modifier = Modifier.onGloballyPositioned { center = it.boundsInRoot().center },
        label = if (p.light) "切换到深色模式" else "切换到浅色模式",
    ) { pressed ->
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(if (pressed) p.toggleBg.copy(alpha = .3f) else p.toggleBg),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(23.dp).graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
                val s = this.size.minDimension / 32f
                scale(s, pivot = Offset.Zero) {
                    drawCircle(p.toggleInk, 9f, Offset(16f, 16f))
                    drawCircle(Color.Black, 9f, Offset(21f + 16f * t, 10f - 16f * t), blendMode = BlendMode.Clear)
                    if (t > 0f) {
                        rotate(-80f * (1 - t), Offset(16f, 16f)) {
                            scale(.65f + .35f * t, Offset(16f, 16f)) {
                                drawPath(rays, p.toggleInk.copy(alpha = t), style = Stroke(1.5f, cap = StrokeCap.Round))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CloseButton(color: Color, label: String, onClick: () -> Unit) {
    Pressable(onClick, Modifier.size(44.dp), label = label) { pressed ->
        SvgIcon(Icons.close, if (pressed) color.copy(alpha = .6f) else color)
    }
}

/** 弹层外壳：遮罩可点按关闭，内容居中，淡入并轻微放大。 */
@Composable
fun OverlayFrame(
    visible: Boolean,
    backdrop: Color,
    onDismiss: () -> Unit,
    alignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    AnimatedVisibility(visible, enter = fadeIn(tween(200)), exit = fadeOut(tween(160))) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(backdrop)
                    .clickable(remember { MutableInteractionSource() }, null, onClickLabel = "关闭") { onDismiss() },
            )
            Box(
                Modifier.fillMaxSize().imePadding(),
                contentAlignment = alignment,
            ) {
                Box(Modifier.animateEnterExit(enter = scaleIn(tween(220), initialScale = .97f), exit = scaleOut(tween(160), targetScale = .98f))) {
                    content()
                }
            }
        }
    }
}

/** 拦住点到卡片内部的点按，避免穿透到遮罩把弹层关掉。 */
@Composable
fun Modifier.swallowTaps(): Modifier = clickable(remember { MutableInteractionSource() }, null) {}

@Composable
fun Overlays(
    modal: ModalKind?,
    readerOpen: Boolean,
    lettersOpen: Boolean,
    letterIndex: Int?,
    content: Luoying,
    letters: List<Letter>,
    prefs: Prefs,
    compact: Boolean,
    dim: Int,
    blur: Int,
    onDim: (Int) -> Unit,
    onBlur: (Int) -> Unit,
    onCloseModal: () -> Unit,
    onCloseReader: () -> Unit,
    onCloseLetters: () -> Unit,
    onLetter: (Int?) -> Unit,
    onToggleTheme: (Offset) -> Unit,
) {
    val p = LocalPalette.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        var lastModal by remember { mutableStateOf(ModalKind.Settings) }
        if (modal != null) lastModal = modal
        OverlayFrame(modal != null, p.backdrop, onCloseModal) {
            ModalSheet(lastModal, min(440.dp, w - 36.dp), prefs, dim, blur, onDim, onBlur, onCloseModal)
        }
        val readerState = remember { ReaderState(prefs, content) }
        // 网上有新内容时，换成新的那份；读到一半的章节若还在，位置不变
        androidx.compose.runtime.LaunchedEffect(content) { readerState.data = content }
        val closeReader = { readerState.saveProgress(); onCloseReader() }
        OverlayFrame(readerOpen, p.readerBackdrop, closeReader) {
            Reader(readerState, w, h, closeReader, onToggleTheme)
        }
        val lettersScroll = remember { androidx.compose.foundation.ScrollState(0) }
        OverlayFrame(lettersOpen, p.lettersBackdrop, onCloseLetters) {
            Letters(letters, w, h, lettersScroll, onClose = onCloseLetters, onOpen = { onLetter(it) }, onToggleTheme = onToggleTheme)
        }
        var lastLetter by remember { mutableStateOf(0) }
        if (letterIndex != null) lastLetter = letterIndex
        OverlayFrame(letterIndex != null, p.detailBackdrop, { onLetter(null) }) {
            LetterDetail(letters, lastLetter.coerceAtMost(letters.size - 1), w, h, onIndex = { onLetter(it) }, onClose = { onLetter(null) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModalSheet(
    kind: ModalKind,
    width: Dp,
    prefs: Prefs,
    dim: Int,
    blur: Int,
    onDim: (Int) -> Unit,
    onBlur: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val p = LocalPalette.current
    val about = kind == ModalKind.About
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier
            .width(width)
            .clip(shape)
            .background(if (about) p.aboutBg else p.modalBg)
            .then(if (about) Modifier else Modifier.border(1.dp, if (p.light) css("#dce0e5") else rgba(255, 255, 255, .2f), shape))
            .swallowTaps()
            .verticalScroll(rememberScrollState())
            .padding(28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(kind.title, p.modalInk, 20.sp, Modifier.weight(1f), spacing = ls(3f, 20f))
            CloseButton(p.modalInk, "关闭", onClose)
        }
        Spacer(Modifier.height(if (about) 28.dp else 25.dp))
        when (kind) {
            ModalKind.Settings -> {
                SettingRow("背景暗度", "$dim%", dim, 0, 60, onDim)
                Spacer(Modifier.height(24.dp))
                SettingRow("界面玻璃模糊", "${blur}px", blur, 0, 24, onBlur)
                Spacer(Modifier.height(24.dp))
                Txt("调整会保存在本机。切到后台时，动态背景会自动暂停。", p.modalMuted, 12.sp, lineHeight = 24.sp)
            }
            ModalKind.Journal -> Journal(prefs)
            ModalKind.Chat, ModalKind.Mine -> {
                Txt("即将开放。", p.modalMuted, 13.sp, lineHeight = 26.sp)
            }
            ModalKind.Scene -> {
                val poster = rememberPoster()
                if (poster != null) {
                    Image(
                        poster, "当前背景场景",
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.FillWidth,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Txt("WLOP-Sky2-海琴烟\n使用你提供的壁纸包中的视频作为动态背景。", p.modalMuted, 12.sp, lineHeight = 24.sp)
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("30 秒循环", "3300 × 1532", "静音播放").forEach {
                        Txt(
                            it, p.modalInk, 10.sp,
                            Modifier.border(1.dp, p.modalLine, RoundedCornerShape(6.dp)).padding(horizontal = 9.dp, vertical = 5.dp),
                        )
                    }
                }
            }
            ModalKind.About -> {
                Txt("那些日子像掠过水面的影子，来不及看清，就已远去，只留下淡淡的怅惘。", p.aboutText, 12.sp, lineHeight = 24.sp)
                Spacer(Modifier.height(32.dp))
                Txt("背景作品：WLOP-Sky2-海琴烟。作品版权归原作者所有。", p.aboutText, 12.sp, lineHeight = 24.sp)
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun SettingRow(label: String, value: String, v: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    val p = LocalPalette.current
    Column {
        Row(Modifier.fillMaxWidth()) {
            Txt(label, p.modalMuted, 12.sp, Modifier.weight(1f))
            Txt(value, p.modalMuted, 12.sp)
        }
        Spacer(Modifier.height(14.dp))
        RangeSlider(v, min, max, p.sliderAccent, p.modalLine, onChange)
    }
}

@Composable
private fun Journal(prefs: Prefs) {
    val p = LocalPalette.current
    var text by remember { mutableStateOf(prefs.note) }
    var status by remember { mutableStateOf("仅保存在本机") }
    Column {
        Txt("记下此刻的一个念头。", p.modalMuted, 12.sp, lineHeight = 24.sp)
        Spacer(Modifier.height(12.dp))
        val shape = RoundedCornerShape(12.dp)
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = TextStyle(color = p.noteInk, fontSize = 13.sp, lineHeight = 24.7.sp),
            cursorBrush = SolidColor(p.sliderAccent),
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(shape)
                .background(p.noteBg)
                .border(1.dp, p.modalLine, shape)
                .padding(16.dp),
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) Txt("今天，想留下什么？", p.modalMuted, 13.sp)
                    inner()
                }
            },
        )
        Spacer(Modifier.height(15.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pressable({
                prefs.note = text
                status = "已保存在本机"
            }, label = "保存手记") { pressed ->
                Txt(
                    "保存手记", p.saveInk, 12.sp,
                    Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (pressed) p.saveBg.copy(alpha = .8f) else p.saveBg)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Txt(status, p.modalMuted, 10.sp)
        }
    }
}
