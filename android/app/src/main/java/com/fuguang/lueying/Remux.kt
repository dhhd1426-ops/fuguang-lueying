package com.fuguang.lueying

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * 把下好的画面、声音装进一个 mp4，装完先自己放一下（读开头、跳到结尾读一帧）确认能看再交出去。
 * 先用 App 里带的 ffmpeg（yt-dlp 那一套）：超过 4 GB 会写 64 位位置表，HEVC、AV1 也认。
 * 不行才用系统的 MediaMuxer：它只会写 32 位位置表，超过 4 GB 的文件后半段位置全错，所以 3.9 GB 以上不交给它。
 * 以前用 MediaMuxer 合坏的文件，`repair` 用 ffmpeg 原样重新装一遍，不重新下。
 */
object Remux {
    private const val LIMIT = 3_900_000_000L

    /** picks：（文件, 只要哪类轨 "video/" "audio/"，或 "" 全要）。 */
    suspend fun pack(ctx: Context, picks: List<Pair<File, String>>, out: File) = withContext(Dispatchers.IO) {
        val args = ArrayList<String>()
        picks.forEach { args += listOf("-i", it.first.path) }
        picks.forEachIndexed { i, (_, want) -> args += listOf("-map", when (want) { "video/" -> "$i:v:0"; "audio/" -> "$i:a:0"; else -> "$i" }) }
        if (ffmpeg(ctx, args, out) && playable(out.path)) return@withContext
        out.delete()
        if (picks.sumOf { it.first.length() } > LIMIT) error("文件超过 4 GB，合不成一个")
        muxer(picks, out)
        if (!playable(out.path)) { out.delete(); error("合出来的文件放不了") }
    }

    /** 下好的文件重新装一遍盒子。成了返回新的大小。 */
    suspend fun repair(ctx: Context, d: Dl): Long = withContext(Dispatchers.IO) {
        val src = path(ctx, d) ?: error("找不到这个文件")
        val size = File(src).length()
        val dir = File(ctx.cacheDir, "fix").apply { deleteRecursively(); mkdirs() }
        val free = dir.usableSpace - size - (200L shl 20)
        if (free < 0) error("空间不够，还差 ${mb(-free)}")
        val out = File(dir, "fixed.${if (d.audioOnly) "m4a" else "mp4"}")
        try {
            if (!ffmpeg(ctx, listOf("-i", src, "-map", "0"), out) || !playable(out.path)) error("重新合成也没成")
            if (d.out.startsWith("content://")) ctx.contentResolver.openOutputStream(Uri.parse(d.out), "wt")!!.use { o -> out.inputStream().use { it.copyTo(o, 1 shl 16) } }
            else if (!out.renameTo(File(src))) out.copyTo(File(src), true)
            out.length()
        } finally { dir.deleteRecursively() }
    }

    /** 相册里的那份：自己存进去的文件，用路径也能读。 */
    internal fun path(ctx: Context, d: Dl): String? {
        if (!d.out.startsWith("content://")) return d.out.takeIf { File(it).canRead() }
        return runCatching {
            ctx.contentResolver.query(Uri.parse(d.out), arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()?.takeIf { File(it).canRead() }
    }

    private suspend fun ffmpeg(ctx: Context, input: List<String>, out: File): Boolean {
        if (!YtDlp.boot()) return false
        val bin = File(ctx.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        if (!bin.exists()) return false
        val pk = File(ctx.noBackupFilesDir, "youtubedl-android/packages")
        val log = File(out.parentFile, "ffmpeg.log")
        // 字幕、数据轨放不进 mp4，丢掉；只换盒子，不重新编码
        val cmd = listOf(bin.path, "-y", "-nostdin", "-loglevel", "error") + input + listOf("-dn", "-sn", "-c", "copy", "-f", "mp4", out.path)
        val pb = ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log)
        pb.environment()["LD_LIBRARY_PATH"] = "${pk.path}/python/usr/lib:${pk.path}/ffmpeg/usr/lib"
        val p = runCatching { pb.start() }.getOrNull() ?: return false
        return try { runInterruptible { p.waitFor() } == 0 && out.length() > 0 } finally { if (p.isAlive) p.destroy(); log.delete() }
    }

    /** 能不能放：有画面或声音、有时长，开头和结尾都读得出一帧（位置表写坏的文件结尾读不出来）。 */
    internal fun playable(path: String): Boolean = runCatching {
        val e = MediaExtractor(); e.setDataSource(path)
        try {
            val t = (0 until e.trackCount).firstOrNull { e.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().let { m -> m.startsWith("video/") || m.startsWith("audio/") } } ?: return false
            val f = e.getTrackFormat(t); e.selectTrack(t)
            val dur = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L
            val buf = ByteBuffer.allocate(8 shl 20)
            if (e.readSampleData(buf, 0) <= 0) return false
            if (dur > 6_000_000) { e.seekTo(dur - 3_000_000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC); if (e.readSampleData(buf, 0) <= 0 || e.sampleTime < dur / 2) return false }
            true
        } finally { e.release() }
    }.getOrDefault(false)

    /** 系统 MediaMuxer：按时间先后从几个文件里轮流取样本写，画面和声音交错放。 */
    private fun muxer(picks: List<Pair<File, String>>, out: File) {
        val mm = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val ex = ArrayList<Pair<MediaExtractor, Map<Int, Int>>>()
        try {
            picks.forEach { (f, want) ->
                val e = MediaExtractor(); e.setDataSource(f.path)
                val map = HashMap<Int, Int>()
                for (i in 0 until e.trackCount) {
                    val mime = e.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
                    if ((want.isEmpty() && (mime.startsWith("video/") || mime.startsWith("audio/"))) || (want.isNotEmpty() && mime.startsWith(want) && map.isEmpty())) {
                        e.selectTrack(i); map[i] = mm.addTrack(e.getTrackFormat(i))
                    }
                }
                if (map.isEmpty()) error("这个文件里没有${if (want == "audio/") "声音" else "画面"}")
                ex += e to map
            }
            mm.start()
            val buf = ByteBuffer.allocate(8 shl 20); val info = MediaCodec.BufferInfo()
            val live = ex.toMutableList()
            while (live.isNotEmpty()) {
                val pair = live.minBy { it.first.sampleTime }
                val (e, map) = pair
                val n = e.readSampleData(buf, 0)
                if (n < 0) { live.remove(pair); continue }
                map[e.sampleTrackIndex]?.let { ti ->
                    info.set(0, n, e.sampleTime, if (e.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    mm.writeSampleData(ti, buf, info)
                }
                e.advance()
            }
            mm.stop()
        } finally { runCatching { mm.release() }; ex.forEach { runCatching { it.first.release() } } }
    }
}
