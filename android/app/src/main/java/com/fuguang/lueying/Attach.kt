package com.fuguang.lueying

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 你发给她的照片和文件（输入框加号的三颗球：照片、拍照、文件）。
 * 每件存成 files/attach/<id>/<原名>，消息里记相对路径 "<id>/<原名>"，跟着备份走。
 * 照片存之前转正、缩到长边 1568（Claude 建议的上限），JPEG 86。
 */
object Attach {
    private lateinit var root: File
    private const val EDGE = 1568
    /** 一个文件读出来的字最多带多少给她。 */
    private const val TEXT_MAX = 40_000

    fun init(ctx: Context) { root = File(ctx.filesDir, "attach").apply { mkdirs() } }

    fun file(ref: String): File = File(root, ref)
    fun name(ref: String): String = ref.substringAfter('/')
    fun isImage(ref: String): Boolean = name(ref).substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif")
    fun size(ref: String): Long = file(ref).length()

    /** 能看图的供应商。看不了图的（DeepSeek 等）只告诉她发来了几张。 */
    fun vision(providerId: String) = providerId in setOf("claude", "openai", "gemini", "grok")

    /** 拍照前给相机一个落脚的地方：cache/camera/<时间>.jpg。 */
    fun cameraTarget(ctx: Context): Pair<File, Uri> {
        val f = File(File(ctx.cacheDir, "camera").apply { mkdirs() }, System.currentTimeMillis().toString(36) + ".jpg")
        return f to androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    }

    /** 从相册 / 选文件拿到的一个 Uri，存进来；返回引用，失败返回 null。 */
    suspend fun save(ctx: Context, uri: Uri, asImage: Boolean): String? = withContext(Dispatchers.IO) {
        runCatching {
            val cr = ctx.contentResolver
            val mime = cr.getType(uri).orEmpty()
            var name = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                ?: uri.lastPathSegment?.substringAfterLast('/') ?: "文件"
            name = name.replace(Regex("[/\\\\:*?\"<>|]"), "_").take(80)
            val id = System.currentTimeMillis().toString(36) + (0..999).random().toString(36)
            val dir = File(root, id).apply { mkdirs() }
            if (asImage || mime.startsWith("image/")) {
                val bytes = cr.openInputStream(uri)!!.use { it.readBytes() }
                val jpg = shrink(bytes) ?: return@runCatching null
                val f = File(dir, name.substringBeforeLast('.').ifBlank { "照片" } + ".jpg")
                f.writeBytes(jpg)
                "$id/${f.name}"
            } else {
                val f = File(dir, name)
                cr.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
                "$id/${f.name}"
            }
        }.getOrNull()
    }

    /** 相机拍好的那张：存进来后删掉临时文件。 */
    suspend fun fromCamera(f: File): String? = withContext(Dispatchers.IO) {
        runCatching {
            if (!f.exists() || f.length() == 0L) return@runCatching null
            val jpg = shrink(f.readBytes()) ?: return@runCatching null
            val id = System.currentTimeMillis().toString(36) + (0..999).random().toString(36)
            val dir = File(root, id).apply { mkdirs() }
            val out = File(dir, "拍照.jpg"); out.writeBytes(jpg)
            "$id/${out.name}"
        }.getOrNull().also { runCatching { f.delete() } }
    }

    /** 不发了：从输入框里去掉时连文件一起删。 */
    fun drop(ref: String) { runCatching { file(ref).parentFile?.deleteRecursively() } }

    private fun shrink(bytes: ByteArray): ByteArray? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0) return null
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= EDGE) s *= 2
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        val k = EDGE.toFloat() / maxOf(bmp.width, bmp.height)
        val rot = runCatching {
            when (ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90; ExifInterface.ORIENTATION_ROTATE_180 -> 180; ExifInterface.ORIENTATION_ROTATE_270 -> 270; else -> 0
            }
        }.getOrDefault(0)
        if (k < 1f || rot != 0) {
            val m = Matrix().apply { if (k < 1f) postScale(k, k); if (rot != 0) postRotate(rot.toFloat()) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 86, it) }.toByteArray()
    }

    /**
     * 一条你的消息带着照片、文件时，发给供应商的 content。
     * full = 最近两条带东西的消息：图原样带上；更早的只留一句「之前发过」，免得每轮都重发。
     */
    fun content(m: ChatMsg, anthropic: Boolean, vision: Boolean, full: Boolean): Any {
        val parts = JSONArray()
        val notes = StringBuilder()
        val imgs = m.files.filter(::isImage)
        val docs = m.files.filterNot(::isImage)
        if (imgs.isNotEmpty()) when {
            !full -> notes.append("（这句话之前他发来过 ${imgs.size} 张照片。）\n")
            !vision -> imgs.forEachIndexed { k, r ->
                val look = AttachLook.seen(r)
                val which = if (imgs.size > 1) "第 ${k + 1} 张" else "一张"
                if (look != null) notes.append("（他发来了${which}照片，你看到的是：）\n").append(look).append("\n（照片到这里。）\n")
                else notes.append("（他发来了${which}照片，可你这会儿看不清。如实告诉他。）\n")
            }
            else -> imgs.forEach { r -> runCatching { image(parts, file(r).readBytes(), "image/jpeg", anthropic) } }
        }
        docs.forEach { r ->
            val f = file(r)
            val n = name(r)
            if (!full) { notes.append("（之前他发来过文件《$n》。）\n"); return@forEach }
            val pdfPages = if (n.endsWith(".pdf", true) && vision && !anthropic) AttachRead.pdfPages(f, 6) else emptyList()
            when {
                n.endsWith(".pdf", true) && anthropic && f.length() < 24_000_000 -> {
                    parts.put(JSONObject().put("type", "document").put("source", JSONObject().put("type", "base64").put("media_type", "application/pdf").put("data", b64(f.readBytes()))))
                    notes.append("（他发来了文件《$n》，就是上面这份。）\n")
                }
                pdfPages.isNotEmpty() -> {
                    pdfPages.forEach { image(parts, it, "image/jpeg", anthropic) }
                    notes.append("（他发来了文件《$n》，上面是前 ${pdfPages.size} 页的样子。）\n")
                }
                else -> {
                    val t = AttachRead.text(f)
                    if (t.isNullOrBlank()) notes.append("（他发来了文件《$n》，${kb(f.length())}，这种文件你打不开。如实告诉他。）\n")
                    else notes.append("（他发来了文件《$n》，里面写的是：）\n").append(t.take(TEXT_MAX)).append(if (t.length > TEXT_MAX) "\n（后面还有，没带全。）\n" else "\n").append("（文件到这里。）\n")
                }
            }
        }
        val text = (notes.toString() + m.text).trim().ifEmpty { "（看看这个。）" }
        if (parts.length() == 0) return text
        parts.put(JSONObject().put("type", "text").put("text", text))
        return parts
    }

    private fun image(parts: JSONArray, bytes: ByteArray, mime: String, anthropic: Boolean) {
        if (anthropic) parts.put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", mime).put("data", b64(bytes))))
        else parts.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:$mime;base64," + b64(bytes))))
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    fun kb(n: Long): String = if (n >= 1_048_576) "%.1f MB".format(n / 1_048_576f) else "${(n / 1024).coerceAtLeast(1)} KB"
}
