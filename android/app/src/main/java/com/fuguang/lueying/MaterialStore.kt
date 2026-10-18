package com.fuguang.lueying

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** 一件存进手机的素材：文件名、原来的地址、种类（图 / 视频 / 图标）。 */
data class StoredMat(val file: String, val from: String)

/**
 * 作品里用到的网上素材，做作品时下载到 files/works/media，作品里换成 https://work.fuguang.local/media/<文件>，
 * 打开作品时由 WorkViewer 从这里取（serve）。分享 .html 时换成 data: 内嵌（inline），发给谁都看得到。
 */
object MaterialStore {
    const val HOST = "work.fuguang.local"
    private const val PREFIX = "https://$HOST/media/"
    private var dir: File? = null
    fun init(ctx: Context) { dir = File(ctx.applicationContext.filesDir, "works/media").apply { mkdirs() } }

    fun localFile(u: String): File? = if (u.startsWith(PREFIX)) dir?.let { File(it, u.removePrefix(PREFIX).substringBefore('?')) }?.takeIf { it.exists() } else null

    fun mimeOf(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"; "avif" -> "image/avif"
        "svg" -> "image/svg+xml"; "ico" -> "image/x-icon"; "mp4" -> "video/mp4"; "webm" -> "video/webm"; "mov" -> "video/quicktime"
        else -> "application/octet-stream"
    }

    private fun extOf(type: String, u: String): String? = when {
        type.contains("jpeg") || type.contains("jpg") -> "jpg"; type.contains("png") -> "png"; type.contains("gif") -> "gif"
        type.contains("webp") -> "webp"; type.contains("avif") -> "avif"; type.contains("svg") -> "svg"; type.contains("icon") -> "ico"
        type.contains("mp4") -> "mp4"; type.contains("webm") -> "webm"; type.contains("quicktime") -> "mov"
        else -> u.substringBefore('?').substringAfterLast('.', "").lowercase().takeIf { it in MEDIA_EXT }
    }

