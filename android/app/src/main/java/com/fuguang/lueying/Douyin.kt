package com.fuguang.lueying

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 抖音分享链接 → 作品（照 GitHub 上还在维护的几家：belingud/douyin-downloader-skill、yuanyuandada/douyin-dl、Ayuan97/douyin-no-watermark）：
 * 1. 分享文字里抠出链接，短链跳一次拿作品 id；
 * 2. 带 ttwid 读 iesdouyin 分享页，页面里内联的 _ROUTER_DATA 有作品数据（2026-08 起不带 ttwid 只给空壳）；
 * 3. 读不到再走 www.douyin.com 的 detail 接口；
 * 4. 播放地址 playwm 换成 play 就是无水印的。不登录最高 720p；内置浏览器里登过抖音就带上那份 Cookie。
 * 都不行时调用方交给 yt-dlp（[pageUrl]）。
 */
object Douyin {
    class Item(
        val id: String, val title: String, val desc: String, val author: String, val cover: String?, val seconds: Int,
        val video: String?, val images: List<String>, val music: String?,
    ) {
        val pageUrl get() = "https://www.douyin.com/" + (if (images.isNotEmpty()) "note/" else "video/") + id
    }

    const val MOBILE_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
    private const val DESK_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
    private val LINK = Regex("https?://(?:v\\.douyin\\.com|www\\.douyin\\.com|m\\.douyin\\.com|www\\.iesdouyin\\.com|jx\\.douyin\\.com)/[A-Za-z0-9_\\-./?=&%]+")
    private val ID = Regex("/(?:video|note|slides)/(\\d{8,})|modal_id=(\\d{8,})|aweme_id=(\\d{8,})")
    @Volatile private var ttwid: String? = null

    /** 文字里有没有抖音链接。 */
    fun link(text: String): String? = LINK.find(text)?.value?.trimEnd('.', '/', '，', '。') ?.let { if (it.contains("douyin.com/") || it.contains("iesdouyin")) it else null }

    suspend fun parse(link: String): Item = withContext(Dispatchers.IO) {
        val id = idOf(link) ?: error("链接里认不出作品")
        val first = runCatching { fromShare(id) }.getOrNull()
        first ?: runCatching { fromDetail(id) }.getOrNull() ?: error("抖音没给数据，可能要等一会儿再试")
    }

    /** 下载用的请求头。 */
    fun headers(): Map<String, String> = mapOf("User-Agent" to MOBILE_UA, "Referer" to "https://www.douyin.com/")

    private fun idOf(link: String): String? {
        ID.find(link)?.let { m -> return m.groupValues.drop(1).first { it.isNotEmpty() } }
        // 短链：跟着跳，最后的地址里有 id
        var u = link; var hops = 0
        while (hops++ < 5) {
            val c = conn(u, MOBILE_UA)
            val code = c.responseCode
            val loc = c.getHeaderField("Location"); c.disconnect()
            if (code !in 300..399 || loc == null) break
            u = URL(URL(u), loc).toString()
            ID.find(u)?.let { m -> return m.groupValues.drop(1).first { it.isNotEmpty() } }
        }
        return null
    }

