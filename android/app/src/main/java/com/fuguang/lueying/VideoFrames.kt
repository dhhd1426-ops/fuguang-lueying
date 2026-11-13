package com.fuguang.lueying

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** Local decoder. Callers supply conversation-owned attachment refs, never model-provided filesystem paths or URLs. */
internal object VideoFrames {
    private lateinit var context: Context
    internal lateinit var root: File
    private val decoder = Mutex()
    private val hashes = java.util.concurrent.ConcurrentHashMap<String, String>()
    data class Info(val ref: String, val id: String, val duration: Long, val width: Int, val height: Int, val rotation: Int) {
        fun json() = JSONObject().put("source", ref).put("video_id", id).put("duration_ms", duration)
            .put("width", width).put("height", height).put("rotation", rotation)
            .put("time_origin", "first_decoded_video_frame")
    }
    data class Frame(val file: File, val ptsUs: Long, val requested: List<Long>)
    data class Batch(val info: Info, val frames: List<Frame>, val missing: List<Long>, val crop: List<Double>?)

    fun init(ctx: Context) {
        context = ctx.applicationContext
        root = File(context.filesDir, "motion").apply { mkdirs() }
    }

    internal fun hash(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    suspend fun info(ref: String): Info = withContext(Dispatchers.IO) {
        require(Attach.isVideo(ref)) { "附件不是支持的视频" }
        val file = Attach.file(ref)
        require(file.isFile && file.length() > 0) { "视频附件不存在或为空" }
        val key = "${file.canonicalPath}:${file.length()}:${file.lastModified()}"
        val id = hashes[key] ?: file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(64 * 1024)
            while (true) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val n = input.read(buf); if (n < 0) break
                digest.update(buf, 0, n)
            }
            digest.digest().joinToString("") { "%02x".format(it) }.also { hashes[key] = it }
        }
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.path)
            fun meta(k: Int) = r.extractMetadata(k)?.toLongOrNull() ?: 0
            val duration = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val rotation = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION).toInt()
            val w = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt()
            val h = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt()
            require(duration > 0 && w > 0 && h > 0) { "无法读取视频轨道" }
            Info(ref, id, duration, if (rotation % 180 == 0) w else h, if (rotation % 180 == 0) h else w, rotation)
        } finally { r.release() }
    }

    suspend fun extract(info: Info, times: List<Long>, edge: Int, crop: List<Double>? = null): Batch = decoder.withLock {
        withContext(Dispatchers.IO) {
            val points = MotionTimes.points(info.duration, times)
            require(edge in 320..1600) { "max_edge 必须为 320–1600" }
            if (crop != null) require(crop.size == 4 && crop.all(Double::isFinite) && crop[0] >= 0 && crop[1] >= 0 &&
                crop[2] > 0 && crop[3] > 0 && crop[0] + crop[2] <= 1 && crop[1] + crop[3] <= 1) { "crop 为归一化的 [x,y,width,height]，必须在画面内" }
            val folder = File(root, "frames/${info.id}/${hash("v1:$points:$edge:$crop")}").apply { mkdirs() }
            val manifest = File(folder, "frames.json")
            fun read(): Batch? = runCatching {
                val a = JSONArray(manifest.readText())
                val frames = List(a.length()) { i ->
                    val row = a.getJSONObject(i)
                    val f = File(folder, row.getString("file"))
                    require(f.isFile && f.length() > 0)
                    Frame(f, row.getLong("pts_us"), emptyList())
                }
                require(frames.isNotEmpty())
                batch(info, frames, points, crop)
            }.getOrNull()
            read()?.let { return@withContext it }
            check(YtDlp.boot()) { "本机视频引擎初始化失败" }
            val bin = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
            check(bin.isFile) { "本机 FFmpeg 不可用" }
            val log = File(folder, "decode.log")
            val filters = mutableListOf("setpts=PTS-STARTPTS", "select='${MotionTimes.select(points)}'", "showinfo")
            if (crop != null) filters += String.format(Locale.US, "crop=iw*%.8f:ih*%.8f:iw*%.8f:ih*%.8f", crop[2], crop[3], crop[0], crop[1])
            filters += "scale=w='min($edge,iw)':h='min($edge,ih)':force_original_aspect_ratio=decrease"
            val args = listOf(bin.path, "-hide_banner", "-nostdin", "-y", "-loglevel", "info", "-i", Attach.file(info.ref).path,
                "-map", "0:v:0", "-an", "-sn", "-dn", "-vf", filters.joinToString(","),
                "-frames:v", points.size.toString(), "-vsync", "0", "-q:v", "3", File(folder, "f-%03d.jpg").path)
            val pb = ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log)
            val pk = File(context.noBackupFilesDir, "youtubedl-android/packages")
            pb.environment()["LD_LIBRARY_PATH"] = "${pk.path}/python/usr/lib:${pk.path}/ffmpeg/usr/lib"
            val process = pb.start()
            try {
                val exit = withTimeout(90_000) { runInterruptible { process.waitFor() } }
                check(exit == 0) { "视频解码失败，可能是本机不支持的编码或损坏的文件" }
                val pts = MotionTimes.pts(log.readText())
                val files = folder.listFiles { f -> f.name.matches(Regex("f-\\d+\\.jpg")) }.orEmpty().sortedBy { it.name }
                check(files.isNotEmpty() && files.size == pts.size) { "未得到带实际时间戳的帧图，不能确认抽帧结果" }
                val rows = files.mapIndexed { i, f -> JSONObject().put("file", f.name).put("pts_us", pts[i]) }
                atomic(manifest, JSONArray(rows).toString())
                val result = checkNotNull(read())
                trimCache(folder)
                result
            } finally {
                if (process.isAlive) process.destroyForcibly()
                log.delete()
                if (!manifest.isFile) folder.deleteRecursively()
            }
        }
    }

    private fun batch(info: Info, frames: List<Frame>, points: List<Long>, crop: List<Double>?): Batch {
        val assigned = points.groupBy { t -> frames.indexOfFirst { it.ptsUs + 1 >= t * 1000 } }
        return Batch(info, frames.mapIndexed { i, f -> f.copy(requested = assigned[i].orEmpty()) }, assigned[-1].orEmpty(), crop)
    }

    internal fun atomic(file: File, text: String) {
        file.parentFile?.mkdirs()
        val a = AtomicFile(file)
        val stream = a.startWrite()
        try { stream.write(text.toByteArray()); a.finishWrite(stream) }
        catch (e: Exception) { a.failWrite(stream); throw e }
    }

    private fun trimCache(except: File) {
        val folders = File(root, "frames").listFiles().orEmpty().flatMap { it.listFiles().orEmpty().toList() }
        var total = folders.sumOf { d -> d.listFiles().orEmpty().sumOf { it.length() } }
        for (d in folders.sortedBy { it.lastModified() }) {
            if (total <= 256L * 1024 * 1024) break
            if (d == except) continue
            val size = d.listFiles().orEmpty().sumOf { it.length() }
            if (d.deleteRecursively()) total -= size
        }
    }

    /** Attachment cover is only a thumbnail, never presented to the model as a timestamped source frame. */
    suspend fun cover(ref: String): Bitmap? = withContext(Dispatchers.IO) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(Attach.file(ref).path)
            r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 320)
        } catch (_: Exception) { null } finally { r.release() }
    }
}

