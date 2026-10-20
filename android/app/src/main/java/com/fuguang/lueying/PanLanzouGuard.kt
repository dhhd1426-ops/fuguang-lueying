package com.fuguang.lueying

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 蓝奏的反爬和签名，PanLanzou 专用。
 * - acw_sc__v2：阿里云 ESA 先回一页带 `var arg1='…'` 的脚本，算出 cookie 再原样请求一次。
 *   算法照 OpenList drivers/lanzou/help.go 的 CalcAcwScV2（unbox + hexXor），和 nfd AcwScV2Generator 结果一样。
 * - 下载 ajax 的表单：照 nfd LzTool.extractAjaxFromHtml，三种页面写法（wp_sign / isngis / 'sign'）。
 * - 蓝奏优享（ilanzou）的 timestamp / downloadId / auth：AES/ECB/PKCS5 加密后转小写 hex，
 *   钥匙是 nfd AESUtils.CIPHER_AES2_IZ 解出来的「lanZouY-disk-app」。
 */
internal object PanLanzouGuard {
    private val ARG1 = Regex("""arg1\s*=\s*'([0-9A-Fa-f]+)'""")
    private const val MASK = "3000176000856006061501533003690027800375"
    private val BOX = intArrayOf(6, 28, 34, 31, 33, 18, 30, 23, 9, 8, 19, 38, 17, 24, 0, 5, 32, 21, 10, 22, 25, 14, 15, 3, 16, 27, 13, 35, 2, 29, 11, 26, 4, 36, 1, 39, 37, 7, 20, 12)

    /** 页面是不是反爬挑战页。 */
    fun challenged(html: String) = "arg1='" in html || "arg1 ='" in html

    /** 从挑战页算出 acw_sc__v2；没认出来返回 null。 */
    fun acw(html: String): String? {
        val arg1 = ARG1.find(html)?.groupValues?.get(1)?.uppercase() ?: return null
        // 按 BOX 重排：arg1 第 i 位放到 BOX[i] 位置
        val box = CharArray(arg1.length)
        for (i in BOX.indices) if (i < arg1.length && BOX[i] < box.size) box[BOX[i]] = arg1[i]
        val un = String(box)
        // 两段 hex 逐字节异或，取短的那段长度
        val n = minOf(un.length, MASK.length) / 2
        val sb = StringBuilder()
        for (i in 0 until n) {
            val a = un.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null
            val b = MASK.substring(i * 2, i * 2 + 2).toInt(16)
            sb.append("%02x".format(a xor b))
        }
        return sb.toString()
    }

    // ———— 下载 ajax ————

    /** 页面里的下载接口：url 是绝对地址（apifile.lanzouw.com/ajaxm.php?file=…）或相对路径；form 是要 POST 的表单。 */
    class Ajax(val url: String, val form: List<Pair<String, String>>)

    private val ABS = Regex("""['"]((?:https?:)?//[^'"\s<>]+/ajax(?:m|file)\.php\?file=\d+)['"]""", RegexOption.IGNORE_CASE)
    private val REL = Regex("""(?:['"/]|^)(ajax(?:m|file)\.php\?file=\d+)""")
    private val WP_SIGN = Regex("""wp_sign\s*=\s*'([^']+)'""")
    private val AJAXDATA = Regex("""ajaxdata\s*=\s*'([^']+)'""")
    private val WEBSIGN = Regex("""'websign'\s*:\s*'([^']*)'""")
    private val ISNGIS = Regex("""var\s+isngis\s*=\s*'([^']+)'""")
    private val SIGN = Regex("""'sign'\s*:\s*'([^']+)'""")

