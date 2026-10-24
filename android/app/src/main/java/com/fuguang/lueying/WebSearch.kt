package com.fuguang.lueying

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/** 一家搜索服务。opt：这家自己的选项（显示名 + 发出去的值）。free：不要密钥。needUrl：要自己填地址（SearXNG）。 */
data class SearchSource(
    val id: String, val name: String, val sub: String, val letter: String, val tint: Long,
    val site: String = "", val free: Boolean = false, val needUrl: Boolean = false,
    val optName: String = "", val opts: List<Pair<String, String>> = emptyList(), val optHint: String = "",
)

/** 某一家填的东西。 */
data class SearchCfg(val key: String = "", val url: String = "", val opt: Int = 0)

/** 一条结果。 */
class Hit(val title: String, val url: String, val text: String, val date: String = "")

/**
 * 联网搜索（「我的 → 功能 → 联网搜索」）。各家接口照 RikkaHub 的 search 模块；Bing、DuckDuckGo 不要密钥，直接读网页结果。
 * 她什么时候查、信什么不信什么，见 system()（《浮光掠影-搜索准则》里「她读到的」那一段）。
 */
object WebSearch {
    const val TOOL = "web_search"
    private const val KEY = "web-search"

    val sources = listOf(
        SearchSource("bing", "Bing", "本机 · 不要密钥", "B", 0xFF7FA7D9, free = true),
        SearchSource("ddg", "DuckDuckGo", "不要密钥", "D", 0xFFE0906A, free = true),
        SearchSource("deepseek", "DeepSeek", "自家搜索引擎 · 聊天的 Key 就能用", "深", 0xFF8EA4E0, "platform.deepseek.com/api_keys"),
        SearchSource("tavily", "Tavily", "给 AI 用的搜索", "T", 0xFFE9B48E, "app.tavily.com", optName = "深度", opts = listOf("快" to "basic", "深" to "advanced"), optHint = "深：多花一倍额度，结果更全"),
        SearchSource("exa", "Exa", "语义搜索", "E", 0xFFA9B6CC, "dashboard.exa.ai/api-keys", optName = "方式", opts = listOf("自动" to "auto", "快速" to "fast", "深入" to "deep")),
        SearchSource("brave", "Brave", "独立索引", "Br", 0xFFD98A6A, "api.search.brave.com"),
        SearchSource("zhipu", "智谱", "中文搜索", "智", 0xFFB3A6D9, "bigmodel.cn/usercenter/proj-mgmt/apikeys"),
        SearchSource("metaso", "秘塔", "中文搜索", "秘", 0xFF8FB0D9, "metaso.cn"),
        SearchSource("linkup", "LinkUp", "带出处", "L", 0xFFC9BC97, "app.linkup.so", optName = "深度", opts = listOf("标准" to "standard", "深" to "deep")),
        SearchSource("searxng", "SearXNG", "自建 · 填地址", "S", 0xFF9FB79A, needUrl = true),
    )

    fun source(id: String) = sources.firstOrNull { it.id == id } ?: sources.first()

    var on by mutableStateOf(true)
    var use by mutableStateOf("bing")
    var added by mutableStateOf(listOf("bing", "ddg"))
    var max by mutableIntStateOf(8)
    var wait by mutableIntStateOf(8)
    var cfg by mutableStateOf(mapOf<String, SearchCfg>())
    private var prefs: Prefs? = null

    fun init(p: Prefs) {
        prefs = p
        runCatching {
            val o = JSONObject(p.string(KEY) ?: return@runCatching)
            on = o.optBoolean("on", true); use = o.optString("use", use); max = o.optInt("max", max); wait = o.optInt("wait", wait)
            o.optJSONArray("added")?.let { a -> added = List(a.length()) { a.getString(it) }.filter { id -> sources.any { it.id == id } }.ifEmpty { added } }
            o.optJSONObject("cfg")?.let { c -> cfg = c.keys().asSequence().associateWith { k -> c.getJSONObject(k).let { SearchCfg(it.optString("key"), it.optString("url"), it.optInt("opt")) } } }
        }
    }

    fun save() {
        val c = JSONObject(); cfg.forEach { (k, v) -> c.put(k, JSONObject().put("key", v.key).put("url", v.url).put("opt", v.opt)) }
        prefs?.putString(KEY, JSONObject().put("on", on).put("use", use).put("max", max).put("wait", wait).put("added", JSONArray(added)).put("cfg", c).toString())
    }

