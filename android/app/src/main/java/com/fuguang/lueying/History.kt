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
    /** 这段对话属于哪位助理（Persona.id）。开始时定下，之后不换。 */
    val who: String = "",
    /** 这段聊了什么（第 359 版）：只给她翻以前的聊天时看，聊完回看时记忆模型顺手补（Reflect.digest）。 */
    val gist: String = "",
    /** 小结最后一次写好的时间。 */
    val gistAt: Long = 0,
    /** 前情（第 394 版，Fold.kt）：前面整理过的几段；null = 还没整理过。 */
    val fold: FoldData? = null,
) {
    /** 预览：最后一条回复的第一句。 */
    val preview: String
        get() = msgs.lastOrNull { it.role == "assistant" }?.text?.let { firstSentence(it.replace(Regex("[*#`>]"), ""), 26) }.orEmpty()
}

/** 对话记录：只存在这台手机上。 */
class ConvStore(private val prefs: Prefs) {
    val all = mutableStateListOf<Conv>()
    private val file = prefs.file(FILE)

    init {
        runCatching {
            // 第 381 版起存在 files/chat-history.json；还没搬过来的从设置里读
            val arr = JSONArray(file.takeIf { it.exists() }?.readText() ?: prefs.string(KEY) ?: return@runCatching)
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
                        ChatMsg(m.optString("r"), m.optString("x"), m.optString("k"), m.optJSONArray("i")?.let { a -> List(a.length()) { a.optString(it) } } ?: emptyList(), m.optLong("t"),
                            m.optJSONArray("w")?.let { a -> List(a.length()) { a.optString(it) } } ?: emptyList(),
                            m.optJSONArray("s")?.let { a -> List(a.length()) { a.getJSONObject(it).let { s -> ToolStep(s.optInt("a"), s.optString("n"), s.optString("t"), s.optString("d")) } } } ?: emptyList(),
                            m.optJSONArray("v")?.let { a -> List(a.length()) { a.optString(it) } } ?: emptyList(),
                            m.optJSONArray("f")?.let { a -> List(a.length()) { a.optString(it) } } ?: emptyList(),
                            m.optJSONArray("wa")?.let { a -> List(a.length()) { a.optInt(it) } } ?: emptyList(),
                            m.optString("pv"), m.optLong("tm"), lesson = m.optString("ls"), pact = m.optString("pc"))
                    },
                    who = o.optString("a"),
                    gist = o.optString("g"),
                    gistAt = o.optLong("ga"),
                    fold = FoldData.of(o.optJSONObject("fd")),
                )
            }
        }
        // 以前的对话没记归属：归到现在选着的那一位；助理已经删掉的，她的对话也一起清掉
        if (Memory.all.isNotEmpty()) {
            val ids = Memory.all.map { it.id }.toSet()
            val before = all.size
            var changed = false
            for (i in all.indices) if (all[i].who.isEmpty()) { all[i] = all[i].copy(who = Memory.activeId); changed = true }
            all.removeAll { it.who !in ids }
            if (changed || all.size != before) persist()
        }
    }

    /** 这位助理的对话（默认是现在选着的那一位）。 */
    fun of(who: String = Memory.activeId): List<Conv> = all.filter { it.who == who }

    /** 还在的助理的对话（删掉还没撤销的那位，她的对话先藏起来）。 */
    fun visible(): List<Conv> = all.filter { c -> Memory.all.any { it.id == c.who } }

    fun get(id: String?): Conv? = all.firstOrNull { it.id == id }

    /** 保存一段对话，返回它的 id（新对话会生成一个）。 */
    fun save(id: String?, msgs: List<ChatMsg>): String? {
        if (msgs.isEmpty()) return id
        val now = System.currentTimeMillis()
        val old = get(id)
        val title = old?.title ?: firstSentence(msgs.first { it.role == "user" }.text, 18)
        // 前情只认前面那几条没动过：改了话重发、删到了前情里面，就作废（下次整理重来）
        val fold = old?.fold?.takeIf { f -> f.upto < msgs.size && msgs.getOrNull(f.upto - 1)?.at == old?.msgs?.getOrNull(f.upto - 1)?.at }
        val c = Conv(id ?: now.toString(), title, old?.created ?: now, now, old?.pinned ?: false, msgs.toList(), old?.who?.ifEmpty { null } ?: Memory.activeId, old?.gist.orEmpty(), old?.gistAt ?: 0, fold)
        val i = all.indexOfFirst { it.id == c.id }
        if (i >= 0) all[i] = c else all.add(0, c)
        persist()
        return c.id
    }

    /** 她主动来找你：这句接在那段对话后面（没有就新开一段，名字用约定的名字）。返回对话 id。 */
    fun append(id: String?, m: ChatMsg, who: String, title: String): String {
        val now = System.currentTimeMillis()
        val old = get(id)
        val c = Conv(old?.id ?: now.toString(), old?.title ?: title, old?.created ?: now, now, old?.pinned ?: false, (old?.msgs ?: emptyList()) + m, old?.who?.ifEmpty { null } ?: who, old?.gist.orEmpty(), old?.gistAt ?: 0, old?.fold)
        val i = all.indexOfFirst { it.id == c.id }
        if (i >= 0) all[i] = c else all.add(0, c)
        persist()
        return c.id
    }

    /** 换上新的小结（不动更新时间，不把这段顶到最上面）。 */
    fun setGist(id: String, gist: String) {
        val i = all.indexOfFirst { it.id == id }
        if (i < 0 || all[i].gist == gist) return
        all[i] = all[i].copy(gist = gist, gistAt = System.currentTimeMillis())
        persist()
    }

    /** 换上新的前情（不动更新时间）。整理时他又接着聊了也没关系：前情只管前面那几条。 */
    fun setFold(id: String, f: FoldData?) {
        val i = all.indexOfFirst { it.id == id }
        if (i < 0 || all[i].fold == f) return
        if (f != null && f.upto >= all[i].msgs.size) return
        all[i] = all[i].copy(fold = f)
        persist()
    }

    fun togglePin(id: String) {
        val i = all.indexOfFirst { it.id == id }
        if (i < 0) return
        all[i] = all[i].copy(pinned = !all[i].pinned)
        persist()
    }

    /** 改名：空的不改。 */
    fun rename(id: String, title: String) {
        val t = title.trim().take(40).ifEmpty { return }
        val i = all.indexOfFirst { it.id == id }
        if (i < 0 || all[i].title == t) return
        all[i] = all[i].copy(title = t)
        persist()
    }

    /** 删掉后撤销：原样放回去。 */
    fun restore(c: Conv) {
        if (all.any { it.id == c.id }) return
        all.add(0, c)
        persist()
    }

    fun delete(id: String) {
        all.removeAll { it.id == id }
        persist()
    }

    /** 存盘：主线程只拍一份快照（都是不可变数据），转 JSON、写文件在后台一条线上做，连着几次只写最后一份。
     *  以前整段记录塞在设置文件里，每存一次主线程转一遍 JSON，系统还要把整个设置文件重写；收尾时关通知会让主线程等它写完。 */
    private fun persist() {
        val snap = all.toList()
        val f = file
        val first = !migrated
        migrated = true
        Saver.put(f) {
            val arr = JSONArray()
            snap.sortedByDescending { it.updated }.forEach { c ->
                val ms = JSONArray()
                c.msgs.forEach { m -> ms.put(JSONObject().put("r", m.role).put("x", m.text).apply { if (m.think.isNotEmpty()) put("k", m.think); if (m.imgs.isNotEmpty()) put("i", JSONArray(m.imgs)); if (m.works.isNotEmpty()) put("w", JSONArray(m.works)); if (m.workAt.isNotEmpty()) put("wa", JSONArray(m.workAt)); if (m.clips.isNotEmpty()) put("v", JSONArray(m.clips)); if (m.files.isNotEmpty()) put("f", JSONArray(m.files)); if (m.steps.isNotEmpty()) put("s", JSONArray(m.steps.map { s -> JSONObject().put("a", s.at).put("n", s.name).put("t", s.title).put("d", s.detail) })); if (m.at > 0) put("t", m.at); if (m.pid.isNotEmpty()) put("pv", m.pid); if (m.thinkMs > 0) put("tm", m.thinkMs); if (m.lesson.isNotEmpty()) put("ls", m.lesson); if (m.pact.isNotEmpty()) put("pc", m.pact) }) }
                arr.put(JSONObject().put("id", c.id).put("t", c.title).put("c", c.created).put("u", c.updated).put("p", c.pinned).put("a", c.who).put("m", ms).apply { if (c.gist.isNotEmpty()) put("g", c.gist).put("ga", c.gistAt); c.fold?.let { put("fd", it.json()) } })
            }
            arr.toString()
        }
        // 第一次写进文件后，把设置里的旧副本清掉（写成功才清，见 Saver）
        if (first) Saver.after(f) { prefs.putString(KEY, null) }
    }

    /** 对话记录在后台写文件：同一个文件排着的只留最新一份；写到临时文件再改名，不会写坏一半。 */
    object Saver {
        private val pool = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "conv-save").apply { isDaemon = true } }
        private val pending = HashMap<java.io.File, () -> String>()
        private var last: java.util.concurrent.Future<*>? = null

        fun put(f: java.io.File, make: () -> String) {
            val run = synchronized(pending) { val had = f in pending; pending[f] = make; !had }
            if (run) synchronized(this) { last = pool.submit { write(f) } }
        }

        /** 前面排着的都写完以后在主线程做一件事（写失败就不做）。 */
        fun after(f: java.io.File, then: () -> Unit) {
            synchronized(this) { last = pool.submit { if (f.exists()) android.os.Handler(android.os.Looper.getMainLooper()).post { then() } } }
        }

        private fun write(f: java.io.File) {
            val make = synchronized(pending) { pending.remove(f) } ?: return
            runCatching {
                val text = make()
                f.parentFile?.mkdirs()
                val tmp = java.io.File(f.path + ".tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            }
        }

        /** 切到后台时等排着的写完（最多 2 秒），和以前设置文件的保证一样。 */
        fun flush() {
            val f = synchronized(this) { last } ?: return
            runCatching { f.get(2, java.util.concurrent.TimeUnit.SECONDS) }
        }
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
        private const val FILE = "chat-history.json"
        /** 这次打开以来写过一次文件了：设置里的旧副本只清一次。 */
        @Volatile private var migrated = false
        fun clock(t: Long): String = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(t))
    }
}
