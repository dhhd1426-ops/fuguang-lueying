package com.fuguang.lueying

import com.fuguang.lueying.PanError.Kind.*
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URL
import java.util.UUID

/**
 * 蓝奏云 + 蓝奏优享（ilanzou.com）：不用登录、不用转存。
 * 参考 nfd LzTool / IzTool、OpenList drivers/lanzou/util.go（反爬和签名在 [PanLanzouGuard]）。
 *
 * 蓝奏云：
 * - 文件夹分享（路径 b 开头 / s/ 开头，或页面里有 filemoreajax）：POST filemoreajax.php 一页一页拿，表单从页面脚本里抠。
 * - 单文件：页面里的 iframe（/fn?…）→ 里面的脚本给 ajaxm.php 地址和 sign → POST 得 dom + url →
 *   GET「dom/file/url」不跟跳转，302 的 Location 就是真实地址；回的是「验证并下载」页就等 2 秒 POST /file/ajax.php el=2。
 * - 带密码的单文件没有 iframe，页面里直接是 ajaxm.php，表单加 p=密码。
 * - 每一步都可能先回 acw_sc__v2 挑战页，算好 cookie 再请求一次。
 * 蓝奏优享：api.ilanzou.com/unproved/ 下的 recommend/list、share/list、file/redirect（302 给地址）。
 */
object PanLanzou : PanApi {
    private const val LZ = "LANZOU"
    private const val IZ_API = "https://api.ilanzou.com/unproved"
    private val FILE_ORIGINS = listOf("https://apifile.lanzouw.com", "https://w1.lanzn.com", "https://www.lanzoux.com")
    private val SUB_FOLDER = Regex("""(?i)(?:folderlink|mbxfolder).+?href="/(.+?)"(?:.+?filename")?>(.+?)<""")

    override suspend fun open(s: PanShare): PanRoot {
        if ("ilanzou.com" in s.link) return izOpen(s)
        // 去掉 #… 和 ?pwd=…（密码走表单），留着 webpage= 这类页面要的参数
        val url = s.link.substringBefore('#').replace(Regex("""[?&](?:pwd|password)=[^&]*"""), "").let { if ('?' !in it && '&' in it) it.replaceFirst('&', '?') else it }
        val html = page(s, url)
        val title = Regex("""<title>(.+?)(?:\s*-\s*蓝奏云)?</title>""").find(html)?.groupValues?.get(1)?.trim().orEmpty()
        if (folder(url, html)) return PanRoot(title.ifEmpty { "蓝奏云文件夹" }, listFolder(s, url, html, ""))
        // 单文件：带密码的先用 ajax 验一次密码（也顺便拿到文件名，密码页上没写）
        var name = fileName(html) ?: title
        if (iframe(html) == null && needPwd(html)) {
            if (s.code.isEmpty()) throw PanError(CODE, "这个蓝奏分享要密码", "$LZ · 缺密码")
            name = ajax(s, url, html, s.code).optString("inf").ifEmpty { name }
        }
        val size = PanLanzouGuard.size(Regex("""(?:文件大小|大小)\s*[:：]?\s*(?:</span>|</div><div[^>]*>)?\s*([0-9.]+\s*[BKMGT])""").find(html)?.groupValues?.get(1))
        val f = PanFile(url, name.ifEmpty { "蓝奏文件" }, size, false, extra = mapOf("pwd" to s.code))
        return PanRoot(f.name, listOf(f), Regex("""分享用户：?\s*(?:</span>)?\s*<font>(.*?)</font>""").find(html)?.groupValues?.get(1).orEmpty())
    }

    override suspend fun list(s: PanShare, dir: PanFile): List<PanFile> {
        if ("ilanzou.com" in s.link) return izList(s, dir.id, dir.path)
        // 蓝奏的子文件夹是会员功能，id 是它自己的分享地址；没有子文件夹的分享根本走不到这里
        val html = page(s, dir.id)
        return if (folder(dir.id, html)) listFolder(s, dir.id, html, dir.path) else emptyList()
    }

