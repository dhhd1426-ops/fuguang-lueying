package com.fuguang.lueying

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/**
 * 网盘文件多路下（像 IDM / aria2）：切成 2 MB 一块，默认 16 路一起抢着下，谁空了就拿下一块；
 * 百度普通账号只开一路、块切大（多开会被限得更狠，见 PanBaiduLocate.svip）；
 * 网盘给了几个服务器地址（百度 locatedownload 会给好几个），各路分着用。
 * 网盘按「一条连接」限速时，路数越多越快；它嫌连接太多回 403 / 429，那一路歇一下再试，连着不行就退出，路数自己降下来。
 * 断点：下好的块号记在 a.bin.chunks，暂停后接着下（没下完的那一块重下，最多丢 2 MB）。
 * 不支持分段（不回 206）或文件小于 4 MB 返回 null，交回普通下载。
 */
internal object PanFetch {
    private const val WORKERS = 16
    private const val CHUNK = 2L shl 20

    suspend fun run(d: Dl, urls: List<String>, dst: File, lanes: Int = WORKERS, open: (String, Map<String, String>, String?) -> HttpURLConnection): File? = withContext(Dispatchers.IO) {
        val h = d.media.headers
        val probe = runCatching { open(urls[0], h, "bytes=0-0") }.getOrNull() ?: return@withContext null
        val len = probe.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1
        val ok = probe.responseCode == 206 && len >= 2 * CHUNK
        probe.disconnect()
        if (!ok) return@withContext null

        val chunk = if (lanes == 1) maxOf(16 * CHUNK, len / 64) else maxOf(CHUNK, len / 512)
        val n = ((len + chunk - 1) / chunk).toInt()
        fun size(i: Int) = minOf(len, (i + 1) * chunk) - i * chunk
        val marks = File(dst.parentFile, dst.name + ".chunks")
        val done = HashSet<Int>()
        if (dst.exists() && dst.length() == len) marks.takeIf { it.exists() }?.readLines()?.mapNotNullTo(done) { it.trim().toIntOrNull() }
        else { RandomAccessFile(dst, "rw").use { it.setLength(len) }; marks.delete() }
        val queue = ConcurrentLinkedQueue((0 until n).filter { it !in done })
        val have = AtomicLong(done.sumOf { size(it) })
        d.total = len; d.done = have.get(); d.frac = have.get().toFloat() / len

        // 速度：每秒算一次
        var t0 = System.currentTimeMillis(); var b0 = have.get()
        fun tick(k: Long) {
            val v = have.addAndGet(k)
            d.done = v; d.frac = v.toFloat() / len
            val t = System.currentTimeMillis()
            if (t - t0 >= 1000) synchronized(this) { if (t - t0 >= 1000) { d.speed = (v - b0) * 1000 / (t - t0); b0 = v; t0 = t } }
        }

        /** 下一块；成了返回 true。没成把已经算进去的字节退回去。 */
        suspend fun one(i: Int, url: String): Boolean {
            val from = i * chunk; val to = from + size(i) - 1
            var got = 0L
            try {
                val c = open(url, h, "bytes=$from-$to")
                try {
                    if (c.responseCode != 206) return false
                    RandomAccessFile(dst, "rw").use { raf ->
                        raf.seek(from)
                        c.inputStream.use { ins ->
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                coroutineContext.ensureActive()
                                val k = ins.read(buf); if (k < 0) break
                                raf.write(buf, 0, k); got += k; tick(k.toLong())
                            }
                        }
                    }
                } finally { c.disconnect() }
                if (got != to - from + 1) { have.addAndGet(-got); return false }
                synchronized(marks) { marks.appendText("$i\n") }
                return true
            } catch (e: CancellationException) { have.addAndGet(-got); throw e
            } catch (e: Exception) { have.addAndGet(-got); return false }
        }

        val live = AtomicInteger(minOf(lanes, queue.size).coerceAtLeast(1))
        coroutineScope {
            repeat(live.get()) { w ->
                launch {
                    var fails = 0
                    while (true) {
                        val i = queue.poll() ?: break
                        if (one(i, urls[(w + fails) % urls.size])) { fails = 0; continue }
                        queue.add(i); fails++
                        // 连着 4 次不行：这一路退出（网盘嫌连接多），留下的接着下
                        if (fails >= 4 && live.get() > 1) { live.decrementAndGet(); break }
                        delay(800L * fails)
                    }
                }
            }
        }
        // 收尾：退出的路留下的块，一路慢慢补
        while (true) {
            val i = queue.poll() ?: break
            var tries = 0
            while (!one(i, urls[tries % urls.size])) { if (++tries >= 6) error("网盘不让接着下了，过会儿再点继续"); delay(1500L * tries) }
        }
        d.speed = 0
        marks.delete()
        dst
    }
}
