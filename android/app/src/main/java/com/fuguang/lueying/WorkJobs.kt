package com.fuguang.lueying

import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import org.json.JSONArray
import org.json.JSONObject

/**
 * 并发双通道（2026-10-25 用户要的）：她用 start_work 交代一件作品，App 在后台另开一路请求去写，
 * 卡片马上插在她说到的地方显示「正在写 · 已写 N 字」，她这边接着说话；后台写好了卡片原地亮起来。
 *
 * 消息里先记 "job:<key>|<作品名>"，写好后换成真的作品 id 存回对话（App 中途被关掉的，卡片显示没写完）。
 * 后台使用发起时的模型设置。静态绘画走独立的草稿、预览和交付流程。
 */
object WorkJobs {
    const val START = "start_work"
    const val PREFIX = "job:"

    /** 一件在后台写的作品：done = 写好的作品 id；fail = 没写成的原因。 */
    data class State(val title: String, val editing: Boolean, val chars: Int = 0, val done: String? = null, val fail: String? = null,
                     val stage: String? = null, val effort: String? = null, val preview: java.io.File? = null)
    val state = mutableStateMapOf<String, State>()

    private class Order(val a: JSONObject, val chat: ChatState, val pid: String, val talk: String, val conv: String?,
                        val author: ArtworkJob.Model, val reviewer: ArtworkJob.Model?, val request: ChatMsg?, val references: List<String>)
    private val orders = mutableMapOf<String, Order>()
    private fun ArtworkJob.Model.metadata() = JSONObject()
        .put("provider", provider.id).put("name", name).put("effort", effort ?: JSONObject.NULL)

    private fun restoreModel(data: JSONObject, store: AssistantStore): ArtworkJob.Model {
        val id = data.getString("provider")
        return ArtworkJob.Model(provider(id), store.config(id), data.getString("name"),
            if (data.isNull("effort")) null else data.getString("effort"))
    }

    private fun Order.metadata() = JSONObject().put("args", a).put("persona", pid).put("talk", talk)
        .put("conv", conv ?: JSONObject.NULL).put("author", author.metadata())
        .put("reviewer", reviewer?.metadata() ?: JSONObject.NULL)
        .put("request", request?.let { JSONObject().put("text", it.text).put("files", JSONArray(it.files)) } ?: JSONObject.NULL)
        .put("references", JSONArray(references))

    private val running = mutableMapOf<String, Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun keyOf(ref: String) = ref.removePrefix(PREFIX).substringBefore('|')
    fun titleOf(ref: String) = ref.substringAfter('|', "")

