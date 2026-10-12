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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.foundation.layout.offset
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
class ChatState(val history: ConvStore) {
    init { Memory.seed(history) }
    val messages = mutableStateListOf<ChatMsg>()
    /** 当前这段对话在记录里的 id；还没发过消息时为 null。 */
    var convId by mutableStateOf<String?>(null)
    var partial by mutableStateOf("")
    var shown by mutableFloatStateOf(0f)
    var busy by mutableStateOf(false)
    var thinking by mutableStateOf(false)
    /** 正在进行的这轮思考原文。 */
    var thinkText by mutableStateOf("")
    /** 正在用工具时的一行提示，如「正在查阅羽的笔记…」。 */
    var toolNote by mutableStateOf("")
    /** 这轮回答里读到的羽的笔记图片。 */
    val pendingImgs = mutableStateListOf<String>()
    var error by mutableStateOf<String?>(null)
    var needsSetup by mutableStateOf(false)
    var draft by mutableStateOf("")
    /** 每发一条消息加一，用来触发“把这条消息滑到屏幕上方”。 */
    var sent by mutableIntStateOf(0)
    private var streamDone = false
    private var job: Job? = null

    fun reset() {
        job?.cancel(); job = null
        messages.clear(); partial = ""; shown = 0f; busy = false; thinking = false; thinkText = ""; error = null; needsSetup = false
        convId = null
    }

    /** 打开记录里的一段对话。 */
    fun open(c: Conv) {
        reset()
        messages.addAll(c.msgs)
        convId = c.id
        sent++
    }

    private fun persist() { convId = history.save(convId, messages) }

    /** 正在编辑重发第几条消息：-1 = 没有。 */
    var editing by mutableIntStateOf(-1)

    /** 发出输入框里的话；编辑重发时，先删掉那条及之后的内容。返回新消息的序号，没发出去是 -1。 */
    fun send(scope: CoroutineScope, store: AssistantStore): Int {
        val text = draft.trim()
        if (text.isEmpty() || busy) return -1
        draft = ""
        error = null; needsSetup = false
        if (editing in messages.indices) while (messages.size > editing) messages.removeAt(messages.lastIndex)
        editing = -1
        messages.add(ChatMsg("user", text))
        Memory.touch(newChat = messages.size == 1)
        persist()
        sent++
        ask(scope, store)
        return messages.lastIndex
    }

