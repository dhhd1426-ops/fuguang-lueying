package com.fuguang.lueying

import org.json.JSONArray
import org.json.JSONObject

/**
 * 夸克网盘 / UC 网盘（同一家的两块牌子，接口一样，换域名、pr、Referer、UA）。
 *
 * 打开：sharepage/token 拿 stoken（存 s.state["stoken"]）→ detail 列目录。
 * 下载：先试分享页直接要地址（小文件夸克肯给）；被大小限制挡住才借道（PanQuarkSave.kt）——
 *   在他网盘根目录找或建「浮光临时」→ sharepage/save 存这一个文件 → 轮询 task 拿新 fid
 *   → file/download 拿地址 → 马上 file/delete 删掉那份。
 * 收拾：cleanup 把「浮光临时」里剩下的都删掉，文件夹本身留着，下次接着用，不用再建。
 *
 * 参考：OpenList drivers/quark_uc（域名、pr、UA、Referer、file/sort、file、file/delete、file/download）
 *       netdisk-fast-download QkTool / UcTool（token → detail → save → task → download）。
 */
object PanQuark : PanApi {
    /** 一家的常量。api 是普通接口的前缀；save 是转存接口的前缀（夸克转存走 drive-h）。 */
    internal class Site(
        val name: String, val tag: String, val api: String, val save: String,
        val pr: String, val referer: String, val ua: String, val uc: Boolean,
    )

