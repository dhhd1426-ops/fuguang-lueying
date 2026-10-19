package com.fuguang.lueying

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 网页素材：她打开一页（open_page），拿到正文和素材（海报、图片、图标、动图、视频、背景图）；
 * 挑几件看一眼（look_materials）；做作品时写进去的素材，由 MaterialStore 存进手机。
 */
object Materials {
    const val OPEN = "open_page"
    const val LOOK = "look_materials"
    const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

    /** 她见过的素材：地址 → 从哪页拿的（下载时当来源，过防盗链）。 */
    private val from = HashMap<String, String>()
    fun pageOf(u: String): String? = synchronized(from) { from[u] }
    fun seen(u: String): Boolean = synchronized(from) { u in from }
    /** 图库给的图也记下来：做作品时一样存进手机。 */
    fun mark(u: String, page: String) { synchronized(from) { from[u] = page } }

    /** 时间线上那一句：读了哪页、拿到几件。 */
    private val said = HashMap<String, String>()
    fun doneTitle(u: String): String = synchronized(said) { said[u.trim()] } ?: "打开了「${u.trim().substringAfter("://").substringBefore('/').removePrefix("www.").take(24)}」"

    val specs: List<ToolSpec> get() = listOf(
        ToolSpec(
            OPEN,
            "打开一个网页，读它的正文，拿到页面上的素材：海报、图片、图标和 Logo、动图、视频和它的封面、背景图。每件带着种类、尺寸、旁边那句话。",
            JSONObject().put("type", "object").put("properties", JSONObject().put("url", JSONObject().put("type", "string").put("description", "网址")))
                .put("required", JSONArray().put("url")),
        ),
        ToolSpec(
            LOOK,
            "看一眼几件素材（最多 6 件）：画面里是什么、横竖、主色。排版前挑要用的那几件看。",
            JSONObject().put("type", "object").put(
                "properties",
                JSONObject().put("urls", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "素材地址，照 $OPEN 给的原样写")),
            ).put("required", JSONArray().put("urls")),
        ),
    )

    /** 她读到的那段：只用「你…」。 */
    fun system(): String =
        "你能打开网页（$OPEN），读正文，也拿到页面上的素材：海报、图片、图标、动图、视频、背景图。" +
            "做东西要用到哪个网页的图和视频，就先打开它；挑几件用 $LOOK 看一眼，知道里面是什么、什么颜色、横的竖的，再想怎么排。" +
            "排的时候把素材地址原样写进作品（img、video、背景图、幻灯片的 image、文档里的 ![说明](地址)、画板封面和卡片墙的 img 都行），" +
            "做好时这些素材会存进这台手机，以后断网也看得到。素材是别人的，作品里留一行小字写出处。"

    suspend fun run(name: String, a: JSONObject, vision: Boolean): ToolOut = if (name == OPEN) open(a.optString("url").trim()) else look(a, vision)

    /* ---------- 打开网页 ---------- */

    private suspend fun open(url: String): ToolOut {
        if (!url.startsWith("http")) return ToolOut("网址不对：要 http 开头的完整网址。")
        Douyin.link(url)?.let { l -> runCatching { Douyin.parse(l) }.getOrNull()?.let { return douyin(url, it) } }
        val g = PageGrab.grab(url)
        val host = runCatching { URL(g.url).host }.getOrDefault("")
        val picked = pick(g.raw, host)
        synchronized(from) { picked.forEach { from[it.url] = g.url } }
        val arr = JSONArray()
        picked.forEachIndexed { i, m ->
            arr.put(JSONObject().put("n", i + 1).put("kind", m.kind).put("url", m.url).apply {
                if (m.w > 0 && m.h > 0) put("size", "${m.w}×${m.h}")
                if (m.alt.isNotBlank()) put("alt", m.alt.take(40))
                if (m.near.isNotBlank()) put("near", m.near.take(60))
            })
        }
        synchronized(said) { said[url] = "读了《${g.title.ifBlank { host }.take(16)}》" + (if (picked.isEmpty()) "" else "，拿到 ${picked.size} 件素材") }
        if (g.text.isBlank() && picked.isEmpty()) return ToolOut("这页没打开，或者打开了但什么都没读到（可能要登录，或者网站拦着）。可以让对方在内置浏览器里打开这页，再点「让她看看这页」。")
        return ToolOut(
            JSONObject().put("title", g.title).put("url", g.url).put("text", g.text.take(5000)).put("materials", arr)
                .put("count", picked.groupingBy { it.kind }.eachCount().entries.joinToString("、") { "${it.key} ${it.value}" }).toString(),
        )
    }

    /** 抖音链接被当成网页打开时：照样解析（见 [VideoParse]），别只拿到标题。 */
    private fun douyin(url: String, i: Douyin.Item): ToolOut {
        ClipLink.offer(url)
        synchronized(said) { said[url] = "解析了抖音「${i.title.take(16)}」" }
        return VideoParse.result(i)
    }

    class Mat(val url: String, val kind: String, val alt: String, val w: Int, val h: Int, val near: String)

    private val JUNK = Regex("avatar|pixel|beacon|track|spacer|blank\\.|loading|placeholder|1x1|/ad[sx]?/|qrcode|emoji", RegexOption.IGNORE_CASE)

    /** 挑素材：去掉头像、广告、跟踪图、太小的；分好种类，好看的排前面。 */
    private fun pick(raw: List<RawAsset>, host: String): List<Mat> {
        val list = raw.asSequence()
            .filter { it.url.startsWith("http") || it.url.startsWith("data:image/svg") }
            .filter { it.url.startsWith("data:") || (!JUNK.containsMatchIn(it.url) && !AdBlock.blocks(it.url, host)) }
            .filter { !(it.w in 1..15 && it.h in 1..15) }
            .map { r ->
                val ext = r.url.substringBefore('?').substringAfterLast('.', "").lowercase()
                val kind = when {
                    r.tag == "video" -> "视频"
                    r.tag == "svg" || r.tag == "icon" || ext == "svg" || ext == "ico" -> "图标"
                    ext == "gif" -> "动图"
                    r.tag == "og" -> "封面"
                    r.tag == "poster" -> "视频封面"
                    r.w in 1..96 && r.h in 1..96 -> "图标"
                    r.tag == "bg" -> "背景图"
                    r.w >= 240 && r.h >= r.w * 1.25 -> "海报"
                    else -> "图片"
                }
                // 内联的 SVG 先存进手机，给她一个短地址
                val u = if (r.url.startsWith("data:")) MaterialStore.saveSvg(r.url) ?: "" else r.url
                Mat(u, kind, r.alt, r.w, r.h, r.near)
            }
            .filter { it.url.isNotEmpty() }
            .toList()
        val order = listOf("封面", "海报", "图片", "视频", "视频封面", "动图", "背景图", "图标")
        val icons = list.filter { it.kind == "图标" }.take(10)
        return (list.filter { it.kind != "图标" }.sortedWith(compareBy<Mat> { order.indexOf(it.kind) }.thenByDescending { it.w * it.h }).take(30) + icons)
    }

    /* ---------- 看一眼 ---------- */

    private suspend fun look(a: JSONObject, vision: Boolean): ToolOut = coroutineScope {
        val arr = a.optJSONArray("urls") ?: JSONArray()
        val urls = (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.startsWith("http") }.distinct().take(6)
        if (urls.isEmpty()) return@coroutineScope ToolOut("没写要看哪几件。")
        val seen = urls.map { u -> async(Dispatchers.IO) { u to runCatching { glance(u, vision) }.getOrElse { Glance("没拿到（${it.message?.take(40) ?: "网站不给"}）", null) } } }.awaitAll()
        val text = seen.mapIndexed { i, (u, g) -> "${i + 1}. $u\n   ${g.note}" }.joinToString("\n")
        ToolOut(
            (if (vision) "下面依次是这几件的样子。\n" else "") + text,
            seen.mapNotNull { it.second.img },
        )
    }

    private class Glance(val note: String, val img: ToolImage?)

    private fun glance(u: String, vision: Boolean): Glance {
        if (Illust.isIllust(u)) return Glance("手绘插画（SVG，放大不糊），颜色就是地址里的 ink（线条）、accent（色块）、paper（头像底色），可以改成作品的颜色", null)
        val local = MaterialStore.localFile(u)
        val (bytes, type) = if (local != null) local.readBytes() to MaterialStore.mimeOf(local.name) else fetch(u, pageOf(u), 20 shl 20)
        if (type.contains("svg")) return Glance("矢量图标（SVG），放大不糊；" + colorsInSvg(String(bytes)), null)
        if (type.startsWith("video")) return Glance("视频（${bytes.size / 1024 / 1024} MB 以内）；画面看不到，有封面就用封面", null)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        val w = o.outWidth; val h = o.outHeight
        if (w <= 0 || h <= 0) return Glance("打不开这张图", null)
        var s = 1; while (w / s > 1536 || h / s > 1536) s *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s }) ?: return Glance("打不开这张图", null)
        val shape = when { h > w * 1.25 -> "竖的"; w > h * 1.25 -> "横的"; else -> "方的" }
        val note = "$w×$h，$shape，主色 ${palette(bmp).joinToString(" ")}" + (if (type.contains("gif")) "，会动的" else "") + (if (bmp.hasAlpha()) "，有透明底" else "")
        val img = if (vision) {
            val k = 768f / maxOf(bmp.width, bmp.height)
            val small = if (k < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true) else bmp
            val out = ByteArrayOutputStream(); small.compress(Bitmap.CompressFormat.JPEG, 80, out)
            ToolImage("image/jpeg", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
        } else null
        return Glance(note, img)
    }

    /** 主色：缩到 24×24，按 4 位一档数，取最多的三种。 */
    private fun palette(b: Bitmap): List<String> {
        val s = Bitmap.createScaledBitmap(b, 24, 24, true)
        val n = HashMap<Int, Int>()
        for (y in 0 until 24) for (x in 0 until 24) {
            val c = s.getPixel(x, y); if ((c ushr 24) < 128) continue
            val k = (c and 0xF0F0F0); n[k] = (n[k] ?: 0) + 1
        }
        return n.entries.sortedByDescending { it.value }.take(3).map { "#%06x".format(it.key or 0x080808) }
    }

    private fun colorsInSvg(x: String) = Regex("#[0-9a-fA-F]{6}\\b|#[0-9a-fA-F]{3}\\b").findAll(x).map { it.value.lowercase() }.distinct().take(4).toList()
        .let { if (it.isEmpty()) "颜色跟着 currentColor 走，可以随你改" else "颜色 " + it.joinToString(" ") }

    /* ---------- 下载 ---------- */

    /** 拿一件素材：带上它所在的网页当来源；被拦就换成不带来源、再换成网站首页，各试一次。 */
    fun fetch(u: String, page: String?, max: Int): Pair<ByteArray, String> {
        val origin = runCatching { URL(u).let { "${it.protocol}://${it.host}/" } }.getOrNull()
        var last: Exception? = null
        for (ref in listOf(page, null, origin).distinct()) {
            try { return get(u, ref, max) } catch (e: Exception) { last = e }
        }
        throw last ?: IllegalStateException("网站不给")
    }

    private fun get(u: String, ref: String?, max: Int): Pair<ByteArray, String> {
        val c = (URL(u.replace("&amp;", "&")).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 20_000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA); setRequestProperty("Accept", "image/avif,image/webp,image/*,video/*,*/*;q=0.8")
            if (ref != null) setRequestProperty("Referer", ref)
            android.webkit.CookieManager.getInstance().getCookie(u)?.let { setRequestProperty("Cookie", it) }
        }
        try {
            if (c.responseCode !in 200..299) error("网站不给（${c.responseCode}）")
            if (c.contentLengthLong > max) error("太大了（${c.contentLengthLong / 1024 / 1024} MB）")
            val out = ByteArrayOutputStream()
            c.inputStream.use { ins ->
                val buf = ByteArray(16 shl 10)
                while (true) { val r = ins.read(buf); if (r < 0) break; out.write(buf, 0, r); if (out.size() > max) error("太大了") }
            }
            return out.toByteArray() to (c.contentType ?: "").lowercase().substringBefore(';').trim()
        } finally { c.disconnect() }
    }
}
