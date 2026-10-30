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
    val pv: String = "", val scene: String = "", val used: Int = 0, val failed: Int = 0, val usedAt: Long = 0,
    /** 记忆页里她写的那段话（Diary.kt，照 Voice 的句群层次）；空 = 还没写。想留着的一刻（暧昧、开心）。 */
    val diary: String = "", val moment: Boolean = false,
    /**
     * 第 345 版的记忆：[text] 是标题，[diary] 是正文（她的第一人称，照事实，段落分明）。
     * pin 常驻（正文一直带在她身上）；done 每天整理过了；under 收进了哪一份周 / 月 / 年；till 周月年那份到哪天为止。
     */
    val pin: Boolean = false, val done: Boolean = false, val under: String = "", val till: Long = 0) {
    /** 人和规矩：关于你、你喜欢的、我学会的、关于我。不按时间收拢，记忆本里带星号。 */
    val rule: Boolean get() = kind in RULES
    /** 按周、月、年收拢的那一份。 */
    val span: Boolean get() = kind in SPANS
    /** 目录里的编号：她翻记忆时用。 */
    val code: String get() = Integer.toString((id.hashCode() and 0x7fffffff) % 60466176, 36).padStart(5, '0')
    /** 属于哪一本：diary / about / pref / lesson。 */
    val book: String get() = if (kind == "about" || kind == "pref" || kind == Lessons.KIND) kind else "diary"
    /** 做事经验用顺手了（用上 3 次），哪个型号都带着。 */
    val habit: Boolean get() = kind == Lessons.KIND && used >= 3
    /** 她自己的偏好、样子。 */
    val isSelf: Boolean get() = kind == "self"

    companion object {
        val RULES = setOf("about", "pref", "self", "lesson")
        val SPANS = setOf("week", "month", "year")
    }
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
    /** 每天第一次来找她时整理一次：上次整理是哪天（yyyy-MM-dd）。 */
    var tidyDay = ""
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

    private var loaded = false

    fun init(p: Prefs) {
        prefs = p
        // 换主题、换图标时页面会重建、再走一遍这里（进程还在）：读过就不再读。不能清空重读：正在聊的那位会被换成新对象，她手上记的东西会丢
        if (loaded) return
        loaded = true
        var dup = false
        runCatching {
            val o = JSONObject(p.string(KEY) ?: return@runCatching)
            o.optJSONArray("all")?.let { a ->
                for (i in 0 until a.length()) {
                    val one = read(a.getJSONObject(i))
                    // 以前已经存成两份的：同一个 id 只留第一份
                    if (all.any { it.id == one.id }) dup = true else all += one
                }
            }
            activeId = o.optString("active")
            userName = o.optString("user")
        }
        if (all.isEmpty()) migrate(p)
        if (all.none { it.id == activeId }) activeId = all.first().id
        if (dup) save()
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
        it.optString("pv"), it.optString("sc"), it.optInt("u"), it.optInt("f"), it.optLong("ua"), it.optString("w"), it.optBoolean("mo"),
        it.optBoolean("pin"), it.optBoolean("ok"), it.optString("up"), it.optLong("till"))

    private fun read(o: JSONObject) = Persona(o.optString("id"), o.optString("name", "Amor"), o.optString("sys", DEFAULT_SYS).let { if (it.trim() == OLD_DEFAULT_SYS) DEFAULT_SYS else it }).apply {
        memOn = o.optBoolean("on", true); lessonsOn = o.optBoolean("lesson", true)
        firstAt = o.optLong("first"); lastAt = o.optLong("last"); chats = o.optInt("chats")
        temp = o.optDouble("temp", .7).toFloat(); topOn = o.optBoolean("topOn", false); top = o.optDouble("top", .9).toFloat(); limit = o.optInt("limit", 40)
        o.optJSONArray("m")?.let { m -> for (i in 0 until m.length()) items += mem(m.getJSONObject(i)) }
        entries += Entries.read(o.optJSONArray("e")); tidyAt = o.optLong("tidyAt"); tidyChats = o.optInt("tidyChats"); tidyDay = o.optString("tidyDay")
    }

    fun save() {
        val a = JSONArray()
        all.forEach { p ->
            val m = JSONArray()
            p.items.forEach { m.put(JSONObject().put("id", it.id).put("t", it.text).put("d", it.detail).put("at", it.at).put("c", it.conv).put("ct", it.convTitle).put("k", it.kind).put("b", it.by)
                .apply { if (it.diary.isNotBlank()) put("w", it.diary); if (it.moment) put("mo", true); if (it.pin) put("pin", true); if (it.done) put("ok", true); if (it.under.isNotEmpty()) put("up", it.under); if (it.till > 0) put("till", it.till) }
                .apply { if (it.kind == Lessons.KIND) put("pv", it.pv).put("sc", it.scene).put("u", it.used).put("f", it.failed).put("ua", it.usedAt) }) }
            a.put(JSONObject().put("id", p.id).put("name", p.name).put("sys", p.sys).put("on", p.memOn).put("lesson", p.lessonsOn)
                .put("first", p.firstAt).put("last", p.lastAt).put("chats", p.chats)
                .put("temp", p.temp.toDouble()).put("topOn", p.topOn).put("top", p.top.toDouble()).put("limit", p.limit).put("m", m).put("e", Entries.write(p.entries)).put("tidyAt", p.tidyAt).put("tidyChats", p.tidyChats).put("tidyDay", p.tidyDay))
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
        Reflect.place(p, m)
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

    /* ---------- 她自己用的记忆工具：记下、翻记忆、忘掉（第 345 版） ---------- */

    const val REMEMBER = "remember"
    const val READ = "read_memory"
    const val FORGET = "forget"

    val toolSpecs: List<ToolSpec> get() = listOf(
        ToolSpec(
            READ,
            "翻你的记忆：按目录里的编号拿到那几条的正文。只在$who 这句话和某条直接有关时才翻；不相关就不翻。周、月、年那几份翻开后还能看到下面挂着的编号，不够细再往下翻。",
            JSONObject().put("type", "object").put("properties", JSONObject()
                .put("ids", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "目录里方括号中的编号，一次最多 6 个"))
            ).put("required", JSONArray(listOf("ids"))),
        ),
        ToolSpec(
            REMEMBER,
            "$who 明确让你记住某件事（“记住…”“别忘了…”“以后都…”），或者纠正你（“不是这样”“别再…”）时，当场交给你的记忆。平常聊天里的事不用你记，聊完记忆会自己回头看一遍。" +
                "kind：about=$who 的情况；pref=$who 的偏好和对你的纠正；self=你自己的样子；lesson=做事上学到的；promise=你答应$who 的事；talk=其他发生的事。" +
                "提到“明天”“周四”这类日子，写成具体日期。",
            JSONObject().put("type", "object").put("properties", JSONObject()
                .put("kind", JSONObject().put("type", "string").put("enum", JSONArray(listOf("about", "pref", "self", "lesson", "promise", "talk"))))
                .put("text", JSONObject().put("type", "string").put("description", "要记住的事，照$who 的意思写清楚"))
                .put("why", JSONObject().put("type", "string").put("description", "为什么、什么时候照着做，可以不填"))
            ).put("required", JSONArray(listOf("kind", "text"))),
        ),
        ToolSpec(
            FORGET,
            "从你的记忆里删掉一件事。$who 说忘掉、别记、记错了的时候用。text 写那件事的关键词，或者目录里的编号。",
            JSONObject().put("type", "object").put("properties", JSONObject()
                .put("text", JSONObject().put("type", "string").put("description", "要删的那件事里的关键词，或编号"))
            ).put("required", JSONArray(listOf("text"))),
        ),
    )

    fun toolRule(): String =
        "你的记忆只给了你目录（每条一个标题）。$who 这句话和哪条直接有关，就用 $READ 翻那几条的正文再答；不相关就不翻，翻到了也只用和这句话有关的部分，不顺带讲别的，也不主动提起以前的事。" +
            "$who 明确让你记住什么、或者纠正你的时候，用 $REMEMBER 交给记忆；$who 让你忘掉什么，用 $FORGET 删掉，做完说一声就好（比如“记住了”）。你们闹着玩、一起编故事时说的话不用记；密码、验证码、证件号这类绝不记。"

    /** 时间线上「翻了几条记忆」下面那行：翻的是哪几条的标题。 */
    fun titles(a: JSONObject): String {
        val ids = a.optJSONArray("ids")?.let { arr -> (0 until arr.length()).map { arr.optString(it).trim().trim('[', ']', '#') } }.orEmpty()
        return ids.mapNotNull { c -> active.items.firstOrNull { it.code == c }?.text }.joinToString("、")
    }

    fun runTool(p: Persona, name: String, a: JSONObject, conv: String, convTitle: String): ToolOut {
        if (name == READ) {
            val ids = a.optJSONArray("ids")?.let { arr -> (0 until arr.length()).map { arr.optString(it).trim().trim('[', ']', '#') } }.orEmpty().filter { it.isNotEmpty() }.take(6)
            if (ids.isEmpty()) return ToolOut("没说要翻哪几条。")
            val out = ids.map { c ->
                val m = p.items.firstOrNull { it.code == c } ?: return@map "[$c] 目录里没有这条。"
                MemTree.full(p, m)
            }
            return ToolOut(out.joinToString("\n\n"))
        }
        if (name == REMEMBER) {
            val t = a.optString("text").trim().take(400)
            if (t.isEmpty()) return ToolOut("没有内容。")
            if (Reflect.sensitive(t)) return ToolOut("这条像是证件号、卡号或密码，不记。")
            val k = a.optString("kind").trim().let { if (it in setOf("about", "pref", "self", "lesson", "promise", "talk")) it else "talk" }
            val now = System.currentTimeMillis()
            // 先按原话放进来，记忆模型随后写成标题 + 正文，或者并进说同一件事的那条
            val m = Mem("$now-t", firstSentence(t, 24), a.optString("why").trim().take(200).let { w -> if (w.isEmpty()) t else "$t（$w）" }, now, conv, convTitle, k, "her")
            p.items.add(0, m)
            save()
            Reflect.place(p, m)
            return ToolOut("记下了。")
        }
        if (name == FORGET) {
            val q = a.optString("text").trim().trim('[', ']', '#')
            if (q.isEmpty()) return ToolOut("没说要忘掉哪件。")
            val hit = p.items.filter { it.code == q || it.text.contains(q) || q.contains(it.text) || q.split(Regex("\\s+")).all { w -> it.text.contains(w) } }
            if (hit.isEmpty()) return ToolOut("没找到这件事。")
            p.items.removeAll(hit.toSet()); save()
            return ToolOut("忘掉了 ${hit.size} 件：" + hit.joinToString("；") { it.text })
        }
        return ToolOut("没有这个工具。")
    }
}
