package com.fuguang.lueying

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipInputStream

/**
 * 主页的 Live2D 鲸鱼娘（大肥鱼）。
 * 页面在 assets/whale/（透明 WebView，自己拦请求供文件：/app/ ← assets/whale，/model/ ← filesDir/whale/model）。
 * 模型文件不随 APK 分发：用户导入 DeepSeek.zip，这里解到私有目录。
 * 页面 → App：Vg.event(ready|editDone|error|glLost|box)；App → 页面：window.whale.*。
 */
object Whale {
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private const val HOST = "whale.local"

    var show by mutableStateOf(true)
    var mood by mutableStateOf(true)
    var touch by mutableStateOf(true)
    /** 待机动作：0 关 / 1 少 / 2 适中 / 3 多 */
    var idle by mutableIntStateOf(2)
    var outfit by mutableStateOf(setOf<String>())
    /** 体型（参数 Clothes2）：0 大号 / .4 中号 / 1 小号 */
    var body by mutableStateOf(0f)

    var hasModel by mutableStateOf(false); private set
    var ready by mutableStateOf(false); private set
    var error by mutableStateOf(""); private set
    var editing by mutableStateOf(false); private set
    var dressing by mutableStateOf(false); private set
    var importing by mutableStateOf(false); private set
    var importMsg by mutableStateOf(""); private set
    private var gen by mutableIntStateOf(0)
    /** WebGL 丢过一次上下文就退到标准画质 */
    private var lowQ = false

    private var web: WebView? = null
    private val queue = ArrayList<String>()
    /** 她占的范围（视口比例 l,t,r,b）；范围外的按下交还给主页 */
    @Volatile private var box = floatArrayOf(0f, 0f, 0f, 0f)

    val active get() = show && hasModel

    fun init(ctx: Context) {
        app = ctx.applicationContext
        runCatching {
            val o = JSONObject(sp().getString("set", null) ?: "{}")
            show = o.optBoolean("show", true); mood = o.optBoolean("mood", true); touch = o.optBoolean("touch", true)
            idle = o.optInt("idle", 2); body = o.optDouble("body", 0.0).toFloat()
            outfit = o.optJSONArray("outfit")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
        }
        hasModel = File(dir(), "model.model3.json").isFile
    }

    private fun dir() = File(app.filesDir, "whale/model")
    private fun sp() = app.getSharedPreferences("whale", Context.MODE_PRIVATE)

    private fun setJson() = JSONObject()
        .put("show", show).put("mood", mood).put("touch", touch).put("idle", idle)
        .put("outfit", JSONArray(outfit.toList())).put("body", body.toDouble())

    /** 改了设置：存起来并推给页面 */
    fun save() {
        sp().edit().putString("set", setJson().toString()).apply()
        js("whale.set(${setJson()})")
    }

    private fun js(s: String) {
        val w = web
        if (w != null && ready) main.post { w.evaluateJavascript(s, null) } else synchronized(queue) { queue += s }
    }

    private fun q(s: String) = JSONObject.quote(s)

    fun chat(on: Boolean) = js("whale.chat($on)")
    fun typing() = js("whale.typing()")
    fun sent(text: String) = js(if ("跳舞" in text) "whale.dance()" else "whale.sent()")
    fun think() = js("whale.think()")
    fun talk(n: Int) = js("whale.talk($n)")
    fun replied(text: String) = js("whale.replied(${q(text.take(600))})")
    fun say(text: String) = js("whale.say(${q(text)})")
    fun moodOn(k: String) = js("whale.mood(${q(k)},3200)")
    fun face(k: String) = js("whale.face(${q(k)})")
    fun routine(n: String) = js("whale.routine(${q(n)})")

    fun edit(on: Boolean) { editing = on; js("whale.edit($on)") }
    fun dress(on: Boolean) { dressing = on; js("whale.dress($on)") }

    /** 页面里的动作名（试动作用）；没就绪时给空 */
    fun routines(done: (List<String>) -> Unit) {
        val w = web ?: return done(emptyList())
        if (!ready) return done(emptyList())
        w.evaluateJavascript("whale.routines()") { raw ->
            done(runCatching {
                val a = JSONArray(JSONArray("[$raw]").getString(0))
                (0 until a.length()).map { a.getString(it) }
            }.getOrDefault(emptyList()))
        }
    }

