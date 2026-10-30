package com.fuguang.lueying

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 跨聊天回忆：开着时，她能翻看你们以前的对话。
 * 支持工具的模型给一个 recall_chats 工具；不支持的，就把最近几段对话的题目放进提示里。
 */
object Recall {
    const val TOOL = "recall_chats"

    val spec = ToolSpec(
        TOOL,
        "翻看你和他以前的聊天记录（不含这一段）。他提到以前聊过的事、问你还记不记得、或者你想接上前情时使用。" +
            "每段带着小结（gist，那段聊了什么、细节都在里面）和原话里命中的句子；先看小结找到是哪段，原话核对细节。" +
            "关键词可以有多个，用空格分开，宁可短（「蓝色 衣服」比「蓝色衣服品牌」好找）；空字符串返回最近的几段对话。",
        JSONObject().put("type", "object")
            .put("properties", JSONObject().put("query", JSONObject().put("type", "string").put("description", "关键词")))
            .put("required", JSONArray().put("query")),
    )

    fun system(): String =
        "你们以前的聊天也在手边，用 $TOOL 能翻，每段都有小结。要不要翻由你判断：他提起以前的事、问你还记不记得、或者你自己想接上前情时再翻；" +
            "平常聊天不用翻。想起来了就像自己记得一样自然地说；翻了也没找到，就照实说记不清，不编。"

    private fun day(t: Long) = SimpleDateFormat("M月d日", Locale.CHINA).format(Date(t))

    /** 搜以前的对话：每段对话里命中的句子，带上前后各一句。 */
    fun run(store: ConvStore, current: String?, query: String): ToolOut {
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val convs = store.of().filter { it.id != current }.sortedByDescending { it.updated }
        if (convs.isEmpty()) return ToolOut("你们以前还没有别的聊天。")
        val out = JSONArray()
        if (words.isEmpty()) {
            convs.take(6).forEach { c ->
                out.put(JSONObject().put("date", day(c.updated)).put("title", c.title)
                    .apply { if (c.gist.isNotBlank()) put("gist", c.gist) }
                    .put("last", c.msgs.takeLast(2).joinToString(" / ") { (if (it.role == "user") "他：" else "你：") + it.text.take(120) }))
            }
        } else {
            for (c in convs) {
                val hits = c.msgs.indices.filter { i -> words.any { w -> c.msgs[i].text.contains(w, ignoreCase = true) } }
                // 小结、标题里有也算：原话里的说法和他现在问的不一样时，小结常常能接上
                val inGist = words.any { w -> c.gist.contains(w, ignoreCase = true) || c.title.contains(w, ignoreCase = true) }
                if (hits.isEmpty() && !inGist) continue
                val lines = hits.take(3).joinToString(" … ") { i ->
                    (maxOf(0, i - 1)..minOf(c.msgs.lastIndex, i + 1)).joinToString(" / ") { k ->
                        (if (c.msgs[k].role == "user") "他：" else "你：") + c.msgs[k].text.replace("\n", " ").take(160)
                    }
                }
                out.put(JSONObject().put("date", day(c.updated)).put("title", c.title)
                    .apply { if (c.gist.isNotBlank()) put("gist", c.gist); if (lines.isNotEmpty()) put("found", lines) })
                if (out.length() >= 6) break
            }
            if (out.length() == 0) return ToolOut("以前的聊天里没找到「${query.trim()}」。")
        }
        // 翻到的几段还没有小结：后台补上，下次就有（第 361 版）
        (if (words.isEmpty()) convs.take(6) else convs.filter { c -> (0 until out.length()).any { out.optJSONObject(it)?.optString("title") == c.title } })
            .filter { it.gist.isBlank() }.take(3).forEach { Reflect.digestNow(it.id) }
        return ToolOut(JSONObject().put("chats", out).toString())
    }

    /** 不支持工具时：最近几段对话的题目和日期。 */
    fun context(store: ConvStore, current: String?): String {
        val convs = store.of().filter { it.id != current }.sortedByDescending { it.updated }.take(6)
        if (convs.isEmpty()) return ""
        return "你们最近还聊过：" + convs.joinToString("；") { day(it.updated) + "「" + it.title + "」" } + "。他提起时接得上就好，不用主动提。"
    }
}