    override suspend fun direct(s: PanShare, f: PanFile): Direct {
        if ("ilanzou.com" in s.link) return izDirect(s, f)
        val html = page(s, f.id)
        val pwd = f.extra["pwd"].orEmpty()
        val fn = iframe(html)
        val o = when {
            fn != null -> origin(f.id).plus(fn).let { inner -> ajax(s, inner, page(s, inner), null) }
            needPwd(html) -> if (pwd.isEmpty()) throw PanError(CODE, "这个蓝奏分享要密码", "$LZ · 缺密码") else ajax(s, f.id, html, pwd)
            else -> throw (gone(html) ?: PanError(OTHER, "蓝奏页面认不出下载入口，可能改版了", "$LZ · 无 iframe"))
        }
        val dom = o.optString("dom"); val tok = o.optString("url")
        if (dom.isEmpty() || tok.isEmpty() || tok == "0") throw PanError(OTHER, "蓝奏没给下载地址", "$LZ · 无 dom/url")
        return Direct(follow(s, "$dom/file/$tok"), mapOf("User-Agent" to Pan.PC_UA, "Accept-Language" to "zh-CN,zh;q=0.9"), f.size)
    }

    // ———— 蓝奏云：页面 ————

    private fun origin(u: String) = URL(u).let { "${it.protocol}://${it.host}" }

    private fun folder(url: String, html: String): Boolean {
        val path = URL(url).path.trim('/')
        return path.startsWith("s/") || (path.isNotEmpty() && '/' !in path && path[0].lowercaseChar() == 'b') ||
            "filemoreajax" in html || "id=\"infos\"" in html
    }

    /** 普通文件页里的下载内页 /fn?…（带密码的页面没有它）。 */
    private fun iframe(html: String) = Regex("""(?i)<iframe[^>]+src\s*=\s*["'](/fn\?[^"'\s>]+)["']""").find(html)?.groupValues?.get(1)

    private fun needPwd(html: String) = "down_p" in html || "id=\"pwd\"" in html || "passwddiv" in html || "pwdload" in html

    private fun fileName(html: String): String? =
        Regex("""filenajax">(.*?)<|<div class="b"><span>(.*?)</span>|padding: 56px 0px 20px 0px;">(.*?)<|var filename = '(.+?)';""").find(html)
            ?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.let { Jsoup.parse(it).text() }

    /** 分享页上「没了」的几种说法；不是就返回 null。 */
    private fun gone(html: String): PanError? = when {
        "取消分享" in html -> PanError(GONE, "这个文件取消分享了", "$LZ · 文件取消分享了")
        "文件不存在" in html || "已经被删除" in html -> PanError(GONE, "文件不在了，可能被删了", "$LZ · 文件不存在")
        else -> Regex("""class="off1"></div>\s*</div>\s*([^<]{2,80})<""").find(html)?.groupValues?.get(1)?.trim()
            ?.let { PanError(GONE, it, "$LZ · $it") }
    }

    /** GET 一个页面（自动过 acw 挑战），顺便认「分享没了」。 */
    private suspend fun page(s: PanShare, url: String): String {
        val h = mapOf("User-Agent" to Pan.PC_UA, "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8", "Accept-Language" to "zh-CN,zh;q=0.9")
        val body = send(s, url, h).body
        gone(body)?.let { if ("filemoreajax" !in body && "iframe" !in body) throw it }
        return body
    }

    /** 发请求，碰到 acw_sc__v2 挑战页就算 cookie 再发一次。extraCookie 如 down_ip=1。 */
    private suspend fun send(s: PanShare, url: String, h: Map<String, String>, body: String? = null, follow: Boolean = true, extraCookie: String? = null): Pan.Resp {
        repeat(3) {
            val ck = listOfNotNull(extraCookie, s.state["acw"]?.let { "acw_sc__v2=$it" }).joinToString("; ")
            val r = Pan.http(url, h + ("Cookie" to ck), body, follow = follow)
            if (!PanLanzouGuard.challenged(r.body)) return r
            s.state["acw"] = PanLanzouGuard.acw(r.body) ?: throw PanError(OTHER, "蓝奏的反爬页认不出来", "$LZ · acw 无 arg1")
        }
        throw PanError(LIMIT, "蓝奏一直拦着，过一会儿再试", "$LZ · acw 没过")
    }

