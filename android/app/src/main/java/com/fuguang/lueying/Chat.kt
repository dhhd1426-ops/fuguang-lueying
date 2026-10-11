package com.fuguang.lueying

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.only
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** 问候用的细意大利斜体（Cormorant Garamond Light Italic，打包时下载生成）。 */
private val Greeting = FontFamily(Font(R.font.cormorant_light_italic, FontWeight.Light, FontStyle.Italic))

private object ChatIcons {
    val newChat = Ic("M12 3.5a8.5 8.5 0 1 1-4.3 15.8L3.5 20.5l1.2-4.1A8.5 8.5 0 0 1 12 3.5zM12 8.5v7M8.5 12h7")
    val plus = Ic("M12 5v14M5 12h14")
    val mic = Ic("M5.5 11.5a6.5 6.5 0 0 0 13 0M12 18v2.5M9 6.5a3 3 0 0 1 6 0v5a3 3 0 0 1-6 0Z")
    val wave = Ic("M4 10v4M8 7v10M12 4.5v15M16 7v10M20 10v4")
    val up = Ic("M12 19V5M6 11l6-6 6 6")
    val stop = Ic("M7.5 7.5h9v9h-9Z")
    val down = Ic("M12 5v14M6 13l6 6 6-6")
}

/**
 * 一段对话的状态。回复的书写方式照云朵天气的 Amor：
 * 网络数据一阵一阵到，这里把它变成匀速、连续的书写——积压得多就写快一点，积压得少就放慢，
 * 最后几个字淡淡浮现；写到一半的 ** 符号先不露出来。
 */
class ChatState {
    val messages = mutableStateListOf<ChatMsg>()
    var partial by mutableStateOf("")
    var shown by mutableFloatStateOf(0f)
    var busy by mutableStateOf(false)
    var thinking by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var needsSetup by mutableStateOf(false)
    var draft by mutableStateOf("")
    /** 每发一条消息加一，用来触发“把这条消息滑到屏幕上方”。 */
    var sent by mutableIntStateOf(0)
    private var streamDone = false
    private var job: Job? = null

    fun reset() {
        job?.cancel(); job = null
        messages.clear(); partial = ""; shown = 0f; busy = false; thinking = false; error = null; needsSetup = false
    }

    fun send(scope: CoroutineScope, store: AssistantStore) {
        val text = draft.trim()
        if (text.isEmpty() || busy) return
        draft = ""
        error = null; needsSetup = false
        messages.add(ChatMsg("user", text))
        sent++
        ask(scope, store)
    }

    /** 上一次出错时，用同样的对话再问一次。 */
    fun retry(scope: CoroutineScope, store: AssistantStore) {
        if (busy || messages.lastOrNull()?.role != "user") return
        error = null; needsSetup = false
        ask(scope, store)
    }

    private fun ask(scope: CoroutineScope, store: AssistantStore) {
        val p = provider(store.activeProvider)
        val c = store.config(store.activeProvider)
        if (!c.configured || store.activeModel.isBlank()) {
            needsSetup = true
            error = "还没设置好 ${c.name} 的密钥或模型"
            return
        }
        busy = true; thinking = false; partial = ""; shown = 0f; streamDone = false
        val history = messages.toList()
        val system = "你是${store.name}，一个温和、真诚、说话简洁的中文助理。用自然的口吻回答，不堆砌格式。"
        job = scope.launch {
            try {
                ChatApi.stream(p, c, store.activeModel, system, history, store.deepThink, onThink = { thinking = true }) { partial += it; thinking = false }
                streamDone = true
                // 等书写追上再收尾
                snapshotFlow { shown >= partial.length }.first { it }
                if (partial.isNotBlank()) messages.add(ChatMsg("assistant", partial.trim()))
                else error = "没有收到回复，换个模型再试"
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (partial.isNotBlank()) messages.add(ChatMsg("assistant", partial.take(ceil(shown).toInt()).trim()))
                throw e
            } catch (e: Exception) {
                if (partial.isNotBlank()) messages.add(ChatMsg("assistant", partial.trim()))
                error = e.message ?: "出错了"
            } finally {
                partial = ""; shown = 0f; busy = false; thinking = false
            }
        }
    }

    fun stop() { job?.cancel() }

    /** 每帧推进一点书写进度。dt 单位秒。 */
    fun step(dt: Float) {
        val backlog = partial.length - shown
        if (backlog <= 0f) return
        val fast = streamDone
        val rate = max(if (fast) 90f else 26f, backlog / (if (fast) .35f else .9f))
        shown = min(partial.length.toFloat(), shown + rate * dt)
    }
}

