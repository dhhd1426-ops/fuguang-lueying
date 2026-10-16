package com.fuguang.lueying

import org.json.JSONArray
import org.json.JSONObject

/**
 * 设定条目：一段她读到的话，什么时候出现、放在哪，都由你定（照 SillyTavern 的世界书 / RikkaHub 的注入）。
 *  - always：一直在；否则最近 4 句话里出现 keys 中任一个词才带上。
 *  - pos：before 她是谁之前 / after 她是谁之后 / top 对话开头 / depth 对话里。
 *  - depth：对话里离最后一句多远。0 = 最后一句之后（最后读到，她最当回事），1 = 最后一句前，N = 倒数第 N 句前。
 *  - voice：aside 旁白（括号小字）/ self 她心里想的。
 * 放进对话里的条目不另起一条消息，而是贴在那句你的话前面（供应商对消息顺序挑剔，这样最稳）。
 */
data class Entry(
    val id: String,
    val name: String = "",
    val body: String = "",
    val always: Boolean = false,
    val keys: String = "",
    val pos: String = "depth",
    val depth: Int = 2,
    val voice: String = "aside",
    val on: Boolean = true,
) {
    val keyList: List<String> get() = keys.split(Regex("[,，、\\s]+")).map { it.trim() }.filter { it.isNotEmpty() }

    /** 设置页那一行的小注：什么时候、放在哪。 */
    val summary: String get() {
        val w = if (always) "一直在" else keyList.take(3).joinToString("、").let { if (it.isEmpty()) "还没填词" else "提到「$it」时" }
        val p = when (pos) {
            "before" -> "她是谁之前"; "after" -> "她是谁之后"; "top" -> "对话开头"
            else -> if (depth == 0) "最后一句之后" else "倒数第 $depth 句前"
        }
        return "$w · $p"
    }
}

object Entries {
    /** 往回看几句找关键词。 */
    private const val SCAN = 4

    fun read(a: JSONArray?): List<Entry> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
            Entry(
                o.optString("id"), o.optString("n"), o.optString("b"), o.optBoolean("al"), o.optString("k"),
                o.optString("p", "depth"), o.optInt("d", 2).coerceIn(0, 8), o.optString("v", "aside"), o.optBoolean("on", true),
            )
        }
    }

    fun write(list: List<Entry>): JSONArray = JSONArray().apply {
        list.forEach { e ->
            put(JSONObject().put("id", e.id).put("n", e.name).put("b", e.body).put("al", e.always).put("k", e.keys)
                .put("p", e.pos).put("d", e.depth).put("v", e.voice).put("on", e.on))
        }
    }

    fun create(p: Persona): Entry {
        val e = Entry("${System.currentTimeMillis()}-e")
        p.entries.add(e); Memory.save()
        return e
    }

    fun update(p: Persona, e: Entry) {
        val i = p.entries.indexOfFirst { it.id == e.id }
        if (i >= 0) { p.entries[i] = e; Memory.save() }
    }

    fun remove(p: Persona, e: Entry) { p.entries.removeAll { it.id == e.id }; Memory.save() }

    private fun fires(e: Entry, recent: String): Boolean {
        if (!e.on || e.body.isBlank()) return false
        if (e.always) return true
        return e.keyList.any { recent.contains(it, ignoreCase = true) }
    }

    /** 她是谁之前 / 之后的条目（只看「一直在」的，免得上半段每轮变来变去）。 */
    fun around(p: Persona, pos: String): String =
        p.entries.filter { it.pos == pos && it.on && it.always && it.body.isNotBlank() }
            .joinToString("\n\n") { Memory.fill(it.body.trim(), p) }

    private fun wrap(e: Entry, p: Persona): String {
        val t = Memory.fill(e.body.trim(), p)
        return if (e.voice == "self") "（你心里想：$t）" else "（$t）"
    }

    /**
     * 把条目和旁白织进要发出去的对话里（只改发出去的副本，不改存下来的聊天）。
     * aside：最后一句前面的时间旁白（Prompt.aside）。
     */
    fun weave(p: Persona, history: List<ChatMsg>, aside: String): List<ChatMsg> {
        if (history.isEmpty()) return history
        val users = history.indices.filter { history[it].role == "user" }
        if (users.isEmpty()) return history
        val lastUser = users.last()
        val recent = history.takeLast(SCAN).joinToString("\n") { it.text }
        val before = HashMap<Int, MutableList<String>>()
        val after = mutableListOf<String>()
        fun at(i: Int, s: String) { before.getOrPut(i) { mutableListOf() } += s }
        // 不是「一直在」的前后条目，触发了也放进对话开头附近，不动上半段
        p.entries.filter { fires(it, recent) }.forEach { e ->
            when {
                e.pos == "top" || ((e.pos == "before" || e.pos == "after") && !e.always) -> at(users.first(), wrap(e, p))
                e.pos == "depth" && e.depth == 0 -> after += wrap(e, p)
                e.pos == "depth" -> {
                    val idx = (history.size - e.depth).coerceAtLeast(0)
                    at(users.firstOrNull { it >= idx } ?: lastUser, wrap(e, p))
                }
            }
        }
        if (aside.isNotBlank()) at(lastUser, aside)
        return history.mapIndexed { i, m ->
            val pre = before[i]
            val post = if (i == lastUser) after else emptyList()
            if (pre == null && post.isEmpty()) m
            else m.copy(text = (pre.orEmpty() + m.text + post).joinToString("\n"))
        }
    }
}
