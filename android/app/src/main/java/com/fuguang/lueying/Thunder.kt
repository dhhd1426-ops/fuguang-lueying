package com.fuguang.lueying

import com.xunlei.downloadlib.XLDownloadManager
import com.xunlei.downloadlib.XLTaskHelper
import com.xunlei.downloadlib.parameter.GetTaskId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 迅雷下载引擎（和 TVBox / FongMi TV 同一套：迅雷安卓 SDK，libxl_thunder_sdk.so）。
 * 为什么用它：国内 BT 里大多数人用的是迅雷，迅雷客户端之间互相传、还有迅雷自己的加速服务器（P2S / CDN）；
 * 用 libtorrent 连上它们也不给传（真机上「连上 1 人，还没人传过来」）。迅雷 SDK 进得了这张网。
 * 种子信息还是 libtorrent 拿（已经很快）；下的时候交给迅雷，一个文件一个文件下；
 * 边下边看用迅雷自带的本机地址（getLocalUrl）。迅雷不肯下的（敏感资源、出错）自动换回 libtorrent。
 */
object Thunder {
    class Task(val id: GetTaskId, val index: Int, val file: File)
    private val tasks = ConcurrentHashMap<String, Task>()
    @Volatile private var loaded: Boolean? = null

    /** 本机库能不能用（只打了 arm64）。 */
    fun ok(): Boolean = loaded ?: runCatching { System.loadLibrary("xl_stat"); System.loadLibrary("xl_thunder_sdk"); XLDownloadManager.ctx != null }.getOrDefault(false).also { loaded = it }

    /**
     * 下选中的文件。report(已下, 总大小, 速度, 一句话)。返回下好的文件和名字。
     * 失败（迅雷报错、3 分钟一个字节都没有）就抛出 Fallback，外面换 libtorrent。
     */
    class Fallback(msg: String) : Exception(msg)

    suspend fun download(d: Dl, w: File, bytes: ByteArray, report: (Long, Long, Long, String) -> Unit): List<Pair<File, String>> = withContext(Dispatchers.IO) {
        val torrent = File(w, "xl/meta.torrent").apply { parentFile?.mkdirs(); if (!exists()) writeBytes(bytes) }
        val dir = File(w, "xl/data").apply { mkdirs() }
        val ti = TorrentInfo(bytes); val fs = ti.files()
        val sel = Torrent.selected(d, ti.numFiles()).sorted()
        // 迅雷的文件序号和 libtorrent 的不一定一样（填充文件），按名字和大小对上
        val xl = synchronized(this) { XLTaskHelper.get().getTorrentInfo(torrent) }.mSubFileInfo ?: throw Fallback("迅雷读不了这个种子")
        val want = sel.mapNotNull { i -> xl.firstOrNull { it.mFileName == fs.fileName(i) && it.mFileSize == fs.fileSize(i) }?.let { it to fs.fileName(i) } }
        if (want.isEmpty()) throw Fallback("迅雷里对不上要的文件")
        val total = want.sumOf { it.first.mFileSize }
        var before = 0L
        val out = ArrayList<Pair<File, String>>()
        for ((f, name) in want) {
            val id = synchronized(this) { XLTaskHelper.get().addTorrentTask(torrent, dir, f.mFileIndex) }
            if (id.mTaskId == 0L) throw Fallback("迅雷没接这个任务")
            val target = File(dir, f.mFileName)
            tasks[d.id] = Task(id, f.mFileIndex, target)
            val t0 = System.currentTimeMillis()
            try {
                while (true) {
                    val info = synchronized(this@Thunder) { XLTaskHelper.get().getBtSubTaskInfo(id, f.mFileIndex) }.mTaskInfo
                    val got = info.mDownloadSize
                    val src = listOfNotNull(
                        info.mP2PSpeed.takeIf { it > 0 }?.let { "P2P " + DownloadService.speed(it) },
                        (info.mP2SSpeed + info.mScdnSpeed + info.mAdditionalResDCDNSpeed + info.mAdditionalResVipSpeed).takeIf { it > 0 }?.let { "加速 " + DownloadService.speed(it) },
                    ).joinToString(" · ")
                    report(before + got, total, info.mDownloadSpeed, "迅雷" + if (src.isNotEmpty()) " · $src" else if (got == 0L) " · 在找资源" else "")
                    when (info.mTaskStatus) {
                        2 -> break
                        3 -> throw Fallback("迅雷：" + info.getErrorMsg())
                    }
                    if (got == 0L && System.currentTimeMillis() - t0 > 180_000) throw Fallback("迅雷 3 分钟没下到")
                    if (info.mFileSize > 0 && got >= info.mFileSize) break
                    delay(1000)
                }
                // 正在边下边看：看完再挪
                while (DlPlay.id == d.id) { report(before + f.mFileSize, total, 0, "看完再存进相册"); delay(1000) }
            } finally {
                withContext(NonCancellable) { tasks.remove(d.id); runCatching { synchronized(this@Thunder) { XLTaskHelper.get().stopTask(id) } }; delay(500) }
            }
            before += f.mFileSize
            val file = target.takeIf { it.exists() } ?: dir.walkTopDown().firstOrNull { it.isFile && it.name == f.mFileName } ?: throw Fallback("迅雷说下好了，但没找到文件")
            out += file to name
        }
        out
    }

    /** 边下边看：迅雷自带的本机播放地址。 */
    fun url(id: String): String? {
        val t = tasks[id] ?: return null
        return runCatching { synchronized(this) { XLTaskHelper.get().getLocalUrl(t.file) } }.getOrNull()?.takeIf { it.startsWith("http") }
    }

    fun diag(id: String): String? {
        val t = tasks[id] ?: return null
        val i = runCatching { synchronized(this) { XLTaskHelper.get().getBtSubTaskInfo(t.id, t.index) }.mTaskInfo }.getOrNull() ?: return null
        fun k(v: Long) = "${v / 1024} KB/s"
        return listOf(
            "迅雷 · 状态 ${i.mTaskStatus} · 已下 ${i.mDownloadSize / 1024} KB / ${i.mFileSize / 1024} KB · ${k(i.mDownloadSpeed)}",
            "P2P ${k(i.mP2PSpeed)} · P2S ${k(i.mP2SSpeed)} · CDN ${k(i.mScdnSpeed)} · 加速 ${k(i.mAdditionalResDCDNSpeed + i.mAdditionalResVipSpeed)} · 源 ${k(i.mOriginSpeed)}",
            "资源 ${i.mAdditionalResCount} 个 · 错误 ${i.mErrorCode}",
        ).joinToString("\n")
    }
}
