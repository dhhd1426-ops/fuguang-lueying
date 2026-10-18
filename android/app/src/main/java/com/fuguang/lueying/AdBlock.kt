package com.fuguang.lueying

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * 内置浏览器去广告（照 uBlock Origin / AdGuard 的做法，分三层）：
 * 1. 请求层：广告、跟踪域名的请求直接不发（shouldInterceptRequest 回一个空响应）。名单 = 自带的常见几百个 + 每周从 anti-AD（专门对付国内网站的公开规则）更新一次。
 * 2. 元素层：页面加载完塞一段 CSS，把常见的广告位、「打开 App」横幅藏起来（COSMETIC）。
 * 3. 跳转层：intent://、market://、各家 App 的私有地址（bilibili://、snssdk:// …）一律不跳。
 * 某个网站拦坏了可以单独放过（allow）。
 */
object AdBlock {
    private const val LIST_URL = "https://anti-ad.net/domains.txt"
    private const val WEEK = 7L * 24 * 3600 * 1000

    /** 自带的：最常见的广告和跟踪域名（国内外都有），名单没下载下来时也能拦住大部分。 */
    private val BASE = """
        doubleclick.net googlesyndication.com googleadservices.com google-analytics.com googletagmanager.com googletagservices.com adservice.google.com
        adnxs.com adsrvr.org criteo.com criteo.net taboola.com outbrain.com pubmatic.com rubiconproject.com openx.net amazon-adsystem.com
        scorecardresearch.com quantserve.com moatads.com adform.net casalemedia.com smartadserver.com yieldmo.com media.net zedo.com
        facebook.net connect.facebook.net ads.twitter.com analytics.twitter.com ads.linkedin.com bat.bing.com clarity.ms hotjar.com
        mixpanel.com segment.io segment.com branch.io app-measurement.com appsflyer.com adjust.com kochava.com
        cpro.baidu.com pos.baidu.com hm.baidu.com eclick.baidu.com cbjs.baidu.com nsclick.baidu.com mobads.baidu.com baidustatic.com
        tanx.com alimama.com mmstat.com atanx.alicdn.com cnzz.com umeng.com umengcloud.com
        gdt.qq.com e.qq.com l.qq.com pingjs.qq.com ta.qq.com mi.gdt.qq.com
        pangolin-sdk-toutiao.com pangle.io snssdk.com ad.toutiao.com log.snssdk.com mcs.snssdk.com
        cm.bilibili.com data.bilibili.com
        admaster.com.cn miaozhen.com irs01.com gridsumdissector.com 51.la cnzz.mmstat.com
        ad.youku.com atm.youku.com lstat.youku.com
        sax.sina.com.cn beacon.sina.com.cn adbox.sina.com.cn
        ads.360.cn lianmeng.360.cn
        ad.xiaomi.com ad.mi.com
        adsame.com mediav.com ipinyou.com allyes.com
        popads.net popcash.net propellerads.com exoclick.com juicyads.com trafficjunky.net adsterra.com
    """.split(Regex("\\s+")).filter { it.isNotBlank() }.toHashSet()

    /** 地址里带这些的请求也拦（第一方自己放广告的常见写法）。 */
    private val PATHS = listOf("/pagead/", "/adsbygoogle", "/ads.js", "/ad_banner", "/adserver", "/advert", "/getad?", "/ad?", "/ads?", "/adx/", "/union/ad")

    /** 元素层：常见的广告位和「打开 App」横幅。 */
    const val COSMETIC = """[id^="ad-"],[id^="ad_"],[class^="ad-"],[class*=" ad-"],[class*="-ad-"],[class*="_ad_"],[class^="ads"],[id^="ads"],
.adsbygoogle,[data-ad],[data-ad-slot],ins.adsbygoogle,iframe[src*="ads"],iframe[id*="google_ads"],
[class*="advert"],[id*="advert"],[class*="sponsor"],[class*="banner-ad"],[class*="ad-banner"],[class*="adbox"],[class*="ad_box"],
[class*="open-app"],[class*="openapp"],[class*="open_app"],[class*="launch-app"],[class*="app-download"],[class*="download-app"],[class*="downloadapp"],
[class*="call-app"],[class*="callapp"],[class*="m-navbar-app"],[class*="openInApp"],[class*="fixed-download"],[class*="float-ad"],[class*="floatad"]
{display:none!important;visibility:hidden!important;height:0!important;min-height:0!important}"""

    private val schemes = setOf("http", "https", "about", "data", "blob", "javascript")
    private var big: Set<String> = emptySet()
    private lateinit var file: File
    private var prefs: Prefs? = null
    /** 放过的网站。 */
    private val allowed = ConcurrentHashMap.newKeySet<String>()

    fun init(ctx: Context, p: Prefs) {
        prefs = p
        file = File(ctx.filesDir, "adblock.txt")
        p.string("adblock-allow")?.split(',')?.filter { it.isNotBlank() }?.let { allowed.addAll(it) }
        Thread { runCatching { if (file.exists()) big = read(file.readText()) } }.start()
    }

    private fun read(t: String): Set<String> = t.lineSequence().map { it.trim().lowercase() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") }
        .map { it.removePrefix("0.0.0.0 ").removePrefix("127.0.0.1 ").removePrefix("||").removeSuffix("^") }
        .filter { '.' in it && ' ' !in it }.toHashSet()

    /** 每周更新一次名单；下不下来就用旧的。 */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        if (!::file.isInitialized) return@withContext
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < WEEK) return@withContext
        runCatching {
            val c = (URL(LIST_URL).openConnection() as HttpURLConnection).apply { connectTimeout = 10_000; readTimeout = 30_000 }
            try {
                if (c.responseCode != 200) return@runCatching
                val t = c.inputStream.bufferedReader().use { it.readText() }
                val s = read(t)
                if (s.size > 1000) { file.writeText(t); big = s }
            } finally { c.disconnect() }
        }
    }

    fun on(host: String) = host.isNotEmpty() && hostKey(host) !in allowed
    fun allow(host: String, yes: Boolean) {
        val k = hostKey(host); if (yes) allowed.remove(k) else allowed.add(k)
        prefs?.putString("adblock-allow", allowed.joinToString(","))
    }

    /** 主域名：www.bilibili.com → bilibili.com。 */
    fun hostKey(h: String): String { val p = h.lowercase().removePrefix("www.").split('.'); return if (p.size <= 2) p.joinToString(".") else p.takeLast(if (p[p.size - 2].length <= 3 && p.size > 2) 3 else 2).joinToString(".") }

    /** 这个请求要不要拦。pageHost 是正在看的网站：它自己放过了就都不拦。 */
    fun blocks(url: String, pageHost: String): Boolean {
        if (!on(pageHost)) return false
        val u = url.lowercase()
        val host = u.substringAfter("://").substringBefore('/').substringBefore(':').substringBefore('?')
        if (host.isEmpty()) return false
        var h = host
        while (true) {
            if (h in BASE || h in big) return true
            val i = h.indexOf('.'); if (i < 0 || h.indexOf('.', i + 1) < 0) break
            h = h.substring(i + 1)
        }
        val path = u.substringAfter(host, "")
        return PATHS.any { it in path }
    }

    /** 跳到别的 App 的地址：不跳。 */
    fun isAppJump(scheme: String?) = scheme != null && scheme.lowercase() !in schemes && scheme.lowercase() !in setOf("tel", "mailto", "sms")
}
