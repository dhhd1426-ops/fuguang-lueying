package com.fuguang.lueying

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import java.io.File

private val Apricot = css("#e9b48e")
private val Ease = CubicBezierEasing(.2f, .8f, .2f, 1f)

private object WorkIcons {
    // 网页：一圆一方，像视频里那枚
    val page = Ic("M12.5 12.5h7.5v7.5h-7.5Z", circles = listOf(floatArrayOf(9f, 9f, 5f)))
    val slides = Ic("M3.5 5h17v11h-17ZM12 16v3.5M8.5 19.5h7")
    val doc = Ic("M14 3H6v18h12V7ZM14 3v4h4M9 12h6M9 15.5h6M9 9h2.5")
    // 画板：一大一小两块积木叠着
    val board = Ic("M4 4h16v6H4ZM4 13h7v7H4ZM14 13h6v7h-6Z")
    val close = Ic("M6 6 18 18M6 18 18 6")
    val share = Ic("M12 3.5v11M8 7.5l4-4 4 4M6 11v8.5h12V11")
    fun of(kind: String) = when (kind) { "slides" -> slides; "doc" -> doc; "board" -> board; else -> page }
}

/** 回复下面的作品卡：左边方块图标，右边作品名和种类。点开全屏看。 */
@Composable
fun WorkCards(ids: List<String>, onOpen: (String) -> Unit) {
    if (ids.isEmpty()) return
    val v = Works.version
    Column(Modifier.padding(top = 10.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ids.forEach { id ->
            val w = remember(id, v) { Works.get(id) } ?: return@forEach
            val used = remember(id, v) { Works.source(id)?.let { MaterialStore.usedOf(it) }.orEmpty() }
            val faces = remember(used) { MaterialStore.faces(used) }
            Pressable({ onOpen(id) }, Modifier.widthIn(max = 440.dp).fillMaxWidth(), label = "打开作品 ${w.title}") { pressed ->
                Row(
                    Modifier.fillMaxWidth()
                        .background(rgba(255, 255, 255, if (pressed) .085f else .055f), RoundedCornerShape(16.dp))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (faces.isNotEmpty()) MaterialStack(faces)
                    else Box(
                        Modifier.size(56.dp).background(Apricot.copy(alpha = .13f), RoundedCornerShape(11.dp)),
                        contentAlignment = Alignment.Center,
                    ) { SvgIcon(WorkIcons.of(w.kind), Apricot, 24.dp, stroke = 1.5f) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Txt(w.title, Page.INK, 16.sp, family = Serif, maxLines = 1)
                        Spacer(Modifier.height(4.dp))
                        val kind = if (w.kind == "board") "画板" else if (used.isEmpty()) "作品 · ${w.kindName}" else w.kindName
                        Txt(kind + (if (used.isEmpty()) "" else " · 用了 ${used.size} 件素材"), Page.MUTED, 12.5.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** 全屏看作品：顶上「关闭 · 作品名 · 分享」，下面是作品本身；加载时中间转一圈。 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WorkViewer(id: String?, onClose: () -> Unit, onFix: (String) -> Unit = {}) {
    var last by remember { mutableStateOf<String?>(null) }
    if (id != null) last = id
    val shown by animateFloatAsState(if (id != null) 1f else 0f, tween(if (id != null) 320 else 240, easing = Ease), label = "work")
    var sheet by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = id != null && sheet) { sheet = false }
    // 返回键：作品里开着详情、弹窗、大图时先关它们（fg.back），都没开才关作品
    var webRef by remember { mutableStateOf<WebView?>(null) }
    androidx.activity.compose.BackHandler(enabled = id != null && !sheet) {
        val wv = webRef
        if (wv == null) onClose() else wv.evaluateJavascript("(window.fg&&fg.back())?'1':'0'") { r -> if (r?.contains("1") != true) onClose() }
    }
    if (shown <= 0f || last == null) { sheet = false; return }
    val cur = last!!
    val v = Works.version
    val w = remember(cur, v) { Works.get(cur) }
    val html = remember(cur, v) { Works.html(cur) }
    // 加载状态和页面里的报错：WebView 回调直接改这两个，不跟着 remember 重建（否则回调还拿着旧的那份）
    val loaded = remember { mutableStateOf(false) }
    val problem = remember { mutableStateOf("") }
    var web by remember { mutableStateOf<WebView?>(null) }
    // 画板页面改过时存回原稿：只认正开着的这件画板
    val boardId = remember { arrayOf<String?>(null) }
    boardId[0] = if (w?.kind == "board") cur else null
    val ctx = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val lift = with(density) { 80.dp.toPx() }

    // 注意：WebView 不能放在带透明度的 graphicsLayer 里，有的机型会整页画不出来。
    // 所以进出场的透明和上移只加在底色和顶栏上，WebView 用它自己的 alpha / translationY。
    Box(
        Modifier.fillMaxSize()
            .background(css("#121519").copy(alpha = shown))
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().graphicsLayer { alpha = shown; translationY = (1f - shown) * lift }
                    .windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Pressable(onClose, label = "关闭") { p -> SvgIcon(WorkIcons.close, Page.INK.copy(alpha = if (p) 1f else .82f), 22.dp, modifier = Modifier.padding(10.dp)) }
                Txt(w?.title ?: "作品", Page.INK, 16.5.sp, Modifier.weight(1f), family = Serif, align = TextAlign.Center, maxLines = 1)
                Pressable({ sheet = true }, label = "分享") { p -> SvgIcon(WorkIcons.share, Page.INK.copy(alpha = if (p) 1f else .82f), 22.dp, modifier = Modifier.padding(10.dp)) }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (html == null) {
                    Txt("这件作品找不到了", Page.MUTED, 14.sp, Modifier.align(Alignment.Center))
                } else {
                    AndroidView(
                        factory = { c ->
                            WebView(c).apply {
                                // 一定要撑满：不给尺寸时 WebView 按内容高度算，页面里的 100vh、innerHeight 会是 0，整页空白
                                layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                                setBackgroundColor(0xFF121519.toInt())
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.mediaPlaybackRequiresUserGesture = false
                                settings.allowFileAccess = false
                                addJavascriptInterface(BoardBridge(boardId), "FG")
                                // 作品套件（fgkit.js）用：外链在内置浏览器开、复制
                                addJavascriptInterface(KitBridge(c), "FGApp")
                                // 页面里的 JS 报错记下来，显示在底部，方便排查
                                webChromeClient = object : android.webkit.WebChromeClient() {
                                    override fun onConsoleMessage(m: android.webkit.ConsoleMessage?): Boolean {
                                        if (m != null && m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR && problem.value.isEmpty())
                                            problem.value = m.message().take(160) + (if (m.lineNumber() > 0) "（第 ${m.lineNumber()} 行）" else "")
                                        return true
                                    }
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        loaded.value = true
                                        // 过一会儿量一下：页面尺寸是 0、或者什么都没画出来，就当作有问题
                                        view?.postDelayed({
                                            if (view.tag == null) return@postDelayed
                                            view.evaluateJavascript(PROBE) { r ->
                                                val t = r.orEmpty().trim('"')
                                                if (t.isNotEmpty() && t != "ok" && problem.value.isEmpty()) problem.value = t
                                            }
                                        }, 1500)
                                    }
                                    override fun onReceivedError(view: WebView?, req: WebResourceRequest?, err: android.webkit.WebResourceError?) {
                                        val u = req?.url?.toString().orEmpty()
                                        // 站点图标是 WebView 自己去要的，没有也不影响；图片坏了作品套件会换成占位，不用再提
                                        val img = req?.requestHeaders?.get("Accept")?.startsWith("image") == true || Regex("\\.(png|jpe?g|gif|webp|svg|avif|ico)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(u)
                                        if (req?.isForMainFrame == false && !img && problem.value.isEmpty()) problem.value = "有个文件没加载上：" + u.take(120)
                                    }
                                    // 存进手机的素材从本地给
                                    override fun shouldInterceptRequest(view: WebView?, req: WebResourceRequest?): android.webkit.WebResourceResponse? = MaterialStore.serve(req)
                                    override fun shouldOverrideUrlLoading(view: WebView?, req: WebResourceRequest?): Boolean {
                                        val u = req?.url ?: return false
                                        // 页内跳转留在页里；点到外面的链接交给浏览器
                                        if (!req.isForMainFrame || u.host == BASE_HOST) return false
                                        if (u.scheme in setOf("about", "data", "blob")) return false
                                        if (u.scheme == "http" || u.scheme == "https") InBrowser.open(u.toString())
                                        else if (u.scheme == "tel" || u.scheme == "mailto") runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                        return true
                                    }
                                }
                                web = this; webRef = this
                            }
                        },
                        update = { wv ->
                            val key = "$cur#$v"
                            wv.alpha = shown
                            wv.translationY = (1f - shown) * lift
                            if (wv.tag != key) {
                                wv.tag = key; loaded.value = false; problem.value = ""
                                // 量好尺寸再加载：页面脚本一开始读到的宽高才是真的
                                val page = if (w?.kind == "page") Kit.inject(wv.context, html) else html
                                wv.doOnLayoutReady { wv.loadDataWithBaseURL("https://$BASE_HOST/", page, "text/html", "utf-8", null) }
                            }
                        },
                        onRelease = { it.tag = null; it.stopLoading(); it.destroy(); web = null; webRef = null },
                        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars),
                    )
                    // 页面有问题：底部一条提示，点「让她修」把问题交回给她
                    if (problem.value.isNotEmpty()) Row(
                        Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(14.dp).fillMaxWidth()
                            .background(css("#22272d"), RoundedCornerShape(14.dp)).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Txt(problem.value, css("#d79a94"), 12.5.sp, Modifier.weight(1f).padding(vertical = 6.dp), lineHeight = 18.sp, maxLines = 3)
                        Pressable({ onFix("《${w?.title ?: "作品"}》打开有问题：${problem.value}。帮我看看，修好它。") }, label = "让她修") { p ->
                            Txt("让她修", Apricot.copy(alpha = if (p) .7f else 1f), 14.sp, Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
                        }
                    }
                    val fade by animateFloatAsState(if (loaded.value) 0f else 1f, tween(220), label = "load")
                    if (fade > 0f) Box(Modifier.fillMaxSize().alpha(fade).background(css("#121519")), contentAlignment = Alignment.Center) { Spinner() }
                }
            }
        }

        // 分享面板：从底下升起，点外面落回去
        val st by animateFloatAsState(if (sheet) 1f else 0f, tween(if (sheet) 300 else 220, easing = Ease), label = "sheet")
        if (st > 0f && w != null) {
            Box(Modifier.fillMaxSize().alpha(st).background(rgba(6, 9, 14, .55f)).pointerInput(Unit) { detectTapGestures { sheet = false } })
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .graphicsLayer { translationY = (1f - st) * size.height }
                    .background(css("#22272d"), RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                    .pointerInput(Unit) { detectTapGestures { } }
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(start = 10.dp, end = 10.dp, top = 12.dp, bottom = 14.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(rgba(255, 255, 255, .16f)))
                Spacer(Modifier.height(10.dp))
                val items = ArrayList<Triple<String, String, () -> Unit>>()
                items += Triple("分享网页文件", ".html，发给谁都能用浏览器打开", { shareBytes(ctx, "${w.title}.html", MaterialStore.inline(html.orEmpty(), Works.source(cur)?.let { MaterialStore.usedOf(it) }.orEmpty()).toByteArray(), "text/html") })
                items += Triple("存成 PDF", "用系统打印，选「另存为 PDF」", { web?.let { printPdf(ctx, it, w.title) }; Unit })
                if (w.kind == "slides") items += Triple("导出 PPT", ".pptx，PowerPoint 和 WPS 都能打开", {
                    Works.source(cur)?.let { o -> shareBytes(ctx, "${w.title}.pptx", Pptx.build(w.title, Works.slidesOf(o)), "application/vnd.openxmlformats-officedocument.presentationml.presentation") }; Unit
                })
                if (w.kind == "doc") items += Triple("导出 Word", ".docx，Word 和 WPS 都能打开", {
                    Works.source(cur)?.let { o -> shareBytes(ctx, "${w.title}.docx", Docx.build(w.title, o.optString("markdown")), "application/vnd.openxmlformats-officedocument.wordprocessingml.document") }; Unit
                })
                items.forEach { (name, note, act) ->
                    Pressable({ sheet = false; act() }, Modifier.fillMaxWidth(), label = name) { p ->
                        Row(
                            Modifier.fillMaxWidth().background(rgba(255, 255, 255, if (p) .07f else 0f), RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Txt(name, Page.INK, 15.sp, Modifier.weight(1f))
                            Txt(note, Page.MUTED, 12.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

private const val BASE_HOST = MaterialStore.HOST

/** 已经量好尺寸就马上执行，否则等第一次布局完再执行。 */
private fun android.view.View.doOnLayoutReady(block: () -> Unit) {
    if (isLaidOut && width > 0 && height > 0) { block(); return }
    addOnLayoutChangeListener(object : android.view.View.OnLayoutChangeListener {
        override fun onLayoutChange(v: android.view.View, l: Int, t: Int, r: Int, b: Int, l0: Int, t0: Int, r0: Int, b0: Int) {
            if (r - l <= 0 || b - t <= 0) return
            v.removeOnLayoutChangeListener(this)
            block()
        }
    })
}

/** 页面体检：尺寸为 0，或者看起来什么都没画，返回一句说明；正常返回 ok。 */
private const val PROBE = """(function(){try{
var w=innerWidth,h=innerHeight;if(!w||!h)return '页面尺寸是 0（'+w+'×'+h+'）';
var b=document.body;if(!b)return '页面没有内容';
var seen=0,all=[b].concat([].slice.call(b.querySelectorAll('*')));
for(var i=0;i<all.length&&i<800;i++){var e=all[i],r=e.getBoundingClientRect();if(r.width<2||r.height<2||r.bottom<0||r.top>h)continue;
var t=e.tagName,cs=getComputedStyle(e);if(cs.visibility=='hidden'||cs.opacity=='0'||cs.display=='none')continue;
if(t=='CANVAS'||t=='SVG'||t=='svg'||t=='IMG'||t=='VIDEO'){seen++;break}
if(cs.backgroundImage!='none'||(cs.backgroundColor&&cs.backgroundColor!='rgba(0, 0, 0, 0)')||cs.boxShadow!='none'){seen++;break}
for(var n=e.firstChild;n;n=n.nextSibling)if(n.nodeType==3&&n.textContent.trim()){seen++;break}if(seen)break}
return seen?'ok':'页面打开了，但什么也没画出来';}catch(x){return '页面体检出错：'+x.message}})()"""

/** 加载时的小圈：一段杏色弧线慢慢转。 */
@Composable
private fun Spinner() {
    val t = rememberInfiniteTransition(label = "spin")
    val a by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "a")
    Canvas(Modifier.size(26.dp)) {
        val s = 2.dp.toPx()
        drawArc(Apricot, a, 100f, false, Offset(s, s), Size(size.width - 2 * s, size.height - 2 * s), style = Stroke(s, cap = StrokeCap.Round))
    }
}

private fun shareBytes(ctx: Context, name: String, bytes: ByteArray, mime: String) {
    runCatching {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val f = File(dir, name.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
        f.writeBytes(bytes)
        val uri: Uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}

private fun printPdf(ctx: Context, web: WebView, title: String) {
    runCatching {
        val pm = ctx.getSystemService(Context.PRINT_SERVICE) as PrintManager
        pm.print(title, web.createPrintDocumentAdapter(title), PrintAttributes.Builder().build())
    }
}

/** 画板页面里的 FG.save：他勾了、拖了、改了字，存回原稿。 */
private class BoardBridge(private val id: Array<String?>) {
    @android.webkit.JavascriptInterface
    fun save(json: String, log: String) { id[0]?.let { Board.save(it, json, log) } }
}

/** 作品套件：每件网页作品打开时塞进去（assets/fgkit.js）。 */
object Kit {
    private var js: String? = null
    fun inject(ctx: Context, html: String): String {
        val k = js ?: runCatching { ctx.assets.open("fgkit.js").bufferedReader().use { it.readText() } }.getOrDefault("").also { js = it }
        if (k.isEmpty() || html.contains("id='fg-kit'") ) return html
        val tag = "<script>" + k.replace("</script", "<\\/script") + "</script>"
        Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)?.let { return html.substring(0, it.range.last + 1) + tag + html.substring(it.range.last + 1) }
        Regex("<html[^>]*>", RegexOption.IGNORE_CASE).find(html)?.let { return html.substring(0, it.range.last + 1) + "<head>" + tag + "</head>" + html.substring(it.range.last + 1) }
        Regex("<!doctype[^>]*>", RegexOption.IGNORE_CASE).find(html)?.let { return html.substring(0, it.range.last + 1) + tag + html.substring(it.range.last + 1) }
        return tag + html
    }
}

/** 作品页面里的 FGApp：外链交给内置浏览器，复制交给剪贴板。 */
private class KitBridge(private val ctx: Context) {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    @android.webkit.JavascriptInterface
    fun open(url: String) { main.post { InBrowser.open(url) } }
    @android.webkit.JavascriptInterface
    fun copy(text: String) { main.post { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("作品", text)) } }
}
