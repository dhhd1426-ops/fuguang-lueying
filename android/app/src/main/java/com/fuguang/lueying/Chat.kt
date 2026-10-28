package com.fuguang.lueying

import androidx.compose.ui.graphics.RectangleShape
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
import androidx.compose.foundation.layout.ime
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.focus.focusRequester
import androidx.compose.animation.core.animateFloatAsState
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
    /** 这一轮的思考最后收进了哪条消息（-1 = 还没有）；开着思考抽屉时这轮结束，抽屉接着看那条。 */
    var landed by mutableIntStateOf(-1)
    /** 这一轮是哪家答的、什么时候开始想、最后一次想到什么时候（算「思考了 N 秒」）。 */
    var turnPid by mutableStateOf("")
    private var thinkAt = 0L
    private var thinkEnd = 0L
    /** 她在做事时你发的话：先排在聊天末尾，她做完手上一步就收到；这轮结束还没收到就接着发。 */
    val queue = mutableStateListOf<String>()
    /** 点了「立即发送」：停下这轮后马上把排着的发出去。 */
    private var sendNowAfterStop = false
    /** 这轮用过 / 正在用的工具，和思考段落排在同一条时间线上。 */
    val liveSteps = mutableStateListOf<ToolStep>()
    /** 这轮回答里读到的羽的笔记图片。 */
    val pendingImgs = mutableStateListOf<String>()
    /** 这次回复里做好的作品 id。 */
    val pendingWorks = mutableStateListOf<String>()
    /** 每张卡插在正文（partial）第几个字后面：她动手做的时候写到哪儿，卡就在哪儿。 */
    val pendingWorkAt = mutableStateListOf<Int>()
    /** 正在写 / 正在改的那件：卡先占好位置，写好了原地换成作品卡。 */
    var workDraft by mutableStateOf<WorkDraft?>(null)
    /** 这轮她解析 / 存过、这条对话里还没有卡的抖音：挂到她这条回复上。 */
    val pendingClips = mutableStateListOf<String>()
    var error by mutableStateOf<String?>(null)
    var needsSetup by mutableStateOf(false)
    /** 这一轮出的岔子、这一轮真做了的事（work / photo / web）、这一轮想起的做事经验（Lessons.kt）。 */
    private val slips = mutableSetOf<String>()
    private val happened = mutableSetOf<String>()
    private var shownLessons: List<Mem> = emptyList()
    var draft by mutableStateOf("")
    /** 输入框里排着要发的照片、文件（Attach.kt 的引用）。 */
    val atts = mutableStateListOf<String>()
    /** 每发一条消息加一，用来触发“把这条消息滑到屏幕上方”。 */
    var sent by mutableIntStateOf(0)
    private var streamDone = false
    private var job: Job? = null
    /** 是不是手上正看着的这段；切走后在后台写完（Chats.kt）。 */
    var onScreen = true

    fun reset() {
        job?.cancel(); job = null
        messages.clear(); queue.clear(); partial = ""; shown = 0f; busy = false; thinking = false; thinkText = ""; error = null; needsSetup = false
        convId = null
    }

    /** 打开记录里的一段对话。 */
    fun open(c: Conv) {
        // 对话只属于一位助理：打开别人的对话，就换到她那边
        if (c.who.isNotEmpty() && c.who != Memory.activeId) Memory.get(c.who)?.let { Memory.use(it) }
        reset()
        messages.addAll(c.msgs)
        convId = c.id
        Notify.cancel(c.id)
        sent++
    }

    private fun persist() { convId = history.save(convId, messages) }

    /** 换了助理：手上这段对话不是她的，就换成和她的新对话。 */
    fun followPersona() {
        val c = history.get(convId) ?: return
        if (c.who.isNotEmpty() && c.who != Memory.activeId) { if (busy) Chats.fresh() else reset() }
    }

    /** 上下文消息限制：这段对话到顶了，只能开新对话。 */
    val full: Boolean get() = Memory.active.limit > 0 && messages.size >= Memory.active.limit

    /** 正在编辑重发第几条消息：-1 = 没有。 */
    var editing by mutableIntStateOf(-1)
    /** 输入框在屏幕上的位置：编辑时气泡往这里飞。 */
    var inputAt by mutableStateOf(androidx.compose.ui.geometry.Rect.Zero)
    /** 让输入框拿到焦点（每加一次就要一次）。 */
    var focusTick by mutableIntStateOf(0)

    /** 发出输入框里的话；编辑重发时，先删掉那条及之后的内容。返回新消息的序号，没发出去是 -1。 */
    fun send(scope: CoroutineScope, store: AssistantStore): Int {
        val text = draft.trim()
        if ((text.isEmpty() && atts.isEmpty()) || (full && editing < 0)) return -1
        // 她还在做：话排在末尾，不打断她；照片、文件留在输入框里，等她做完再一起发
        if (busy) { if (text.isEmpty()) return -1; draft = ""; editing = -1; queue.add(text); return -1 }
        draft = ""
        error = null; needsSetup = false
        // 这句是在纠正她、或是改了话重发：记成岔子，这一轮完了她给自己记一句
        val correcting = editing < 0 && Lessons.isCorrection(text, messages.lastOrNull())
        Lessons.settleTalk(Memory.active, text, correcting)
        if (correcting) slips += Lessons.CORRECT
        if (editing in messages.indices) { slips += Lessons.REDO; while (messages.size > editing) messages.removeAt(messages.lastIndex) }
        editing = -1
        // 文字里有抖音链接：卡片挂在这条消息下面（先是「正在认」，认出了就填满）
        messages.add(ChatMsg("user", text, clips = listOfNotNull(VideoStore.offer(text)?.link, PanStore.offer(text)?.link, TgStore.offer(text)?.link), files = atts.toList()))
        atts.clear()
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
        slips += Lessons.REDO
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
        busy = true; thinking = false; thinkText = ""; partial = ""; shown = 0f; streamDone = false; landed = -1; turnPid = store.activeProvider; thinkAt = 0L; thinkEnd = 0L
        pendingImgs.clear(); pendingWorks.clear(); pendingWorkAt.clear(); workDraft = null; pendingClips.clear(); liveSteps.clear(); happened.clear()
        val who = Memory.active
        val history = messages.toList()
        // 做事经验：按这一轮要做的事想起几条，时间线上记一步「想起上次」
        val pv = store.activeProvider + "|" + store.activeModel.trim().lowercase()
        shownLessons = Lessons.pick(who, history, pv)
        if (shownLessons.isNotEmpty()) liveSteps.add(ToolStep(0, Lessons.STEP, "想起上次", shownLessons.joinToString("；") { it.text }, done = true))
        if (history.lastOrNull()?.files?.any { Attach.isImage(it) } == true) happened += "photo"
        // 思考档位照 Claude App：选哪档就原样发哪档（每个型号各记各的），App 不替模型猜
        val effort = store.effort()
        ChatApi.temperature = who.temp
        ChatApi.topP = if (who.topOn) who.top else null
        // 你切走了她也接着回完；回好了、你不在这里，就发通知（Notify.kt）
        val roundAt = System.currentTimeMillis()
        val mine = history.lastOrNull { it.role == "user" }?.text
        Notify.startLive(convId, this@ChatState.history, mine)
        job = scope.launch {
            try {
                // 她看不了图：先请能看图的帮她看一眼（AttachLook.kt），时间线上记一步
                if (!Attach.vision(p.id, store.activeModel)) {
                    val pics = history.filter { it.role == "user" && it.files.isNotEmpty() }.takeLast(2).flatMap { it.files }.filter { Attach.isImage(it) && AttachLook.seen(it) == null }
                    if (pics.isNotEmpty()) {
                        val idx = liveSteps.size
                        liveSteps.add(ToolStep(0, Materials.LOOK, "正在看照片", "", done = false))
                        val err = AttachLook.ensure(pics, store)
                        if (idx < liveSteps.size) liveSteps[idx] = liveSteps[idx].copy(title = if (err == null) "看了${if (pics.size > 1) " ${pics.size} 张" else ""}照片" else "照片没看清", detail = err.orEmpty(), done = true)
                        if (err != null) { slips += Lessons.PHOTO; happened -= "photo" }
                    }
                }
                // 连接了羽：给模型查笔记的工具；开着跨聊天回忆：再给一个翻以前聊天的工具
                Yu.refresh()
                val yu = Yu.connected
                val recall = who.memOn
                val store0 = this@ChatState.history
                val cur = convId
                val question = history.lastOrNull { it.role == "user" }?.text.orEmpty()
                val mcp = Mcp.specs
                val web = WebSearch.usable
                val skills = Skills.specs()
                val specs = Memory.toolSpecs + Works.specs.filter { it.name != Works.MAKE } + WorkJobs.spec + Materials.specs + VideoParse.specs + PanTools.specs + ClockTools.specs + skills + (if (web) listOf(WebSearch.spec) else emptyList()) + (if (yu) Yu.tools else emptyList()) + (if (recall) listOf(Recall.spec) else emptyList()) + mcp
                val convTitle0 = store0.get(cur)?.title ?: firstSentence(question, 18)
                val extra = buildString {
                    append("\n\n你手边还有这些东西，需要时再用，不需要就不用：")
                    append("\n").append(Memory.toolRule())
                    append("\n").append(Works.system(Memory.userCall, history, who.id))
                    append("\n").append(Materials.system())
                    append("\n").append(VideoParse.system(Memory.userCall))
                    append("\n").append(PanTools.system(Memory.userCall))
                    append("\n").append(ClockTools.system(Memory.userCall))
                    append("\n\n").append(Craft.system())
                    if (skills.isNotEmpty()) append("\n").append(Skills.system())
                    if (web) append("\n").append(WebSearch.system())
                    if (recall) append("\n").append(Recall.system())
                    if (yu) append("\n").append(Yu.system())
                    if (mcp.isNotEmpty()) append("\n").append(Mcp.system())
                }
                ChatApi.stream(
                    p, c, store.activeModel, Prompt.system(who, store.activeModel), Entries.weave(who, history, Prompt.aside(who, history, who.prevAt) + Lessons.aside(shownLessons, slips)), effort,
                    onThink = { thinkText += it; thinking = true; val t = System.currentTimeMillis(); if (thinkAt == 0L) thinkAt = t; thinkEnd = t },
                    tools = if (specs.isNotEmpty()) Tools(
                        specs,
                        { n, a ->
                            // 记进时间线：思考原文在这里断一段，工具排在它后面
                            if (thinkText.isNotBlank() && !thinkText.endsWith("\n\n")) thinkText += "\n\n"
                            val idx = liveSteps.size
                            liveSteps.add(ToolStep(thinkText.length, n, Trail.liveTitle(n), Trail.detail(n, a), done = false))
                            var made = n != Works.MAKE && n != Works.EDIT
                            if (!made && workDraft == null) workDraft = WorkDraft(partial.length, n, 0, WorkDraft.title(n, a.toString()))
                            when (n) { Works.MAKE, Works.EDIT, WorkJobs.START -> happened += "work"; Materials.LOOK -> happened += "photo"; WebSearch.TOOL -> happened += "web" }
                            try {
                            if (n == Memory.REMEMBER || n == Memory.FORGET) Memory.runTool(who, n, a, cur.orEmpty(), convTitle0)
                            else if (n == Recall.TOOL) Recall.run(store0, cur, a.optString("query"))
                            else if (n == WebSearch.TOOL) WebSearch.run(a.optString("query"))
                            else if (n == VideoParse.TOOL || n == VideoParse.SAVE) VideoParse.run(n, a) { l -> if (messages.none { l in it.clips } && l !in pendingClips) pendingClips.add(l) }
                            else if (n == PanTools.OPEN || n == PanTools.GET) PanTools.run(n, a) { l -> if (messages.none { l in it.clips } && l !in pendingClips) pendingClips.add(l) }
                            else if (n in ClockTools.names) ClockTools.run(n, a, who.id) { l -> if (l !in pendingClips) pendingClips.add(l) }
                            else if (n == Materials.OPEN || n == Materials.LOOK) Materials.run(n, a, Yu.vision(p.id))
                            else if (n == Skills.OPEN || n == Skills.FIND || n == Skills.LEARN) Skills.run(n, a)
                            else if (n == WorkJobs.START) WorkJobs.start(a, this@ChatState, store, who.id, history) { ref -> pendingWorks.add(ref); pendingWorkAt.add(partial.length) }
                            else if (n == Works.MAKE || n == Works.READ || n == Works.EDIT) Works.run(n, a, who.id) { id -> made = true; if (id !in pendingWorks) { pendingWorks.add(id); pendingWorkAt.add(workDraft?.at ?: partial.length) } }
                            else if (Mcp.owns(n)) Mcp.run(n, a)
                            else Yu.run(n, a, Yu.vision(p.id)) { refs -> refs.forEach { r -> if (r !in pendingImgs) pendingImgs.add(r) } }
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // 工具自己出错不拖垮整轮：告诉她没成，她可以重来或如实说
                                if (n == WebSearch.TOOL) slips += Lessons.WEB
                                ToolOut("没成功：" + (e.message ?: e.javaClass.simpleName).take(80) + "。可以再试一次，或如实告诉对方没做成。")
                            } finally {
                                // 没做成的作品如实标出来，不显示「做好了」
                                val t = if (made) Trail.doneTitle(n, a) else Trail.failTitle(n, a)
                                if (!made) slips += Lessons.WORK
                                if (idx < liveSteps.size) liveSteps[idx] = liveSteps[idx].copy(title = t, done = true)
                                if (n == Works.MAKE || n == Works.EDIT) workDraft = null
                            }
                        },
                        { },
                        inbox = { takeQueue() },
                        onDraft = { n, len, head -> val d = workDraft; workDraft = WorkDraft(d?.at ?: partial.length, n, len, WorkDraft.title(n, head) ?: d?.title) },
                    ) else null,
                    fallback = if (specs.isNotEmpty()) ({
                        listOfNotNull(
                            if (recall) Recall.context(store0, cur).takeIf { it.isNotEmpty() } else null,
                            if (yu) Yu.context(question) else null,
                        ).joinToString("\n\n")
                    }) else null,
                    toolNote = extra,
                ) { partial += it; thinking = false }
                streamDone = true
                // 等书写追上再收尾（切到后台没有画面推进书写，直接写完）
                while (shown < partial.length) { if (!Notify.foreground || !onScreen) shown = partial.length.toFloat(); kotlinx.coroutines.delay(32) }
                if (partial.isNotBlank() || pendingWorks.isNotEmpty() || pendingClips.isNotEmpty()) {
                    messages.add(replyMsg(partial.trim()))
                    // 记忆：不是每句都记，等这段聊停了再整段回想（换对话时马上回想上一段）
                    val cid = convId.orEmpty()
                    val title = this@ChatState.history.get(convId)?.title ?: firstSentence(question, 18)
                    val model = store.activeModel
                    Reflect.later(cid, messages.toList()) { w -> Reflect.run(who, p, c, model, w, cid, title); Reflect.tidy(who, p, c, model) }
                    // 做事经验：想起过的这一轮算不算用上；出了岔子就请帮手给她记一句，挂在这条回复下面
                    slips += ChatApi.slips
                    Lessons.settle(who, shownLessons, slips, happened)
                    if (slips.isNotEmpty() && who.lessonsOn) {
                        val win = messages.toList(); val at = win.last().at; val s = slips.toSet()
                        scope.launch { Lessons.learn(who, store, win, s, pv, cid, title)?.let { m -> attachLesson(cid, at, m.id) } }
                    }
                } else error = "没有收到回复，换个模型再试"
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (partial.isNotBlank() || pendingWorks.isNotEmpty() || pendingClips.isNotEmpty()) messages.add(replyMsg(partial.take(ceil(shown).toInt()).trim()))
                throw e
            } catch (e: Exception) {
                if (partial.isNotBlank() || pendingWorks.isNotEmpty() || pendingClips.isNotEmpty()) messages.add(replyMsg(partial.trim()))
                error = e.message ?: "出错了"
            } finally {
                val stopped = job?.isCancelled == true
                slips.clear(); shownLessons = emptyList()
                partial = ""; shown = 0f; busy = false; thinking = false; thinkText = ""; pendingImgs.clear(); pendingWorks.clear(); pendingWorkAt.clear(); workDraft = null; pendingClips.clear(); liveSteps.clear()
                persist()
                Notify.stopLive()
                if (!stopped) {
                    val err = error
                    val r = messages.lastOrNull()?.takeIf { it.role == "assistant" && it.at >= roundAt }
                    val seen = Notify.foreground && onScreen
                    if (err != null) Notify.failed(convId, this@ChatState.history, err, seen)
                    else if (r != null) Notify.replied(convId, this@ChatState.history, mine, Notify.preview(r), seen)
                }
                // 这轮结束还有排着的话：接着发（你自己点停止的不发，出错的也不发，留着等你）
                val go = sendNowAfterStop || (!stopped && error == null)
                sendNowAfterStop = false
                if (go && queue.isNotEmpty()) scope.launch { flushQueue(scope, store) }
                else if (!onScreen) Chats.finished(this@ChatState)
            }
        }
    }

    fun stop() { job?.cancel() }

    /** 「立即发送」：她在做就先停下（写好的留着），然后马上发。 */
    fun sendNow(scope: CoroutineScope, store: AssistantStore) {
        if (busy) { sendNowAfterStop = true; stop() } else flushQueue(scope, store)
    }

    /** 把排着的几句合成一条发出去。 */
    private fun flushQueue(scope: CoroutineScope, store: AssistantStore) {
        if (queue.isEmpty() || busy || full) return
        val text = queue.joinToString("\n"); queue.clear()
        error = null; needsSetup = false
        messages.add(ChatMsg("user", text))
        persist(); sent++
        ask(scope, store)
    }

    /** 做完一步工具时收排着的话：这一段先收成一条回复，你的话进对话，她接着做。 */
    private fun takeQueue(): String? {
        if (queue.isEmpty()) return null
        val q = queue.joinToString("\n"); queue.clear()
        if (partial.isNotBlank() || pendingWorks.isNotEmpty() || pendingClips.isNotEmpty() || thinkText.isNotBlank() || liveSteps.isNotEmpty())
            messages.add(replyMsg(partial.trim()))
        messages.add(ChatMsg("user", q))
        partial = ""; shown = 0f; thinkText = ""; liveSteps.clear(); pendingImgs.clear(); pendingWorks.clear(); pendingWorkAt.clear(); workDraft = null; pendingClips.clear()
        persist(); sent++
        return q
    }

    /** 存进消息的工具步骤：中途停下的也算用过。 */
    /** 后台写好了一件（WorkJobs）：把占位的引用换成真的作品 id，存回那段对话。 */
    fun swapWork(conv: String?, from: String, to: String) {
        val i = pendingWorks.indexOf(from); if (i >= 0) pendingWorks[i] = to
        fun fix(m: ChatMsg) = if (from in m.works) m.copy(works = m.works.map { if (it == from) to else it }) else m
        if (messages.any { from in it.works }) {
            messages.indices.forEach { k -> messages[k] = fix(messages[k]) }
            persist()
        } else if (conv != null && conv != convId) history.get(conv)?.let { c -> if (c.msgs.any { from in it.works }) history.save(conv, c.msgs.map(::fix)) }
    }

    /** 她记下了一条做事经验：挂到那条回复上（换了对话也找得到）。 */
    private fun attachLesson(conv: String, msgAt: Long, id: String) {
        fun fix(m: ChatMsg) = if (m.role == "assistant" && m.at == msgAt) m.copy(lesson = id) else m
        if (messages.any { it.role == "assistant" && it.at == msgAt }) {
            messages.indices.forEach { k -> messages[k] = fix(messages[k]) }
            persist()
        } else if (conv.isNotEmpty() && conv != convId) history.get(conv)?.let { c -> history.save(conv, c.msgs.map(::fix)) }
    }

    /** 收成一条她的回复：卡的位置换算到去掉开头空白后的正文里。 */
    private fun replyMsg(text: String): ChatMsg {
        val lead = partial.length - partial.trimStart().length
        landed = messages.size
        return ChatMsg("assistant", text, thinkText, pendingImgs.toList(), works = pendingWorks.toList(), steps = doneSteps(), clips = pendingClips.toList(),
            workAt = pendingWorkAt.map { (it - lead).coerceIn(0, text.length) }, pid = turnPid,
            thinkMs = if (thinkAt > 0L) (thinkEnd - thinkAt).coerceAtLeast(0L) else 0L).also { thinkAt = 0L; thinkEnd = 0L }
    }

    private fun doneSteps() = liveSteps.map { if (it.done) it else it.copy(title = it.title.removePrefix("正在"), done = true) }

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
    onMine: () -> Unit,
    onSetup: () -> Unit,
    /** 侧栏「管理助理」：去「我的 → 助理」。 */
    onAssistants: () -> Unit = onMine,
) {
    val side = if (compact) (if (tiny) 18.dp else 24.dp) else (width * .052f)
    val scope = rememberCoroutineScope()
    val title = Memory.active.name
    LaunchedEffect(Memory.activeId) { chat.followPersona() }
    NotifyHooks(chat, store, scope)
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
    plus.pick = rememberAttachPick(chat)
    AttachLook.store = store
    val flight = remember { SendFlight() }
    androidx.activity.compose.BackHandler(enabled = plus.open) { plus.open = false }
    /** 正在看哪条消息的思考：-1 = 正在进行的这一轮。 */
    var thinkOf by remember { mutableIntStateOf(-1) }
    var thinkOpen by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<String?>(null) }
    // 正在全屏看的作品
    var workOpen by remember { mutableStateOf<String?>(null) }
    // 侧栏「作品」：所有做过的作品
    var shelf by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = shelf) { shelf = false }
    // 侧栏「画板」：计划、安排、日程
    var boards by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = boards) { boards = false }
    // 长按菜单、选择文字、小提示
    var press by remember { mutableStateOf<MsgPress?>(null) }
    var selectText by remember { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    androidx.activity.compose.BackHandler(enabled = thinkOpen) { thinkOpen = false }
    androidx.activity.compose.BackHandler(enabled = picker) { picker = false }
    // 左侧菜单：从左边滑出，盖在聊天页上面（聊天页不动，只压暗）；点右边露出的部分收回
    var menu by remember { mutableStateOf(false) }
    var editFly by remember { mutableStateOf<Pair<String, androidx.compose.ui.geometry.Rect>?>(null) }
    var flyHost by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    androidx.activity.compose.BackHandler(enabled = menu) { menu = false }
    val menuT by androidx.compose.animation.core.animateFloatAsState(
        if (menu) 1f else 0f, tween(320, easing = androidx.compose.animation.core.CubicBezierEasing(.2f, .8f, .2f, 1f)), label = "menu",
    )
    val density = LocalDensity.current
    val imeOpen = WindowInsets.ime.getBottom(density) > 0
    // 内置浏览器里「让她看看这页」：写进输入框
    androidx.compose.runtime.DisposableEffect(chat) {
        InBrowser.ask = { t -> if (chat.busy) chat.draft = t else { chat.draft = t }; chat.focusTick++ }
        // 长条上「让她看看」：直接发出去（她在忙就排队）
        ClipLink.ask = { t -> chat.draft = t; val n = chat.send(scope, store); if (n >= 0) scope.launch { flight.launch(n) } }
        TgPill.ask = ClipLink.ask
        onDispose { InBrowser.ask = null; ClipLink.ask = null; TgPill.ask = null }
    }
    androidx.compose.runtime.SideEffect {
        Zen.held = menu || shelf || boards || workOpen != null || thinkOpen || picker || press != null || viewing != null || selectText != null || chat.draft.isNotBlank() || imeOpen || plus.open || chat.atts.isNotEmpty()
    }
    val menuW = min(width.value * .86f, 340f).dp
    val menuPx = with(density) { menuW.toPx() }
    Box(Modifier.fillMaxSize().background(Skin.shade(.38f))) {
      Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .padding(start = side, end = side, top = if (compact) 26.dp else 38.dp, bottom = if (compact) 22.dp else 30.dp),
        ) {
            ChatHeader(compact, chat.messages.isEmpty() && !chat.busy, false, chat.convId?.let { chat.history.get(it) }, onMenu = { menu = true }) { Chats.fresh() }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (chat.messages.isEmpty() && !chat.busy) ChatHello(compact, store, chat)
                else Conversation(chat, store, compact, flight, onSetup = onSetup, onRetry = { chat.retry(scope, store) }, onThink = { thinkOf = it; thinkOpen = true }, onImage = { viewing = it }, onWork = { workOpen = it },
                    pressed = press?.index ?: -1, onLong = { if (!chat.busy || it.index < chat.messages.size) press = it })
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ChatInput(
                    compact, blur, title, store.activeModel, store.activeProvider, store.effort(), chat, plus, flight,
                    onPick = { picker = true },
                    onSend = {
                        val n = chat.send(scope, store)
                        if (n >= 0) scope.launch { flight.launch(n) }
                    },
                    onStop = { chat.stop() },
                    modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                )
            }
        }
        SendFlightLayer(flight)
        if (menuT > 0f) {
            Box(
                Modifier.fillMaxSize().alpha(menuT).background(Skin.scrim(.68f))
                    .pointerInput(Unit) { detectTapGestures { menu = false } },
            )
        }
      }
        if (menuT > 0f) {
            // 菜单本体：对话记录
            Box(
                Modifier.fillMaxHeight().width(menuW)
                    .graphicsLayer { translationX = (menuT - 1f) * menuPx }
                    .then(if (Skin.glass) Modifier.frost(RectangleShape, Skin.panel) else Modifier.background(Skin.panel))
                    .pointerInput(Unit) { detectTapGestures { } }
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                HistoryMenu(
                    chat.history, chat.convId, chat.busy,
                    onOpen = { Chats.open(it); menu = false },
                    onNew = { Chats.fresh(); menu = false },
                    onWorks = { menu = false; shelf = true },
                    onBoards = { menu = false; boards = true },
                    onJournal = { menu = false; NoteCard.open = true },
                    onMine = { onMine() },
                    onAssistants = { menu = false; onAssistants() },
                )
            }
        }
        LiquidPlusLayer(plus) { plus.pick(it) }
        ModelDrawer(picker, store, compact, onClose = { picker = false })
        // 正在看这一轮的思考时她想完了：接着看收进去的那条；什么都没留下就收起抽屉
        LaunchedEffect(chat.busy) {
            if (!chat.busy && thinkOpen && thinkOf < 0) {
                val i = chat.landed
                if (i in chat.messages.indices && (chat.messages[i].think.isNotBlank() || chat.messages[i].steps.isNotEmpty())) thinkOf = i else thinkOpen = false
            }
        }
        val liveThink = thinkOf < 0 || thinkOf >= chat.messages.size
        ThinkDrawer(
            open = thinkOpen,
            raw = if (liveThink) chat.thinkText else chat.messages[thinkOf].think,
            steps = if (liveThink) chat.liveSteps.toList() else chat.messages[thinkOf].steps,
            live = liveThink && chat.busy && (chat.partial.isEmpty() || chat.liveSteps.any { !it.done }),
            summary = ThinkTidy.summaryStyle(if (liveThink) chat.turnPid.ifEmpty { store.activeProvider } else chat.messages[thinkOf].pid.ifEmpty { store.activeProvider }),
            onClose = { thinkOpen = false },
        )
        WorksShelf(shelf, onClose = { shelf = false }) { shelf = false; workOpen = it }
        BoardShelf(boards, onClose = { boards = false }, onOpen = { workOpen = it }) {
            // 「让她搭一块」：新开一个对话，输入框里写好开头
            boards = false
            Chats.fresh()
            Chats.current?.let { it.draft = "帮我搭一块画板："; it.focusTick++ }
        }
        NoteImageViewer(viewing) { viewing = null }
        WorkViewer(workOpen, onClose = { workOpen = null }, onFix = { msg ->
            // 关掉作品页，把问题当作一条消息发给她，她自己读原稿改
            workOpen = null
            if (!chat.busy) {
                chat.draft = msg
                val n = chat.send(scope, store)
                if (n >= 0) scope.launch { flight.launch(n) }
            } else chat.draft = msg
        })
        val pm = press?.let { chat.messages.getOrNull(it.index) }
        val pIdx = press?.index ?: -1
        val pBounds = press?.bounds
        MsgMenu(
            press = if (pm != null) press else null, msg = pm,
            isLast = press?.index == chat.messages.lastIndex && !chat.busy,
            side = side,
            onClose = { press = null },
            onCopy = { pm?.let { clipboard.setText(AnnotatedString(it.text)); toast = "已复制" } },
            onSelect = { selectText = pm?.text },
            onEdit = {
                // 菜单先关再执行：这里用打开菜单时记下的那一条，别再读已经清空的 press
                if (pm != null && pIdx >= 0 && !chat.busy) {
                    chat.editing = pIdx
                    if (pBounds != null && chat.inputAt != androidx.compose.ui.geometry.Rect.Zero) editFly = pm.text to pBounds
                    else { chat.draft = pm.text; chat.focusTick++ }
                }
            },
            onRegen = { chat.regenerate(scope, store) },
        )
        // 编辑重发：那条气泡飞回输入框，落进去变成可以改的文字
        editFly?.let { (text, from) ->
            val k = remember(from) { androidx.compose.animation.core.Animatable(0f) }
            LaunchedEffect(from) {
                k.animateTo(1f, tween(420, easing = androidx.compose.animation.core.CubicBezierEasing(.4f, 0f, .2f, 1f)))
                chat.draft = text; chat.focusTick++
                editFly = null
            }
            val to = chat.inputAt
            Box(Modifier.matchParentSize().onGloballyPositioned { flyHost = it.positionInRoot() }) {
                Box(
                    Modifier
                        .offset {
                            val x = from.left + (to.left + 12.dp.toPx() - from.left) * k.value - flyHost.x
                            val y = from.top + (to.top + 8.dp.toPx() - from.top) * k.value - flyHost.y
                            androidx.compose.ui.unit.IntOffset(x.toInt(), y.toInt())
                        }
                        .graphicsLayer {
                            val sc = 1f - .1f * k.value; scaleX = sc; scaleY = sc
                            alpha = 1f - .8f * k.value
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                        },
                ) { UserBubble(text, compact, Modifier) }
            }
        }
        SelectTextPage(selectText) { selectText = null }
        ConvMenuLayer(chat.history)
        BoxToast(toast, Modifier.align(Alignment.BottomCenter).padding(bottom = 150.dp)) { toast = null }
    }
}

