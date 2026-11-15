package com.fuguang.lueying

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 她手上的事：照 Claude 的任务清单。每段聊天一张，存 files/steps/<对话 id>.json。 */
internal object Steps {
    const val TOOL = "update_steps"
    const val PENDING = "pending"
    const val DOING = "in_progress"
    const val DONE = "completed"
    private val STATES = setOf(PENDING, DOING, DONE)
    /** 换掉的那张留几轮。 */
    private const val PREV_TURNS = 6

    data class Item(val text: String, val state: String)

    private class Sheet(
        var items: List<Item> = emptyList(),
        var prev: List<Item> = emptyList(),
        var prevLeft: Int = 0,
        val half: LinkedHashMap<String, String> = LinkedHashMap(),
    ) {
        /** 这一轮开头从 half 拿出来、要告诉她的（只说一次，不存盘）。 */
        var told: List<String> = emptyList()
    }

    private var dir: File? = null
    private val cache = HashMap<String, Sheet>()
    private val lock = Any()

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "steps")
        // 以前的目标账本放在设置文件里，不再用：后台删掉
        val app = ctx.applicationContext
        Thread { runCatching { app.deleteSharedPreferences("goal_continuity_v1") } }.start()
    }

    /** 删掉的聊天不再留清单（撤销删除在同一次打开里，文件还在缓存里不受影响）。 */
    fun prune(keep: Set<String>) {
        val d = dir ?: return
        Thread {
            runCatching {
                d.listFiles()?.forEach { f ->
                    val id = f.name.removeSuffix(".json").removeSuffix(".tmp")
                    if (keep.none { name(it) == id }) f.delete()
                }
            }
        }.start()
    }

    private fun name(id: String) = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(80)
    private fun file(id: String) = dir?.let { File(it, name(id) + ".json") }

    private fun load(id: String): Sheet = cache.getOrPut(id) {
        runCatching { file(id)?.takeIf { it.exists() }?.readText()?.let { parse(it) } }.getOrNull() ?: Sheet()
    }

    private fun list(a: JSONArray?): List<Item> =
        if (a == null) emptyList()
        else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            .map { Item(it.optString("text").trim(), it.optString("state").takeIf { s -> s in STATES } ?: PENDING) }
            .filter { it.text.isNotEmpty() }

    private fun parse(t: String): Sheet {
        val o = JSONObject(t)
        val half = LinkedHashMap<String, String>()
        o.optJSONObject("half")?.let { h -> h.keys().forEach { k -> half[k] = h.optString(k) } }
        return Sheet(list(o.optJSONArray("items")), list(o.optJSONArray("prev")), o.optInt("prevLeft"), half)
    }

    private fun json(l: List<Item>) = JSONArray().apply { l.forEach { put(JSONObject().put("text", it.text).put("state", it.state)) } }

    private fun save(id: String, s: Sheet) {
        val f = file(id) ?: return
        val text = JSONObject()
            .put("items", json(s.items)).put("prev", json(s.prev)).put("prevLeft", s.prevLeft)
            .put("half", JSONObject().apply { s.half.forEach { (k, v) -> put(k, v) } })
            .toString()
        ConvStore.Saver.put(f) { text }
    }

    /** 每轮开头：上一轮做到一半的操作拿出来这轮说一次；换掉的那张少留一轮。 */
    fun startTurn(id: String?) {
        if (id.isNullOrBlank()) return
        synchronized(lock) {
            val s = load(id)
            s.told = s.half.values.toList()
            var changed = s.half.isNotEmpty()
            s.half.clear()
            if (s.prev.isNotEmpty()) {
                s.prevLeft -= 1
                if (s.prevLeft <= 0) { s.prev = emptyList(); s.prevLeft = 0 }
                changed = true
            }
            if (changed) save(id, s)
        }
    }

    private fun mark(state: String) = when (state) { DOING -> "▶"; DONE -> "☑"; else -> "☐" }

    /** 放进这一轮给她的说明里。清单空、没有要提的就是空字符串。 */
    fun note(id: String?): String {
        if (id.isNullOrBlank()) return ""
        val s = synchronized(lock) { load(id) }
        return buildString {
            if (s.items.any { it.state != DONE }) {
                append("\n\n# 手上的事")
                s.items.forEach { append("\n").append(mark(it.state)).append(" ").append(it.text) }
            }
            if (s.prev.isNotEmpty()) append("\n换掉的：").append(s.prev.joinToString(" / ") { it.text })
            if (s.told.isNotEmpty())
                append("\n上一轮被打断、结果不明：").append(s.told.joinToString("、")).append("。先看实际状态再决定。")
        }
    }

    val spec get() = ToolSpec(
        TOOL,
        "手上要做好几步的事，记成清单，边做边更新，每次传整张。同时最多一项 in_progress，真做完才 completed。他插问：在当前项前加一项。他让停：改回 pending。他换事：改掉或清空。",
        JSONObject().put("type", "object")
            .put("properties", JSONObject().put("items", JSONObject()
                .put("type", "array")
                .put("items", JSONObject().put("type", "object")
                    .put("properties", JSONObject()
                        .put("text", JSONObject().put("type", "string").put("description", "这件事，一句平常话"))
                        .put("state", JSONObject().put("type", "string").put("enum", JSONArray(listOf(PENDING, DOING, DONE)))))
                    .put("required", JSONArray(listOf("text", "state"))))))
            .put("required", JSONArray(listOf("items")))
    )

    fun run(id: String?, a: JSONObject): ToolOut {
        if (id.isNullOrBlank()) return ToolOut("这段聊天还没存好，清单没记上，下一轮再记。", failed = true)
        val arr = a.optJSONArray("items") ?: return ToolOut("要传 items：整张清单（清空就传空数组）。", failed = true)
        val items = list(arr).map { it.copy(text = it.text.take(200)) }.take(30)
        if (items.count { it.state == DOING } > 1) return ToolOut("同一时间只能有一项 in_progress：挑正在做的那项，其他改回 pending。", failed = true)
        synchronized(lock) {
            val s = load(id)
            val old = s.items
            when {
                // 整张换掉（一项都没留下）而旧的还没做完：留几轮，他想回去找得回来
                old.any { it.state != DONE } && items.none { n -> old.any { it.text == n.text } } -> { s.prev = old; s.prevLeft = PREV_TURNS }
                items.isNotEmpty() && items.all { it.state == DONE } -> { s.prev = emptyList(); s.prevLeft = 0 }
            }
            s.items = items
            save(id, s)
        }
        val done = items.count { it.state == DONE }
        val next = items.firstOrNull { it.state == DOING } ?: items.firstOrNull { it.state == PENDING }
        return ToolOut(if (next == null) "$done/${items.size}" else "$done/${items.size} · ${next.text}")
    }

    /** 思考过程里这一步的标题；进度由标题右边那排小点表示。 */
    fun doneTitle(a: JSONObject): String = if (list(a.optJSONArray("items")).isEmpty()) "清空了要做的事" else "要做的事"

    /** 存进思考过程那一步：一行一项，开头一个记号。 */
    fun encode(a: JSONObject): String = list(a.optJSONArray("items")).joinToString("\n") { mark(it.state) + it.text.replace('\n', ' ') }

    fun decode(s: String): List<Item> = s.lines().mapNotNull { l ->
        val state = when (l.firstOrNull()) { '▶' -> DOING; '☑' -> DONE; '☐' -> PENDING; else -> null }
        state?.let { Item(l.drop(1).trim(), it) }
    }.filter { it.text.isNotEmpty() }

    /** 会动到外面的操作：做到一半被打断，结果说不准。 */
    private val OUTWARD = setOf(
        WebHand.TAP, WebHand.TYPE, WorkJobs.START, WorkJobs.RESUME, Pacts.SET, Pacts.DROP, PanTools.GET,
        ClockTools.ALARM, ClockTools.OFF, ClockTools.ADD, ClockTools.EDIT, ClockTools.DEL,
    )

    /** 开始做之前记一笔；返回这笔的记号，做完（不管成没成，只要知道结果）用 [end] 划掉。 */
    fun begin(id: String?, n: String, a: JSONObject): String? {
        if (id.isNullOrBlank() || !(n in OUTWARD || Mcp.owns(n))) return null
        val key = java.util.UUID.randomUUID().toString()
        val what = runCatching { Trail.doneTitle(n, a) }.getOrDefault(n).take(40)
        synchronized(lock) { val s = load(id); s.half[key] = what; save(id, s) }
        return key
    }

    fun end(id: String?, key: String?) {
        if (id.isNullOrBlank() || key == null) return
        synchronized(lock) { val s = load(id); if (s.half.remove(key) != null) save(id, s) }
    }

    /** 每轮一个：她要收尾时，清单上还有在做的、这一轮又没碰过清单，提醒一次。 */
    class Nudge {
        var touched = false
        private var sent = false

        fun beforeFinish(id: String?, waiting: Boolean): String? {
            if (sent || touched || waiting || id.isNullOrBlank()) return null
            val doing = synchronized(lock) { load(id) }.items.firstOrNull { it.state == DOING } ?: return null
            sent = true
            return "（App 提醒）「${doing.text}」还在做。"
        }
    }
}
