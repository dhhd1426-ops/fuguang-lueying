package com.fuguang.lueying

import com.fuguang.lueying.PanError.Kind.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 移动云盘（中国移动 139 / 和彩云）分享直接下。
 * 参考 OpenList drivers/139（util.go 的 sharePost / shareGetFilesWithRef / shareGetLinkWithRef / yun139EncryptedRequest，
 * types.go 的 ShareListResp / ShareContentInfoResp / ShareDownloadResp）。nfd EcTool 是「移动云空间」ecpan.cn，另一家，没用上。
 *
 * 接口都在 share-kd-njs.yun.139.com/yun-share/richlifeApp/devapp/IOutLink/ 下，请求和回复都加密：
 * 请求体键按字母排好序的 JSON → AES-128-CBC（钥匙 PVGDwmcvfs1uV3d1，随机 IV）→ base64(IV + 密文)；回复同样解。
 * - 列表：getOutLinkInfoV6 {linkID, passwd, pCaID}，pCaID=root 是根；caLst 是文件夹，coLst 是文件。
 * - 下载：getContentInfoFromOutLink 先看有没有 cdnDownLoadUrl；没有再 dlFromOutLinkV3 拿 cdnDownloadUrl / redrUrl / downloadURL。
 * 登录不是必须的；内置浏览器里登过的话，yun.139.com 的 authorization Cookie 会带上（有些分享要账号才给原文件）。
 */
object Pan139 : PanApi {
    private const val API = "https://share-kd-njs.yun.139.com/yun-share/richlifeApp/devapp/IOutLink"
    private val KEY = SecretKeySpec("PVGDwmcvfs1uV3d1".toByteArray(), "AES")
    private val rnd = SecureRandom()

    override suspend fun open(s: PanShare): PanRoot {
        val d = call(s, "getOutLinkInfoV6", mapOf("getOutLinkInfoReq" to req(s, "pCaID" to "root")))
        val files = parse(d, "")
        return PanRoot(d.optString("lkName").ifEmpty { files.firstOrNull()?.name ?: "移动云盘分享" }, files)
    }

    override suspend fun list(s: PanShare, dir: PanFile): List<PanFile> =
        parse(call(s, "getOutLinkInfoV6", mapOf("getOutLinkInfoReq" to req(s, "pCaID" to dir.id))), dir.path)

    override suspend fun direct(s: PanShare, f: PanFile): Direct {
        val h = mapOf("User-Agent" to Pan.PC_UA, "Referer" to "https://yun.139.com/")
        // 列表里偶尔直接带着下载地址
        f.extra["dl"]?.takeIf { it.startsWith("http") }?.let { return Direct(it, h, f.size) }
        val info = runCatching {
            call(s, "getContentInfoFromOutLink", mapOf("getContentInfoFromOutLinkReq" to req(s, "contentId" to f.id)))
        }.getOrElse { if (it is PanError && it.kind != OTHER && it.kind != NET) throw it; null }
        info?.optJSONObject("contentInfo")?.optString("cdnDownLoadUrl")?.takeIf { it.startsWith("http") }?.let { return Direct(it, h, f.size) }

        // 没登录时这一步常被拒（OpenList 也要求账号）；拒了就说要登录
        val dl = runCatching { call(s, "dlFromOutLinkV3", mapOf("dlFromOutLinkReqV3" to req(s, "coIDLst" to mapOf("item" to listOf(f.id))))) }
            .getOrElse { e ->
                if (account().isEmpty() && (e !is PanError || e.kind == OTHER)) throw PanError(LOGIN, "这个分享要先在内置浏览器里登录移动云盘", "139 · 未登录")
                throw e
            }
        val url = dl.optJSONObject("extInfo")?.optString("cdnDownloadUrl").orEmpty()
            .ifEmpty { dl.optString("redrUrl") }.ifEmpty { dl.optString("downloadURL") }
        if (url.startsWith("http")) return Direct(url, h, f.size)
        if (account().isEmpty()) throw PanError(LOGIN, "这个分享要登录移动云盘才给原文件", "139 · 无下载地址（未登录）")
        throw PanError(OTHER, "移动云盘没给下载地址", "139 · 无下载地址")
    }

    // ———— 列表 ————

    private fun parse(d: JSONObject, base: String): List<PanFile> {
        fun at(n: String) = if (base.isEmpty()) n else "$base/$n"
        val out = ArrayList<PanFile>()
        d.optJSONArray("caLst")?.let { a ->
            for (i in 0 until a.length()) a.getJSONObject(i).let { out += PanFile(it.optString("caId"), it.optString("caName"), -1, true, at(it.optString("caName"))) }
        }
        d.optJSONArray("coLst")?.let { a ->
            for (i in 0 until a.length()) a.getJSONObject(i).let {
                val name = it.optString("coName")
                out += PanFile(it.optString("coId"), name, it.optLong("coSize", -1), false, at(name), mapOf("dl" to it.optString("downloadURL")))
            }
        }
        return out
    }

