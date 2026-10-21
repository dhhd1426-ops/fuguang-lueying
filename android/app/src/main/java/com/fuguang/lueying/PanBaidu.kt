package com.fuguang.lueying

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

/**
 * 百度网盘分享直接下。登录靠内置浏览器里的 Cookie（BDUSS / STOKEN）。
 *
 * 走两条路：
 * 1. 不转存（先走这条）。照抄 baiduwp-php（app/Parse.php）和 AList 的 baidu_share 驱动：
 *    share/verify 验提取码拿 randsk（= Cookie 里的 BDCLND）→ share/wxlist 列文件（顺带给 shareid / uk / seckey）
 *    → share/tplconfig 拿 sign + timestamp → api/sharedownload 拿 dlink → 带 UA「LogStatistic」请求 dlink，302 的 Location 就是真地址。
 *    baiduwp 的经验：5MB 以下谁都行；大文件普通账号常被拒（31326 / 118）或只给限速地址，它是靠 SVIP 账号解的。
 * 2. 借道（不转存拿不到地址时才走）：share/transfer 存进他网盘的「/浮光临时」→ api/filemetas?dlink=1 拿 dlink
 *    → 同样解出真地址 → 马上删掉那份。cleanup 把「/浮光临时」整个删掉。转存参数照 BaiduPanFilesTransfers（src/network.py），
 *    filemetas 照 OpenList 的 baidu_netdisk 驱动（linkCrack，UA「netdisk」）。
 * 只是被限速（地址里有 qdall01 / 没有 tsl=0）不算失败：借道拿到的对非会员一样限速，没必要再转存一次。
 */
object PanBaidu : PanApi {
    private const val API = "https://pan.baidu.com"
    private const val TEMP = "/浮光临时"
    private const val SHARE_UA = "LogStatistic"   // baiduwp：分享 dlink 用这个 UA，不对就 31326
    private const val NETDISK_UA = "netdisk"       // OpenList / AList：接口和自己网盘里的 dlink 用这个
    /** BaiduPCS-Go 用的安卓客户端 UA。 */
    private const val APP_UA = PanBaiduLocate.UA
    private val SHARE_UAS = listOf(SHARE_UA, APP_UA, NETDISK_UA, "pan.baidu.com")
    private val OWN_UAS = listOf(NETDISK_UA, APP_UA, "pan.baidu.com", SHARE_UA)
    private val dirLock = Mutex()

    // ———— 打开、列目录 ————

    override suspend fun open(s: PanShare): PanRoot {
        login()
        if (s.code.isNotEmpty()) verify(s)
        val files = wxlist(s, "", root = true, parent = "")
        if (files.isEmpty()) throw PanError(PanError.Kind.GONE, "分享是空的，里面什么都没有", "BAIDU · empty")
        val title = if (files.size == 1) files[0].name else "${files[0].name} 等 ${files.size} 个"
        return PanRoot(title, files)
    }

    override suspend fun list(s: PanShare, dir: PanFile): List<PanFile> =
        wxlist(s, dir.extra["path"].orEmpty(), root = false, parent = dir.path)

    /** 验提取码：拿 randsk，存进 state（当 BDCLND Cookie 用，借道转存必须带）。 */
    private suspend fun verify(s: PanShare) {
        val url = "$API/share/verify?" + Pan.form("surl" to s.id, "t" to System.currentTimeMillis(),
            "channel" to "chunlei", "web" to 1, "clienttype" to 0)
        val j = json(Pan.http(url, mapOf("Cookie" to ck(s), "Referer" to "$API/share/init?surl=${s.id}"),
            Pan.form("pwd" to s.code, "vcode" to "", "vcode_str" to "")))
        when (val e = j.optInt("errno", -999)) {
            0 -> s.state["randsk"] = j.optString("randsk")
            -9, -12 -> throw codeErr(s, e)
            -62 -> throw PanError(PanError.Kind.LIMIT, "试提取码太频繁了，过几分钟再来", "BAIDU · errno -62")
            -6, -4 -> throw loginErr(e)
            -1, 105, 115, 116 -> throw gone(e)
            else -> throw other(e)
        }
    }