    private val QUARK = Site(
        "夸克网盘", "QUARK",
        "https://drive-pc.quark.cn/1/clouddrive", "https://drive-h.quark.cn/1/clouddrive",
        "ucpro", "https://pan.quark.cn/",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch",
        uc = false,
    )
    private val UC = Site(
        "UC 网盘", "UC",
        "https://pc-api.uc.cn/1/clouddrive", "https://pc-api.uc.cn/1/clouddrive",
        "UCBrowser", "https://drive.uc.cn/",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) uc-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch",
        uc = true,
    )

    internal fun site(s: PanShare) = if (s.disk == Disk.UC) UC else QUARK

    // ———— 接口 ————

    override suspend fun open(s: PanShare): PanRoot {
        val d = token(s)
        val files = detail(s, "0", "")
        val title = d.optString("title").ifBlank { files.firstOrNull()?.name ?: "分享" }
        val owner = d.optJSONObject("author")?.optString("nick_name").orEmpty()
        return PanRoot(title, files, owner)
    }

    override suspend fun list(s: PanShare, dir: PanFile): List<PanFile> = detail(s, dir.id, dir.path)

    override suspend fun direct(s: PanShare, f: PanFile): Direct {
        val st = site(s)
        if (cookie(s).isEmpty()) throw login(st, 0)
        // 先试分享页直接拿；登录、失效、提取码、网络这些借道也救不了，直接报
        try {
            return shareDownload(s, f)
        } catch (e: PanError) {
            if (e.kind != PanError.Kind.LIMIT && e.kind != PanError.Kind.OTHER) throw e
        }
        return PanQuarkSave.borrow(s, f)
    }

    /** 把「浮光临时」里剩下的删干净；文件夹本身留着。收拾是顺手的事，出错不报。 */
    override suspend fun cleanup(s: PanShare) {
        if (cookie(s).isEmpty()) return
        try {
            val tmp = s.state["tmp"] ?: PanQuarkSave.findTmp(s) ?: return
            val ids = PanQuarkSave.mine(s, tmp).map { it.optString("fid") }.filter { it.isNotEmpty() }
            ids.chunked(50).forEach { PanQuarkSave.remove(s, it) }
        } catch (_: PanError) {
        }
    }

    // ———— 分享这边 ————

    /** 验提取码、拿 stoken。UC 要带 share_for_transfer（它的 detail 走 transfer_share）。 */
    private suspend fun token(s: PanShare): JSONObject {
        val st = site(s)
        val b = JSONObject().put("pwd_id", s.id).put("passcode", s.code)
        if (st.uc) b.put("share_for_transfer", true)
        val d = call(s, "share/sharepage/token", body = b).optJSONObject("data") ?: JSONObject()
        val stoken = d.optString("stoken")
        if (stoken.isEmpty()) {
            throw if (s.code.isEmpty()) PanError(PanError.Kind.CODE, "这个分享要提取码，填上再试", "${st.tag} · stoken")
            else PanError(PanError.Kind.GONE, "这个分享打不开了，可能已经失效", "${st.tag} · stoken")
        }
        s.state["stoken"] = stoken
        return d
    }

    /** 带着 stoken 做一件事；stoken 过期（14001 / 消息里说 token）就重拿一次再做。 */
    internal suspend fun <T> withToken(s: PanShare, block: suspend (String) -> T): T {
        val first = s.state["stoken"] ?: run { token(s); s.state["stoken"].orEmpty() }
        return try {
            block(first)
        } catch (_: Stale) {
            token(s)
            try {
                block(s.state["stoken"].orEmpty())
            } catch (_: Stale) {
                throw PanError(PanError.Kind.OTHER, "分享口令一直对不上，重新打开这个分享试试", "${site(s).tag} · stoken")
            }
        }
    }

    internal class Stale : Exception()

    /** 列分享里一个文件夹（根是 "0"），一页 50 个翻到底。 */
    private suspend fun detail(s: PanShare, pdir: String, parent: String): List<PanFile> = withToken(s) { stoken ->
        val st = site(s)
        val out = ArrayList<PanFile>()
        var page = 1
        var seen = 0
        while (true) {
            val q = arrayListOf<Pair<String, Any?>>(
                "pwd_id" to s.id, "stoken" to stoken, "pdir_fid" to pdir, "force" to "0",
                "_page" to page, "_size" to 50, "_fetch_banner" to 1, "_fetch_share" to 1, "_fetch_total" to 1,
                "_sort" to "file_type:asc,file_name:asc",
            )
            if (st.uc) { q += "passcode" to s.code; q += "fetch_file_list" to 1 }
            val j = call(s, if (st.uc) "transfer_share/detail" else "share/sharepage/detail", q.toTypedArray())
            val list = j.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                val fid = o.optString("fid")
                if (fid.isEmpty() || fid == pdir) continue // UC 偶尔把当前文件夹自己也列出来
                val name = unescape(o.optString("file_name").ifEmpty { fid })
                out += PanFile(
                    fid, name, o.optLong("size", -1), dir = !o.optBoolean("file", true),
                    path = if (parent.isEmpty()) name else "$parent/$name",
                    extra = mapOf("token" to o.optString("share_fid_token")),
                )
            }
            seen += list.length()
            val total = j.optJSONObject("metadata")?.optInt("_total", 0) ?: 0
            if (list.length() == 0 || seen >= total || page >= 100) break
            page++
        }
        out
    }

    /** 分享页直接要下载地址（不用存）。夸克大文件会回 23018 size limit，这时 fail 归成 LIMIT，外面改借道。 */
    private suspend fun shareDownload(s: PanShare, f: PanFile): Direct = withToken(s) { stoken ->
        val b = JSONObject().put("fids", JSONArray().put(f.id)).put("pwd_id", s.id).put("stoken", stoken)
        f.extra["token"]?.takeIf { it.isNotEmpty() }?.let { b.put("fids_token", JSONArray().put(it)) }
        link(s, call(s, "file/download", body = b), f)
    }

    // ———— 网络 ————

    /** file/download 的回包 → Direct。夸克下载地址校验 Cookie 和 UA，三样都要带上。 */
    internal fun link(s: PanShare, j: JSONObject, f: PanFile): Direct {
        val st = site(s)
        val d = j.optJSONArray("data")?.optJSONObject(0)
        val url = d?.optString("download_url").orEmpty()
        if (url.isEmpty()) throw PanError(PanError.Kind.OTHER, "${st.name}没给下载地址，过会儿再试", "${st.tag} · download_url")
        return Direct(
            url,
            mapOf("Cookie" to cookie(s), "Referer" to st.referer, "User-Agent" to st.ua),
            d?.optLong("size", f.size) ?: f.size,
        )
    }

    /** 他的 Cookie：内置浏览器里的，加上这次会话里服务器刷新过的 __puus。 */
    private fun cookie(s: PanShare): String = s.state["cookie"] ?: Pan.cookie(s.disk)

    private fun url(st: Site, path: String, q: Array<out Pair<String, Any?>>): String {
        val base = if (path == "share/sharepage/save") st.save else st.api
        val common = arrayOf<Pair<String, Any?>>("pr" to st.pr, "fr" to "pc", "uc_param_str" to "", "entry" to (if (st.uc) "ft" else null))
        return "$base/$path?" + Pan.form(*(common + q))
    }

    /** 发一次请求，回包 code 不是 0 就按原因抛 PanError；分享接口 stoken 过期抛 Stale。 */
    internal suspend fun call(
        s: PanShare, path: String, q: Array<out Pair<String, Any?>> = emptyArray(), body: JSONObject? = null,
    ): JSONObject {
        val st = site(s)
        val ck = cookie(s)
        val r = Pan.http(
            url(st, path, q),
            mapOf(
                "Cookie" to ck, "Referer" to st.referer, "Origin" to st.referer.trimEnd('/'),
                "User-Agent" to st.ua, "Accept" to "application/json, text/plain, */*",
            ),
            body?.toString(), json = true,
        )
        // 服务器会续发 __puus（登录态短票），记下来这次会话接着用
        r.setCookies().split("; ").firstOrNull { it.startsWith("__puus=") }?.let { puus ->
            s.state["cookie"] = (ck.split(";").map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("__puus=") } + puus).joinToString("; ")
        }
        val j = (try { JSONObject(r.body) } catch (_: Exception) { null })
            ?: throw if (r.code == 401) login(st, 401) else PanError(PanError.Kind.OTHER, "${st.name}回的东西看不懂，过会儿再试", "${st.tag} · HTTP ${r.code}")
        val code = j.optInt("code", 0)
        val msg = j.optString("message")
        if (r.code == 401 || code == 31001) throw login(st, if (code != 0) code else r.code)
        if (code == 0 && j.optInt("status", 200) < 400) return j
        // 分享这边的接口（夸克 share/sharepage/*、UC transfer_share/detail）stoken 过期：14001 或消息里说 token
        if ("share/" in path && path != "share/sharepage/token" && (code == 14001 || "token" in msg.lowercase())) throw Stale()
        if (path == "file/download" && "token" in msg.lowercase() && body?.has("stoken") == true) throw Stale()
        throw fail(st, code, msg)
    }

    private fun login(st: Site, code: Int) =
        PanError(PanError.Kind.LOGIN, "${st.name}还没登录或登录过期了，在内置浏览器里登一下再来", if (code == 0) "${st.tag} · 没有 Cookie" else "${st.tag} · $code")

    /** 按回包的 code 和 message 分原因。夸克的错误码没公开，主要靠 message 里的字眼。 */
    internal fun fail(st: Site, code: Int, msg: String): PanError {
        val m = msg.lowercase()
        val tag = "${st.tag} · $code"
        fun has(vararg w: String) = w.any { it in m }
        return when {
            has("login", "登录", "未登陆") -> login(st, code)
            has("提取码", "passcode", "访问码") ->
                PanError(PanError.Kind.CODE, "提取码不对，核对一下再试", tag)
            has("容量", "空间不足", "capacity", "space") ->
                PanError(PanError.Kind.LIMIT, "你的${st.name}满了，借道存不进去，清一点空间再试", tag)
            code == 23018 || has("size limit", "会员", "vip", "超出", "上限", "limit", "限制") ->
                PanError(PanError.Kind.LIMIT, "这个文件超出了你${st.name}账号的限制（大小或会员），开会员或换个小点的再试", tag)
            has("失效", "不存在", "取消", "违规", "过期", "删除", "封禁", "expired", "not exist", "cancel", "illegal") ->
                PanError(PanError.Kind.GONE, "这个分享没了（失效、被取消或违规），找个新链接吧", tag)
            else -> PanError(PanError.Kind.OTHER, "${st.name}拒绝了：${msg.ifEmpty { "没说原因" }}", tag)
        }
    }

    /** 文件名里偶尔有 HTML 转义（OpenList 也做了这步）。 */
    internal fun unescape(t: String) = t.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&amp;", "&")
}
