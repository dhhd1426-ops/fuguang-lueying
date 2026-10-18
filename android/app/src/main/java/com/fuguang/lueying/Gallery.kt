package com.fuguang.lueying

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 一家图库。free：不要 Key。remote：图只能用它给的链接（不存进手机）。 */
data class GallerySource(val id: String, val name: String, val sub: String, val letter: String, val tint: Long, val site: String = "", val free: Boolean = false, val remote: Boolean = false)

/** 图库里的一张：统一成一个样子（照 image-resolver-mcp）。 */
class Pic(val url: String, val kind: String, val w: Int, val h: Int, val author: String, val from: String, val license: String, val page: String, val thumb: String)

/**
 * 图库（「我的 → 功能 → 图库」）：她找一般配图的地方，拿高清原图，带作者和授权。
 * 接口：Pexels（照片、视频）、Pixabay（照片、插画、矢量）、Openverse（开放授权，不要 Key）、Iconify（图标，不要 Key）、Unsplash（只能用它的链接）。
 */
object Gallery {
    const val TOOL = "find_images"
    private const val KEY = "gallery"

    val sources = listOf(
        GallerySource("illust", "内置插画", "手绘场景和头像 · 不联网", "绘", 0xFFE9B48E, free = true),
        GallerySource("openverse", "Openverse", "开放授权 · 不要 Key", "O", 0xFF9FB79A, free = true),
        GallerySource("iconify", "Iconify", "图标 30 多万个 · 不要 Key", "I", 0xFFA9B6CC, free = true),
        GallerySource("pexels", "Pexels", "照片和视频 · 免费 Key", "P", 0xFF8FC1B0, "www.pexels.com/api"),
        GallerySource("pixabay", "Pixabay", "照片、插画、矢量 · 免费 Key", "X", 0xFFE0B46A, "pixabay.com/api/docs"),
        GallerySource("unsplash", "Unsplash", "照片最好 · 只能联网看", "U", 0xFFD9D3CC, "unsplash.com/developers", remote = true),
    )

    fun source(id: String) = sources.firstOrNull { it.id == id } ?: sources.first()

    var on by mutableStateOf(true)
    var used by mutableStateOf(listOf("illust", "openverse", "iconify"))
    var keys by mutableStateOf(mapOf<String, String>())
    private var prefs: Prefs? = null

    fun init(p: Prefs) {
        prefs = p
        runCatching {
            val o = JSONObject(p.string(KEY) ?: return@runCatching)
            on = o.optBoolean("on", true)
            o.optJSONArray("used")?.let { a -> used = List(a.length()) { a.getString(it) }.filter { id -> sources.any { it.id == id } } }
            // 第 185 版加了内置插画：以前存过设置的也补上一次
            if (o.optInt("v") < 2 && "illust" !in used) used = listOf("illust") + used
            o.optJSONObject("keys")?.let { k -> keys = k.keys().asSequence().associateWith { k.optString(it) } }
        }
    }

    fun save() {
        prefs?.putString(KEY, JSONObject().put("v", 2).put("on", on).put("used", JSONArray(used)).put("keys", JSONObject(keys)).toString())
    }

    fun ready(id: String) = source(id).free || keys[id].orEmpty().isNotBlank()
    fun setKey(id: String, k: String) { keys = keys + (id to k.trim()); save() }
    fun add(id: String) { if (id !in used) used = used + id; save() }
    fun remove(id: String) { used = used - id; save() }
    /** 开着、而且至少一家能用，才给她这个本事。 */
    val usable: Boolean get() = on && used.any { ready(it) }

    /* ---------- 给她 ---------- */

    val spec = ToolSpec(
        TOOL,
        "在图库里找图：高清原图，带作者和授权。照片、插画、图标、视频都有。一般配图（氛围、背景、风景、物件、食物、人物）先在这里找；讲具体某样东西的（某场展览的海报、某家店）去打开它的网页拿。",
        JSONObject().put("type", "object").put(
            "properties",
            JSONObject()
                .put("query", JSONObject().put("type", "string").put("description", "要找的画面，用英文词更准，两三个词，比如 autumn cafe"))
                .put("kind", JSONObject().put("type", "string").put("enum", JSONArray(listOf("photo", "illustration", "icon", "video"))).put("description", "照片 / 插画 / 图标 / 视频，默认照片"))
                .put("orientation", JSONObject().put("type", "string").put("enum", JSONArray(listOf("landscape", "portrait", "square"))).put("description", "横 / 竖 / 方，不限就不填")),
        ).put("required", JSONArray().put("query")),
    )

    fun system(): String =
        "你还有一个图库（$TOOL）：高清原图，每张带作者和授权，有照片、插画、图标和视频。" +
            "插画里有一套内置的手绘小场景（读书、咖啡、跑步、遛狗、植物……）和手绘头像，不联网也能用，地址里的 ink（线条）、accent（色块）、paper（头像的底色）可以改成作品的颜色（六位色值，不带 #）。" +
            "一般配图——氛围、背景、风景、物件、食物、人物——先在图库里找；" +
            "讲具体某样东西的（某场展览的海报、某家店的门面），去打开它的网页拿。图标都在图库里找（kind=icon），拿到的是 SVG，颜色可以随作品改。" +
            "用英文词找更准。找到后挑几件看一眼再用；作品里每张图库的图都写上它给的 credit（作者和图库名）。"

