package com.fuguang.lueying

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

internal object MotionTools {
    const val INSPECT = "inspect_video"
    const val FRAMES = "view_video_frames"
    const val RENDER = "render_work_frames"
    const val COMPARE = "compare_motion"
    val names = setOf(INSPECT, FRAMES, RENDER, COMPARE)
    private fun field(type: String, text: String) = JSONObject().put("type", type).put("description", text)
    val specs: List<ToolSpec> get() = listOf(
        INSPECT to "打开用户上传的视频，返回元数据和全片概览帧。不会听到声音；需要研究动作时继续区间抽帧。",
        FRAMES to "查看视频真实帧图，可指定 times_ms，或 start_ms/end_ms/count；支持 crop 局部放大。每张图带实际 PTS，重复目标可能对应同一帧。",
        RENDER to "查看作品在指定时刻的实际浏览器画面。后台默认当前稿；主对话必须带 work_id。作品需有可定位时间的动画，不能用静态截图代替。",
        COMPARE to "将参考视频与作品同一时刻的实际画面并排、叠加、差分查看。offset_ms 指作品的时间减去参考时间；不自动拉伸时间或扭曲画面。一次最多 6 个时刻。",
    ).map { (name, description) ->
        val props = JSONObject()
            .put("source", field("string", "视频附件引用，照抄消息里的 source；本工具不接受任意路径或网址"))
            .put("times_ms", field("array", "指定 1–12 个毫秒时刻；compare_motion 最多 6 个").put("items", JSONObject().put("type", "integer")))
            .put("start_ms", field("integer", "区间起点毫秒，默认 0"))
            .put("end_ms", field("integer", "区间终点毫秒，小于视频 duration_ms"))
            .put("count", field("integer", "概览默认 8；区间默认 8，最多 12；比较最多 6"))
            .put("max_edge", field("integer", "帧图最长边，默认 960，320–1600"))
            .put("crop", field("array", "原视频归一化 [x,y,width,height]；裁切在缩图前完成").put("items", JSONObject().put("type", "number")))
            .put("work_id", field("string", "已交付作品 ID；后台不填时使用当前稿"))
            .put("revision", field("integer", "后台稿件版本，填写最近 make_work 返回的 motion_revision"))
            .put("offset_ms", field("integer", "作品时间相对参考视频的偏移，默认 0"))
            .put("viewport_width", field("integer", "作品 CSS 画布宽度，默认 393，240–960；高度按参考宽高比确定"))
            .put("viewport_height", field("integer", "仅 render_work_frames 可指定高度，默认 852"))
        ToolSpec(name, description, JSONObject().put("type", "object").put("properties", props)
            .put("required", JSONArray(if (name == RENDER) emptyList<String>() else listOf("source"))))
    }

    fun references(history: List<ChatMsg>): List<String> {
        val attached = history.filter { it.role == "user" }.flatMap { it.files }
        val saved = history.flatMap { it.works }.distinct().flatMap { id ->
            Works.source(id)?.optJSONArray("motion_references")?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty()
        }
        return (attached + saved).filter { it.matches(Regex("[a-z0-9]+/[^/\\\\]+")) && Attach.isVideo(it) }.distinct()
    }
    fun context(refs: List<String>) = if (refs.isEmpty()) "" else "\n可查看的视频附件（source 原样传入工具）：\n" +
        refs.joinToString("\n") { "source=$it，文件名=${Attach.name(it)}" }
    val rule = "用户上传的视频可用 inspect_video、view_video_frames 自己查看；先看全片，再按需缩小区间看动作，不能把封面当看过视频。" +
        "复刻动效时把参考 source 放进 start_work.references，让后台继续查看原视频。只按实际返回的帧图判断；没有语音转写就不能声称听到了声音。"
    val authorRule = "你可以自己调用视频工具研究参考，直到足够理解。动效必须自包含，不依赖未保存的网络脚本。" +
        "推荐使用可暂停的时间轴或 Web Animations。提供 window.__motion={durationMs:总毫秒数,ready:初始化Promise,seek(ms){定位到该毫秒并暂停，返回Promise或void}}。" +
        "Canvas/WebGL/粒子必须实现 seek：固定随机种子，从可重复的初态按固定步长模拟，或直接按时间求状态；不能用真实等待时间当动画时间。" +
        "写出预览后用 render_work_frames 查看实际过程，并用 compare_motion 对照参考；根据差异决定修改或交付，轮数由结果决定。" +
        "compare_motion 的像素差只辅助定位，不等于视觉相似度；抽样之间的动作仍要针对性检查。不能用参考视频直接播放冒充代码复刻。"

