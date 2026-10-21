package com.fuguang.lueying

/**
 * Telegram 消息链接。认这几种（照 TDLib getMessageLinkInfo 支持的写法）：
 * - 公开：t.me/<频道名>/<号>、t.me/<频道名>/<话题>/<号>、t.me/s/<频道名>/<号>，可以带 ?single / ?comment= / ?thread=
 * - 私有：t.me/c/<数字>/<号>（只有登录了、而且是成员才看得到）
 * - tg://resolve?domain=<频道名>&post=<号>、tg://privatepost?channel=<数字>&post=<号>
 * 也认 telegram.me / telegram.dog。
 */
class TgLink(val url: String, val chan: String, val post: Long, val private: Boolean, val single: Boolean) {
    /** 网页预览（不登录）：只有公开频道有。 */
    val embed get() = "https://t.me/$chan/$post?embed=1&mode=tme" + if (single) "&single=1" else ""

    companion object {
        private val WEB = Regex("""(?:https?://)?(?:t\.me|telegram\.me|telegram\.dog)/(?:s/)?(c/\d+|[A-Za-z][A-Za-z0-9_]{3,})/(?:(\d+)/)?(\d+)(\?[^\s<>"']*)?""")
        private val RESOLVE = Regex("""tg://resolve\?[^\s<>"']*""")
        private val PRIVATE = Regex("""tg://privatepost\?[^\s<>"']*""")

        fun find(text: String): TgLink? {
            WEB.find(text)?.let { m ->
                val head = m.groupValues[1]
                if (head in setOf("joinchat", "addstickers", "share", "proxy", "socks", "setlanguage", "addtheme", "invoice", "boost")) return null
                val post = m.groupValues[3].toLongOrNull() ?: return null
                val q = m.groupValues[4]
                return TgLink("https://t.me/$head/" + (m.groupValues[2].takeIf { it.isNotEmpty() }?.let { "$it/" } ?: "") + post + q.substringBefore('#'),
                    head, post, head.startsWith("c/"), "single" in q)
            }
            RESOLVE.find(text)?.let { m ->
                val q = query(m.value)
                val d = q["domain"] ?: return null
                val p = q["post"]?.toLongOrNull() ?: return null
                return TgLink("https://t.me/$d/$p", d, p, private = false, single = "single" in q)
            }
            PRIVATE.find(text)?.let { m ->
                val q = query(m.value)
                val c = q["channel"] ?: return null
                val p = q["post"]?.toLongOrNull() ?: return null
                return TgLink("https://t.me/c/$c/$p", "c/$c", p, private = true, single = "single" in q)
            }
            return null
        }

        private fun query(u: String): Map<String, String> = u.substringAfter('?', "").split('&').filter { it.isNotEmpty() }
            .associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }
    }
}