/**
 * 底部导航“聊天”页。没有消息时：正中一颗浮光四角星 + 斜体问候 + 一道短暖线。
 * 有消息后：你发的消息会滑到屏幕上方，回复在下面慢慢写出来，屏幕不跟着滚，由你来滑。
 */
@Composable
fun ChatPage(
    compact: Boolean,
    tiny: Boolean,
    width: Dp,
    blur: Dp,
    hour: Int,
    store: AssistantStore,
    chat: ChatState,
    onHome: () -> Unit,
    onMine: () -> Unit,
    onSetup: () -> Unit,
    onMoreModels: () -> Unit,
    onOpen: (ModalKind) -> Unit,
) {
    val side = if (compact) (if (tiny) 18.dp else 24.dp) else (width * .052f)
    val scope = rememberCoroutineScope()
    val title = store.name
    val subtitle = "${store.name} · ${store.config(store.activeProvider).name} · ${store.activeModel}"

    // 书写节奏：有积压时每帧推进
    LaunchedEffect(chat) {
        while (true) {
            snapshotFlow { chat.partial.length > chat.shown }.first { it }
            var last = 0L
            while (chat.partial.length > chat.shown) {
                withFrameNanos { t ->
                    val dt = if (last == 0L) 1f / 60 else ((t - last) / 1e9f).coerceAtMost(.1f)
                    last = t
                    chat.step(dt)
                }
            }
        }
    }

    var picker by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = picker) { picker = false }
    Box(Modifier.fillMaxSize().background(rgba(14, 17, 23, .38f))) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .padding(start = side, end = side, top = if (compact) 26.dp else 38.dp, bottom = if (compact) 22.dp else 30.dp),
        ) {
            ChatHeader(compact, title, subtitle) { chat.reset() }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (chat.messages.isEmpty() && !chat.busy) Hello(compact, hour)
                else Conversation(chat, store, compact, onSetup = onSetup, onRetry = { chat.retry(scope, store) })
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ChatInput(
                    compact, blur, title, store.activeModel, store.deepThink, chat,
                    onPick = { picker = true },
                    onSend = { chat.send(scope, store) },
                    onStop = { chat.stop() },
                    modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                )
            }
            BottomNav(compact, active = "聊天", onHome = onHome, onChat = {}, onMine = onMine, onOpen = onOpen)
        }
        ModelDrawer(picker, store, compact, onClose = { picker = false }, onMore = { picker = false; onMoreModels() })
    }
}

@Composable
private fun ChatHeader(compact: Boolean, title: String, subtitle: String, onNew: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Pressable(onNew, Modifier.align(Alignment.CenterEnd), label = "新对话") { pressed ->
            SvgIcon(ChatIcons.newChat, if (pressed) Page.ink else Page.ink.copy(alpha = .85f), 22.dp, modifier = Modifier.padding(6.dp))
        }
    }
}

@Composable
private fun Hello(compact: Boolean, hour: Int) {
    val text = when (hour) {
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        else -> "Good evening"
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        SvgIcon(Icons.spark, Page.accent.copy(alpha = .85f), if (compact) 24.dp else 28.dp, stroke = .9f)
        Spacer(Modifier.height(if (compact) 16.dp else 20.dp))
        val fs = if (compact) 34f else 42f
        Txt(text, Page.ink.copy(alpha = .86f), fs.sp, spacing = ls(1.5f, fs), family = Greeting, weight = FontWeight.Light, maxLines = 1)
        Spacer(Modifier.height(if (compact) 14.dp else 18.dp))
        Box(Modifier.width(34.dp).height(1.dp).background(Page.accent.copy(alpha = .55f)))
    }
}

/* ---------------- 对话区 ---------------- */