    /** 让 Amor 对最后一个问题重新回答。 */
    fun regenerate(scope: CoroutineScope, store: AssistantStore) {
        if (busy) return
        if (messages.lastOrNull()?.role == "assistant") messages.removeAt(messages.lastIndex)
        if (messages.lastOrNull()?.role != "user") return
        error = null; needsSetup = false
        persist()
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
        busy = true; thinking = false; thinkText = ""; partial = ""; shown = 0f; streamDone = false
        pendingImgs.clear()
        val history = messages.toList()
        val who = Memory.active
        job = scope.launch {
            try {
                // 连接了羽：给模型两个查笔记的工具
                Yu.refresh()
                val yu = Yu.connected
                val question = history.lastOrNull { it.role == "user" }?.text.orEmpty()
                ChatApi.stream(
                    p, c, store.activeModel, Memory.prompt(who) + (if (yu) "\n\n— 以下是你能用的工具 —\n" + Yu.system() else ""), history, store.deepThink,
                    onThink = { thinkText += it; thinking = true },
                    tools = if (yu) Tools(Yu.tools, { n, a -> Yu.run(n, a, Yu.vision(p.id)) { refs -> refs.forEach { r -> if (r !in pendingImgs) pendingImgs.add(r) } } }, { toolNote = if (it == "list_todos") "正在查看羽的待办…" else "正在查阅羽的笔记…" }) else null,
                    fallback = if (yu) ({ Yu.context(question) }) else null,
                ) { partial += it; thinking = false; toolNote = "" }
                streamDone = true
                // 等书写追上再收尾
                snapshotFlow { shown >= partial.length }.first { it }
                if (partial.isNotBlank()) {
                    messages.add(ChatMsg("assistant", partial.trim(), thinkText, pendingImgs.toList()))
                    // 全局记忆：回答完后在后台整理一下值得记住的事
                    val reply = partial.trim()
                    val cid = convId.orEmpty()
                    val title = this@ChatState.history.get(convId)?.title ?: firstSentence(question, 18)
                    scope.launch { Memory.learn(who, p, c, store.activeModel, question, reply, cid, title) }
                } else error = "没有收到回复，换个模型再试"
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (partial.isNotBlank()) messages.add(ChatMsg("assistant", partial.take(ceil(shown).toInt()).trim(), thinkText, pendingImgs.toList()))
                throw e
            } catch (e: Exception) {
                if (partial.isNotBlank()) messages.add(ChatMsg("assistant", partial.trim(), thinkText, pendingImgs.toList()))
                error = e.message ?: "出错了"
            } finally {
                partial = ""; shown = 0f; busy = false; thinking = false; thinkText = ""; toolNote = ""; pendingImgs.clear()
                persist()
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
    val title = Memory.active.name
    val subtitle = "${Memory.active.name} · ${store.config(store.activeProvider).name} · ${store.activeModel}"

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
    val plus = remember { LiquidPlusState() }
    val flight = remember { SendFlight() }
    androidx.activity.compose.BackHandler(enabled = plus.open) { plus.open = false }
    /** 正在看哪条消息的思考：-1 = 正在进行的这一轮。 */
    var thinkOf by remember { mutableIntStateOf(-1) }
    var thinkOpen by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<String?>(null) }
    // 长按菜单、选择文字、小提示
    var press by remember { mutableStateOf<MsgPress?>(null) }
    var selectText by remember { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    androidx.activity.compose.BackHandler(enabled = thinkOpen) { thinkOpen = false }
    androidx.activity.compose.BackHandler(enabled = picker) { picker = false }
    // 左侧菜单：从左边滑出，盖在聊天页上面（聊天页不动，只压暗）；点右边露出的部分收回
    var menu by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = menu) { menu = false }
    val menuT by androidx.compose.animation.core.animateFloatAsState(
        if (menu) 1f else 0f, tween(320, easing = androidx.compose.animation.core.CubicBezierEasing(.2f, .8f, .2f, 1f)), label = "menu",
    )
    val density = LocalDensity.current
    val menuW = min(width.value * .6f, 340f).dp
    val menuPx = with(density) { menuW.toPx() }
    Box(Modifier.fillMaxSize().background(rgba(14, 17, 23, .38f))) {
      Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .padding(start = side, end = side, top = if (compact) 26.dp else 38.dp, bottom = if (compact) 22.dp else 30.dp),
        ) {
            ChatHeader(compact, title, subtitle, onMenu = { menu = true }) { chat.reset() }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (chat.messages.isEmpty() && !chat.busy) Hello(compact, hour)
                else Conversation(chat, store, compact, flight, onSetup = onSetup, onRetry = { chat.retry(scope, store) }, onThink = { thinkOf = it; thinkOpen = true }, onImage = { viewing = it },
                    pressed = press?.index ?: -1, onLong = { if (!chat.busy || it.index < chat.messages.size) press = it })
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ChatInput(
                    compact, blur, title, store.activeModel, store.deepThink, chat, plus, flight,
                    onPick = { picker = true },
                    onSend = {
                        val n = chat.send(scope, store)
                        if (n >= 0) scope.launch { flight.launch(n) }
                    },
                    onStop = { chat.stop() },
                    modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                )
            }
            BottomNav(compact, active = "聊天", onHome = onHome, onChat = {}, onMine = onMine, onOpen = onOpen)
        }
        SendFlightLayer(flight)
        if (menuT > 0f) {
            Box(
                Modifier.fillMaxSize().alpha(menuT).background(rgba(6, 9, 14, .68f))
                    .pointerInput(Unit) { detectTapGestures { menu = false } },
            )
        }
      }
        if (menuT > 0f) {
            // 菜单本体：对话记录
            Box(
                Modifier.fillMaxHeight().width(menuW)
                    .graphicsLayer { translationX = (menuT - 1f) * menuPx }
                    .background(rgba(20, 23, 29, 1f))
                    .pointerInput(Unit) { detectTapGestures { } }
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                HistoryMenu(
                    chat.history, chat.convId,
                    onOpen = { if (!chat.busy) chat.open(it); menu = false },
                    onNew = { chat.reset(); menu = false },
                )
            }
        }
        LiquidPlusLayer(plus) { /* 照片、拍照、文件：入口先放好，功能后面接 */ }
        ModelDrawer(picker, store, compact, onClose = { picker = false }, onMore = { picker = false; onMoreModels() })
        val liveThink = thinkOf < 0 || thinkOf >= chat.messages.size
        ThinkDrawer(
            open = thinkOpen,
            raw = if (liveThink) chat.thinkText else chat.messages[thinkOf].think,
            live = liveThink && chat.busy && chat.partial.isEmpty(),
            onClose = { thinkOpen = false },
        )
        NoteImageViewer(viewing) { viewing = null }
        val pm = press?.let { chat.messages.getOrNull(it.index) }
        MsgMenu(
            press = if (pm != null) press else null, msg = pm,
            isLast = press?.index == chat.messages.lastIndex && !chat.busy,
            side = side,
            onClose = { press = null },
            onCopy = { pm?.let { clipboard.setText(AnnotatedString(it.text)); toast = "已复制" } },
            onSelect = { selectText = pm?.text },
            onEdit = {
                val i = press?.index ?: -1
                if (pm != null && i >= 0 && !chat.busy) { chat.editing = i; chat.draft = pm.text }
            },
            onRegen = { chat.regenerate(scope, store) },
        )
        SelectTextPage(selectText) { selectText = null }
        BoxToast(toast, Modifier.align(Alignment.BottomCenter).padding(bottom = 150.dp)) { toast = null }
    }
}

