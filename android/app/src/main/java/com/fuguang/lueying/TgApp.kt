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
    /** 网页登录后的 Cookie（stel_token，还有服务器另发的 stel_ssid 等），整段带着走，像浏览器一样。 */
    private val jar = LinkedHashMap<String, String>()
    private fun ck() = mapOf("Cookie" to jar.entries.joinToString("; ") { "${it.key}=${it.value}" }, "Origin" to SITE, "Referer" to "$SITE/apps")
    private fun keep(r: Pan.Resp) { r.setCookies().split("; ").forEach { kv -> if ('=' in kv) jar[kv.substringBefore('=')] = kv.substringAfter('=') } }
    val token get() = jar["stel_token"].orEmpty()

    suspend fun send(phone: String): String {
        jar.clear()
        val r = Pan.http("$SITE/auth/send_password", mapOf("Origin" to SITE, "Referer" to "$SITE/auth"), Pan.form("phone" to phone))
        keep(r)
        val h = runCatching { JSONObject(r.body).optString("random_hash") }.getOrNull().orEmpty()
        if (h.isNotEmpty()) return h
        throw TgFail(if ("too many" in r.body.lowercase()) "试太多次了，过一会儿再来" else "Telegram 没发验证码，看看号码对不对", "TGAPP · send ${r.code} ${r.body.take(40)}")
    }

    suspend fun login(phone: String, hash: String, code: String): String {
        val r = Pan.http("$SITE/auth/login", ck() + ("Referer" to "$SITE/auth"), Pan.form("phone" to phone, "random_hash" to hash, "password" to code, "remember" to 1))
        keep(r)
        val tok = token
        if (r.body.trim() == "true" && tok.isNotEmpty()) return tok
        throw TgFail(if ("invalid" in r.body.lowercase()) "验证码不对" else "没登上，再试一次", "TGAPP · login ${r.code} ${r.body.take(40)}")
    }

    /**
     * 读 api_id / api_hash；没有就试着建一个。TDLib 维护者说过 my.telegram.org 故意拦自动建（回一个「ERROR」），
     * 拦了就抛 [TgManual]：让他在内置浏览器里自己点一下（Cookie 已经替他登好），或者粘贴已有的。
     */
    suspend fun keys(): Pair<Int, String> {
        val first = Pan.http("$SITE/apps", ck()).also { keep(it) }
        read(first.body)?.let { return it }
        val h = Jsoup.parse(first.body).selectFirst("input[name=hash]")?.attr("value").orEmpty()
        if (h.isEmpty()) throw TgManual("TGAPP · 没有开通表单 ${first.code}")
        val r = Pan.http("$SITE/apps/create", ck() + ("X-Requested-With" to "XMLHttpRequest"), Pan.form(
            "hash" to h, "app_title" to "Fuguang Lueying " + (100..999).random(), "app_shortname" to "fuguang" + (1000..9999).random(),
            "app_url" to "", "app_platform" to "android", "app_desc" to "",
        ))
        keep(r)
        read(Pan.http("$SITE/apps", ck()).body)?.let { return it }
        throw TgManual("TGAPP · create ${r.code} ${r.body.trim().take(30)}")
    }

    /** 他在内置浏览器里建好以后：用浏览器的 Cookie 读一次。 */
    suspend fun fromWeb(): Pair<Int, String>? {
        val c = android.webkit.CookieManager.getInstance().getCookie(SITE)?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { read(Pan.http("$SITE/apps", mapOf("Cookie" to c)).body) }.getOrNull()
    }

    /** 把这里登好的 Cookie 交给内置浏览器，打开就是登录好的开通页。 */
    fun toWeb() {
        val cm = android.webkit.CookieManager.getInstance()
        jar.forEach { (k, v) -> cm.setCookie(SITE, "$k=$v; path=/; secure") }
        cm.flush()
    }

    private fun read(html: String): Pair<Int, String>? {
        val d = Jsoup.parse(html)
        val id = d.selectFirst("label[for=app_id] + div span strong")?.text()?.trim()?.toIntOrNull()
        val hash = d.selectFirst("label[for=app_hash] + div span")?.text()?.trim().orEmpty()
        return if (id != null && hash.length >= 16) id to hash else null
    }
}

/** 自动开通被拦：要他在网页里点一下，或粘贴已有的 api_id。 */
class TgManual(val code: String) : Exception("Telegram 不让自动开通")