    private val said = HashMap<String, String>()
    fun doneTitle(q: String) = synchronized(said) { said[q.trim()] } ?: "在图库找了「${q.trim().take(16)}」"

    suspend fun run(a: JSONObject): ToolOut {
        val q = a.optString("query").trim().ifEmpty { return ToolOut("没写要找什么。") }
        val kind = a.optString("kind").ifEmpty { "photo" }
        val ori = a.optString("orientation")
        val ids = used.filter { ready(it) }.filter { id ->
            when (kind) { "icon" -> id == "iconify"; "video" -> id == "pexels" || id == "pixabay"; "illustration" -> id == "pixabay" || id == "openverse" || id == "illust"; else -> id != "iconify" && id != "illust" }
        }
        if (ids.isEmpty()) return ToolOut(if (kind == "icon") "图标要开 Iconify，现在没开。" else "能找这类图的图库都没开或没填 Key。")
        val (pics, errs) = coroutineScope {
            ids.map { id -> async(Dispatchers.IO) { id to runCatching { search(id, q, kind, ori, 8) } } }.awaitAll()
        }.let { r -> r.flatMap { it.second.getOrDefault(emptyList()) } to r.mapNotNull { (id, x) -> x.exceptionOrNull()?.let { "${source(id).name}：${it.message?.take(40)}" } } }
        synchronized(said) { said[q] = "在图库找了「${q.take(16)}」" + if (pics.isEmpty()) "，没找到" else "，拿到 ${pics.size} 张" }
        if (pics.isEmpty()) return ToolOut("「$q」在图库里没找到。" + (if (errs.isEmpty()) "换个英文词再找。" else "出错：" + errs.joinToString("；")))
        pics.forEach { Materials.mark(it.url, it.page) }
        val arr = JSONArray()
        pics.forEachIndexed { i, p ->
            arr.put(JSONObject().put("n", i + 1).put("kind", p.kind).put("url", p.url).put("from", p.from).put("credit", credit(p)).apply {
                if (p.w > 0) put("size", "${p.w}×${p.h}")
                if (p.license.isNotBlank()) put("license", p.license)
            })
        }
        return ToolOut(JSONObject().put("query", q).put("results", arr).apply { if (errs.isNotEmpty()) put("errors", JSONArray(errs)) }.toString())
    }

    /** 署名：照各家要求的写法。 */
    fun credit(p: Pic) = when (p.from) {
        "Unsplash" -> "Photo by ${p.author} on Unsplash"
        "Iconify" -> "图标：${p.author}（Iconify）"
        "内置插画" -> "插画：${p.author}（CC0）"
        "Openverse" -> "${p.author.ifBlank { "佚名" }} · ${p.license}（Openverse）"
        else -> "${p.author} / ${p.from}"
    }

    /* ---------- Unsplash：图只能用它的链接，用进作品时告诉它一声 ---------- */

    private val track = HashMap<String, String>()
    fun keepRemote(u: String) = u.contains("images.unsplash.com")
    fun used(u: String) {
        val loc = synchronized(track) { track.remove(u) } ?: return
        val k = keys["unsplash"].orEmpty()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { runCatching { get(loc, mapOf("Authorization" to "Client-ID $k")) } }
    }

