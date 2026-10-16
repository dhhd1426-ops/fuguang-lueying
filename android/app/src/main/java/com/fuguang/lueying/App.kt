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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
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
    Settings("显示设置"), Journal("此刻手记"), Scene("晚风与浮光"), About("关于浮光掠影"), Chat("聊天"), Mine("我的")
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
    var chatOpen by remember { mutableStateOf(false) }
    var mineOpen by remember { mutableStateOf(false) }
    val mineNav = remember { MineNav() }
    val assistant = remember { AssistantStore(prefs) }
    val chatState = remember { ChatState(ConvStore(prefs)) }

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
    BackHandler(enabled = !lettersOpen && !readerOpen && modal == null && !zen && chatOpen) { chatOpen = false }
    BackHandler(enabled = !lettersOpen && !readerOpen && modal == null && !zen && mineOpen) { if (!mineNav.back()) mineOpen = false }

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
                modal != null -> if (chatOpen || mineOpen) 18.dp else 4.dp
                // 新对话还没聊：背景直接露出来；聊起来再虚化
                chatOpen && chatState.messages.isEmpty() && !chatState.busy -> 0.dp
                chatOpen || mineOpen -> 18.dp
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
            val pageAlpha = appear(!zen && !chatOpen && !mineOpen, 260)
            if (pageAlpha > 0f) {
                HomePage(
                    compact = compact,
                    tiny = tiny,
                    width = screenW,
                    now = now,
                    paused = paused,
                    fullscreen = fullscreen,
                    blur = blur.dp,
                    modifier = Modifier.pageIn(pageAlpha),
                    onOpen = { modal = it },
                    newLuoying = store.newLuoying,
                    newLetters = store.newLetters,
                    onReader = { readerOpen = true; store.markLuoyingSeen() },
                    onLetters = { lettersOpen = true; store.markLettersSeen() },
                    onHome = { modal = null; readerOpen = false; lettersOpen = false; letterIndex = null; chatOpen = false; mineOpen = false },
                    onChat = { chatOpen = true; mineOpen = false },
                    onMine = { mineOpen = true; chatOpen = false; mineNav.screen = "mine" },
                    onZen = { zen = true },
                )
            }

            // 沉浸时轻点任意处就回来
            if (zen) Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { zen = false } })
            AnimatedVisibility(zen, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                ShowUiButton(blur.dp) { zen = false }
            }
        }
        }

        // 聊天页：盖在虚化后的背景上，自己不虚化
        CompositionLocalProvider(LocalHaze provides haze) {
            val chatAlpha = appear(chatOpen && !zen, 260)
            if (chatAlpha > 0f) {
                Box(Modifier.fillMaxSize().pageIn(chatAlpha)) {
                    ChatPage(
                        compact = compact,
                        tiny = tiny,
                        width = screenW,
                        blur = blur.dp,
                        hour = java.util.Calendar.getInstance().apply { time = now }.get(java.util.Calendar.HOUR_OF_DAY),
                        store = assistant,
                        chat = chatState,
                        onHome = { chatOpen = false },
                        onMine = { MineCard.open = true },
                        onSetup = { mineNav.screen = "provider/${assistant.activeProvider}"; MineCard.open = true },
                        onAssistants = { mineNav.screen = "assistant"; MineCard.open = true },
                        onOpen = { modal = it },
                    )
                }
            }
            val mineAlpha = appear(mineOpen && !zen, 260)
            if (mineAlpha > 0f) {
                Box(Modifier.fillMaxSize().pageIn(mineAlpha)) {
                    MinePage(
                        compact = compact,
                        tiny = tiny,
                        width = screenW,
                        blur = blur.dp,
                        store = assistant,
                        nav = mineNav,
                        onHome = { mineOpen = false },
                        onChat = { mineOpen = false; chatOpen = true },
                        onOpen = { modal = it },
                    )
                }
            }
        }

        // 「我的」卡片：在页面之上、圆钮卡片之下
        if (!zen) MineCardLayer(assistant, mineNav)
        if (!zen) WeatherCardLayer()

        if (!zen) CornerMenuLayer(
            active = when { chatOpen -> 1; mineOpen -> 3; else -> 0 },
            dim = dim, blur = blur,
            onDim = { dim = it; prefs.dim = it },
            onBlur = { blur = it; prefs.blur = it },
            onGo = { i ->
                when (i) {
                    0 -> { modal = null; readerOpen = false; lettersOpen = false; letterIndex = null; chatOpen = false; mineOpen = false }
                    1 -> { chatOpen = true; mineOpen = false }
                    2 -> modal = ModalKind.Journal
                    else -> { mineOpen = true; chatOpen = false; mineNav.screen = "mine" }
                }
            },
            onZen = { zen = true },
        )

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
    onChat: () -> Unit,
    onMine: () -> Unit,
    onZen: () -> Unit,
) {
    val side = if (compact) (if (tiny) 18.dp else 24.dp) else (width * .052f)
    Column(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(start = side, end = side, top = if (compact) 26.dp else 38.dp, bottom = if (compact) 22.dp else 30.dp),
    ) {
        Header(compact, tiny, now, newLuoying, newLetters, onOpen, onReader, onLetters, onHome)
        // 双击画面空白处：进入沉浸，只看画面
        Box(Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) { detectTapGestures(onDoubleTap = { onZen() }) }) {
            Hero(compact, Modifier.align(Alignment.CenterStart))
            // 时间天气：左下暗处，点开是天气卡片
            WeatherWidget(now, compact, Modifier.align(Alignment.BottomStart).offset(x = (-10).dp))
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
        Spacer(Modifier.weight(1f))
        val gap = if (tiny) 14.dp else if (compact) 22.dp else 38.dp
        Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
            NavItem("浮光", true, compact) { onOpen(ModalKind.Scene) }
            NavItem("掠影", false, compact, dot = newLuoying, onClick = onReader)
            NavItem("绯句", false, compact, dot = newLetters, onClick = onLetters)
            NavItem("关于", false, compact) { onOpen(ModalKind.About) }
        }
        if (!compact) {
            Spacer(Modifier.width(26.dp))
            val date = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(now).uppercase(Locale.ENGLISH)
            Txt(date, Page.muted, 10.sp, spacing = ls(2f, 10f))
        }
        Spacer(Modifier.width(if (tiny) 10.dp else if (compact) 18.dp else 26.dp))
        // 底栏收进这颗钮里：点开长出页面和显示设置
        CornerMenuButton(compact)
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
private fun Hero(compact: Boolean, modifier: Modifier) {
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
        Spacer(Modifier.height(if (compact) 26.dp else 32.dp))
        val isz = if (compact) 11f else 12f
        Txt(
            "时光匆匆而逝，\n回望只剩模糊余影的惆怅。",
            rgba(250, 245, 238, .67f),
            isz.sp,
            Modifier.widthIn(max = if (compact) 260.dp else 590.dp),
            spacing = ls(if (compact) 1f else 2f, isz),
            lineHeight = (isz * 2.1f).sp,
        )
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

@Suppress("unused")
private fun Modifier.debugDot(): Modifier = drawBehind { drawCircle(Page.accent, 2f, Offset.Zero) }

/** 换页：淡入时从稍大一点收回原位，像镜头推近；淡出时反过来。 */
private fun Modifier.pageIn(a: Float): Modifier = graphicsLayer {
    alpha = a
    val sc = 1f + (1f - a) * .035f
    scaleX = sc; scaleY = sc
}
