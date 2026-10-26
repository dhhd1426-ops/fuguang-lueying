package com.fuguang.lueying

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * 记下的一件事：一句话 + 细节 + 来自哪段对话。
 * kind 分三本：
 *  - 她的记忆：talk 你们聊过的事，promise 她答应过你的，know 她对你的了解
 *  - about 关于你：你的基本情况
 *  - pref 你的偏好：你喜欢她怎么说话、你的喜好和习惯
 *  - lesson 她记的：做事出过的岔子，她自己记的一句（Lessons.kt）
 * by：her 她记下的，you 你写的。
 */
data class Mem(val id: String, val text: String, val detail: String, val at: Long, val conv: String, val convTitle: String, val kind: String = "know", val by: String = "her",
    /** 只有做事经验（kind = lesson）用：哪家哪个型号出的岔子（空 = 不分）；什么时候想起（work / photo / web / talk）；用上几次；没帮上几次；上次想起是什么时候。 */
    val pv: String = "", val scene: String = "", val used: Int = 0, val failed: Int = 0, val usedAt: Long = 0) {
    /** 属于哪一本：diary / about / pref / lesson。 */
    val book: String get() = if (kind == "about" || kind == "pref" || kind == Lessons.KIND) kind else "diary"
    /** 做事经验用顺手了（用上 3 次），哪个型号都带着。 */
    val habit: Boolean get() = kind == Lessons.KIND && used >= 3
    /** 她自己的偏好、样子。 */
    val isSelf: Boolean get() = kind == "self"
}

/** 一位助理：名字、她是谁（系统提示词）、她记得的事、你们认识多久。 */
class Persona(val id: String, name: String, sys: String) {
    var name by mutableStateOf(name)
    var sys by mutableStateOf(sys)
    /** 跨聊天回忆：开着时，她能翻看你们以前的对话。（记录不受它影响，一直都记。） */
    var memOn by mutableStateOf(true)
    /** 记做事经验：出了岔子她自己记一句、下次想起来（Lessons.kt）。关掉就不记也不想起。 */
    var lessonsOn by mutableStateOf(true)
    val items = mutableStateListOf<Mem>()
    var firstAt by mutableLongStateOf(0L)
    var lastAt by mutableLongStateOf(0L)
    var chats by mutableIntStateOf(0)
    /** 回答的手感：温度；Top P（topOn 关着就交给供应商默认）；上下文消息限制：一段对话最多几条（0 = 不限）。 */
    var temp by mutableStateOf(.7f)
    var topOn by mutableStateOf(false)
    var top by mutableStateOf(.9f)
    var limit by mutableIntStateOf(40)
    /** 设定条目：按位置和深度放进她读到的东西里（Entries.kt）。 */
    val entries = mutableStateListOf<Entry>()
    /** 上次整理记忆的时间、那时聊过几次（Reflect.tidy）。 */
    var tidyAt = 0L
    var tidyChats = 0
    /** 这次发消息之前，上一次来找她的时间（touch 会把 lastAt 改成现在）。 */
    var prevAt = 0L
}

/**
 * 助理们：每位都有自己的系统提示词和记忆，同一时间只有一位陪你聊天。只存在这台手机上。
 * 发消息时把正在用的那位拼进 system 提示；她的全局记忆开着时，每轮回答后让模型整理出值得记住的事。
 */
object Memory {
    private const val KEY = "fuguang-personas"
    private const val OLD = "fuguang-memory"
    private var prefs: Prefs? = null

    const val DEFAULT_SYS =
        "你温和、真诚，像一个认识{name}很久的老朋友。说话简洁自然，用口语，不堆砌格式，不用客套话开头，也不说教。" +
            "{name}问问题，就直接给答案；{name}心情不好，就先陪着，不急着讲道理。不确定的事就说不确定，不编。"
    /** 第 83 版以前的默认提示词：存着这段的助理，读出来时当成现在的默认，这样「恢复默认」不会凭空出现。 */
    private const val OLD_DEFAULT_SYS =
        "你温和、真诚，像一个认识他很久的老朋友。说话简洁自然，用口语，不堆砌格式，不用客套话开头，也不说教。" +
            "他问问题，就直接给答案；他心情不好，就先陪着，不急着讲道理。不确定的事就说不确定，不编。"