    private class Bridge {
        @JavascriptInterface
        fun event(name: String, json: String) { main.post { onEvent(name, json) } }
    }

    private fun onEvent(name: String, json: String) {
        val o = runCatching { JSONObject(json) }.getOrDefault(JSONObject())
        when (name) {
            "ready" -> {
                ready = true; error = ""
                val w = web ?: return
                w.evaluateJavascript("whale.set(${setJson()})", null)
                val pending = synchronized(queue) { queue.toList().also { queue.clear() } }
                // 只补最后一次布局类调用，回放一堆旧的嘴型没意义
                pending.lastOrNull { it.startsWith("whale.chat(") }?.let { w.evaluateJavascript(it, null) }
                if (dressing) w.evaluateJavascript("whale.dress(true)", null)
                if (editing) w.evaluateJavascript("whale.edit(true)", null)
            }
            "editDone" -> editing = false
            "error" -> error = o.optString("msg", "鲸鱼娘没能加载")
            "glLost" -> if (!lowQ) { lowQ = true; reload() }
            "box" -> box = floatArrayOf(o.optDouble("l").toFloat(), o.optDouble("t").toFloat(), o.optDouble("r").toFloat(), o.optDouble("b").toFloat())
        }
    }

    private fun reload() { ready = false; box = floatArrayOf(0f, 0f, 0f, 0f); gen++ }

    private fun url(): String {
        val mem = runCatching {
            val mi = ActivityManager.MemoryInfo()
            (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi); mi.totalMem
        }.getOrDefault(0L)
        val hd = !lowQ && mem >= (7L shl 30)
        return "https://$HOST/app/index.html?mask=${if (lowQ) 2048 else 4096}&hd=${if (hd) 1 else 0}"
    }

    private fun mime(p: String) = when (p.substringAfterLast('.').lowercase()) {
        "html" -> "text/html"; "js" -> "text/javascript"; "json" -> "application/json"
        "png" -> "image/png"; "webp" -> "image/webp"; "webm" -> "video/webm"; "css" -> "text/css"
        else -> "application/octet-stream"
    }

