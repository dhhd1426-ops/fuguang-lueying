package com.fuguang.lueying

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import kotlin.coroutines.resume

/** 网页上的一件素材：原样从页面里拿出来的。 */
data class RawAsset(val url: String, val tag: String, val alt: String, val w: Int, val h: Int, val near: String)

/** 打开一页拿到的东西：标题、正文、素材。 */
data class Grabbed(val url: String, val title: String, val text: String, val raw: List<RawAsset>)

/**
 * 打开一页，把正文和素材拿出来。
 * 先用一个看不见的 WebView 打开（和内置浏览器同一份登录，靠脚本出内容的网站也能拿到），往下滚几屏让懒加载的图出来；
 * 打不开再退回直接读网页源码。内置浏览器里「让她看看这页」时，直接用那页的快照（`remember`）。
 */
object PageGrab {
    private var app: Context? = null
    private val snaps = HashMap<String, Grabbed>()

    fun init(ctx: Context) { app = ctx.applicationContext }

    /** 内置浏览器里正看着的那页：读一份快照留着，她马上要用。 */
    fun remember(web: WebView, done: () -> Unit) {
        web.evaluateJavascript(SNAP) { r ->
            parse(web.url.orEmpty(), r)?.let { g -> synchronized(snaps) { snaps[key(g.url)] = g } }
            done()
        }
    }

    suspend fun grab(url: String): Grabbed {
        synchronized(snaps) { snaps[key(url)] }?.let { return it }
        val live = runCatching { headless(url) }.getOrNull()
        if (live != null && (live.raw.isNotEmpty() || live.text.length > 200)) return live
        return withContext(Dispatchers.IO) { source(url) }
    }

