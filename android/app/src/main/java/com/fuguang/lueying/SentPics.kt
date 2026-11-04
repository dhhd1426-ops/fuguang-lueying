package com.fuguang.lueying

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.LruCache
import androidx.compose.runtime.mutableStateMapOf
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 她直接发图片（第 385 版，原型 https://claude.ai/artifact/MkwUcuJbKmQhytTfb2GNvE 第 1 版；第 387 版改成第 5 版：
 * 每张一格先弹跳、拿到变原图比例、小球往上弹把图带出来；拿不到的缩掉不存；SVG 用 AndroidSVG 画成 PNG）。
 * 图从哪来：网页上的图片地址（搜网页、打开网页时拿到的）、data:image、photo:1（对方最近发来的那张照片）、她以前发过的 pic: 图。
 * 下载下来存在 files/pics/<批次>/<序号>（原样字节，不带扩展名，用的时候看文件头）；说明存在旁边的 <序号>.cap。
 * 消息里和羽的笔记图一样记在 imgs 里，写成 "pic:<批次>/<序号>"；回复下面画成一张大图或两列方块（NoteImages.kt 的 NoteImageRow）。
 * PDF / Word 里的 ![说明](地址) 也走这里的 fetch + prepare 把图拿下来放进去（SentFiles.kt）。
 */
object SentPics {
    const val TOOL = "send_image"
    const val PREFIX = "pic:"
    private const val MAX_BYTES = 20 * 1024 * 1024

    private var root: File? = null
    fun init(ctx: Context) {
        if (root == null) root = File(ctx.applicationContext.filesDir, "pics").apply { mkdirs() }
    }