    class Session(val refs: List<String>, private val vision: Boolean, private val reviewer: ArtworkJob.Model? = null,
                  private val checkpointDir: File? = null) {
        private var html: String? = null
        var revision = 0; private set
        var comparedRevision = -1; private set
        var lastEvidencePreview: File? = null; private set
        private var spent = 0
        private var inspected = false
        suspend fun draft(page: String) { html = page; revision++; comparedRevision = -1; checkpoint() }
        val canPresent get() = refs.isEmpty() || (inspected && comparedRevision == revision)

        suspend fun restore(): String = withContext(Dispatchers.IO) {
            val saved = checkpointDir?.let { File(it, "motion.json") }?.takeIf { it.isFile } ?: return@withContext ""
            runCatching {
                val o = JSONObject(saved.readText())
                spent = o.optInt("spent", 0).coerceIn(0, 192)
                revision = o.optInt("revision", 0).coerceAtLeast(0)
                html = o.optString("html").takeIf { it.isNotBlank() }
                comparedRevision = -1
                "\n上次任务保留的第 $revision 稿（尚未在本次恢复中确认交付；先重新 make_work 建立预览，再检查）：\n${html.orEmpty().take(60_000)}"
            }.getOrDefault("")
        }

        private suspend fun checkpoint() = withContext(Dispatchers.IO) {
            checkpointDir?.let { VideoFrames.atomic(File(it, "motion.json"), JSONObject().put("spent", spent)
                .put("revision", revision).put("html", html ?: "").toString()) }
            Unit
        }

        suspend fun run(name: String, args: JSONObject): ToolOut = withContext(Dispatchers.Default) { try {
            require(vision || reviewer != null) { "没有可用的视觉模型，无法完成视频画面检查；请配置能看图的模型" }
            require(spent < 192) { "本次画面检查预算已用完，已保留预览与帧图；本稿不能自动标记为完成，可调整要求后继续" }
            when (name) {
                INSPECT, FRAMES -> {
                    val info = source(args)
                    val batch = VideoFrames.extract(info, times(info, args), args.optInt("max_edge", 960), crop(args))
                    val rows = batch.frames.mapIndexed { i, frame ->
                        JSONObject().put("index", i).put("actual_pts_us", frame.ptsUs).put("requested_ms", JSONArray(frame.requested))
                    }
                    val metadata = info.json().put("frames", JSONArray(rows)).put("missing_times_ms", JSONArray(batch.missing))
                        .put("crop", crop(args)?.let { JSONArray(it) } ?: JSONObject.NULL)
                        .put("warning", "仅查看这些抽样画面；音频未转写，HDR/压缩颜色不保证与播放器完全一致")
                    val pictures = batch.frames.map { frame -> Picture(frame.file.readBytes(), "参考 · ${MotionTimes.label(frame.ptsUs)}", "image/jpeg") }
                    val out = visual(metadata.toString(), pictures)
                    inspected = true
                    out
                }
                RENDER, COMPARE -> review(name, args)
                else -> ToolOut("没有这个视频工具", failed = true)
            }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            ToolOut("画面提取或渲染超时，已保留制作进度；请缩小区间或减少帧数后重试。", failed = true)
        }
        catch (e: Exception) { ToolOut("画面检查未完成：${e.message ?: e.javaClass.simpleName}。不能据此声称已验证。", failed = true) }
        finally { checkpoint() } }

        private suspend fun source(a: JSONObject): VideoFrames.Info {
            val source = a.optString("source")
            require(source in refs) { "source 不在这段对话提供的视频附件中，请照抄附件引用" }
            return VideoFrames.info(source)
        }

        private fun times(info: VideoFrames.Info, a: JSONObject, defaultCount: Int = 8): List<Long> {
            val arr = a.optJSONArray("times_ms")
            return if (arr != null) MotionTimes.points(info.duration, List(arr.length()) { arr.getLong(it) })
            else MotionTimes.plan(info.duration, a.optLong("start_ms", 0), a.optLong("end_ms", (info.duration - maxOf(1, info.duration / 20)).coerceAtLeast(0)), a.optInt("count", defaultCount))
        }

        private fun crop(a: JSONObject): List<Double>? = a.optJSONArray("crop")?.let { arr -> List(arr.length()) { arr.getDouble(it) } }

        private suspend fun review(name: String, a: JSONObject): ToolOut {
            val current = html
            val workId = a.optString("work_id")
            val page = if (workId.isNotBlank()) Works.html(workId) else current
            require(!page.isNullOrBlank()) { "请先 make_work 生成预览，或提供已存在的 work_id" }
            if (workId.isBlank()) require(a.optInt("revision", -1) == revision) { "请传入当前 motion_revision=$revision，旧版本不能作为本稿检查结果" }
            val compare = name == COMPARE
            val reference = if (compare) source(a) else null
            val targets = if (reference != null) times(reference, a, 6) else {
                val arr = a.optJSONArray("times_ms") ?: error("render_work_frames 需要 times_ms")
                require(arr.length() in 1..12)
                List(arr.length()) { arr.getLong(it) }.also { require(it.all { t -> t >= 0 }) }
            }
            require(!compare || targets.size <= 6) { "每次对比最多 6 个时刻，缩小区间后可继续" }
            val batch = if (reference != null) VideoFrames.extract(reference, targets, a.optInt("max_edge", 960), crop(a)) else null
            require(batch == null || batch.missing.isEmpty()) { "部分参考时刻没有实际帧，请向前调整时间后重试" }
            val offset = a.optLong("offset_ms", 0)
            val renderedTimes = batch?.frames?.map { it.ptsUs / 1000 + offset } ?: targets
            require(renderedTimes.all { it >= 0 && it <= 3_600_000 }) { "作品时间超出可检查范围" }
            val width = a.optInt("viewport_width", 393)
            val roi = crop(a)
            val height = if (reference == null) a.optInt("viewport_height", 852) else
                (width * (reference.height * (roi?.get(3) ?: 1.0)) / (reference.width * (roi?.get(2) ?: 1.0))).roundToInt()
            val shots = MotionPreview.render(page, renderedTimes, width, height)
            val pictures = mutableListOf<Picture>()
            try {
                for ((i, shot) in shots.withIndex()) {
                    if (batch == null) pictures += Picture(png(shot.bitmap), "作品 · ${shot.timeMs} ms · 第 $revision 稿")
                    else {
                        val source = checkNotNull(BitmapFactory.decodeFile(batch.frames[i].file.path))
                        try {
                            val image = comparison(source, shot.bitmap)
                            try { pictures += Picture(png(image), "参考 ${MotionTimes.label(batch.frames[i].ptsUs)} / 作品 ${shot.timeMs} ms · 上排：参考、作品；下排：叠加、差分") }
                            finally { image.recycle() }
                        } finally { source.recycle() }
                    }
                }
            } finally { shots.forEach { it.bitmap.recycle() } }
            val out = visual(JSONObject().put("motion_revision", revision).put("reference", reference?.ref)
                .put("work_times_ms", JSONArray(renderedTimes)).put("offset_ms", offset)
                .put("verification", "实际 WebView 渲染；仅验证这些时刻，不代表全部动作或交互已通过").toString(), pictures)
            if (compare && workId.isBlank()) { inspected = true; comparedRevision = revision }
            return out
        }

        private suspend fun visual(text: String, pictures: List<Picture>): ToolOut {
            require(pictures.isNotEmpty()) { "没有可查看的画面" }
            require(spent + pictures.size <= 192) { "本次画面检查预算不足，已保留当前制作进度" }
            require(pictures.sumOf { it.bytes.size.toLong() } <= 16L * 1024 * 1024) { "帧图过大，请减少帧数或降低 max_edge" }
            spent += pictures.size
            val evidence = withContext(Dispatchers.IO) { MotionEvidence.save(text, pictures) }
            lastEvidencePreview = File(VideoFrames.root, "evidence/$evidence/0.${if (pictures.first().mime == "image/jpeg") "jpg" else "png"}")
            val summary = "$text\n证据：$evidence\n本次已查看 $spent/192 张画面；需要时继续局部查看。"
            return if (vision) ToolOut(summary, pictures.map { picture ->
                ToolImage(picture.mime, android.util.Base64.encodeToString(picture.bytes, android.util.Base64.NO_WRAP), picture.caption)
            }) else {
                val helper = checkNotNull(reviewer)
                val sheet = sheet(pictures)
                val note = AttachLook.describe(helper.provider, helper.config, helper.name, sheet, "image/jpeg",
                    "按时间顺序查看下列真实帧图或对比图，描述动作变化、具体差异和仍需查看的区间。不要声称听到了声音，不要给虚构相似度。\n$text\n" + pictures.joinToString("\n") { it.caption })
                ToolOut("$summary\n视觉助手 ${helper.name} 的观察（当前制作模型收到的是描述）：\n$note")
            }
        }
    }

