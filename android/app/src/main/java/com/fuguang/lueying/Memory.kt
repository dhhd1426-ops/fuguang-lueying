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
 * by：her 她记下的，you 你写的。
 */
data class Mem(val id: String, val text: String, val detail: String, val at: Long, val conv: String, val convTitle: String, val kind: String = "know", val by: String = "her") {
    /** 属于哪一本：diary / about / pref。 */
    val book: String get() = if (kind == "about" || kind == "pref") kind else "diary"
    /** 她自己的偏好、样子。 */
    val isSelf: Boolean get() = kind == "self"
}

/** 一位助理：名字、她是谁（系统提示词）、她记得的事、你们认识多久。 */
class Persona(val id: String, name: String, sys: String) {
    var name by mutableStateOf(name)
    var sys by mutableStateOf(sys)
    /** 跨聊天回忆：开着时，她能翻看你们以前的对话。（记录不受它影响，一直都记。） */
    var memOn by mutableStateOf(true)
    val items = mutableStateListOf<Mem>()
    var firstAt by mutableLongStateOf(0L)
    var lastAt by mutableLongStateOf(0L)
    var chats by mutableIntStateOf(0)
    /** 回答的手感：温度；Top P（topOn 关着就交给供应商默认）；上下文消息限制：一段对话最多几条（0 = 不限）。 */
    var temp by mutableStateOf(.7f)
    var topOn by mutableStateOf(false)
    var top by mutableStateOf(.9f)
    var limit by mutableIntStateOf(40)
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
        "你温和、真诚，像一个认识他很久的老朋友。说话简洁自然，用口语，不堆砌格式，不用客套话开头，也不说教。" +
            "他问问题，就直接给答案；他心情不好，就先陪着，不急着讲道理。不确定的事就说不确定，不编。"

    val all = mutableStateListOf<Persona>()
    /** 你的称呼：提示词里用它叫你；没填就叫「他」。 */
    var userName by mutableStateOf("")
    private val who: String get() = userName.trim().ifEmpty { "他" }
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

    private fun mem(it: JSONObject) = Mem(it.optString("id"), it.optString("t"), it.optString("d"), it.optLong("at"), it.optString("c"), it.optString("ct"), it.optString("k", "know").ifBlank { "know" }, it.optString("b").ifBlank { if (it.optString("c").isEmpty()) "you" else "her" })

    private fun read(o: JSONObject) = Persona(o.optString("id"), o.optString("name", "Amor"), o.optString("sys", DEFAULT_SYS)).apply {
        memOn = o.optBoolean("on", true)
        firstAt = o.optLong("first"); lastAt = o.optLong("last"); chats = o.optInt("chats")
        temp = o.optDouble("temp", .7).toFloat(); topOn = o.optBoolean("topOn", false); top = o.optDouble("top", .9).toFloat(); limit = o.optInt("limit", 40)
        o.optJSONArray("m")?.let { m -> for (i in 0 until m.length()) items += mem(m.getJSONObject(i)) }
    }

