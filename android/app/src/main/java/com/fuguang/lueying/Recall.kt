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
            "关键词可以有多个，用空格分开；空字符串返回最近的几段对话。",
        JSONObject().put("type", "object")
            .put("properties", JSONObject().put("query", JSONObject().put("type", "string").put("description", "关键词")))
            .put("required", JSONArray().put("query")),
    )

    fun system(): String =
        "你能翻看你们以前的聊天：他提到以前聊过的事、问你还记不记得，或者你想接上前情时，先用 $TOOL 去找，再像自己想起来一样自然地说，不用提工具。"

    private fun day(t: Long) = SimpleDateFormat("M月d日", Locale.CHINA).format(Date(t))

    /** 搜以前的对话：每段对话里命中的句子，带上前后各一句。 */
    fun run(store: ConvStore, current: String?, query: String): ToolOut {
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val convs = store.all.filter { it.id != current }.sortedByDescending { it.updated }
        if (convs.isEmpty()) return ToolOut("你们以前还没有别的聊天。")
        val out = JSONArray()
        if (words.isEmpty()) {
            convs.take(6).forEach { c ->
                out.put(JSONObject().put("date", day(c.updated)).put("title", c.title)
                    .put("last", c.msgs.takeLast(2).joinToString(" / ") { (if (it.role == "user") "他：" else "你：") + it.text.take(120) }))
            }
        } else {
            for (c in convs) {
                val hits = c.msgs.indices.filter { i -> words.any { w -> c.msgs[i].text.contains(w, ignoreCase = true) } }
                if (hits.isEmpty()) continue
                val lines = hits.take(3).joinToString(" … ") { i ->
                    (maxOf(0, i - 1)..minOf(c.msgs.lastIndex, i + 1)).joinToString(" / ") { k ->
                        (if (c.msgs[k].role == "user") "他：" else "你：") + c.msgs[k].text.replace("\n", " ").take(160)
                    }
                }
                out.put(JSONObject().put("date", day(c.updated)).put("title", c.title).put("found", lines))
                if (out.length() >= 6) break
            }
            if (out.length() == 0) return ToolOut("以前的聊天里没找到「${query.trim()}」。")
        }
        return ToolOut(JSONObject().put("chats", out).toString())
    }

    /** 不支持工具时：最近几段对话的题目和日期。 */
    fun context(store: ConvStore, current: String?): String {
        val convs = store.all.filter { it.id != current }.sortedByDescending { it.updated }.take(6)
        if (convs.isEmpty()) return ""
        return "你们最近还聊过：" + convs.joinToString("；") { day(it.updated) + "「" + it.title + "」" } + "。他提起时接得上就好。"
    }
}
