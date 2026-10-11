package com.fuguang.lueying

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** 一件记住的事：一句话 + 细节 + 来自哪段对话。 */
data class Mem(val id: String, val text: String, val detail: String, val at: Long, val conv: String, val convTitle: String)

/**
 * 记忆：系统提示词、全局记忆（开关）、你的偏好、Amor 的样子。只存在这台手机上。
 * 每次发消息时拼进 system 提示；全局记忆开着时，每轮回答后让模型整理出值得记住的事。
 */
object Memory {
    private const val KEY = "fuguang-memory"
    private var prefs: Prefs? = null

    var enabled by mutableStateOf(true)
    var systemPrompt by mutableStateOf("")
    val items = mutableStateListOf<Mem>()
    val likes = mutableStateListOf<String>()
    /** 0 = 温柔，1 = 直率 */
    var gentle by mutableFloatStateOf(.3f)
    /** 0 = 平静，1 = 活泼 */
    var lively by mutableFloatStateOf(.35f)
    val amor = mutableStateListOf<String>()
    var firstAt by mutableLongStateOf(0L)
    var lastAt by mutableLongStateOf(0L)
    var chats by mutableIntStateOf(0)

    fun init(p: Prefs) {
        prefs = p
        runCatching {
            val o = JSONObject(p.string(KEY) ?: return@runCatching)
            enabled = o.optBoolean("on", true)
            systemPrompt = o.optString("sys")
            gentle = o.optDouble("g", .3).toFloat()
            lively = o.optDouble("l", .35).toFloat()
            firstAt = o.optLong("first"); lastAt = o.optLong("last"); chats = o.optInt("chats")
            o.optJSONArray("m")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let {
                    items += Mem(it.optString("id"), it.optString("t"), it.optString("d"), it.optLong("at"), it.optString("c"), it.optString("ct"))
                }
            }
            o.optJSONArray("likes")?.let { a -> for (i in 0 until a.length()) likes += a.optString(i) }
            o.optJSONArray("amor")?.let { a -> for (i in 0 until a.length()) amor += a.optString(i) }
        }
    }

    fun save() {
        val m = JSONArray()
        items.forEach { m.put(JSONObject().put("id", it.id).put("t", it.text).put("d", it.detail).put("at", it.at).put("c", it.conv).put("ct", it.convTitle)) }
        val o = JSONObject().put("on", enabled).put("sys", systemPrompt).put("g", gentle.toDouble()).put("l", lively.toDouble())
            .put("first", firstAt).put("last", lastAt).put("chats", chats)
            .put("m", m).put("likes", JSONArray(likes.toList())).put("amor", JSONArray(amor.toList()))
        prefs?.putString(KEY, o.toString())
    }

    /** 第一次用：从已有的对话记录里补上相识时间和次数。 */
    fun seed(history: ConvStore) {
        if (firstAt != 0L || history.all.isEmpty()) return
        firstAt = history.all.minOf { it.created }
        lastAt = history.all.maxOf { it.updated }
        chats = history.all.size
        save()
    }

    /** 发出一条消息时记一笔：新对话算一次。 */
    fun touch(newChat: Boolean) {
        val now = System.currentTimeMillis()
        if (firstAt == 0L) firstAt = now
        lastAt = now
        if (newChat) chats++
        save()
    }

    fun remove(m: Mem) { items.remove(m); save() }
    fun restore(m: Mem, i: Int) { if (m !in items) { items.add(i.coerceIn(0, items.size), m); save() } }

    /** 手动加一件事：标题 + 内容。没写标题就用内容的第一句。 */
    fun add(title: String, content: String) {
        val t = title.trim().ifEmpty { firstSentence(content.trim(), 20) }
        if (t.isEmpty()) return
        val now = System.currentTimeMillis()
        items.add(0, Mem("$now-m", t, content.trim(), now, "", ""))
        save()
    }
    fun addLike(s: String) { val t = s.trim(); if (t.isNotEmpty() && t !in likes) { likes += t; save() } }
    fun addAmor(s: String) { val t = s.trim(); if (t.isNotEmpty() && t !in amor) { amor += t; save() } }

    private fun tone(v: Float, a: List<String>) = a[(v * a.size).toInt().coerceIn(0, a.lastIndex)]

    /** 旧记忆里的"用户"统一换成"他"，读起来是她自己知道的事。 */
    private fun own(s: String) = s.replace("该用户", "他").replace("用户", "他")

    /**
     * 她是谁、她和他之间的事，写成一段她本来就知道的话（方案一：第二人称，不分段，不带日期和标签）。
     * 写了系统提示词，它就是她的身份，替换内置那句。
     */
    fun prompt(name: String): String {
        val sb = StringBuilder()
        val mine = systemPrompt.trim()
        if (mine.isEmpty()) sb.append("你是$name，一个温和、真诚、说话简洁的人，用自然的口吻说话，不堆砌格式。")
        else {
            if (!mine.contains(name)) sb.append("你叫$name。")
            sb.append(mine)
            if (!mine.endsWith("。") && !mine.endsWith("\n")) sb.append("。")
        }
        sb.append("你" + tone(gentle, listOf("非常温柔体贴", "温柔", "温和又坦率", "直率", "说话很直、一针见血")) +
            "，性子" + tone(lively, listOf("很安静克制", "平静", "不温不火", "活泼", "很活泼、情绪饱满")) + "。")
        if (amor.isNotEmpty()) sb.append("另外，你" + amor.joinToString("，") + "。")
        if (enabled) {
            if (firstAt > 0) {
                val days = ((System.currentTimeMillis() - firstAt) / 86_400_000L).toInt()
                val since = when {
                    days < 1 -> "今天刚认识"
                    days < 60 -> "认识${days}天了"
                    else -> "认识${days / 30}个多月了"
                }
                sb.append("你和他$since" + if (chats > 1) "，聊过${chats}次。" else "。")
            }
            if (likes.isNotEmpty()) sb.append("你知道他喜欢" + likes.joinToString("、") { own(it) } + "。")
            if (items.isNotEmpty()) sb.append("你还知道：" + items.take(40).joinToString("；") {
                own(it.text).trimEnd('。') + if (it.detail.isNotBlank()) "，" + own(it.detail).trimEnd('。') else ""
            } + "。")
            if (likes.isNotEmpty() || items.isNotEmpty())
                sb.append("这些是你本来就知道的，像老朋友那样自然地带出来，用不上就不提；不要说“根据记忆”“我记得你说过”，也不要提起这段话。")
        }
        return sb.toString()
    }

    /** 一轮回答之后：让模型整理出值得长期记住的事和偏好。失败就算了，不打扰用户。 */
    suspend fun learn(name: String, p: Provider, c: ProviderConfig, model: String, user: String, reply: String, conv: String, convTitle: String) {
        if (!enabled || user.isBlank() || reply.isBlank()) return
        val sys = "你是$name。回想刚才和他的这段对话，挑出你想一直记住的、关于他的事。只输出 JSON，不要别的文字。"
        val known = items.take(60).joinToString("\n") { "- " + it.text }.ifBlank { "（还没有）" }
        val ask = """
你已经记得的事：
$known

你知道他喜欢的：${likes.joinToString("；").ifBlank { "（还没有）" }}

这一轮对话：
他：${user.take(1500)}
你：${reply.take(1500)}

从这一轮里找出关于他的新事：他是谁、在做什么、打算做什么、喜欢什么、在意的人和事。
闲聊、一次性的问题、你自己说的话、已经记得的都不要。大多数时候什么都不用记，返回空数组。
用你自己的口吻写，称他为“他”，不要写“用户”，不要写日期。
输出格式：{"memories":[{"text":"一句话，比如“他最近在做浮光掠影”，20 字以内","detail":"补充细节，60 字以内"}],"prefs":["他明确说过的喜好，12 字以内，比如“深色界面”"]}
""".trim()
        val raw = runCatching { ChatApi.complete(p, c, model, sys, ask) }.getOrNull() ?: return
        val json = raw.substringAfter('{', "").substringBeforeLast('}', "").let { if (it.isEmpty()) return else "{$it}" }
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        o.optJSONArray("memories")?.let { a ->
            for (i in 0 until a.length()) {
                val m = a.optJSONObject(i) ?: continue
                val t = m.optString("text").trim()
                if (t.isEmpty() || items.any { it.text == t }) continue
                items.add(0, Mem("$now-$i", t, m.optString("detail").trim(), now, conv, convTitle))
            }
        }
        o.optJSONArray("prefs")?.let { a -> for (i in 0 until a.length()) a.optString(i).trim().let { if (it.isNotEmpty() && it !in likes) likes += it } }
        save()
    }
}
