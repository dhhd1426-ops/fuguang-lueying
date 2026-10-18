package com.fuguang.lueying

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray

/**
 * 认出网页里的视频（照 N_m3u8DL-RE、各家浏览器「视频嗅探」的做法）：
 * 看网页发出去的每个请求——.m3u8 是分段视频，.mp4/.webm/.flv 是整段视频，.m4s 是画面和声音分开传的（B 站这类）；
 * 再加上页面里 <video> 标签的地址。认出来的放进 found，浏览器右下角就浮出「下载视频」。
 */
object Sniffer {
    /** 一个能下的东西。kind：file 整段文件、hls 分段、dash 画面+声音分开。 */
    data class Media(
        val kind: String, val url: String, val audio: String? = null,
        val headers: Map<String, String> = emptyMap(), val page: String = "",
        val mime: String = "", val size: Long = -1, val name: String = "",
    )

    val found = mutableStateListOf<Media>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var page = ""
    // DASH：同一页里先后看到的 m4s（按去掉参数的地址分），猜哪条是画面、哪条是声音
    private val m4s = LinkedHashMap<String, Pair<String, Map<String, String>>>()

    private val video = Regex("\\.(mp4|webm|mov|m4v|flv|mkv)$")
    private val skip = Regex("(^|[/._-])(ad|ads|advert|preroll|logo|thumb|cover|poster|loading)([/._-]|$)")
    /** B 站声音轨的编号：30216 / 30232 / 30280 / 30250（杜比）/ 30251（无损）。 */
    private val biliAudio = Regex("-(30216|30232|30280|30250|30251)\\.m4s")

    fun reset(u: String) { page = u; m4s.clear(); main.post { found.clear() } }

    private fun add(m: Media) = main.post {
        if (found.any { it.url == m.url || (m.kind == "dash" && it.kind == "dash") }) {
            if (m.kind == "dash") { val i = found.indexOfFirst { it.kind == "dash" }; if (i >= 0) found[i] = m }
            return@post
        }
        // 有了 dash 或 hls，就不再列它们的碎片
        found.add(m)
    }

    /** 网页发出去的每个请求都过一遍（在后台线程）。 */
    fun saw(url: String, headers: Map<String, String>, pageUrl: String?) {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        val ref = headers["Referer"] ?: headers["referer"] ?: pageUrl ?: page
        val h = headers.filterKeys { it.equals("Referer", true) || it.equals("User-Agent", true) || it.equals("Origin", true) }.toMutableMap()
        if (h.keys.none { it.equals("Referer", true) } && ref.isNotEmpty()) h["Referer"] = ref
        when {
            path.endsWith(".m3u8") -> add(Media("hls", url, headers = h, page = page))
            path.endsWith(".m4s") -> {
                val key = path.substringAfterLast('/')
                synchronized(m4s) { if (key !in m4s) m4s[key] = url to h }
                pairDash()
            }
            video.containsMatchIn(path) && !skip.containsMatchIn(path.substringAfterLast('/')) -> add(Media("file", url, headers = h, page = page, mime = "video/" + path.substringAfterLast('.')))
        }
    }

    private fun pairDash() {
        val all = synchronized(m4s) { m4s.values.toList() }
        if (all.size < 2) return
        val audio = all.firstOrNull { biliAudio.containsMatchIn(it.first) } ?: all.last()
        val vid = all.firstOrNull { it !== audio } ?: return
        add(Media("dash", vid.first, audio.first, vid.second, page))
    }

    /** 点到的是个文件（setDownloadListener）。 */
    fun direct(url: String, mime: String?, len: Long, cd: String?, pageUrl: String?) {
        val name = cd?.let { Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)").find(it)?.groupValues?.get(1) }?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }.orEmpty()
        add(Media("file", url, headers = mapOf("Referer" to (pageUrl ?: page)), page = pageUrl ?: page, mime = mime.orEmpty(), size = len, name = name))
    }

    /** 页面里 <video> / <source> 的地址（不是 blob: 的才有用）。 */
    const val PROBE = """(function(){var o=[];document.querySelectorAll('video,video source,source[type^="video"]').forEach(function(v){var s=v.currentSrc||v.src;if(s&&!/^blob:|^data:/.test(s))o.push(s)});return JSON.stringify(o)})()"""

    fun fromPage(json: String?, pageUrl: String) {
        val t = json?.trim()?.removeSurrounding("\"")?.replace("\\\"", "\"")?.replace("\\\\", "\\") ?: return
        runCatching { JSONArray(t) }.getOrNull()?.let { a -> for (i in 0 until a.length()) saw(a.optString(i), emptyMap(), pageUrl) }
    }
}
