package com.fuguang.lueying

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 她直接发文件（第 348 版，原型 2dkXurrZ5wtRtivmwhnq3b 第 9 版）：Word / PDF / txt / md，当场生成，不走后台作品。
 * 存在 files/sent/<批次>/<文件名>；消息里和作品一样记在 works 里，写成 "file:<批次>/<文件名>"，卡片插在她说到的地方。
 * 卡片（SentFileCard.kt）：打包时角标里变形弹跳，统一 1.8 秒第二次落地落成圆、涨开成格式色；轻点从下面升起面板：预览、打开、分享、存到手机。
 */
object SentFiles {
    const val TOOL = "send_file"
    const val PREFIX = "file:"

    private var root: File? = null
    private var loaded = false
    fun init(ctx: Context) {
        if (loaded) return
        root = File(ctx.applicationContext.filesDir, "sent").apply { mkdirs() }
        loaded = true
    }

    /** 这次打开 App 后发的：null 正在打包，"" 好了，别的是没打包成的原因。以前发的不在这里（文件在就是好的）。 */
    val state = mutableStateMapOf<String, String?>()
    /** 这次刚发、还没播完打包动效的。 */
    val fresh = HashSet<String>()
    /**
     * 打包动效的时钟记在文件上，不记在卡片上（第 350 版）：她说完话，回复收进聊天记录时卡片会重画一张，
     * 原来进度记在卡片里跟着丢了、从头再跳一遍（用户录屏：1.8 秒的事拖到 4 秒多）。
     * born：卡片放上去那一刻（System.nanoTime，和画面帧的时钟同一个）；last：落地落成圆的那一拍。
     */
    val born = HashMap<String, Long>()
    val last = HashMap<String, Int>()
    /** 同一份（格式 + 名字 + 内容）两分钟内又发：请求被退回、整轮重发时她会再调一次，不再多一张卡。 */
    private val recent = HashMap<String, Pair<String, Long>>()
    /** 底部面板正开着哪一份。 */
    var sheet by mutableStateOf<String?>(null)

    fun isRef(id: String) = id.startsWith(PREFIX)
    fun file(ref: String): File? = root?.let { File(it, ref.removePrefix(PREFIX)) }
    fun name(ref: String) = ref.substringAfterLast('/')
    fun fmt(ref: String) = when (name(ref).substringAfterLast('.', "").lowercase()) { "docx" -> "docx"; "pdf" -> "pdf"; "md" -> "md"; else -> "txt" }
    fun ok(ref: String): Boolean = state[ref].let { s -> if (state.containsKey(ref)) s == "" else file(ref)?.exists() == true }
    fun fail(ref: String): String? = if (state.containsKey(ref)) state[ref]?.takeIf { it.isNotEmpty() } else if (file(ref)?.exists() == true) null else "文件不在这台手机上了"

    /** 格式的说法、角标字、颜色（固定，不跟主题）。 */
    fun label(f: String) = when (f) { "docx" -> "Word"; "pdf" -> "PDF"; "md" -> "Markdown"; else -> "文本" }
    fun tag(f: String) = when (f) { "docx" -> "DOC"; "pdf" -> "PDF"; "md" -> "MD"; else -> "TXT" }
    fun color(f: String) = when (f) { "docx" -> Color(0xFF3C6FD1); "pdf" -> Color(0xFFD2554A); "md" -> Color(0xFF4A4F57); else -> Color(0xFF7C8B86) }
    private fun mime(f: String) = when (f) {
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "pdf" -> "application/pdf"; "md" -> "text/markdown"; else -> "text/plain"
    }

    /** 面板里预览的几行：发的时候的原文。 */
    fun preview(ref: String): String = runCatching { File(file(ref)!!.parentFile, ".src-" + name(ref) + ".txt").readText() }.getOrDefault("").let(::plain).take(600)

    /* ---------- 给模型 ---------- */

