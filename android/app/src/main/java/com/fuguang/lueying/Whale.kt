package com.fuguang.lueying

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.Charset
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * 主页的 Live2D 鲸鱼娘（大肥鱼）。
 * 页面在 assets/whale/（透明 WebView，自己拦请求供文件：/app/ ← assets/whale，/model/ ← filesDir/whale/model）。
 * 模型文件不随 APK 分发：用户导入 DeepSeek.zip，这里解到私有目录。
 * 原贴图是五张 8192 的整页图集，页面里直接解会把渲染进程撑爆（真机上表现为她一直不出来）：
 * 导入时就缩到 4096（内存不足 7GB 缩到 2048）存好，页面只读缩好的。
 * 页面 → App：Vg.event(ready|phase|editDone|error|glLost|box)；App → 页面：window.whale.*。
 */
object Whale {
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private const val HOST = "whale.local"

    var show by mutableStateOf(true)
    var mood by mutableStateOf(true)
    var touch by mutableStateOf(true)
    /** 长按把她拎起来拖到别处 */
    var drag by mutableStateOf(true)
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
    /** 旧版导入的模型在后台补做贴图整理，这段时间她不上台 */
    var prepping by mutableStateOf(false); private set
    /** 加载走到哪一步了（设置页里看得到，超时的时候一起报出来） */
    var phase by mutableStateOf(""); private set
    /** 渲染进程连着崩，先停用，等重新导入 */
    var broken by mutableStateOf(false); private set
    private var crashes = 0
    private var gen by mutableIntStateOf(0)
    /** WebGL 丢过上下文、或渲染进程崩过，就退到标准画质，并记下来（下次启动不再试高清） */
    private var lowQ = false

    private var web: WebView? = null
    private val queue = ArrayList<String>()
    /** 她占的范围（视口比例 l,t,r,b）；主页在这块上盖一层接触摸（WhaleTouch） */
    var box by mutableStateOf(floatArrayOf(0f, 0f, 0f, 0f)); private set

    /** 该不该搭台（WebView） */
    val active get() = show && hasModel && !prepping && !broken
    /** 她真的站在主页上了：主页据此让位（标志上移、问候下移）；没出来时主页保持原样 */
    val onStage get() = active && ready && error.isEmpty()

    fun init(ctx: Context) {
        app = ctx.applicationContext
        runCatching {
            val o = JSONObject(sp().getString("set", null) ?: "{}")
            show = o.optBoolean("show", true); mood = o.optBoolean("mood", true); touch = o.optBoolean("touch", true); drag = o.optBoolean("drag", true)
            idle = o.optInt("idle", 2); body = o.optDouble("body", 0.0).toFloat()
            outfit = o.optJSONArray("outfit")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
        }
        lowQ = sp().getBoolean("lowq", false)
        hasModel = File(dir(), "model.model3.json").isFile
        if (hasModel && !File(dir(), "tex/.ok").isFile) {
            prepping = true; phase = "正在整理贴图…"
            Thread {
                val r = runCatching { prep(dir()) { s -> main.post { phase = s } } }
                main.post {
                    prepping = false; phase = ""
                    r.onFailure { broken = true; error = "贴图整理失败：${it.message ?: it.javaClass.simpleName}。请重新导入模型" }
                }
            }.start()
        }
    }

    private fun totalMem() = runCatching {
        val mi = ActivityManager.MemoryInfo()
        (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi); mi.totalMem
    }.getOrDefault(0L)
    private fun roomy() = totalMem() >= (7L shl 30)

    private fun low() { lowQ = true; sp().edit().putBoolean("lowq", true).apply() }

    private fun dir() = File(app.filesDir, "whale/model")
    private fun sp() = app.getSharedPreferences("whale", Context.MODE_PRIVATE)

    private fun css(c: androidx.compose.ui.graphics.Color) =
        "rgba(${(c.red * 255).toInt()},${(c.green * 255).toInt()},${(c.blue * 255).toInt()},${"%.3f".format(Locale.US, c.alpha)})"
    /** 气泡跟 App 的主题走 */
    private fun theme() = JSONObject().put("card", css(Skin.sheet)).put("ink", css(Skin.ink)).put("accent", css(Skin.accent))

    private fun setJson() = JSONObject()
        .put("show", show).put("mood", mood).put("touch", touch).put("drag", drag).put("idle", idle)
        .put("outfit", JSONArray(outfit.toList())).put("body", body.toDouble()).put("theme", theme())

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
    private val DANCE = Regex("跳.{0,3}舞")
    fun sent(text: String) = js(if (DANCE.containsMatchIn(text)) "whale.dance()" else "whale.sent()")
    fun think() = js("whale.think()")
    fun talk(n: Int) = js("whale.talk($n)")
    fun replied(text: String) = js("whale.replied(${q(text.take(600))})")
    fun say(text: String) = js("whale.say(${q(text)})")
    fun moodOn(k: String) = js("whale.mood(${q(k)},3200)")
    fun face(k: String) = js("whale.face(${q(k)})")
    fun routine(n: String) = js("whale.routine(${q(n)})")

