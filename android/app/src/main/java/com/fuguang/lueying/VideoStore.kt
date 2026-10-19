package com.fuguang.lueying

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 一条抖音。parse：0 正在认 · 1 认出了 · 2 认不出。save：0 没存 · 1 在存 · 2 存好了 · 3 没成。pick：0 视频（图集是全部原图）· 1 只要声音 · 2 封面。 */
class Clip(val link: String, val at: Long = System.currentTimeMillis()) {
    var item by mutableStateOf<Douyin.Item?>(null)
    var parsedAt = 0L
    var parse by mutableIntStateOf(0)
    var why by mutableStateOf("")
    var pick by mutableIntStateOf(0)
    var save by mutableIntStateOf(0)
    var frac by mutableFloatStateOf(0f)
    var out by mutableStateOf("")          // 存好了：content:// 地址（图集是第一张）
    var outMime by mutableStateOf("")
    var saveWhy by mutableStateOf("")
    var cover by mutableStateOf<ImageBitmap?>(null)
    /** 每存好一次加一：卡片和长条看到就迸光点。 */
    var doneTick by mutableIntStateOf(0)
    val title get() = item?.title ?: "这条抖音"
}

/**
 * 抖音的唯一事实来源：长条、聊天里的卡、她的 parse_video / save_video 都读写这里，谁都不单独持有结果。
 * 一条链接一条 Clip；解析结果缓存 1 小时（无水印地址有时效），过期了存之前静默重解析。
 * 存的进度写回 Clip，所以你点卡上的「存」和她调 save_video 是同一个动作、同一份进度。
 */
object VideoStore {
    private const val FRESH_MS = 3600_000L
    val clips = mutableStateMapOf<String, Clip>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val parsing = HashMap<String, Deferred<Unit>>()
    private val saving = HashMap<String, Deferred<String>>()
    private lateinit var app: Context
    private var prefs: Prefs? = null

    fun init(ctx: Context, p: Prefs) { app = ctx.applicationContext; prefs = p; load() }

    fun clip(link: String?): Clip? = link?.let { clips[it] }

    /** 文字里有抖音链接就收下（没解析过就开始解析），返回那条；没有返回 null。 */
    fun offer(text: String): Clip? {
        val l = Douyin.link(text) ?: return null
        val c = clips.getOrPut(l) { Clip(l) }
        if (c.parse != 1 || c.item == null) parseAsync(c)
        return c
    }

    private fun parseAsync(c: Clip): Deferred<Unit> = parsing[c.link]?.takeIf { it.isActive } ?: scope.async {
        c.parse = 0; c.why = ""
        runCatching { Douyin.parse(c.link) }
            .onSuccess { i -> c.item = i; c.parsedAt = System.currentTimeMillis(); c.parse = 1; persist(); if (c.cover == null) i.cover?.let { u -> c.cover = withContext(Dispatchers.IO) { picture(u) } } }
            .onFailure { c.parse = 2; c.why = it.message.orEmpty() }
        Unit
    }.also { parsing[c.link] = it }

    /** 等解析完；fresh 时过期了重来一次。 */
    suspend fun ready(c: Clip, fresh: Boolean = false) {
        val stale = fresh && c.item != null && System.currentTimeMillis() - c.parsedAt > FRESH_MS
        if (stale || c.item == null || parsing[c.link]?.isActive == true) parseAsync(c).await()
    }

