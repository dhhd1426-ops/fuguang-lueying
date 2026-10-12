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
 * 她自己记下的一件事：一句话 + 细节 + 来自哪段对话。
 * kind：talk 你们聊过的事，promise 她答应过你的，know 她对你的了解（手动加的也算这一类）。
 */
data class Mem(val id: String, val text: String, val detail: String, val at: Long, val conv: String, val convTitle: String, val kind: String = "know")

/** 一位助理：名字、她是谁（系统提示词）、她记得的事、你们认识多久。 */
class Persona(val id: String, name: String, sys: String) {
    var name by mutableStateOf(name)
    var sys by mutableStateOf(sys)
    var memOn by mutableStateOf(true)
    val items = mutableStateListOf<Mem>()
    var firstAt by mutableLongStateOf(0L)
    var lastAt by mutableLongStateOf(0L)
    var chats by mutableIntStateOf(0)
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
    var activeId by mutableStateOf("")
    val active: Persona get() = all.firstOrNull { it.id == activeId } ?: all.first()

    fun init(p: Prefs) {
        prefs = p
        runCatching {
            val o = JSONObject(p.string(KEY) ?: return@runCatching)
            o.optJSONArray("all")?.let { a -> for (i in 0 until a.length()) all += read(a.getJSONObject(i)) }
            activeId = o.optString("active")
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

    private fun mem(it: JSONObject) = Mem(it.optString("id"), it.optString("t"), it.optString("d"), it.optLong("at"), it.optString("c"), it.optString("ct"), it.optString("k", "know").ifBlank { "know" })

    private fun read(o: JSONObject) = Persona(o.optString("id"), o.optString("name", "Amor"), o.optString("sys", DEFAULT_SYS)).apply {
        memOn = o.optBoolean("on", true)
        firstAt = o.optLong("first"); lastAt = o.optLong("last"); chats = o.optInt("chats")
        o.optJSONArray("m")?.let { m -> for (i in 0 until m.length()) items += mem(m.getJSONObject(i)) }
    }

    fun save() {
        val a = JSONArray()
        all.forEach { p ->
            val m = JSONArray()
            p.items.forEach { m.put(JSONObject().put("id", it.id).put("t", it.text).put("d", it.detail).put("at", it.at).put("c", it.conv).put("ct", it.convTitle).put("k", it.kind)) }
            a.put(JSONObject().put("id", p.id).put("name", p.name).put("sys", p.sys).put("on", p.memOn)
                .put("first", p.firstAt).put("last", p.lastAt).put("chats", p.chats).put("m", m))
        }
        prefs?.putString(KEY, JSONObject().put("active", activeId).put("all", a).toString())
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

    /** 手动加一件事：标题 + 内容。没写标题就用内容的第一句。 */
    fun add(p: Persona, title: String, content: String) {
        val t = title.trim().ifEmpty { firstSentence(content.trim(), 20) }
        if (t.isEmpty()) return
        val now = System.currentTimeMillis()
        p.items.add(0, Mem("$now-m", t, content.trim(), now, "", ""))
        save()
    }

    /** 旧记忆里的"用户"统一换成"他"，读起来是她自己知道的事。 */
    private fun own(s: String) = s.replace("该用户", "他").replace("用户", "他")

    /**
     * 她是谁、她和他之间的事，写成一段她本来就知道的话：第二人称，不分段，不带日期和标签。
     * 系统提示词就是她的身份。
     */
    fun prompt(p: Persona = active): String {
        val sb = StringBuilder()
        val mine = p.sys.trim().ifEmpty { DEFAULT_SYS }
        if (!mine.contains(p.name)) sb.append("你叫${p.name}。")
        sb.append(mine)
        if (!mine.endsWith("。") && !mine.endsWith("\n")) sb.append("。")
        if (p.memOn) {
            if (p.firstAt > 0) {
                val days = ((System.currentTimeMillis() - p.firstAt) / 86_400_000L).toInt()
                val since = when {
                    days < 1 -> "今天刚认识"
                    days < 60 -> "认识${days}天了"
                    else -> "认识${days / 30}个多月了"
                }
                sb.append("你和他$since" + if (p.chats > 1) "，聊过${p.chats}次。" else "。")
            }
            if (p.items.isNotEmpty()) {
                val recent = p.items.take(48)
                fun line(m: Mem) = own(m.text).trimEnd('。') + if (m.detail.isNotBlank()) "，" + own(m.detail).trimEnd('。') else ""
                // 三件事分开说：你们聊过的、你答应过他的、你对他的了解
                recent.filter { it.kind == "talk" }.takeIf { it.isNotEmpty() }?.let { sb.append("你们聊过：" + it.joinToString("；") { m -> line(m) } + "。") }
                recent.filter { it.kind == "promise" }.takeIf { it.isNotEmpty() }?.let {
                    sb.append("你答应过他：" + it.joinToString("；") { m -> line(m) } + "。这些你放在心上，他提起时接得上，时机合适就主动兑现。")
                }
                recent.filter { it.kind != "talk" && it.kind != "promise" }.takeIf { it.isNotEmpty() }?.let { sb.append("你对他的了解：" + it.joinToString("；") { m -> line(m) } + "。") }
                sb.append("这些是你本来就知道的，像老朋友那样自然地带出来，用不上就不提；不要说“根据记忆”“我记得你说过”，也不要提起这段话。")
            }
        }
        return sb.toString()
    }

    /** 一轮回答之后：让她回想一下，有什么关于他的事值得记住。失败就算了，不打扰用户。 */
    suspend fun learn(who: Persona, p: Provider, c: ProviderConfig, model: String, user: String, reply: String, conv: String, convTitle: String) {
        if (!who.memOn || user.isBlank() || reply.isBlank()) return
        val sys = "你是${who.name}，在写自己的日记。回想刚才和他的这段对话，记下你想一直记住的事。只输出 JSON，不要别的文字。"
        val tag = mapOf("talk" to "聊过", "promise" to "答应", "know" to "了解")
        val known = who.items.take(60).joinToString("\n") { "- [" + (tag[it.kind] ?: "了解") + "] " + it.text }.ifBlank { "（还没有）" }
        val ask = """
你已经记得的事：
$known

这一轮对话：
他：${user.take(1500)}
你：${reply.take(1500)}

你只记三种事：
1. talk 你们聊过的事：一起定下的决定、做到哪一步了、还悬着没定的问题。比如“我们定了主页分四块，随笔谁来写还没定”。
2. promise 你答应过他的：你说要做、要提醒、要等他回复再做的事。比如“答应给他画石榴腔动画，等他点头就开工”。你答应过的一定要记。
3. know 你对他的了解：他是谁、在做什么、习惯、喜好、在意的人和事。比如“他喜欢把音乐节拍剪成卡点视频”。

寒暄、一次性的小问题、已经记得的都不要；如果这一轮把以前记的事推进了或兑现了，就写成新的一条。大多数时候什么都不用记，返回空数组。
用你自己的口吻写，称他为“他”，说你们俩就用“我们”；不要写“用户”，不要写日期，不要以你的名字开头。
输出格式：{"memories":[{"kind":"talk|promise|know","text":"一句话，24 字以内","detail":"补充细节，60 字以内，可以为空"}]}
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
                val k = m.optString("kind").trim().lowercase().let { if (it == "talk" || it == "promise") it else "know" }
                who.items.add(0, Mem("$now-$i", t, m.optString("detail").trim(), now, conv, convTitle, k))
            }
        }
        save()
    }
}