    fun cfgOf(id: String) = cfg[id] ?: SearchCfg()
    fun setCfg(id: String, c: SearchCfg) { cfg = cfg + (id to c); save() }
    /** 这家用的 Key：DeepSeek 没单独填时，借聊天里配好的 DeepSeek Key（同一个账号、同一个 Key）。 */
    fun keyOf(id: String): String = cfgOf(id).key.trim().ifEmpty { if (id == "deepseek") AttachLook.store?.config("deepseek")?.key?.trim().orEmpty() else "" }
    fun ready(id: String): Boolean = source(id).let { s -> s.free || (if (s.needUrl) cfgOf(id).url.isNotBlank() else keyOf(id).isNotBlank()) }
    fun add(id: String) { if (id !in added) added = added + id; use = id; save() }
    fun remove(id: String) {
        added = (added - id).ifEmpty { listOf("bing") }
        if (use == id) use = added.first()
        cfg = cfg - id; save()
    }
    /** 开着、而且选着的那一家能用，才给她这个本事。 */
    val usable: Boolean get() = on && ready(use)
    /** 「功能」那一行：已启用几项。 */
    val enabledCount: Int get() = (if (on) 1 else 0) + (if (Skills.on) 1 else 0) + (if (Gallery.on) 1 else 0)

    val spec = ToolSpec(
        TOOL,
        "上网查现在的事：最新消息、天气、价格、比分、谁现在在什么位置、某样东西现在什么样，或者你不认识的名字和词。词要短，几件事分开查。",
        JSONObject().put("type", "object").put("properties", JSONObject().put("query", JSONObject().put("type", "string").put("description", "要查的词，短一点，两三个到五六个词"))).put("required", JSONArray().put("query")),
    )

    /** 她读到的那段（《浮光掠影-搜索准则》）：只用「你…」。 */
    fun system(): String {
        val n = Memory.userCall
        return "你能上网查东西（$TOOL）。查，是为了让你说的「现在的事」是真的：最新的消息、天气、价格、比分、谁现在在哪个位置、某样东西现在是什么样，还有你不认识的名字和词。" +
            "不会变的事、${n}的心事、你们一起编的故事，不用查，你本来就知道。拿不准是不是变了，问的又是「现在」，就查一下。" +
            "\n查的时候词要短，一次没找到就换个说法。几件事就分开查；几种说法对不上，再查一次分辨清楚，看最具体的那个细节。" +
            "\n网上很多是带货和软文：「十大推荐」「必买」「亲测好用」、带购买链接和优惠码的、通篇只夸不说缺点的，你都不轻信。先看官方网站、正规媒体、原始出处；" +
            "买东西、看病、理财这些事，至少两处互不相干的地方对得上才说。只有推广文章撑着的说法，就直说网上多是推广，没找到靠得住的。" +
            "\n说给${n}听的时候，先说答案，用你自己的话，不整段照搬；说清楚是什么时候的消息；对不上的地方直说；没查到就说没查到，不拿旧印象冒充。" +
            "最后带上你真正用到的出处（网站名和链接）。说话还是你平常的样子。"
    }

    /** 她用这个本事：查到的整理成短短的列表交给她。 */
    suspend fun run(query: String): ToolOut {
        val q = query.trim().ifEmpty { return ToolOut("没写要查什么。") }
        val hits = search(use, q)
        if (hits.isEmpty()) return ToolOut("「$q」没查到东西。可以换个说法再查，或者如实说没查到。")
        val arr = JSONArray()
        hits.forEach { h -> arr.put(JSONObject().put("title", h.title).put("url", h.url).put("text", h.text.take(800)).apply { if (h.date.isNotBlank()) put("date", h.date) }) }
        return ToolOut(JSONObject().put("query", q).put("from", source(use).name).put("results", arr).toString())
    }

