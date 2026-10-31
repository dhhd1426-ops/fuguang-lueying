package com.fuguang.lueying

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 跨聊天回忆：开着时，她能翻看你们以前的对话。
 * 第 363 版（原型 https://claude.ai/artifact/Q5vBKuiQJYH8eZzCEEND5b 第 3 版）分三步：
 * 1. 你每说一句，App 拿这句里的词去比所有聊天的标题和摘要（[match]，在手机上比，不调模型），对上的一到三段把摘要放到她眼前，
 *    时间线上记一步「想起之前聊过的」（[AUTO]）；
 * 2. 她看摘要就知道是哪段、聊过什么；
 * 3. 不够细就用 [OPEN] 打开那段原话（时间线「正在回忆」→「回忆起那段聊天」）。
 * 她自己想找时还能用 [TOOL] 按关键词搜。
 */
object Recall {
    const val TOOL = "recall_chats"
    const val OPEN = "open_chat"
    /** 时间线上「想起之前聊过的」那一步（不是工具，是 App 自己对上的）。 */
    const val AUTO = "recall_auto"

    val spec = ToolSpec(
        TOOL,
        "按关键词搜你和他以前的聊天（不含这一段）。每段带编号（id）、小结（gist，那段聊了什么、细节都在里面）和原话里命中的句子。" +
            "他说起以前的事、而眼前没有对上的聊天时用。关键词可以有多个，用空格分开，宁可短（「蓝色 衣服」比「蓝色衣服品牌」好找）；空字符串返回最近的几段。",
        JSONObject().put("type", "object")
            .put("properties", JSONObject().put("query", JSONObject().put("type", "string").put("description", "关键词")))
            .put("required", JSONArray().put("query")),
    )

    val openSpec = ToolSpec(
        OPEN,
        "打开以前的一段聊天，看原话。小结不够细、要核对你们当时具体怎么说的时候用。id 是那段聊天的编号；" +
            "query 写要找的词（比如「蓝色」），会从说到它的地方前后取一段，不写就取最后一段。",
        JSONObject().put("type", "object")
            .put("properties", JSONObject()
                .put("id", JSONObject().put("type", "string").put("description", "那段聊天的编号"))
                .put("query", JSONObject().put("type", "string").put("description", "要找的词，可以不写")))
            .put("required", JSONArray().put("id")),
    )

    fun system(): String =
        "你们以前的聊天也在手边，每段都有小结。他这句话和以前哪段对得上时，App 会把那段的编号和小结放在他这句话前面（「你想起以前聊过的」）；" +
            "看小结够答就直接答，要核对原话用 $OPEN 打开那段。眼前没有、他又说起以前的事，用 $TOOL 按关键词搜。" +
            "和这句话无关的就当没想起，不要主动提。想起来了就像自己记得一样自然地说；找不到就照实说记不清，不编。"

    private fun day(t: Long) = SimpleDateFormat("M月d日", Locale.CHINA).format(Date(t))
    private fun who(role: String) = if (role == "user") Memory.userCall else "你"

    /* ---------- 1. 自动对上 ---------- */

    /** 对上的一段：哪段、他这句话里哪些词对上了。 */
    class Hit(val c: Conv, val words: List<String>, val score: Int)

    /** 太常见、对不出什么的两字词。 */
    private val STOP = setOf(
        "姐姐", "什么", "之前", "那个", "这个", "我们", "你们", "他们", "一下", "是不", "不是", "怎么", "来着", "就是", "还是", "可以", "没有",
        "知道", "觉得", "现在", "今天", "时候", "一个", "这样", "那样", "然后", "因为", "所以", "但是", "如果", "还有", "已经", "你说", "我说",
        "说的", "过的", "了吗", "是什", "么呀", "的那", "那件", "这件", "上次", "以前", "记得", "你还", "我想", "想要", "一点", "有点", "好的",
        "你好", "谢谢", "晚安", "早安", "喜欢", "真的", "其实", "宝宝", "你的", "我的", "的是", "是我", "是你", "了吧", "吗？",
    )

    /** 他这句话里的词：中文按两个字一组滑过去，英文数字按整词；记下每组在句子里的位置。 */
    private fun grams(q: String): List<Pair<String, Int>> {
        val out = mutableListOf<Pair<String, Int>>()
        Regex("[\\u4e00-\\u9fff]{2,}").findAll(q).forEach { m ->
            val s = m.value
            for (i in 0 until s.length - 1) { val g = s.substring(i, i + 2); if (g !in STOP) out += g to (m.range.first + i) }
        }
        Regex("[A-Za-z0-9]{2,}").findAll(q).forEach { m -> out += m.value.lowercase() to m.range.first }
        return out.distinctBy { it.first }
    }

