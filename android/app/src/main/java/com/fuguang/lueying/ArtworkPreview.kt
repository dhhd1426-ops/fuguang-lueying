package com.fuguang.lueying

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import android.util.Base64
import android.util.Xml
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONTokener
import org.xmlpull.v1.XmlPullParser
import kotlin.coroutines.resume

internal object ArtworkPreview {
    private val renderer = Mutex()
    private const val SVG_NS = "http://www.w3.org/2000/svg"
    private const val PNG = "data:image/png;base64,"

    /** Model output is loaded as an SVG image. Only the host page can run JavaScript. */
    private fun image(svg: String) =
        "data:image/svg+xml;base64," + Base64.encodeToString(svg.toByteArray(), Base64.NO_WRAP)

    fun page(svg: String) = """<!doctype html><html lang="zh"><head><meta name="viewport" content="width=device-width, initial-scale=1">
        <style>body{margin:0;display:grid;place-items:center;min-height:100vh;background:#121519}
        img{width:100vw;height:100vh;object-fit:contain;display:block}</style></head><body>
        <img src="${image(svg)}" alt="作品"></body></html>"""

    fun validate(svg: String): String? {
        if (svg.contains("<!DOCTYPE", ignoreCase = true)) return "SVG 不能包含文档类型声明。"
        return try {
            val xml = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                setInput(svg.reader())
            }
            var seen = false
            while (xml.eventType != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType == XmlPullParser.START_TAG && xml.depth == 1) {
                    if (seen || xml.name != "svg" || xml.namespace != SVG_NS ||
                        xml.getAttributeValue(null, "viewBox").isNullOrBlank()) {
                        return "原稿必须是带 xmlns 和 viewBox 的完整 SVG。"
                    }
                    seen = true
                }
                xml.nextToken()
            }
            if (seen) null else "没有找到 SVG 内容。"
        } catch (_: Exception) { "SVG 标记不完整或格式有误。" }
    }

    suspend fun reviewImage(png: ByteArray): ByteArray = withContext(Dispatchers.Default) {
        val original = checkNotNull(BitmapFactory.decodeByteArray(png, 0, png.size)) { "无法读取预览图" }
        val edge = maxOf(original.width, original.height)
        if (edge <= 900) {
            original.recycle()
            return@withContext png
        }
        try {
            val image = Bitmap.createScaledBitmap(original, original.width * 900 / edge, original.height * 900 / edge, true)
            try {
                ByteArrayOutputStream().use { out ->
                    check(image.compress(Bitmap.CompressFormat.PNG, 100, out))
                    out.toByteArray()
                }
            } finally { if (image !== original) image.recycle() }
        } finally { original.recycle() }
    }

    suspend fun render(context: Context, svg: String, width: Int, height: Int): ByteArray = renderer.withLock {
        val result = withTimeoutOrNull(12_000) {
            withContext(Dispatchers.Main) {
                val web = WebView(context)
                try {
                    val uri = image(svg)
                    web.settings.apply {
                        javaScriptEnabled = true
                        allowFileAccess = false
                        allowContentAccess = false
                        blockNetworkLoads = true
                    }
                    suspendCancellableCoroutine<String> { cont ->
                        web.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                if (!cont.isActive) return
                                view.evaluateJavascript(
                                    """(function(){try {
                                    const img = document.images[0];
                                    if (!img || !img.complete || !img.naturalWidth) return 'SVG 未能解码';
                                    const canvas = document.createElement('canvas');
                                    canvas.width = $width; canvas.height = $height;
                                    const ctx = canvas.getContext('2d', {willReadFrequently: true});
                                    ctx.drawImage(img, 0, 0, $width, $height);
                                    const pixels = ctx.getImageData(0, 0, $width, $height).data;
                                    let visible = false;
                                    for (let i = 3; i < pixels.length; i += 4) {
                                        if (pixels[i] !== 0) { visible = true; break; }
                                    }
                                    return visible ? canvas.toDataURL('image/png') : '画面完全透明';
                                    } catch (_) { return '无法读取画布像素'; }})()"""
                                ) { value ->
                                    if (cont.isActive) cont.resume(
                                        runCatching { JSONTokener(value.orEmpty()).nextValue() as? String }.getOrNull()
                                            ?: "未得到渲染结果"
                                    )
                                }
                            }
                        }
                        web.loadDataWithBaseURL("https://artwork.invalid/",
                            """<!doctype html><meta http-equiv="Content-Security-Policy"
                                content="default-src 'none'; img-src data:">
                                <img src="$uri" width="$width" height="$height">""",
                            "text/html", "UTF-8", null)
                    }
                } finally {
                    web.stopLoading()
                    web.destroy()
                }
            }
        } ?: error("渲染超时")
        check(result.startsWith(PNG)) { result.take(120) }
        withContext(Dispatchers.Default) { Base64.decode(result.removePrefix(PNG), Base64.DEFAULT) }
    }
}
