package com.fuguang.lueying

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 磁力探测（GitHub 上的安卓模拟器里跑）：adb 启动 App 时带上 probe_magnet，
 * 先用 libtorrent 拿种子信息，再交给迅雷下 90 秒、试边下边看读 1 MB，每一步写进 logcat（标签 FGPROBE）。
 */
object MagnetProbe {
    private fun say(s: String) = Log.i("FGPROBE", s)

    fun run(ctx: Context, magnet: String) = CoroutineScope(Dispatchers.IO).launch {
        val t0 = System.currentTimeMillis(); fun sec() = (System.currentTimeMillis() - t0) / 1000
        runCatching {
            say("[${sec()}s] 开始 · 迅雷能用：${Thunder.ok()}")
            val work = File(ctx.cacheDir, "probe").apply { deleteRecursively(); mkdirs() }
            val m = Torrent.fetch(magnet, File(work, "meta"), 90)
            if (m == null) { say("[${sec()}s] 90 秒没拿到种子信息"); return@launch }
            say("[${sec()}s] 种子信息：${m.items.size} 个文件，${m.items.sumOf { it.size } shr 20} MB")
            File(work, "meta.torrent").writeBytes(m.bytes)
            val big = m.items.maxBy { it.size }
            val d = Dl("probe", m.name, Sniffer.Media("magnet", magnet), "", "${big.i}", false, false, false)
            val job = launch {
                runCatching { Thunder.download(d, work, m.bytes) { done, total, sp, line -> d.done = done; d.total = total; d.speed = sp; d.note = line } }
                    .onFailure { say("[${sec()}s] 迅雷出错：$it") }
            }
            for (k in 0 until 18) {
                delay(5000)
                say("[${sec()}s] ${d.note} · ${d.done shr 10} KB / ${d.total shr 20} MB · ${d.speed shr 10} KB/s")
                Thunder.diag("probe")?.lines()?.forEach { say("    $it") }
                if (k == 6) {
                    val u = Thunder.url("probe"); say("[${sec()}s] 边下边看地址：$u")
                    if (u != null) runCatching {
                        val c = URL(u).openConnection() as HttpURLConnection
                        c.setRequestProperty("Range", "bytes=0-1048575"); c.readTimeout = 60_000
                        val n = c.inputStream.use { ins -> val b = ByteArray(65536); var t = 0L; while (true) { val x = ins.read(b); if (x < 0) break; t += x }; t }
                        say("[${sec()}s] 边下边看读开头：HTTP ${c.responseCode}，${n shr 10} KB")
                    }.onFailure { say("[${sec()}s] 边下边看读开头失败：$it") }
                }
            }
            job.cancel()
            say("[${sec()}s] 结束")
        }.onFailure { say("[${sec()}s] 出错：${Log.getStackTraceString(it).take(1500)}") }
    }
}