@Composable
private fun ChatHeader(compact: Boolean, title: String, subtitle: String, onMenu: () -> Unit, onNew: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Pressable(onMenu, Modifier.align(Alignment.CenterStart), label = "菜单") { pressed ->
            SvgIcon(Ic("M4 6.5h15M4 12h10M4 17.5h13"), if (pressed) Page.ink else Page.ink.copy(alpha = .85f), 22.dp, modifier = Modifier.padding(6.dp))
        }
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
private fun Conversation(
    chat: ChatState, store: AssistantStore, compact: Boolean, flight: SendFlight, onSetup: () -> Unit, onRetry: () -> Unit,
    onThink: (Int) -> Unit, onImage: (String) -> Unit, pressed: Int, onLong: (MsgPress) -> Unit,
) {
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
                        ) { UserBubble(m.text, compact, Modifier.longPressMsg(i, pressed == i, onLong).flightBubble(flight, i == flight.idx, 18.dp)) }
                    } else {
                        if (m.think.isNotBlank()) ThinkSummaryLine(thinkSummary(m.think)) { onThink(i) }
                        Box(Modifier.fillMaxWidth().longPressMsg(i, pressed == i, onLong)) { Reply(m.text, compact, fadeTail = false) }
                        NoteImageRow(m.imgs, onImage)
                    }
                }
                if (chat.busy) {
                    if (chat.partial.isEmpty() && chat.toolNote.isNotEmpty()) {
                        ThinkingMotion(chat.toolNote, Modifier.padding(start = 2.dp, top = 6.dp, bottom = 10.dp))
                    } else if (chat.partial.isEmpty()) {
                        if (chat.thinkText.isNotBlank()) {
                            // 深度思考中：三点动效 + 最新一句，点开看过程
                            Pressable({ onThink(-1) }, label = "查看思考过程") { _ ->
                                Row(Modifier.padding(start = 2.dp, top = 2.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    ThinkingMotion(latestSentence(chat.thinkText), Modifier.weight(1f, fill = false))
                                    Spacer(Modifier.width(6.dp))
                                    SvgIcon(Ic("M9.5 6l6 6-6 6"), Page.muted, 14.dp)
                                }
                            }
                        } else ThinkingMotion("", Modifier.padding(start = 2.dp, top = 6.dp))   // 没开深度思考：只有三点
                    } else {
                        if (chat.thinkText.isNotBlank()) ThinkSummaryLine(thinkSummary(chat.thinkText)) { onThink(-1) }
                    }
                    if (chat.partial.isNotEmpty()) Reply(chat.partial.take(ceil(chat.shown).toInt()), compact, fadeTail = chat.shown < chat.partial.length + 1)
                    NoteImageRow(chat.pendingImgs.toList(), onImage)
                    if (chat.partial.isNotEmpty() && chat.toolNote.isNotEmpty()) ThinkingMotion(chat.toolNote, Modifier.padding(start = 2.dp, top = 8.dp))
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
private fun UserBubble(text: String, compact: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier.widthIn(max = if (compact) 300.dp else 460.dp)
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
    plus: LiquidPlusState,
    flight: SendFlight,
    onPick: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(22.dp)
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val canSend = chat.draft.isNotBlank() && !chat.busy
    // 方案 2：开始打字，发送球从输入框里拉出来；删空了再被吸回去
    val t by androidx.compose.animation.core.animateFloatAsState(
        if (canSend) 1f else 0f,
        androidx.compose.animation.core.spring(dampingRatio = .62f, stiffness = 210f), label = "sep",
    )
    // 方案 3：发出去那一下，球先挤一下再弹回
    val pulse = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(flight.count) {
        if (flight.count == 0) return@LaunchedEffect
        pulse.animateTo(.62f, tween(110))
        pulse.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = .42f, stiffness = 320f))
    }
    var pressed by remember { mutableStateOf(false) }
    val ball = 42.dp
    val inset = 12.dp
    val sep = 54.dp
    Box(modifier.padding(top = 10.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(end = sep * t.coerceAtLeast(0f))
                .glass(shape, blur, fill = rgba(30, 34, 42, .42f))
                .padding(start = 16.dp, end = inset, top = 15.dp, bottom = inset),
        ) {
            // 编辑重发：输入框上方一条杏色提示，点 × 放弃
            if (chat.editing >= 0) {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    SvgIcon(Ic("M13.5 6.5l4 4M5 19l1-4.5L15.5 5a1.5 1.5 0 0 1 2 0l1.5 1.5a1.5 1.5 0 0 1 0 2L9.5 18z"), css("#e9b48e"), 15.dp)
                    Spacer(Modifier.width(6.dp))
                    Txt("编辑消息，发送后从这里重新回答", css("#e9b48e"), 12.5.sp, Modifier.weight(1f), maxLines = 1)
                    Pressable({ chat.editing = -1; chat.draft = "" }, label = "放弃编辑") { _ ->
                        SvgIcon(Icons.close, Page.muted, 16.dp, modifier = Modifier.padding(4.dp))
                    }
                }
            }
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
                LiquidPlusButton(plus) { /* 照片、拍照、文件：功能后面接 */ }
                Pressable(onPick, enabled = !chat.busy, label = "选择模型") { p ->
                    Row(
                        Modifier.widthIn(max = 240.dp)
                            .background(rgba(255, 255, 255, if (p) .13f else .07f), RoundedCornerShape(50))
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
                // 给球在框里留的位置：球拉出去后，这块空位也跟着收拢
                Spacer(Modifier.width((ball - 10.dp) * (1f - t).coerceIn(0f, 1f)))
            }
        }
        // 杏色的液体球（带黏连），画在输入框外层
        Box(Modifier.matchParentSize()) { SendGoo(t, pulse.value, pressed, inset, sep, ball) }
        val icon = when { chat.busy -> ChatIcons.stop; canSend -> ChatIcons.up; else -> ChatIcons.wave }
        Pressable(
            { if (chat.busy) onStop() else if (canSend) { onSend(); keyboard?.hide(); focus.clearFocus() } },
            Modifier.align(Alignment.BottomEnd).offset(x = -inset * (1f - t), y = -inset)
                .onGloballyPositioned { flight.from = it.boundsInRoot().center },
            label = when { chat.busy -> "停止"; canSend -> "发送"; else -> "语音" },
        ) { p ->
            LaunchedEffect(p) { pressed = p }
            Box(Modifier.size(ball).graphicsLayer { scaleX = pulse.value; scaleY = pulse.value }, contentAlignment = Alignment.Center) {
                SvgIcon(icon, css("#2a211b"), 20.dp, stroke = 1.6f)
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


/* ---------------- 深度思考可见 ---------------- */

/** 开始写回复后：只有一句总结 + ›，不带动效。 */
@Composable
private fun ThinkSummaryLine(summary: String, onClick: () -> Unit) {
    if (summary.isBlank()) return
    Pressable(onClick, Modifier.padding(bottom = 10.dp), label = "查看思考过程") { pressed ->
        Row(Modifier.padding(start = 2.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(summary, if (pressed) Page.ink else Page.muted, 14.sp, Modifier.weight(1f, fill = false), maxLines = 1)
            Spacer(Modifier.width(6.dp))
            SvgIcon(Ic("M9.5 6l6 6-6 6"), Page.muted, 14.dp)
        }
    }
}

/** 思考过程抽屉（方案 1）：时间线，一段一个节点；正在写的那段是橙点 + 动效；点节点看全文。 */
@Composable
private fun ThinkDrawer(open: Boolean, raw: String, live: Boolean, onClose: () -> Unit) {
    val shown by androidx.compose.animation.core.animateFloatAsState(
        if (open) 1f else 0f, tween(if (open) 320 else 220, easing = androidx.compose.animation.core.CubicBezierEasing(.2f, .8f, .2f, 1f)), label = "think",
    )
    var detail by remember(open) { mutableIntStateOf(-1) }
    if (shown <= 0f) return
    val segs = thinkSegments(raw)
    val orange = Color(0xFFD97757)
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().alpha(shown).background(rgba(6, 9, 14, .5f)).pointerInput(Unit) { detectTapGestures { onClose() } })
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            val h = maxHeight
            Column(
                Modifier
                    .widthIn(max = 640.dp).fillMaxWidth()
                    .height(h * .82f)
                    .graphicsLayer { translationY = (1f - shown) * h.toPx() * .6f; alpha = shown }
                    .background(rgba(20, 24, 31, .97f), RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(start = 22.dp, end = 22.dp, top = 10.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(4.dp).background(rgba(255, 255, 255, .25f), RoundedCornerShape(2.dp)))
                Spacer(Modifier.height(14.dp))
                Box(Modifier.fillMaxWidth()) {
                    if (detail >= 0) Pressable({ detail = -1 }, Modifier.align(Alignment.CenterStart), label = "返回") { _ ->
                        SvgIcon(Ic("M14.5 6l-6 6 6 6"), Page.ink, 20.dp, modifier = Modifier.padding(6.dp))
                    } else Pressable(onClose, Modifier.align(Alignment.CenterStart), label = "关闭") { _ ->
                        SvgIcon(Icons.close, Page.ink, 20.dp, modifier = Modifier.padding(6.dp))
                    }
                    Txt(
                        if (detail >= 0 && detail < segs.size) firstSentence(segs[detail], 16) else "思考过程",
                        Page.ink, 16.sp, Modifier.align(Alignment.Center).padding(horizontal = 44.dp), weight = FontWeight.SemiBold, maxLines = 1,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                    if (detail >= 0 && detail < segs.size) {
                        Txt(segs[detail], Page.ink.copy(alpha = .86f), 14.5.sp, lineHeight = 25.sp)
                    } else if (segs.isEmpty()) {
                        ThinkingMotion("", Modifier.padding(4.dp))
                    } else segs.forEachIndexed { k, seg ->
                        val isLive = live && k == segs.lastIndex
                        val last = k == segs.lastIndex
                        Pressable({ if (!isLive) detail = k }, Modifier.fillMaxWidth(), label = firstSentence(seg)) { pressed ->
                            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(if (pressed) rgba(255, 255, 255, .05f) else Color.Transparent, RoundedCornerShape(12.dp))) {
                                Box(Modifier.width(22.dp).fillMaxHeight()) {
                                    if (!last) Box(Modifier.padding(top = 18.dp).align(Alignment.TopCenter).width(1.dp).fillMaxHeight().background(rgba(255, 255, 255, .12f)))
                                    Box(Modifier.padding(top = 9.dp).align(Alignment.TopCenter).size(7.dp).background(if (isLive) orange else rgba(255, 255, 255, .32f), CircleShape))
                                }
                                Column(Modifier.weight(1f).padding(start = 8.dp, bottom = 18.dp, end = 6.dp)) {
                                    if (isLive) ThinkingMotion(latestSentence(seg, 24), size = 14.5.sp)
                                    else {
                                        Txt(firstSentence(seg, 24), Page.ink.copy(alpha = .9f), 14.5.sp, maxLines = 1)
                                        val rest = seg.removePrefix(Regex("[^。！？!?\\n]+[。！？!?]?").find(seg)?.value.orEmpty()).trim()
                                        if (rest.isNotEmpty()) {
                                            Spacer(Modifier.height(3.dp))
                                            Txt(rest.replace('\n', ' '), Page.muted, 12.5.sp, maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
