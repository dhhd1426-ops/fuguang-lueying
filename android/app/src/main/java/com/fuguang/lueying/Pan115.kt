package com.fuguang.lueying

import org.json.JSONException
import org.json.JSONObject

/**
 * 115 分享直接下，不转存。
 *
 * - 列文件：115cdn.com/webapi/share/snap（share_code、receive_code、cid、offset、limit），不用登录也能列；
 *   115cdn 不通再试 webapi.115.com 上同一个接口。
 * - 下载地址：proapi.115.com/app/share/downurl，POST 表单 data=m115 加密的 JSON，返回的 data 也是 m115 加密的，
 *   加解密在 [Pan115Crypt]。这个接口要登录（内置浏览器里的 UID / CID / SEID / KID），
 *   还只认 115 自家客户端的 UA，普通浏览器 UA 会回「当前版本过低」。
 * 做法照 SheltonZhu/115driver（OpenList 的 115_share 驱动用它）的 GetShareSnap / DownloadByShareCode。
 */
object Pan115 : PanApi {
    /** 115 浏览器的 UA。下载地址接口只认 115 客户端；拿到的 CDN 地址也用同一个 UA 去下，最稳。 */
    private const val UA_115 = "Mozilla/5.0 115Browser/27.0.5.7"
    private val SNAP_HOSTS = listOf("https://115cdn.com/webapi/share/snap", "https://webapi.115.com/share/snap")
    private const val DOWNURL = "https://proapi.115.com/app/share/downurl"
    private const val PAGE = 1000

    override suspend fun open(s: PanShare): PanRoot {
        val (info, files) = snap(s, "", "")
        val share = info.optJSONObject("shareinfo")
        // 分享设了「自动填访问码」时，链接里没码也能列；下载接口还是要码，就记下它给的
        share?.optString("receive_code").orEmpty().let { if (it.isNotEmpty() && s.code.isEmpty()) s.state["rc"] = it }
        if (files.isEmpty()) {
            val forbid = share?.optString("forbid_reason").orEmpty()
            if (forbid.isNotBlank()) throw PanError(PanError.Kind.GONE, "这个分享被 115 屏蔽了：$forbid", "115 · forbid")
        }
        val title = share?.optString("share_title").orEmpty().ifBlank { files.firstOrNull()?.name ?: "115 分享" }
        val owner = info.optJSONObject("userinfo")?.optString("user_name").orEmpty()
        return PanRoot(title, files, owner)
    }

    override suspend fun list(s: PanShare, dir: PanFile): List<PanFile> = snap(s, dir.id, dir.path).second

    override suspend fun direct(s: PanShare, f: PanFile): Direct {
        val cookie = Pan.cookie(Disk.P115)
        if ("UID=" !in cookie) throw PanError(PanError.Kind.LOGIN, "115 要先登录才能下，去内置浏览器里登一下", "115 · 没有 Cookie")
        val key = Pan115Crypt.newKey()
        val plain = JSONObject()
            .put("share_code", s.id)
            .put("receive_code", code(s))
            .put("file_id", f.id)
            .toString()
        val r = Pan.http(
            "$DOWNURL?t=${System.currentTimeMillis() / 1000}",
            headers = mapOf("User-Agent" to UA_115, "Accept" to "application/json;charset=UTF-8", "Cookie" to cookie),
            body = Pan.form("data" to Pan115Crypt.encode(plain.toByteArray(), key)),
        )
        val j = json(r)
        check(j)
        val enc = j.optString("data")
        if (enc.isBlank() || enc == "[]" || enc == "{}") throw PanError(PanError.Kind.GONE, "这个文件在分享里找不到了", "115 · 下载地址为空")
        val d = try {
            JSONObject(String(Pan115Crypt.decode(enc, key), Charsets.UTF_8))
        } catch (e: Exception) {
            throw PanError(PanError.Kind.OTHER, "115 的下载地址解不开，可能接口改了", "115 · 解密失败")
        }
        // 文件夹的 url 是空的 / 不是对象
        val url = d.optJSONObject("url")?.optString("url").orEmpty()
        if (url.isEmpty()) throw PanError(PanError.Kind.OTHER, "这一项拿不到下载地址，可能是文件夹", "115 · url 为空")
        val size = d.opt("fs")?.toString()?.toLongOrNull() ?: f.size
        return Direct(url, mapOf("User-Agent" to UA_115, "Cookie" to cookie), size)
    }

    // ———— 列文件 ————