    /** 对上的几组拼回词：相邻、重叠的两字组接成一个词（「蓝色」「衣服」）。 */
    private fun words(q: String, hits: List<Pair<String, Int>>): List<String> {
        val spans = hits.map { (g, at) -> at to at + g.length }.sortedBy { it.first }
        val merged = mutableListOf<IntArray>()
        spans.forEach { (a, b) -> val last = merged.lastOrNull(); if (last != null && a <= last[1]) last[1] = maxOf(last[1], b) else merged += intArrayOf(a, b) }
        return merged.map { q.substring(it[0], it[1].coerceAtMost(q.length)) }.distinct().take(4)
    }

    /** 拿他这句话去比这位助理以前的每段聊天：标题、小结里有算 2 分，只在原话里有算 1 分；至少两个词、3 分才算对上，最多三段。 */
    fun match(store: ConvStore, current: String?, question: String): List<Hit> {
        val q = question.trim().take(200)
        val gs = grams(q)
        if (gs.size < 2) return emptyList()
        val convs = store.of().filter { it.id != current && it.msgs.any { m -> m.role == "user" } }
        if (convs.isEmpty()) return emptyList()
        val hay = convs.associate { c -> c.id to (c.title + "\n" + c.gist).lowercase() }
        val body = convs.associate { c -> c.id to c.msgs.joinToString("\n") { it.text }.takeLast(30000).lowercase() }
        // 一半以上的聊天里都有的词，说明太常见，不算
        val common = if (convs.size >= 6) gs.map { it.first }.filter { g -> convs.count { c -> body[c.id]!!.contains(g) || hay[c.id]!!.contains(g) } * 2 > convs.size }.toSet() else emptySet()
        val use = gs.filter { it.first !in common }
        return convs.mapNotNull { c ->
            var score = 0
            val got = use.filter { (g, _) ->
                when { hay[c.id]!!.contains(g) -> { score += 2; true }; body[c.id]!!.contains(g) -> { score += 1; true }; else -> false }
            }
            if (got.size >= 2 && score >= 3) Hit(c, words(q, got), score) else null
        }.sortedWith(compareByDescending<Hit> { it.score }.thenByDescending { it.c.updated }).take(3)
    }

    /** 放在他这句话前面的那段旁白。 */
    fun note(hits: List<Hit>): String {
        if (hits.isEmpty()) return ""
        val sb = StringBuilder("（你想起以前聊过的、可能和他这句话有关的聊天：")
        hits.forEach { h ->
            sb.append("\n[编号 ").append(h.c.id).append("] ").append(day(h.c.updated)).append("「").append(h.c.title).append("」")
            if (h.c.gist.isNotBlank()) sb.append("\n小结：").append(h.c.gist.take(900))
            else sb.append("\n（这段还没有小结）原话里说到的：").append(snippets(h.c, h.words))
        }
        sb.append("\n看小结够答就直接答；要核对原话用 $OPEN 打开那段（填编号和要找的词）。和他这句话无关就当没想起，不要提。）")
        return sb.toString()
    }

    /** 时间线上那一步展开后的内容：第一行「『蓝色』『衣服』→ 10月28日『换季的外套』」，下面是小结。 */
    fun step(hits: List<Hit>): ToolStep {
        val words = hits.flatMap { it.words }.distinct().take(4).joinToString("") { "「$it」" }
        val head = words + " → " + hits.joinToString("、") { day(it.c.updated) + "「" + it.c.title + "」" }
        val detail = head + hits.joinToString("") { h -> "\n\n" + (if (hits.size > 1) "「${h.c.title}」\n" else "") + h.c.gist.ifBlank { "（还没有小结，按原话对上的）" } }
        return ToolStep(0, AUTO, "想起之前聊过的", detail, done = true)
    }

    private fun snippets(c: Conv, words: List<String>): String {
        val hits = c.msgs.indices.filter { i -> words.any { w -> c.msgs[i].text.contains(w, ignoreCase = true) } }
        return hits.take(3).joinToString(" … ") { i -> who(c.msgs[i].role) + "：" + c.msgs[i].text.replace("\n", " ").take(200) }
    }

    /* ---------- 3. 打开原话 ---------- */

    /** 刚打开的那段：时间线上展开看的内容（「换季的外套 · 第 3–8 句」+ 原话）。 */
    var lastOpen = ""
        private set

