package com.fuguang.lueying

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 网盘分享直接下：认链接、各家共用的数据和网络小工具。
 * 各家的接口在 Pan<家>.kt，都实现 [PanApi]；状态和进度在 [PanStore]；界面在 PanPane / PanPill / PanCard。
 *
 * 三种「怎么下」：
 * - 直接下：分享页给得出下载地址（蓝奏、123、移动、百度、115）。
 * - 借道（[Disk.borrow]）：官方不给分享页直接下载（夸克、UC、阿里）。先存进他网盘里的「浮光临时」，
 *   拿到下载地址就删掉那份；地址删了以后照样能下完（各家都是先签好的地址）。
 * 登录都靠内置浏览器：他在那里登一次，这里读 WebView 的 Cookie（阿里读 localStorage，见 PanAli）。
 */
enum class Disk(
    val label: String, val glyph: String, val color: Long,
    /** 登录页（内置浏览器打开它）；null 是这家不用登录。 */
    val loginUrl: String?,
    /** 读哪个域名的 Cookie。 */
    val cookieHost: String,
    /** 0 不用登录 · 1 大文件才要 · 2 要。 */
    val login: Int,
    val borrow: Boolean = false,
) {
    BAIDU("百度网盘", "百", 0xFF9FB8EC, "https://pan.baidu.com/", "https://pan.baidu.com", 2),
    P115("115", "115", 0xFFE9C28E, "https://115.com/", "https://115.com", 2),
    QUARK("夸克网盘", "夸", 0xFFB3A4EC, "https://pan.quark.cn/", "https://pan.quark.cn", 2, borrow = true),
    UC("UC 网盘", "UC", 0xFFF0B07A, "https://drive.uc.cn/", "https://drive.uc.cn", 2, borrow = true),
    ALI("阿里云盘", "阿", 0xFFF08A7E, "https://www.alipan.com/drive", "https://www.alipan.com", 2, borrow = true),
    P123("123 云盘", "123", 0xFF8FB5E8, "https://www.123pan.com/", "https://www.123pan.com", 1),
    LANZOU("蓝奏云", "蓝", 0xFF8FCFD0, null, "https://www.lanzoui.com", 0),
    P139("移动云盘", "移", 0xFF9CC59A, null, "https://yun.139.com", 0);

    val api: PanApi get() = when (this) {
        BAIDU -> PanBaidu
        P115 -> Pan115
        QUARK, UC -> PanQuark
        ALI -> PanAli
        P123 -> Pan123
        LANZOU -> PanLanzou
        P139 -> Pan139
    }
}

/** 一个分享。id 是链接里那段分享码；code 是提取码（文字里带着就自动带上）。state 给各家存这次会话的令牌（夸克 stoken、阿里 share_token……）。 */
class PanShare(val link: String, val disk: Disk, val id: String, var code: String) {
    val state = HashMap<String, String>()
}

/**
 * 一个文件或文件夹。id 是这家认的文件编号；extra 给各家放下载要用的其他字段（夸克 fid_token、百度 fs_id……）。
 * 路径 path 是从分享根开始的「第一章/01.mp4」，下文件夹时用来在相册 / 下载里分清。
 */
class PanFile(
    val id: String, val name: String, val size: Long, val dir: Boolean,
    val path: String = name, val extra: Map<String, String> = emptyMap(),
)

/** 打开一个分享的结果：标题（分享名 / 第一个文件名）+ 根目录里的东西。 */
class PanRoot(val title: String, val files: List<PanFile>, val owner: String = "")

/** 一个文件真正的下载地址 + 下载时必须带的请求头（UA、Referer、Cookie）。 */
class Direct(val url: String, val headers: Map<String, String> = emptyMap(), val size: Long = -1, val alts: List<String> = emptyList())

/**
 * 出错按原因分开（第 212 版的规矩：说原因 + 一件能做的事 + 灰色原因码）。
 * kind：GONE 分享没了 · CODE 提取码不对或缺 · LOGIN 要登录 · LIMIT 这家限制（会员、流量、大小）· NET 网络 · OTHER。
 */
class PanError(val kind: Kind, val why: String, val code: String) : Exception(why) {
    enum class Kind { GONE, CODE, LOGIN, LIMIT, NET, OTHER }
}

interface PanApi {
    /** 打开分享：验提取码、拿根目录。 */
    suspend fun open(s: PanShare): PanRoot
    /** 列一个文件夹里的东西。 */
    suspend fun list(s: PanShare, dir: PanFile): List<PanFile>
    /** 一个文件的下载地址。借道的几家在这里存进「浮光临时」、拿地址、删掉。 */
    suspend fun direct(s: PanShare, f: PanFile): Direct
    /** 一批下完（或放弃）后收拾：借道的几家把「浮光临时」里剩下的删干净。默认什么都不做。 */
    suspend fun cleanup(s: PanShare) {}
}

object Pan {
    const val PC_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
    const val PHONE_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36"

    private val URL_RE = Regex("""https?://[^\s，。、"'<>（）()【】]+""")
    private val CODE_RE = Regex("""(?:提取码|提取碼|访问码|訪問碼|密码|密碼|pwd|code)\s*[:：=]?\s*([A-Za-z0-9]{2,8})""", RegexOption.IGNORE_CASE)

    /** 文字里有网盘分享链接就认出来；没有返回 null。 */
    fun link(text: String): PanShare? {
        for (m in URL_RE.findAll(text)) {
            val u = m.value.trimEnd('.', '/', '，', '。', ')', '）')
            val (disk, id) = match(u) ?: continue
            val inUrl = Regex("""[?&#](?:pwd|password|passcode)=([A-Za-z0-9]+)""").find(u)?.groupValues?.get(1)
            val near = CODE_RE.find(text.substring(m.range.first))?.groupValues?.get(1)
            return PanShare(u, disk, id, inUrl ?: near ?: "")
        }
        return null
    }

