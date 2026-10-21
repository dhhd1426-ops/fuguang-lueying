package com.fuguang.lueying

import org.jsoup.Jsoup

/**
 * 不登录：公开频道的网页预览（t.me/<频道>/<号>?embed=1）里直接有视频地址（cdn*.telesco.pe，带签名、会过期，拿到就下）。
 * 读法照 yt-dlp 的 TelegramEmbedIE：每个 a.tgme_widget_message_video_player 里的 <video src>、时长、封面。
 * 大视频网页不给地址（显示「Media is too big」）、频道关了网页预览，都回 [Web.need] = true，交给登录那条路。
 */
internal object TgWeb {
    class Web(val title: String, val who: String, val vids: List<TgVid>, val need: Boolean, val why: String)

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

    suspend fun read(l: TgLink): Web {
        val r = Pan.http(l.embed, mapOf("User-Agent" to UA, "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.6"))
        if (r.code >= 400) return Web("", "", emptyList(), true, "TG · 网页 HTTP ${r.code}")
        val doc = Jsoup.parse(r.body, l.embed)
        val err = doc.selectFirst(".tgme_widget_message_error")?.text().orEmpty()
        if (err.isNotEmpty()) return Web("", "", emptyList(), true, "TG · 网页 $err".take(60))
        val text = doc.selectFirst(".tgme_widget_message_text")?.wholeText()?.trim().orEmpty()
        val who = doc.selectFirst(".tgme_widget_message_owner_name, .tgme_widget_message_author")?.text()?.trim().orEmpty()
        val vids = ArrayList<TgVid>()
        val players = doc.select("a.tgme_widget_message_video_player, .tgme_widget_message_roundvideo_player")
        for ((i, a) in players.withIndex()) {
            val src = a.selectFirst("video")?.attr("abs:src").orEmpty()
            val dur = a.selectFirst("time[class*=duration], .message_video_duration")?.text().orEmpty()
            val thumb = Regex("""background-image:url\('([^']+)'\)""").find(a.selectFirst("[class*=video_thumb]")?.attr("style").orEmpty())?.groupValues?.get(1)
            val big = src.isEmpty() || a.selectFirst(".message_media_not_supported") != null
            vids += TgVid("w$i", name(text, l, i, players.size), secs(dur), -1, if (big) null else src, thumb = thumb)
        }
        // 视频太大时网页只放一块「Media is too big」，没有播放器
        val tooBig = doc.select(".message_media_not_supported_label, .message_media_not_supported").any { "big" in it.text().lowercase() }
        val need = vids.isEmpty() || vids.any { it.web == null } || tooBig
        val why = when { tooBig -> "TG · 网页说太大"; vids.isEmpty() -> "TG · 网页里没有视频"; need -> "TG · 网页没给地址"; else -> "" }
        return Web(text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(60), who, vids, need, why)
    }

    /** 下载用的文件名：正文第一行 + 第几个。 */
    fun name(text: String, l: TgLink, i: Int, n: Int): String {
        val base = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(40)?.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            ?.ifBlank { null } ?: "${l.chan.replace('/', '_')}_${l.post}"
        return (if (n > 1) "$base（${i + 1}）" else base) + ".mp4"
    }

    private fun secs(s: String): Int = s.split(':').mapNotNull { it.trim().toIntOrNull() }.fold(0) { a, b -> a * 60 + b }
}