    /** 她在窗口里占的矩形（像素 l,t,r,b）；没站出来时为 null */
    fun rect(): FloatArray? {
        val w = web ?: return null
        val b = box
        if (!ready || w.width == 0 || w.height == 0 || b[2] <= b[0] || b[3] <= b[1]) return null
        val at = IntArray(2); w.getLocationInWindow(at)
        return floatArrayOf(at[0] + b[0] * w.width, at[1] + b[1] * w.height, at[0] + b[2] * w.width, at[1] + b[3] * w.height)
    }

    /** 手指（窗口坐标）转给页面：kind = down / move / up */
    fun ptr(kind: String, wx: Float, wy: Float) {
        val w = web ?: return
        if (!ready || w.width == 0 || w.height == 0) return
        val at = IntArray(2); w.getLocationInWindow(at)
        w.evaluateJavascript("whale.ptr('$kind',${(wx - at[0]) / w.width},${(wy - at[1]) / w.height})", null)
    }

    fun edit(on: Boolean) { editing = on; js("whale.edit($on)") }
    fun dress(on: Boolean) { dressing = on; if (!on) sheetTop = 0f; js("whale.dress($on)"); pushRoom() }

    // —— 让路：助理在回复、在打字、在翻对话时她不说自己的话 ——
    private val holds = HashSet<String>()
    fun hold(why: String, on: Boolean) {
        val was = holds.isNotEmpty()
        if (on) holds += why else holds -= why
        if (holds.isNotEmpty() != was) js("whale.quiet(${holds.isNotEmpty()})")
    }

    /** 侧栏、整页卡片盖住她时停帧 */
    private var covered = false
    fun cover(on: Boolean) { if (on != covered) { covered = on; js("whale.cover($on)") } }

    // —— 她能站的范围（窗口坐标）：标题栏下面到输入框上面；换装时到面板上面。键盘推起输入框时每帧跟着报 ——
    private var roomTop = 0f
    private var roomBot = 0f
    private var sheetTop = 0f
    private var lastRoom = ""
    fun room(top: Float, bottom: Float) { roomTop = top; roomBot = bottom; pushRoom() }
    fun sheet(top: Float) { if (dressing) { sheetTop = top; pushRoom() } }
    private fun pushRoom() {
        val w = web ?: return
        if (!ready || w.height == 0 || roomBot <= roomTop) return
        val at = IntArray(2); w.getLocationInWindow(at)
        val b = if (dressing && sheetTop > roomTop) minOf(roomBot, sheetTop) else roomBot
        val s = "whale.room(%.4f,%.4f)".format(Locale.US, (roomTop - at[1]) / w.height, (b - at[1]) / w.height)
        if (s != lastRoom) { lastRoom = s; w.evaluateJavascript(s, null) }
    }

