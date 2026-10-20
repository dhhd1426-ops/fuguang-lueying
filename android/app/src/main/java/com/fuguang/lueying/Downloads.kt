package com.fuguang.lueying

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 一个下载。state：0 排队 · 1 在下 · 2 合成 · 3 好了 · 4 没成 · 5 暂停 · 6 等 Wi-Fi */
class Dl(
    val id: String, val title: String, val media: Sniffer.Media, val quality: String, val variant: String?,
    val audioOnly: Boolean, val gallery: Boolean, val wifiOnly: Boolean, val at: Long = System.currentTimeMillis(),
) {
    var state by mutableIntStateOf(0)
    var done by mutableLongStateOf(0)
    var total by mutableLongStateOf(-1)
    var frac by mutableFloatStateOf(0f)
    var speed by mutableLongStateOf(0)
    var error by mutableStateOf("")
    var out by mutableStateOf("")      // 好了以后：content:// 或文件路径
    var note by mutableStateOf("")     // 磁力：几个人在传 / 核对已下的部分 / 正在存进相册
    var job: Job? = null
}

/**
 * 下载（内置浏览器）：整段文件多线程分块下；m3u8 分段并发下、解开 AES-128、按顺序拼好再转成 mp4；
 * 画面和声音分开的（B 站 m4s）各自下完合成一个文件（Remux：先 ffmpeg，再 MediaMuxer，合完自己放一下确认能看）。加了版权保护（DRM）的不下。
 * 在后台走：有在下的就开着前台服务，通知栏有进度；切页面、关浏览器都接着下。暂停后再继续，下过的不重下。
 */
object Downloads {
    var open by mutableStateOf(false)
    val list = mutableStateListOf<Dl>()
    val running: Int get() = list.count { it.state in setOf(0, 1, 2, 6) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var app: Context
    private var prefs: Prefs? = null
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

    fun init(ctx: Context, p: Prefs) {
        app = ctx.applicationContext; prefs = p
        Torrent.init(File(app.filesDir, "torrent"))
        com.xunlei.downloadlib.XLDownloadManager.ctx = app
        runCatching {
            val a = JSONArray(p.string("downloads") ?: return@runCatching)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val hs = o.optJSONObject("headers")?.let { h -> h.keys().asSequence().associateWith { k -> h.optString(k) } }.orEmpty()
                val m = Sniffer.Media(o.optString("kind"), o.optString("url"), o.optString("audio").ifEmpty { null }, headers = hs, page = o.optString("page"), mime = o.optString("mime"), name = o.optString("name"))
                list += Dl(o.getString("id"), o.optString("title"), m, o.optString("quality"), o.optString("variant").ifEmpty { null }, o.optBoolean("audioOnly"), o.optBoolean("gallery"), o.optBoolean("wifi"), o.optLong("at")).also {
                    it.out = o.optString("out"); it.total = o.optLong("total")
                    if (o.optInt("state", 3) == 3) { it.state = 3; it.done = it.total; it.frac = 1f }
                    else { it.state = 5; it.done = o.optLong("done"); it.frac = o.optDouble("frac", 0.0).toFloat() }
                }
            }
        }
    }

    private fun save() {
        val a = JSONArray()
        list.forEach { d ->
            a.put(JSONObject().put("id", d.id).put("title", d.title).put("kind", d.media.kind).put("url", d.media.url).put("audio", d.media.audio ?: "")
                .put("page", d.media.page).put("quality", d.quality).put("audioOnly", d.audioOnly).put("gallery", d.gallery).put("at", d.at).put("out", d.out).put("total", d.total)
                .put("state", if (d.state == 3) 3 else 5).put("done", d.done).put("frac", d.frac.toDouble()).put("variant", d.variant ?: "").put("wifi", d.wifiOnly)
                .put("headers", JSONObject(d.media.headers)).put("name", d.media.name).put("mime", d.media.mime))
        }
        prefs?.putString("downloads", a.toString())
    }

    private fun dir() = File(app.filesDir, "downloads").apply { mkdirs() }
    private fun work(d: Dl) = File(app.cacheDir, "dl/${d.id}").apply { mkdirs() }