    val all = mutableStateListOf<Persona>()
    /** 你的称呼：提示词里用它叫你；没填就叫「他」。 */
    var userName by mutableStateOf("")
    private val who: String get() = userName.trim().ifEmpty { "他" }
    /** 提示词里怎么称呼用户。 */
    val userCall: String get() = who
    var activeId by mutableStateOf("")
    val active: Persona get() = all.firstOrNull { it.id == activeId } ?: all.first()

    fun init(p: Prefs) {
        prefs = p
        runCatching {
            val o = JSONObject(p.string(KEY) ?: return@runCatching)
            o.optJSONArray("all")?.let { a -> for (i in 0 until a.length()) all += read(a.getJSONObject(i)) }
            activeId = o.optString("active")
            userName = o.optString("user")
        }
        if (all.isEmpty()) migrate(p)
        if (all.none { it.id == activeId }) activeId = all.first().id
    }

    /** 从只有一位助理的旧版本搬过来：名字、系统提示词、记得的事都留着。 */
    private fun migrate(p: Prefs) {
        val name = runCatching { JSONObject(p.string("assistant-config") ?: "{}").optString("name") }.getOrNull().orEmpty().ifBlank { "Amor" }
        val a = Persona("${System.currentTimeMillis()}-a", name, DEFAULT_SYS)
        runCatching {
            val o = JSONObject(p.string(OLD) ?: return@runCatching)
            a.memOn = o.optBoolean("on", true)
            o.optString("sys").trim().takeIf { it.isNotEmpty() }?.let { a.sys = it }
            a.firstAt = o.optLong("first"); a.lastAt = o.optLong("last"); a.chats = o.optInt("chats")
            o.optJSONArray("m")?.let { m -> for (i in 0 until m.length()) a.items += mem(m.getJSONObject(i)) }
        }
        all += a; activeId = a.id
        save()
    }

    private fun mem(it: JSONObject) = Mem(it.optString("id"), it.optString("t"), it.optString("d"), it.optLong("at"), it.optString("c"), it.optString("ct"), it.optString("k", "know").ifBlank { "know" }, it.optString("b").ifBlank { if (it.optString("c").isEmpty()) "you" else "her" },
        it.optString("pv"), it.optString("sc"), it.optInt("u"), it.optInt("f"), it.optLong("ua"))

    private fun read(o: JSONObject) = Persona(o.optString("id"), o.optString("name", "Amor"), o.optString("sys", DEFAULT_SYS).let { if (it.trim() == OLD_DEFAULT_SYS) DEFAULT_SYS else it }).apply {
        memOn = o.optBoolean("on", true); lessonsOn = o.optBoolean("lesson", true)
        firstAt = o.optLong("first"); lastAt = o.optLong("last"); chats = o.optInt("chats")
        temp = o.optDouble("temp", .7).toFloat(); topOn = o.optBoolean("topOn", false); top = o.optDouble("top", .9).toFloat(); limit = o.optInt("limit", 40)
        o.optJSONArray("m")?.let { m -> for (i in 0 until m.length()) items += mem(m.getJSONObject(i)) }
        entries += Entries.read(o.optJSONArray("e")); tidyAt = o.optLong("tidyAt"); tidyChats = o.optInt("tidyChats")
    }