    private fun serve(u: Uri): WebResourceResponse? {
        if (u.host != HOST) return null
        val p = u.path.orEmpty()
        val stream = runCatching {
            when {
                p.startsWith("/app/") -> app.assets.open("whale/" + p.removePrefix("/app/"))
                p.startsWith("/model/") -> {
                    val root = dir().canonicalFile
                    val f = File(root, p.removePrefix("/model/")).canonicalFile
                    if (f.path.startsWith(root.path + File.separator) && f.isFile) f.inputStream() else null
                }
                else -> null
            }
        }.getOrNull() ?: return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        val text = mime(p).let { it.startsWith("text/") || it.endsWith("json") }
        return WebResourceResponse(mime(p), if (text) "utf-8" else null, stream)
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun make(ctx: Context): WebView = object : WebView(ctx) {
        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN && !editing) {
                if (!touch || !ready || width == 0 || height == 0) return false
                val x = e.x / width; val y = e.y / height; val b = box
                if (x < b[0] || x > b[2] || y < b[1] || y > b[3]) return false
            }
            return super.dispatchTouchEvent(e)
        }
    }.apply {
        setBackgroundColor(Color.TRANSPARENT)
        isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        addJavascriptInterface(Bridge(), "Vg")
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = serve(request.url)
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (web === view) web = null
                lowQ = true; reload()
                return true
            }
        }
        loadUrl(url())
    }

    /** 铺满的舞台层；放在主页内容后面（编辑位置时由调用方抬到最上层）。 */
    @Composable
    fun Stage(modifier: Modifier = Modifier) {
        if (!active) return
        val owner = LocalLifecycleOwner.current
        DisposableEffect(owner) {
            val obs = LifecycleEventObserver { _, ev ->
                when (ev) {
                    Lifecycle.Event.ON_PAUSE -> { web?.onPause(); web?.evaluateJavascript("whale.pause(true)", null) }
                    Lifecycle.Event.ON_RESUME -> { web?.onResume(); web?.evaluateJavascript("whale.pause(false)", null) }
                    else -> Unit
                }
            }
            owner.lifecycle.addObserver(obs)
            onDispose { owner.lifecycle.removeObserver(obs) }
        }
        key(gen) {
            AndroidView(
                factory = { c -> make(c).also { web = it } },
                modifier = modifier,
                onRelease = { w -> if (web === w) { web = null; ready = false }; w.destroy() },
            )
        }
    }

    /** 导入 DeepSeek.zip：只取模型需要的文件（跳过 psd），写 model3.json，重新加载。 */
    fun import(uri: Uri, scope: CoroutineScope) {
        if (importing) return
        importing = true; importMsg = "正在解压，大约半分钟…"
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { unzip(uri) } }
            importing = false
            r.onSuccess { importMsg = ""; hasModel = true; error = ""; lowQ = false; reload() }
                .onFailure { importMsg = it.message ?: "导入失败" }
        }
    }

    fun remove() {
        dir().deleteRecursively(); hasModel = false; ready = false; web = null
    }

    private val BW = listOf(
        "untracked", "bw_happy", "bw_shy", "bw_gentle", "bw_surprise", "bw_naughty", "bw_love", "bw_angry1", "bw_angry2", "bw_angry3",
        "bw_sad", "bw_awk1", "bw_awk2", "bw_pat", "bw_talk_chest", "bw_talk_grass", "bw_talk_finger", "bw_idle60",
    )

    private fun unzip(uri: Uri) {
        val tmp = File(app.filesDir, "whale/tmp")
        tmp.deleteRecursively(); File(tmp, "tex").mkdirs(); File(tmp, "mo").mkdirs()
        val tex = ArrayList<String>()
        var moc = false; var phy = false; var cdi = false; var idleMo = false
        val ins = app.contentResolver.openInputStream(uri) ?: kotlin.error("打不开这个文件")
        ZipInputStream(BufferedInputStream(ins, 1 shl 16), Charset.forName("GBK")).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val n = e.name.replace('\\', '/')
                val b = n.substringAfterLast('/')
                if (e.isDirectory || b.isEmpty() || "/psd/" in "/$n") continue
                val out = when {
                    b.endsWith(".moc3") -> { moc = true; "model.moc3" }
                    b.endsWith(".physics3.json") -> { phy = true; "model.physics3.json" }
                    b.endsWith(".cdi3.json") -> { cdi = true; "model.cdi3.json" }
                    b == "IDLE.motion3.json" -> { idleMo = true; "mo/idle.motion3.json" }
                    b.startsWith("texture_") && b.endsWith(".png") -> { tex += b; "tex/$b" }
                    else -> null
                } ?: continue
                File(tmp, out).outputStream().use { z.copyTo(it, 1 shl 16) }
            }
        }
        if (!moc || tex.isEmpty()) { tmp.deleteRecursively(); kotlin.error("压缩包里没找到鲸鱼娘模型，请选 DeepSeek.zip") }
        tex.sort()
        val mo = JSONObject()
        if (idleMo) mo.put("Idle", JSONArray().put(JSONObject().put("File", "mo/idle.motion3.json").put("FadeInTime", .5).put("FadeOutTime", .5)))
        for (g in BW) mo.put(g, JSONArray().put(JSONObject().put("File", "/app/mo/$g.motion3.json").put("FadeInTime", .4).put("FadeOutTime", .4)))
        val refs = JSONObject().put("Moc", "model.moc3").put("Textures", JSONArray(tex.map { "tex/$it" })).put("Motions", mo)
        if (phy) refs.put("Physics", "model.physics3.json")
        if (cdi) refs.put("DisplayInfo", "model.cdi3.json")
        val groups = JSONArray()
            .put(JSONObject().put("Target", "Parameter").put("Name", "EyeBlink").put("Ids", JSONArray(listOf("ParamEyeROpen", "ParamEyeLOpen"))))
            .put(JSONObject().put("Target", "Parameter").put("Name", "LipSync").put("Ids", JSONArray(listOf("ParamMouthOpenY"))))
        File(tmp, "model.model3.json").writeText(JSONObject().put("Version", 3).put("FileReferences", refs).put("Groups", groups).toString())
        val dst = dir()
        dst.deleteRecursively()
        if (!tmp.renameTo(dst)) kotlin.error("写入失败，存储空间可能不够")
    }
}
