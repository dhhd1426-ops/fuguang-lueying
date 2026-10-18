package com.fuguang.lueying

import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 视频自己的字幕（YouTube、B 站这类有字幕轨的）：用 yt-dlp 拿下来，按播放时间显示，提前翻好。
 * 比录声音再认字准、没有延迟，也不用录制许可。拿不到才退回录声音认字（LiveService）。
 */
object CaptionTrack {
    class Cue(val start: Double, val end: Double, val text: String, var trans: String = "")

    /** 拿字幕：优先要目标语言（有就不用翻），再要英文、中文，人工字幕先于自动字幕。 */
    suspend fun fetch(ctx: android.content.Context, pageUrl: String, to: String): Pair<List<Cue>, Boolean>? = withContext(Dispatchers.IO) {
        if (!YtDlp.ready()) return@withContext null
        val dir = File(ctx.cacheDir, "subs").apply { deleteRecursively(); mkdirs() }
        val want = when (to) { "中文" -> "zh-Hans,zh-CN,zh,zh-Hant,en,en-US,en-GB,en-orig"; "日本語" -> "ja,en,en-US,en-orig,zh-Hans,zh"; else -> "en,en-US,en-GB,en-orig,zh-Hans,zh" }
        runCatching {
            val r = YoutubeDLRequest(pageUrl).apply {
                android.webkit.CookieManager.getInstance().getCookie(pageUrl)?.takeIf { it.isNotBlank() }?.let { addOption("--add-header", "Cookie:$it") }
                addOption("--skip-download"); addOption("--no-playlist")
                addOption("--write-subs"); addOption("--write-auto-subs")
                addOption("--sub-langs", want)
                addOption("--sub-format", "vtt/srt/best")
                addOption("--convert-subs", "vtt")
                addOption("-o", File(dir, "s").absolutePath)
                addOption("--socket-timeout", "15")
            }
            YoutubeDL.getInstance().execute(r, null, null)
        }
        val files = dir.listFiles()?.filter { it.name.endsWith(".vtt") }.orEmpty()
        if (files.isEmpty()) return@withContext null
        // 文件名 s.<语言>.vtt：按 want 的先后挑
        val order = want.split(',')
        val pick = files.minByOrNull { f -> order.indexOfFirst { l -> f.name.endsWith(".$l.vtt") }.let { if (it < 0) 99 else it } } ?: return@withContext null
        val lang = pick.name.removePrefix("s.").removeSuffix(".vtt")
        val cues = group(parse(pick.readText()))
        if (cues.isEmpty()) return@withContext null
        val native = (to == "中文" && lang.startsWith("zh")) || (to == "English" && lang.startsWith("en")) || (to == "日本語" && lang.startsWith("ja"))
        cues to native
    }

    private fun t(s: String): Double {
        val p = s.trim().replace(',', '.').split(':')
        return when (p.size) { 3 -> p[0].toDouble() * 3600 + p[1].toDouble() * 60 + p[2].toDouble(); 2 -> p[0].toDouble() * 60 + p[1].toDouble(); else -> p[0].toDoubleOrNull() ?: 0.0 }
    }

    /** 读 WebVTT；YouTube 自动字幕一条里会带上一句的重复和逐字时间标签，去掉。 */
    private fun parse(vtt: String): List<Cue> {
        val out = ArrayList<Cue>()
        val blocks = vtt.replace("\r", "").split(Regex("\n\\s*\n"))
        var lastLine = ""
        for (b in blocks) {
            val lines = b.lines().filter { it.isNotBlank() }
            val ti = lines.indexOfFirst { "-->" in it }; if (ti < 0) continue
            val (a, z) = lines[ti].split("-->").map { it.trim().substringBefore(' ') }
            val body = lines.drop(ti + 1).map { it.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&gt;", ">").replace("&lt;", "<").replace("&nbsp;", " ").trim() }.filter { it.isNotEmpty() }
            if (body.isEmpty()) continue
            // 自动字幕：第一行是上一句的重复，只要新的那行
            val text = body.filter { it != lastLine }.joinToString(" ").trim()
            lastLine = body.last()
            val s = t(a); val e = t(z)
            if (text.isEmpty() || e - s < 0.05) continue
            if (out.isNotEmpty() && out.last().text == text) continue
            out += Cue(s, e, text)
        }
        return out
    }

    /** 碎的合成一句：遇到句号、问号，或攒到 6 秒 / 18 个词就断。 */
    private fun group(cs: List<Cue>): List<Cue> {
        val out = ArrayList<Cue>()
        var s = -1.0; var e = 0.0; val sb = StringBuilder()
        fun flush() { if (sb.isNotBlank()) out += Cue(s, e, sb.toString().trim()); sb.clear(); s = -1.0 }
        for (c in cs) {
            if (s < 0) s = c.start
            if (sb.isNotEmpty()) sb.append(if (c.text.first().code in 0x4E00..0x9FFF) "" else " ")
            sb.append(c.text); e = c.end
            val words = sb.split(' ').size
            if (Regex("[.?!。？！…]$").containsMatchIn(c.text) || e - s >= 6.0 || words >= 18) flush()
        }
        flush()
        return out
    }

    /** 把一批句子一次翻好（编号对齐）。 */
    suspend fun translate(batch: List<Cue>, to: String, before: String) {
        val st = Live.store ?: return
        val p = provider(st.activeProvider); val c = st.config(st.activeProvider)
        if (!c.configured || st.activeModel.isBlank()) { Live.fail("还没设置好模型，翻不了"); return }
        val sys = "你是视频字幕翻译。把每一行翻成${to}，口语、自然、简短，能当字幕看；人名、术语前后一致。按原来的编号一行一条输出「编号. 译文」，不多不少，不解释。" +
            (if (before.isNotEmpty()) "\n前文（供参考，不用翻）：$before" else "")
        val user = batch.mapIndexed { i, q -> "${i + 1}. ${q.text}" }.joinToString("\n")
        val out = runCatching { ChatApi.complete(p, c, st.activeModel, sys, user, 2000) }.getOrNull() ?: return
        out.lines().forEach { l -> Regex("^\\s*(\\d+)[.、)）]\\s*(.+)$").find(l)?.let { m -> batch.getOrNull(m.groupValues[1].toInt() - 1)?.trans = m.groupValues[2].trim() } }
    }

    /** 下载视频时一起存的双语字幕（srt）。 */
    fun srt(cues: List<Cue>): String {
        fun f(x: Double): String { val ms = (x * 1000).toLong(); return "%02d:%02d:%02d,%03d".format(ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000) }
        return cues.mapIndexed { i, q -> "${i + 1}\n${f(q.start)} --> ${f(q.end)}\n" + (if (q.trans.isNotEmpty() && q.trans != q.text) q.trans + "\n" else "") + q.text + "\n" }.joinToString("\n")
    }
}
