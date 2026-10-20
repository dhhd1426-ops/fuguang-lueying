package com.fuguang.lueying

import org.json.JSONObject
import java.security.MessageDigest

/**
 * 百度的第三条路：自己网盘里的文件走 PCS 的 locatedownload（BaiduPCS-Go 默认就用它，普通账号也给地址，只是慢）。
 * 借道存进「/浮光临时」后，filemetas 的 dlink 不放行时用这个。
 * 签名照 BaiduPCS-Go baidupcs/netdisksign：
 * - devuid = MD5(BDUSS) 大写 + "|0"
 * - rand = SHA1( SHA1(BDUSS) 小写十六进制 + uid + 固定串 + time + devuid )
 * uid 是百度账号的 uid（不是网盘的 uk），照 baidu-tools/tieba 用贴吧客户端登录接口拿（贴吧客户端签名：参数排序拼起来 + "tiebaclient!!!" 取 MD5）。
 */
internal object PanBaiduLocate {
    const val UA = "netdisk;P2SP;3.0.0.8;netdisk;11.12.3;ANG-AN00;android-android;10.0;JSbridge4.4.0;jointBridge;1.1.0;"
    private const val SALT = "ebrcUYiuxaZv2XGu7KIYKxUrqfnOfpDF"
    private val uids = HashMap<String, String>()

    suspend fun direct(bduss: String, path: String, size: Long): Direct {
        val uid = uid(bduss)
        val dev = hex(md5(bduss)).uppercase() + "|0"
        val t = (System.currentTimeMillis() / 1000).toString()
        val rand = hex(sha1(hex(sha1(bduss)) + uid + SALT + t + dev))
        val url = "https://pcs.baidu.com/rest/2.0/pcs/file?" + Pan.form(
            "ant" to 1, "check_blue" to 1, "es" to 1, "esl" to 1, "app_id" to 250528, "method" to "locatedownload",
            "path" to path, "ver" to "4.0", "clienttype" to 17, "channel" to 0, "apn_id" to "1_0", "freeisp" to 0, "queryfree" to 0, "use" to 0,
            "time" to t, "rand" to rand, "devuid" to dev, "cuid" to dev)
        val r = Pan.http(url, mapOf("User-Agent" to UA, "Cookie" to "BDUSS=$bduss"), body = "")
        val j = runCatching { JSONObject(r.body) }.getOrNull()
            ?: throw PanError(PanError.Kind.LIMIT, "百度没给下载地址", "BAIDU · 定位 HTTP ${r.code}")
        val urls = j.optJSONArray("urls")
        if (urls != null) for (i in 0 until urls.length()) {
            val o = urls.optJSONObject(i) ?: continue
            if (o.optInt("encrypt", 0) == 0 && o.optString("url").startsWith("http"))
                return Direct(o.optString("url"), mapOf("User-Agent" to UA, "Cookie" to "BDUSS=$bduss"), size)
        }
        val e = j.optInt("error_code", j.optInt("errno", 0))
        throw PanError(PanError.Kind.LIMIT, "百度没给下载地址", "BAIDU · 定位 ${r.code}" + if (e != 0) "/$e" else "")
    }

    /** 贴吧客户端登录接口：用 BDUSS 换百度 uid。 */
    private suspend fun uid(bduss: String): String {
        uids[bduss]?.let { return it }
        val imei = "860000000000000"
        val ver = "7.0.0.0"; val from = "mini_ad_wandoujia"
        val p = sortedMapOf(
            "bdusstoken" to "$bduss|null", "channel_id" to "", "channel_uid" to "", "stErrorNums" to "0", "subapp_type" to "mini",
            "timestamp" to "${System.currentTimeMillis() / 1000}922", "_client_type" to "2", "_client_version" to ver,
            "_phone_imei" to imei, "from" to from, "model" to "MI 9",
            "cuid" to hex(md5("_${ver}_${imei}_$from")).uppercase() + "|" + imei.reversed(),
        )
        p["sign"] = hex(md5(p.entries.joinToString("") { (k, v) -> "$k=$v" } + "tiebaclient!!!")).uppercase()
        val r = Pan.http("https://tieba.baidu.com/c/s/login", mapOf("User-Agent" to "bdtb for Android 6.9.2.1", "Cookie" to "ka=open", "net" to "1"),
            p.entries.joinToString("&") { (k, v) -> k + "=" + Pan.enc(v) })
        val j = runCatching { JSONObject(r.body) }.getOrNull()
        val id = j?.optJSONObject("user")?.opt("id")?.toString().orEmpty()
        if (id.isEmpty() || id == "0") throw PanError(PanError.Kind.LIMIT, "百度没给下载地址", "BAIDU · uid " + (j?.optString("error_code") ?: "HTTP ${r.code}"))
        uids[bduss] = id
        return id
    }

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray())
    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
