package com.fuguang.lueying

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import java.io.ByteArrayOutputStream
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.ConsoleMessage
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

/** Opens the prepared page without exposing app bridges or permitting external navigation. */
internal object WorkReview {
    data class Result(val ready: Boolean, val text: String, val image: ByteArray? = null)
    private data class Preview(val report: JSONObject, val image: ByteArray?)

    private lateinit var context: Context
    private val lock = Mutex()

    fun init(ctx: Context) { context = ctx.applicationContext }

    suspend fun inspect(html: String, missing: List<String>, capture: Boolean): Result = lock.withLock {
        val errors = mutableListOf<String>()
        val preview = withTimeoutOrNull(12_000) {
            withContext(Dispatchers.Main) {
                val view = WebView(context)
                try {
                    view.settings.apply {
                        javaScriptEnabled = true
                        useWideViewPort = true
                        allowFileAccess = false
                        allowContentAccess = false
                        blockNetworkLoads = true
                    }
                    val density = context.resources.displayMetrics.density
                    val width = (393 * density).toInt()
                    val height = (852 * density).toInt()
                    view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    view.layout(0, 0, width, height)
                    suspendCancellableCoroutine<Preview?> { cont ->
                        view.webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR && errors.size < 4)
                                    errors += message.message().take(200)
                                return true
                            }
                        }
                        view.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = MaterialStore.serve(request)
                            override fun onPageFinished(view: WebView, url: String) {
                                if (!cont.isActive) return
                                view.evaluateJavascript(PROBE) { raw ->
                                    val result = runCatching {
                                        JSONObject(JSONTokener(raw).nextValue() as String)
                                    }.getOrNull()
                                    if (cont.isActive) cont.resume(result?.let {
                                        Preview(it, if (capture && it.optBoolean("content")) screenshot(view) else null)
                                    })
                                }
                            }
                        }
                        view.loadDataWithBaseURL("https://${MaterialStore.HOST}/", Kit.inject(context, html),
                            "text/html", "UTF-8", null)
                    }
                } finally {
                    view.stopLoading()
                    view.destroy()
                }
            }
        } ?: return@withLock Result(false, "预览没有打开或未返回检查结果。请修复后再试。")
        val page = preview.report
        val ready = page.optBoolean("content")
        val result = buildString {
            append(if (ready) "已在 Android WebView 打开此稿。" else "预览没有可见正文或绘制元素。")
            append("\n窄屏横向溢出：").append(page.optInt("overflow")).append(" px")
            append("；未加载图片：").append(page.optInt("broken"))
            append("；可见交互控件：").append(page.optInt("controls"))
            append("。\n可见正文摘录：").append(page.optString("text").take(2000))
            if (errors.isNotEmpty()) append("\n控制台错误：").append(errors.joinToString("；"))
            if (missing.isNotEmpty()) append("\n未保存素材：").append(missing.take(5).joinToString("、"))
            append("\n外部网络和实际点击未验证。请根据检查结果和原始要求决定返工或交付。")
        }
        Result(ready, result, preview.image)
    }

    private fun screenshot(view: WebView): ByteArray? = runCatching {
        val bitmap = Bitmap.createBitmap(393, 852, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.scale(393f / view.width, 852f / view.height)
            view.draw(canvas)
            val first = bitmap.getPixel(0, 0)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            if (pixels.all { it == first }) null else ByteArrayOutputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                it.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }.getOrNull()

    fun image(bytes: ByteArray) = ToolImage("image/png", Base64.encodeToString(bytes, Base64.NO_WRAP))

    private const val PROBE = """JSON.stringify((function(){
        const body = document.body;
        if (!body) return {};
        const visible = e => {
            const style = getComputedStyle(e), rect = e.getBoundingClientRect();
            return rect.width > 1 && rect.height > 1 && style.visibility !== 'hidden' && style.opacity !== '0';
        };
        const text = body.innerText.trim();
        return {
            content: !!text || Array.from(body.querySelectorAll('svg,canvas,video,img')).some(visible),
            text: text.slice(0, 2000),
            overflow: Math.max(0, document.documentElement.scrollWidth - document.documentElement.clientWidth),
            broken: Array.from(document.images).filter(img => img.complete && !img.naturalWidth).length,
            controls: Array.from(body.querySelectorAll('button,a,input,select,textarea,[role=button]')).filter(visible).length
        };
    })())"""
}