    /* ---------- 各家 ---------- */

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    suspend fun search(id: String, q: String, kind: String, ori: String, n: Int): List<Pic> = withContext(Dispatchers.IO) {
        val k = keys[id].orEmpty().trim()
        when (id) {
            "illust" -> Illust.search(q, n)
            "iconify" -> {
                val o = get("https://api.iconify.design/search?query=${enc(q)}&limit=32", emptyMap())
                val a = o.optJSONArray("icons") ?: JSONArray()
                (0 until minOf(a.length(), n * 2)).map { i ->
                    val name = a.getString(i); val (pre, ic) = name.substringBefore(':') to name.substringAfter(':')
                    Pic("https://api.iconify.design/$pre/$ic.svg", "图标", 0, 0, name, "Iconify", "", "https://icon-sets.iconify.design/$pre/$ic/", "")
                }
            }
            "openverse" -> {
                val cat = if (kind == "illustration") "&category=illustration,digitized_artwork" else "&category=photograph"
                val ar = when (ori) { "landscape" -> "&aspect_ratio=wide"; "portrait" -> "&aspect_ratio=tall"; "square" -> "&aspect_ratio=square"; else -> "" }
                val o = get("https://api.openverse.org/v1/images/?q=${enc(q)}&page_size=$n&license_type=commercial,modification$cat$ar", emptyMap())
                o.optJSONArray("results").list { r ->
                    Pic(r.optString("url"), if (kind == "illustration") "插画" else "照片", r.optInt("width"), r.optInt("height"), r.optString("creator"), "Openverse",
                        (r.optString("license").uppercase() + " " + r.optString("license_version")).trim().let { if (it.startsWith("CC0") || it.startsWith("PDM")) it else "CC $it" },
                        r.optString("foreign_landing_url"), r.optString("thumbnail"))
                }
            }
            "pexels" -> {
                val oq = if (ori.isNotEmpty()) "&orientation=$ori" else ""
                if (kind == "video") {
                    val o = get("https://api.pexels.com/videos/search?query=${enc(q)}&per_page=$n$oq", mapOf("Authorization" to k))
                    o.optJSONArray("videos").list { v ->
                        val files = v.optJSONArray("video_files").list { it }.filter { it.optString("file_type") == "video/mp4" && it.optInt("width") in 1..1920 }
                        val f = files.maxByOrNull { it.optInt("width") } ?: return@list null
                        Pic(f.optString("link"), "视频", f.optInt("width"), f.optInt("height"), v.optJSONObject("user")?.optString("name").orEmpty(), "Pexels", "Pexels License", v.optString("url"), v.optString("image"))
                    }
                } else {
                    val o = get("https://api.pexels.com/v1/search?query=${enc(q)}&per_page=$n$oq", mapOf("Authorization" to k))
                    o.optJSONArray("photos").list { p ->
                        val src = p.optJSONObject("src") ?: return@list null
                        Pic(src.optString("large2x").ifEmpty { src.optString("original") }, "照片", p.optInt("width"), p.optInt("height"), p.optString("photographer"), "Pexels", "Pexels License", p.optString("url"), src.optString("medium"))
                    }
                }
            }
            "pixabay" -> {
                val oq = when (ori) { "landscape" -> "&orientation=horizontal"; "portrait" -> "&orientation=vertical"; else -> "" }
                if (kind == "video") {
                    val o = get("https://pixabay.com/api/videos/?key=${enc(k)}&q=${enc(q.take(100))}&per_page=${n.coerceAtLeast(3)}&safesearch=true", emptyMap())
                    o.optJSONArray("hits").list { h ->
                        val v = h.optJSONObject("videos")?.let { it.optJSONObject("large")?.takeIf { x -> x.optString("url").isNotEmpty() } ?: it.optJSONObject("medium") } ?: return@list null
                        Pic(v.optString("url"), "视频", v.optInt("width"), v.optInt("height"), h.optString("user"), "Pixabay", "Pixabay License", h.optString("pageURL"), v.optString("thumbnail"))
                    }
                } else {
                    val type = if (kind == "illustration") "illustration" else "photo"
                    val o = get("https://pixabay.com/api/?key=${enc(k)}&q=${enc(q.take(100))}&image_type=$type&per_page=${n.coerceAtLeast(3)}&safesearch=true$oq", emptyMap())
                    o.optJSONArray("hits").list { h ->
                        Pic(h.optString("largeImageURL"), if (type == "photo") "照片" else "插画", h.optInt("imageWidth"), h.optInt("imageHeight"), h.optString("user"), "Pixabay", "Pixabay License", h.optString("pageURL"), h.optString("webformatURL"))
                    }
                }
            }
            "unsplash" -> {
                val oq = when (ori) { "landscape" -> "&orientation=landscape"; "portrait" -> "&orientation=portrait"; "square" -> "&orientation=squarish"; else -> "" }
                val o = get("https://api.unsplash.com/search/photos?query=${enc(q)}&per_page=$n$oq", mapOf("Authorization" to "Client-ID $k", "Accept-Version" to "v1"))
                o.optJSONArray("results").list { p ->
                    val urls = p.optJSONObject("urls") ?: return@list null
                    val u = urls.optString("raw").let { if (it.isEmpty()) urls.optString("regular") else "$it&w=1800&q=80&fm=jpg" }
                    p.optJSONObject("links")?.optString("download_location")?.takeIf { it.isNotEmpty() }?.let { synchronized(track) { track[u] = it } }
                    Pic(u, "照片", p.optInt("width"), p.optInt("height"), p.optJSONObject("user")?.optString("name").orEmpty(), "Unsplash", "Unsplash License", p.optJSONObject("links")?.optString("html").orEmpty(), urls.optString("small"))
                }
            }
            else -> emptyList()
        }.filter { it.url.startsWith("http") }.take(n)
    }

    private inline fun <T> JSONArray?.list(f: (JSONObject) -> T?): List<T> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it)?.let(f) }

    private fun get(u: String, headers: Map<String, String>): JSONObject {
        val c = (URL(u).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 15_000
            setRequestProperty("Accept", "application/json"); setRequestProperty("User-Agent", "fuguang-lueying/1.0")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            val code = c.responseCode
            if (code !in 200..299) error(when (code) { 401, 403 -> "Key 不对或没有权限（$code）"; 429 -> "找得太频繁了，过一会儿再试（429）"; else -> "出错了（$code）" })
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }
}