    /** 这一个视频有哪些清晰度：m3u8 主列表里的几档；别的就一档「原画」。 */
    suspend fun qualities(m: Sniffer.Media): List<Pair<String, String?>> = withContext(Dispatchers.IO) {
        if (m.kind != "hls") return@withContext listOf((if (m.kind == "dash") "当前画质" else "原画") to null)
        runCatching {
            val text = get(m.url, m.headers)
            if (!text.contains("#EXT-X-STREAM-INF")) return@runCatching listOf("原画" to null)
            val lines = text.lines()
            lines.mapIndexedNotNull { i, l ->
                if (!l.startsWith("#EXT-X-STREAM-INF")) return@mapIndexedNotNull null
                val res = Regex("RESOLUTION=\\d+x(\\d+)").find(l)?.groupValues?.get(1)
                val bw = Regex("BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0
                val u = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: return@mapIndexedNotNull null
                Triple(res?.let { "${it}P" } ?: "${bw / 1000} kbps", resolve(m.url, u.trim()), bw)
            }.sortedByDescending { it.third }.distinctBy { it.first }.map { it.first to it.second }
        }.getOrDefault(listOf("原画" to null))
    }

    fun start(act: Activity?, title: String, m: Sniffer.Media, quality: String, variant: String?, audioOnly: Boolean, gallery: Boolean, wifiOnly: Boolean) {
        val d = Dl(System.currentTimeMillis().toString(36), title.ifBlank { "视频" }.take(60), m, quality, variant, audioOnly, gallery, wifiOnly)
        list.add(0, d); save()
        // 通知栏进度：安卓 13 起要先问一声
        if (act != null && android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(act, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) ActivityCompat.requestPermissions(act, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 71)
        run(d)
    }

    fun pause(d: Dl) { if (d.media.kind == "ytdlp") YtDlp.stop(d.id); d.job?.cancel(); d.state = 5; d.speed = 0; save() }
    fun resume(d: Dl) { if (d.state == 5 || d.state == 4) run(d) }
    /** 删完的一句提示（相册那份删不掉时）；下载页底下浮出来。 */
    var note by mutableStateOf<String?>(null)

    /**
     * 删干净：正在放的先停；下载停下后等它真停了（线程、yt-dlp 进程不再写）再删临时文件夹；
     * 成品在相册的删相册那份，在 App 里的删文件。相册那份删不掉（重装过 App 就不归它管了）就如实说。
     */
    fun delete(d: Dl) {
        if (DlPlay.id == d.id) DlPlay.close()
        if (d.media.kind == "ytdlp") YtDlp.stop(d.id)
        val job = d.job
        job?.cancel(); list.remove(d); save()
        scope.launch { runCatching { job?.join() }; delay(600); runCatching { work(d).deleteRecursively() } }
        val gone = when {
            d.out.startsWith("content://") -> { val u = Uri.parse(d.out); runCatching { app.contentResolver.delete(u, null, null) }; missing(u) }
            d.out.isNotEmpty() -> File(d.out).let { it.delete(); !it.exists() }
            else -> true
        }
        if (!gone) note = "相册里那份删不掉，要到相册里自己删"
    }

    internal fun missing(u: Uri): Boolean = runCatching {
        app.contentResolver.query(u, arrayOf(android.provider.MediaStore.MediaColumns._ID), null, null, null)?.use { it.count == 0 } ?: true
    }.getOrDefault(true)

    /** 播放 / 分享用的地址。 */
    fun uri(d: Dl): Uri? = when {
        d.out.startsWith("content://") -> Uri.parse(d.out)
        d.out.isNotEmpty() -> runCatching { androidx.core.content.FileProvider.getUriForFile(app, app.packageName + ".files", File(d.out)) }.getOrNull()
        else -> null
    }

    private fun run(d: Dl) {
        d.error = ""; d.state = 0
        runCatching { ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java)) }
        d.job = scope.launch {
            try {
                while (d.wifiOnly && !wifi()) { d.state = 6; delay(10_000) }
                d.state = 1
                val w = work(d)
                // yt-dlp 认得的网站：交给它（它自己合成、转好格式）
                if (d.media.kind == "ytdlp") {
                    val f = YtDlp.download(d.id, d.media.url, d.media.headers, d.variant?.toIntOrNull() ?: 0, d.audioOnly, w) { p, line ->
                        d.frac = p * .97f
                        Regex("at\\s+([\\d.]+)\\s*([KMG]i?B)/s").find(line)?.let { m -> val v = m.groupValues[1].toDoubleOrNull() ?: 0.0
                            d.speed = (v * when (m.groupValues[2].first()) { 'G' -> 1073741824.0; 'M' -> 1048576.0; else -> 1024.0 }).toLong() }
                        Regex("of\\s+~?\\s*([\\d.]+)\\s*([KMG]i?B)").find(line)?.let { m -> val v = m.groupValues[1].toDoubleOrNull() ?: 0.0
                            d.total = (v * when (m.groupValues[2].first()) { 'G' -> 1073741824.0; 'M' -> 1048576.0; else -> 1024.0 }).toLong(); d.done = (d.total * p).toLong() }
                        if (line.contains("[Merger]") || line.contains("[ExtractAudio]")) d.state = 2
                    }
                    d.state = 2; d.speed = 0
                    d.total = f.length(); d.done = d.total; d.frac = 1f
                    d.out = place(d, f); runCatching { w.deleteRecursively() }; d.state = 3
                    withContext(Dispatchers.Main) { save() }
                    return@launch
                }
                // 磁力 / 种子：只下选中的文件，下完存好（视频进相册，别的进「下载」文件夹）
                if (d.media.kind == "magnet") {
                    // 先交给迅雷（国内的人和加速服务器都在它那张网里）；迅雷不肯下、下不动，再换 libtorrent
                    var files: List<Pair<File, String>>? = null
                    if (Thunder.ok() && !File(w, "bt-only").exists()) try {
                        val bytes = Torrent.metaBytes(d, w)
                        files = Thunder.download(d, w, bytes) { done, total, sp, line ->
                            d.done = done; d.total = total; d.speed = sp; d.frac = if (total > 0) done.toFloat() / total else 0f; d.note = line
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e
                    } catch (e: Throwable) {
                        // 迅雷不肯下、下不动、本机库出问题：都换回 libtorrent，不让 App 崩
                        File(w, "bt-only").writeText(e.toString()); d.note = (if (e is Thunder.Fallback) e.message else "迅雷出错") + "，换普通 BT"
                    }
                    if (files == null) files = Torrent.download(d, w) { st ->
                        d.total = st.totalWanted(); d.done = st.totalWantedDone(); d.speed = st.downloadPayloadRate().toLong()
                        d.frac = if (d.total > 0) d.done.toFloat() / d.total else 0f
                        d.note = Torrent.line(st)
                    }
                    if (files.isEmpty()) error("下完了，但没找到文件")
                    d.state = 2; d.speed = 0; d.note = "正在存进相册…"
                    val outs = files.map { (f, name) -> if (Torrent.isMedia(name)) place(d, f, name) else placeFile(d, f, name) }
                    d.out = outs[files.indexOfFirst { Torrent.ext(it.second) in Torrent.VIDEO }.coerceAtLeast(0)]
                    d.total = files.sumOf { it.first.length().coerceAtLeast(0) }.takeIf { it > 0 } ?: d.total; d.done = d.total; d.frac = 1f; d.note = ""
                    runCatching { w.deleteRecursively() }; d.state = 3
                    withContext(Dispatchers.Main) { save() }
                    return@launch
                }
                // 网盘里的一个文件（PanStore 拿好了地址和请求头）：原样下，不转格式；视频声音进相册，别的进「下载」
                if (d.media.kind == "pan") {
                    val name = d.media.name.ifBlank { d.title }
                    val got = file(d, d.media.url, File(w, "a.bin"), share = 1f, base = 0f)
                    d.state = 2; d.speed = 0
                    d.total = got.length(); d.done = d.total; d.frac = 1f
                    d.out = if (d.gallery && Torrent.isMedia(name)) place(d, got, name) else placeFile(d, got, name)
                    runCatching { w.deleteRecursively() }; d.state = 3
                    withContext(Dispatchers.Main) { save() }
                    return@launch
                }
                val got: File = when (d.media.kind) {
                    "hls" -> hls(d, w)
                    "dash" -> dash(d, w)
                    else -> file(d, d.media.url, File(w, "a.bin"), share = 1f, base = 0f)
                }
                // 不是视频的文件（安装包、压缩包、PDF……）：原样放进「下载」文件夹
                if (d.media.kind == "file" && !isVideo(d.media)) {
                    d.total = got.length(); d.done = d.total; d.frac = 1f
                    d.out = placeFile(d, got); runCatching { w.deleteRecursively() }; d.state = 3
                    withContext(Dispatchers.Main) { save() }
                    return@launch
                }
                d.state = 2; d.speed = 0
                val ext = if (d.audioOnly) "m4a" else if (got.name.endsWith(".ts")) "ts" else "mp4"
                val final = File(w, "out.$ext")
                when {
                    d.audioOnly -> Remux.pack(app, listOf(got to "audio/"), final)
                    d.media.kind == "dash" -> got.renameTo(final)
                    d.media.kind == "hls" && got.name.endsWith(".ts") -> if (!runCatching { Remux.pack(app, listOf(got to ""), File(w, "out.mp4")) }.isSuccess) got.renameTo(final) else { got.delete() }
                    else -> got.renameTo(final)
                }
                val result = File(w, "out.mp4").takeIf { it.exists() && !d.audioOnly } ?: final
                d.total = result.length(); d.done = d.total; d.frac = 1f
                d.out = place(d, result)
                runCatching { w.deleteRecursively() }
                d.state = 3
                withContext(Dispatchers.Main) { save() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                d.error = e.message?.take(60) ?: "没下成"; d.state = 4; d.speed = 0
                withContext(Dispatchers.Main + kotlinx.coroutines.NonCancellable) { save() }
            }
        }
    }

    /** 放到最后的地方：存到相册（Movies/浮光掠影 或 Music/浮光掠影），或者留在 App 里。 */
    private fun place(d: Dl, f: File, named: String? = null): String {
        val ext = named?.let { Torrent.ext(it) } ?: f.extension
        val name = (named?.substringBeforeLast('.') ?: d.title).replace(Regex("[\\\\/:*?\"<>|\\n]"), "_").take(80) + "." + ext
        if (d.gallery) runCatching {
            val audio = ext == "m4a" || (named != null && Torrent.mime(named).startsWith("audio"))
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, if (named != null) Torrent.mime(named) else if (audio) "audio/mp4" else if (ext == "ts") "video/mp2t" else "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, (if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES) + "/浮光掠影")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val col = if (audio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val uri = app.contentResolver.insert(col, cv)!!
            app.contentResolver.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o, 1 shl 16) } }
            cv.clear(); cv.put(MediaStore.MediaColumns.IS_PENDING, 0); app.contentResolver.update(uri, cv, null, null)
            f.delete()
            return uri.toString()
        }
        val dst = File(dir(), "${d.id}-$name")
        if (!f.renameTo(dst)) { f.copyTo(dst, true); f.delete() }
        return dst.absolutePath
    }

    private fun isVideo(m: Sniffer.Media) = m.mime.startsWith("video") || m.mime.startsWith("audio") || Regex("\\.(mp4|webm|mov|m4v|flv|mkv|ts|m4a|mp3)(\\?|$)").containsMatchIn(m.url.lowercase())

    private fun placeFile(d: Dl, f: File, named: String? = null): String {
        val name = (named ?: d.media.name).ifBlank { d.media.url.substringBefore('?').substringAfterLast('/').ifBlank { d.title } }.replace(Regex("[\\\\/:*?\"<>|\\n]"), "_").take(80)
        return runCatching {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, named?.let { Torrent.mime(it) } ?: d.media.mime.ifBlank { "application/octet-stream" })
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/浮光掠影")
            }
            val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
            app.contentResolver.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o, 1 shl 16) } }
            f.delete(); uri.toString()
        }.getOrElse { val dst = File(dir(), "${d.id}-$name"); f.copyTo(dst, true); f.delete(); dst.absolutePath }
    }

    private fun wifi(): Boolean {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val c = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    // ———— 网络 ————

    private fun open(url: String, h: Map<String, String>, range: String? = null): HttpURLConnection {
        var u = url; var hops = 0
        while (true) {
            val c = (URL(u).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = false
                setRequestProperty("User-Agent", h.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: UA)
                h.forEach { (k, v) -> if (!k.equals("User-Agent", true) && !k.equals("Range", true)) setRequestProperty(k, v) }
                CookieManager.getInstance().getCookie(u)?.let { setRequestProperty("Cookie", it) }
                if (range != null) setRequestProperty("Range", range)
            }
            val code = c.responseCode
            if (code in 300..399 && hops < 6) { val loc = c.getHeaderField("Location"); c.disconnect(); u = resolve(u, loc ?: error("跳转没给地址")); hops++; continue }
            if (code == 403 || code == 401) { c.disconnect(); error("网站不让下（$code），可能要先登录") }
            if (code == 404) { c.disconnect(); error("地址失效了（404），回网页重新点一次") }
            if (code !in 200..299) { c.disconnect(); error("出错了（$code）") }
            return c
        }
    }

    private fun get(url: String, h: Map<String, String>): String = open(url, h).let { c -> try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() } }
    private fun bytes(url: String, h: Map<String, String>): ByteArray = open(url, h).let { c -> try { c.inputStream.use { it.readBytes() } } finally { c.disconnect() } }

    private fun resolve(base: String, u: String): String = runCatching { URL(URL(base), u).toString() }.getOrDefault(u)

    /** 速度：每秒算一次。 */
    private class Meter(val d: Dl) { val n = AtomicLong(); var t0 = System.currentTimeMillis(); var b0 = 0L
        fun add(k: Long) { val v = n.addAndGet(k); val t = System.currentTimeMillis(); if (t - t0 >= 1000) { d.speed = (v - b0) * 1000 / (t - t0); b0 = v; t0 = t } } }

    /** 整段文件：支持分块就 4 块一起下（大于 8 MB 时），不支持就一条下；暂停后接着下。share/base：这一段在总进度里占多少。 */
    private suspend fun file(d: Dl, url: String, dst: File, share: Float, base: Float): File = withContext(Dispatchers.IO) {
        val h = d.media.headers
        val probe = open(url, h, "bytes=0-")
        val len = probe.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: probe.contentLengthLong
        val ranges = probe.responseCode == 206
        probe.disconnect()
        val meter = Meter(d)
        if (ranges && len > 8L * 1024 * 1024) {
            val parts = 4; val step = len / parts
            RandomAccessFile(dst, "rw").use { it.setLength(len) }
            val doneParts = AtomicLong(0)
            (0 until parts).map { i ->
                async {
                    val from = i * step; val to = if (i == parts - 1) len - 1 else (i + 1) * step - 1
                    val mark = File(dst.parentFile, "${dst.name}.p$i")
                    var at = mark.takeIf { it.exists() }?.readText()?.toLongOrNull() ?: from
                    doneParts.addAndGet(at - from)
                    if (at > to) return@async
                    val c = open(url, h, "bytes=$at-$to")
                    try {
                        RandomAccessFile(dst, "rw").use { raf ->
                            raf.seek(at)
                            c.inputStream.use { ins ->
                                val buf = ByteArray(1 shl 16)
                                while (true) {
                                    ensureActive()
                                    val n = ins.read(buf); if (n < 0) break
                                    raf.write(buf, 0, n); at += n; meter.add(n.toLong())
                                    val all = doneParts.addAndGet(n.toLong())
                                    d.done = all; d.total = len; d.frac = base + share * all / len
                                    if (at % (1 shl 20) < n) mark.writeText("$at")
                                }
                            }
                        }
                    } finally { c.disconnect(); mark.writeText("$at") }
                }
            }.awaitAll()
        } else {
            var have = if (dst.exists()) dst.length() else 0L
            val c = open(url, h, if (have > 0 && ranges) "bytes=$have-" else null)
            if (c.responseCode != 206) have = 0
            try {
                java.io.FileOutputStream(dst, have > 0).use { o ->
                    c.inputStream.use { ins ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            ensureActive()
                            val n = ins.read(buf); if (n < 0) break
                            o.write(buf, 0, n); have += n; meter.add(n.toLong())
                            d.done = have; d.total = len; d.frac = if (len > 0) base + share * have / len else base
                        }
                    }
                }
            } finally { c.disconnect() }
        }
        dst
    }

    /** m3u8：选好那一档 → 分段并发下（6 条）→ 解开 AES-128 → 按顺序拼起来。 */
    private suspend fun hls(d: Dl, w: File): File = withContext(Dispatchers.IO) {
        val h = d.media.headers
        var listUrl = d.variant ?: d.media.url
        var text = get(listUrl, h)
        if (text.contains("#EXT-X-STREAM-INF")) {   // 没选档：拿最高的一档
            val lines = text.lines()
            val best = lines.mapIndexedNotNull { i, l -> if (l.startsWith("#EXT-X-STREAM-INF")) (Regex("BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0) to lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } else null }
                .filter { it.second != null }.maxByOrNull { it.first } ?: error("列表是空的")
            listUrl = resolve(listUrl, best.second!!.trim()); text = get(listUrl, h)
        }
        if (Regex("METHOD=SAMPLE-AES|KEYFORMAT=\"com\\.(widevine|apple|microsoft)|urn:uuid:").containsMatchIn(text)) error("这个视频有版权保护，下不了")
        class Seg(val i: Int, val url: String, val key: String?, val iv: ByteArray?)
        val segs = ArrayList<Seg>()
        var key: String? = null; var iv: ByteArray? = null; var map: String? = null
        var seq = Regex("#EXT-X-MEDIA-SEQUENCE:(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        text.lines().forEach { raw ->
            val l = raw.trim()
            when {
                l.startsWith("#EXT-X-KEY") -> {
                    val m = Regex("METHOD=([A-Z0-9-]+)").find(l)?.groupValues?.get(1)
                    key = if (m == "AES-128") resolve(listUrl, Regex("URI=\"([^\"]+)\"").find(l)!!.groupValues[1]) else null
                    iv = Regex("IV=0[xX]([0-9a-fA-F]+)").find(l)?.groupValues?.get(1)?.let { hex -> hex.padStart(32, '0').chunked(2).map { it.toInt(16).toByte() }.toByteArray() }
                }
                l.startsWith("#EXT-X-MAP") -> map = Regex("URI=\"([^\"]+)\"").find(l)?.groupValues?.get(1)?.let { resolve(listUrl, it) }
                l.isNotEmpty() && !l.startsWith("#") -> {
                    val segIv = iv ?: ByteArray(16).also { b -> var s = seq; for (k in 15 downTo 8) { b[k] = (s and 0xff).toByte(); s = s shr 8 } }
                    segs += Seg(segs.size, resolve(listUrl, l), key, if (key != null) segIv else null); seq++
                }
            }
        }
        if (segs.isEmpty()) error("列表里没有分段")
        val keys = HashMap<String, ByteArray>()
        val sem = Semaphore(6); val meter = Meter(d); val got = AtomicLong(segs.count { File(w, "s${it.i}").exists() }.toLong())
        d.total = -1
        segs.map { s ->
            async {
                val f = File(w, "s${s.i}")
                if (f.exists()) return@async
                sem.withPermit {
                    var tries = 0
                    while (true) {
                        ensureActive()
                        try {
                            var b = bytes(s.url, h)
                            if (s.key != null) {
                                val k = synchronized(keys) { keys[s.key] } ?: bytes(s.key, h).also { synchronized(keys) { keys[s.key] = it } }
                                b = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), IvParameterSpec(s.iv)) }.doFinal(b)
                            }
                            File(w, "s${s.i}.part").apply { writeBytes(b); renameTo(f) }
                            meter.add(b.size.toLong()); d.done += b.size
                            d.frac = got.incrementAndGet().toFloat() / segs.size * .95f
                            break
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { if (++tries >= 3) throw e; delay(800L * tries) }
                    }
                }
            }
        }.awaitAll()
        val out = File(w, if (map != null) "joined.mp4" else "joined.ts")
        out.outputStream().buffered(1 shl 16).use { o ->
            map?.let { o.write(bytes(it, h)) }
            segs.forEach { s -> File(w, "s${s.i}").inputStream().use { it.copyTo(o) } }
        }
        segs.forEach { File(w, "s${it.i}").delete() }
        out
    }

    /** 画面 + 声音分开的：各下各的，再合成一个 mp4。 */
    private suspend fun dash(d: Dl, w: File): File {
        val a = d.media.audio ?: error("没找到声音那一轨")
        if (d.audioOnly) return file(d, a, File(w, "a.m4s"), 1f, 0f)
        val v = file(d, d.media.url, File(w, "v.m4s"), .8f, 0f)
        val au = file(d, a, File(w, "a.m4s"), .2f, .8f)
        val out = File(w, "merged.mp4")
        Remux.pack(app, listOf(v to "video/", au to "audio/"), out)
        v.delete(); au.delete()
        return out
    }
}
