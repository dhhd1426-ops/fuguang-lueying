package com.fuguang.lueying

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

/**
 * 阿里云盘分享。
 * 打开、列目录不用登录（share_token 就够）；下载要登录：先试分享页直接给地址，不给就借道——
 * 存进他自己盘里的「浮光临时」，拿下载地址，再把那份扔进回收站。
 * 登录读的是内置浏览器里 www.alipan.com 的 localStorage「token」（阿里网页版不放 Cookie）。
 * 接口写法对照 OpenList drivers/aliyundrive、aliyundrive_share 和 tickstep/aliyunpan-api（aliyunpan_web）。
 */
object PanAli : PanApi {
    private const val API = "https://api.alipan.com"
    private const val HOME = "https://www.alipan.com/drive"
    private const val TEMP = "浮光临时"
    private const val APP_ID = "5dde4e1bdf9e4966b387ba58f4b3fdc3"
    private const val SHARE_CANARY = "client=web,app=share,version=v2.3.1"
    private const val APP_CANARY = "client=Android,app=adrive,version=v4.1.0"
    private val REF = mapOf("Referer" to "https://www.alipan.com/", "User-Agent" to Pan.PC_UA)

    private var app: Context? = null
    fun init(ctx: Context) { app = ctx.applicationContext }

    /** 网页版的登录令牌。raw 是 localStorage 里那整个 JSON，刷新后要原样写回去。 */
    private class Tok(val raw: JSONObject) {
        val access: String = raw.optString("access_token")
        val refresh: String = raw.optString("refresh_token")
        val user: String = raw.optString("user_id")
        var drive: String = raw.optString("resource_drive_id")
    }

    @Volatile private var tok: Tok? = null
    @Volatile private var tmp: Pair<String, String>? = null   // （盘，「浮光临时」的 file_id）
    // 设备签名 x-device-id / x-signature，每个用户建一次会话
    @Volatile private var dev = ""; @Volatile private var sig = ""; @Volatile private var sessionUser = ""

    // ———— 登录 ————

    /** 内置浏览器里阿里网页版的 token（localStorage「token」）；没登录或读不到是 null。 */
    suspend fun token(): JSONObject? {
        val r = web("localStorage.getItem('token')") ?: return null
        val s = runCatching { JSONTokener(r).nextValue() as? String }.getOrNull() ?: return null
        return runCatching { JSONObject(s) }.getOrNull()?.takeIf { it.optString("access_token").isNotEmpty() }
    }