    private fun match(u: String): Pair<Disk, String>? {
        fun after(re: String) = Regex(re).find(u)?.groupValues?.get(1)
        return when {
            "pan.baidu.com" in u -> (after("""/s/1?([\w-]+)""") ?: after("""surl=([\w-]+)"""))?.let { Disk.BAIDU to it }
            Regex("""(115|115cdn|anxia)\.com/s/""").containsMatchIn(u) -> after("""/s/(\w+)""")?.let { Disk.P115 to it }
            "pan.quark.cn/s/" in u -> after("""/s/(\w+)""")?.let { Disk.QUARK to it }
            "drive.uc.cn/s/" in u || "fast.uc.cn/s/" in u -> after("""/s/(\w+)""")?.let { Disk.UC to it }
            Regex("""(alipan|aliyundrive)\.com/s/""").containsMatchIn(u) -> after("""/s/(\w+)""")?.let { Disk.ALI to it }
            Regex("""(123pan|123865|123684|123912|123592|123\d{3})\.(com|cn)/s/""").containsMatchIn(u) -> after("""/s/([\w-]+)""")?.let { Disk.P123 to it }
            Regex("""lanzou\w?\.com/|lanzn\.com/|ilanzou\.com/""").containsMatchIn(u) -> after("""\.com/(?:tp/)?([\w]+)""")?.let { Disk.LANZOU to it }
            "caiyun.139.com" in u || "yun.139.com" in u -> (after("""/(?:m/i|w/i|i)\?(\w+)""") ?: after("""linkID=(\w+)"""))?.let { Disk.P139 to it }
            else -> null
        }
    }

    // ———— 网络 ————

    /** 内置浏览器里这家的登录 Cookie（没登过是空）。 */
    fun cookie(d: Disk): String = runCatching { CookieManager.getInstance().getCookie(d.cookieHost) }.getOrNull().orEmpty()
    fun cookie(host: String): String = runCatching { CookieManager.getInstance().getCookie(host) }.getOrNull().orEmpty()

    class Resp(val code: Int, val body: String, val headers: Map<String, List<String>>, val url: String) {
        fun header(n: String) = headers.entries.firstOrNull { it.key.equals(n, true) }?.value?.firstOrNull()
        /** 服务器新发的 Cookie，拼成「a=1; b=2」。 */
        fun setCookies(): String = headers.entries.filter { it.key.equals("Set-Cookie", true) }.flatMap { it.value }.joinToString("; ") { it.substringBefore(';') }
    }

    /**
     * 发一次请求。body 不为 null 就是 POST（json=true 时 Content-Type 是 JSON，否则表单）。
     * follow=false 时不跟跳转（拿 302 的 Location 当下载地址的几家用）。
     */
    suspend fun http(
        url: String, headers: Map<String, String> = emptyMap(), body: String? = null,
        json: Boolean = false, follow: Boolean = true, timeout: Int = 15_000, method: String? = null,
    ): Resp = withContext(Dispatchers.IO) {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeout; readTimeout = timeout; instanceFollowRedirects = follow
            if (headers.keys.none { it.equals("User-Agent", true) }) setRequestProperty("User-Agent", PC_UA)
            headers.forEach { (k, v) -> if (v.isNotEmpty()) setRequestProperty(k, v) }
            if (method != null) requestMethod = method
            if (body != null) {
                if (method == null) requestMethod = "POST"
                doOutput = true
                if (headers.keys.none { it.equals("Content-Type", true) })
                    setRequestProperty("Content-Type", if (json) "application/json;charset=UTF-8" else "application/x-www-form-urlencoded;charset=UTF-8")
                outputStream.use { it.write(body.toByteArray()) }
            }
        }
        try {
            val code = c.responseCode
            val text = (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            Resp(code, text, c.headerFields.filterKeys { it != null }, c.url.toString())
        } catch (e: java.io.IOException) {
            throw PanError(PanError.Kind.NET, "连不上，看看网络", "NET · ${e.javaClass.simpleName}")
        } finally { c.disconnect() }
    }

    fun form(vararg kv: Pair<String, Any?>): String =
        kv.filter { it.second != null }.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v.toString(), "UTF-8") }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun mime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"; "mkv" -> "video/x-matroska"; "mov" -> "video/quicktime"; "avi" -> "video/x-msvideo"; "flv" -> "video/x-flv"; "ts" -> "video/mp2t"; "webm" -> "video/webm"
        "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; "flac" -> "audio/flac"; "wav" -> "audio/wav"; "aac" -> "audio/aac"; "ogg" -> "audio/ogg"
        "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"; "heic" -> "image/heic"
        "pdf" -> "application/pdf"; "zip" -> "application/zip"; "rar" -> "application/vnd.rar"; "7z" -> "application/x-7z-compressed"
        "apk" -> "application/vnd.android.package-archive"; "txt" -> "text/plain"; "epub" -> "application/epub+zip"
        else -> "application/octet-stream"
    }

    /** 视频、声音、图片进相册；其他进 下载/浮光掠影。 */
    fun media(name: String) = mime(name).let { it.startsWith("video/") || it.startsWith("audio/") || it.startsWith("image/") }

    fun size(b: Long): String = when {
        b < 0 -> ""
        b >= 1_000_000_000 -> "%.2f GB".format(b / 1e9)
        b >= 100_000_000 -> "%.0f MB".format(b / 1e6)
        b >= 1_000_000 -> "%.1f MB".format(b / 1e6)
        else -> "${maxOf(1, b / 1000)} KB"
    }
}