    private fun ajaxHeaders(referer: String) = mapOf(
        "User-Agent" to Pan.PC_UA, "Accept" to "application/json, text/javascript, */*; q=0.01",
        "X-Requested-With" to "XMLHttpRequest", "Referer" to referer, "Origin" to origin(referer),
    )

    private fun json(t: String) = t.indexOf('{').takeIf { it >= 0 }?.let { runCatching { JSONObject(t.substring(it)) }.getOrNull() }

    /** 这家主机不能用（空、不是 JSON、「已超时」），换下一个。 */
    private fun hostBad(o: JSONObject?) = o == null || (o.optInt("zt") != 1 && "超时" in o.optString("inf") + o.optString("info"))

    /** 文件的下载 ajax：先打页面里写的地址，主机不行再换 apifile / w1 / www。返回 zt=1 的 JSON。 */
    private suspend fun ajax(s: PanShare, referer: String, html: String, pwd: String?): JSONObject {
        val a = PanLanzouGuard.ajax(html, origin(referer), pwd)
            ?: throw PanError(OTHER, "蓝奏页面认不出下载参数，可能改版了", "$LZ · 无 sign")
        val path = URL(a.url).let { it.path + "?" + it.query }
        val hosts = (listOf(a.url) + (listOf(origin(referer)) + FILE_ORIGINS).map { it + path }).distinct()
        var o: JSONObject? = null
        for (u in hosts) {
            o = json(send(s, u, ajaxHeaders(referer), Pan.form(*a.form.toTypedArray())).body)
            if (!hostBad(o)) break
        }
        o ?: throw PanError(OTHER, "蓝奏的下载接口没回数据", "$LZ · ajax 空")
        if (o.optInt("zt") != 1) throw fail(o.optString("inf").ifEmpty { o.optString("info") })
        return o
    }

    private fun fail(inf: String): PanError = when {
        "密码" in inf -> PanError(CODE, "密码不对", "$LZ · $inf")
        listOf("取消", "不存在", "失效", "删除", "违规").any { it in inf } -> PanError(GONE, "分享没了：$inf", "$LZ · $inf")
        listOf("频繁", "限制", "流量").any { it in inf } -> PanError(LIMIT, "蓝奏限制了：$inf", "$LZ · $inf")
        else -> PanError(OTHER, inf.ifEmpty { "蓝奏出错了" }, "$LZ · ${inf.ifEmpty { "未知" }}")
    }

    /** 「dom/file/…」这一跳：302 就是真实地址；回验证页就等两秒再 POST /file/ajax.php。 */
    private suspend fun follow(s: PanShare, mid: String): String {
        val o = origin(mid)
        val h = mapOf("User-Agent" to Pan.PC_UA, "Accept" to "text/html,*/*;q=0.8", "Accept-Language" to "zh-CN,zh;q=0.9", "Referer" to "$o/")
        repeat(2) { round ->
            val r = send(s, mid, h, follow = false, extraCookie = "down_ip=1")
            r.header("Location")?.takeIf { it.startsWith("http") && "SignError" !in it }?.let { return it }
            val file = Regex("""'file'\s*:\s*'([^']+)'""").find(r.body)?.groupValues?.get(1)
            val sign = Regex("""'sign'\s*:\s*'([^']+)'""").find(r.body)?.groupValues?.get(1)
            if (file == null || sign == null) throw PanError(OTHER, "蓝奏下载页认不出来", "$LZ · 验证页无 sign")
            delay(2200L) // 太早 POST 会得到 ?SignError
            val v = send(s, "$o/file/ajax.php", ajaxHeaders(mid), Pan.form("file" to file, "el" to 2, "sign" to sign), extraCookie = "down_ip=1")
            val u = json(v.body)?.optString("url").orEmpty()
            if (u.startsWith("http") && "SignError" !in u) return u
            if (round == 1) throw PanError(OTHER, "蓝奏的下载验证没过，再试一次", "$LZ · 二次验证失败")
        }
        throw PanError(OTHER, "蓝奏没给真实地址", "$LZ · 无地址")
    }