    fun save() {
        val a = JSONArray()
        all.forEach { p ->
            val m = JSONArray()
            p.items.forEach { m.put(JSONObject().put("id", it.id).put("t", it.text).put("d", it.detail).put("at", it.at).put("c", it.conv).put("ct", it.convTitle).put("k", it.kind).put("b", it.by)
                .apply { if (it.kind == Lessons.KIND) put("pv", it.pv).put("sc", it.scene).put("u", it.used).put("f", it.failed).put("ua", it.usedAt) }) }
            a.put(JSONObject().put("id", p.id).put("name", p.name).put("sys", p.sys).put("on", p.memOn).put("lesson", p.lessonsOn)
                .put("first", p.firstAt).put("last", p.lastAt).put("chats", p.chats)
                .put("temp", p.temp.toDouble()).put("topOn", p.topOn).put("top", p.top.toDouble()).put("limit", p.limit).put("m", m).put("e", Entries.write(p.entries)).put("tidyAt", p.tidyAt).put("tidyChats", p.tidyChats))
        }
        prefs?.putString(KEY, JSONObject().put("active", activeId).put("user", userName).put("all", a).toString())
    }

    fun get(id: String): Persona? = all.firstOrNull { it.id == id }

    /** 新来一位助理，带着默认的系统提示词。 */
    fun create(): Persona {
        val p = Persona("${System.currentTimeMillis()}-p", "新的助理", DEFAULT_SYS)
        all += p; save()
        return p
    }

    fun use(p: Persona) { activeId = p.id; save() }

    /** 删掉一位（至少留一位）；删的是正在用的，就换成第一位。 */
    fun delete(p: Persona) {
        if (all.size <= 1) return
        all.remove(p)
        if (activeId == p.id) activeId = all.first().id
        save()
    }

    /** 撤销删除：放回原位，原来正在用的话也换回来。 */
    fun restore(p: Persona, i: Int, wasActive: Boolean) {
        if (p in all) return
        all.add(i.coerceIn(0, all.size), p)
        if (wasActive) activeId = p.id
        save()
    }

    /** 第一次用：从已有的对话记录里补上相识时间和次数。 */
    fun seed(history: ConvStore) {
        val a = active
        val hers = history.of(a.id)
        if (a.firstAt != 0L || hers.isEmpty()) return
        a.firstAt = hers.minOf { it.created }
        a.lastAt = hers.maxOf { it.updated }
        a.chats = hers.size
        save()
    }

    /** 发出一条消息时记一笔：新对话算一次。 */
    fun touch(newChat: Boolean) {
        val a = active
        val now = System.currentTimeMillis()
        if (a.firstAt == 0L) a.firstAt = now
        a.prevAt = a.lastAt
        a.lastAt = now
        if (newChat) a.chats++
        save()
    }

    fun remove(p: Persona, m: Mem) { p.items.remove(m); save() }
    fun restore(p: Persona, m: Mem, i: Int) { if (m !in p.items) { p.items.add(i.coerceIn(0, p.items.size), m); save() } }

    /**
     * 你手动加一件事到某一本里（book：diary / about / pref）。
     * 你写的「我」换成「他」，「我们」不动，这样拼进提示词时读起来是她知道的事。
     */
    fun add(p: Persona, book: String, text: String, detail: String = ""): Mem? {
        val t = hers(text.trim())
        if (t.isEmpty()) return null
        val dt = hers(detail.trim())
        val k = when (book) { "about", "pref", Lessons.KIND -> book; else -> if ("答应" in t || "答应" in dt) "promise" else "know" }
        val now = System.currentTimeMillis()
        val m = Mem("$now-m", t, dt, now, "", "", k, "you", scene = if (k == Lessons.KIND) Lessons.guessScene(t + dt) else "")
        p.items.add(0, m)
        save()
        return m
    }

    private fun hers(s: String) = s.replace("我们", "\u0000").replace("咱们", "\u0000").replace("我", "他").replace("\u0000", "我们")

    /** 旧记忆里的"用户"统一换成"他"，读起来是她自己知道的事。 */
    fun ownWords(s: String) = s.replace("该用户", who).replace("用户", who)