    /** 从文件页 / iframe 页 / 密码页里拼出下载 ajax；pwd 不空就带上 p。认不出返回 null。 */
    fun ajax(html: String, origin: String, pwd: String?): Ajax? {
        val html2 = stripComments(html)
        val target = ABS.find(html2)?.groupValues?.get(1)?.let { if (it.startsWith("//")) "https:$it" else it }
            ?: REL.find(html2)?.groupValues?.get(1)?.let { "$origin/$it" }
            ?: return null
        val f = ArrayList<Pair<String, String>>()
        f += "action" to "downprocess"
        val wp = WP_SIGN.find(html2)?.groupValues?.get(1)
        val ad = AJAXDATA.find(html2)?.groupValues?.get(1)
        val isngis = ISNGIS.findAll(html2).map { it.groupValues[1] }.lastOrNull { it.isNotEmpty() }
        when {
            wp != null -> {
                f += "sign" to wp
                if (ad != null) { f += "websignkey" to ad; f += "signs" to ad }
                f += "websign" to (WEBSIGN.find(html2)?.groupValues?.get(1) ?: "")
                f += "kd" to "1"; f += "ves" to "1"
            }
            isngis != null -> { f += "sign" to isngis; f += "kd" to "1" }
            else -> {
                val signs = SIGN.findAll(html2).map { it.groupValues[1] }.toList()
                if (signs.isEmpty()) return null
                f += "sign" to (if (signs.size > 1) signs[1] else signs[0])
                f += "kd" to "1"
                if (ad != null) { f += "websignkey" to ad; f += "signs" to ad }
            }
        }
        if (!pwd.isNullOrEmpty()) f += "p" to pwd
        return Ajax(target, f)
    }

    /** 去掉 HTML 注释和 JS 行注释（页面里常有注释掉的旧 sign，会认错）。 */
    fun stripComments(html: String): String =
        html.replace(Regex("<!--[\\s\\S]*?-->"), "\n").replace(Regex("""(?m)(^|[^:'"\\])//[^\n]*"""), "$1").replace(Regex("""/\*[\s\S]*?\*/"""), "")

    // ———— 文件夹列表的表单 ————

    private val FILEMORE = Regex("""url\s*:\s*'(/filemoreajax\.php\?file=\d+)'[\s\S]*?data\s*:\s*\{([^}]+)\}""")
    private val KV = Regex("""'(\w+)'\s*:\s*('(?:\\'|[^'])*'|\d+|\w+)""")

    /** 文件夹页里 filemoreajax.php 的地址和表单（照 nfd LzTool.extractFolderAjax）。pg 由调用方每页改。 */
    fun folderAjax(html: String, origin: String, pwd: String): Ajax? {
        val html2 = stripComments(html)
        val m = FILEMORE.find(html2) ?: return null
        val f = ArrayList<Pair<String, String>>()
        for (kv in KV.findAll(m.groupValues[2])) {
            val (k, raw) = kv.destructured
            val v = when {
                k == "pwd" -> pwd
                k == "pg" -> "1"
                raw.length >= 2 && raw.startsWith("'") && raw.endsWith("'") -> raw.substring(1, raw.length - 1)
                raw.all { it.isDigit() } -> raw
                // 值是 JS 变量名：找 var 名 = '…'，取最后一个非空的
                else -> Regex("""var\s+${Regex.escape(raw)}\s*=\s*'([^']*)'""").findAll(html2).map { it.groupValues[1] }.lastOrNull { it.isNotEmpty() } ?: "1"
            }
            f += k to v
        }
        if (f.none { it.first == "pwd" } && pwd.isNotEmpty()) f += "pwd" to pwd
        if (f.none { it.first == "fid" } || f.none { it.first == "t" } || f.none { it.first == "k" }) return null
        return Ajax(origin + m.groupValues[1], f)
    }

    // ———— 蓝奏优享 ————

    private val IZ_KEY = SecretKeySpec("lanZouY-disk-app".toByteArray(), "AES")

    /** ilanzou 参数加密：AES/ECB/PKCS5 → 小写 hex。 */
    fun iz(s: String): String {
        val c = Cipher.getInstance("AES/ECB/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, IZ_KEY)
        return c.doFinal(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** 「12.3 M」「512 K」「1.2 G」「800 B」→ 字节；认不出是 -1。 */
    fun size(s: String?): Long {
        val m = Regex("""([0-9.]+)\s*([BKMGT])""", RegexOption.IGNORE_CASE).find(s ?: return -1) ?: return -1
        val n = m.groupValues[1].toDoubleOrNull() ?: return -1
        val mul = when (m.groupValues[2].uppercase()) { "K" -> 1L shl 10; "M" -> 1L shl 20; "G" -> 1L shl 30; "T" -> 1L shl 40; else -> 1L }
        return (n * mul).toLong()
    }
}