    private fun key(u: String) = u.substringBefore('#').trimEnd('/')

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun headless(url: String): Grabbed? = withContext(Dispatchers.Main) {
        val ctx = app ?: return@withContext null
        val wv = WebView(ctx)
        try {
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.loadsImagesAutomatically = true
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
            // 没挂到界面上也要有尺寸：懒加载和图片的实际大小都靠它
            wv.measure(android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(2340, android.view.View.MeasureSpec.EXACTLY))
            wv.layout(0, 0, 1080, 2340)
            withTimeoutOrNull(14_000) {
                suspendCancellableCoroutine { k ->
                    var once = false
                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, u: String?) { if (!once) { once = true; k.resume(Unit) } }
                        override fun shouldOverrideUrlLoading(view: WebView?, req: android.webkit.WebResourceRequest?): Boolean =
                            req?.url?.scheme?.let { it != "http" && it != "https" } ?: false
                    }
                    wv.loadUrl(url)
                }
            }
            delay(900)
            // 往下滚几屏，懒加载的图才会换成真地址
            repeat(8) { wv.evaluateJavascript("window.scrollBy(0,innerHeight)", null); delay(260) }
            wv.evaluateJavascript("window.scrollTo(0,0)", null); delay(300)
            val r = suspendCancellableCoroutine { k -> wv.evaluateJavascript(SNAP) { k.resume(it) } }
            parse(wv.url ?: url, r)
        } finally {
            Handler(Looper.getMainLooper()).post { runCatching { wv.stopLoading(); wv.destroy() } }
        }
    }

    private fun parse(url: String, r: String?): Grabbed? = runCatching {
        val s = JSONTokener(r ?: return null).nextValue() as? String ?: return null
        val o = JSONObject(s)
        val a = o.optJSONArray("raw")
        val raw = (0 until (a?.length() ?: 0)).mapNotNull { i ->
            a!!.optJSONObject(i)?.let { x -> RawAsset(x.optString("u"), x.optString("tag"), x.optString("alt").trim(), x.optInt("w"), x.optInt("h"), x.optString("near").trim()) }
        }
        Grabbed(url, o.optString("title").trim(), o.optString("text").trim(), raw)
    }.getOrNull()

    /** 退路：直接读网页源码（没有脚本、没有实际尺寸）。 */
    private fun source(url: String): Grabbed {
        val d = Jsoup.connect(url).userAgent(Materials.UA).header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
            .timeout(15_000).maxBodySize(6 shl 20).followRedirects(true).get()
        val raw = ArrayList<RawAsset>()
        fun near(e: org.jsoup.nodes.Element): String = (e.closest("figure")?.selectFirst("figcaption")?.text() ?: e.parent()?.text().orEmpty()).replace(Regex("\\s+"), " ").take(60)
        d.select("meta[property=og:image], meta[name=twitter:image]").forEach { raw += RawAsset(it.absUrl("content"), "og", "", 0, 0, "") }
        d.select("link[rel~=icon], link[rel=apple-touch-icon]").forEach { raw += RawAsset(it.absUrl("href"), "icon", "", 0, 0, "") }
        d.select("img").forEach { e ->
            val lazy = listOf("data-src", "data-original", "data-lazy-src", "data-actualsrc").firstNotNullOfOrNull { k -> e.absUrl(k).takeIf { it.isNotEmpty() } }
            val src = e.absUrl("src")
            val u = if (lazy != null && (src.isEmpty() || src.startsWith("data:"))) lazy else src
            raw += RawAsset(u, "img", e.attr("alt"), e.attr("width").toIntOrNull() ?: 0, e.attr("height").toIntOrNull() ?: 0, near(e))
        }
        d.select("video").forEach { v ->
            v.absUrl("poster").takeIf { it.isNotEmpty() }?.let { raw += RawAsset(it, "poster", "", 0, 0, near(v)) }
            (v.absUrl("src").ifEmpty { v.selectFirst("source")?.absUrl("src").orEmpty() }).takeIf { it.isNotEmpty() }?.let { raw += RawAsset(it, "video", "", 0, 0, near(v)) }
        }
        d.select("[style*=background]").forEach { e ->
            Regex("url\\(['\"]?([^'\")]+)").find(e.attr("style"))?.let { m -> runCatching { java.net.URL(java.net.URL(url), m.groupValues[1]).toString() }.getOrNull()?.let { raw += RawAsset(it, "bg", "", 0, 0, "") } }
        }
        d.select("script, style, nav, footer, header, noscript").remove()
        val body = d.selectFirst("article, main, [role=main], #js_content") ?: d.body()
        return Grabbed(url, d.title(), body.wholeText().replace(Regex("\n{3,}"), "\n\n").trim().take(8000), raw.distinctBy { it.url })
    }

    /** 在页面里跑：正文 + 素材（图、视频和封面、背景图、站点图标、内联的 SVG 图标）。 */
    private const val SNAP = """(function(){try{
var out=[],seen={},svgs=0;function abs(u){try{return new URL(u,location.href).href}catch(e){return''}}
function near(e){if(!e||!e.closest)return'';var f=e.closest('figure'),c=f&&f.querySelector('figcaption');if(c)return c.innerText.trim().slice(0,60);
var x=e.parentElement;for(var i=0;i<3&&x;i++,x=x.parentElement){var t=(x.innerText||'').trim();if(t)return t.replace(/\s+/g,' ').slice(0,60)}return''}
function add(u,tag,e,w,h){if(!u)return;if(u.indexOf('data:')!==0)u=abs(u);if(!u||seen[u])return;if(!/^https?:/.test(u)&&u.indexOf('data:image/svg')!==0)return;seen[u]=1;
out.push({u:u,tag:tag,alt:e&&(e.alt||e.title||(e.getAttribute&&e.getAttribute('aria-label')))||'',w:Math.round(w||0),h:Math.round(h||0),near:near(e)})}
document.querySelectorAll('meta[property="og:image"],meta[name="twitter:image"]').forEach(function(m){add(m.content,'og',null)});
document.querySelectorAll('link[rel~="icon"],link[rel="apple-touch-icon"]').forEach(function(l){add(l.href,'icon',null)});
document.querySelectorAll('img').forEach(function(i){var u=i.currentSrc||i.src;var d=i.getAttribute('data-src')||i.getAttribute('data-original')||i.getAttribute('data-lazy-src')||i.getAttribute('data-actualsrc');
if(d&&(!u||u.indexOf('data:')===0))u=d;add(u,'img',i,i.naturalWidth||i.width,i.naturalHeight||i.height)});
document.querySelectorAll('video').forEach(function(v){if(v.poster)add(v.poster,'poster',v,v.videoWidth||v.clientWidth,v.videoHeight||v.clientHeight);
var s=v.currentSrc||v.src||((v.querySelector('source')||{}).src);if(s&&s.indexOf('blob:')!==0)add(s,'video',v,v.videoWidth,v.videoHeight)});
document.querySelectorAll('svg').forEach(function(s){if(svgs>=12)return;var r=s.getBoundingClientRect();if(r.width<20||r.height<20||r.width>640)return;var x=s.outerHTML;if(x.length>12000)return;
if(x.indexOf('xmlns=')<0)x=x.replace('<svg','<svg xmlns="http://www.w3.org/2000/svg"');svgs++;add('data:image/svg+xml;base64,'+btoa(unescape(encodeURIComponent(x))),'svg',s,r.width,r.height)});
var all=document.querySelectorAll('body *');for(var i=0;i<all.length&&i<4000;i++){var bg=getComputedStyle(all[i]).backgroundImage;
if(bg&&bg.indexOf('url(')>=0){var m=bg.match(/url\(["']?([^"')]+)/),r=all[i].getBoundingClientRect();if(m)add(m[1],'bg',all[i],r.width,r.height)}}
var t=(document.querySelector('article,main,[role=main],#js_content')||document.body).innerText||'';
return JSON.stringify({title:document.title,text:t.replace(/\n{3,}/g,'\n\n').slice(0,8000),raw:out.slice(0,220)});
}catch(x){return JSON.stringify({title:document.title,text:'',raw:[]})}})()"""
}
