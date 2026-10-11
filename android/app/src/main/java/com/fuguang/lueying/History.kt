package com.fuguang.lueying

import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 一段对话。时间都是毫秒。 */
data class Conv(
    val id: String,
    val title: String,
    val created: Long,
    val updated: Long,
    val pinned: Boolean,
    val msgs: List<ChatMsg>,
) {
    /** 预览：最后一条回复的第一句。 */
    val preview: String
        get() = msgs.lastOrNull { it.role == "assistant" }?.text?.let { firstSentence(it.replace(Regex("[*#`>]"), ""), 26) }.orEmpty()
}

/** 对话记录：只存在这台手机上。 */
class ConvStore(private val prefs: Prefs) {
    val all = mutableStateListOf<Conv>()

    init {
        runCatching {
            val arr = JSONArray(prefs.string(KEY) ?: return@runCatching)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val ms = o.optJSONArray("m") ?: JSONArray()
                all += Conv(
                    id = o.getString("id"),
                    title = o.optString("t"),
                    created = o.optLong("c"),
                    updated = o.optLong("u"),
                    pinned = o.optBoolean("p"),
                    msgs = List(ms.length()) { k ->
                        val m = ms.getJSONObject(k)
                        ChatMsg(m.optString("r"), m.optString("x"), m.optString("k"), m.optJSONArray("i")?.let { a -> List(a.length()) { a.optString(it) } } ?: emptyList())
                    },
                )
            }
        }
    }

    fun get(id: String?): Conv? = all.firstOrNull { it.id == id }

    /** 保存一段对话，返回它的 id（新对话会生成一个）。 */
    fun save(id: String?, msgs: List<ChatMsg>): String? {
        if (msgs.isEmpty()) return id
        val now = System.currentTimeMillis()
        val old = get(id)
        val title = old?.title ?: firstSentence(msgs.first { it.role == "user" }.text, 18)
        val c = Conv(id ?: now.toString(), title, old?.created ?: now, now, old?.pinned ?: false, msgs.toList())
        val i = all.indexOfFirst { it.id == c.id }
        if (i >= 0) all[i] = c else all.add(0, c)
        persist()
        return c.id
    }

    fun togglePin(id: String) {
        val i = all.indexOfFirst { it.id == id }
        if (i < 0) return
        all[i] = all[i].copy(pinned = !all[i].pinned)
        persist()
    }

    fun delete(id: String) {
        all.removeAll { it.id == id }
        persist()
    }

    private fun persist() {
        val arr = JSONArray()
        all.sortedByDescending { it.updated }.forEach { c ->
            val ms = JSONArray()
            c.msgs.forEach { m -> ms.put(JSONObject().put("r", m.role).put("x", m.text).apply { if (m.think.isNotEmpty()) put("k", m.think); if (m.imgs.isNotEmpty()) put("i", JSONArray(m.imgs)) }) }
            arr.put(JSONObject().put("id", c.id).put("t", c.title).put("c", c.created).put("u", c.updated).put("p", c.pinned).put("m", ms))
        }
        prefs.putString(KEY, arr.toString())
    }

    /** 按天分组，新的在上；每组里新的在上。 */
    fun days(): List<Pair<String, List<Conv>>> {
        val today = Calendar.getInstance()
        val md = SimpleDateFormat("M月d日", Locale.CHINA)
        return all.sortedByDescending { it.updated }
            .groupBy { md.format(Date(it.updated)) }
            .map { (label, list) ->
                val cal = Calendar.getInstance().apply { timeInMillis = list.first().updated }
                val isToday = cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) && cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
                (if (isToday) "$label · 今天" else label) to list
            }
    }

    companion object {
        private const val KEY = "chat-history"
        fun clock(t: Long): String = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(t))
    }
}