    /** 存到相册。同一条正在存就等那一次，不重开。返回一句结果（给她回话用）。 */
    fun save(c: Clip, act: Activity?, pick: Int = c.pick): Deferred<String> {
        saving[c.link]?.takeIf { it.isActive }?.let { return it }
        if (c.save == 2 && pick == c.pick) return scope.async { "之前存过了，在相册的浮光掠影里，卡上能点开" }
        c.pick = pick; c.save = 1; c.frac = 0f; c.saveWhy = ""
        return scope.async {
            try {
                ready(c, fresh = true)
                val i = c.item
                when {
                    pick == 2 -> { val u = i?.cover ?: error("这条没有封面"); c.out = saveImage(u, c.title, "封面"); c.outMime = "image/jpeg"; c.frac = 1f }
                    i != null && i.images.isNotEmpty() && pick == 0 -> { c.out = saveImages(c, i); c.outMime = "image/jpeg" }
                    else -> c.out = saveVideo(c, act, audio = pick == 1)
                }
                c.save = 2; c.doneTick++; persist()
                "存好了，在相册的浮光掠影里，卡上点「打开」就能看"
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { c.save = 3; c.saveWhy = e.message?.take(40) ?: "没下成"; "没成：${c.saveWhy}" }
        }.also { saving[c.link] = it }
    }

    /** 她调 save_video：最多等 60 秒。 */
    suspend fun saveAndWait(c: Clip, act: Activity?, pick: Int): String =
        withTimeoutOrNull(60_000) { save(c, act, pick).await() } ?: "还在下，卡上看得到进度"

    // ———— 存 ————

    private suspend fun saveVideo(c: Clip, act: Activity?, audio: Boolean): String {
        val i = c.item
        val title = c.title
        val q = if (audio) "只要声音" else "720p"
        if (i?.video != null) {
            viaDownloads(c, act, title, Sniffer.Media("file", i.video, headers = Douyin.headers(), page = i.pageUrl, mime = "video/mp4", name = "$title.mp4"), q, null, audio)?.let { return it }
            // 地址过期：重新解析一次再试
            c.parsedAt = 0; ready(c, fresh = true)
            c.item?.video?.let { v -> viaDownloads(c, act, title, Sniffer.Media("file", v, headers = Douyin.headers(), page = i.pageUrl, mime = "video/mp4", name = "$title.mp4"), q, null, audio)?.let { return it } }
        }
        val page = i?.pageUrl ?: c.link
        return viaDownloads(c, act, title, Sniffer.Media("ytdlp", page, headers = Douyin.headers(), page = page), "最清楚", "0", audio) ?: error(c.saveWhy.ifBlank { "这条下不下来" })
    }

    /** 交给下载列表（通知栏、下载页都看得到），跟着它的进度走；成了返回相册地址，没成返回 null。 */
    private suspend fun viaDownloads(c: Clip, act: Activity?, title: String, m: Sniffer.Media, quality: String, variant: String?, audio: Boolean): String? {
        Downloads.start(act, title, m, quality, variant, audioOnly = audio, gallery = true, wifiOnly = false)
        val d = Downloads.list.firstOrNull() ?: return null
        while (true) {
            c.frac = d.frac
            when (d.state) {
                3 -> { c.outMime = if (audio) "audio/mp4" else "video/mp4"; return d.out }
                4 -> { c.saveWhy = d.error; Downloads.delete(d); return null }
            }
            if (d !in Downloads.list) { c.saveWhy = "下载被删掉了"; return null }
            delay(150)
        }
    }

    private suspend fun saveImages(c: Clip, i: Douyin.Item): String {
        var first = ""
        i.images.forEachIndexed { n, u ->
            val uri = saveImage(u, i.title, "${n + 1}")
            if (n == 0) first = uri
            c.frac = (n + 1f) / i.images.size
        }
        return first
    }

    private suspend fun saveImage(u: String, title: String, tail: String): String = withContext(Dispatchers.IO) {
        val bytes = fetch(u) ?: error("图片没拿到")
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, title.replace(Regex("[\\\\/:*?\"<>|\\n]"), "_").take(40) + "-$tail.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/浮光掠影")
        }
        val uri = app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: error("相册存不进去")
        app.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        uri.toString()
    }

    /** 存好的东西还在不在（相册里被删了就回到「没存」）。 */
    fun stillThere(c: Clip): Boolean = c.out.startsWith("content://") && runCatching {
        app.contentResolver.query(Uri.parse(c.out), arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.count > 0 } ?: false
    }.getOrDefault(false)

    private fun fetch(u: String): ByteArray? = runCatching {
        (URL(u).openConnection() as HttpURLConnection).run {
            connectTimeout = 12_000; readTimeout = 20_000
            Douyin.headers().forEach { (k, v) -> setRequestProperty(k, v) }
            try { inputStream.use { it.readBytes() } } finally { disconnect() }
        }
    }.getOrNull()

    private fun picture(u: String): ImageBitmap? = fetch(u)?.let { b ->
        BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap()
    }

    // ———— 存档：重开 App 卡片还在 ————

    private fun persist() {
        val a = JSONArray()
        clips.values.sortedByDescending { it.at }.take(200).forEach { c ->
            val i = c.item
            a.put(JSONObject().put("link", c.link).put("at", c.at).put("pick", c.pick).put("save", if (c.save == 2) 2 else 0).put("out", c.out).put("mime", c.outMime).apply {
                if (i != null) put("id", i.id).put("title", i.title).put("desc", i.desc).put("author", i.author).put("cover", i.cover ?: "").put("sec", i.seconds)
                    .put("video", i.video ?: "").put("images", JSONArray(i.images)).put("music", i.music ?: "").put("parsedAt", c.parsedAt)
            })
        }
        prefs?.putString("clips", a.toString())
    }

    private fun load() {
        runCatching {
            val a = JSONArray(prefs?.string("clips") ?: return)
            for (k in 0 until a.length()) {
                val o = a.getJSONObject(k)
                val c = Clip(o.getString("link"), o.optLong("at"))
                if (o.has("id")) {
                    val imgs = o.optJSONArray("images")?.let { j -> List(j.length()) { j.optString(it) } }.orEmpty()
                    c.item = Douyin.Item(o.getString("id"), o.optString("title"), o.optString("desc"), o.optString("author"), o.optString("cover").ifEmpty { null }, o.optInt("sec"),
                        o.optString("video").ifEmpty { null }, imgs, o.optString("music").ifEmpty { null })
                    c.parsedAt = o.optLong("parsedAt"); c.parse = 1
                } else c.parse = 2
                c.pick = o.optInt("pick"); c.out = o.optString("out"); c.outMime = o.optString("mime")
                c.save = if (o.optInt("save") == 2 && c.out.isNotEmpty()) 2 else 0
                clips[c.link] = c
            }
        }
        // 封面图在后台拉；相册里被删了的回到「没存」
        scope.launch {
            clips.values.forEach { c ->
                if (c.save == 2 && !withContext(Dispatchers.IO) { stillThere(c) }) c.save = 0
                c.item?.cover?.let { u -> if (c.cover == null) c.cover = withContext(Dispatchers.IO) { picture(u) } }
            }
        }
    }
}
