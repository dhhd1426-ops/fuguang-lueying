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

/**
 * 打开就是聊天页（主页、时间天气都去掉了）。手记（浮光 / 掠影 / 绯句）和「我的」（含关于）都是同一种悬浮卡片，
 * 停在屏幕视觉中心，见 CenterCard.kt。
 */
@Composable
fun FuguangApp(prefs: Prefs, fullscreen: Boolean, setFullscreen: (Boolean) -> Unit) {
    val context = LocalContext.current
    val store = remember { ContentStore(context, prefs) }

    var paused by remember { mutableStateOf(false) }
    var foreground by remember { mutableStateOf(true) }
    var zen by remember { mutableStateOf(false) }
    var dim by remember { mutableIntStateOf(prefs.dim) }
    var blur by remember { mutableIntStateOf(prefs.blur) }

    val mineNav = remember { MineNav() }
    val assistant = remember { AssistantStore(prefs) }
    val chatState = remember { ChatState(ConvStore(prefs)) }

    // 切到后台时暂停，回来后按原状态继续
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) foreground = false
            if (e == Lifecycle.Event.ON_START) foreground = true
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    // 每次回到前台检查一次网上的新内容（掠影、绯句）
    LaunchedEffect(foreground) { if (foreground) runCatching { store.refresh() } }

    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(30_000) } }

    BackHandler(enabled = zen) { zen = false }

    val haze = rememberHazeState()

    BoxWithConstraints(Modifier.fillMaxSize().background(Page.ground)) {
        val screenW = maxWidth
        val compact = screenW < 750.dp
        val tiny = maxWidth < 370.dp
        val playing = !paused && foreground

        // 新对话还没聊：背景直接露出来；聊起来再虚化。沉浸时只看画面。
        val backBlur by androidx.compose.animation.core.animateDpAsState(
            when {
                zen -> 0.dp
                chatState.messages.isEmpty() && !chatState.busy -> 0.dp
                else -> 18.dp
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
        }

        CompositionLocalProvider(LocalHaze provides haze) {
            val chatAlpha = appear(!zen, 260)
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
                        onMine = { MineCard.open = true },
                        onSetup = { mineNav.screen = "provider/${assistant.activeProvider}"; MineCard.open = true },
                        onAssistants = { mineNav.screen = "assistant"; MineCard.open = true },
                    )
                }
            }
            // 沉浸时轻点任意处就回来
            if (zen) Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { zen = false } })
            AnimatedVisibility(zen, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                ShowUiButton(blur.dp) { zen = false }
            }
        }

        // 悬浮卡片：在页面之上、圆钮卡片之下
        if (!zen) MineCardLayer(assistant, mineNav)
        if (!zen) NoteCardLayer(store, prefs)

        if (!zen) CornerMenuLayer(
            dim = dim, blur = blur,
            onDim = { dim = it; prefs.dim = it },
            onBlur = { blur = it; prefs.blur = it },
            onNote = { NoteCard.from = CornerMenu.anchor.center; NoteCard.open = true },
            onZen = { zen = true },
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
