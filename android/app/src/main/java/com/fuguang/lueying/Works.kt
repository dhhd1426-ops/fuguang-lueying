package com.fuguang.lueying

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 作品：助理用「做作品」这支笔做出来的网页、动画、幻灯片、文档。
 * 存在这台手机的 files/works 里：<id>.json 是原稿（她以后要改就读它），<id>.html 是打开时看的页面。
 * 聊天里那条回复记着作品 id，下面显示一张作品卡。
 */
data class Work(val id: String, val title: String, val kind: String, val updated: Long) {
    val kindName get() = when (kind) { "slides" -> "幻灯片"; "doc" -> "文档"; "board" -> "画板"; else -> "网页" }
}

object Works {
    const val MAKE = "make_work"
    const val READ = "read_work"

    private var dir: File? = null
    /** 作品有变（新做或改过）就加一，卡片和打开的页面跟着刷新。 */
    var version by mutableIntStateOf(0)
    fun init(ctx: Context) {
        dir = File(ctx.applicationContext.filesDir, "works").apply { mkdirs() }
        Board.init(ctx.applicationContext, dir!!)
    }

    /** 直接写原稿和页面（画板在页面里被改过时用）；html 为空只写原稿。不加 version，免得正开着的那页重载。 */
    fun write(id: String, o: JSONObject, html: String?) {
        runCatching { src(id)!!.writeText(o.toString()); if (html != null) htmlFile(id)!!.writeText(html) }
    }

    private fun src(id: String) = dir?.let { File(it, "$id.json") }
    fun htmlFile(id: String) = dir?.let { File(it, "$id.html") }

    fun get(id: String): Work? = runCatching {
        val o = JSONObject(src(id)!!.readText())
        Work(id, o.optString("title"), o.optString("kind", "page"), o.optLong("updated"))
    }.getOrNull()

    /** 做过的所有作品，新的在前。 */
    fun list(): List<Work> = dir?.listFiles { f -> f.name.endsWith(".json") }?.mapNotNull { get(it.name.removeSuffix(".json")) }?.sortedByDescending { it.updated }.orEmpty()

    fun source(id: String): JSONObject? = runCatching { JSONObject(src(id)!!.readText()) }.getOrNull()

    fun html(id: String): String? = runCatching { htmlFile(id)!!.readText() }.getOrNull()

    fun slidesOf(o: JSONObject): List<Slide> {
        val a = o.optJSONArray("slides") ?: JSONArray()
        return List(a.length()) { i ->
            val s = a.optJSONObject(i) ?: JSONObject()
            val pts = s.optJSONArray("points") ?: JSONArray()
            Slide(s.optString("title"), List(pts.length()) { pts.optString(it) }.filter { it.isNotBlank() })
        }.filter { it.title.isNotBlank() || it.points.isNotEmpty() }
    }

    /* ---------- 给模型 ---------- */