    fun save() {
        val a = JSONArray()
        all.forEach { p ->
            val m = JSONArray()
            p.items.forEach { m.put(JSONObject().put("id", it.id).put("t", it.text).put("d", it.detail).put("at", it.at).put("c", it.conv).put("ct", it.convTitle).put("k", it.kind).put("b", it.by)) }
            a.put(JSONObject().put("id", p.id).put("name", p.name).put("sys", p.sys).put("on", p.memOn)
                .put("first", p.firstAt).put("last", p.lastAt).put("chats", p.chats)
                .put("temp", p.temp.toDouble()).put("topOn", p.topOn).put("top", p.top.toDouble()).put("limit", p.limit).put("m", m))
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
        if (a.firstAt != 0L || history.all.isEmpty()) return
        a.firstAt = history.all.minOf { it.created }
        a.lastAt = history.all.maxOf { it.updated }
        a.chats = history.all.size
        save()
    }

    /** 发出一条消息时记一笔：新对话算一次。 */
    fun touch(newChat: Boolean) {
        val a = active
        val now = System.currentTimeMillis()
        if (a.firstAt == 0L) a.firstAt = now
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
        val k = when (book) { "about", "pref" -> book; else -> if ("答应" in t || "答应" in dt) "promise" else "know" }
        val now = System.currentTimeMillis()
        val m = Mem("$now-m", t, dt, now, "", "", k, "you")
        p.items.add(0, m)
        save()
        return m
    }

    private fun hers(s: String) = s.replace("我们", "\u0000").replace("咱们", "\u0000").replace("我", "他").replace("\u0000", "我们")

    /** 旧记忆里的"用户"统一换成"他"，读起来是她自己知道的事。 */
    private fun own(s: String) = s.replace("该用户", who).replace("用户", who)

    /** 时间感知：现在几点、认识多久、多久没来了。 */
    private fun timeSense(p: Persona): String {
        val now = System.currentTimeMillis()
        val c = java.util.Calendar.getInstance()
        val h = c.get(java.util.Calendar.HOUR_OF_DAY)
        val part = when (h) { in 0..4 -> "凌晨"; in 5..8 -> "早上"; in 9..11 -> "上午"; in 12..13 -> "中午"; in 14..17 -> "下午"; in 18..22 -> "晚上"; else -> "深夜" }
        val lines = mutableListOf<String>()
        lines += java.text.SimpleDateFormat("M 月 d 日 EEEE", java.util.Locale.CHINA).format(java.util.Date(now)) + " $part " +
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA).format(java.util.Date(now)) + "。"
        if (p.firstAt <= 0) lines += "这是$who 第一次找你聊天。"
        else {
            val days = ((now - p.firstAt) / 86_400_000L).toInt() + 1
            lines += "你们认识第 $days 天，聊过 ${p.chats} 次。"
            val gap = now - p.lastAt
            if (p.lastAt > 0) {
                val min = gap / 60_000
                lines += "上一次$who 找你是" + when {
                    min < 10 -> "刚刚"
                    min < 60 -> "${min} 分钟前"
                    min < 24 * 60 -> "${min / 60} 小时前"
                    min < 48 * 60 -> "昨天"
                    else -> "${min / 1440} 天前"
                } + "。"
                if (gap >= 7L * 86_400_000) lines += "已经一周多没来了，可以自然地问问最近怎么样，别埋怨。"
                else if (gap >= 3L * 86_400_000) lines += "好几天没聊了，可以轻轻带一句好久不见。"
            }
        }
        if (h in 1..4) lines += "现在很晚了。"
        return lines.joinToString("")
    }

    /**
     * 拼进 system 的部分，和云朵天气一样分段加【标签】：
     * 你写的提示词（{name} 换成你的称呼）→【时间感知】→【关于你】→【你的偏好】→【她自己的样子】→【她自己记得的事】。
     */
    fun prompt(p: Persona = active): String {
        val sb = StringBuilder()
        sb.append(p.sys.trim().ifEmpty { DEFAULT_SYS }.replace("{name}", who))
        sb.append("\n\n【时间感知】\n").append(timeSense(p))
        fun line(m: Mem) = own(m.text).trimEnd('。') + if (m.detail.isNotBlank()) "（" + own(m.detail).trimEnd('。') + "）" else ""
        fun block(title: String, ms: List<Mem>) {
            if (ms.isNotEmpty()) sb.append("\n\n【").append(title).append("】\n").append(ms.joinToString("\n") { "- " + line(it) })
        }
        block("关于$who", p.items.filter { it.kind == "about" }.take(30))
        block("${who}的偏好，回答时照着做", p.items.filter { it.kind == "pref" }.take(30))
        block("你（${p.name}）自己的样子", p.items.filter { it.kind == "self" }.take(20))
        val diary = p.items.filter { it.kind == "talk" || it.kind == "promise" || it.kind == "know" }.take(48)
        if (diary.isNotEmpty()) {
            sb.append("\n\n【你自己记得的事】\n").append(diary.joinToString("\n") { "- " + (if (it.kind == "promise") "答应过：" else "") + line(it) })
        }
        if (p.items.isNotEmpty())
            sb.append("\n\n上面这些是你本来就记得、感觉得到的，像老朋友一样自然地用上：合适的时候提一句，不要逐条复述，不要每次都说时间，也不要说“根据记忆”“根据记录”之类的话。答应过的事放在心上，$who 提起或聊到相关的事时再接上。")
        return sb.toString()
    }

    /* ---------- 她自己用的记忆工具：记下、忘掉 ---------- */

    const val REMEMBER = "remember"
    const val FORGET = "forget"