    /**
     * 提示词里可以写的占位符（照 RikkaHub，{x} 和 {{x}} 都认，不分大小写）：
     * {name} / {user} = 用户名称（没填就是「他」），{char} = 她的名字，{model} = 正在用的模型，{date} = 今天日期。
     */
    fun fill(text: String, p: Persona, model: String = ""): String {
        val date = java.text.SimpleDateFormat("yyyy 年 M 月 d 日 EEEE", java.util.Locale.CHINA).format(java.util.Date())
        var s = text
        mapOf("name" to who, "user" to who, "char" to p.name, "model" to model.ifBlank { "未知" }, "date" to date).forEach { (k, v) ->
            s = s.replace("{{$k}}", v, ignoreCase = true).replace("{$k}", v, ignoreCase = true)
        }
        return s
    }

    /* ---------- 她自己用的记忆工具：记下、忘掉 ---------- */

    const val REMEMBER = "remember"
    const val FORGET = "forget"

    val toolSpecs: List<ToolSpec> get() = listOf(
        ToolSpec(
            REMEMBER,
            "$who 明确让你记住某件事（“记住…”“别忘了…”“以后都…”），或者纠正你（“不是这样”“别再…”）时，当场记下。平常聊天里的事不用你记，聊完你会自己回想一遍。" +
                "kind：about=$who 的情况；pref=$who 的偏好、希望你怎么和他相处（纠正也记在这里）；self=你自己的样子；promise=你答应$who 的事；talk=你们定下、还没做完的事；know=其他。" +
                "提到“明天”“周四”这类日子，写成具体日期。",
            JSONObject().put("type", "object").put("properties", JSONObject()
                .put("kind", JSONObject().put("type", "string").put("enum", JSONArray(listOf("about", "pref", "self", "promise", "talk", "know"))))
                .put("text", JSONObject().put("type", "string").put("description", "一句话，30 字以内，用你自己的口吻"))
                .put("why", JSONObject().put("type", "string").put("description", "纠正或偏好时：为什么、什么时候照着做，一句话，可以不填"))
            ).put("required", JSONArray(listOf("kind", "text"))),
        ),
        ToolSpec(
            FORGET,
            "从你的记忆里删掉一件事。$who 说忘掉、别记、记错了的时候用。text 写那件事的关键词。",
            JSONObject().put("type", "object").put("properties", JSONObject()
                .put("text", JSONObject().put("type", "string").put("description", "要删的那件事里的关键词"))
            ).put("required", JSONArray(listOf("text"))),
        ),
    )

    fun toolRule(): String =
        "聊天时不用操心记什么，聊完你会自己回想一遍。$who 明确让你记住什么、或者纠正你的时候，用 $REMEMBER 当场记下；$who 让你忘掉什么，用 $FORGET 删掉，" +
            "做完说一声就好（比如“记住了”）。你们闹着玩、一起编故事时说的话不用记；密码、验证码、证件号这类绝不记。"

    fun runTool(p: Persona, name: String, a: JSONObject, conv: String, convTitle: String): ToolOut {
        if (name == REMEMBER) {
            val t = a.optString("text").trim().take(80)
            if (t.isEmpty()) return ToolOut("没有内容。")
            if (Reflect.sensitive(t)) return ToolOut("这条像是证件号、卡号或密码，不记。")
            val k = a.optString("kind").trim().let { if (it in setOf("about", "pref", "self", "promise", "talk", "know")) it else "know" }
            if (p.items.any { it.text == t }) return ToolOut("已经记得了。")
            val now = System.currentTimeMillis()
            p.items.add(0, Mem("$now-t", t, a.optString("why").trim().take(80), now, conv, convTitle, k, "her"))
            save()
            return ToolOut("记下了。")
        }
        if (name == FORGET) {
            val q = a.optString("text").trim()
            if (q.isEmpty()) return ToolOut("没说要忘掉哪件。")
            val hit = p.items.filter { it.text.contains(q) || q.contains(it.text) || q.split(Regex("\\s+")).all { w -> it.text.contains(w) } }
            if (hit.isEmpty()) return ToolOut("没找到这件事。")
            p.items.removeAll(hit.toSet()); save()
            return ToolOut("忘掉了 ${hit.size} 件：" + hit.joinToString("；") { it.text })
        }
        return ToolOut("没有这个工具。")
    }
}