    // ———— 请求 ————

    private fun req(s: PanShare, vararg kv: Pair<String, Any>): Map<String, Any> =
        mapOf("account" to account(), "linkID" to s.id, "passwd" to s.code) + kv

    /** 内置浏览器 yun.139.com 的 authorization Cookie（Basic base64「pc:手机号:token|…」），没登过是空。 */
    private fun auth(): String {
        val raw = Regex("""(?:^|;\s*)authorization=([^;]+)""", RegexOption.IGNORE_CASE).find(Pan.cookie(Disk.P139))?.groupValues?.get(1) ?: return ""
        return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw).trim().removePrefix("Basic ").removePrefix("basic ").trim()
    }

    private fun account(): String = auth().takeIf { it.isNotEmpty() }?.let {
        runCatching { String(Base64.getDecoder().decode(it)).split(':').getOrNull(1) }.getOrNull()
    }.orEmpty()

    private fun headers(): Map<String, String> = mapOf(
        // 照 OpenList shareHeaders 原样（UA 和 X-Deviceinfo 要对得上）
        "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0", "Accept" to "application/json, text/plain, */*",
        "Content-Type" to "application/json;charset=UTF-8",
        "X-Deviceinfo" to "||9|12.27.0|firefox|140.0|||linux unknown|1920X526|zh-CN|||",
        "hcy-cool-flag" to "1", "CMS-DEVICE" to "default", "x-m4c-caller" to "PC", "X-Yun-Api-Version" to "v1",
        "Origin" to "https://yun.139.com", "Referer" to "https://yun.139.com/",
        "Authorization" to auth().let { if (it.isEmpty()) "" else "Basic $it" },
    )

    /** 加密发出去、解密回来；success 不是 true 就按原因抛错。返回 data。 */
    private suspend fun call(s: PanShare, path: String, body: Map<String, Any>): JSONObject {
        val r = Pan.http("$API/$path", headers(), seal(sorted(body)))
        if (r.code != 200) throw PanError(OTHER, "移动云盘接口出错了，过一会儿再试", "139 · HTTP ${r.code}")
        val text = r.body.trim().let { if (it.startsWith("{")) it else unseal(it) }
        val o = runCatching { JSONObject(text) }.getOrNull() ?: throw PanError(OTHER, "移动云盘回的东西解不开", "139 · 解密失败")
        if (o.optBoolean("success", false)) return o.optJSONObject("data") ?: JSONObject()
        throw fail(s, o.optString("code"), o.optString("message").ifEmpty { o.optString("desc") })
    }

    private fun fail(s: PanShare, code: String, msg: String): PanError {
        val c = "139 · code ${code.ifEmpty { "?" }}"
        fun has(vararg w: String) = w.any { it in msg }
        return when {
            has("提取码", "密码", "passwd") -> PanError(CODE, if (s.code.isEmpty()) "这个分享要提取码" else "提取码不对", c)
            has("不存在", "取消", "失效", "过期", "删除", "违规", "已撤销") -> PanError(GONE, "分享没了：$msg", c)
            has("登录", "token", "鉴权", "未授权") -> PanError(LOGIN, "要先登录移动云盘", c)
            has("流量", "上限", "频繁", "限制", "会员", "次数") -> PanError(LIMIT, "移动云盘限制了：$msg", c)
            else -> PanError(OTHER, msg.ifBlank { "移动云盘出错了" }, c)
        }
    }

    // ———— 加解密 ————

    private fun seal(plain: String): String {
        val iv = ByteArray(16).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.ENCRYPT_MODE, KEY, IvParameterSpec(iv)) }
        return Base64.getEncoder().encodeToString(iv + c.doFinal(plain.toByteArray()))
    }

    private fun unseal(b64: String): String = runCatching {
        val all = Base64.getDecoder().decode(b64)
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.DECRYPT_MODE, KEY, IvParameterSpec(all.copyOfRange(0, 16))) }
        String(c.doFinal(all.copyOfRange(16, all.size)))
    }.getOrDefault("")

    /** 键按字母排序的紧凑 JSON（服务器按这个格式认，照 OpenList sortedJsonStringify）。 */
    private fun sorted(v: Any?): String = when (v) {
        null -> "null"
        is Map<*, *> -> v.entries.sortedBy { it.key.toString() }.joinToString(",", "{", "}") { JSONObject.quote(it.key.toString()) + ":" + sorted(it.value) }
        is List<*> -> v.joinToString(",", "[", "]") { sorted(it) }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { sorted(v.opt(it)) }
        is Number, is Boolean -> v.toString()
        else -> JSONObject.quote(v.toString())
    }
}
