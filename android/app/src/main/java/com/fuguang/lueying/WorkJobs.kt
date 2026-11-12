package com.fuguang.lueying

import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
                        val author: ArtworkJob.Model, val reviewer: ArtworkJob.Model?, val request: ChatMsg?)
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
                .put("id", JSONObject().put("type", "string").put("description", "大改以前的作品时填它的 id；新作品不填")),
        ).put("required", JSONArray(listOf("title", "kind", "brief"))),
    )

    /** 主对话里的说法：用 start_work 交代，交代完接着说，别说「做好了」。 */
    fun rule(who: String) = "做新作品、大改旧作品用 $START 交代给后台。单幅静态绘画填 artwork=true 和 kind=page：卡片马上出现，写好了自己亮起来。交代完就接着跟 $who 说话，可以说正在做、想怎么做，别说「做好了，点开看」——那时还没写完。"

    /** 主对话里调用 start_work：记下这一单，马上在后台开写，返回给她的话。onCard 拿到消息里要记的引用。 */
    fun start(a: JSONObject, chat: ChatState, store: AssistantStore, pid: String, history: List<ChatMsg>, onCard: (String) -> Unit): ToolOut {
        val title = a.optString("title").trim().ifEmpty { "未命名" }.take(40)
        if (a.optString("brief").isBlank()) return ToolOut("brief 是空的：把要做什么、内容和风格写清楚再交代一次。")
        val key = java.util.UUID.randomUUID().toString().replace("-", "")
        val talk = history.takeLast(6).joinToString("\n") { m -> (if (m.role == "user") Memory.userCall else "你") + "：" + ToolText.clean(m.text).take(600) }.takeLast(3000)
        val providerId = store.activeProvider
        val model = store.activeModel
        val author = ArtworkJob.Model(provider(providerId), store.config(providerId), model, store.effort(providerId, model))
        val reviewer = AttachLook.helper(store)?.let { (id, name) ->
            ArtworkJob.Model(provider(id), store.config(id), name, store.effort(id, name))
        }
        orders[key] = Order(JSONObject(a.toString()), chat, pid, talk, chat.convId, author, reviewer,
            history.lastOrNull { it.role == "user" })
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
                    record.optJSONObject("reviewer")?.let { restoreModel(it, store) }, request)
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
        running[key] = scope.launch {
            var made: String? = null
            var err: String? = null
            try {
                val (p, c, model, effort) = o.author
                if (artwork) {
                    ArtworkJob.saveOrder(key, o.metadata())
                    err = kotlinx.coroutines.withTimeout(15 * 60_000L) { ArtworkJob.run(key, title, editId, o.pid, a.optBoolean("quick"), o.author, o.reviewer,
                        o.request, order(o, title, editId, artwork = true),
                        onStage = { phase, preview -> state[key]?.let { state[key] = it.copy(stage = phase, preview = preview ?: it.preview) } },
                        onEffort = { sent -> state[key]?.let { state[key] = it.copy(effort = "${p.name} · $model · ${effort ?: "默认"} → ${sent ?: "未发送"}") } },
                        onMade = { made = it }) }
                } else {
                    val make = Works.specs.first { it.name == Works.MAKE }
                    val tools = Tools(
                        listOf(make),
                        run = { n, args ->
                            if (n != Works.MAKE) ToolOut("这里只用 ${Works.MAKE}。")
                            else {
                                args.put("title", title).put("kind", a.optString("kind").ifBlank { args.optString("kind") })
                                if (editId != null) args.put("id", editId)
                                Works.run(Works.MAKE, args, o.pid) { id -> made = id }.let { r -> if (made != null) ToolOut("好了，不用再说话。") else r }
                            }
                        },
                        onUse = { },
                        onDraft = { _, len, _ -> state[key]?.let { s -> state[key] = s.copy(chars = len) } },
                    )
                    ChatApi.stream(p, c, model, system(editId), listOf(ChatMsg("user", order(o, title, editId))), effort, onThink = { }, tools = tools) {
                        if (made != null) throw CancellationException("写好了")
                    }
                }
            } catch (e: CancellationException) {
                if (made == null) err = if (e is kotlinx.coroutines.TimeoutCancellationException) "绘制超时，已保留可用稿件" else "停下了"
            } catch (e: Exception) {
                err = (e.message ?: e.javaClass.simpleName).take(60)
            }
            val s = state[key] ?: State(title, editId != null)
            val id = made
            if (id != null) { state[key] = s.copy(done = id); o.chat.swapWork(o.conv, "$PREFIX$key|$title", id); Notify.work(o.conv, o.chat.history, title) }
            else state[key] = s.copy(fail = err ?: "后台没把作品写出来")
            running.remove(key)
        }
    }

    private fun system(editId: String?) =
        "你是 Vignette 里那位助理在后台动手做作品的那支笔。前台她已经答应了对方，现在由你把作品完整做出来：" +
            "只调用一次 ${Works.MAKE}，把整件作品一次写完；不要回复别的话，不要贴代码。" +
            (if (editId != null) "这是在以前那件作品的原稿上大改，保留对方喜欢的部分，按要求改。" else "") +
            "\n\n" + Craft.system()

    private fun order(o: Order, title: String, editId: String?, artwork: Boolean = false): String = buildString {
        append("作品名：《$title》，种类：${o.a.optString("kind").ifBlank { "page" }}。\n\n她交代的要求：\n").append(o.a.optString("brief").trim())
        if (o.talk.isNotBlank()) append("\n\n最近几句聊天（帮你理解语气和来龙去脉）：\n").append(o.talk)
        if (editId != null) Works.source(editId)?.let { src -> append("\n\n原稿：\n").append(WorkEdit.body(src).take(60_000)) }
        if (!artwork) append("\n\n现在就调用 ${Works.MAKE} 做出来。")
    }
}