    /** 这次打开 App 后发的：null 正在拿，"" 好了，别的是没拿到的原因。以前发的不在这里（文件在就是好的）。 */
    val state = mutableStateMapOf<String, String?>()
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) { override fun sizeOf(key: String, value: Bitmap) = value.byteCount }
    /**
     * 动效的时钟记在图上，不记在格子上（同文件卡）：她说完话、回复收进聊天记录时格子会重画一遍，接着跳、接着带图，不从头来。
     * born：格子放上去那一刻；ready：图拿到并解好（可以变比例）；gone：拿不到；都是 System.nanoTime。fresh：这次刚发、还没带出来的。
     */
    val born = HashMap<String, Long>()
    val ready = HashMap<String, Long>()
    val gone = HashMap<String, Long>()
    val fresh = HashSet<String>()
    /** 图的宽高（拿到时记下；以前发的第一次画时从文件头读）。 */
    val dims = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Int>>()
    /** 透明底的图（logo 这类）：垫浅色纸底、整张放下不裁。拿到时算好，存在旁边的 .clear。 */
    private val clearOf = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    fun clear(ref: String): Boolean = clearOf.getOrPut(ref) { file(ref)?.let { File(it.path + ".clear").exists() } ?: false }
    /** 同一个地址两分钟内又发：整轮重发时她会再调一次，不再多一张。 */
    private val recent = HashMap<String, Pair<String, Long>>()

    fun isRef(r: String) = r.startsWith(PREFIX)
    fun file(ref: String): File? = root?.let { File(it, ref.removePrefix(PREFIX)) }
    fun caption(ref: String): String = runCatching { File(file(ref)!!.path + ".cap").readText() }.getOrDefault("")
    fun ok(ref: String): Boolean = if (state.containsKey(ref)) state[ref] == "" else file(ref)?.exists() == true
    fun fail(ref: String): String? = if (state.containsKey(ref)) state[ref]?.takeIf { it.isNotEmpty() } else if (file(ref)?.exists() == true) null else "这张图不在这台手机上了"

    /* ---------- 给模型 ---------- */

    val spec get() = ToolSpec(
        TOOL,
        "直接发图片给对方，一次最多 9 张，马上出现在你这条回复下面，对方点开能看大图、存到相册、分享。" +
            "url 写图片地址：网页上的图片地址（照 web_search、open_page 拿到的原样写，PNG、JPG、WebP、GIF、SVG 都行），或 photo:1（对方最近发来的那张照片，photo:2 是再往前一张）。" +
            "拿不到的图不会出现在聊天里，结果里会告诉你哪几张没拿到，你在回复里如实说。" +
            "caption 是图下面一行小字，可以不写。",
        JSONObject().put("type", "object").put(
            "properties",
            JSONObject().put(
                "images",
                JSONObject().put("type", "array").put(
                    "items",
                    JSONObject().put("type", "object").put(
                        "properties",
                        JSONObject()
                            .put("url", JSONObject().put("type", "string").put("description", "图片地址"))
                            .put("caption", JSONObject().put("type", "string").put("description", "图下面的一行说明，几个字")),
                    ).put("required", JSONArray(listOf("url"))),
                ),
            ),
        ).put("required", JSONArray(listOf("images"))),
    )

    fun system(who: String) =
        "$who 想看一张图、要几张图，用 $TOOL 直接发到聊天里；要把图放进 PDF 或 Word，就在 send_file 的正文里写 ![说明](图片地址)，图会放进文件。" +
            "图片地址要真实拿到过的（搜到的、打开网页看到的、他发来的照片 photo:1），别自己编。发了就说一两句，不用再贴地址。"

    /** 跑工具：先把图的位置放上（正在拿），再下载。onCard 拿到要记进消息的引用。photos：对方发来的照片，最近的在前。 */
    suspend fun run(a: JSONObject, photos: List<File>, onCard: (String) -> Unit): ToolOut {
        val d = root ?: return ToolOut("图片没地方存。")
        val arr = a.optJSONArray("images") ?: return ToolOut("images 是空的：每张写清 url 再发一次。")
        val items = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.filter { it.optString("url").isNotBlank() }
        if (items.isEmpty()) return ToolOut("images 是空的：每张写清 url 再发一次。")
        val batch = System.currentTimeMillis().toString(36) + (100..999).random()
        val dir = File(d, batch).apply { mkdirs() }
        val now = System.currentTimeMillis()
        recent.entries.removeAll { now - it.value.second > 120_000 }
        var again = 0
        val jobs = items.take(9).mapIndexedNotNull { k, o ->
            val url = o.optString("url").trim()
            recent[url]?.let { (old, _) -> if (ok(old) || state[old] == null && state.containsKey(old)) { again++; return@mapIndexedNotNull null } }
            val ref = "$PREFIX$batch/${k + 1}"
            recent[url] = ref to now
            val cap = o.optString("caption").trim().take(60)
            if (cap.isNotEmpty()) runCatching { File(dir, "${k + 1}.cap").writeText(cap) }
            withContext(Dispatchers.Main) { state[ref] = null; fresh += ref; born[ref] = System.nanoTime(); onCard(ref) }
            Triple(ref, url, k + 1)
        }
        // 几张一起拿，谁先拿到谁先带出来
        val errs = kotlinx.coroutines.coroutineScope {
            jobs.map { (ref, url, no) -> async { no to one(ref, url, no, dir, photos) } }.awaitAll()
        }
        val bad = errs.mapNotNull { (no, err) -> err?.let { "第 $no 张（$it）" } }
        val good = jobs.size - bad.size
        return ToolOut(
            (if (good > 0) "发好了 $good 张，图在你这条回复下面，说一两句就好，不用再贴地址。" else "") +
                (if (bad.isEmpty()) "" else "没拿到：" + bad.joinToString("、") + "。这几张不会出现在聊天里，如实告诉对方哪几张拿不到，或换一个地址再发。") +
                (if (again > 0) "有 $again 张刚才已经发过了，不用再发。" else "") +
                (if (items.size > 9) "一次最多 9 张，后面的没发。" else ""),
        )
    }

    /** 拿一张：下载、解好放进缓存、看是不是透明底；返回没拿到的原因，拿到了是 null。 */
    private suspend fun one(ref: String, url: String, no: Int, dir: File, photos: List<File>): String? {
            val err = withContext(Dispatchers.IO) {
                runCatching {
                    val f = File(dir, no.toString())
                    f.writeBytes(fetch(url, photos))
                    // 先解好放进缓存，格子拿到就能变比例、带图，不再等一次解码
                    val b = bitmap(ref, 1080) ?: error("解不开这张图")
                    dims[ref] = b.width to b.height
                    val c = see(b)
                    clearOf[ref] = c
                    if (c) File(f.path + ".clear").writeText("1")
                    null
                }.getOrElse { e -> (e.message ?: e.javaClass.simpleName).take(40) }
            }
            withContext(Dispatchers.Main) {
                if (err == null) ready[ref] = System.nanoTime() else gone[ref] = System.nanoTime()
                state[ref] = err ?: ""
            }
            return err
    }

    /* ---------- 拿图 ---------- */

    /** 把一个地址变成图片字节；不是图片就报错。 */
    suspend fun fetch(url: String, photos: List<File> = emptyList()): ByteArray = withContext(Dispatchers.IO) {
        val u = url.trim()
        val bytes = when {
            u.startsWith("data:image/", true) -> Base64.decode(u.substringAfter(','), Base64.DEFAULT)
            u.startsWith("photo:", true) -> {
                val n = u.substringAfter(':').trim().toIntOrNull() ?: 1
                photos.getOrNull(n - 1)?.takeIf { it.exists() }?.readBytes() ?: error("对方没发过第 $n 张照片")
            }
            isRef(u) -> file(u)?.takeIf { it.exists() }?.readBytes() ?: error("这张图不在了")
            u.startsWith("http://", true) || u.startsWith("https://", true) -> http(u)
            else -> error("不是图片地址")
        }
        if (bytes.size < 16) error("拿到的是空的")
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth > 0 && o.outHeight > 0) return@withContext bytes
        if (String(bytes, 0, minOf(bytes.size, 2048)).contains("<svg", true)) svgPng(bytes) else error("拿到的不是图片")
    }

    /** SVG 画成 PNG：长边 1024（没写尺寸的按 512 见方），透明底。用 AndroidSVG（App 里本来就带着）。 */
    private fun svgPng(bytes: ByteArray): ByteArray {
        val svg = runCatching { com.caverock.androidsvg.SVG.getFromString(String(bytes)) }.getOrElse { error("SVG 解不开") }
        val vb = svg.documentViewBox
        val dw = svg.documentWidth; val dh = svg.documentHeight
        if (vb == null && dw > 0 && dh > 0) svg.setDocumentViewBox(0f, 0f, dw, dh)
        val ratio = when {
            vb != null && vb.width() > 0 && vb.height() > 0 -> vb.width() / vb.height()
            dw > 0 && dh > 0 -> dw / dh
            else -> 1f
        }.coerceIn(.2f, 5f)
        val long = if (vb == null && (dw <= 0 || dh <= 0)) 512 else 1024
        val w = if (ratio >= 1f) long else (long * ratio).toInt().coerceAtLeast(1)
        val h = if (ratio >= 1f) (long / ratio).toInt().coerceAtLeast(1) else long
        svg.setDocumentWidth(w.toFloat()); svg.setDocumentHeight(h.toFloat())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        svg.renderToCanvas(android.graphics.Canvas(bmp))
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    /** 四个角都差不多透明就算透明底。 */
    private fun see(b: Bitmap): Boolean {
        if (!b.hasAlpha()) return false
        val x = b.width - 1; val y = b.height - 1
        return listOf(b.getPixel(0, 0), b.getPixel(x, 0), b.getPixel(0, y), b.getPixel(x, y)).count { (it ushr 24) < 200 } >= 3
    }

    /** 下载：带浏览器的 UA 和来源页，自己跟着跳转（http ⇄ https 的跳转系统不跟）。 */
    private fun http(start: String): ByteArray {
        var url = start
        repeat(5) {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000; readTimeout = 20_000; instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36")
                setRequestProperty("Accept", "image/webp,image/png,image/jpeg,image/svg+xml,image/*;q=0.8,*/*;q=0.5")
                runCatching { val h = URL(url); setRequestProperty("Referer", "${h.protocol}://${h.host}/") }
            }
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: error("跳转没给地址")
                    url = URL(URL(url), loc).toString()
                    return@repeat
                }
                if (code !in 200..299) error("网页回了 $code")
                if (c.contentLengthLong > MAX_BYTES) error("图太大")
                c.inputStream.use { ins ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = ins.read(buf); if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > MAX_BYTES) error("图太大")
                    }
                    return out.toByteArray()
                }
            } finally { c.disconnect() }
        }
        error("跳转太多次")
    }

    /** 放进文件用的图：长边最多 1600，带透明的存 PNG，别的存 JPEG。 */
    class DocPic(val bytes: ByteArray, val w: Int, val h: Int, val png: Boolean)

    fun prepare(bytes: ByteArray): DocPic? = runCatching {
        val bmp = decode(bytes, 1600) ?: return null
        val png = bmp.hasAlpha()
        val out = ByteArrayOutputStream()
        bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 88, out)
        DocPic(out.toByteArray(), bmp.width, bmp.height, png).also { bmp.recycle() }
    }.getOrNull()

    /** 长边缩到 max 以内（先按 2 的倍数抽样，再精确缩）。 */
    fun decode(bytes: ByteArray, max: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0) return null
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= max) s *= 2
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        val long = maxOf(b.width, b.height)
        if (long <= max) return b
        val k = max.toFloat() / long
        return Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true).also { if (it !== b) b.recycle() }
    }

    /** 图的宽高（不解码），拿不到是 null。 */
    suspend fun size(ref: String): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        val f = file(ref)?.takeIf { it.exists() } ?: return@withContext null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth > 0) o.outWidth to o.outHeight else null
    }

    /** 缓存里已经有的（不读文件）。 */
    fun cached(ref: String, max: Int): Bitmap? = cache.get("$ref@$max")

    suspend fun bitmap(ref: String, max: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = "$ref@$max"
        cache.get(key) ?: file(ref)?.takeIf { it.exists() }?.readBytes()?.let { decode(it, max) }?.also { cache.put(key, it) }
    }

    /* ---------- 看大图里的两件事 ---------- */

    private fun ext(f: File): String {
        val h = runCatching { f.inputStream().use { s -> ByteArray(12).also { s.read(it) } } }.getOrDefault(ByteArray(12))
        return when {
            h[0] == 0x89.toByte() && h[1] == 'P'.code.toByte() -> "png"
            h[0] == 'G'.code.toByte() && h[1] == 'I'.code.toByte() -> "gif"
            h[0] == 'R'.code.toByte() && h[8] == 'W'.code.toByte() -> "webp"
            else -> "jpg"
        }
    }

    private fun mime(e: String) = when (e) { "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"; else -> "image/jpeg" }

    /** 存到相册（图片 / 浮光掠影）。成功返回 null，否则返回原因。 */
    suspend fun save(ctx: Context, ref: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val f = file(ref)?.takeIf { it.exists() } ?: error("图不在了")
            val e = ext(f)
            val r = ctx.contentResolver
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "Vignette-" + ref.removePrefix(PREFIX).replace('/', '-') + "." + e)
                put(MediaStore.MediaColumns.MIME_TYPE, mime(e))
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/浮光掠影")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val u = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: error("相册存不进去")
            try {
                r.openOutputStream(u)!!.use { os -> f.inputStream().use { it.copyTo(os) } }
                r.update(u, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (t: Throwable) { r.delete(u, null, null); throw t }
            null
        }.getOrElse { t -> (t.message ?: t.javaClass.simpleName).take(30) }
    }

    /** 分享：先复制一份带扩展名的到 cache/share/，别的 App 才认得是什么图。 */
    fun share(ctx: Context, ref: String) {
        runCatching {
            val f = file(ref)?.takeIf { it.exists() } ?: return
            val e = ext(f)
            val out = File(File(ctx.cacheDir, "share").apply { mkdirs() }, "Vignette-" + ref.removePrefix(PREFIX).replace('/', '-') + "." + e)
            f.copyTo(out, overwrite = true)
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", out)
            val send = Intent(Intent.ACTION_SEND).setType(mime(e)).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(Intent.createChooser(send, "分享图片").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
