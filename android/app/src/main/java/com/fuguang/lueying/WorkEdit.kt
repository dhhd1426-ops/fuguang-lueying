package com.fuguang.lueying

import org.json.JSONArray
import org.json.JSONObject

/**
 * 改作品的一小段：照 Claude Code 的 Edit / MultiEdit，在原稿上找一段字换成另一段，不用整份重写。
 * 原稿就是 read_work 给她看的那份文字（网页的 HTML、文档的 Markdown、幻灯片和画板的 JSON），
 * 所以她照着读到的原文抄 old 就能对上。几处改动要么全成，要么一处都不动。
 * 换好后交回 make_work 的那条路重画页面、存素材，作品卡和 id 都不变。
 */
object WorkEdit {
    const val TOOL = "edit_work"

    val spec = ToolSpec(
        TOOL,
        "在一件作品原稿上改一小段，不用整份重写：改字、换颜色、调大小间距、换一张图、加删一小块都用它。" +
            "先用 read_work 看原稿，old 照原稿一字不差地抄（空格、换行、引号都要一样），抄够前后几个字让它在原稿里只出现一次；new 是换上去的那段，删掉就写空。" +
            "几处改动放进同一个 edits 里一次改完，按顺序套用；有一处对不上，整次都不改，会告诉你哪一处。" +
            "幻灯片和画板的原稿是 JSON，改完要还是合法的 JSON。改动超过一半、换了整个走法，才用 make_work 带上 id 整份重写。",
        JSONObject().put("type", "object").put(
            "properties",
            JSONObject()
                .put("id", JSONObject().put("type", "string").put("description", "要改的作品 id"))
                .put(
                    "edits",
                    JSONObject().put("type", "array").put("description", "这次的几处改动，按顺序套用").put(
                        "items",
                        JSONObject().put("type", "object").put(
                            "properties",
                            JSONObject()
                                .put("old", JSONObject().put("type", "string").put("description", "原稿里要换掉的那段，一字不差"))
                                .put("new", JSONObject().put("type", "string").put("description", "换上去的那段"))
                                .put("all", JSONObject().put("type", "boolean").put("description", "true：原稿里每一处都换（比如一个颜色值）；不填只换唯一的那一处")),
                        ).put("required", JSONArray(listOf("old", "new"))),
                    ),
                )
                .put("title", JSONObject().put("type", "string").put("description", "要改作品名才填")),
        ).put("required", JSONArray(listOf("id", "edits"))),
    )

    /** 原稿文字：和 read_work 给她看的一样（画板不带「他动过」那段）。 */
    fun body(o: JSONObject): String = when (o.optString("kind")) {
        "board" -> o.optJSONObject("board")?.toString(1).orEmpty()
        "slides" -> o.optJSONArray("slides")?.toString(1).orEmpty()
        "doc" -> o.optString("markdown")
        else -> o.optString("html")
    }

    class Result(val args: JSONObject?, val text: String, val count: Int = 0)

    /** 把改动套到原稿上，成了给出交给 make_work 的参数，没成给出原因（原稿不动）。 */
    fun apply(o: JSONObject, a: JSONObject): Result {
        val edits = a.optJSONArray("edits")
        if (edits == null || edits.length() == 0) return Result(null, "没有改动：edits 是空的。")
        var s = body(o)
        if (s.isEmpty()) return Result(null, "这件作品没有原稿可改，用 make_work 带上 id 重做一次。")
        var n = 0
        for (i in 0 until edits.length()) {
            val e = edits.optJSONObject(i) ?: return Result(null, "第 ${i + 1} 处写得不对，要有 old 和 new。")
            val old = e.optString("old")
            val new = e.optString("new")
            val k = "第 ${i + 1} 处"
            if (old.isEmpty()) return Result(null, "$k 的 old 是空的。没有改任何地方。")
            if (old == new) return Result(null, "$k 的 old 和 new 一样。没有改任何地方。")
            val hits = count(s, old)
            if (hits == 0) return Result(null, "$k 在原稿里没找到（${clip(old)}）。可能和原稿差了空格、换行或引号，先用 read_work 再看一遍照原文抄。没有改任何地方。")
            if (hits > 1 && !e.optBoolean("all")) return Result(null, "$k 在原稿里出现了 $hits 次（${clip(old)}），多抄几个前后的字让它只出现一次；要每处都换就加 all=true。没有改任何地方。")
            s = if (e.optBoolean("all")) s.replace(old, new) else s.replaceFirst(old, new)
            n += if (e.optBoolean("all")) hits else 1
        }
        val kind = o.optString("kind", "page")
        val title = a.optString("title").trim().ifEmpty { o.optString("title") }
        val args = JSONObject().put("id", a.optString("id").trim()).put("title", title).put("kind", kind)
        when (kind) {
            "board" -> args.put("board", runCatching { JSONObject(s) }.getOrNull() ?: return Result(null, "改完的画板不是合法的 JSON 了（多了或少了逗号、引号、括号）。没有改任何地方。"))
            "slides" -> args.put("slides", runCatching { JSONArray(s) }.getOrNull() ?: return Result(null, "改完的幻灯片不是合法的 JSON 了（多了或少了逗号、引号、括号）。没有改任何地方。"))
            "doc" -> args.put("markdown", s)
            else -> args.put("html", s)
        }
        return Result(args, "改了 $n 处", n)
    }

    private fun count(s: String, sub: String): Int {
        var c = 0; var at = s.indexOf(sub)
        while (at >= 0) { c++; at = s.indexOf(sub, at + sub.length) }
        return c
    }

    private fun clip(t: String) = t.replace('\n', '⏎').let { if (it.length > 40) it.take(40) + "…" else it }
}