    /** share/wxlist：根目录 root=1、dir 空；子目录给服务器上的 path。一页 1000 个，翻到底。 */
    private suspend fun wxlist(s: PanShare, dir: String, root: Boolean, parent: String): List<PanFile> {
        val out = ArrayList<PanFile>()
        var page = 1
        while (true) {
            val body = Pan.form("shorturl" to "1" + s.id, "dir" to dir, "root" to if (root) 1 else 0,
                "pwd" to s.code, "page" to page, "num" to 1000, "order" to "time")
            val j = json(Pan.http("$API/share/wxlist?channel=weixin&version=2.2.2&clienttype=25&web=1",
                mapOf("User-Agent" to NETDISK_UA, "Cookie" to ck(s), "Referer" to "$API/disk/home"), body))
            if (j.optInt("errno", -999) != 0) throw listErr(s, j)
            val d = j.optJSONObject("data") ?: throw other("no data")
            if (d.optString("seckey").isNotEmpty()) {
                s.state["shareid"] = d.opt("shareid").toString()
                s.state["uk"] = d.opt("uk").toString()
                // wxlist 的 seckey 是 URL 安全写法，换回普通 base64（baiduwp decodeSceKey）
                s.state["seckey"] = d.optString("seckey").replace('-', '+').replace('~', '=').replace('_', '/')
            }
            val arr = d.optJSONArray("list") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("server_filename")
                val fsid = o.opt("fs_id").toString()
                out += PanFile(fsid, name, o.optLong("size", -1), o.optString("isdir") == "1",
                    if (parent.isEmpty()) name else "$parent/$name",
                    mapOf("fs_id" to fsid, "path" to o.optString("path")))
            }
            if (!d.optBoolean("has_more", false) && arr.length() < 1000) break
            page++
        }
        return out
    }

    // ———— 下载地址 ————

    override suspend fun direct(s: PanShare, f: PanFile): Direct {
        login()
        PanBaiduLocate.member(ck(s))
        val first = try { return share(s, f) } catch (e: PanError) { e }
        // 分享没了、没登录、断网：借道也救不了，直接报
        if (first.kind == PanError.Kind.GONE || first.kind == PanError.Kind.LOGIN || first.kind == PanError.Kind.NET) throw first
        return try { borrow(s, f) } catch (e: PanError) {
            if (e.kind != PanError.Kind.LIMIT && e.kind != PanError.Kind.OTHER) throw e
            // 两条路都没成：两步的码都写上，真机截图就看得出卡在哪
            throw PanError(e.kind, e.why, first.code + " ｜ " + e.code.removePrefix("BAIDU · "))
        }
    }

    /** 路 1：不转存。api/sharedownload 拿 dlink（AList Link / baiduwp getDlink）。 */
    private suspend fun share(s: PanShare, f: PanFile): Direct {
        if (s.state["seckey"] == null) wxlist(s, "", root = true, parent = "")
        val (sign, ts) = sign(s)
        val sekey = s.state["seckey"] ?: URLDecoder.decode(s.state["randsk"].orEmpty(), "UTF-8")
        val url = "$API/api/sharedownload?" + Pan.form("app_id" to 250528, "channel" to "chunlei", "clienttype" to 12,
            "sign" to sign, "timestamp" to ts, "web" to 1)
        val body = Pan.form("encrypt" to 0, "extra" to """{"sekey":"$sekey"}""", "fid_list" to "[${f.extra["fs_id"] ?: f.id}]",
            "primaryid" to s.state["shareid"], "uk" to s.state["uk"], "product" to "share", "type" to "nolimit")
        val j = json(Pan.http(url, mapOf("Cookie" to ck(s), "Referer" to "$API/disk/home"), body))
        when (val e = j.optInt("errno", -999)) {
            0 -> {}
            -9, 116, 105 -> throw gone(e)
            -6 -> throw loginErr(e)
            -20 -> throw PanError(PanError.Kind.LIMIT, "百度要验证码了，过会儿再试", "BAIDU · errno -20")
            112 -> { s.state.remove("ts"); throw other(e) }   // sign 过期，下次重拿
            else -> throw PanError(PanError.Kind.LIMIT, "百度不让直接下这个文件", "BAIDU · errno $e")   // 118 等
        }
        val dlink = j.optJSONArray("list")?.optJSONObject(0)?.optString("dlink").orEmpty()
        if (dlink.isEmpty()) throw PanError(PanError.Kind.LIMIT, "百度没给下载地址", "BAIDU · no dlink")
        return resolve(dlink, SHARE_UAS, f.size, "直接")
    }

    /** sign + timestamp，5 分钟内有效，4 分钟内复用（baiduwp getSign）。 */
    private suspend fun sign(s: PanShare): Pair<String, String> {
        val at = s.state["at"]?.toLongOrNull() ?: 0
        if (System.currentTimeMillis() - at < 240_000 && s.state["ts"] != null) return s.state["sign"]!! to s.state["ts"]!!
        val url = "$API/share/tplconfig?" + Pan.form("surl" to "1" + s.id, "fields" to "sign,timestamp",
            "channel" to "chunlei", "web" to 1, "app_id" to 250528, "clienttype" to 0)
        val j = json(Pan.http(url, mapOf("User-Agent" to "netdisk;pan.baidu.com", "Cookie" to ck(s))))
        if (j.optInt("errno", -999) != 0) throw listErr(s, j)
        val d = j.optJSONObject("data") ?: throw other("no sign")
        s.state["sign"] = d.optString("sign"); s.state["ts"] = d.opt("timestamp").toString()
        s.state["at"] = System.currentTimeMillis().toString()
        return s.state["sign"]!! to s.state["ts"]!!
    }

    /** 路 2：借道。存进「/浮光临时」→ filemetas 拿 dlink → 解出真地址；不放行再走 PCS locatedownload。下完 cleanup 删「/浮光临时」。 */
    private suspend fun borrow(s: PanShare, f: PanFile): Direct {
        val tk = bdstoken(s)
        dirLock.withLock { ensureTemp(s, tk) }
        s.state["borrowed"] = "1"
        if (s.state["shareid"] == null) wxlist(s, "", root = true, parent = "")
        val url = "$API/share/transfer?" + Pan.form("shareid" to s.state["shareid"], "from" to s.state["uk"],
            "sekey" to URLDecoder.decode(s.state["randsk"].orEmpty(), "UTF-8").ifEmpty { null },
            "bdstoken" to tk, "ondup" to "newcopy", "channel" to "chunlei", "web" to 1, "clienttype" to 0)
        val j = json(Pan.http(url, mapOf("Cookie" to ck(s), "Referer" to "$API/s/1${s.id}"),
            Pan.form("fsidlist" to "[${f.extra["fs_id"] ?: f.id}]", "path" to TEMP)))
        when (val e = j.optInt("errno", -999)) {
            0 -> {}
            -6, -4 -> throw loginErr(e)
            -9, -12 -> throw codeErr(s, e)
            -10, 20 -> throw PanError(PanError.Kind.LIMIT, "你的百度网盘满了，腾点地方再下", "BAIDU · errno $e")
            12 -> throw PanError(PanError.Kind.LIMIT, "文件太多，百度不让一次存这么多", "BAIDU · errno 12")
            -62 -> throw PanError(PanError.Kind.LIMIT, "这个分享被访问太多次了，过会儿再试", "BAIDU · errno -62")
            105, 115, 116 -> throw gone(e)
            else -> throw PanError(PanError.Kind.LIMIT, "直接下和转存都没成", "BAIDU · transfer $e")
        }
        // 返回里 extra.list[0].to 是存好后的路径；没有就按名字猜
        val to = j.optJSONObject("extra")?.optJSONArray("list")?.optJSONObject(0)?.optString("to")
            ?.takeIf { it.isNotEmpty() } ?: "$TEMP/${f.name}"
        // 存进去的那份不马上删：下载要用到它，整批下完 cleanup 时把「/浮光临时」一起删
        val viaMeta = runCatching {
            val m = json(Pan.http("$API/api/filemetas?" + Pan.form("target" to arr(to),
                "dlink" to 1, "web" to 5, "origin" to "dlna"), mapOf("User-Agent" to NETDISK_UA, "Cookie" to ck(s))))
            if (m.optInt("errno", -999) != 0) throw PanError(PanError.Kind.LIMIT, "存进去了，但百度不给下载地址", "BAIDU · 借道 filemetas ${m.optInt("errno")}")
            val dlink = m.optJSONArray("info")?.optJSONObject(0)?.optString("dlink").orEmpty()
            if (dlink.isEmpty()) throw PanError(PanError.Kind.LIMIT, "存进去了，但百度不给下载地址", "BAIDU · 借道 no dlink")
            resolve(dlink, OWN_UAS, f.size, "借道")
        }
        viaMeta.getOrNull()?.let { return it }
        val e1 = viaMeta.exceptionOrNull()
        if (e1 is PanError && e1.kind != PanError.Kind.LIMIT && e1.kind != PanError.Kind.OTHER) throw e1
        // 第三条路：PCS locatedownload（BaiduPCS-Go 的做法）
        return try { PanBaiduLocate.direct(bduss(), to, f.size) } catch (e2: PanError) {
            throw PanError(e2.kind, e2.why, ((e1 as? PanError)?.code ?: "BAIDU · 借道 ?") + " ｜ " + e2.code.removePrefix("BAIDU · "))
        }
    }

    /**
     * 请求 dlink 不跟跳转：302 的 Location 是真地址。百度按 UA 放行，各家成熟做法用的 UA 不一样
     * （baiduwp：LogStatistic；OpenList：netdisk；BaiduPCS-Go：安卓客户端那一长串），一个不放行就换下一个。
     * 用 HEAD，不带 Range（baiduwp 就是这么问的；带 Range 有时会被当成下载直接拒）。
     * 第 240 版真机：普通账号 73.7 MB 的分享文件回 403 · 31329，所以这里每一步的码都留着，最后一起写进原因码。
     */
    private suspend fun resolve(dlink: String, uas: List<String>, size: Long, step: String): Direct {
        val ck = "BDUSS=" + bduss()
        val seen = ArrayList<String>()
        for (ua in uas) {
            val r = Pan.http(dlink, mapOf("User-Agent" to ua, "Cookie" to ck), follow = false, method = "HEAD")
            val loc = r.header("Location")
            if (r.code in 300..399 && !loc.isNullOrEmpty()) return Direct(loc, mapOf("User-Agent" to ua), size)
            if (r.code == 200 || r.code == 206) return Direct(dlink, mapOf("User-Agent" to ua, "Cookie" to ck), size)
            // HEAD 没有正文：再 GET 一次看百度的错误码（只读 1 个字节，免得真回了文件）
            val g = Pan.http(dlink, mapOf("User-Agent" to ua, "Cookie" to ck, "Range" to "bytes=0-0"), follow = false)
            g.header("Location")?.takeIf { g.code in 300..399 && it.isNotEmpty() }?.let { return Direct(it, mapOf("User-Agent" to ua), size) }
            val j = runCatching { JSONObject(g.body) }.getOrNull()
            val e = j?.optInt("errno", 0)?.takeIf { it != 0 } ?: j?.optInt("error_code", 0) ?: 0
            if (e == 31360) throw PanError(PanError.Kind.OTHER, "下载地址过期了，再点一次", "BAIDU · $step 31360")
            seen += "${uaName(ua)} ${g.code}" + if (e != 0) "/$e" else ""
        }
        throw PanError(PanError.Kind.LIMIT, "百度没放行下载", "BAIDU · $step " + seen.joinToString(", "))
    }

    private fun uaName(ua: String) = when { ua == SHARE_UA -> "LS"; ua.startsWith("netdisk;P2SP") -> "安卓"; ua == NETDISK_UA -> "nd"; else -> "pan" }

        // ———— 收拾 ————

    override suspend fun cleanup(s: PanShare) {
        if (s.state.remove("borrowed") == null) return
        runCatching { delete(s, bdstoken(s), TEMP) }
    }

    private suspend fun bdstoken(s: PanShare): String {
        s.state["bdstoken"]?.let { return it }
        val url = "$API/api/gettemplatevariable?" + Pan.form("clienttype" to 0, "app_id" to 38824127, "web" to 1,
            "fields" to """["bdstoken","token","uk","isdocuser","servertime"]""")
        val j = json(Pan.http(url, mapOf("Cookie" to ck(s), "Referer" to "$API/disk/home")))
        if (j.optInt("errno", -999) != 0) throw loginErr(j.optInt("errno"))
        val t = j.optJSONObject("result")?.optString("bdstoken").orEmpty()
        if (t.isEmpty()) throw loginErr("no bdstoken")
        s.state["bdstoken"] = t
        return t
    }

    /** 「/浮光临时」不在就建；先查再建，免得百度自动改名成「浮光临时(1)」。 */
    private suspend fun ensureTemp(s: PanShare, tk: String) {
        val l = json(Pan.http("$API/api/list?" + Pan.form("dir" to TEMP, "num" to 1, "web" to 1, "bdstoken" to tk),
            mapOf("Cookie" to ck(s))))
        if (l.optInt("errno", -999) == 0) return
        Pan.http("$API/api/create?" + Pan.form("a" to "commit", "bdstoken" to tk),
            mapOf("Cookie" to ck(s)), Pan.form("path" to TEMP, "isdir" to 1, "block_list" to "[]"))
    }

    private suspend fun delete(s: PanShare, tk: String, path: String) {
        Pan.http("$API/api/filemanager?" + Pan.form("opera" to "delete", "async" to 0, "onnest" to "fail",
            "bdstoken" to tk, "channel" to "chunlei", "web" to 1, "app_id" to 250528, "clienttype" to 0),
            mapOf("Cookie" to ck(s)), Pan.form("filelist" to arr(path)))
    }

    // ———— 小件 ————

    private fun bduss(): String = Regex("""(?:^|;\s*)BDUSS=([^;]+)""").find(Pan.cookie(Disk.BAIDU))?.groupValues?.get(1).orEmpty()

    /** ["路径"]。不用 JSONArray：它会把 / 写成 \/。 */
    private fun arr(p: String) = "[\"" + p.replace("\\", "\\\\").replace("\"", "\\\"") + "\"]"

    private fun login() { if (bduss().isEmpty()) throw loginErr("no BDUSS") }

    /** 浏览器里的 Cookie，验过提取码再补上 BDCLND。 */
    private fun ck(s: PanShare): String {
        val c = Pan.cookie(Disk.BAIDU)
        val r = s.state["randsk"] ?: return c
        return if ("BDCLND=" in c) c.replace(Regex("""BDCLND=[^;]*"""), "BDCLND=$r") else "$c; BDCLND=$r"
    }

    private fun json(r: Pan.Resp): JSONObject = runCatching { JSONObject(r.body) }.getOrElse {
        throw PanError(PanError.Kind.OTHER, "百度回的东西看不懂，过会儿再试", "BAIDU · HTTP ${r.code}")
    }

    /** wxlist / tplconfig 的错：errtype 比 errno 准（baiduwp listError）。 */
    private fun listErr(s: PanShare, j: JSONObject): PanError {
        val e = j.optInt("errno", -999)
        val t = j.opt("errtype")?.toString().orEmpty()
        return when {
            "pw" in t || e == -12 -> codeErr(s, e)
            t == "10" -> PanError(PanError.Kind.GONE, "分享过期了，问分享的人要个新的", "BAIDU · errtype 10")
            t == "3" -> PanError(PanError.Kind.GONE, "这个分享被百度屏蔽了", "BAIDU · errtype 3")
            t == "5" -> if (s.code.isEmpty()) codeErr(s, e) else PanError(PanError.Kind.GONE, "分享没了，或者提取码不对", "BAIDU · errtype 5")
            t == "0" || t.startsWith("mis_") || e in setOf(-9, 105, 115, 116, 2) -> gone(if (t.isNotEmpty()) "$e/$t" else e)
            e == -6 || t == "9019" -> loginErr(e)
            t == "8001" || t == "9013" -> PanError(PanError.Kind.LIMIT, "你的百度账号被限制了，换个号登录", "BAIDU · errtype $t")
            else -> other(if (t.isNotEmpty()) "$e/$t" else e)
        }
    }

    private fun codeErr(s: PanShare, e: Any) = PanError(PanError.Kind.CODE,
        if (s.code.isEmpty()) "这个分享要提取码，问分享的人要" else "提取码不对，问问分享的人", "BAIDU · errno $e")
    private fun gone(e: Any) = PanError(PanError.Kind.GONE, "分享没了，可能被删或过期了", "BAIDU · errno $e")
    private fun loginErr(e: Any) = PanError(PanError.Kind.LOGIN, "先在内置浏览器里登录百度网盘", "BAIDU · errno $e")
    private fun other(e: Any) = PanError(PanError.Kind.OTHER, "百度那边出错了，再试一次", "BAIDU · errno $e")
}