    private fun conn(url: String, ua: String, cookie: String? = null, post: String? = null): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000; readTimeout = 15_000; instanceFollowRedirects = false
            setRequestProperty("User-Agent", ua)
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            cookie?.let { setRequestProperty("Cookie", it) }
            if (post != null) {
                requestMethod = "POST"; doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(post.toByteArray()) }
            }
        }

    private fun text(c: HttpURLConnection): String = try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }

    /** 匿名 ttwid：字节的统一注册接口给一个。 */
    private fun ttwid(): String? {
        ttwid?.let { return it }
        return runCatching {
            val body = """{"region":"cn","aid":1768,"needFid":false,"service":"www.ixigua.com","migrate_info":{"ticket":"","source":"node"},"cbUrl_protocol":"https","union":true}"""
            val c = conn("https://ttwid.bytedance.com/ttwid/union/register/", DESK_UA, post = body)
            c.responseCode
            val v = c.headerFields.entries.filter { it.key.equals("Set-Cookie", true) }.flatMap { it.value }
                .firstNotNullOfOrNull { Regex("ttwid=([^;]+)").find(it)?.groupValues?.get(1) }
            c.disconnect(); v
        }.getOrNull()?.also { ttwid = it }
    }

    /** 带上的 Cookie：内置浏览器里登过抖音就用那份，再补上 ttwid。 */
    private fun cookie(): String {
        val web = runCatching { CookieManager.getInstance().getCookie("https://www.douyin.com") }.getOrNull().orEmpty()
        if (web.contains("ttwid=")) return web
        val t = ttwid() ?: return web
        return listOf(web, "ttwid=$t").filter { it.isNotBlank() }.joinToString("; ")
    }

    private fun fromShare(id: String): Item? {
        val page = text(conn("https://www.iesdouyin.com/share/video/$id/", MOBILE_UA, cookie()))
        val at = page.indexOf("window._ROUTER_DATA").takeIf { it >= 0 } ?: return null
        val json = balanced(page, page.indexOf('{', at)) ?: return null
        val loader = JSONObject(json).optJSONObject("loaderData") ?: return null
        loader.keys().forEach { k ->
            val list = loader.optJSONObject(k)?.optJSONObject("videoInfoRes")?.optJSONArray("item_list")
            if (list != null && list.length() > 0) return item(list.getJSONObject(0), id)
        }
        return null
    }

    private fun fromDetail(id: String): Item? {
        val url = "https://www.douyin.com/aweme/v1/web/aweme/detail/?device_platform=webapp&aid=6383&channel=channel_pc_web&aweme_id=$id&pc_client_type=1&version_code=190500&version_name=19.5.0&cookie_enabled=true&platform=PC&downlink=10"
        val c = conn(url, DESK_UA, cookie()).apply { setRequestProperty("Referer", "https://www.douyin.com/") }
        val body = text(c).takeIf { it.isNotBlank() } ?: return null
        return JSONObject(body).optJSONObject("aweme_detail")?.let { item(it, id) }
    }

    /** 从 { 开始配平括号（跳过字符串里的括号），截出整段 JSON。 */
    private fun balanced(s: String, from: Int): String? {
        if (from < 0) return null
        var depth = 0; var inStr = false; var esc = false
        for (i in from until s.length) {
            val ch = s[i]
            if (inStr) { if (esc) esc = false else if (ch == '\\') esc = true else if (ch == '"') inStr = false; continue }
            when (ch) { '"' -> inStr = true; '{' -> depth++; '}' -> if (--depth == 0) return s.substring(from, i + 1) }
        }
        return null
    }

    private fun first(a: JSONArray?): String? = a?.let { (0 until it.length()).map { i -> it.optString(i) }.firstOrNull { u -> u.startsWith("http") } }

    private fun item(o: JSONObject, id: String): Item {
        val v = o.optJSONObject("video")
        val imgs = o.optJSONArray("images")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val urls = a.optJSONObject(i)?.optJSONArray("url_list") ?: return@mapNotNull null
                val all = (0 until urls.length()).map { urls.optString(it) }
                all.firstOrNull { it.contains(".jpeg") || it.contains(".jpg") } ?: all.firstOrNull { it.startsWith("http") }
            }
        }.orEmpty()
        val play = v?.optJSONObject("play_addr")
        val video = if (imgs.isNotEmpty()) null else first(play?.optJSONArray("url_list"))?.replace("playwm", "play")
            ?: play?.optString("uri")?.takeIf { it.isNotBlank() }?.let { "https://www.iesdouyin.com/aweme/v1/play/?video_id=$it&ratio=720p&line=0" }
        val ms = v?.optInt("duration")?.takeIf { it > 0 } ?: o.optInt("duration")
        return Item(
            id = id,
            title = o.optString("desc").lineSequence().firstOrNull()?.trim().orEmpty().ifBlank { "抖音作品" }.take(60),
            desc = o.optString("desc").trim().take(2000),
            author = o.optJSONObject("author")?.optString("nickname").orEmpty(),
            cover = first(v?.optJSONObject("cover")?.optJSONArray("url_list")) ?: first(v?.optJSONObject("origin_cover")?.optJSONArray("url_list")) ?: imgs.firstOrNull(),
            seconds = if (ms > 1000) ms / 1000 else ms,
            video = video, images = imgs,
            music = first(o.optJSONObject("music")?.optJSONObject("play_url")?.optJSONArray("url_list")),
        )
    }
}
