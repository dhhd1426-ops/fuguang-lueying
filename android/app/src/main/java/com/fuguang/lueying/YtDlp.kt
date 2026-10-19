package com.fuguang.lueying

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 下载引擎：yt-dlp（开源下载器 Seal 用的同一套：youtubedl-android，把 yt-dlp 和 ffmpeg 装进 App）。
 * 认得一千多个网站（B 站、YouTube、微博、抖音……），能列出所有清晰度，画面和声音分开的用 ffmpeg 合成。
 * 它认不出的网页，再退回 Sniffer 自己嗅探。每周试着更新一次 yt-dlp（网站改版后靠这个跟上）。
 */
object YtDlp {
    class Fmt(val label: String, val height: Int, val size: Long)
    class Info(val title: String, val fmts: List<Fmt>, val audioSize: Long)

    private val lock = Mutex()
    @Volatile private var ready = false
    private lateinit var app: Context
    private var prefs: Prefs? = null

    fun init(ctx: Context, p: Prefs) { app = ctx.applicationContext; prefs = p }

    /** 第一次用时解开（几秒）。 */
    private suspend fun boot(): Boolean = lock.withLock {
        if (ready) return true
        withContext(Dispatchers.IO) {
            runCatching { YoutubeDL.init(app); FFmpeg.init(app); ready = true }
            if (ready) runCatching {
                val last = prefs?.string("ytdlp-updated")?.toLongOrNull() ?: 0
                if (System.currentTimeMillis() - last > 7L * 24 * 3600 * 1000) {
                    YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)
                    prefs?.putString("ytdlp-updated", System.currentTimeMillis().toString())
                }
            }
        }
        ready
    }

    private fun YoutubeDLRequest.web(headers: Map<String, String>, pageUrl: String): YoutubeDLRequest = apply {
        android.webkit.CookieManager.getInstance().getCookie(pageUrl)?.takeIf { it.isNotBlank() }?.let { addOption("--add-header", "Cookie:$it") }
        headers.forEach { (k, v) -> if (k.equals("User-Agent", true) || k.equals("Referer", true)) addOption("--add-header", "$k:$v") }
        if (headers.keys.none { it.equals("Referer", true) }) addOption("--add-header", "Referer:$pageUrl")
        addOption("--no-playlist")
        addOption("--socket-timeout", "15")
    }

    /** 这个网页 yt-dlp 认不认得、有哪几档。认不得返回 null。 */
    suspend fun info(pageUrl: String, headers: Map<String, String>): Info? {
        if (!boot()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val r = YoutubeDLRequest(pageUrl).web(headers, pageUrl).apply { addOption("--dump-single-json"); addOption("-R", "1") }
                val o = JSONObject(YoutubeDL.getInstance().execute(r, null, null).out)
                val fm = o.optJSONArray("formats") ?: return@runCatching null
                var audio = 0L
                val byH = HashMap<Int, Long>()
                for (i in 0 until fm.length()) {
                    val f = fm.optJSONObject(i) ?: continue
                    val sz = f.optLong("filesize", 0).takeIf { it > 0 } ?: f.optLong("filesize_approx", 0)
                    val v = f.optString("vcodec", "none"); val a = f.optString("acodec", "none")
                    if (v == "none" && a != "none") audio = maxOf(audio, sz)
                    val h = f.optInt("height", 0)
                    if (v != "none" && h > 0) byH[h] = maxOf(byH[h] ?: 0L, sz)
                }
                if (byH.isEmpty() && audio == 0L) return@runCatching null
                Info(o.optString("title"), byH.entries.sortedByDescending { it.key }.map { (h, s) -> Fmt("${h}P", h, if (s > 0) s + audio else 0) }, audio)
            }.getOrNull()
        }
    }

    /** 下载：height 为 0 是最清楚的那档。callback 给进度（0–1）和速度说明。 */
    suspend fun download(id: String, pageUrl: String, headers: Map<String, String>, height: Int, audioOnly: Boolean, dir: File, onProgress: (Float, String) -> Unit): File {
        if (!boot()) error("下载引擎没装好")
        return withContext(Dispatchers.IO) {
            dir.mkdirs()
            val r = YoutubeDLRequest(pageUrl).web(headers, pageUrl).apply {
                if (audioOnly) { addOption("-f", "ba/b"); addOption("-x"); addOption("--audio-format", "m4a") }
                else {
                    val h = if (height > 0) "[height<=$height]" else ""
                    addOption("-f", "bv*$h+ba/b$h/bv*+ba/b")
                    addOption("--merge-output-format", "mp4")
                }
                addOption("-N", "4")
                addOption("--no-mtime")
                addOption("-o", File(dir, "out.%(ext)s").absolutePath)
            }
            val res = YoutubeDL.getInstance().execute(r, id) { p, _, line -> onProgress((p / 100f).coerceIn(0f, 1f), line) }
            dir.listFiles()?.filter { it.isFile && it.name.startsWith("out.") && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }?.maxByOrNull { it.length() }
                ?: error(res.err.lines().lastOrNull { it.contains("ERROR") }?.substringAfter("ERROR:")?.trim()?.take(60) ?: "没下成")
        }
    }

    /** 边下边看：这一档的直链（画面一条、声音一条，或者合在一起的一条）。拿不到返回空。 */
    suspend fun streams(pageUrl: String, headers: Map<String, String>, height: Int, audioOnly: Boolean): List<String> {
        if (!boot()) return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching {
                val h = if (height > 0) "[height<=$height]" else ""
                val r = YoutubeDLRequest(pageUrl).web(headers, pageUrl).apply { addOption("-g"); addOption("-f", if (audioOnly) "ba/b" else "bv*$h+ba/b$h/bv*+ba/b") }
                YoutubeDL.getInstance().execute(r, null, null).out.lines().map { it.trim() }.filter { it.startsWith("http") }.take(2)
            }.getOrDefault(emptyList())
        }
    }

    fun stop(id: String) { runCatching { YoutubeDL.destroyProcessById(id) } }
}
