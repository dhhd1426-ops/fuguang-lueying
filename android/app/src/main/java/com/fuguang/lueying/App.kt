package com.fuguang.lueying

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ModalKind(val title: String) {
    Settings("显示设置"), Journal("此刻手记"), Scene("晚风与浮光"), About("关于浮光掠影")
}

@Composable
fun FuguangApp(prefs: Prefs, fullscreen: Boolean, setFullscreen: (Boolean) -> Unit) {
    val context = LocalContext.current
    val store = remember { ContentStore(context, prefs) }

    var paused by remember { mutableStateOf(false) }
    var foreground by remember { mutableStateOf(true) }
    var zen by remember { mutableStateOf(false) }
    var dim by remember { mutableIntStateOf(prefs.dim) }
    var blur by remember { mutableIntStateOf(prefs.blur) }
    var light by remember { mutableStateOf(prefs.theme == "light") }

    var modal by remember { mutableStateOf<ModalKind?>(null) }
    var readerOpen by remember { mutableStateOf(false) }
    var lettersOpen by remember { mutableStateOf(false) }
    var letterIndex by remember { mutableStateOf<Int?>(null) }

    // 切到后台时暂停，回来后按原状态继续（网站的 visibilitychange）
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) foreground = false
            if (e == Lifecycle.Event.ON_START) foreground = true
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    // 每次回到前台检查一次网上的新内容
    LaunchedEffect(foreground) { if (foreground) runCatching { store.refresh() } }

    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(30_000) } }

    // 返回键：依次关闭最上层
    BackHandler(enabled = letterIndex != null) { letterIndex = null }
    BackHandler(enabled = letterIndex == null && lettersOpen) { lettersOpen = false }
    BackHandler(enabled = !lettersOpen && readerOpen) { readerOpen = false }
    BackHandler(enabled = !lettersOpen && !readerOpen && modal != null) { modal = null }
    BackHandler(enabled = !lettersOpen && !readerOpen && modal == null && zen) { zen = false }

    val haze = rememberHazeState()
    val palette = if (light) LightPalette else DarkPalette

    BoxWithConstraints(Modifier.fillMaxSize().background(Page.ground)) {
        val screenW = maxWidth
        val compact = screenW < 750.dp
        val tiny = maxWidth < 370.dp
        val playing = !paused && foreground && !lettersOpen

        // 弹层打开时，后面的页面按网站 ::backdrop 一样虚化
        val backBlur by androidx.compose.animation.core.animateDpAsState(
            when {
                letterIndex != null -> 6.dp
                lettersOpen -> 8.dp
                readerOpen -> 9.dp
                modal == ModalKind.About -> 8.dp
                modal != null -> 4.dp
                else -> 0.dp
            },
            label = "backdrop",
        )
        Box(Modifier.fillMaxSize().blur(backBlur)) {
        Box(Modifier.fillMaxSize().hazeSource(haze)) {
            SceneBackground(
                playing = playing,
                positionX = if (compact) .61f else .62f,
                onFailed = { paused = true },
            )
            Shade(dim = dim / 100f, compact = compact, zen = zen)
        }

        CompositionLocalProvider(LocalHaze provides haze) {
            val pageAlpha = appear(!zen, 260)
            if (pageAlpha > 0f) {
                HomePage(
                    compact = compact,
                    tiny = tiny,
                    width = screenW,
                    now = now,
                    paused = paused,
                    fullscreen = fullscreen,
                    blur = blur.dp,
                    modifier = Modifier.alpha(pageAlpha),
                    onOpen = { modal = it },
                    newLuoying = store.newLuoying,
                    newLetters = store.newLetters,
                    onReader = { readerOpen = true; store.markLuoyingSeen() },
                    onLetters = { lettersOpen = true; store.markLettersSeen() },
                    onHome = { modal = null; readerOpen = false; lettersOpen = false; letterIndex = null },
                    onTogglePlay = { paused = !paused },
                    onZen = { zen = true },
                    onFullscreen = { setFullscreen(!fullscreen) },
                    onCredit = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://steamcommunity.com/sharedfiles/filedetails/?id=2886288494")))
                        }
                    },
                )
            }

            AnimatedVisibility(zen, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                ShowUiButton(blur.dp) { zen = false }
            }
        }
        }

        CompositionLocalProvider(LocalPalette provides palette) {
            ThemeReveal(light = light) { reveal ->
                Overlays(
                    modal = modal,
                    readerOpen = readerOpen,
                    lettersOpen = lettersOpen,
                    letterIndex = letterIndex,
                    content = store.luoying,
                    letters = store.letters,
                    prefs = prefs,
                    compact = compact,
                    dim = dim,
                    blur = blur,
                    onDim = { dim = it; prefs.dim = it },
                    onBlur = { blur = it; prefs.blur = it },
                    onCloseModal = { modal = null },
                    onCloseReader = { readerOpen = false },
                    onCloseLetters = { lettersOpen = false; letterIndex = null },
                    onLetter = { letterIndex = it },
                    onToggleTheme = { center ->
                        reveal(center) {
                            light = !light
                            prefs.theme = if (light) "light" else "dark"
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun HomePage(
    compact: Boolean,
    tiny: Boolean,
    width: Dp,
    now: Date,
    paused: Boolean,
    fullscreen: Boolean,
    blur: Dp,
    modifier: Modifier,
    onOpen: (ModalKind) -> Unit,
    newLuoying: Boolean,
    newLetters: Boolean,
    onReader: () -> Unit,
    onLetters: () -> Unit,
    onHome: () -> Unit,
    onTogglePlay: () -> Unit,
    onZen: () -> Unit,
    onFullscreen: () -> Unit,
    onCredit: () -> Unit,
) {
    val side = if (compact) (if (tiny) 18.dp else 24.dp) else (width * .052f)
    Column(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(start = side, end = side, top = if (compact) 26.dp else 38.dp, bottom = if (compact) 22.dp else 30.dp),
    ) {
        Header(compact, tiny, now, newLuoying, newLetters, onOpen, onReader, onLetters, onHome)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Hero(compact, tiny, width, onZen, Modifier.align(Alignment.CenterStart))
            if (!compact) {
                VerticalLabel("LIGHT / WIND / A QUIET MOMENT", Modifier.align(Alignment.CenterEnd))
            }
        }
        BottomBar(compact, now, paused, fullscreen, blur, onOpen, onTogglePlay, onZen, onFullscreen)
        Row(
            Modifier.fillMaxWidth().padding(top = if (compact) 16.dp else 21.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val fs = if (compact) 7f else 8f
            Txt("慢一点，看见光。", rgba(255, 255, 255, .35f), fs.sp, spacing = ls(if (compact) 1f else 2f, fs))
            Pressable(onCredit, label = "打开背景作品页面") { pressed ->
                Txt(
                    "背景：WLOP · SKY2 ↗",
                    if (pressed) Page.ink else rgba(255, 255, 255, .35f),
                    fs.sp,
                    Modifier.padding(5.dp),
                    spacing = ls(if (compact) 1f else 2f, fs),
                )
            }
        }
    }
}

@Composable
private fun Header(
    compact: Boolean,
    tiny: Boolean,
    now: Date,
    newLuoying: Boolean,
    newLetters: Boolean,
    onOpen: (ModalKind) -> Unit,
    onReader: () -> Unit,
    onLetters: () -> Unit,
    onHome: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Pressable(onHome, label = "浮光首页") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SvgIcon(Icons.spark, Page.ink, if (compact) 26.dp else 32.dp, stroke = 1f)
                Spacer(Modifier.width(if (compact) 8.dp else 13.dp))
                Column {
                    val bs = if (compact) 18f else 21f
                    Txt("浮光", Page.ink, bs.sp, spacing = ls(if (compact) 4f else 6f, bs))
                    Spacer(Modifier.height(5.dp))
                    Txt("FUGUANG", Page.muted, 8.sp, spacing = ls(4f, 8f))
                }
            }
        }
        Spacer(Modifier.weight(1f))
        val gap = if (tiny) 14.dp else if (compact) 22.dp else 38.dp
        Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
            NavItem("浮光", true, compact) { onOpen(ModalKind.Scene) }
            NavItem("掠影", false, compact, dot = newLuoying, onClick = onReader)
            NavItem("留言", false, compact, dot = newLetters, onClick = onLetters)
            NavItem("关于", false, compact) { onOpen(ModalKind.About) }
        }
        if (!compact) {
            Spacer(Modifier.width(26.dp))
            val date = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(now).uppercase(Locale.ENGLISH)
            Txt(date, Page.muted, 10.sp, spacing = ls(2f, 10f))
        }
        Spacer(Modifier.width(if (tiny) 10.dp else if (compact) 18.dp else 26.dp))
        val round = if (compact) 35.dp else 42.dp
        Pressable({ onOpen(ModalKind.Settings) }, label = "显示设置") { pressed ->
            Box(
                Modifier
                    .size(round)
                    .clip(CircleShape)
                    .background(rgba(255, 255, 255, if (pressed) .12f else .04f))
                    .border(1.dp, Page.line, CircleShape),
                contentAlignment = Alignment.Center,
            ) { SvgIcon(Icons.settings, Page.ink) }
        }
    }
}

@Composable
private fun NavItem(label: String, active: Boolean, compact: Boolean, dot: Boolean = false, onClick: () -> Unit) {
    Pressable(onClick, label = if (dot) "$label，有新内容" else label) { pressed ->
        Column(Modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val fs = if (compact) 11f else 12f
            Box {
                Txt(label, if (active || pressed || dot) Page.ink else Page.muted, fs.sp, spacing = ls(if (compact) 1f else 2f, fs))
                if (dot) {
                    // 有新章节或新留言：右上角一个暖色小点
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 5.dp, y = (-2).dp)
                            .size(5.dp)
                            .background(Page.dot, CircleShape),
                    )
                }
            }
            if (active) {
                Spacer(Modifier.height(8.dp))
                Box(Modifier.width(12.dp).height(2.dp).background(Page.accent))
            } else {
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun Hero(compact: Boolean, tiny: Boolean, width: Dp, onEnter: () -> Unit, modifier: Modifier) {
    Column(
        modifier
            .widthIn(max = if (compact) 340.dp else 590.dp)
            .padding(top = if (compact) 65.dp else 70.dp, bottom = if (compact) 60.dp else 100.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(30.dp).height(1.dp).background(Page.eyebrow))
            Spacer(Modifier.width(12.dp))
            val es = if (compact) 8f else 10f
            Txt("A MOMENT OF STILLNESS", Page.eyebrow, es.sp, spacing = ls(if (compact) 3f else 4f, es))
        }
        Spacer(Modifier.height(if (compact) 24.dp else 28.dp))
        val h1 = when {
            tiny -> 36f
            compact -> 43f
            else -> (width.value * .052f).coerceIn(42f, 78f)
        }
        val h1ls = ls(if (compact) 6f else 9f, h1)
        Txt("浮光", Page.ink, h1.sp, spacing = h1ls, lineHeight = (h1 * 1.38f).sp, family = Serif)
        Txt("掠影", rgba(250, 245, 238, .86f), h1.sp, spacing = h1ls, lineHeight = (h1 * 1.38f).sp, family = Serif)
        Spacer(Modifier.height(26.dp))
        val isz = if (compact) 11f else 12f
        Txt(
            "时光匆匆而逝，\n回望只剩模糊余影的惆怅。",
            rgba(250, 245, 238, .67f),
            isz.sp,
            Modifier.widthIn(max = if (compact) 260.dp else 590.dp),
            spacing = ls(if (compact) 1f else 2f, isz),
            lineHeight = (isz * 2.1f).sp,
        )
        Spacer(Modifier.height(34.dp))
        Pressable(onEnter, label = "进入此刻") { pressed ->
            Column(Modifier.width(IntrinsicSize.Max)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt("进入此刻", Page.ink, 12.sp, spacing = ls(2f, 12f))
                    Spacer(Modifier.width(if (pressed) 38.dp else 30.dp))
                    SvgIcon(Icons.arrow, Page.ink, 18.dp)
                }
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(rgba(239, 207, 180, if (pressed) .9f else .5f)))
            }
        }
    }
}

