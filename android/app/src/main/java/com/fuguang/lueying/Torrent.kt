package com.fuguang.lueying

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.swig.torrent_flags_t
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

/**
 * 磁力 / 种子（照开源安卓 BT 客户端 LibreTorrent：libtorrent4j）。
 * 内置浏览器点到 magnet: 或 .torrent：先从 DHT 和 Tracker 拿种子信息（里面有哪些文件、多大），
 * 选好文件交给下载页（Dl.kind = magnet，variant = 选中的文件序号）；下的时候只要选中的那几个，
 * 下完不做种（从会话里拿掉），存进相册 / 下载文件夹。边下边看见 [TorrentServe]。
 */
object Torrent {
    /** 种子里的一个文件。 */
    data class Item(val i: Int, val name: String, val path: String, val size: Long) {
        val video get() = ext(name) in VIDEO
    }
    /** 拿到的种子信息。 */
    class Meta(val bytes: ByteArray, val name: String, val items: List<Item>) {
        val hash: String get() = TorrentInfo(bytes).infoHash().toHex()
    }

    val VIDEO = setOf("mp4", "mkv", "avi", "mov", "m4v", "webm", "ts", "flv", "wmv", "rmvb", "mpg", "mpeg", "m2ts")
    private val AUDIO = setOf("mp3", "m4a", "flac", "aac", "wav", "ogg", "ape")
    fun ext(n: String) = n.substringAfterLast('.', "").lowercase()