@Composable
private fun ChatHeader(compact: Boolean, empty: Boolean, busy: Boolean, conv: Conv?, onMenu: () -> Unit, onNew: () -> Unit) {
    // 左：侧栏（对话、作品、手记、切换助理、新对话）；右：新对话（照 Claude App），有话了再加一个 ⋮（ConvMenu.kt）
    Box(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Pressable(onMenu, Modifier.align(Alignment.CenterStart), label = "对话和助理") { pressed ->
            SvgIcon(Ic("M4 6.5h15M4 12h10M4 17.5h13"), if (pressed) Page.ink else Page.ink.copy(alpha = .85f), 22.dp, modifier = Modifier.padding(6.dp))
        }
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
            NewChatButton(empty, busy, compact, onNew)
            MoreButton(if (empty) null else conv, compact)
        }
    }
}

/* ---------------- 对话区 ---------------- */

@Composable
private fun Conversation(
    chat: ChatState, store: AssistantStore, compact: Boolean, flight: SendFlight, onSetup: () -> Unit, onRetry: () -> Unit,
    onThink: (Int) -> Unit, onImage: (String) -> Unit, onWork: (String) -> Unit, pressed: Int, onLong: (MsgPress) -> Unit,
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

    // 顶栏下面渐隐：字滑上去慢慢淡掉，不被一刀切开；淡的高度跟着滑动距离长到 40dp，还在最顶上时第一行不淡（原型 XYDpQVepjD8DweQeihE8EX）
    Box(
        Modifier.fillMaxSize().onSizeChanged { viewport = it.height }
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val f = min(scroll.value.toFloat(), 40.dp.toPx())
                if (f > .5f) drawRect(
                    androidx.compose.ui.graphics.Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, startY = 0f, endY = f),
                    blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                )
            },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(top = 6.dp)) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().align(Alignment.CenterHorizontally)) {
                chat.messages.forEachIndexed { i, m ->
                    // 编辑重发时：那条自己变淡，后面的回复也变淡（发送后会被替换）
                    val dim by animateFloatAsState(
                        if (chat.editing >= 0 && i >= chat.editing) (if (i == chat.editing) .35f else .28f) else 1f, tween(320), label = "editDim",
                    )
                    // 位置要按这一层（列表的直接子项）来量：包了一层之后，里面量到的只是相对这一层的位置
                    val topPad = if (i == 0) 0.dp else 26.dp
                    Column(
                        Modifier.graphicsLayer { alpha = dim }
                            .then(if (i == lastUser && m.role == "user") Modifier.onGloballyPositioned { lastUserTop = it.positionInParent().y + with(density) { topPad.toPx() } } else Modifier),
                    ) {
                    if (m.role == "user") {
                        Box(
                            Modifier.fillMaxWidth().padding(top = topPad, bottom = 16.dp),
                            contentAlignment = Alignment.CenterEnd,
                        ) {
                            Column(horizontalAlignment = Alignment.End) {
                                SentFiles(m.files, compact, chat)
                                if (m.text.isNotBlank() || m.files.isEmpty()) UserBubble(m.text, compact, Modifier.longPressMsg(i, pressed == i, onLong).flightBubble(flight, i == flight.idx, 18.dp))
                            }
                        }
                        if (m.clips.isNotEmpty()) Box(Modifier.fillMaxWidth().padding(top = 0.dp), contentAlignment = Alignment.CenterEnd) {
                            Box(Modifier.fillMaxWidth(.86f), contentAlignment = Alignment.CenterEnd) { ClipCards(m.clips) }
                        }
                    } else {
                        ThinkRow(m.think, m.steps, live = false, summary = ThinkTidy.summaryStyle(m.pid.ifEmpty { store.activeProvider }), thinkMs = m.thinkMs, fetch = i == chat.landed) { onThink(i) }
                        Box(Modifier.fillMaxWidth().longPressMsg(i, pressed == i, onLong)) {
                            ReplyFlow(remember(m.text) { ToolText.clean(m.text) }, m.works, m.workAt, null, false, onWork) { t, f -> Reply(t, compact, f) }
                        }
                        UsedSkills(m.steps)
                        LessonRow(m.lesson)
                        NoteImageRow(m.imgs, onImage)
                        ClipCards(m.clips)
                    }
                    }
                }
                if (chat.busy) {
                    // 思考、用工具、开始回答都在同一行：进行中三点 + 最新一句，结束换时钟
                    val steps = chat.liveSteps.toList()
                    val trailLive = chat.partial.isEmpty() || steps.any { !it.done }
                    if (chat.thinkText.isBlank() && steps.isEmpty()) {
                        if (chat.partial.isEmpty()) ThinkingMotion("", Modifier.padding(start = 2.dp, top = 6.dp))   // 没开深度思考也没用工具：只有三点
                    } else ThinkRow(chat.thinkText, steps, trailLive, summary = ThinkTidy.summaryStyle(chat.turnPid.ifEmpty { store.activeProvider }), fetch = true) { onThink(-1) }
                    SkillAskHost(compact)
                    ReplyFlow(chat.partial.take(ceil(chat.shown).toInt()), chat.pendingWorks.toList(), chat.pendingWorkAt.toList(), chat.workDraft, chat.shown < chat.partial.length + 1, onWork) { t, f -> Reply(t, compact, f) }
                    NoteImageRow(chat.pendingImgs.toList(), onImage)
                    ClipCards(chat.pendingClips.toList())
                }
                if (chat.queue.isNotEmpty()) QueuedBubbles(chat, compact) { chat.sendNow(scope, store) }
                chat.error?.let { err ->
                    Column(Modifier.padding(top = 6.dp, start = 2.dp)) {
                        Txt(err, Skin.danger, 13.sp, lineHeight = 21.sp)
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
                    Modifier.size(40.dp).background(Skin.tint(.88f), CircleShape).border(1.dp, Page.line, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { SvgIcon(ChatIcons.down, Page.ink, 18.dp) }
            }
        }
    }
}