@Composable
private fun Conversation(chat: ChatState, store: AssistantStore, compact: Boolean, onSetup: () -> Unit, onRetry: () -> Unit) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var viewport by remember { mutableIntStateOf(0) }
    var lastUserTop by remember { mutableFloatStateOf(0f) }
    var endY by remember { mutableFloatStateOf(0f) }
    val gapPx = with(density) { 14.dp.toPx() }
    val lastUser = chat.messages.indexOfLast { it.role == "user" }

    // 发出一条消息后，把它滑到屏幕上方（之后回复写出来时屏幕不动）
    LaunchedEffect(chat.sent) {
        if (chat.sent == 0) return@LaunchedEffect
        repeat(3) { withFrameNanos { } }
        scroll.animateScrollTo((lastUserTop - gapPx).toInt().coerceAtLeast(0))
    }
    // 打开时停在最新处
    LaunchedEffect(Unit) { withFrameNanos { }; withFrameNanos { }; scroll.scrollTo((lastUserTop - gapPx).toInt().coerceAtLeast(0)) }

    // 最后一条你的消息下面留足空白，让它能滑到屏幕上方；回答越长空白越少，屏幕位置不变
    val spacerPx = if (lastUser < 0) 0f else max(0f, viewport - (endY - lastUserTop) - with(density) { 22.dp.toPx() })

    Box(Modifier.fillMaxSize().onSizeChanged { viewport = it.height }) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(top = 6.dp)) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().align(Alignment.CenterHorizontally)) {
                chat.messages.forEachIndexed { i, m ->
                    if (m.role == "user") {
                        Box(
                            Modifier.fillMaxWidth().padding(top = if (i == 0) 0.dp else 26.dp, bottom = 16.dp)
                                .then(if (i == lastUser) Modifier.onGloballyPositioned { lastUserTop = it.positionInParent().y } else Modifier),
                            contentAlignment = Alignment.CenterEnd,
                        ) { UserBubble(m.text, compact) }
                    } else {
                        Reply(m.text, compact, fadeTail = false)
                    }
                }
                if (chat.busy) {
                    if (chat.partial.isEmpty()) ThinkingMotion(if (chat.thinking || store.deepThink) "正在思考…" else "${store.name} 正在想…", Modifier.padding(start = 2.dp, top = 2.dp))
                    else Reply(chat.partial.take(ceil(chat.shown).toInt()), compact, fadeTail = chat.shown < chat.partial.length + 1)
                }
                chat.error?.let { err ->
                    Column(Modifier.padding(top = 6.dp, start = 2.dp)) {
                        Txt(err, css("#d79a94"), 13.sp, lineHeight = 21.sp)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (chat.needsSetup) SmallButton("去设置", onSetup)
                            else SmallButton("重试", onRetry)
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().onGloballyPositioned { endY = it.positionInParent().y }.height(with(density) { spacerPx.toDp() }))
            }
        }
        // 内容（不算末尾空白）还在屏幕下面时，显示「↓ 回到最新」
        val below = endY - (scroll.value + viewport) > with(density) { 60.dp.toPx() }
        if (below) {
            Pressable(
                { scope.launch { scroll.animateScrollTo((endY - viewport + with(density) { 40.dp.toPx() }).toInt().coerceAtLeast(0)) } },
                Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                label = "回到最新",
            ) { _ ->
                Box(
                    Modifier.size(40.dp).background(rgba(30, 34, 42, .88f), CircleShape).border(1.dp, Page.line, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { SvgIcon(ChatIcons.down, Page.ink, 18.dp) }
            }
        }
    }
}