    /** 在主线程开一个看不见的 WebView 打开阿里网页版，页面加载完在里面跑一段脚本，回脚本的结果（JSON 编码的）。最多等 15 秒。 */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun web(js: String): String? {
        val ctx = app ?: return null
        return withContext(Dispatchers.Main) {
            val wv = WebView(ctx)
            try {
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                withTimeoutOrNull(15_000) {
                    suspendCancellableCoroutine<String?> { k ->
                        var once = false
                        wv.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                // 只在 www.alipan.com 这个源上读，中间跳到别处的不算
                                if (once || url?.startsWith("https://www.alipan.com") != true) return
                                once = true
                                view?.evaluateJavascript(js) { r -> if (k.isActive) k.resume(r) }
                            }
                        }
                        wv.loadUrl(HOME)
                    }
                }
            } finally {
                wv.stopLoading()
                wv.destroy()
            }
        }
    }

    private fun noLogin(code: String) = PanError(PanError.Kind.LOGIN, "先在内置浏览器里登录阿里云盘", code)

    private suspend fun login(): Tok {
        tok?.let { return it }
        return (token()?.let { Tok(it) } ?: throw noLogin("ALI · NoToken")).also { tok = it }
    }

    /** access_token 过期：先看网页自己刷新过没有，没有就拿 refresh_token 换，换到的写回 localStorage（不然网页那边的旧 refresh_token 会失效）。 */
    private suspend fun renew(old: Tok): Tok {
        token()?.let { Tok(it) }?.takeIf { it.access != old.access }?.let { tok = it; return it }
        if (old.refresh.isEmpty()) { tok = null; throw noLogin("ALI · NoRefreshToken") }
        val r = Pan.http("https://auth.alipan.com/v2/account/token", emptyMap(),
            JSONObject().put("refresh_token", old.refresh).put("grant_type", "refresh_token").toString(), json = true)
        val j = runCatching { JSONObject(r.body) }.getOrNull()
        if (j == null || r.code >= 400 || j.optString("access_token").isEmpty()) { tok = null; throw noLogin("ALI · RefreshFailed") }
        val raw = old.raw
        for (k in listOf("access_token", "refresh_token", "expire_time", "expires_in")) if (j.has(k)) raw.put(k, j.get(k))
        web("localStorage.setItem('token', ${JSONObject.quote(raw.toString())}); 'ok'")
        return Tok(raw).also { tok = it }
    }

    /** 设备会话：设备号 = sha256(user_id)，私钥由设备号来；签 「appId:设备号:user_id:0」，再把公钥报给阿里。 */
    private suspend fun session(t: Tok, force: Boolean) {
        if (!force && sessionUser == t.user && sig.isNotEmpty()) return
        val d = PanAliSign.sha256Hex(t.user)
        val key = PanAliSign.key(d)
        dev = d
        sig = PanAliSign.sign(key, PanAliSign.sha256("$APP_ID:$d:${t.user}:0"))
        sessionUser = t.user
        val body = JSONObject().put("deviceName", "samsung").put("modelName", "SM-G9810").put("nonce", 0)
            .put("pubKey", PanAliSign.pub(key)).put("refreshToken", t.refresh)
        // 建不成也不拦：不验签名的接口照样能用，验的接口会回 DeviceSessionSignatureInvalid 再来一次
        runCatching { Pan.http("$API/users/v1/users/device/create_session", authHeaders(t), body.toString(), json = true) }
    }

    private fun authHeaders(t: Tok) = mapOf(
        "Authorization" to "Bearer ${t.access}", "x-device-id" to dev, "x-signature" to sig, "X-Canary" to APP_CANARY,
        "Origin" to "https://www.alipan.com", "Referer" to "https://www.alipan.com/",
    )

    // ———— 请求 ————

    /**
     * 发一次接口。auth：带登录；st：带 x-share-token。
     * 令牌过期刷新一次、签名失效重建会话一次、share_token 失效重拿一次，其余按原因抛 [PanError]。
     */
    private suspend fun call(url: String, body: JSONObject, auth: Boolean = true, s: PanShare? = null, st: Boolean = s != null): JSONObject {
        var renewed = false; var sessioned = false; var reshared = false
        while (true) {
            val h = HashMap<String, String>()
            var t: Tok? = null
            if (auth) {
                t = login(); session(t, false); h.putAll(authHeaders(t))
            } else {
                h["X-Canary"] = SHARE_CANARY; h["Origin"] = "https://www.alipan.com"; h["Referer"] = "https://www.alipan.com/"
            }
            if (st && s != null) h["x-share-token"] = shareToken(s)
            val r = Pan.http(url, h, body.toString(), json = true)
            val j = runCatching { JSONObject(r.body) }.getOrNull() ?: JSONObject()
            if (r.code < 400) return j
            val code = j.optString("code")
            when {
                t != null && !renewed && (code.startsWith("AccessToken") || r.code == 401) -> { renewed = true; renew(t) }
                t != null && !sessioned && (code == "DeviceSessionSignatureInvalid" || code == "UserDeviceOffline") -> { sessioned = true; session(t, true) }
                s != null && st && !reshared && code == "ShareLinkTokenInvalid" -> { reshared = true; s.state.remove("token") }
                else -> throw fail(code, r.code, s)
            }
        }
    }

    private fun fail(code: String, http: Int, s: PanShare?): PanError {
        val c = "ALI · " + code.ifEmpty { "HTTP$http" }
        return when {
            code.contains("SharePwd") -> PanError(PanError.Kind.CODE, if (s?.code.isNullOrEmpty()) "这个分享要提取码" else "提取码不对", c)
            code.startsWith("ShareLink.") || code.startsWith("NotFound.Share") -> PanError(PanError.Kind.GONE, "这个分享已经取消或过期了", c)
            code.startsWith("NotFound.File") -> PanError(PanError.Kind.GONE, "分享里这个文件已经没了", c)
            code.startsWith("AccessToken") || code.contains("Device") || http == 401 -> PanError(PanError.Kind.LOGIN, "阿里云盘登录过期了，去内置浏览器重新登一下", c)
            code.startsWith("QuotaExhausted") -> PanError(PanError.Kind.LIMIT, "你的阿里云盘空间满了，借道存不进去，腾点地方再试", c)
            code == "TooManyRequests" || http == 429 -> PanError(PanError.Kind.LIMIT, "阿里说太频繁了，过一会儿再试", c)
            else -> PanError(PanError.Kind.OTHER, "阿里云盘没给下载地址，过会儿再试", c)
        }
    }

    private suspend fun shareToken(s: PanShare): String {
        s.state["token"]?.let { return it }
        val j = call("$API/v2/share_link/get_share_token", JSONObject().put("share_id", s.id).put("share_pwd", s.code), auth = false, s = s, st = false)
        val t = j.optString("share_token").ifEmpty { throw fail("ShareToken.Empty", 200, s) }
        s.state["token"] = t
        return t
    }

    // ———— 分享 ————

    override suspend fun open(s: PanShare): PanRoot {
        val info = call("$API/adrive/v3/share_link/get_share_by_anonymous?share_id=${Pan.enc(s.id)}",
            JSONObject().put("share_id", s.id), auth = false, s = s, st = false)
        if (info.optBoolean("has_pwd") && s.code.isEmpty()) throw PanError(PanError.Kind.CODE, "这个分享要提取码", "ALI · SharePwd.Missing")
        s.state.remove("token")
        shareToken(s)
        val files = list(s, PanFile("root", "", 0, true, path = ""))
        val title = info.optString("share_title").ifEmpty { info.optString("share_name") }.ifEmpty { files.firstOrNull()?.name ?: "阿里云盘分享" }
        return PanRoot(title, files, info.optString("display_name").ifEmpty { info.optString("creator_name") })
    }

    override suspend fun list(s: PanShare, dir: PanFile): List<PanFile> {
        val out = ArrayList<PanFile>()
        var marker = ""
        do {
            val body = JSONObject().put("share_id", s.id).put("parent_file_id", dir.id).put("limit", 100)
                .put("order_by", "name").put("order_direction", "ASC")
            if (marker.isNotEmpty()) body.put("marker", marker)
            val j = call("$API/adrive/v3/file/list", body, auth = false, s = s)
            val items = j.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                val name = o.optString("name")
                out += PanFile(
                    o.optString("file_id"), name, o.optLong("size", -1), o.optString("type") == "folder",
                    path = if (dir.path.isEmpty()) name else "${dir.path}/$name")
            }
            marker = j.optString("next_marker")
        } while (marker.isNotEmpty() && out.size < 5000)
        return out
    }

    override suspend fun direct(s: PanShare, f: PanFile): Direct {
        login()
        // 1. 不转存：分享页直接给地址（阿里现在多半不给，给了就用）
        val quick = try {
            val j = call("$API/v2/file/get_share_link_download_url", JSONObject().put("share_id", s.id).put("file_id", f.id).put("expire_sec", 600), s = s)
            j.optString("download_url").ifEmpty { j.optString("url") }
        } catch (e: PanError) {
            if (e.kind == PanError.Kind.GONE || e.kind == PanError.Kind.CODE || e.kind == PanError.Kind.NET) throw e
            null
        }
        if (quick != null && quick.startsWith("http")) return Direct(quick, REF, f.size)

        // 2. 借道：存进「浮光临时」→ 拿地址 → 扔掉那份
        val d = drive()
        val req = JSONObject().put("id", "0").put("method", "POST").put("url", "/file/copy")
            .put("headers", JSONObject().put("Content-Type", "application/json"))
            .put("body", JSONObject().put("share_id", s.id).put("file_id", f.id).put("to_drive_id", d)
                .put("to_parent_file_id", tempDir(d)).put("auto_rename", true))
        val j = call("$API/adrive/v2/batch", JSONObject().put("requests", JSONArray().put(req)).put("resource", "file"), s = s)
        val r0 = j.optJSONArray("responses")?.optJSONObject(0) ?: throw fail("Batch.Empty", 200, s)
        val b = r0.optJSONObject("body") ?: JSONObject()
        val status = r0.optInt("status")
        if (status >= 400) throw fail(b.optString("code"), status, s)
        b.optString("async_task_id").takeIf { it.isNotEmpty() }?.let { wait(it, s) }
        val id = b.optString("file_id").ifEmpty { throw fail("Copy.NoFileId", status, s) }
        val drv = b.optString("drive_id").ifEmpty { d }
        try {
            val u = call("$API/v2/file/get_download_url", JSONObject().put("drive_id", drv).put("file_id", id).put("expire_sec", 14400))
            val url = u.optString("url").ifEmpty { u.optString("cdn_url") }
            if (!url.startsWith("http")) throw fail("DownloadUrl.Empty", 200, s)
            return Direct(url, REF, u.optLong("size", f.size))
        } finally {
            // 地址是签好的，扔进回收站后照样能下完
            try { trash(drv, id) } catch (_: PanError) {}
        }
    }

    /** 文件夹或大文件的转存是异步的，等它做完（最多约 15 秒）。 */
    private suspend fun wait(task: String, s: PanShare) {
        repeat(30) {
            val j = call("$API/v2/async_task/get", JSONObject().put("async_task_id", task))
            when (j.optString("state").ifEmpty { j.optString("status") }) {
                "Succeed", "Success" -> return
                "Failed" -> throw fail(j.optString("code").ifEmpty { "AsyncTask.Failed" }, 200, s)
            }
            delay(500)
        }
    }

    override suspend fun cleanup(s: PanShare) {
        if (tok == null || tmp == null) return
        try {
            val d = drive()
            val j = call("$API/adrive/v3/file/list", JSONObject().put("drive_id", d).put("parent_file_id", tempDir(d)).put("limit", 200))
            val items = j.optJSONArray("items") ?: return
            for (i in 0 until items.length()) items.optJSONObject(i)?.optString("file_id")?.takeIf { it.isNotEmpty() }?.let { trash(d, it) }
        } catch (_: PanError) {
            // 收拾不干净不打扰他，下次再清
        }
    }

    // ———— 他自己的盘 ————

    /** 借道存到哪个盘：资源库优先，没有就备份盘（default_drive_id）。 */
    private suspend fun drive(): String {
        val t = login()
        if (t.drive.isNotEmpty()) return t.drive
        val j = try { call("$API/v2/user/get", JSONObject()) } catch (_: PanError) { null }
        t.drive = j?.optString("resource_drive_id").orEmpty().ifEmpty { t.raw.optString("default_drive_id") }
            .ifEmpty { j?.optString("default_drive_id").orEmpty() }
        if (t.drive.isEmpty()) throw noLogin("ALI · NoDrive")
        return t.drive
    }

    /** 根目录下的「浮光临时」：check_name_mode=refuse，已有就回原来那个的 file_id，没有就建。 */
    private suspend fun tempDir(d: String): String {
        tmp?.takeIf { it.first == d }?.let { return it.second }
        val j = call("$API/adrive/v2/file/createWithFolders", JSONObject().put("check_name_mode", "refuse").put("drive_id", d)
            .put("name", TEMP).put("parent_file_id", "root").put("type", "folder"))
        return j.optString("file_id").ifEmpty { throw fail("TempDir.Failed", 200, null) }.also { tmp = d to it }
    }

    private suspend fun trash(d: String, id: String) {
        call("$API/v2/recyclebin/trash", JSONObject().put("drive_id", d).put("file_id", id))
    }
}
