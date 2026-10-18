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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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

    BackHandler(enabled = zen) { zen = false; Zen.touch() }

    // 自动沉浸：停手 1 分钟进；最后 5 秒界面先淡一点。有东西开着、她在回答时一直从头算。
    var soon by remember { mutableStateOf(false) }
    val held = Zen.held || chatState.busy || MineCard.open || NoteCard.open || CornerMenu.open || !foreground || InBrowser.url != null || Downloads.open
    LaunchedEffect(held, Zen.touchedAt, zen) {
        soon = false
        if (held || zen) { if (held) Zen.touch(); return@LaunchedEffect }
        val left = Zen.IDLE_MS - (System.currentTimeMillis() - Zen.touchedAt)
        delay((left - Zen.WARN_MS).coerceAtLeast(0))
        soon = true
        delay(Zen.WARN_MS.coerceAtMost(left.coerceAtLeast(0)))
        soon = false; zen = true
    }
    // 沉浸时连状态栏、底部横条一起收起
    LaunchedEffect(zen) { setFullscreen(zen) }

    val haze = rememberHazeState()

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Page.ground)
            // 任何一次碰屏幕（点、滑、拖）都从头计时；只看不拦
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) { awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial); Zen.touch() }
                }
            },
    ) {
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
            // 退开时慢慢变清楚（1.1 秒），回来时快
            androidx.compose.animation.core.tween(if (zen) 1100 else 300, easing = Curves.standard),
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
            // 退开：1.1 秒慢慢淡掉；回来：0.26 秒。进之前最后 5 秒先淡到 82%
            val chatAlpha by androidx.compose.animation.core.animateFloatAsState(
                when { zen -> 0f; soon -> .82f; else -> 1f },
                androidx.compose.animation.core.tween(if (zen) 1100 else if (soon) 900 else 260, easing = Curves.standard), label = "zen",
            )
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
            // 沉浸时轻点任意处就回来：点的地方漾开一圈，这一下不传给下面
            var wakeAt by remember { mutableStateOf<Offset?>(null) }
            if (zen) Box(Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    val d = awaitFirstDown(requireUnconsumed = false); d.consume()
                    wakeAt = d.position; zen = false; Zen.touch()
                    do { val e = awaitPointerEvent(); e.changes.forEach { it.consume() } } while (e.changes.any { it.pressed })
                }
            })
            wakeAt?.let { at -> WakeRing(at) { wakeAt = null } }
        }

        // 悬浮卡片：在页面之上、圆钮卡片之下
        if (!zen) MineCardLayer(assistant, mineNav)
        if (!zen) NoteCardLayer(store, prefs)

        // 「这次用了」里点一样本事：去「我的 → 功能 → 技能」看它
        Skills.show = { id -> mineNav.screen = "skills/$id"; MineCard.open = true }
        // 同声翻译用现在聊天的模型来翻
        Live.store = assistant
        // 内置浏览器、下载页：盖在最上面
        InBrowserLayer()
        DownloadsLayer()

        if (!zen) CornerMenuLayer(
            dim = dim, blur = blur,
            onDim = { dim = it; prefs.dim = it },
            onBlur = { blur = it; prefs.blur = it },
            onNote = { NoteCard.from = CornerMenu.anchor.center; NoteCard.open = true },
        )
    }
}

/** 叫醒时那一圈：从指尖漾开 0.5 秒。 */
@Composable
private fun WakeRing(at: Offset, onEnd: () -> Unit) {
    val k = remember(at) { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(at) { k.animateTo(1f, androidx.compose.animation.core.tween(500, easing = Curves.decel)); onEnd() }
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        drawCircle(Page.ink.copy(alpha = .6f * (1f - k.value)), 6.dp.toPx() + 26.dp.toPx() * k.value, at, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
    }
}

@Suppress("unused")
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
