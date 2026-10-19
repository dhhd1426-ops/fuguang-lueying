package com.fuguang.lueying

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 磁力探测（CI 上跑，电脑上用同一个 libtorrent 和同一份 Torrent / TorrentServe 代码）：
 * 拿 Ubuntu 官方种子的 infohash 做一个只有 hash 的磁力链接（和网页上常见的磁力一样，不带 Ubuntu 自己的 Tracker），
 * 走一遍：找种子信息 → 下 → 本机小服务按 Range 读开头 2 MB。每一步花了多久、连上几个人写进报告。
 * 只报告，不判失败（网络慢不该挡住编译）。
 */
class TorrentProbeTest {
    private val log = StringBuilder()
    private fun note(s: String) { println(s); log.appendLine(s) }

    @Test
    fun probe() = runBlocking {
        val out = File("build/torrent-report").apply { mkdirs() }
        try { run(File(out, "work")) } catch (e: Throwable) { note("出错：$e") }
        File(out, "report.txt").writeText(log.toString())
    }

    private suspend fun run(work: File) {
        val t0 = System.currentTimeMillis()
        fun sec() = (System.currentTimeMillis() - t0) / 1000
        Torrent.session()
        for (i in 0 until 30) { if (Torrent.nodes() > 20) break; delay(1000) }
        note("[${sec()}s] DHT 节点 ${Torrent.nodes()}")

        val page = withContext(Dispatchers.IO) { URL("https://releases.ubuntu.com/24.04/").readText() }
        val name = Regex("href=\"(ubuntu-[0-9.]+-live-server-amd64\\.iso\\.torrent)\"").find(page)?.groupValues?.get(1)
            ?: Regex("href=\"([^\"]+\\.iso\\.torrent)\"").find(page)!!.groupValues[1]
        val bytes = withContext(Dispatchers.IO) { URL("https://releases.ubuntu.com/24.04/$name").readBytes() }
        val hash = org.libtorrent4j.TorrentInfo(bytes).infoHash().toHex()
        note("[${sec()}s] 测试种子 $name  $hash")

        val magnet = "magnet:?xt=urn:btih:$hash&dn=ubuntu"
        val m0 = sec()
        val meta = Torrent.fetch(magnet, File(work, "meta"), 120)
        if (meta == null) { note("[${sec()}s] 磁力拿种子信息：120 秒没拿到"); return }
        note("[${sec()}s] 磁力拿种子信息：${sec() - m0} 秒，${meta.items.size} 个文件，${meta.items.sumOf { it.size } shr 20} MB")

        val big = meta.items.maxBy { it.size }
        val d = Dl("probe", meta.name, Sniffer.Media("magnet", magnet), "1 个文件", "${big.i}", false, false, false)
        val dir = File(work, "dl").apply { mkdirs() }
        var first = -1L
        val job = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            runCatching { Torrent.download(d, dir) { st -> if (first < 0 && st.totalPayloadDownload() > 0) first = sec(); d.done = st.totalWantedDone() } }
                .onFailure { note("下载出错：$it") }
        }
        for (k in 0 until 12) {
            delay(5000)
            note("[${sec()}s] ${Torrent.diag("probe").lineSequence().take(3).joinToString(" | ")}")
            if (k == 11) note(Torrent.diag("probe"))
        }
        note("第一个字节在第 ${first} 秒到")

        // 边下边看：从本机小服务读开头 2 MB、结尾前 1 MB
        for ((label, range) in listOf("开头 2 MB" to "bytes=0-2097151", "结尾 1 MB" to "bytes=${big.size - 1048576}-")) {
            val s0 = System.currentTimeMillis()
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val c = URL(TorrentServe.urlFor("probe", big.i)).openConnection() as HttpURLConnection
                    c.setRequestProperty("Range", range); c.readTimeout = 150_000
                    val code = c.responseCode
                    val n = c.inputStream.use { ins -> val b = ByteArray(65536); var t = 0L; while (true) { val x = ins.read(b); if (x < 0) break; t += x }; t }
                    "HTTP $code，读到 ${n / 1024} KB"
                }.getOrElse { "失败：$it" }
            }
            note("[${sec()}s] 边下边看 $label：$r，用了 ${(System.currentTimeMillis() - s0) / 1000} 秒")
            if (TorrentServe.lastError.isNotEmpty()) note("  小服务出错：${TorrentServe.lastError}")
        }
        note(Torrent.diag("probe"))
        job.cancel()
    }
}