    /** 常用的公开 Tracker：磁力链接里的少、慢，补上一批，拿种子信息快很多。 */
    private val TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce", "udp://open.stealth.si:80/announce", "udp://tracker.torrent.eu.org:451/announce",
        "udp://exodus.desync.com:6969/announce", "udp://open.demonii.com:1337/announce", "udp://tracker.openbittorrent.com:6969/announce",
        "udp://tracker.dler.org:6969/announce", "udp://explodie.org:6969/announce", "https://tracker.tamersunion.org:443/announce",
        "udp://tracker.qu.ax:6969/announce", "http://tracker.opentrackr.org:1337/announce",
    )

    private var sm: SessionManager? = null
    private val lock = Any()
    internal val handles = ConcurrentHashMap<String, TorrentHandle>()
    internal val infos = ConcurrentHashMap<String, TorrentInfo>()
    internal val dirs = ConcurrentHashMap<String, File>()
    /** 刚拿到、还没开始下的种子信息（按磁力链接）。 */
    private val pending = ConcurrentHashMap<String, ByteArray>()

    fun session(): SessionManager = synchronized(lock) {
        sm ?: SessionManager(false).also { s ->
            val sp = SettingsPack()
                .uploadRateLimit(256 * 1024)          // 下的时候也会传给别人（BT 就是这样），封个顶，不占你的上行
                .activeDownloads(4).connectionsLimit(200)
            sp.setEnableDht(true)
            s.start(SessionParams(sp))
            sm = s
        }
    }

    /** 拿种子信息时连上了几个 DHT 节点（给「在找」那一行看）。 */
    fun nodes(): Long = runCatching { sm?.dhtNodes() ?: 0L }.getOrDefault(0L)

    fun isMagnet(u: String) = u.trim().startsWith("magnet:", true)
    fun isTorrent(url: String, mime: String?) = mime?.contains("bittorrent", true) == true || url.substringBefore('?').lowercase().endsWith(".torrent")

    /** 磁力链接里写的名字（dn=），拿到种子信息前先显示它。 */
    fun nameOf(magnet: String): String = Regex("[?&]dn=([^&]+)").find(magnet)?.groupValues?.get(1)
        ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }.orEmpty()

    private fun withTrackers(m: String): String = m + TRACKERS.filter { !m.contains(it) }.joinToString("") { "&tr=" + java.net.URLEncoder.encode(it, "UTF-8") }

    /** 磁力：从 DHT 和 Tracker 拿种子信息，最多等 [seconds] 秒。拿不到返回 null。 */
    suspend fun fetch(magnet: String, tmp: File, seconds: Int = 90): Meta? = withContext(Dispatchers.IO) {
        val s = session()
        val b = runCatching { s.fetchMagnet(withTrackers(magnet), seconds, tmp.apply { mkdirs() }) }.getOrNull() ?: return@withContext null
        read(b)?.also { pending[magnet] = b }
    }

    /** .torrent 文件：带上网页的 Cookie 和来源读下来。 */
    suspend fun fetchFile(url: String, referer: String): Meta? = withContext(Dispatchers.IO) {
        runCatching {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 30_000
                setRequestProperty("Referer", referer)
                CookieManager.getInstance().getCookie(url)?.let { setRequestProperty("Cookie", it) }
            }
            val b = c.inputStream.use { it.readBytes() }; c.disconnect()
            read(b)?.also { pending[url] = it.bytes }
        }.getOrNull()
    }

    private fun read(b: ByteArray): Meta? = runCatching {
        val ti = TorrentInfo(b); val fs = ti.files()
        val items = (0 until fs.numFiles()).filter { !fs.padFileAt(it) }.map { Item(it, fs.fileName(it), fs.filePath(it), fs.fileSize(it)) }
        Meta(b, ti.name(), items)
    }.getOrNull()

    /** 默认选哪些：视频（50 MB 以上）；没有视频就全选。 */
    fun defaults(m: Meta): Set<Int> = m.items.filter { it.video && it.size > 50L * 1024 * 1024 }.map { it.i }.toSet().ifEmpty { m.items.map { it.i }.toSet() }

    /**
     * 下（在 Downloads.run 里调）：只要选中的文件；每秒报一次进度。下完从会话里拿掉（不做种），返回选中的文件和它们的名字。
     * 暂停 = 这个协程被取消：同样从会话里拿掉，已下的留在 [w] 里，再继续时 libtorrent 核对一遍接着下。
     */
    suspend fun download(d: Dl, w: File, report: (TorrentStatus) -> Unit): List<Pair<File, String>> = withContext(Dispatchers.IO) {
        val metaFile = File(w, "meta.torrent")
        val bytes = when {
            metaFile.exists() -> metaFile.readBytes()
            pending[d.media.url] != null -> pending.remove(d.media.url)!!.also { metaFile.writeBytes(it) }
            else -> {
                d.note = "在找种子信息…"
                val m = if (isMagnet(d.media.url)) fetch(d.media.url, File(w, "meta")) else fetchFile(d.media.url, d.media.page)
                (m ?: error("找不到人分享这个种子")).bytes.also { metaFile.writeBytes(it); pending.remove(d.media.url) }
            }
        }
        val ti = TorrentInfo(bytes)
        val sel = d.variant.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.toSet().ifEmpty { (0 until ti.numFiles()).toSet() }
        val pr = Array(ti.numFiles()) { if (it in sel) Priority.DEFAULT else Priority.IGNORE }
        val dir = File(w, "data").apply { mkdirs() }
        val s = session()
        s.download(ti, dir, null, pr, null, torrent_flags_t())
        var th: TorrentHandle? = null
        for (k in 0 until 100) { th = s.find(ti.infoHash())?.takeIf { it.isValid }; if (th != null) break; delay(100) }
        val h = th ?: error("没加进下载里")
        handles[d.id] = h; infos[d.id] = ti; dirs[d.id] = dir
        try {
            while (true) {
                val st = h.status()
                report(st)
                if (st.totalWanted() > 0 && st.totalWantedDone() >= st.totalWanted()) break
                delay(1000)
            }
            // 正在边下边看：看完再挪走（挪走了播放器就读不到了）
            while (DlPlay.id == d.id) { d.note = "看完再存进相册"; delay(1000) }
        } finally {
            withContext(NonCancellable) {
                handles.remove(d.id); infos.remove(d.id); dirs.remove(d.id)
                runCatching { s.remove(h) }
                delay(800)   // 让它把文件关好
            }
        }
        val fs = ti.files()
        sel.sorted().map { File(dir, fs.filePath(it)) to fs.fileName(it) }.filter { it.first.exists() }
    }

    /** 下完的文件放哪：视频进相册「Movies/浮光掠影」，声音进「Music/浮光掠影」，别的进「Download/浮光掠影」。 */
    fun mime(name: String): String = when (val e = ext(name)) {
        "mp4", "m4v" -> "video/mp4"; "mkv" -> "video/x-matroska"; "webm" -> "video/webm"; "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"; "ts", "m2ts" -> "video/mp2t"; "flv" -> "video/x-flv"; "wmv" -> "video/x-ms-wmv"
        "mp3" -> "audio/mpeg"; "m4a", "aac" -> "audio/mp4"; "flac" -> "audio/flac"; "wav" -> "audio/wav"; "ogg" -> "audio/ogg"
        else -> android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(e) ?: "application/octet-stream"
    }
    fun isMedia(name: String) = ext(name) in VIDEO || ext(name) in AUDIO

    /** 一句话说现在怎么样（下载页那一行）。 */
    fun line(st: TorrentStatus): String = when (st.state()) {
        TorrentStatus.State.CHECKING_FILES, TorrentStatus.State.CHECKING_RESUME_DATA -> "核对已下的部分…"
        TorrentStatus.State.DOWNLOADING_METADATA -> "在找种子信息…"
        else -> if (st.numPeers() == 0) "在找人 · 还没连上" else "${st.numPeers()} 个人在传"
    }

    /** 边下边看读哪个文件：选中的里面最大的视频。 */
    fun streamFile(id: String): Int? {
        val ti = infos[id] ?: return null; val h = handles[id] ?: return null
        val fs = ti.files(); val pr = runCatching { h.filePriorities() }.getOrNull()
        return (0 until fs.numFiles()).filter { ext(fs.fileName(it)) in VIDEO && (pr == null || pr[it] != Priority.IGNORE) }.maxByOrNull { fs.fileSize(it) }
    }
}