    /** 用某一家查一次（试搜也走这里）。 */
    suspend fun search(id: String, q: String, n: Int = max): List<Hit> = withContext(Dispatchers.IO) {
        val c = cfgOf(id); val s = source(id)
        val opt = s.opts.getOrNull(c.opt)?.second.orEmpty()
        val base = c.url.trim().trimEnd('/')
        val list: List<Hit> = when (id) {
            // DeepSeek 2026-10-17 开放的自家搜索：summary 是给模型读的长摘要（600–800 字），query 最多 128 字
            "deepseek" -> post(base.ifEmpty { "https://api.deepseek.com" } + "/v1/tools/search", JSONObject().put("query", q.take(128)).put("count", n.coerceIn(1, 50)), "Bearer ${keyOf(id)}")
                .optJSONArray("results").hits { Hit(it.optString("title"), it.optString("url"), it.optString("summary").ifEmpty { it.optString("snippet") }, it.optString("date").takeIf { d -> d != "null" }.orEmpty()) }
            "tavily" -> post(base.ifEmpty { "https://api.tavily.com" } + "/search", JSONObject().put("query", q).put("max_results", n).put("search_depth", opt.ifEmpty { "basic" }), "Bearer ${c.key.trim()}")
                .optJSONArray("results").hits { Hit(it.optString("title"), it.optString("url"), it.optString("content"), it.optString("published_date")) }
            "exa" -> post(base.ifEmpty { "https://api.exa.ai" } + "/search", JSONObject().put("query", q).put("numResults", n).put("type", opt.ifEmpty { "auto" })
                .put("contents", JSONObject().put("text", JSONObject().put("maxCharacters", 800))), "Bearer ${c.key.trim()}")
                .optJSONArray("results").hits { Hit(it.optString("title"), it.optString("url"), it.optString("text"), it.optString("publishedDate")) }
            "brave" -> get(base.ifEmpty { "https://api.search.brave.com" } + "/res/v1/web/search?q=${enc(q)}&count=${n.coerceAtMost(20)}", mapOf("X-Subscription-Token" to c.key.trim(), "Accept" to "application/json"))
                .optJSONObject("web")?.optJSONArray("results").hits { Hit(it.optString("title"), it.optString("url"), it.optString("description"), it.optString("age")) }
            "zhipu" -> post(base.ifEmpty { "https://open.bigmodel.cn" } + "/api/paas/v4/web_search", JSONObject().put("search_query", q).put("search_engine", "search_std").put("count", n), "Bearer ${c.key.trim()}")
                .optJSONArray("search_result").hits { Hit(it.optString("title"), it.optString("link"), it.optString("content"), it.optString("publish_date")) }
            "metaso" -> post(base.ifEmpty { "https://metaso.cn" } + "/api/v1/search", JSONObject().put("q", q).put("scope", "webpage").put("size", n).put("includeSummary", false), "Bearer ${c.key.trim()}")
                .optJSONArray("webpages").hits { Hit(it.optString("title"), it.optString("link"), it.optString("snippet").ifEmpty { it.optString("summary") }, it.optString("date")) }
            "linkup" -> post(base.ifEmpty { "https://api.linkup.so" } + "/v1/search", JSONObject().put("q", q).put("depth", opt.ifEmpty { "standard" }).put("outputType", "searchResults"), "Bearer ${c.key.trim()}")
                .optJSONArray("results").hits { Hit(it.optString("name"), it.optString("url"), it.optString("content")) }.take(n)
            "searxng" -> {
                if (base.isEmpty()) error("还没填 SearXNG 的地址")
                get("$base/search?q=${enc(q)}&format=json", if (c.key.isNotBlank()) mapOf("Authorization" to "Bearer ${c.key.trim()}") else emptyMap())
                    .optJSONArray("results").hits { Hit(it.optString("title"), it.optString("url"), it.optString("content"), it.optString("publishedDate")) }.take(n)
            }
            "ddg" -> page("https://html.duckduckgo.com/html/?q=${enc(q)}").select(".result").mapNotNull { e ->
                val a = e.selectFirst("a.result__a") ?: return@mapNotNull null
                var href = a.attr("href")
                // DuckDuckGo 的链接是跳转地址：取出真正的网址
                Regex("uddg=([^&]+)").find(href)?.let { href = URLDecoder.decode(it.groupValues[1], "UTF-8") }
                Hit(a.text(), href, e.selectFirst(".result__snippet")?.text().orEmpty())
            }.take(n)
            else -> page("https://www.bing.com/search?q=${enc(q)}&count=${n.coerceAtMost(30)}").select("li.b_algo").mapNotNull { e ->
                val a = e.selectFirst("h2 a") ?: return@mapNotNull null
                Hit(a.text(), a.attr("href"), e.selectFirst(".b_caption p, p")?.text().orEmpty())
            }.take(n)
        }
        list.filter { it.url.startsWith("http") && it.title.isNotBlank() }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private inline fun JSONArray?.hits(f: (JSONObject) -> Hit): List<Hit> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it)?.let(f) }

    private fun open(u: String): HttpURLConnection = (URL(u).openConnection() as HttpURLConnection).apply {
        connectTimeout = wait * 1000; readTimeout = wait * 1000
    }

    private fun answer(conn: HttpURLConnection): JSONObject {
        val code = conn.responseCode
        if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw IllegalStateException(
                when (code) {
                    401, 403 -> "Key 不对或没有权限（$code）"; 402 -> "额度用完了（402）"; 429 -> "查得太频繁或额度用完（429）"
                    else -> "出错了（$code）" + err.take(60).let { if (it.isBlank()) "" else "：$it" }
                },
            )
        }
        return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
    }

    private fun post(u: String, body: JSONObject, auth: String): JSONObject = open(u).run {
        requestMethod = "POST"; doOutput = true
        setRequestProperty("Content-Type", "application/json"); setRequestProperty("Accept", "application/json"); setRequestProperty("Authorization", auth)
        outputStream.use { it.write(body.toString().toByteArray()) }
        try { answer(this) } finally { disconnect() }
    }

    private fun get(u: String, headers: Map<String, String>): JSONObject = open(u).run {
        headers.forEach { (k, v) -> setRequestProperty(k, v) }
        try { answer(this) } finally { disconnect() }
    }

    /** 不要密钥的两家：像浏览器一样打开结果页再读。 */
    private fun page(u: String) = Jsoup.connect(u)
        .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36")
        .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
        .timeout(wait * 1000)
        .get()
}