    // ———— 蓝奏云：文件夹 ————

    private suspend fun listFolder(s: PanShare, url: String, html: String, base: String): List<PanFile> {
        val out = ArrayList<PanFile>()
        fun at(n: String) = if (base.isEmpty()) n else "$base/$n"
        // 会员才有的子文件夹，直接写在页面上
        for (m in SUB_FOLDER.findAll(html)) {
            val name = Jsoup.parse(m.groupValues[2]).text()
            out += PanFile(origin(url) + "/" + m.groupValues[1], name, -1, true, at(name))
        }
        val a = PanLanzouGuard.folderAjax(html, origin(url), if (base.isEmpty()) s.code else "")
            ?: return out.ifEmpty { throw (gone(html) ?: PanError(OTHER, "蓝奏文件夹页认不出来，可能改版了", "$LZ · 无 filemoreajax")) }
        val path = URL(a.url).let { it.path + "?" + it.query }
        val hosts = (listOf(origin(url)) + FILE_ORIGINS.drop(1)).map { it + path }.distinct()
        val h = mapOf("User-Agent" to Pan.PHONE_UA, "Referer" to url, "X-Requested-With" to "XMLHttpRequest", "Accept-Language" to "zh-CN,zh;q=0.9")
        var host = hosts[0]
        var pg = 1
        var busy = 0
        while (pg <= 100) {
            val form = Pan.form(*a.form.map { if (it.first == "pg") "pg" to pg.toString() else it }.toTypedArray())
            var o: JSONObject? = null
            for (u in listOf(host) + hosts.filter { it != host }) {
                o = json(send(s, u, h, form).body)
                if (!hostBad(o)) { host = u; break }
            }
            o ?: throw PanError(OTHER, "蓝奏文件夹列表没回数据", "$LZ · filemoreajax 空")
            when (o.optInt("zt")) {
                1 -> {}
                2 -> break // 没有更多了
                3 -> throw PanError(CODE, if (s.code.isEmpty()) "这个蓝奏文件夹要密码" else "密码不对", "$LZ · ${o.optString("info")}")
                4 -> { if (++busy > 5) throw PanError(LIMIT, "蓝奏说太频繁了，过一会儿再试", "$LZ · zt 4"); delay(1000L); continue }
                else -> throw fail(o.optString("info").ifEmpty { o.optString("inf") })
            }
            val arr = o.optJSONArray("text") ?: break
            if (arr.length() == 0) break
            for (i in 0 until arr.length()) {
                val j = arr.getJSONObject(i)
                val name = Jsoup.parse(j.optString("name_all")).text()
                // 文件夹里的文件不加密，打开它自己的分享页下
                out += PanFile(origin(url) + "/" + j.optString("id"), name, PanLanzouGuard.size(j.optString("size")), false, at(name))
            }
            pg++
            delay(600L) // 翻太快会被 zt=4 打回
        }
        return out
    }

    // ———— 蓝奏优享 ————

    private fun izUuid(s: PanShare) = s.state.getOrPut("izUuid") { UUID.randomUUID().toString() }
    private fun izId(s: PanShare) = Regex("""/s/(\w+)""").find(s.link)?.groupValues?.get(1) ?: s.id
    private fun izQuery(s: PanShare) = "devType=6&devModel=Chrome&uuid=${izUuid(s)}&extra=2&timestamp=${PanLanzouGuard.iz(System.currentTimeMillis().toString())}"
    private val IZ_H = mapOf("User-Agent" to Pan.PC_UA, "Accept" to "application/json, text/plain, */*", "Origin" to "https://www.ilanzou.com", "Referer" to "https://www.ilanzou.com/")

    private suspend fun izCall(s: PanShare, url: String): JSONObject {
        val o = json(send(s, url, IZ_H, "").body) ?: throw PanError(OTHER, "蓝奏优享没回数据", "ILANZOU · 空")
        if (o.optInt("code", -1) == 200) return o
        throw izFail(s, o)
    }

