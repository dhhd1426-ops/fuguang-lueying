package com.fuguang.lueying

import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A private virtual display keeps browser rendering alive offscreen, including Canvas/WebGL. */
internal object MotionPreview {
    data class Shot(val timeMs: Long, val bitmap: Bitmap)
    private lateinit var context: Context
    private val lock = Mutex()
    fun init(ctx: Context) { context = ctx.applicationContext }

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    suspend fun render(html: String, times: List<Long>, width: Int, height: Int): List<Shot> = lock.withLock {
        require(times.size in 1..12 && times.all { it >= 0 }) { "每次预览 1–12 个有效时刻" }
        require(width.toLong() * height * times.size <= 6_000_000) { "这一批预览画面过大，请减少帧数或画布尺寸" }
        require(width in 240..960 && height in 128..1600 && width.toLong() * height <= 1_536_000) { "预览画布超出范围" }
        withContext(Dispatchers.Main) {
            withTimeout(60_000) {
                val handler = Handler(Looper.getMainLooper())
                val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
                reader.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, handler)
                val manager = context.getSystemService(DisplayManager::class.java)
                val display = try { checkNotNull(manager.createVirtualDisplay("Vignette motion review", width, height, 160,
                    reader.surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)) {
                    "设备无法创建实际渲染预览"
                } } catch (e: Exception) { reader.close(); throw e }
                var presentation: Presentation? = null
                var web: WebView? = null
                val shots = mutableListOf<Shot>()
                try {
                    val screen = Presentation(context, display.display)
                    presentation = screen
                    val view = WebView(screen.context)
                    web = view
                    val errors = mutableListOf<String>()
                    view.settings.apply {
                        javaScriptEnabled = true
                        allowFileAccess = false
                        allowContentAccess = false
                        // Every HTTP request is fulfilled or rejected by the client below.
                        blockNetworkLoads = false
                        useWideViewPort = true
                    }
                    view.setBackgroundColor(android.graphics.Color.WHITE)
                    view.webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                            if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR && errors.size < 4)
                                errors += message.message().take(160)
                            return true
                        }
                    }
                    screen.setContentView(view)
                    screen.window?.apply {
                        addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
                        setWindowAnimations(0)
                        setDimAmount(0f)
                        setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.WHITE))
                        decorView.setPadding(0, 0, 0, 0)
                    }
                    screen.show()
                    screen.window?.setLayout(width, height)
                    val reviewUrl = "https://${MaterialStore.HOST}/__motion_review__/${java.util.UUID.randomUUID()}"
                    val viewport = "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                    val page = if (html.contains(Regex("name\\s*=\\s*['\"]viewport", RegexOption.IGNORE_CASE))) html
                        else if (html.contains("<head>", true)) html.replaceFirst(Regex("<head>", RegexOption.IGNORE_CASE), "<head>$viewport")
                        else "$viewport$html"
                    val content = Kit.inject(screen.context, page).toByteArray(Charsets.UTF_8)
                    suspendCancellableCoroutine<Unit> { cont ->
                        view.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest) = true
                            override fun shouldInterceptRequest(v: WebView, req: WebResourceRequest): WebResourceResponse {
                                if (req.isForMainFrame && req.url.toString() == reviewUrl)
                                    return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(content))
                                return MaterialStore.serve(req) ?: WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                            }
                            override fun onPageFinished(v: WebView, url: String) {
                                if (url == reviewUrl && cont.isActive) cont.resume(Unit)
                            }
                        }
                        view.loadUrl(reviewUrl)
                    }
                    check(view.js("document.documentElement.innerHTML.length").toIntOrNull()?.let { it > 0 } == true) { "实际预览文档未加载" }
                    view.js(context.assets.open("motion-review.js").bufferedReader().use { it.readText() })
                    for ((index, time) in (times + times.first()).withIndex()) {
                        view.seek(time)
                        check(errors.isEmpty()) { "预览运行错误：${errors.joinToString("；")}" }
                        view.visual()
                        // PixelCopy reads the actual composed browser surface, rather than WebView.draw's software approximation.
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        var copyFinished = false
                        try {
                            suspendCancellableCoroutine<Unit> { cont ->
                                PixelCopy.request(checkNotNull(screen.window), bitmap, { code ->
                                    copyFinished = true
                                    if (cont.isActive) {
                                        if (code == PixelCopy.SUCCESS) cont.resume(Unit)
                                        else cont.resumeWithException(IllegalStateException("实际预览截图失败（$code）"))
                                    } else bitmap.recycle()
                                }, handler)
                            }
                            if (index == times.size) {
                                check(shots.first().bitmap.sameAs(bitmap)) { "同一时刻重复渲染不一致，请固定随机种子并修复 seek，使时间定位可重复" }
                                bitmap.recycle()
                            } else shots += Shot(time, bitmap)
                        } catch (e: Exception) { if (copyFinished) bitmap.recycle(); throw e }
                    }
                    shots.toList()
                } catch (e: Exception) {
                    shots.forEach { it.bitmap.recycle() }
                    throw e
                } finally {
                    web?.stopLoading()
                    presentation?.dismiss()
                    web?.destroy()
                    display.release()
                    reader.close()
                }
            }
        }
    }

    private suspend fun WebView.js(code: String): String = suspendCancellableCoroutine { cont ->
        evaluateJavascript(code) { result -> if (cont.isActive) cont.resume(result ?: "null") }
    }

    private suspend fun WebView.seek(time: Long) {
        js("window.__fgMotionResult=null;window.__fgMotionReview.seek($time).then(r=>window.__fgMotionResult={ok:true,result:r}).catch(e=>window.__fgMotionResult={ok:false,error:String(e.message||e)});void 0;")
        withTimeout(12_000) {
            while (true) {
                val raw = js("JSON.stringify(window.__fgMotionResult)")
                val decoded = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
                if (!decoded.isNullOrBlank() && decoded != "null") {
                    val result = JSONObject(decoded)
                    check(result.optBoolean("ok")) { result.optString("error", "动画无法定位") }
                    return@withTimeout
                }
                delay(25)
            }
        }
    }

    private suspend fun WebView.visual() {
        suspendCancellableCoroutine<Unit> { cont ->
            postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                override fun onComplete(id: Long) { if (cont.isActive) cont.resume(Unit) }
            })
            invalidate()
        }
        // A visual-state callback only promises readiness for the NEXT draw. PixelCopy must wait
        // until the Android render thread has actually submitted that frame to the surface.
        check(isHardwareAccelerated) { "设备未启用实际硬件渲染，不能验证动画截图" }
        suspendCancellableCoroutine<Unit> { cont ->
            val callback = Runnable { if (cont.isActive) cont.resume(Unit) }
            val observer = viewTreeObserver
            observer.registerFrameCommitCallback(callback)
            cont.invokeOnCancellation { if (observer.isAlive) observer.unregisterFrameCommitCallback(callback) }
            invalidate()
        }
    }
}