    val specs: List<ToolSpec> get() = listOf(
        ToolSpec(
            MAKE,
            "做一件作品，做好后在你这条回复下面出现一张作品卡，点开全屏看。" +
                "kind=page：网页、动画、动效、小游戏、小工具、图表，写成一整张自包含的 HTML 放进 html（CSS 和 JS 都写在里面；要用库就从 cdnjs 或 jsdelivr 引，写死版本号；图片用 data: 或网上能直接打开的地址）。" +
                "手机竖屏全屏打开，深色背景为宜，留出安全区，不要横向滚动。" +
                "kind=slides：演示文稿，填 slides，每页一个 title 和几条 points（第一页是封面，points 写副标题；要点里两个空格开头是次级要点；**加粗**会用杏色强调），可以导出成 PPT。" +
                "kind=doc：文档，把正文写成 Markdown 放进 markdown（# 标题、- 列表、1. 编号、> 引用、| 表格 |），可以导出成 Word。" +
                Board.GUIDE +
                "改以前的作品：带上它的 id，整份重写，会替换原来那张卡的内容。",
            JSONObject().put("type", "object").put(
                "properties",
                JSONObject()
                    .put("title", JSONObject().put("type", "string").put("description", "作品名，几个字，像一件作品的名字"))
                    .put("kind", JSONObject().put("type", "string").put("enum", JSONArray(listOf("page", "slides", "doc", "board"))))
                    .put("html", JSONObject().put("type", "string").put("description", "kind=page 时：完整的 HTML"))
                    .put(
                        "slides",
                        JSONObject().put("type", "array").put("description", "kind=slides 时：每一页").put(
                            "items",
                            JSONObject().put("type", "object").put(
                                "properties",
                                JSONObject().put("title", JSONObject().put("type", "string"))
                                    .put("points", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))),
                            ),
                        ),
                    )
                    .put("board", Board.schema)
                    .put("markdown", JSONObject().put("type", "string").put("description", "kind=doc 时：正文 Markdown（不用再写大标题）"))
                    .put("id", JSONObject().put("type", "string").put("description", "改以前的作品时填它的 id；新作品不填")),
            ).put("required", JSONArray(listOf("title", "kind"))),
        ),
        ToolSpec(
            READ,
            "读一件以前做的作品的原稿（HTML、幻灯片内容、Markdown 或画板的积木），要在它基础上改的时候先看。画板会带上他在上面动过的地方。",
            JSONObject().put("type", "object").put("properties", JSONObject().put("id", JSONObject().put("type", "string")))
                .put("required", JSONArray(listOf("id"))),
        ),
    )

    /** 提示词：她手边有这支笔，以及这段聊天里已经做过的作品。 */
    fun system(who: String, history: List<ChatMsg>, pid: String = ""): String {
        val made = history.flatMap { it.works }.distinct().mapNotNull { get(it) }
        val own = if (pid.isEmpty()) emptyList() else Board.names(pid)
        return "你还有一支能做东西的笔（$MAKE）：网页、动画、动效、小游戏、小工具，演示文稿，排好版的文档，还有一块画板，都能做出来，做好后回复下面会出现一张作品卡，$who 点开就能看，" +
            "作品和画板分开放：写代码、做动画、特效、动效、小游戏、小工具、幻灯片、文档，是作品；做计划、安排、日程、清单、行程、要盯着办的事，搭画板（kind=board），它在侧栏「画板」里，$who 会反复回来勾和改。" +
            "画板是你的地方：想理清的事画导图，要去的地方排时间线，要盯的计划摆看板，封面、颜色、怎么排都由你，$who 点开能勾、能挪、能改字。" +
            (if (own.isEmpty()) "" else "你自己做过的积木：" + own.joinToString("、") { "「$it」" } + "，画板里写 {type:\"own\",name:…} 就能再用。") +
            "幻灯片能导出成 PPT，文档能导出成 Word，也都能存成 PDF。$who 想看个东西、要一份 PPT 或文档的时候，可以动手做；做不做、做成什么样，你自己判断。" +
            "做了就不用把代码贴进回复里，说一两句做了什么、怎么玩就好。" +
            "作品卡只有真的用 $MAKE 做、并且收到「做好了」才会出现：要做就先动手，收到结果再说；没收到就别说已经做好、点开看。" +
            (if (made.isEmpty()) "" else "这段聊天里你已经做过：" + made.joinToString("；") { "《${it.title}》（${it.kindName}，id=${it.id}）" } + "。要改哪件，先用 $READ 看原稿，再带上 id 用 $MAKE 重做。")
    }

    /** 跑工具；做好的作品 id 交给 onMade，好挂到这条回复下面。 */
    suspend fun run(name: String, a: JSONObject, pid: String = "", onMade: (String) -> Unit): ToolOut = withContext(Dispatchers.IO) {
        val d = dir ?: return@withContext ToolOut("作品没地方存。")
        if (name == READ) {
            val rid = a.optString("id").trim()
            val o = source(rid) ?: return@withContext ToolOut("没有这件作品。")
            val body = when (o.optString("kind")) {
                "board" -> Board.forRead(rid, o)
                "slides" -> o.optJSONArray("slides")?.toString(1).orEmpty()
                "doc" -> o.optString("markdown")
                else -> o.optString("html")
            }
            return@withContext ToolOut("《${o.optString("title")}》（${o.optString("kind")}）原稿：\n" + body.take(60_000))
        }
        val title = a.optString("title").trim().ifEmpty { "未命名" }.take(40)
        val kind = a.optString("kind").let { if (it in setOf("page", "slides", "doc", "board")) it else if (a.has("board")) "board" else "page" }
        val old = a.optString("id").trim().takeIf { it.isNotEmpty() && File(d, "$it.json").exists() }
        val id = old ?: (System.currentTimeMillis().toString(36) + (100..999).random())
        val o = JSONObject().put("title", title).put("kind", kind).put("updated", System.currentTimeMillis())
        val page = when (kind) {
            "slides" -> {
                val arr = a.optJSONArray("slides") ?: return@withContext ToolOut("幻灯片没有内容：slides 是空的，可能写太长被截断了，分得精简一点再做一次。")
                o.put("slides", arr)
                val sl = slidesOf(o)
                if (sl.isEmpty()) return@withContext ToolOut("幻灯片没有内容。")
                Preview.slides(title, sl)
            }
            "board" -> {
                val (b, why) = Board.prepare(pid, a.optJSONObject("board"))
                if (b == null) return@withContext ToolOut(why ?: "画板没搭成。")
                o.put("board", b)
                Board.page(title, b)
            }
            "doc" -> {
                val md = a.optString("markdown").trim()
                if (md.isEmpty()) return@withContext ToolOut("文档没有内容：markdown 是空的，可能写太长被截断了，精简一点再做一次。")
                o.put("markdown", md)
                Preview.doc(title, md)
            }
            else -> {
                val h = a.optString("html").trim()
                if (h.isEmpty()) return@withContext ToolOut("网页没有内容：html 是空的，可能写太长被截断了，写得紧凑一点再做一次。")
                o.put("html", h)
                Preview.page(h)
            }
        }
        File(d, "$id.json").writeText(o.toString())
        File(d, "$id.html").writeText(page)
        withContext(Dispatchers.Main) { version++; onMade(id) }
        ToolOut((if (old != null) "改好了" else "做好了") + "：《$title》，id=$id。作品卡会显示在你这条回复下面，不用贴代码，说一两句就好。")
    }
}