@Composable
private fun UserBubble(text: String, compact: Boolean) {
    Box(
        Modifier.widthIn(max = if (compact) 300.dp else 460.dp)
            .background(rgba(255, 255, 255, .08f), RoundedCornerShape(18.dp))
            .border(1.dp, rgba(255, 255, 255, .1f), RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) { Txt(text, Page.ink, 15.sp, lineHeight = 24.sp) }
}

@Composable
private fun SmallButton(text: String, onClick: () -> Unit) {
    Pressable(onClick, label = text) { pressed ->
        Txt(
            text, Page.ink, 13.sp,
            Modifier.background(rgba(255, 255, 255, if (pressed) .14f else .07f), RoundedCornerShape(50))
                .border(1.dp, Page.line, RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/* ---------------- 回复的排版：段落、**粗体**、列表、标题、代码 ---------------- */

private val replyStyle = TextStyle(color = Page.ink.copy(alpha = .9f), fontSize = 15.5.sp, lineHeight = 27.sp, letterSpacing = .01.em)

@Composable
private fun Reply(raw: String, compact: Boolean, fadeTail: Boolean) {
    var text = raw
    if (fadeTail) {
        // 写到一半的 **粗体** 或 `代码`，先不露出符号
        if (Regex("\\*\\*").findAll(text).count() % 2 == 1) text = text.substring(0, text.lastIndexOf("**")) + text.substring(text.lastIndexOf("**") + 2)
        if (text.replace("```", "").count { it == '`' } % 2 == 1) text = text.substring(0, text.lastIndexOf('`')) + text.substring(text.lastIndexOf('`') + 1)
    }
    val blocks = text.split(Regex("\\n{2,}")).filter { it.isNotBlank() }
    Column(Modifier.fillMaxWidth().padding(start = 2.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        blocks.forEachIndexed { bi, block ->
            val last = bi == blocks.lastIndex
            if (block.trimStart().startsWith("```")) {
                val code = block.trim().removePrefix("```").substringAfter('\n', "").removeSuffix("```").trimEnd()
                BasicText(
                    code,
                    Modifier.fillMaxWidth().background(rgba(0, 0, 0, .25f), RoundedCornerShape(12.dp)).padding(14.dp),
                    style = replyStyle.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 21.sp),
                )
            } else {
                BasicText(inline(block, if (fadeTail && last) 8 else 0), style = replyStyle)
            }
        }
    }
}

/** 一段文字转成带样式的文字；fade > 0 时最后几个字由淡到实。 */
private fun inline(block: String, fade: Int): AnnotatedString {
    val lines = block.lines().map { l ->
        val t = l.trimStart()
        when {
            t.startsWith("### ") || t.startsWith("## ") || t.startsWith("# ") -> "**" + t.trimStart('#').trim() + "**"
            t.startsWith("- ") || t.startsWith("* ") -> "•  " + t.drop(2)
            else -> l
        }
    }
    val src = lines.joinToString("\n")
    // 先算出纯文字和粗体区间
    val plain = StringBuilder()
    val bold = mutableListOf<IntRange>()
    var i = 0
    var start = -1
    while (i < src.length) {
        if (src.startsWith("**", i)) {
            if (start < 0) start = plain.length else { bold += start until plain.length; start = -1 }
            i += 2
        } else { plain.append(src[i]); i++ }
    }
    val s = plain.toString().replace(Regex("`([^`]*)`"), "$1")
    return buildAnnotatedString {
        append(s)
        bold.forEach { r -> if (r.first < s.length) addStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Page.ink), r.first, min(r.last + 1, s.length)) }
        if (fade > 0) {
            val n = min(fade, s.length)
            for (k in 0 until n) {
                val pos = s.length - 1 - k
                val a = .2f + .8f * k / fade
                addStyle(SpanStyle(color = Page.ink.copy(alpha = .9f * a)), pos, pos + 1)
            }
        }
    }
}

/* ---------------- 输入框 ---------------- */

@Composable
private fun ChatInput(
    compact: Boolean,
    blur: Dp,
    title: String,
    model: String,
    think: Boolean,
    chat: ChatState,
    onPick: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(22.dp)
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    Column(
        modifier
            .padding(top = 10.dp)
            .glass(shape, blur, fill = rgba(30, 34, 42, .42f))
            .padding(start = 16.dp, end = 12.dp, top = 15.dp, bottom = 12.dp),
    ) {
        BasicTextField(
            value = chat.draft,
            onValueChange = { chat.draft = it },
            textStyle = TextStyle(color = Page.ink, fontSize = if (compact) 15.sp else 16.sp, lineHeight = 24.sp),
            cursorBrush = SolidColor(Page.accent),
            maxLines = 6,
            modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp).padding(start = 4.dp),
            decorationBox = { inner ->
                Box {
                    if (chat.draft.isEmpty()) Txt("回信给 $title…", Page.muted, if (compact) 15.sp else 16.sp)
                    inner()
                }
            },
        )
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box {
                Box(Modifier.size(36.dp).border(1.dp, Page.line, CircleShape), contentAlignment = Alignment.Center) {
                    SvgIcon(ChatIcons.plus, Page.ink, 18.dp)
                }
            }
            Pressable(onPick, enabled = !chat.busy, label = "选择模型") { pressed ->
                Row(
                    Modifier.widthIn(max = 240.dp)
                        .background(rgba(255, 255, 255, if (pressed) .13f else .07f), RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 9.dp)
                        .alpha(if (chat.busy) .45f else 1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Txt(model.ifBlank { "选择模型" }, Page.ink, 12.sp, maxLines = 1)
                    Txt(if (think) " · 深思" else " · 自动", if (think) Page.accent else Page.muted, 12.sp, maxLines = 1)
                }
            }
            Spacer(Modifier.weight(1f))
            SvgIcon(ChatIcons.mic, Page.ink.copy(alpha = .85f), 21.dp)
            val canSend = chat.draft.isNotBlank() && !chat.busy
            val icon = when { chat.busy -> ChatIcons.stop; canSend -> ChatIcons.up; else -> ChatIcons.wave }
            Pressable(
                { if (chat.busy) onStop() else if (canSend) { onSend(); keyboard?.hide(); focus.clearFocus() } },
                label = when { chat.busy -> "停止"; canSend -> "发送"; else -> "语音" },
            ) { pressed ->
                Box(
                    Modifier.size(42.dp).background(rgba(233, 180, 142, if (pressed) .7f else .9f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { SvgIcon(icon, css("#2a211b"), 20.dp, stroke = 1.6f) }
            }
        }
    }
}


/* ---------------- 选择模型抽屉（方案 1：无边框，选中项淡暖色块） ---------------- */

@Composable
private fun ModelDrawer(open: Boolean, store: AssistantStore, compact: Boolean, onClose: () -> Unit, onMore: () -> Unit) {
    val shown by androidx.compose.animation.core.animateFloatAsState(
        if (open) 1f else 0f, tween(if (open) 320 else 220, easing = androidx.compose.animation.core.CubicBezierEasing(.2f, .8f, .2f, 1f)), label = "drawer",
    )
    if (shown <= 0f) return
    val warm = rgba(233, 180, 142, 1f)
    Box(Modifier.fillMaxSize()) {
        // 暗幕：点空白处收起
        Box(
            Modifier.fillMaxSize().alpha(shown).background(rgba(6, 9, 14, .5f))
                .pointerInput(Unit) { detectTapGestures { onClose() } },
        )
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            val h = maxHeight
            Column(
                Modifier
                    .widthIn(max = 560.dp).fillMaxWidth()
                    .graphicsLayer { translationY = (1f - shown) * h.toPx() * .6f; alpha = shown }
                    .background(rgba(20, 24, 31, .97f), RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                    .pointerInput(Unit) { detectTapGestures { } }
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(androidx.compose.foundation.layout.WindowInsetsSides.Bottom))
                    .heightIn(max = h * .78f)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 22.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(4.dp).background(rgba(255, 255, 255, .25f), RoundedCornerShape(2.dp)))
                Spacer(Modifier.height(14.dp))
                Box(Modifier.fillMaxWidth()) {
                    Pressable(onClose, Modifier.align(Alignment.CenterStart), label = "关闭") { _ ->
                        SvgIcon(Icons.close, Page.ink, 20.dp, modifier = Modifier.padding(6.dp))
                    }
                    Txt("选择模型", Page.ink, 16.sp, Modifier.align(Alignment.Center), weight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(10.dp))
                val ready = store.ready()
                if (ready.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    DrawerRow("还没有可用的模型", selected = false, dim = true) { onMore() }
                }
                ready.forEach { p ->
                    val c = store.config(p.id)
                    Txt(c.name.uppercase(), Page.muted, 10.sp, Modifier.padding(start = 6.dp, top = 14.dp, bottom = 8.dp), spacing = ls(2.5f, 10f))
                    c.models.forEach { m ->
                        val sel = store.activeProvider == p.id && store.activeModel == m
                        DrawerRow(m, selected = sel) { store.use(p.id, m); onClose() }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth().background(rgba(255, 255, 255, .06f), RoundedCornerShape(14.dp))
                        .padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Txt("深度思考", Page.ink, 15.sp, Modifier.weight(1f))
                    Pressable({ store.setThink(!store.deepThink) }, label = "深度思考") { _ ->
                        val on = store.deepThink
                        Box(
                            Modifier.width(46.dp).height(26.dp)
                                .background(if (on) warm.copy(alpha = .85f) else rgba(255, 255, 255, .14f), RoundedCornerShape(50)).padding(3.dp),
                            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
                        ) { Box(Modifier.size(20.dp).background(if (on) css("#2a211b") else Page.ink.copy(alpha = .7f), CircleShape)) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Pressable(onMore, Modifier.align(Alignment.CenterHorizontally), label = "更多模型") { pressed ->
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Txt("更多模型", if (pressed) Page.ink else Page.muted, 13.sp)
                        Spacer(Modifier.width(4.dp))
                        SvgIcon(Ic("M9.5 6l6 6-6 6"), Page.muted, 14.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerRow(text: String, selected: Boolean, dim: Boolean = false, onClick: () -> Unit) {
    Pressable(onClick, Modifier.fillMaxWidth().padding(bottom = 6.dp), label = text) { pressed ->
        Row(
            Modifier.fillMaxWidth()
                .background(
                    when { selected -> rgba(233, 180, 142, .16f); pressed -> rgba(255, 255, 255, .05f); else -> Color.Transparent },
                    RoundedCornerShape(14.dp),
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt(text, if (selected) Page.ink else if (dim) Page.muted else Page.ink.copy(alpha = .78f), 15.sp, Modifier.weight(1f), maxLines = 1)
            if (selected) SvgIcon(Ic("M5 12.5l4.5 4.5L19 7.5"), rgba(233, 180, 142, 1f), 18.dp, stroke = 1.8f)
        }
    }
}