    /** 她自己会说的固定台词（Speech 预先合成存在手机上）；没就绪时给空 */
    fun lines(done: (List<String>) -> Unit) {
        val w = web ?: return done(emptyList())
        if (!ready) return done(emptyList())
        w.evaluateJavascript("whale.lines()") { raw ->
            done(runCatching {
                val a = JSONArray(JSONArray("[$raw]").getString(0))
                (0 until a.length()).map { a.getString(it) }
            }.getOrDefault(emptyList()))
        }
    }

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
                ready = true; error = ""; crashes = 0
                phase = "已就绪 · 贴图 ${o.optInt("tex")} · 遮罩 ${o.optInt("mask")}"
                val w = web ?: return
                w.evaluateJavascript("whale.set(${setJson()})", null)
                val pending = synchronized(queue) { queue.toList().also { queue.clear() } }
                // 只补最后一次布局类调用，回放一堆旧的嘴型没意义
                pending.lastOrNull { it.startsWith("whale.chat(") }?.let { w.evaluateJavascript(it, null) }
                pending.lastOrNull { it.startsWith("whale.say(") }?.let { w.evaluateJavascript(it, null) }
                if (dressing) w.evaluateJavascript("whale.dress(true)", null)
                if (holds.isNotEmpty()) w.evaluateJavascript("whale.quiet(true)", null)
                if (covered) w.evaluateJavascript("whale.cover(true)", null)
                lastRoom = ""; pushRoom()
                if (editing) w.evaluateJavascript("whale.edit(true)", null)
            }
            "editDone" -> editing = false
            "act" -> WhaleTools.note(o)
            "speak" -> Speech.whaleSay(o.optString("text"))
            "grab" -> web?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            "error" -> error = o.optString("msg").ifBlank { json.takeIf { it.isNotBlank() && !it.startsWith("{") } ?: "鲸鱼娘没能加载" }
            // 站出来以后的小错：她照常动，只记进日志（不再盖成「载入失败」）
            "warn" -> android.util.Log.w("Whale", o.optString("msg"))
            "phase" -> if (!ready) phase = o.optString("s")
            "glLost" -> if (!lowQ) { low(); reload() } else { ready = false; error = "显存不够，她没能留在屏幕上" }
            "box" -> box = floatArrayOf(o.optDouble("l").toFloat(), o.optDouble("t").toFloat(), o.optDouble("r").toFloat(), o.optDouble("b").toFloat())
        }
    }

    private fun reload() { ready = false; error = ""; phase = ""; box = floatArrayOf(0f, 0f, 0f, 0f); gen++ }

    private fun url(): String {
        val hd = !lowQ && roomy()
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
        // 平时不收触摸：她在对话列表后面，收不到；点和拖由 WhaleTouch 转给页面。只有「位置与大小」时（抬到最上层）自己收
        override fun dispatchTouchEvent(e: MotionEvent): Boolean = if (editing) super.dispatchTouchEvent(e) else false
    }.apply {
        // 不设的话 WebView 按「包住内容」量自己：页面里 height:100% 算出来是 0，舞台高 0，她被缩成 0（真机上就是看不见）
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
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
                low(); crashes++
                if (crashes >= 3) { ready = false; broken = true; error = "她的渲染进程连着崩了几次，先停用了。重新导入模型可以再试" } else reload()
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
        // 换主题时气泡跟着换色
        val th = theme().toString()
        LaunchedEffect(th, ready) { if (ready) web?.evaluateJavascript("whale.set({theme:$th})", null) }
        // 半分钟还没站出来又没报错：别让主页一直空等，记成超时（之后真好了会自己清掉）
        LaunchedEffect(gen) {
            delay(30_000)
            if (!ready && error.isEmpty()) error = "加载超时" + if (phase.isNotEmpty()) "（停在：$phase）" else ""
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
        importing = true; importMsg = "正在解压，大约一分钟…"
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { unzip(uri) { s -> main.post { if (importing) importMsg = s } } } }
            importing = false
            r.onSuccess {
                importMsg = ""; hasModel = true; broken = false; crashes = 0
                lowQ = false; sp().edit().remove("lowq").apply()
                reload()
            }
                .onFailure { importMsg = it.message ?: "导入失败" }
        }
    }

    fun remove() {
        dir().deleteRecursively(); hasModel = false; ready = false; web = null
        broken = false; crashes = 0; error = ""; phase = ""
    }

    private val BW = listOf(
        "untracked", "bw_happy", "bw_shy", "bw_gentle", "bw_surprise", "bw_naughty", "bw_love", "bw_angry1", "bw_angry2", "bw_angry3",
        "bw_sad", "bw_awk1", "bw_awk2", "bw_pat", "bw_talk_chest", "bw_talk_grass", "bw_talk_finger", "bw_idle60",
    )

    private fun unzip(uri: Uri, say: (String) -> Unit) {
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
        try { prep(tmp, say) } catch (e: Throwable) { tmp.deleteRecursively(); kotlin.error(e.message ?: "贴图整理失败") }
        val dst = dir()
        dst.deleteRecursively()
        if (!tmp.renameTo(dst)) kotlin.error("写入失败，存储空间可能不够")
    }

    /** 把 tex/ 里的贴图缩到这台手机吃得下的大小（最长边 4096；内存不足 7GB 用 2048），做完留个记号。 */
    private fun prep(root: File, say: (String) -> Unit) {
        val files = File(root, "tex").listFiles { f -> f.name.endsWith(".png") }?.sortedBy { it.name }.orEmpty()
        val cap = if (roomy()) 4096 else 2048
        files.forEachIndexed { i, f -> say("正在整理贴图 ${i + 1}/${files.size}…"); shrink(f, cap) }
        File(root, "tex/.ok").writeText(cap.toString())
    }

    private fun shrink(f: File, cap: Int) {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        val w = o.outWidth; val h = o.outHeight
        if (w <= 0 || h <= 0) kotlin.error("贴图读不出来：${f.name}")
        var k = 1
        while (maxOf(w, h) / k > cap) k *= 2
        if (k == 1) return
        // 内存够就整张解开，再一半一半地缩（每次 2×2 取平均，发丝边缘干净）；不够就解的时候先抽掉一半
        val tries = (if (roomy()) listOf(1, 2, k) else listOf(2, k)).filter { it <= k }.distinct()
        try {
            var bmp = tries.firstNotNullOfOrNull { decode(f, it) } ?: kotlin.error("内存不够，贴图解不开：${f.name}")
            while (maxOf(bmp.width, bmp.height) > cap) { val n = half(bmp); bmp.recycle(); bmp = n }
            val tmp = File(f.path + ".tmp")
            val ok = tmp.outputStream().buffered(1 shl 16).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            if (!ok || !tmp.renameTo(f)) { tmp.delete(); kotlin.error("贴图写不进去，存储空间可能不够：${f.name}") }
        } catch (e: OutOfMemoryError) {
            kotlin.error("内存不够，贴图缩不了：${f.name}")
        }
    }

    private fun decode(f: File, sample: Int): Bitmap? = try {
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
    } catch (e: OutOfMemoryError) { null }

    private fun half(src: Bitmap): Bitmap {
        val w = maxOf(1, src.width / 2); val h = maxOf(1, src.height / 2)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }
}