    private fun izFail(s: PanShare, o: JSONObject): PanError {
        val code = o.optInt("code", -1)
        val msg = o.optString("msg")
        val c = "ILANZOU · code $code"
        return when {
            "码" in msg || "密码" in msg -> PanError(CODE, if (s.code.isEmpty()) "这个分享要提取码" else "提取码不对", c)
            listOf("不存在", "取消", "失效", "删除", "违规").any { it in msg } -> PanError(GONE, "分享没了：$msg", c)
            listOf("流量", "限制", "频繁", "会员").any { it in msg } -> PanError(LIMIT, "蓝奏优享限制了：$msg", c)
            else -> PanError(OTHER, msg.ifEmpty { "蓝奏优享出错了" }, c)
        }
    }

    private fun izFile(j: JSONObject, uid: String, base: String): PanFile {
        val dir = j.optInt("fileType") == 2
        val name = j.optString(if (dir && j.has("name")) "name" else "fileName").ifEmpty { j.optString("name") }
        val path = if (base.isEmpty()) name else "$base/$name"
        return if (dir) PanFile(j.optString("folderId"), name, -1, true, path)
        else PanFile(j.optString("fileId"), name, j.optLong("fileSize", -1).let { if (it < 0) -1 else it * 1024 }, false, path,
            mapOf("uid" to j.optString("userId").ifEmpty { uid }))
    }

    private suspend fun izOpen(s: PanShare): PanRoot {
        // 先请求一次会员接口（同一个 uuid），不然后面的接口不认（nfd IzTool 24.5.12 的说明）
        runCatching { Pan.http("$IZ_API/buy/vip/list?${izQuery(s)}", IZ_H, "") }
        val code = if (s.code.isEmpty()) "" else "&code=${Pan.enc(s.code)}"
        val o = izCall(s, "$IZ_API/recommend/list?${izQuery(s)}&shareId=${izId(s)}&type=0&offset=1&limit=60$code")
        val share = o.optJSONArray("list")?.optJSONObject(0) ?: throw PanError(GONE, "分享里没有东西了", "ILANZOU · 空列表")
        val uid = share.optString("userId")
        val arr = share.optJSONArray("fileList")
        val files = (0 until (arr?.length() ?: 0)).map { izFile(arr!!.getJSONObject(it), uid, "") }
        return PanRoot(files.firstOrNull()?.name ?: "蓝奏优享分享", files, share.optJSONObject("map")?.optString("userName").orEmpty())
    }

    private suspend fun izList(s: PanShare, folder: String, base: String): List<PanFile> {
        val out = ArrayList<PanFile>()
        for (p in 1..50) {
            val arr = izCall(s, "$IZ_API/share/list?${izQuery(s)}&shareId=${izId(s)}&folderId=$folder&offset=$p&limit=60").optJSONArray("list") ?: break
            for (i in 0 until arr.length()) out += izFile(arr.getJSONObject(i), "", base)
            if (arr.length() < 60) break
        }
        return out
    }

    private suspend fun izDirect(s: PanShare, f: PanFile): Direct {
        val now = System.currentTimeMillis()
        val q = "downloadId=${PanLanzouGuard.iz("${f.id}|${f.extra["uid"].orEmpty()}")}&enable=1&devType=6&uuid=${izUuid(s)}" +
            "&timestamp=${PanLanzouGuard.iz(now.toString())}&auth=${PanLanzouGuard.iz("${f.id}|$now")}&shareId=${izId(s)}"
        val r = send(s, "$IZ_API/file/redirect?$q", IZ_H, follow = false)
        val loc = r.header("Location")
        if (loc.isNullOrEmpty()) {
            val o = json(r.body)
            throw if (o != null && o.has("code")) izFail(s, o) else PanError(OTHER, "蓝奏优享没给下载地址", "ILANZOU · HTTP ${r.code}")
        }
        return Direct(loc, mapOf("User-Agent" to Pan.PC_UA, "Referer" to "https://www.ilanzou.com/"), f.size)
    }
}