    data class Picture(val bytes: ByteArray, val caption: String, val mime: String = "image/png")
    private fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
    private fun comparison(ref: Bitmap, work: Bitmap): Bitmap {
        val w = work.width; val h = work.height
        val scaled = Bitmap.createScaledBitmap(ref, w, h, true)
        val out = Bitmap.createBitmap(w * 2, h * 2 + 64, Bitmap.Config.ARGB_8888)
        try {
            val c = Canvas(out); c.drawColor(Color.WHITE)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 18f }
            c.drawBitmap(scaled, 0f, 32f, null); c.drawBitmap(work, w.toFloat(), 32f, null)
            c.drawText("Reference", 8f, 24f, p); c.drawText("Rebuild", w + 8f, 24f, p)
            c.drawBitmap(scaled, 0f, h + 64f, null)
            p.alpha = 128; c.drawBitmap(work, 0f, h + 64f, p); p.alpha = 255
            c.drawText("Overlay", 8f, h + 56f, p); c.drawText("Absolute difference", w + 8f, h + 56f, p)
            val a = IntArray(w * h); val b = IntArray(w * h)
            scaled.getPixels(a, 0, w, 0, 0, w, h); work.getPixels(b, 0, w, 0, 0, w, h)
            for (i in a.indices) a[i] = Color.rgb(abs(Color.red(a[i]) - Color.red(b[i])), abs(Color.green(a[i]) - Color.green(b[i])), abs(Color.blue(a[i]) - Color.blue(b[i])))
            out.setPixels(a, 0, w, w, h + 64, w, h)
            return out
        } finally { if (scaled !== ref) scaled.recycle() }
    }

    private fun sheet(pictures: List<Picture>): ByteArray {
        val out = Bitmap.createBitmap(960, pictures.size * 360, Bitmap.Config.ARGB_8888)
        try {
            val c = Canvas(out); c.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 20f }
            pictures.forEachIndexed { i, picture ->
                val b = checkNotNull(BitmapFactory.decodeByteArray(picture.bytes, 0, picture.bytes.size))
                try {
                    val scale = minOf(960f / b.width, 322f / b.height)
                    val w = b.width * scale; val h = b.height * scale
                    c.drawText(picture.caption.take(80), 8f, i * 360 + 25f, paint)
                    c.drawBitmap(b, null, RectF((960 - w) / 2, i * 360 + 32f, (960 + w) / 2, i * 360 + 32f + h), paint)
                } finally { b.recycle() }
            }
            return ByteArrayOutputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 85, it); it.toByteArray() }
        } finally { out.recycle() }
    }
}
