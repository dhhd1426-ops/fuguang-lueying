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
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.swig.add_torrent_params
import org.libtorrent4j.swig.byte_vector
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.libtorrent
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

    /** 内置的公开 Tracker（下面的在线列表拿不到时用）。 */
    private val BUILTIN = listOf(
        "udp://tracker.opentrackr.org:1337/announce", "udp://open.stealth.si:80/announce", "udp://tracker.torrent.eu.org:451/announce",
        "udp://exodus.desync.com:6969/announce", "udp://open.demonii.com:1337/announce", "udp://tracker.openbittorrent.com:6969/announce",
        "udp://tracker.dler.org:6969/announce", "udp://explodie.org:6969/announce", "https://tracker.tamersunion.org:443/announce",
        "udp://tracker.qu.ax:6969/announce", "http://tracker.opentrackr.org:1337/announce",
    )

    /**
     * 在线 Tracker 列表：XIU2/TrackersListCollection（专门挑在国内也连得上的）+ ngosang/trackerslist，
     * 走 jsDelivr（国内能打开），一天更新一次，存在本地。不少国外 UDP Tracker 在国内连不上，只用内置那几个常常找不到人。
     */
    @Volatile private var online: List<String> = emptyList()
    @Volatile private var home: File? = null
    val TRACKERS: List<String> get() = (online + BUILTIN).distinct().take(80)

    /** App 启动时调：记住放状态的地方，读上次的 Tracker 列表，过期了后台去拿新的。 */
    fun init(dir: File) {
        home = dir.apply { mkdirs() }
        val f = File(dir, "trackers.txt")
        online = runCatching { f.readLines().filter { it.contains("://") } }.getOrDefault(emptyList())
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 24 * 3600_000L) kotlin.concurrent.thread(isDaemon = true) {
            val got = listOf(
                "https://cdn.jsdelivr.net/gh/XIU2/TrackersListCollection@master/best.txt",
                "https://cdn.jsdelivr.net/gh/ngosang/trackerslist@master/trackers_best.txt",
                "https://cdn.jsdelivr.net/gh/XIU2/TrackersListCollection@master/http.txt",
            ).flatMap { u -> runCatching { (URL(u).openConnection() as HttpURLConnection).run { connectTimeout = 10_000; readTimeout = 15_000; inputStream.bufferedReader().readLines() } }.getOrDefault(emptyList()) }
                .map { it.trim() }.filter { it.contains("://") && it.contains("announce") }.distinct()
            if (got.size >= 5) { online = got; runCatching { f.writeText(got.joinToString("\n")) } }
        }
    }

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
            // 所有 Tracker 一起问（默认只问第一个能用的），找人快很多
            sp.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.announce_to_all_trackers.swigValue(), true)
            sp.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.announce_to_all_tiers.swigValue(), true)
            // 多几个 DHT 入口（默认那几个在国内常连不上）
            sp.setDhtBootstrapNodes("dht.libtorrent.org:25401,router.bittorrent.com:6881,router.utorrent.com:6881,dht.transmissionbt.com:6881,dht.aelitis.com:6881,router.bitcomet.com:6881")
            // 上次存下的 DHT 节点表：再打开不用从头找，几秒就能连上
            val st = home?.let { File(it, "session.dat") }?.takeIf { it.exists() }
            val params = st?.let { runCatching { SessionParams(it.readBytes()) }.getOrNull() }
            if (params != null) { s.start(params); s.applySettings(sp) } else s.start(SessionParams(sp))
            sm = s
            // 每 5 分钟存一次 DHT 节点表
            kotlin.concurrent.thread(isDaemon = true) {
                while (true) { Thread.sleep(300_000); runCatching { val b = s.saveState(); if (b != null && b.size > 100) home?.let { File(it, "session.dat").writeBytes(b) } } }
            }
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
    /** 这一个下载的种子文件（存在 work/meta.torrent；没有就去找）。迅雷那条路也用它。 */
    suspend fun metaBytes(d: Dl, w: File): ByteArray = withContext(Dispatchers.IO) {
        val metaFile = File(w, "meta.torrent")
        when {
            metaFile.exists() -> metaFile.readBytes()
            pending[d.media.url] != null -> pending.remove(d.media.url)!!.also { metaFile.writeBytes(it) }
            else -> {
                d.note = "在找种子信息…"
                val m = if (isMagnet(d.media.url)) fetch(d.media.url, File(w, "meta")) else fetchFile(d.media.url, d.media.page)
                (m ?: error("找不到人分享这个种子")).bytes.also { metaFile.writeBytes(it); pending.remove(d.media.url) }
            }
        }
    }

    /** 选中的文件序号（libtorrent 的序号）。 */
    fun selected(d: Dl, n: Int): Set<Int> = d.variant.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.toSet().ifEmpty { (0 until n).toSet() }

    suspend fun download(d: Dl, w: File, report: (TorrentStatus) -> Unit): List<Pair<File, String>> = withContext(Dispatchers.IO) {
        val bytes = metaBytes(d, w)
        val ti = TorrentInfo(bytes)
        val sel = selected(d, ti.numFiles())
        val pr = Array(ti.numFiles()) { if (it in sel) Priority.DEFAULT else Priority.IGNORE }
        val dir = File(w, "data").apply { mkdirs() }
        val s = session()
        val h = add(s, ti, d.media.url, dir, pr)
        handles[d.id] = h; infos[d.id] = ti; dirs[d.id] = dir
        try {
            var kick = 0L
            while (true) {
                val st = h.status()
                report(st)
                // 一直没连上人：每 20 秒再问一遍 Tracker 和 DHT
                if (st.numPeers() == 0 && System.currentTimeMillis() - kick > 20_000) {
                    kick = System.currentTimeMillis(); runCatching { h.forceReannounce(); h.forceDHTAnnounce() }
                }
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

    /**
     * 加进会话：磁力链接本身的 Tracker + 补的公开 Tracker + DHT 都带上（以前只用拿到的种子信息加，Tracker 丢了，常常找不到人）；
     * 不排队、不暂停，加进去就开始。
     */
    private fun add(s: SessionManager, ti: TorrentInfo, key: String, dir: File, pr: Array<Priority>): TorrentHandle {
        val ec = error_code()
        val p = (if (isMagnet(key)) libtorrent.parse_magnet_uri(key, ec).takeIf { ec.value() == 0 } else null) ?: add_torrent_params()
        val tv = p.getTrackers()
        TRACKERS.forEach { if (it !in tv) tv.add(it) }
        p.setTrackers(tv)
        p.set_ti(ti.swig())
        p.setSave_path(dir.absolutePath)
        p.set_file_priorities(byte_vector().apply { pr.forEach { add(it.swig()) } })
        p.setFlags(p.getFlags().and_(TorrentFlags.PAUSED.inv()).and_(TorrentFlags.AUTO_MANAGED.inv()))
        ec.clear()
        val th = s.swig().add_torrent(p, ec)
        if (ec.value() != 0 || th == null || !th.is_valid()) error("没加进下载里：" + ec.message())
        // 刚拿种子信息时那一份（只上传、拿到就停）可能还没从会话里拿掉，这时拿回来的是它：把这两个标记去掉、重设要哪些文件
        return TorrentHandle(th).also { runCatching {
            it.unsetFlags(TorrentFlags.UPLOAD_MODE); it.unsetFlags(TorrentFlags.STOP_WHEN_READY)
            it.prioritizeFiles(pr); it.resume(); it.forceReannounce(); it.forceDHTAnnounce()
        } }
    }

    /** 下完的文件放哪：视频进相册「Movies/浮光掠影」，声音进「Music/浮光掠影」，别的进「Download/浮光掠影」。 */
    fun mime(name: String): String = when (val e = ext(name)) {
        "mp4", "m4v" -> "video/mp4"; "mkv" -> "video/x-matroska"; "webm" -> "video/webm"; "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"; "ts", "m2ts" -> "video/mp2t"; "flv" -> "video/x-flv"; "wmv" -> "video/x-ms-wmv"
        "mp3" -> "audio/mpeg"; "m4a", "aac" -> "audio/mp4"; "flac" -> "audio/flac"; "wav" -> "audio/wav"; "ogg" -> "audio/ogg"
        else -> runCatching { android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(e) }.getOrNull() ?: "application/octet-stream"
    }
    fun isMedia(name: String) = ext(name) in VIDEO || ext(name) in AUDIO

    /** 一句话说现在怎么样（下载页那一行）。 */
    fun line(st: TorrentStatus): String = when (st.state()) {
        TorrentStatus.State.CHECKING_FILES, TorrentStatus.State.CHECKING_RESUME_DATA -> "核对已下的部分…"
        TorrentStatus.State.DOWNLOADING_METADATA -> "在找种子信息…"
        else -> when {
            st.numPeers() > 0 && st.downloadPayloadRate() < 2048 && st.totalPayloadDownload() < 1048576 -> "连上 ${st.numPeers()} 人，还没人传过来"
            st.numPeers() > 0 -> "${st.numPeers()} 个人在传"
            st.listPeers() > 0 -> "找到 ${st.listPeers()} 个人，正在连"
            else -> "在找人 · 已连上 ${nodes()} 个 DHT 节点"
        }
    }

    /**
     * 排查用：这一个下载现在的样子（连上几个人、谁有多少、Tracker 哪些能用、为什么不能用）。
     * 下载页点那一行的状态字展开；CI 上的磁力探测也用它写报告。
     */
    fun diag(id: String): String {
        val h = handles[id] ?: return "不在下载里（暂停了或者还没开始）"
        val st = h.status()
        val s = sm
        val peers = runCatching { h.peerInfo() }.getOrDefault(emptyList())
        val tr = runCatching { h.trackers() }.getOrDefault(emptyList())
        val works = tr.count { e -> e.endpoints().any { runCatching { it.infohashV1().isWorking }.getOrDefault(false) } }
        val head = listOf(
            "状态 ${st.state()} · 连上 ${st.numPeers()} 人（种子 ${st.numSeeds()}）· 知道 ${st.listPeers()} 人 · 可去连 ${st.connectCandidates()}",
            "Tracker 列表 ${TRACKERS.size} 个（在线 ${online.size}）",
            "DHT ${nodes()} 节点 · 外网能连进来：${if (s?.isFirewalled == false) "能" else "不能"} · Tracker 能用 $works/${tr.size}",
            "下 ${st.downloadPayloadRate() / 1024} KB/s · 收到 ${st.totalPayloadDownload() / 1024} KB · 核对过 ${st.totalWantedDone() / 1024} KB · 丢弃 ${st.totalFailedBytes() / 1024} KB",
        )
        val ps = peers.sortedByDescending { it.downSpeed() }.take(8).map {
            "  · ${it.client().ifBlank { "?" }.take(24)} ${it.connectionType()} 对方有 ${(it.progress() * 100).toInt()}% ↓${it.downSpeed() / 1024} KB/s 收过 ${it.totalDownload() / 1024} KB"
        }
        val ts = tr.flatMap { e -> e.endpoints().mapNotNull { ep -> runCatching { ep.infohashV1() }.getOrNull()?.takeIf { it.message().isNotBlank() || it.fails() > 0 }
            ?.let { "  × ${e.url().substringAfter("://").substringBefore('/')}：${it.message().ifBlank { "失败 ${it.fails()} 次" }}" } } }.distinct().take(6)
        return (head + ps + ts).joinToString("\n")
    }

    /** 边下边看读哪个文件：选中的里面最大的视频。 */
    fun streamFile(id: String): Int? {
        val ti = infos[id] ?: return null; val h = handles[id] ?: return null
        val fs = ti.files(); val pr = runCatching { h.filePriorities() }.getOrNull()
        return (0 until fs.numFiles()).filter { ext(fs.fileName(it)) in VIDEO && (pr == null || pr[it] != Priority.IGNORE) }.maxByOrNull { fs.fileSize(it) }
    }
}