    val toolSpecs: List<ToolSpec> get() = listOf(
        ToolSpec(
            REMEMBER,
            "把值得长期记住的事记进你的记忆。kind：about=$who 的基本情况（住哪、做什么、身边的人）；pref=$who 的偏好、习惯、希望你怎么说话；" +
                "self=你自己的偏好和样子（比如你喜欢怎么说话、你的口头禅）；promise=你答应$who 的事；talk=你们一起定下的、聊到哪一步了；know=你对$who 的其他了解。",
            JSONObject().put("type", "object").put("properties", JSONObject()
                .put("kind", JSONObject().put("type", "string").put("enum", JSONArray(listOf("about", "pref", "self", "promise", "talk", "know"))))
                .put("text", JSONObject().put("type", "string").put("description", "一句话，30 字以内，用你自己的口吻"))
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
        "你可以用 $REMEMBER 记下值得长期记住的事（$who 的情况、$who 的偏好、你自己的偏好、你答应的事），用 $FORGET 删掉$who 让你忘掉的。" +
            "要不要记由你自己判断：$who 明确说“记住”的一定记；寒暄、一次性的问题不记；密码、验证码、证件号这类绝不记。记的时候不用说出来。"

    fun runTool(p: Persona, name: String, a: JSONObject, conv: String, convTitle: String): ToolOut {
        if (name == REMEMBER) {
            val t = a.optString("text").trim().take(80)
            if (t.isEmpty()) return ToolOut("没有内容。")
            val k = a.optString("kind").trim().let { if (it in setOf("about", "pref", "self", "promise", "talk", "know")) it else "know" }
            if (p.items.any { it.text == t }) return ToolOut("已经记得了。")
            val now = System.currentTimeMillis()
            p.items.add(0, Mem("$now-t", t, "", now, conv, convTitle, k, "her"))
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

    /** 一轮回答之后：让她回想一下，有什么关于他的事值得记住。失败就算了，不打扰用户。 */
    suspend fun learn(who: Persona, p: Provider, c: ProviderConfig, model: String, user: String, reply: String, conv: String, convTitle: String) {
        if (user.isBlank() || reply.isBlank()) return
        val sys = "你是${who.name}，在写自己的日记。回想刚才和他的这段对话，记下你想一直记住的事。只输出 JSON，不要别的文字。"
        val tag = mapOf("talk" to "聊过", "promise" to "答应", "know" to "了解", "about" to "他的情况", "pref" to "他的偏好", "self" to "你自己")
        val known = who.items.take(60).joinToString("\n") { "- [" + (tag[it.kind] ?: "了解") + "] " + it.text }.ifBlank { "（还没有）" }
        val ask = """
你已经记得的事：
$known

这一轮对话：
他：${user.take(1500)}
你：${reply.take(1500)}

你只记这几种事：
1. talk 你们聊过的事：一起定下的决定、做到哪一步了、还悬着没定的问题。比如“我们定了主页分四块，随笔谁来写还没定”。
2. promise 你答应过他的：你说要做、要提醒、要等他回复再做的事。比如“答应给他做一段动画，等他点头就开工”。你答应过的一定要记。
3. about 他的基本情况：住在哪、做什么工作、身边的人。比如“他在济南，是工程师”。
4. pref 他的偏好：他希望你怎么和他说话、他的喜好和习惯。比如“他喜欢回答短一点”“时间以网络时间为准”。
5. self 你自己的偏好和样子：比如你发现自己喜欢怎么说话、你的口头禅。
6. know 你对他的其他了解：在做的事、在意的东西，放不进上面两类的。比如“他在用羽记笔记，分工作和创作两路”。

寒暄、一次性的小问题、已经记得的都不要；如果这一轮把以前记的事推进了或兑现了，就写成新的一条。大多数时候什么都不用记，返回空数组。
用你自己的口吻写，称他为“他”，说你们俩就用“我们”；不要写“用户”，不要写日期，不要以你的名字开头。
输出格式：{"memories":[{"kind":"talk|promise|about|pref|self|know","text":"一句话，24 字以内","detail":"补充细节，60 字以内，可以为空"}]}
""".trim()
        val raw = runCatching { ChatApi.complete(p, c, model, sys, ask) }.getOrNull() ?: return
        val json = raw.substringAfter('{', "").substringBeforeLast('}', "").let { if (it.isEmpty()) return else "{$it}" }
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        o.optJSONArray("memories")?.let { a ->
            for (i in 0 until a.length()) {
                val m = a.optJSONObject(i) ?: continue
                val t = m.optString("text").trim()
                if (t.isEmpty() || who.items.any { it.text == t }) continue
                val k = m.optString("kind").trim().lowercase().let { if (it in setOf("talk", "promise", "about", "pref", "self")) it else "know" }
                who.items.add(0, Mem("$now-$i", t, m.optString("detail").trim(), now, conv, convTitle, k))
            }
        }
        save()
    }
}