    val spec get() = ToolSpec(
        TOOL,
        "直接发文件给对方：Word（docx）、PDF、纯文本（txt）、Markdown（md），可以一次发几份（比如同一份要 Word 和 PDF 就写两项）。" +
            "马上在你这条回复下面出现文件卡，对方点开能打开、分享、存到手机。content 写正文：docx / pdf 用 Markdown（# 标题、- 列表、1. 编号、> 引用、| 表格 |、**加粗**），txt 和 md 照原样写进文件。" +
            "对方要什么就写什么，别自己加没要的标题和寒暄。",
        JSONObject().put("type", "object").put(
            "properties",
            JSONObject().put(
                "files",
                JSONObject().put("type", "array").put(
                    "items",
                    JSONObject().put("type", "object").put(
                        "properties",
                        JSONObject()
                            .put("name", JSONObject().put("type", "string").put("description", "文件名，不带扩展名，几个字"))
                            .put("format", JSONObject().put("type", "string").put("enum", JSONArray(listOf("docx", "pdf", "txt", "md"))))
                            .put("content", JSONObject().put("type", "string").put("description", "文件正文")),
                    ).put("required", JSONArray(listOf("name", "format", "content"))),
                ),
            ),
        ).put("required", JSONArray(listOf("files"))),
    )

    fun system(who: String) =
        "$who 要一份文件（Word、PDF、txt、md），用 $TOOL 直接发：卡片马上出现在你这条回复里，他点开就能打开、分享、存到手机。" +
            "几句话、一张清单、一份纪要、一篇笔记这种，都直接发文件，不用交给后台做作品；要精心设计的网页、幻灯片、带插图的长篇排版，才做作品。" +
            "发了就说一两句，不用把内容再贴进回复，也别说「卡上点导出」。"

    /** 跑工具：先把卡片放上（正在打包），再生成文件。onCard 拿到要记进消息的引用。 */
    suspend fun run(a: JSONObject, onCard: (String) -> Unit): ToolOut {
        val d = root ?: return ToolOut("文件没地方存。")
        val arr = a.optJSONArray("files") ?: return ToolOut("files 是空的：每份写清 name、format、content 再发一次。")
        val items = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        if (items.isEmpty()) return ToolOut("files 是空的：每份写清 name、format、content 再发一次。")
        val batch = System.currentTimeMillis().toString(36) + (100..999).random()
        val dir = File(d, batch).apply { mkdirs() }
        val taken = HashSet<String>()
        val again = ArrayList<String>()
        val now = System.currentTimeMillis()
        recent.entries.removeAll { now - it.value.second > 120_000 }
        val jobs = items.take(6).mapNotNull { o ->
            val f = o.optString("format").lowercase().let { if (it in setOf("docx", "pdf", "txt", "md")) it else "txt" }
            var base = o.optString("name").trim().replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").ifEmpty { "文件" }.take(40)
            if (base.endsWith(".$f", true)) base = base.dropLast(f.length + 1)
            var nm = "$base.$f"; var k = 2
            while (!taken.add(nm)) nm = "$base（${k++}）.$f"
            val sig = f + "|" + base + "|" + o.optString("content").trim().hashCode()
            recent[sig]?.let { (old, _) -> if (file(old)?.exists() == true || state.containsKey(old)) { again += name(old); return@mapNotNull null } }
            val ref = "$PREFIX$batch/$nm"
            recent[sig] = ref to now
            withContext(Dispatchers.Main) { state[ref] = null; fresh.add(ref); born[ref] = System.nanoTime(); onCard(ref) }
            Triple(ref, f, o.optString("content"))
        }
        val done = ArrayList<String>(); val bad = ArrayList<String>()
        jobs.forEach { (ref, f, body) ->
            val nm = name(ref)
            val err = withContext(Dispatchers.IO) {
                runCatching {
                    if (body.isBlank()) error("内容是空的")
                    val bytes = when (f) {
                        "docx" -> Docx.build("", body)
                        "pdf" -> PdfOut.build(body)
                        "md" -> body.toByteArray()
                        else -> plain(body).toByteArray()
                    }
                    File(dir, nm).writeBytes(bytes)
                    File(dir, ".src-$nm.txt").writeText(body)
                    null
                }.getOrElse { e -> (e.message ?: e.javaClass.simpleName).take(60) }
            }
            withContext(Dispatchers.Main) { state[ref] = err ?: "" }
            if (err == null) done += nm else bad += "$nm（$err）"
        }
        return ToolOut(
            (if (done.isEmpty()) "" else "发好了：" + done.joinToString("、") + "。文件卡在你这条回复里，不用把内容再贴一遍，说一两句就好。") +
                (if (bad.isEmpty()) "" else "没打包成：" + bad.joinToString("、") + "。可以改一改再发，或如实告诉对方。") +
                (if (again.isEmpty()) "" else again.joinToString("、") + " 刚才已经发过了，卡片就在你这条回复里，不用再发。") +
                (if (items.size > 6) "一次最多 6 份，后面的没发。" else ""),
        )
    }