    private val MEDIA_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "avif", "svg", "ico", "mp4", "webm", "mov")
    private val MEDIA_URL = Regex("\\.(jpe?g|png|gif|webp|avif|svg|ico|mp4|webm|mov)(\\?|#|$)", RegexOption.IGNORE_CASE)

    private fun hash(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    /** 页面里内联的 SVG 图标：先存下来，给她一个短地址。 */
    fun saveSvg(data: String): String? = runCatching {
        val d = dir ?: return null
        val xml = String(Base64.decode(data.substringAfter("base64,"), Base64.DEFAULT))
        val name = hash(xml) + ".svg"
        File(d, name).takeIf { !it.exists() }?.writeText(xml)
        PREFIX + name
    }.getOrNull()

    /* ---------- 做作品时：把素材存进手机 ---------- */

    class Result(val text: String, val used: List<StoredMat>, val missed: List<String>)

    private val URL_TOKEN = Regex("https?://[^\\s\"'<>()\\\\`]+")
    private val ATTR_BEFORE = Regex("(?:src|srcset|poster|data-src|href|content)\\s*=\\s*[\"']?[^\"'<>]*$|url\\(\\s*[\"']?$|!\\[[^\\]]*]\\($", RegexOption.IGNORE_CASE)

    /** 文字里（HTML、Markdown）出现的素材地址都存进手机、换成本地地址。 */
    suspend fun localize(text: String): Result = localizeAll(listOf(text)).let { Result(it.first[0], it.second, it.third) }

    /** 画板、幻灯片这种 JSON 原稿：每个字符串里的素材地址都换掉。 */
    suspend fun localizeJson(v: Any): Pair<List<StoredMat>, List<String>> {
        val holders = ArrayList<Triple<Any, Any, String>>()
        fun walk(x: Any?) {
            when (x) {
                is JSONObject -> x.keys().forEach { k -> val c = x.opt(k); if (c is String) holders += Triple(x, k, c) else walk(c) }
                is JSONArray -> for (i in 0 until x.length()) { val c = x.opt(i); if (c is String) holders += Triple(x, i, c) else walk(c) }
            }
        }
        walk(v)
        val (texts, used, missed) = localizeAll(holders.map { it.third }, wholeIsAsset = true)
        holders.forEachIndexed { i, (o, k, old) ->
            if (texts[i] != old) when (o) { is JSONObject -> o.put(k as String, texts[i]); is JSONArray -> o.put(k as Int, texts[i]) }
        }
        return used to missed
    }

    private suspend fun localizeAll(texts: List<String>, wholeIsAsset: Boolean = false): Triple<List<String>, List<StoredMat>, List<String>> = coroutineScope {
        val d = dir ?: return@coroutineScope Triple(texts, emptyList(), emptyList())
        // 先找出要存的地址
        val want = LinkedHashSet<String>()
        texts.forEach { t ->
            URL_TOKEN.findAll(t).forEach { m ->
                val u = m.value.trimEnd('.', ',', ';', ')')
                if (u.startsWith(PREFIX)) return@forEach
                // Unsplash 要求直接用它的链接：不存，只告诉它一声
                if (Gallery.keepRemote(u)) { Gallery.used(u); return@forEach }
                val before = t.substring(maxOf(0, m.range.first - 120), m.range.first)
                val tag = before.substringAfterLast('<', "").substringBefore(' ').lowercase()
                if (tag in setOf("script", "link", "a", "iframe", "form", "meta")) return@forEach
                if (Materials.seen(u) || MEDIA_URL.containsMatchIn(u) || ATTR_BEFORE.containsMatchIn(before) || (wholeIsAsset && t.trim() == u)) want += u
            }
        }
        val gate = Semaphore(4)
        var total = 0L
        val got = want.take(60).map { u ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    u to runCatching {
                        val name0 = hash(u)
                        d.listFiles { f -> f.name.startsWith(name0) }?.firstOrNull()?.let { return@runCatching it.name }
                        val isVideo = Regex("\\.(mp4|webm|mov)", RegexOption.IGNORE_CASE).containsMatchIn(u)
                        val (bytes, type) = Materials.fetch(u, Materials.pageOf(u), if (isVideo) 40 shl 20 else 20 shl 20)
                        if (!(type.startsWith("image") || type.startsWith("video") || type.contains("octet"))) return@runCatching null
                        val ext = extOf(type, u) ?: return@runCatching null
                        synchronized(this@MaterialStore) { total += bytes.size; if (total > (150L shl 20)) error("素材太多了") }
                        val (body, e2) = shrink(bytes, ext)
                        val name = "$name0.$e2"
                        File(d, name).writeBytes(body)
                        name
                    }.getOrElse { "!" }
                }
            }
        }.awaitAll().toMap()
        val map = got.filterValues { it != null && it != "!" }.mapValues { PREFIX + it.value }
        val out = texts.map { t -> if (map.isEmpty()) t else URL_TOKEN.replace(t) { m -> val u = m.value.trimEnd('.', ',', ';', ')'); map[u]?.let { it + m.value.removePrefix(u) } ?: m.value } }
        Triple(out, map.map { (u, l) -> StoredMat(l.removePrefix(PREFIX), u) }, got.filterValues { it == "!" }.keys.toList())
    }

    /** 太大的照片缩一缩（最长边 2048），省地方；动图、矢量、视频原样。 */
    private fun shrink(b: ByteArray, ext: String): Pair<ByteArray, String> {
        if (b.size < (2_500 shl 10) || ext !in setOf("jpg", "png", "webp")) return b to ext
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(b, 0, b.size, o)
        var s = 1; while (o.outWidth / s > 2048 || o.outHeight / s > 2048) s *= 2
        val bmp = BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = s }) ?: return b to ext
        val out = ByteArrayOutputStream()
        return if (bmp.hasAlpha()) { bmp.compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray() to "png" }
        else { bmp.compress(Bitmap.CompressFormat.JPEG, 86, out); out.toByteArray() to "jpg" }
    }

    /* ---------- 打开作品时 ---------- */

    /** WorkViewer 的 shouldInterceptRequest 调：本地素材从这里给，视频支持拖动（Range）。 */
    fun serve(req: WebResourceRequest?): WebResourceResponse? {
        val u = req?.url ?: return null
        if (u.host != HOST || u.path?.startsWith("/media/") != true) return null
        val f = dir?.let { File(it, u.lastPathSegment.orEmpty()) }?.takeIf { it.exists() } ?: return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), null)
        val mime = mimeOf(f.name)
        val headers = HashMap<String, String>().apply { put("Access-Control-Allow-Origin", "*"); put("Accept-Ranges", "bytes"); put("Cache-Control", "max-age=31536000") }
        val range = req.requestHeaders?.entries?.firstOrNull { it.key.equals("Range", true) }?.value
        val m = range?.let { Regex("bytes=(\\d*)-(\\d*)").find(it) }
        if (m != null) {
            val len = f.length()
            val start = m.groupValues[1].toLongOrNull() ?: 0L
            val end = (m.groupValues[2].toLongOrNull() ?: (len - 1)).coerceAtMost(len - 1)
            if (start <= end) {
                val ins = FileInputStream(f); ins.skip(start)
                headers["Content-Range"] = "bytes $start-$end/$len"; headers["Content-Length"] = (end - start + 1).toString()
                return WebResourceResponse(mime, null, 206, "Partial Content", headers, LimitedStream(ins, end - start + 1))
            }
        }
        headers["Content-Length"] = f.length().toString()
        return WebResourceResponse(mime, null, 200, "OK", headers, FileInputStream(f))
    }

    private class LimitedStream(private val s: java.io.InputStream, private var left: Long) : java.io.InputStream() {
        override fun read(): Int = if (left <= 0) -1 else s.read().also { if (it >= 0) left-- }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val r = s.read(b, off, minOf(len.toLong(), left).toInt()); if (r > 0) left -= r; return r
        }
        override fun close() = s.close()
    }

    /** 分享 .html：本地素材换成 data: 内嵌；太大的（视频）换回原来的网址。 */
    fun inline(html: String, used: List<StoredMat>): String {
        val d = dir ?: return html
        var budget = 40L shl 20
        val back = used.associate { it.file to it.from }
        return Regex(Regex.escape(PREFIX) + "([0-9a-f]{16}\\.[a-z0-9]+)").replace(html) { m ->
            val name = m.groupValues[1]; val f = File(d, name)
            if (f.exists() && f.length() <= (8L shl 20) && f.length() <= budget && !mimeOf(name).startsWith("video")) {
                budget -= f.length(); "data:${mimeOf(name)};base64," + Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
            } else back[name] ?: m.value
        }
    }

    /** 作品卡上叠的那几张：只挑照片类（能解出画面的）。 */
    fun faces(used: List<StoredMat>, n: Int = 3): List<File> =
        used.mapNotNull { m -> dir?.let { File(it, m.file) }?.takeIf { it.exists() && it.extension in setOf("jpg", "png", "webp", "gif") && it.length() > 6_000 } }.take(n)

    /** 页面里真正用到的本地素材（按出现先后），带上它原来的地址。 */
    fun refs(page: String, known: List<StoredMat>): List<StoredMat> {
        val from = known.associate { it.file to it.from }
        return Regex(Regex.escape(PREFIX) + "([0-9a-f]{16}\\.[a-z0-9]+)").findAll(page).map { it.groupValues[1] }.distinct()
            .map { StoredMat(it, from[it].orEmpty()) }.toList()
    }

    fun usedOf(o: JSONObject): List<StoredMat> = o.optJSONArray("materials")?.let { a ->
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { StoredMat(it.optString("f"), it.optString("u")) } }
    }.orEmpty()

    fun toJson(used: List<StoredMat>) = JSONArray().apply { used.distinctBy { it.file }.forEach { put(JSONObject().put("f", it.file).put("u", it.from)) } }

    suspend fun thumb(f: File, px: Int): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(f.path, o)
            var s = 1; while (o.outWidth / (s * 2) >= px && o.outHeight / (s * 2) >= px) s *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
        }.getOrNull()
    }
}