    /** 列一个目录的全部（翻页拉完）。cid 空是分享根目录。返回 snap 的 data（拿标题用）和文件列表。 */
    private suspend fun snap(s: PanShare, cid: String, base: String): Pair<JSONObject, List<PanFile>> {
        val out = ArrayList<PanFile>()
        var first: JSONObject? = null
        var offset = 0
        while (true) {
            val data = snapPage(s, cid, offset)
            if (first == null) first = data
            val arr = data.optJSONArray("list")
            val n = arr?.length() ?: 0
            for (i in 0 until n) {
                val o = arr!!.getJSONObject(i)
                val name = o.optString("n")
                // fc=0 是文件夹，编号在 cid；文件编号在 fid
                val dir = o.optInt("fc", 1) == 0
                val id = if (dir) o.optString("cid") else o.optString("fid")
                out += PanFile(
                    id = id, name = name, size = o.opt("s")?.toString()?.toLongOrNull() ?: -1, dir = dir,
                    path = if (base.isEmpty()) name else "$base/$name",
                    extra = mapOf("sha1" to o.optString("sha")),
                )
            }
            offset += n
            if (n == 0 || offset >= data.optInt("count", 0)) break
        }
        return first!! to out
    }

    private suspend fun snapPage(s: PanShare, cid: String, offset: Int): JSONObject {
        val query = Pan.form(
            "share_code" to s.id, "receive_code" to s.code, "cid" to cid,
            "offset" to offset, "limit" to PAGE, "asc" to 0, "format" to "json",
        )
        val headers = mapOf(
            "User-Agent" to Pan.PC_UA,
            "Accept" to "application/json, text/plain, */*",
            "Referer" to "https://115cdn.com/s/${s.id}?password=${Pan.enc(s.code)}&",
            "Cookie" to Pan.cookie(Disk.P115),
        )
        var last: PanError? = null
        for (host in SNAP_HOSTS) {
            val r = try { Pan.http("$host?$query", headers) } catch (e: PanError) { last = e; continue }
            val j = try { json(r) } catch (e: PanError) { last = e; continue }
            try { check(j) } catch (e: PanError) {
                // 链接里根本没带码，就别说「不对」，直接说要码
                if (e.kind == PanError.Kind.CODE && s.code.isEmpty()) throw PanError(PanError.Kind.CODE, "这个分享要访问码，把四位码一起贴进来", e.code)
                throw e
            }
            return j.optJSONObject("data") ?: throw PanError(PanError.Kind.OTHER, "115 返回的分享内容是空的", "115 · 没有 data")
        }
        throw last ?: PanError(PanError.Kind.OTHER, "115 没有回应", "115 · 无响应")
    }

    // ———— 公共 ————

    private fun code(s: PanShare) = s.code.ifEmpty { s.state["rc"].orEmpty() }

    private fun json(r: Pan.Resp): JSONObject = try {
        JSONObject(r.body)
    } catch (e: JSONException) {
        throw PanError(PanError.Kind.OTHER, "115 返回的东西看不懂，过会儿再试", "115 · HTTP ${r.code}")
    }

    /** state 不是 true 就按 errno 和 error 文字分原因抛出去。 */
    private fun check(j: JSONObject) {
        val ok = when (val st = j.opt("state")) { is Boolean -> st; is Number -> st.toInt() != 0; else -> false }
        if (ok) return
        val errno = j.opt("errno")?.toString()?.toIntOrNull()?.takeIf { it != 0 }
            ?: j.opt("errNo")?.toString()?.toIntOrNull()?.takeIf { it != 0 } ?: 0
        val msg = j.optString("error").ifBlank { j.optString("msg") }
        val rc = "115 · errno $errno"
        throw when {
            errno in LOGIN_ERRNO || Regex("登录|登陆").containsMatchIn(msg) ->
                PanError(PanError.Kind.LOGIN, "115 登录过期了，去内置浏览器里重新登一下", rc)
            errno in CODE_ERRNO || Regex("访问码|提取码|接收码|密码").containsMatchIn(msg) ->
                PanError(PanError.Kind.CODE, "访问码不对，核对一下链接后面的四位码", rc)
            errno in GONE_ERRNO || Regex("取消|过期|失效|不存在|已删除|被删除|违规|屏蔽|审核").containsMatchIn(msg) ->
                PanError(PanError.Kind.GONE, "这个分享已经失效或被取消了", rc)
            else -> PanError(PanError.Kind.OTHER, if (msg.isNotBlank()) "115 说：$msg" else "115 出错了，过会儿再试", rc)
        }
    }

    // 115driver error.go 和 p115client 里见过的码
    private val LOGIN_ERRNO = setOf(99, 990001, 40101017, 40101032, 40101035, 40101037)
    /** 4100008：访问码不对（p115nano302 遇到它会重新取码再试）。 */
    private val CODE_ERRNO = setOf(4100008)
    /** 4100009 分享无效 · 4100026 分享不存在或已删除。 */
    private val GONE_ERRNO = setOf(4100009, 4100026)
}
