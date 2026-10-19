package com.fuguang.lueying

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * 磁力边下边看（照 LibreTorrent / TorrServer 的做法）：本机 127.0.0.1 开一个小服务，播放器按 Range 读还在下的视频；
 * 读到哪一块还没下到，就给那一块和后面十几 MB 设上期限，让 libtorrent 先下它们，下到了再交出去。
 * 开头和结尾几块先要（mp4 的索引常在结尾），拖到哪就先下哪。
 */
object TorrentServe {
    @Volatile private var server: ServerSocket? = null

    private fun port(): Int = synchronized(this) {
        server?.takeIf { !it.isClosed }?.localPort ?: ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")).also { s ->
            server = s
            thread(isDaemon = true, name = "torrent-serve") { while (!s.isClosed) { val c = runCatching { s.accept() }.getOrNull() ?: break; thread(isDaemon = true) { serve(c) } } }
        }.localPort
    }

    /** 这个下载正在下的视频的播放地址；还没在下就 null。 */
    fun url(id: String): String? {
        val f = Torrent.streamFile(id) ?: return null
        warm(id, f)
        return "http://127.0.0.1:${port()}/t/$id/$f"
    }

    /** 先要开头 4 块、结尾 2 块。 */
    private fun warm(id: String, f: Int) {
        val h = Torrent.handles[id] ?: return; val fs = Torrent.infos[id]?.files() ?: return
        val first = fs.pieceIndexAtFile(f); val last = fs.lastPieceIndexAtFile(f)
        (first..minOf(first + 3, last)).forEachIndexed { k, p -> if (!h.havePiece(p)) h.setPieceDeadline(p, 200 + 300 * k) }
        (maxOf(first, last - 1)..last).forEach { p -> if (!h.havePiece(p)) h.setPieceDeadline(p, 600) }
    }

    private fun serve(c: Socket) = runCatching {
        c.use { sock ->
            sock.soTimeout = 30_000
            val r = BufferedReader(InputStreamReader(sock.getInputStream()))
            val req = r.readLine() ?: return@use
            var range: String? = null
            while (true) { val l = r.readLine() ?: break; if (l.isEmpty()) break; if (l.startsWith("Range:", true)) range = l.substringAfter(':').trim() }
            val parts = req.split(' ').getOrNull(1)?.trim('/')?.split('/') ?: return@use
            val out = sock.getOutputStream()
            fun fail(code: String) { out.write("HTTP/1.1 $code\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush() }
            if (parts.size != 3 || parts[0] != "t") return@use fail("404 Not Found")
            val id = parts[1]; val f = parts[2].toIntOrNull() ?: return@use fail("404 Not Found")
            val ti = Torrent.infos[id] ?: return@use fail("404 Not Found")
            val fs = ti.files()
            val len = fs.fileSize(f)
            val m = range?.let { Regex("bytes=(\\d*)-(\\d*)").find(it) }
            val from = m?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val to = (m?.groupValues?.get(2)?.toLongOrNull() ?: (len - 1)).coerceAtMost(len - 1)
            if (from >= len) { out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$len\r\nConnection: close\r\n\r\n".toByteArray()); out.flush(); return@use }
            val head = StringBuilder(if (m != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                .append("Content-Type: ").append(Torrent.mime(fs.fileName(f))).append("\r\n")
                .append("Accept-Ranges: bytes\r\nContent-Length: ").append(to - from + 1).append("\r\n")
            if (m != null) head.append("Content-Range: bytes $from-$to/$len\r\n")
            head.append("Connection: close\r\n\r\n")
            out.write(head.toString().toByteArray())
            val pl = ti.pieceLength().toLong()
            val base = fs.fileOffset(f)
            val window = maxOf(6, ((24L shl 20) / pl).toInt())
            var raf: RandomAccessFile? = null
            val buf = ByteArray(1 shl 16)
            var pos = from
            try {
                while (pos <= to) {
                    val piece = ((base + pos) / pl).toInt()
                    if (!ready(id, piece, window)) return@use
                    val path = java.io.File(Torrent.dirs[id] ?: return@use, fs.filePath(f))
                    val file = raf ?: RandomAccessFile(path, "r").also { raf = it }
                    val end = minOf(to + 1, (piece + 1L) * pl - base)
                    file.seek(pos)
                    while (pos < end) {
                        val n = file.read(buf, 0, minOf(buf.size.toLong(), end - pos).toInt())
                        if (n <= 0) return@use
                        out.write(buf, 0, n); pos += n
                    }
                }
                out.flush()
            } finally { raf?.close() }
        }
    }

    /** 等这一块下到；等的时候给它和后面 [window] 块设期限（越近越急）。下载被拿掉了、等了 3 分钟都没到就放弃。 */
    private fun ready(id: String, piece: Int, window: Int): Boolean {
        val t0 = System.currentTimeMillis(); var asked = 0L
        while (true) {
            val h = Torrent.handles[id] ?: return false
            val n = Torrent.infos[id]?.numPieces() ?: return false
            if (h.havePiece(piece)) {
                // 读到了：顺手把后面几块也排上，播放不断
                if (System.currentTimeMillis() - asked > 2000) { ask(h, piece + 1, window, n); asked = System.currentTimeMillis() }
                return true
            }
            if (System.currentTimeMillis() - asked > 1500) { ask(h, piece, window, n); asked = System.currentTimeMillis() }
            if (System.currentTimeMillis() - t0 > 180_000) return false
            Thread.sleep(120)
        }
    }

    private fun ask(h: org.libtorrent4j.TorrentHandle, from: Int, window: Int, n: Int) {
        for (k in 0 until window) { val p = from + k; if (p >= n) break; if (!h.havePiece(p)) h.setPieceDeadline(p, 300 + 250 * k) }
    }
}