    fun open(store: ConvStore, a: JSONObject): ToolOut {
        lastOpen = ""
        val id = a.optString("id").trim().removePrefix("编号").trim().trim('[', ']')
        val c = store.get(id)?.takeIf { it.who == Memory.activeId } ?: return ToolOut("没有编号是 $id 的聊天。")
        val q = a.optString("query").trim()
        val words = q.split(Regex("\\s+")).filter { it.isNotBlank() }
        val at = c.msgs.indices.firstOrNull { i -> words.any { w -> c.msgs[i].text.contains(w, ignoreCase = true) } }
        val from = if (at == null) maxOf(0, c.msgs.size - 30) else maxOf(0, at - 6)
        val to = minOf(c.msgs.size, from + 30)
        val lines = (from until to).filter { c.msgs[it].text.isNotBlank() }.map { i -> "第 ${i + 1} 句 " + who(c.msgs[i].role) + "：" + c.msgs[i].text.take(800) }
        if (c.gist.isBlank()) Reflect.digestNow(c.id)
        lastOpen = "${c.title} · 第 ${from + 1}–$to 句" + (if (at == null && words.isNotEmpty()) "（没找到「$q」，看的是最后一段）" else "") +
            "\n\n" + (from until to).filter { c.msgs[it].text.isNotBlank() }.joinToString("\n") { i -> (if (c.msgs[i].role == "user") Memory.userCall else (Memory.get(c.who)?.name ?: "她")) + "：" + c.msgs[i].text.replace("\n", " ").take(300) }
        return ToolOut(
            "${day(c.updated)}「${c.title}」第 ${from + 1}–$to 句（共 ${c.msgs.size} 句）" + (if (at == null && words.isNotEmpty()) "，原话里没找到「$q」，给的是最后一段" else "") + "：\n" + lines.joinToString("\n"),
        )
    }

    /* ---------- 她自己搜 ---------- */

    /** 搜以前的对话：每段对话里命中的句子，带上前后各一句。 */
    fun run(store: ConvStore, current: String?, query: String): ToolOut {
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val convs = store.of().filter { it.id != current }.sortedByDescending { it.updated }
        if (convs.isEmpty()) return ToolOut("你们以前还没有别的聊天。")
        val out = JSONArray()
        if (words.isEmpty()) {
            convs.take(6).forEach { c ->
                out.put(JSONObject().put("id", c.id).put("date", day(c.updated)).put("title", c.title)
                    .apply { if (c.gist.isNotBlank()) put("gist", c.gist) }
                    .put("last", c.msgs.takeLast(2).joinToString(" / ") { who(it.role) + "：" + it.text.take(120) }))
            }
        } else {
            for (c in convs) {
                val hits = c.msgs.indices.filter { i -> words.any { w -> c.msgs[i].text.contains(w, ignoreCase = true) } }
                // 小结、标题里有也算：原话里的说法和他现在问的不一样时，小结常常能接上
                val inGist = words.any { w -> c.gist.contains(w, ignoreCase = true) || c.title.contains(w, ignoreCase = true) }
                if (hits.isEmpty() && !inGist) continue
                val lines = hits.take(3).joinToString(" … ") { i ->
                    (maxOf(0, i - 1)..minOf(c.msgs.lastIndex, i + 1)).joinToString(" / ") { k ->
                        who(c.msgs[k].role) + "：" + c.msgs[k].text.replace("\n", " ").take(160)
                    }
                }
                out.put(JSONObject().put("id", c.id).put("date", day(c.updated)).put("title", c.title)
                    .apply { if (c.gist.isNotBlank()) put("gist", c.gist); if (lines.isNotEmpty()) put("found", lines) })
                if (out.length() >= 6) break
            }
            if (out.length() == 0) return ToolOut("以前的聊天里没找到「${query.trim()}」。")
        }
        // 翻到的几段还没有小结：后台补上，下次就有（第 361 版）
        val ids = (0 until out.length()).mapNotNull { out.optJSONObject(it)?.optString("id") }.toSet()
        convs.filter { it.id in ids && it.gist.isBlank() }.take(3).forEach { Reflect.digestNow(it.id) }
        return ToolOut(JSONObject().put("chats", out).toString())
    }

    /** 不支持工具时：最近几段对话的题目和日期。 */
    fun context(store: ConvStore, current: String?): String {
        val convs = store.of().filter { it.id != current }.sortedByDescending { it.updated }.take(6)
        if (convs.isEmpty()) return ""
        return "你们最近还聊过：" + convs.joinToString("；") { day(it.updated) + "「" + it.title + "」" } + "。他提起时接得上就好，不用主动提。"
    }
}
