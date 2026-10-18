package com.fuguang.lueying

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 画板：她用积木一块块搭起来的作品（kind=board）。
 * 原稿是 { tone, blocks }，画法在 assets/board.html；他在上面勾、拖、改字，改完存回原稿，
 * 同时记一句「他改了什么」，她用 read_work 读原稿时能看到。
 * 她自己做的积木（type=own）每位助理各有一份（works/blocks.lib），以后只写名字就能再用。
 */
object Board {
    private var template = ""
    private var lib: File? = null

    fun init(ctx: Context, dir: File) {
        template = runCatching { ctx.assets.open("board.html").bufferedReader().use { it.readText() } }.getOrDefault("")
        lib = File(dir, "blocks.lib")  // 不能叫 .json：作品列表按 .json 找原稿
    }

    /** 给工具说明的一段：她手边有哪些积木。 */
    const val GUIDE = "kind=board：画板，一本能动手的小本子，像 Notion 那样用积木一块块往下搭，放进 board：{tone, blocks}。" +
        "style 是画风，不只是颜色，字、纸面、装饰、动效都跟着变：paper 纸笺（暖纸、宋体、红线小票，勾完盖个「好」字印，适合菜谱、手账、清单）、" +
        "night 夜墨（深色、标题渐变、进度发光，适合计划、日程、目标）、grid 方格（蓝图格子纸、等宽编号、刻度进度，适合装修、旅行、工程、预算）；按内容挑，别总用一种。" +
        "tone 是这块画板的颜色，你自己挑：apricot 杏、sea 海、moss 苔、rose 蔷薇、dusk 暮紫、sand 沙、mist 雾，或者直接写 #rrggbb。" +
        "积木（type）：cover 封面（title、sub、glyph 一个大字）；heading 标题（text、note）；text 段落（text，**加粗**）；callout 提示（mark 一个字、text）；" +
        "quote 引用（text、by）；divider 分隔；todo 待办（items:[{t,done}]）；toggle 折叠（title、note、open、children:[积木]）；" +
        "columns 分栏（cols:[[积木],[积木]]，最多三栏，手机上窄了会叠起来）；table 表格（head、rows，total:true 末列合计）；" +
        "kanban 看板（cols:[{name,cards:[{t,tag}]}]，最后一列算做完）；timeline 时间线（items:[{tm,t,s}]）；" +
        "progress 进度（label；from:kanban 或 todo 自己数，或者写 value）；gallery 卡片墙（cards:[{g 一个字,t,s}]）；" +
        "week 周历（cells：七天各一组短字，today 是今天 0–6）；mindmap 导图（root、kids:[{t,kids:[…]}]，几层都行）；" +
        "own 你自己做的积木（name、html：一小段自包含的 HTML/CSS/JS，背景透明，颜色可用 var(--acc) var(--ink) var(--glass)；起过名字的，以后只写 name 就能再用）。" +
        "搭什么、用哪几块、怎么排、什么画风和颜色，都看你；同一块画板里积木要有轻重：一张封面、几段标题分开，要点放提示，长说明收进折叠，别一路平铺。" +
        "他打开时先是只看：点一下勾掉、展开；要改字点「编辑」或长按。他改过的地方你用 read_work 看得到。"

    val schema: JSONObject get() = JSONObject().put("type", "object").put("description", "kind=board 时：画板原稿")
        .put(
            "properties",
            JSONObject()
                .put("tone", JSONObject().put("type", "string"))
                .put("style", JSONObject().put("type", "string").put("enum", JSONArray(listOf("paper", "night", "grid"))))
                .put("blocks", JSONObject().put("type", "array").put("items", JSONObject().put("type", "object").put("properties", JSONObject().put("type", JSONObject().put("type", "string"))))),
        )

    /** 她交来的原稿：理一理，补上她自己的积木；不能用时返回一句为什么。 */
    fun prepare(pid: String, raw: JSONObject?): Pair<JSONObject?, String?> {
        val blocks = raw?.optJSONArray("blocks") ?: return null to "画板没有内容：board.blocks 是空的，可能写太长被截断了，精简一点再搭一次。"
        if (blocks.length() == 0) return null to "画板没有积木。"
        val missing = ArrayList<String>()
        val mine = library(pid)
        fun walk(a: JSONArray?) {
            if (a == null) return
            for (i in 0 until a.length()) {
                val b = a.optJSONObject(i) ?: continue
                if (b.optString("type") == "own") {
                    val name = b.optString("name").trim()
                    val html = b.optString("html").trim()
                    when {
                        html.isNotEmpty() && name.isNotEmpty() -> mine.put(name, html)
                        html.isEmpty() && mine.has(name) -> b.put("html", mine.optString(name))
                        html.isEmpty() -> missing += name.ifEmpty { "没名字的那块" }
                    }
                }
                walk(b.optJSONArray("children"))
                b.optJSONArray("cols")?.let { cols -> for (j in 0 until cols.length()) walk(cols.optJSONArray(j)) }
            }
        }
        walk(blocks)
        if (missing.isNotEmpty()) return null to "这几块你自己的积木还没做过：${missing.joinToString("、")}。第一次用要连 html 一起写。"
        saveLibrary(pid, mine)
        return JSONObject().put("tone", raw.optString("tone", "apricot")).put("style", raw.optString("style", "night").takeIf { it in setOf("paper", "night", "grid") } ?: "night").put("blocks", blocks) to null
    }

    /** 画成页面：把原稿塞进模板。 */
    fun page(title: String, board: JSONObject): String {
        val data = JSONObject(board.toString()).put("title", title).toString()
            .replace("</", "<\\/").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
        return template.replace("/*BOARD*/null", data)
    }

    /** 他在画板上改过：存回原稿、重画页面（不刷新正开着的那页），记下改了什么。 */
    @Synchronized
    fun save(id: String, json: String, log: String) {
        val o = Works.source(id) ?: return
        val b = runCatching { JSONObject(json) }.getOrNull() ?: return
        val title = b.optString("title").trim().ifEmpty { o.optString("title") }.take(40)
        b.remove("title")
        val edits = o.optJSONArray("edits") ?: JSONArray()
        runCatching { JSONArray(log) }.getOrNull()?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let { edits.put(it.take(120)) } }
        while (edits.length() > 40) edits.remove(0)
        o.put("board", b).put("title", title).put("edits", edits).put("updated", System.currentTimeMillis())
        Works.write(id, o, page(title, b))
    }

    /** 给她读的原稿：积木 + 他改过的地方。读过就清掉，免得下次再说一遍。 */
    fun forRead(id: String, o: JSONObject): String {
        val edits = o.optJSONArray("edits")
        val note = if (edits == null || edits.length() == 0) "" else
            "\n\n他在上面动过（从旧到新）：\n" + (0 until edits.length()).joinToString("\n") { "· " + edits.optString(it) }
        if (note.isNotEmpty()) { o.remove("edits"); Works.write(id, o, null) }
        return (o.optJSONObject("board")?.toString(1).orEmpty()) + note
    }

    /** 她自己做过的积木名字，写进提示词里。 */
    fun names(pid: String): List<String> = library(pid).keys().asSequence().toList()

    private fun all(): JSONObject = runCatching { JSONObject(lib!!.readText()) }.getOrDefault(JSONObject())
    private fun library(pid: String): JSONObject = all().optJSONObject(pid) ?: JSONObject()
    private fun saveLibrary(pid: String, mine: JSONObject) {
        val f = lib ?: return
        runCatching { f.writeText(all().put(pid, mine).toString()) }
    }
}