@Composable
fun UserBubble(text: String, compact: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier.widthIn(max = if (compact) 300.dp else 460.dp)
            .background(veil(.08f), RoundedCornerShape(18.dp))
            .border(1.dp, veil(.1f), RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) { BasicText(userLinks(text), style = TextStyle(fontFamily = Sans, color = Page.ink, fontSize = 15.sp, lineHeight = 24.sp)) }
}

private val urlRe = Regex("https?://[A-Za-z0-9._~:/?#\\[\\]@!$&'*+,;=%-]+")

/** 你发的话里的网址也能点：只认网址里能出现的字符，紧跟着的中文不会算进去；点了在内置浏览器开。 */
private fun userLinks(text: String): AnnotatedString = buildAnnotatedString {
    append(text)
    urlRe.findAll(text).forEach { m ->
        val u = m.value.trimEnd('.', ',', ')', ';', '!', '?', ':', '\'', ']')
        if (u.length > 10) addLink(androidx.compose.ui.text.LinkAnnotation.Clickable(u, androidx.compose.ui.text.TextLinkStyles(SpanStyle(color = Skin.accent, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline))) { InBrowser.open(u) }, m.range.first, m.range.first + u.length)
    }
}

@Composable
private fun SmallButton(text: String, onClick: () -> Unit) {
    Pressable(onClick, label = text) { pressed ->
        Txt(
            text, Page.ink, 13.sp,
            Modifier.background(veil(if (pressed) .14f else .07f), RoundedCornerShape(50))
                .border(1.dp, Page.line, RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/* ---------------- 回复的排版：段落、**粗体**、列表、标题、代码 ---------------- */

private val replyStyle get() = TextStyle(fontFamily = Sans, color = Page.ink.copy(alpha = .9f), fontSize = 15.5.sp, lineHeight = 27.sp, letterSpacing = .01.em)

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
                    Modifier.fillMaxWidth().background(Skin.well(.25f), RoundedCornerShape(12.dp)).padding(14.dp),
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
    // 链接：[字](网址) 先换成「\u0001字\u0002」，网址按顺序记下，最后变成能点的（在内置浏览器开）
    val urls = ArrayList<String>()
    val src = Regex("\\[([^\\]\\n]{1,80})]\\((https?://[^)\\s]+)\\)").replace(lines.joinToString("\n")) { m -> urls += m.groupValues[2]; "\u0001" + m.groupValues[1] + "\u0002" }
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
    val s0 = plain.toString().replace(Regex("`([^`]*)`"), "$1")
    // 去掉链接记号，同时把粗体区间挪到新位置
    val map = IntArray(s0.length + 1); val sb = StringBuilder(); val links = ArrayList<Triple<Int, Int, String>>(); var open = -1; var li = 0
    for (k in s0.indices) {
        map[k] = sb.length
        when (s0[k]) { '\u0001' -> open = sb.length; '\u0002' -> { if (open >= 0 && li < urls.size) links += Triple(open, sb.length, urls[li]); li++; open = -1 }; else -> sb.append(s0[k]) }
    }
    map[s0.length] = sb.length
    val s = sb.toString()
    // 光秃秃的网址也能点
    urlRe.findAll(s).forEach { m -> if (links.none { m.range.first in it.first until it.second }) { val u = m.value.trimEnd('.', ',', ')', ';', '!', '?', ':', '\'', ']'); links += Triple(m.range.first, m.range.first + u.length, u) } }
    return buildAnnotatedString {
        append(s)
        bold.forEach { r -> val a = map[min(r.first, s0.length)]; val b = map[min(r.last + 1, s0.length)]; if (a < b) addStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Page.ink), a, b) }
        links.forEach { (a, b, u) ->
            addLink(androidx.compose.ui.text.LinkAnnotation.Clickable(u, androidx.compose.ui.text.TextLinkStyles(SpanStyle(color = Skin.accent, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline))) { InBrowser.open(u) }, a, b)
        }
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
    provider: String,
    think: String?,
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
    // 她在做时也能发：有字就是 ↑（排在末尾），没字才是 ■
    val canSend = (chat.draft.isNotBlank() || (chat.atts.isNotEmpty() && !chat.busy)) && (!chat.full || chat.editing >= 0)
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
    // 方案 1 收矮：发送球、留白都小一号
    val ball = 36.dp
    val inset = 9.dp
    val sep = 46.dp
    Box(modifier.padding(top = 10.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(end = sep * t.coerceAtLeast(0f))
                .glass(shape, blur, fill = Skin.tint(.42f), border = Color.Transparent)
                .padding(start = 16.dp, end = inset, top = 12.dp, bottom = inset),
        ) {
            // 上下文消息限制到顶：输入框上方展开一行杏色提示，发送键变灰，提示里直接开新对话
            androidx.compose.animation.AnimatedVisibility(
                chat.full && chat.editing < 0,
                enter = androidx.compose.animation.expandVertically(tween(280)) + androidx.compose.animation.fadeIn(tween(280)),
                exit = androidx.compose.animation.shrinkVertically(tween(220)) + androidx.compose.animation.fadeOut(tween(180)),
            ) {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(5.dp).background(Skin.accent, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    val lim = Memory.active.limit
                    Txt("这段对话已经到上限（$lim/$lim）", Skin.accent, 12.5.sp, Modifier.weight(1f), maxLines = 1)
                    Pressable({ Chats.fresh() }, label = "开新对话") { pr ->
                        Txt(
                            "开新对话", Skin.accent, 12.5.sp,
                            Modifier.background(Skin.accent.copy(alpha = if (pr) .28f else .15f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp),
                            maxLines = 1,
                        )
                    }
                }
            }
            // 编辑重发：输入框上方一条杏色提示，点 × 放弃
            androidx.compose.animation.AnimatedVisibility(
                chat.editing >= 0,
                enter = androidx.compose.animation.expandVertically(tween(260)) + androidx.compose.animation.fadeIn(tween(260)),
                exit = androidx.compose.animation.shrinkVertically(tween(220)) + androidx.compose.animation.fadeOut(tween(180)),
            ) {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    SvgIcon(Ic("M13.5 6.5l4 4M5 19l1-4.5L15.5 5a1.5 1.5 0 0 1 2 0l1.5 1.5a1.5 1.5 0 0 1 0 2L9.5 18z"), Skin.accent, 15.dp)
                    Spacer(Modifier.width(6.dp))
                    Txt("编辑消息，发送后从这里重新回答", Skin.accent, 12.5.sp, Modifier.weight(1f), maxLines = 1)
                    Pressable({ chat.editing = -1; chat.draft = "" }, label = "放弃编辑") { _ ->
                        SvgIcon(Icons.close, Page.muted, 16.dp, modifier = Modifier.padding(4.dp))
                    }
                }
            }
            AttachTray(chat, AttachLook.sees(provider, AttachLook.store, model), model)
            val inputFocus = remember { androidx.compose.ui.focus.FocusRequester() }
            LaunchedEffect(chat.focusTick) { if (chat.focusTick > 0) runCatching { inputFocus.requestFocus() } }
            BasicTextField(
                value = chat.draft,
                onValueChange = { chat.draft = it },
                textStyle = TextStyle(fontFamily = Sans, color = Page.ink, fontSize = if (compact) 15.sp else 16.sp, lineHeight = 24.sp),
                cursorBrush = SolidColor(Page.accent),
                maxLines = 6,
                modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp).padding(start = 4.dp)
                    .focusRequester(inputFocus)
                    .onGloballyPositioned { chat.inputAt = it.boundsInRoot() },
                decorationBox = { inner ->
                    Box {
                        if (chat.draft.isEmpty()) Txt("回信给 $title…", Page.muted, if (compact) 15.sp else 16.sp)
                        inner()
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LiquidPlusButton(plus) { plus.pick(it) }
                Pressable(onPick, enabled = !chat.busy, label = "选择模型") { p ->
                    Row(
                        Modifier.widthIn(max = 240.dp)
                            .background(veil(if (p) .13f else .07f), RoundedCornerShape(50))
                            .padding(horizontal = 13.dp, vertical = 8.dp)
                            .alpha(if (chat.busy) .45f else 1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Txt(model.ifBlank { "选择模型" }, Page.ink, 12.sp, maxLines = 1)
                        if (think != null) Txt(" · " + (if (think == Effort.OFF) "不思考" else Effort.label(think)), if (think == Effort.OFF) Page.muted else Page.accent, 12.sp, maxLines = 1)
                    }
                }
                Spacer(Modifier.weight(1f))
                // 给球在框里留的位置：球拉出去后，这块空位也跟着收拢
                Spacer(Modifier.width((ball - 10.dp) * (1f - t).coerceIn(0f, 1f)))
            }
        }
        // 杏色的液体球（带黏连），画在输入框外层
        Box(Modifier.matchParentSize()) { SendGoo(t, pulse.value, pressed, inset, sep, ball) }
        val icon = when { canSend -> ChatIcons.up; chat.busy -> ChatIcons.stop; else -> ChatIcons.wave }
        Pressable(
            { if (canSend) { onSend(); keyboard?.hide(); focus.clearFocus() } else if (chat.busy) onStop() },  // 发出去就收起键盘
            Modifier.align(Alignment.BottomEnd).offset(x = -inset * (1f - t), y = -inset)
                .onGloballyPositioned { flight.from = it.boundsInRoot().center },
            label = when { canSend -> "发送"; chat.busy -> "停止"; else -> "语音" },
        ) { p ->
            LaunchedEffect(p) { pressed = p }
            Box(Modifier.size(ball).graphicsLayer { scaleX = pulse.value; scaleY = pulse.value }, contentAlignment = Alignment.Center) {
                SvgIcon(icon, Skin.onAccent, 18.dp, stroke = 1.6f)
            }
        }
    }
}


/* ---------------- 选择模型抽屉（方案 1：无边框，选中项淡暖色块） ---------------- */

/**
 * 「更多模型」不再跳到「我的」，而是从按钮里长出一张浮动小卡片（ProviderCard.kt）。
 * 卡片开着时抽屉压暗；在卡片里选了模型，卡片和抽屉一起沉下去。
 */
@Composable
private fun ModelDrawer(open: Boolean, store: AssistantStore, compact: Boolean, onClose: () -> Unit) {
    val shown by androidx.compose.animation.core.animateFloatAsState(
        if (open) 1f else 0f, tween(if (open) 320 else 220, easing = androidx.compose.animation.core.CubicBezierEasing(.2f, .8f, .2f, 1f)), label = "drawer",
    )
    val card = remember { ProviderCardState() }
    var host by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    var moreAt by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    var anchor by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val dim by androidx.compose.animation.core.animateFloatAsState(if (card.open || card.sink) 1f else 0f, tween(260), label = "dim")
    androidx.activity.compose.BackHandler(enabled = card.open) { if (card.pid != null) card.back(store) else card.dismiss(store) }
    LaunchedEffect(open) { if (!open && card.open) card.dismiss(store) }
    LaunchedEffect(card.note) { if (card.note != null) { kotlinx.coroutines.delay(2200); card.note = null } }
    fun openCard() {
        val h = host; val m = moreAt
        if (h != null && m != null && m.isAttached) anchor = h.localBoundingBoxOf(m)
        card.show()
    }
    if (shown <= 0f && !card.open && !card.sink) return
    val sheet = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
    Box(Modifier.fillMaxSize().onGloballyPositioned { host = it }) {
        // 暗幕：点空白处收起
        Box(
            Modifier.fillMaxSize().alpha(shown).background(Skin.scrim(.5f))
                .pointerInput(Unit) { detectTapGestures { onClose() } },
        )
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            val h = maxHeight
            Column(
                Modifier
                    .widthIn(max = 560.dp).fillMaxWidth()
                    .graphicsLayer { translationY = (1f - shown) * h.toPx() * .6f; alpha = shown }
                    .drawWithContent {
                        drawContent()
                        if (dim > 0f) drawOutline(sheet.createOutline(size, layoutDirection, this), rgba(0, 0, 0, .4f * dim))
                    }
                    .background(Skin.sheet, sheet)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(androidx.compose.foundation.layout.WindowInsetsSides.Bottom))
                    .heightIn(max = h * .78f)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 22.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(4.dp).background(veil(.25f), RoundedCornerShape(2.dp)))
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
                    DrawerRow("还没有可用的模型", selected = false, dim = true) { openCard() }
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
                // 深度思考六档：液体色块，可点可拖
                ThinkSegment(Effort.spec(store.activeProvider, store.activeModel), store.effort()) { store.setEffort(it) }
                val note = card.note
                val noteA by androidx.compose.animation.core.animateFloatAsState(if (note != null) 1f else 0f, tween(200), label = "note")
                if (noteA > 0f) Txt(note ?: "", Page.accent, 12.sp, Modifier.fillMaxWidth().padding(top = 10.dp).alpha(noteA), align = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Box(Modifier.align(Alignment.CenterHorizontally).onGloballyPositioned { moreAt = it }) {
                    PressSurface(
                        { openCard() }, shape = RoundedCornerShape(50),
                        fill = if (card.open) veil(.09f) else Color.Transparent, label = "更多模型",
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Txt("更多模型", if (card.open) Page.ink else Page.muted, 13.sp)
                            Spacer(Modifier.width(4.dp))
                            SvgIcon(Ic("M9.5 6l6 6-6 6"), Page.muted, 14.dp)
                        }
                    }
                }
            }
        }
        ProviderCardLayer(card, store, anchor) { card.sink = true; card.open = false; onClose() }
    }
}

@Composable
private fun DrawerRow(text: String, selected: Boolean, dim: Boolean = false, onClick: () -> Unit) {
    Pressable(onClick, Modifier.fillMaxWidth().padding(bottom = 6.dp), label = text) { pressed ->
        Row(
            Modifier.fillMaxWidth()
                .background(
                    when { selected -> Skin.accent.copy(alpha = .16f); pressed -> veil(.05f); else -> Color.Transparent },
                    RoundedCornerShape(14.dp),
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt(text, if (selected) Page.ink else if (dim) Page.muted else Page.ink.copy(alpha = .78f), 15.sp, Modifier.weight(1f), maxLines = 1)
            if (selected) SvgIcon(Ic("M5 12.5l4.5 4.5L19 7.5"), Skin.accent.copy(alpha = 1f), 18.dp, stroke = 1.8f)
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
/** 回答下面「这次用了」：这一轮翻开过的本事，一样一个小标签，点了去看它。 */
@Composable
private fun UsedSkills(steps: List<ToolStep>) {
    val names = remember(steps) { steps.filter { it.name == Skills.OPEN }.flatMap { it.detail.split('、') }.map { it.trim() }.filter { it.isNotEmpty() }.distinct() }
    if (names.isEmpty()) return
    androidx.compose.foundation.layout.FlowRow(
        Modifier.padding(top = 2.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Txt("这次用了", Page.muted.copy(alpha = .55f), 11.5.sp, Modifier.padding(end = 2.dp))
        names.forEach { n ->
            val s = Skills.of().firstOrNull { it.title == n || it.id == n }
            PressSurface({ s?.let { Skills.show?.invoke(it.id) } }, Modifier.height(28.dp), shape = RoundedCornerShape(50), fill = veil(.06f), pressScale = .94f, label = n) {
                Row(Modifier.padding(start = 4.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    SkillTile(s?.id ?: n, n, 20.dp)
                    Spacer(Modifier.width(6.dp))
                    Txt(n, Page.ink.copy(alpha = .86f), 12.sp, maxLines = 1)
                }
            }
        }
    }
}