@Composable
private fun BottomBar(
    compact: Boolean,
    now: Date,
    paused: Boolean,
    fullscreen: Boolean,
    blur: Dp,
    onOpen: (ModalKind) -> Unit,
    onTogglePlay: () -> Unit,
    onZen: () -> Unit,
    onFullscreen: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val clock = SimpleDateFormat("HH:mm", Locale.CHINA).format(now)
    val weekday = SimpleDateFormat("M月d日EEEE", Locale.CHINA).format(now)

    @Composable
    fun SceneCard(mod: Modifier) {
        Pressable({ onOpen(ModalKind.Scene) }, mod, label = "查看当前动态背景") { pressed ->
            Row(
                Modifier
                    .then(if (compact) Modifier.fillMaxSize() else Modifier.fillMaxHeight())
                    .glass(shape, blur, fill = rgba(225, 229, 233, if (pressed) .12f else .065f))
                    .padding(if (compact) 16.dp else 22.dp, if (compact) 16.dp else 22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val poster = rememberPoster()
                if (poster != null) {
                    Image(
                        poster, "夕光场景缩略图",
                        Modifier.size(if (compact) 43.dp else 56.dp, if (compact) 48.dp else 56.dp).clip(RoundedCornerShape(9.dp)),
                        contentScale = ContentScale.Crop,
                        alignment = androidx.compose.ui.BiasAlignment(.24f, 0f),
                    )
                }
                Spacer(Modifier.width(if (compact) 12.dp else 20.dp))
                Column {
                    val ov = if (compact) 7f else 8f
                    Txt("NOW PLAYING · 01", Page.muted, ov.sp, spacing = ls(if (compact) 2f else 3f, ov), maxLines = 1)
                    Spacer(Modifier.height(9.dp))
                    val ts = if (compact) 12f else 14f
                    Txt("晚风与浮光", Page.ink, ts.sp, spacing = ls(2f, ts), maxLines = 1)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(4.dp).background(Page.dot, CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Txt(if (paused) "动态背景 · 已暂停" else "动态背景 · 循环播放", Page.muted, 9.sp, maxLines = 1)
                    }
                }
            }
        }
    }

    @Composable
    fun Tools(mod: Modifier) {
        Row(
            mod.glass(shape, blur).padding(horizontal = if (compact) 15.dp else 20.dp),
            horizontalArrangement = if (compact) Arrangement.SpaceAround else Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val h = if (compact) 75.dp else 96.dp
            Tool(if (paused) Icons.play else Icons.pause, if (paused) "播放" else "暂停", h, if (paused) "播放背景" else "暂停背景", onTogglePlay)
            Tool(Icons.eye, "纯净模式", h, "纯净模式", onZen)
            Tool(Icons.expand, if (fullscreen) "退出全屏" else "全屏", h, "全屏", onFullscreen)
            Tool(Icons.note, "写手记", h, "写手记") { onOpen(ModalKind.Journal) }
        }
    }

    @Composable
    fun TimeCard(mod: Modifier) {
        Column(
            mod.glass(shape, blur).padding(horizontal = if (compact) 13.dp else 24.dp, vertical = if (compact) 18.dp else 19.dp),
            horizontalAlignment = Alignment.End,
        ) {
            val cs = if (compact) 23f else 30f
            Txt(clock, Page.ink, cs.sp, spacing = ls(if (compact) 1f else 3f, cs), weight = FontWeight.Light)
            Spacer(Modifier.height(8.dp))
            val ws = if (compact) 8f else 9f
            Txt(weekday, Page.muted, ws.sp, spacing = ls(if (compact) 0f else 2f, ws), maxLines = 1)
        }
    }

    if (compact) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SceneCard(Modifier.weight(1f).fillMaxHeight())
                TimeCard(Modifier.widthIn(min = 100.dp).fillMaxHeight())
            }
            Tools(Modifier.fillMaxWidth())
        }
    } else {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SceneCard(Modifier.widthIn(min = 300.dp).fillMaxHeight())
            Tools(Modifier.fillMaxHeight())
            Spacer(Modifier.weight(1f))
            TimeCard(Modifier.widthIn(min = 158.dp).fillMaxHeight())
        }
    }
}