    /** txt 和预览：去掉 Markdown 记号，保留换行。 */
    fun plain(md: String): String = md.replace("\r", "").lines().joinToString("\n") { l ->
        l.replace(Regex("^#{1,6}\\s+"), "").replace(Regex("^>\\s?"), "")
            .replace(Regex("\\*\\*(.+?)\\*\\*|__(.+?)__")) { it.groupValues[1].ifEmpty { it.groupValues[2] } }
            .replace(Regex("`(.+?)`")) { it.groupValues[1] }
    }.trim() + "\n"

    /* ---------- 面板里的三件事 ---------- */

    private fun uri(ctx: Context, ref: String) = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file(ref)!!)

    /** 打开：交给手机上能看的 App；没有就返回一句话。 */
    fun open(ctx: Context, ref: String): String? = try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri(ctx, ref), mime(fmt(ref))).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        null
    } catch (e: ActivityNotFoundException) { "手机上没有能打开${label(fmt(ref))}的 App，可以先分享或存到手机" } catch (e: Exception) { "打不开：" + (e.message ?: "").take(30) }

    fun share(ctx: Context, ref: String) {
        runCatching {
            val send = Intent(Intent.ACTION_SEND).setType(mime(fmt(ref))).putExtra(Intent.EXTRA_STREAM, uri(ctx, ref)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(Intent.createChooser(send, name(ref)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** 存到手机：下载 / Vignette。返回提示的话。 */
    suspend fun save(ctx: Context, ref: String): String = withContext(Dispatchers.IO) {
        runCatching {
            val src = file(ref)!!
            val r = ctx.contentResolver
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name(ref))
                put(MediaStore.Downloads.MIME_TYPE, mime(fmt(ref)))
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Vignette")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val u = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: error("下载文件夹打不开")
            try {
                r.openOutputStream(u)!!.use { os -> src.inputStream().use { it.copyTo(os) } }
                r.update(u, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            } catch (e: Throwable) { r.delete(u, null, null); throw e }
            "已存到「下载 / Vignette」"
        }.getOrElse { e -> "没存上：" + (e.message ?: e.javaClass.simpleName).take(30) }
    }
}

/** Markdown → PDF（A4，系统字体，中文跟着系统回退）。块的拆法和 Word 导出同一套（OfficeExport.kt 的 Md.parse）。 */
object PdfOut {
    private const val W = 595
    private const val H = 842
    private const val M = 56f
    private val inkC = android.graphics.Color.rgb(31, 31, 31)
    private val mutedC = android.graphics.Color.rgb(110, 110, 104)
    private val lineC = android.graphics.Color.rgb(217, 213, 205)

    fun build(md: String): ByteArray {
        val doc = PdfDocument()
        val width = (W - 2 * M).toInt()
        var page: PdfDocument.Page? = null
        var cv: Canvas? = null
        var y = 0f
        var n = 0
        fun newPage() {
            page?.let { doc.finishPage(it) }
            n++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, n).create()); cv = page!!.canvas; y = M
        }
        fun need(h: Float) { if (y + h > H - M && y > M) newPage() }
        fun spans(runs: List<Run>, boldAll: Boolean = false): CharSequence {
            val sb = SpannableStringBuilder()
            runs.forEach { r ->
                val s = sb.length; sb.append(r.text); val e = sb.length
                if (r.bold || boldAll) sb.setSpan(StyleSpan(Typeface.BOLD), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (r.italic) sb.setSpan(StyleSpan(Typeface.ITALIC), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (r.code) sb.setSpan(TypefaceSpan("monospace"), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            return sb
        }
        fun layout(text: CharSequence, size: Float, color: Int, w: Int, mult: Float = 1.5f): StaticLayout {
            val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; this.color = color }
            return StaticLayout.Builder.obtain(text, 0, text.length, p, w.coerceAtLeast(10)).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, mult).build()
        }
        /** 一行一行放，放不下就翻页；mark 在第一行左边画记号（圆点、编号）。 */
        fun place(l: StaticLayout, x: Float, mark: String? = null, markSize: Float = 0f, bar: Boolean = false) {
            for (i in 0 until l.lineCount) {
                val top = l.getLineTop(i).toFloat(); val bot = l.getLineBottom(i).toFloat()
                need(bot - top)
                val c = cv!!
                if (i == 0 && mark != null) c.drawText(mark, M + x - 14f, y + l.getLineBaseline(0) - top, TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = markSize; color = mutedC })
                if (bar) c.drawRect(M + x - 12f, y, M + x - 9.5f, y + (bot - top), Paint().apply { color = lineC })
                c.save(); c.translate(M + x, y - top); c.clipRect(0f, top, l.width.toFloat(), bot); l.draw(c); c.restore()
                y += bot - top
            }
        }
        newPage()
        val blocks = Md.parse(md)
        var no = 0; var lastList = -1
        blocks.forEachIndexed { idx, b ->
            when (b) {
                is Block.Heading -> {
                    val size = when (b.level) { 1 -> 20f; 2 -> 16f; else -> 13.5f }
                    if (idx > 0) y += if (b.level == 1) 12f else 8f
                    val l = layout(spans(b.runs, true), size, inkC, width, 1.3f); need(l.height.toFloat().coerceAtMost(60f)); place(l, 0f); y += 6f
                }
                is Block.Para -> { place(layout(spans(b.runs), 11.5f, inkC, width), 0f); y += 8f }
                is Block.Bullet -> { val ind = 16f + 16f * b.level; place(layout(spans(b.runs), 11.5f, inkC, (width - ind).toInt()), ind, "•", 11.5f); y += 3f }
                is Block.Numbered -> {
                    if (b.list != lastList) { lastList = b.list; no = 0 }
                    no++
                    place(layout(spans(b.runs), 11.5f, inkC, (width - 18).toInt()), 18f, "$no.", 11f); y += 3f
                }
                is Block.Quote -> { place(layout(spans(b.runs), 11.5f, mutedC, (width - 16).toInt()), 16f, bar = true); y += 8f }
                is Block.Rule -> { need(14f); y += 6f; cv!!.drawLine(M, y, W - M, y, Paint().apply { color = lineC; strokeWidth = .8f }); y += 8f }
                is Block.Image -> { place(layout("（图：" + b.alt.ifBlank { "图片" } + "）", 10.5f, mutedC, width), 0f); y += 6f }
                is Block.Table -> {
                    val cols = b.rows.maxOf { it.size }.coerceAtLeast(1)
                    val cw = width / cols
                    b.rows.forEachIndexed { ri, row ->
                        val ls = (0 until cols).map { ci -> layout(spans(row.getOrElse(ci) { emptyList() }, ri == 0), 10.5f, inkC, cw - 12, 1.35f) }
                        val rh = ls.maxOf { it.height } + 10f
                        need(rh)
                        val c = cv!!
                        if (ri == 0) c.drawRect(M, y, W - M, y + rh, Paint().apply { color = android.graphics.Color.rgb(242, 240, 235) })
                        ls.forEachIndexed { ci, l -> c.save(); c.translate(M + ci * cw + 6f, y + 5f); l.draw(c); c.restore() }
                        c.drawLine(M, y + rh, W - M, y + rh, Paint().apply { color = lineC; strokeWidth = .6f })
                        y += rh
                    }
                    y += 10f
                }
            }
        }
        page?.let { doc.finishPage(it) }
        val out = ByteArrayOutputStream()
        doc.writeTo(out); doc.close()
        return out.toByteArray()
    }
}