    val spec get() = ToolSpec(
        START,
        "做一件作品（网页、动画、小游戏、小工具、幻灯片、文档、画板），或者把以前的作品大改重做。交给后台那支笔去写，卡片马上出现在你说到的地方，写好了自己亮起来；你交代完就接着跟他说话，不用等。" +
            "brief 要写清楚，后台那支笔看不到更早的聊天：做什么、给谁看、里面要有的内容和细节、风格和颜色、要有的交互；做幻灯片写每页讲什么，做文档写提纲。小改（改字、换颜色、加删一小块）不用它，用 ${Works.EDIT}。",
        JSONObject().put("type", "object").put(
            "properties",
            JSONObject()
                .put("title", JSONObject().put("type", "string").put("description", "作品名，几个字"))
                .put("kind", JSONObject().put("type", "string").put("enum", JSONArray(listOf("page", "slides", "doc", "board"))))
                .put("artwork", JSONObject().put("type", "boolean").put("description", "用户明确要画静态插画、山水画、月亮、水彩等单幅画面时设 true，kind 填 page"))
                .put("quick", JSONObject().put("type", "boolean").put("description", "只有用户明确要快速初稿时才设 true；绘画默认看图精修"))
                .put("brief", JSONObject().put("type", "string").put("description", "交代给后台那支笔的完整要求"))
                .put("references", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "复刻视频时填写视频 source 附件引用；后台可反复抽帧查看。未填默认本条用户消息的视频；与视频无关时传空数组"))
                .put("id", JSONObject().put("type", "string").put("description", "大改以前的作品时填它的 id；新作品不填")),
        ).put("required", JSONArray(listOf("title", "kind", "brief"))),
    )

    /** 主对话里的说法：用 start_work 交代，交代完接着说，别说「做好了」。 */
    fun rule(who: String) = "做新作品、大改旧作品用 $START 交代给后台。单幅静态绘画填 artwork=true 和 kind=page：卡片马上出现，写好了自己亮起来。交代完就接着跟 $who 说话，可以说正在做、想怎么做，别说「做好了，点开看」——那时还没写完。"

    /** 主对话里调用 start_work：记下这一单，马上在后台开写，返回给她的话。onCard 拿到消息里要记的引用。 */
    fun start(a: JSONObject, chat: ChatState, store: AssistantStore, pid: String, history: List<ChatMsg>, onCard: (String) -> Unit): ToolOut {
        val title = a.optString("title").trim().ifEmpty { "未命名" }.take(40)
        if (a.optString("brief").isBlank()) return ToolOut("brief 是空的：把要做什么、内容和风格写清楚再交代一次。")
        val previousRefs = Works.source(a.optString("id"))?.optJSONArray("motion_references")?.let { refs -> List(refs.length()) { refs.optString(it) } }.orEmpty()
        val available = MotionTools.references(history) + previousRefs
        val requested = a.optJSONArray("references")?.let { arr -> List(arr.length()) { arr.getString(it) } }
            ?: history.lastOrNull { it.role == "user" }?.files.orEmpty().filter(Attach::isVideo).ifEmpty { previousRefs }
        if (requested.any { it !in available }) return ToolOut("参考视频必须来自本对话的附件，请照抄 source。", failed = true)
        val references = requested.distinct()
        val key = java.util.UUID.randomUUID().toString().replace("-", "")
        val talk = history.takeLast(6).joinToString("\n") { m -> (if (m.role == "user") Memory.userCall else "你") + "：" + ToolText.clean(m.text).take(600) }.takeLast(3000)
        val providerId = store.activeProvider
        val model = store.activeModel
        val author = ArtworkJob.Model(provider(providerId), store.config(providerId), model, store.effort(providerId, model))
        val reviewer = AttachLook.helper(store)?.let { (id, name) ->
            ArtworkJob.Model(provider(id), store.config(id), name, store.effort(id, name))
        }
        orders[key] = Order(JSONObject(a.toString()), chat, pid, talk, chat.convId, author, reviewer,
            history.lastOrNull { it.role == "user" }, references)
        onCard("$PREFIX$key|$title")
        run(key)
        val editing = a.optString("id").isNotBlank()
        return ToolOut("已经交给后台在写《$title》了，卡片就在你说到这里的位置，写好会自己亮起来。接着跟${Memory.userCall}说话就好；别说已经${if (editing) "改" else "做"}好。")
    }

    fun canRetry(ref: String, record: JSONObject?) = orders.containsKey(keyOf(ref)) || record != null

    fun retry(ref: String, record: JSONObject?, chat: ChatState, store: AssistantStore) {
        val key = keyOf(ref)
        if (state[key]?.done != null) return
        if (key !in orders && record != null) {
            val restored = runCatching {
                val request = record.optJSONObject("request")?.let { item ->
                    val files = item.optJSONArray("files") ?: JSONArray()
                    ChatMsg("user", item.getString("text"), files = List(files.length()) { files.getString(it) })
                }
                Order(record.getJSONObject("args"), chat, record.getString("persona"), record.getString("talk"),
                    if (record.isNull("conv")) null else record.getString("conv"), restoreModel(record.getJSONObject("author"), store),
                    record.optJSONObject("reviewer")?.let { restoreModel(it, store) }, request,
                    record.optJSONArray("references")?.let { refs -> List(refs.length()) { refs.getString(it) } }.orEmpty())
            }.getOrNull() ?: return
            orders[key] = restored
        }
        run(key)
    }
    fun stop(ref: String) { running[keyOf(ref)]?.cancel() }

    private fun run(key: String) {
        val o = orders[key] ?: return
        if (running[key]?.isCompleted == false) return
        val a = o.a
        val title = a.optString("title").trim().ifEmpty { "未命名" }.take(40)
        val editId = a.optString("id").trim().takeIf { it.isNotEmpty() && Works.get(it) != null }
        val artwork = a.optBoolean("artwork") && a.optString("kind") == "page"
        state[key] = State(title, editId != null, stage = if (artwork) "正在起稿" else null, preview = state[key]?.preview)
        val live = Notify.startLive(o.conv, o.chat.history, title)
        running[key] = scope.launch {
            try {
            var made: String? = null
            var err: String? = null
            try {
                ArtworkJob.saveOrder(key, o.metadata())
                val (p, c, model, effort) = o.author
                if (artwork) {
                    err = kotlinx.coroutines.withTimeout(15 * 60_000L) { ArtworkJob.run(key, title, editId, o.pid, a.optBoolean("quick"), o.author, o.reviewer,
                        o.request, order(o, title, editId, artwork = true),
                        onStage = { phase, preview -> state[key]?.let { state[key] = it.copy(stage = phase, preview = preview ?: it.preview) } },
                        onEffort = { sent -> state[key]?.let { state[key] = it.copy(effort = "${p.name} · $model · ${effort ?: "默认"} → ${sent ?: "未发送"}") } },
                        onMade = { made = it }) }
                } else {
                    val make = Works.specs.first { it.name == Works.MAKE }
                    val present = "present_work"
                    val sees = Attach.vision(p.id, model)
                    val motion = MotionTools.Session(o.references, sees, o.reviewer, ArtworkJob.directory(key))
                    val resumed = motion.restore()
                    var candidate: (suspend () -> ToolOut)? = null
                    var round = 0
                    var reviewedRound = 0
                    var thoughtEntry: ArtworkRuns.Entry? = null
                    val thought = StringBuilder()
                    suspend fun finishThought() {
                        thoughtEntry?.let { ArtworkRuns.finishThought(key, it, thought.toString().trim()) }
                        thoughtEntry = null
                        thought.clear()
                    }
                    val tools = Tools(
                        listOf(ToolSpec(make.name, make.description + "。在这个后台流程中只生成预览，检查后再调用 $present 交付。", make.schema),
                            ToolSpec(present, "交付最近一次检查过的作品预览。视频复刻必须先检查本稿的动画过程并对照参考。", JSONObject().put("type", "object"))) + MotionTools.specs,
                        run = { n, args ->
                            when (n) {
                                Works.MAKE -> {
                                    candidate = null
                                    args.put("title", title).put("kind", a.optString("kind").ifBlank { args.optString("kind") })
                                    args.put("motion_references", JSONArray(o.references))
                                    args.remove("id")
                                    if (editId != null) args.put("id", editId)
                                    state[key]?.let { state[key] = it.copy(stage = "正在检查预览") }
                                    Works.run(Works.MAKE, args, o.pid, preview = { page, missing, publish ->
                                        motion.draft(page)
                                        val review = WorkReview.inspect(page, missing, sees || o.reviewer != null)
                                        val image = review.image
                                        val reviewer = o.reviewer
                                        val visual = if (!sees && image != null && reviewer != null) {
                                            try {
                                                AttachLook.describe(reviewer.provider, reviewer.config, reviewer.name, image, "image/png",
                                                    "按原始要求检查这个作品的首屏截图，给出具体问题和修改方向。不能从截图确认的交互不要判定通过。\n" + a.optString("brief").take(3000))
                                            } catch (e: CancellationException) { throw e }
                                            catch (_: Exception) { "视觉检查请求未成功。" }
                                        } else ""
                                        withContext(Dispatchers.Main) {
                                            if (review.ready) {
                                                candidate = publish
                                                reviewedRound = round
                                            }
                                            ArtworkRuns.note(key, "review", if (review.ready) "已检查作品预览" else "预览未通过打开检查")
                                            state[key]?.let { state[key] = it.copy(stage = if (review.ready) "已检查预览" else "预览检查未通过") }
                                        }
                                        val text = when {
                                            sees && image != null -> review.text + "\n附实际首屏截图，请查看画面后决定。"
                                            visual.isNotBlank() -> review.text + "\n视觉检查：$visual"
                                            else -> review.text + "\n本轮未完成截图视觉检查。"
                                        }
                                        ToolOut(text + "\nmotion_revision=${motion.revision}。参考视频复刻请继续查看动画过程并 compare_motion，再决定是否交付。", if (sees && image != null) listOf(WorkReview.image(image)) else emptyList())
                                    }) { id -> made = id }
                                }
                                in MotionTools.names -> {
                                    state[key]?.let { state[key] = it.copy(stage = when (n) {
                                        MotionTools.INSPECT -> "正在查看参考视频"
                                        MotionTools.FRAMES -> "正在细看动作"
                                        MotionTools.RENDER -> "正在检查动画过程"
                                        else -> "正在对照参考修改"
                                    }) }
                                    val out = motion.run(n, args)
                                    if (n == MotionTools.COMPARE && !out.failed) reviewedRound = round
                                    motion.lastEvidencePreview?.let { image -> state[key]?.let { state[key] = it.copy(preview = image) } }
                                    ArtworkRuns.note(key, "motion", Trail.liveTitle(n).removePrefix("正在") + "\n" + out.text, motion.revision)
                                    out
                                }
                                present -> when {
                                    candidate == null -> ToolOut("还没有可交付预览。先制作并检查。")
                                    !motion.canPresent -> ToolOut("参考视频复刻尚未完成本稿的真实画面对比。先调用 compare_motion，再阅读结果决定。", failed = true)
                                    reviewedRound == round -> ToolOut("先阅读预览检查结果，再决定是否交付。")
                                    else -> checkNotNull(candidate).invoke().also {
                                        if (made != null) ArtworkRuns.note(key, "present", "已交付作品")
                                    }
                                }
                                else -> ToolOut("这里只能制作预览和交付。")
                            }
                        },
                        onUse = {},
                        onDraft = { _, len, _ -> state[key]?.let { s -> state[key] = s.copy(chars = len, stage = "正在制作") } },
                        finished = { made != null },
                        maxRounds = if (o.references.isEmpty()) 10 else 32,
                        imageHistoryBatches = if (o.references.isEmpty()) 0 else 3,
                    )
                    try {
                        kotlinx.coroutines.withTimeout(15 * 60_000L) {
                            ChatApi.stream(p, c, model, system(editId) + (if (o.references.isEmpty()) "" else "\n" + MotionTools.authorRule), listOf(ChatMsg("user", order(o, title, editId) + resumed)), effort,
                                onThink = { chunk ->
                                    if (thoughtEntry == null && chunk.isNotBlank()) thoughtEntry = ArtworkRuns.beginThought(key)
                                    if (thoughtEntry != null) thought.append(chunk)
                                }, tools = tools, onRoundEnd = { round++; finishThought() }, onText = {})
                        }
                    } finally {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { finishThought() }
                    }
                    if (made == null && candidate != null) err = "预览已检查，模型未确认交付"
                }
            } catch (e: CancellationException) {
                if (made == null) err = if (e is kotlinx.coroutines.TimeoutCancellationException) if (artwork) "绘制超时，已保留可用稿件" else "制作超时，未交付" else "停下了"
            } catch (e: Exception) {
                err = (e.message ?: e.javaClass.simpleName).take(60)
            }
            val s = state[key] ?: State(title, editId != null)
            val id = made
            if (id != null) { state[key] = s.copy(done = id); o.chat.swapWork(o.conv, "$PREFIX$key|$title", id); Notify.work(o.conv, o.chat.history, title) }
            else state[key] = s.copy(fail = err ?: "后台没把作品写出来")
            } finally {
                running.remove(key)
                Notify.stopLive(live)
            }
        }
    }

    private fun system(editId: String?) =
        "你是 Vignette 里那位助理在后台动手做作品的那支笔。前台她已经答应了对方，现在由你把作品完整做出来：" +
            "先规划，然后用 ${Works.MAKE} 生成完整预览。收到实际检查结果后，对照原始要求判断是否要返工。有问题就修改并再次预览，满意后调用 present_work 交付。轮数由结果决定。只有实际检查过的项目才可以说已验证；不要贴代码。" +
            (if (editId != null) "这是在以前那件作品的原稿上大改，保留对方喜欢的部分，按要求改。" else "") +
            "\n\n" + Craft.system()

    private fun order(o: Order, title: String, editId: String?, artwork: Boolean = false): String = buildString {
        append("作品名：《$title》，种类：${o.a.optString("kind").ifBlank { "page" }}。\n\n她交代的要求：\n").append(o.a.optString("brief").trim())
        if (o.talk.isNotBlank()) append("\n\n最近几句聊天（帮你理解语气和来龙去脉）：\n").append(o.talk)
        if (editId != null) Works.source(editId)?.let { src -> append("\n\n原稿：\n").append(WorkEdit.body(src).take(60_000)) }
        append(MotionTools.context(o.references))
        if (!artwork) append(if (o.references.isEmpty()) "\n\n现在就调用 ${Works.MAKE} 做出来。" else "\n\n先查看参考视频，再制作；交付前必须对照当前稿的实际动画画面。")
    }
}