@Composable
private fun Tool(icon: Ic, label: String, height: Dp, a11y: String, onClick: () -> Unit) {
    Pressable(onClick, Modifier.width(67.dp).height(height), label = a11y) { pressed ->
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            val c = if (pressed) Page.ink.copy(alpha = 1f) else rgba(250, 245, 238, .78f)
            SvgIcon(icon, c, modifier = Modifier.graphicsLayer { scaleX = if (pressed) .9f else 1f; scaleY = scaleX })
            Spacer(Modifier.height(11.dp))
            Txt(label, c, 9.sp, spacing = ls(1f, 9f), maxLines = 1)
        }
    }
}

@Composable
private fun ShowUiButton(blur: Dp, onClick: () -> Unit) {
    Pressable(onClick, Modifier.navigationBarsPadding().padding(bottom = 25.dp), label = "显示界面") { pressed ->
        Row(
            Modifier
                .glass(RoundedCornerShape(50), blur.coerceAtLeast(10.dp), fill = rgba(20, 25, 30, if (pressed) .55f else .35f), border = Page.line)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SvgIcon(Icons.eye, Page.ink)
            Spacer(Modifier.width(10.dp))
            Txt("显示界面", Page.ink, 12.sp)
        }
    }
}

/** writing-mode: vertical-rl 的竖排小字。 */
@Composable
private fun VerticalLabel(text: String, modifier: Modifier) {
    Layout(
        content = { Txt(text, rgba(255, 255, 255, .4f), 9.sp, spacing = ls(4f, 9f), maxLines = 1) },
        modifier = modifier,
    ) { measurables, constraints ->
        val p = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = 2000))
        layout(p.height, p.width) {
            p.placeWithLayer(-(p.width - p.height) / 2, (p.width - p.height) / 2) {
                rotationZ = 90f
            }
        }
    }
}

@Suppress("unused")
private fun Modifier.debugDot(): Modifier = drawBehind { drawCircle(Page.accent, 2f, Offset.Zero) }
