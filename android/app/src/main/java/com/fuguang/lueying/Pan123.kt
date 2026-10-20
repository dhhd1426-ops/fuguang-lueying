package com.fuguang.lueying

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import com.fuguang.lueying.PanError.Kind.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URLDecoder
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import java.util.zip.CRC32
import kotlin.coroutines.resume

/**
 * 123 云盘：分享页直接下，不用转存。
 * 参考 nfd YeTool / Ye2Tool、OpenList drivers/123_share（util.go 的 signPath、driver.go 的 Link）。
 * - 列表：GET /b/api/share/get（ParentFileId=0 是根，Next=-1 是最后一页）。
 * - 下载：POST /b/api/share/download/info → data.DownloadURL；它的 params 参数是 base64 的真实跳转地址，
 *   不跟跳转请求一次，拿 302 的 Location（或 JSON 里的 data.redirect_url）。
 * - 两个接口的 query 都要带签名（[sign]，照 OpenList signPath / nfd ye123.js getSign）。
 * - 超过 100 MB 要登录。123 网页登录后令牌在 localStorage 的 authorToken（不在 Cookie 里），
 *   所以开一个看不见的 WebView、以 www.123pan.com 为源读 localStorage。用之前要 [init]。
 */
object Pan123 : PanApi {
    private const val ORIGIN = "https://www.123pan.com"
    private const val API = "$ORIGIN/b/api"
    private const val BIG = 100L * 1024 * 1024
    /** 时间串每一位数字换成这里的字母（签名用）。 */
    private const val TABLE = "adefghlmyijnopkqrstubcvwsz"

    private var app: Context? = null
    @Volatile private var token: String? = null
    /** 上次读到「没登录」的时间；一分钟内不再开 WebView 重读。 */
    @Volatile private var emptyAt = 0L

    fun init(ctx: Context) { app = ctx.applicationContext }

    override suspend fun open(s: PanShare): PanRoot {
        val files = page(s, "0", "")
        return PanRoot(files.firstOrNull()?.name ?: "123 云盘分享", files)
    }

    override suspend fun list(s: PanShare, dir: PanFile): List<PanFile> = page(s, dir.id, dir.path)

    override suspend fun direct(s: PanShare, f: PanFile): Direct {
        val t = token()
        if (f.size > BIG && t.isEmpty()) throw PanError(LOGIN, "超过 100 MB 的要先登录 123 云盘", "123 · 大文件要登录")
        val body = JSONObject().put("ShareKey", key(s)).put("FileID", f.id.toLong()).put("S3keyFlag", f.extra["s3"].orEmpty())
            .put("Size", f.size).put("Etag", f.extra["etag"].orEmpty()).toString()
        val d = call(s, "/share/download/info", "", body, t)
        val first = d.optString("DownloadURL").ifEmpty { d.optString("DownloadUrl") }
        if (first.isEmpty()) throw PanError(OTHER, "123 没给下载地址", "123 · 无 DownloadURL")
        // DownloadURL 里的 params 是 base64 的下一跳；没有就直接用它
        val hop = Regex("""[?&]params=([^&]+)""").find(first)?.groupValues?.get(1)?.let { p ->
            runCatching { String(Base64.getDecoder().decode(URLDecoder.decode(p.replace("+", "%2B"), "UTF-8"))) }.getOrNull()
        }?.takeIf { it.startsWith("http") }
            // 没有 params 的就是文件地址本身，不再请求（Pan.http 会把整个文件读进内存）
            ?: return Direct(first, mapOf("User-Agent" to Pan.PC_UA, "Referer" to "$ORIGIN/"), f.size)
        val r = Pan.http(hop, mapOf("Referer" to "$ORIGIN/", "User-Agent" to Pan.PC_UA), follow = false)
        val url = r.header("Location") ?: runCatching { JSONObject(r.body) }.getOrNull()?.let { o ->
            if (o.optInt("code", 0) != 0) throw fail(s, o.optInt("code"), o.optString("message"))
            o.optJSONObject("data")?.optString("redirect_url")
        }?.takeIf { it.isNotEmpty() } ?: hop
        return Direct(url, mapOf("User-Agent" to Pan.PC_UA, "Referer" to "$ORIGIN/"), f.size)
    }

    // ———— 列表 ————

