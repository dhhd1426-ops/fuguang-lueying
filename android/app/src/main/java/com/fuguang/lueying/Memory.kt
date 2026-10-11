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

    /** 拼进 system 提示的部分。 */
    fun prompt(): String {
        val out = mutableListOf<String>()
        systemPrompt.trim().takeIf { it.isNotEmpty() }?.let { out += it }
        val style = "说话风格：" + tone(gentle, listOf("非常温柔体贴", "温柔", "温和而坦率", "直率", "非常直接、一针见血")) +
            "，" + tone(lively, listOf("很平静克制", "平静", "情绪适中", "活泼", "很活泼、情绪饱满")) + "。"
        out += style + if (amor.isNotEmpty()) "另外：" + amor.joinToString("；") + "。" else ""
        if (likes.isNotEmpty()) out += "用户的偏好：" + likes.joinToString("；") + "。"
        if (enabled && items.isNotEmpty()) {
            val fmt = java.text.SimpleDateFormat("M月d日", java.util.Locale.CHINA)
            out += "你记得关于用户的这些事（从新到旧），自然地用上，不用每次都提起：\n" +
                items.take(40).joinToString("\n") { "- " + fmt.format(java.util.Date(it.at)) + "：" + it.text + if (it.detail.isNotBlank()) "（" + it.detail + "）" else "" }
        }
        return out.joinToString("\n")
    }

    /** 一轮回答之后：让模型整理出值得长期记住的事和偏好。失败就算了，不打扰用户。 */
    suspend fun learn(p: Provider, c: ProviderConfig, model: String, user: String, reply: String, conv: String, convTitle: String) {
        if (!enabled || user.isBlank() || reply.isBlank()) return
        val sys = "你在帮一位 AI 助理整理它对用户的长期记忆。只输出 JSON，不要别的文字。"
        val known = items.take(60).joinToString("\n") { "- " + it.text }.ifBlank { "（还没有）" }
        val ask = """
已经记住的事：
$known

已知的偏好：${likes.joinToString("；").ifBlank { "（还没有）" }}

这一轮对话：
用户：${user.take(1500)}
助理：${reply.take(1500)}

从这一轮里找出值得长期记住的、关于用户本人的新信息：身份、在做的事、计划、喜好、重要的人和事。
闲聊、一次性的问题、助理自己说的话、已经记住的内容都不要。大多数时候什么都不用记，返回空数组。
输出格式：{"memories":[{"text":"一句话，20 字以内","detail":"补充细节，60 字以内"}],"prefs":["用户明确表达的偏好，12 字以内"]}
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
