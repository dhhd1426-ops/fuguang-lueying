package com.fuguang.lueying

import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 并发双通道（2026-10-25 用户要的）：她用 start_work 交代一件作品，App 在后台另开一路请求去写，
 * 卡片马上插在她说到的地方显示「正在写 · 已写 N 字」，她这边接着说话；后台写好了卡片原地亮起来。
 *
 * 消息里先记 "job:<key>|<作品名>"，写好后换成真的作品 id 存回对话（App 中途被关掉的，卡片显示没写完）。
 * 后台那一路：同一个供应商和型号，只带 make_work 一个工具；看得到她交代的要求、最近几句聊天，改作品时还有原稿。
 */
object WorkJobs {
    const val START = "start_work"
    const val PREFIX = "job:"

    /** 一件在后台写的作品：done = 写好的作品 id；fail = 没写成的原因。 */
    data class State(val title: String, val editing: Boolean, val chars: Int = 0, val done: String? = null, val fail: String? = null)
    val state = mutableStateMapOf<String, State>()

    private class Order(val a: JSONObject, val chat: ChatState, val store: AssistantStore, val pid: String, val talk: String, val conv: String?)
    private val orders = mutableMapOf<String, Order>()
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
                .put("brief", JSONObject().put("type", "string").put("description", "交代给后台那支笔的完整要求"))
                .put("id", JSONObject().put("type", "string").put("description", "大改以前的作品时填它的 id；新作品不填")),
        ).put("required", JSONArray(listOf("title", "kind", "brief"))),
    )

    /** 主对话里的说法：用 start_work 交代，交代完接着说，别说「做好了」。 */
    fun rule(who: String) = "做新作品、大改旧作品用 $START 交代给后台：卡片马上出现，写好了自己亮起来。交代完就接着跟 $who 说话，可以说正在做、想怎么做，别说「做好了，点开看」——那时还没写完。"

    /** 主对话里调用 start_work：记下这一单，马上在后台开写，返回给她的话。onCard 拿到消息里要记的引用。 */
    fun start(a: JSONObject, chat: ChatState, store: AssistantStore, pid: String, history: List<ChatMsg>, onCard: (String) -> Unit): ToolOut {
        val title = a.optString("title").trim().ifEmpty { "未命名" }.take(40)
        if (a.optString("brief").isBlank()) return ToolOut("brief 是空的：把要做什么、内容和风格写清楚再交代一次。")
        val key = System.currentTimeMillis().toString(36) + (100..999).random()
        val talk = history.takeLast(6).joinToString("\n") { m -> (if (m.role == "user") Memory.userCall else "你") + "：" + ToolText.clean(m.text).take(600) }.takeLast(3000)
        orders[key] = Order(a, chat, store, pid, talk, chat.convId)
        onCard("$PREFIX$key|$title")
        run(key)
        val editing = a.optString("id").isNotBlank()
        return ToolOut("已经交给后台在写《$title》了，卡片就在你说到这里的位置，写好会自己亮起来。接着跟${Memory.userCall}说话就好；别说已经${if (editing) "改" else "做"}好。")
    }

    /** 没写成的，点卡片重来一次。 */
    fun retry(ref: String) { val k = keyOf(ref); if (orders.containsKey(k) && state[k]?.done == null) run(k) }
    fun canRetry(ref: String) = orders.containsKey(keyOf(ref))

    private fun run(key: String) {
        val o = orders[key] ?: return
        val a = o.a
        val title = a.optString("title").trim().ifEmpty { "未命名" }.take(40)
        val editId = a.optString("id").trim().takeIf { it.isNotEmpty() && Works.get(it) != null }
        state[key] = State(title, editId != null)
        scope.launch {
            var made: String? = null
            var err: String? = null
            try {
                val p = provider(o.store.activeProvider); val c = o.store.config(o.store.activeProvider)
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
                val level = if (o.store.thinkLevel == 0) 0 else 2
                ChatApi.stream(p, c, o.store.activeModel, system(editId), listOf(ChatMsg("user", order(o, title, editId))), level, onThink = { }, tools = tools) {
                    if (made != null) throw CancellationException("写好了")
                }
            } catch (e: CancellationException) {
                if (made == null) err = "停下了"
            } catch (e: Exception) {
                err = (e.message ?: e.javaClass.simpleName).take(60)
            }
            val s = state[key] ?: State(title, editId != null)
            val id = made
            if (id != null) { state[key] = s.copy(done = id); o.chat.swapWork(o.conv, "$PREFIX$key|$title", id) }
            else state[key] = s.copy(fail = err ?: "后台没把作品写出来")
        }
    }

    private fun system(editId: String?) =
        "你是 Vignette 里那位助理在后台动手做作品的那支笔。前台她已经答应了对方，现在由你把作品完整做出来：" +
            "只调用一次 ${Works.MAKE}，把整件作品一次写完；不要回复别的话，不要贴代码。" +
            (if (editId != null) "这是在以前那件作品的原稿上大改，保留对方喜欢的部分，按要求改。" else "") +
            "\n\n" + Craft.system()

    private fun order(o: Order, title: String, editId: String?): String = buildString {
        append("作品名：《$title》，种类：${o.a.optString("kind").ifBlank { "page" }}。\n\n她交代的要求：\n").append(o.a.optString("brief").trim())
        if (o.talk.isNotBlank()) append("\n\n最近几句聊天（帮你理解语气和来龙去脉）：\n").append(o.talk)
        if (editId != null) Works.source(editId)?.let { src -> append("\n\n原稿：\n").append(WorkEdit.body(src).take(60_000)) }
        append("\n\n现在就调用 ${Works.MAKE} 做出来。")
    }
}
