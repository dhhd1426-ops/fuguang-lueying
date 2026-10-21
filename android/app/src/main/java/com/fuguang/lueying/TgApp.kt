package com.fuguang.lueying

import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * 替他开通 Telegram 的 api_id（Telegram 规定每个 App 用自己的，在 my.telegram.org 申请）。
 * 照 my.telegram.org-api-wrapper（VityaSchel）记下的接口：
 * auth/send_password（phone）→ 回 random_hash，验证码发在他的 Telegram 里 → auth/login（phone、random_hash、password=验证码）→ Cookie stel_token
 * → /apps 读 api_id、api_hash；还没建过就用页面里的 hash 交 /apps/create（标题、短名、平台）再读一次。
 * 拿到的存进 prefs `tg-api`，以后 TDLib 就用它，不用再开通。
 */
internal object TgApp {
    private const val SITE = "https://my.telegram.org"

    suspend fun send(phone: String): String {
        val r = Pan.http("$SITE/auth/send_password", body = Pan.form("phone" to phone))
        val h = runCatching { JSONObject(r.body).optString("random_hash") }.getOrNull().orEmpty()
        if (h.isNotEmpty()) return h
        throw TgFail(if ("too many" in r.body.lowercase()) "试太多次了，过一会儿再来" else "Telegram 没发验证码，看看号码对不对", "TGAPP · send ${r.code} ${r.body.take(40)}")
    }

    suspend fun login(phone: String, hash: String, code: String): String {
        val r = Pan.http("$SITE/auth/login", body = Pan.form("phone" to phone, "random_hash" to hash, "password" to code, "remember" to 1))
        val tok = Regex("""stel_token=([^;]+)""").find(r.setCookies())?.groupValues?.get(1).orEmpty()
        if (r.body.trim() == "true" && tok.isNotEmpty()) return tok
        throw TgFail(if ("invalid" in r.body.lowercase()) "验证码不对" else "没登上，再试一次", "TGAPP · login ${r.code} ${r.body.take(40)}")
    }

    /** 读 api_id / api_hash；没有就建一个。 */
    suspend fun keys(token: String): Pair<Int, String> {
        val ck = mapOf("Cookie" to "stel_token=$token")
        read(Pan.http("$SITE/apps", ck).body)?.let { return it }
        repeat(2) { n ->
            val page = Jsoup.parse(Pan.http("$SITE/apps", ck).body)
            val h = page.selectFirst("input[name=hash]")?.attr("value").orEmpty()
            if (h.isEmpty()) throw TgFail("Telegram 没给开通页面，过会儿再试", "TGAPP · 没有 hash")
            val short = "fuguang" + (1000..9999).random()
            val r = Pan.http("$SITE/apps/create", ck + ("X-Requested-With" to "XMLHttpRequest"), Pan.form(
                "hash" to h, "app_title" to "Fuguang Lueying", "app_shortname" to short, "app_url" to "", "app_platform" to "android", "app_desc" to "",
            ))
            read(Pan.http("$SITE/apps", ck).body)?.let { return it }
            if (n == 1) throw TgFail("Telegram 没让建，换个网络再试", "TGAPP · create ${r.code} ${r.body.take(40)}")
        }
        throw TgFail("没开通成", "TGAPP · ?")
    }

    private fun read(html: String): Pair<Int, String>? {
        val d = Jsoup.parse(html)
        val id = d.selectFirst("label[for=app_id] + div span strong")?.text()?.trim()?.toIntOrNull()
        val hash = d.selectFirst("label[for=app_hash] + div span")?.text()?.trim().orEmpty()
        return if (id != null && hash.length >= 16) id to hash else null
    }
}