    private suspend fun page(s: PanShare, parent: String, base: String): List<PanFile> {
        val out = ArrayList<PanFile>()
        val t = token()
        var p = 1
        while (p <= 50) {
            val q = Pan.form(
                "limit" to 100, "next" to 0, "orderBy" to "file_name", "orderDirection" to "asc",
                "shareKey" to key(s), "SharePwd" to s.code, "ParentFileId" to parent, "Page" to p,
                "event" to "homeListFile", "operateType" to 1,
            )
            val d = call(s, "/share/get", q, null, t)
            val arr = d.optJSONArray("InfoList") ?: break
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val dir = o.optInt("Type") == 1
                val name = o.optString("FileName")
                out += PanFile(
                    o.optLong("FileId").toString(), name, if (dir) -1 else o.optLong("Size", -1), dir,
                    if (base.isEmpty()) name else "$base/$name",
                    mapOf("etag" to o.optString("Etag"), "s3" to o.optString("S3KeyFlag")),
                )
            }
            if (arr.length() == 0 || d.optString("Next") == "-1") break
            p++
        }
        return out
    }

    // ———— 请求 ————

    /** 链接里的分享码去掉 .html / #… 这些尾巴。 */
    private fun key(s: PanShare) = s.id.substringBefore('.').substringBefore('#').substringBefore('?')

    private fun headers(s: PanShare, t: String) = mapOf(
        "User-Agent" to Pan.PC_UA, "Accept" to "application/json, text/plain, */*",
        "platform" to "web", "App-Version" to "3", "LoginUuid" to UUID.randomUUID().toString().replace("-", ""),
        "Origin" to ORIGIN, "Referer" to s.link.substringBefore('?').ifEmpty { "$ORIGIN/s/${key(s)}.html" },
        "Authorization" to if (t.isEmpty()) "" else "Bearer $t",
    )

    /** 发请求，code 不是 0 就按原因抛 [PanError]；返回 data。 */
    private suspend fun call(s: PanShare, path: String, query: String, body: String?, t: String): JSONObject {
        val (k, v) = sign("/b/api$path")
        val url = "$API$path?" + (if (query.isEmpty()) "" else "$query&") + Pan.form(k to v)
        val r = Pan.http(url, headers(s, t), body, json = body != null)
        val o = runCatching { JSONObject(r.body) }.getOrNull()
            ?: throw PanError(OTHER, "123 返回的不是数据，过一会儿再试", "123 · HTTP ${r.code}")
        val code = o.optInt("code", -1)
        if (code != 0) throw fail(s, code, o.optString("message"))
        return o.optJSONObject("data") ?: JSONObject()
    }

    private fun fail(s: PanShare, code: Int, msg: String): PanError {
        val c = "123 · code $code"
        fun has(vararg w: String) = w.any { it in msg }
        return when {
            code == 401 || has("登录", "token", "Token") -> { token = null; PanError(LOGIN, "要先登录 123 云盘", c) }
            has("提取码", "密码") -> PanError(CODE, if (s.code.isEmpty()) "这个分享要提取码" else "提取码不对", c)
            has("不存在", "取消", "失效", "过期", "删除", "违规", "封禁") -> PanError(GONE, "分享没了：$msg", c)
            has("流量", "上限", "频繁", "会员", "限制", "超出") -> PanError(LIMIT, "123 限制了：$msg", c)
            else -> PanError(OTHER, msg.ifBlank { "123 出错了" }, c)
        }
    }

    /**
     * 签名：北京时间 yyyyMMddHHmm 每位数字换成 [TABLE] 里的字母 → crc32 当参数名；
     * 「秒级时间戳|随机数|路径|web|3|参数名」再 crc32 → 参数值「时间戳-随机数-crc」。
     */
    private fun sign(path: String): Pair<String, String> {
        val now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))
        val ts = now.toEpochSecond().toString()
        val rnd = Math.round(1e7 * Math.random()).toString()
        val k = crc(now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmm")).map { TABLE[it - '0'] }.joinToString(""))
        val v = crc(listOf(ts, rnd, path, "web", "3", k).joinToString("|"))
        return k to "$ts-$rnd-$v"
    }

    private fun crc(s: String) = CRC32().apply { update(s.toByteArray()) }.value.toString()

    // ———— 登录令牌 ————

    /** 登录令牌：先看 Cookie（万一以后放进去），再读内置浏览器 www.123pan.com 的 localStorage.authorToken。没登过是空。 */
    private suspend fun token(): String {
        token?.let { return it }
        if (System.currentTimeMillis() - emptyAt < 60_000) return ""
        val ck = Regex("""(?:^|;\s*)(?:authorToken|token)=([^;]+)""").find(Pan.cookie(Disk.P123))?.groupValues?.get(1)
        val t = clean(ck ?: readStorage().orEmpty())
        if (t.isNotEmpty()) token = t else emptyAt = System.currentTimeMillis()
        return t
    }

    /** 去掉 JSON 引号、URL 编码和 Bearer 前缀。 */
    private fun clean(raw: String): String {
        var t = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw).trim()
        if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")) t = t.substring(1, t.length - 1)
        if (t.startsWith("Bearer ", true)) t = t.substring(7).trim()
        return if (t == "null" || t == "undefined") "" else t
    }

    /**
     * 看不见的 WebView：用 www.123pan.com 当 baseURL 装一页空白（不联网），页面的源就是 123，
     * 读得到他在内置浏览器里登录后存下的 localStorage。和内置浏览器共用同一份 WebView 存储。
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun readStorage(): String? {
        val ctx = app ?: return null
        return withContext(Dispatchers.Main) {
            val wv = WebView(ctx)
            try {
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                val raw = withTimeoutOrNull(8_000) {
                    suspendCancellableCoroutine<String> { k ->
                        var once = false
                        wv.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                if (once) return
                                once = true
                                wv.evaluateJavascript("(function(){try{return localStorage.getItem('authorToken')||''}catch(e){return ''}})()") { v ->
                                    if (k.isActive) k.resume(v ?: "")
                                }
                            }
                        }
                        wv.loadDataWithBaseURL("$ORIGIN/", "<html><body></body></html>", "text/html", "utf-8", null)
                    }
                }
                // evaluateJavascript 回来的是 JSON 字符串（带引号），先解一层
                raw?.let { runCatching { JSONTokener(it).nextValue() as? String }.getOrNull() }
            } finally {
                Handler(Looper.getMainLooper()).post { runCatching { wv.stopLoading(); wv.destroy() } }
            }
        }
    }
}
