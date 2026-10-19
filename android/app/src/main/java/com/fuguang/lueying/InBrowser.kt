package com.fuguang.lueying

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import java.io.ByteArrayInputStream
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 内置浏览器：聊天里的链接、搜索出处、作品和画板里的外链，都在这里打开，不跳出 App。
 * 从下面升上来（进 decel 340ms，出 accel 220ms）；顶上关闭 · 去广告盾牌 · 标题网址 · 更多，加载时一道杏色细进度；
 * 底下后退 · 前进 · 下载 · 分享。网页里的视频能播、能全屏（横过来）。返回键先退页，退到头再关。
 */
object InBrowser {
    var url by mutableStateOf<String?>(null)
    /** 每次 open 加一：同一个网址再点也重新加载。 */
    var nonce by mutableIntStateOf(0)
    /** 「让她看看这页」：聊天页接过去。 */
    var ask: ((String) -> Unit)? = null

    fun open(u: String) {
        val t = u.trim()
        if (!t.startsWith("http://") && !t.startsWith("https://")) return
        url = t; nonce++
    }
    fun close() { url = null; Magnet.ask = null }
}

private val Warm = Color(0xFFE9B48E)
private val Bar = Color(0xFF1A1D23)
private val Ground = Color(0xFF121418)

private object BrIcons {
    val close = Ic("M6 6 18 18M6 18 18 6")
    val back = Ic("M14.5 6l-6 6 6 6")
    val fwd = Ic("M9.5 6l6 6-6 6")
    val share = Ic("M12 3.5v11M8 7.5l4-4 4 4M6 11v8.5h12V11")
    val more = Ic("M5 12h.01M12 12h.01M19 12h.01")
    val shield = Ic("M12 3l7 3v5.5c0 4.5-3 8-7 9.5-4-1.5-7-5-7-9.5V6zM9 12l2 2 4-4")
    val lock = Ic("M5 11h14v10H5zM8 11V8a4 4 0 0 1 8 0v3")
    val reload = Ic("M19 8a7.5 7.5 0 1 0 1 5M19 4v4h-4")
    val copy = Ic("M8 8h12v12H8zM16 8V5H4v12h4")
    val chat = Ic("M5 5.5h14v10H10l-4 3v-3H5Z")
    val out = Ic("M14 4h6v6M20 4l-9 9M18 14v6H4V6h6")
    val down = Ic("M12 4v11M7.5 10.5L12 15l4.5-4.5M5 19h14")
    val home = Ic("M4.5 11 12 4.5l7.5 6.5M6.5 9.5V19.5h11V9.5")
}

internal fun Context.activity(): Activity? { var c: Context? = this; while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }; return null }

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun InBrowserLayer() {
    val target = InBrowser.url
    var last by remember { mutableStateOf<String?>(null) }
    if (target != null) last = target
    val t by animateFloatAsState(if (target != null) 1f else 0f, tween(if (target != null) 340 else 220, easing = if (target != null) Curves.decel else Curves.accel), label = "browser")
    if (t <= 0f || last == null) return
    val ctx = LocalContext.current
    var web by remember { mutableStateOf<WebView?>(null) }
    var title by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var secure by remember { mutableStateOf(true) }
    var progress by remember { mutableIntStateOf(0) }
    var canBack by remember { mutableStateOf(false) }
    var canFwd by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var shieldOpen by remember { mutableStateOf(false) }
    val blocked = remember { intArrayOf(0, 0, 0) }    // 广告、跟踪、跳 App
    var blockedTick by remember { mutableIntStateOf(0) }
    var toast by remember { mutableStateOf<String?>(null) }
    var full by remember { mutableStateOf<View?>(null) }
    var fullCb by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    var dlOpen by remember { mutableStateOf(false) }
    var h by remember { mutableIntStateOf(0) }
    // 全屏：从视频原处长出来（fullFrom），退出时先播完收回的动画（exiting），网站自己退的不再回调（siteHid）
    var fullFrom by remember { mutableStateOf<Rect?>(null) }
    var exiting by remember { mutableStateOf(false) }
    var siteHid by remember { mutableStateOf(false) }
    var rootPos by remember { mutableStateOf(Offset.Zero) }
    // 主页：盖在网页上，网页的前后记录不动；homePlay 每次回到主页加一，让 Amor 浮一遍
    var home by remember { mutableStateOf(false) }
    var homePlay by remember { mutableIntStateOf(0) }
    var homeFocus by remember { mutableIntStateOf(0) }

    fun finishFull() {
        if (!siteHid) fullCb?.onCustomViewHidden()
        full = null; fullCb = null; fullFrom = null; exiting = false; siteHid = false
        ctx.activity()?.let { a ->
            a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            WindowInsetsControllerCompat(a.window, a.window.decorView).show(WindowInsetsCompat.Type.systemBars())
        }
    }
    fun exitFull() { if (full != null) exiting = true else finishFull() }
    BackHandler(enabled = target != null) {
        when {
            full != null -> exitFull()
            dlOpen -> dlOpen = false
            menu || shieldOpen -> { menu = false; shieldOpen = false }
            home -> home = false
            web?.canGoBack() == true -> web?.goBack()
            else -> InBrowser.close()
        }
    }
    LaunchedEffect(toast) { if (toast != null) { delay(1800); toast = null } }
    LaunchedEffect(Unit) { AdBlock.refresh() }

    Box(
        Modifier.fillMaxSize().onSizeChanged { h = it.height }.onGloballyPositioned { rootPos = it.positionInWindow() }
            .offset { IntOffset(0, ((1f - t) * h).toInt()) }
            .background(Ground)
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Column(Modifier.fillMaxSize()) {
            // 顶栏
            Box(Modifier.fillMaxWidth().background(Bar).windowInsetsPadding(WindowInsets.statusBars)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBtn(BrIcons.close, "关闭") { InBrowser.close() }
                    // 去广告：拦了几个
                    val on = AdBlock.on(host)
                    val total = remember(blockedTick) { blocked.sum() }
                    PressSurface({ shieldOpen = !shieldOpen; menu = false }, Modifier.height(30.dp), shape = RoundedCornerShape(15.dp), fill = if (on) Warm.copy(alpha = .1f) else Color.White.copy(alpha = .05f), pressScale = .92f, label = "去广告") {
                        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            SvgIcon(BrIcons.shield, if (on) Warm else Page.muted, 13.dp, stroke = 2f)
                            Spacer(Modifier.width(4.dp))
                            Txt(if (on) "$total" else "关", if (on) Warm else Page.muted, 11.5.sp, maxLines = 1)
                        }
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Txt(if (home) "主页" else title.ifBlank { "正在打开…" }, Page.ink, 14.sp, maxLines = 1)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (secure) { SvgIcon(BrIcons.lock, Page.muted.copy(alpha = .6f), 10.dp, stroke = 2.2f); Spacer(Modifier.width(3.dp)) }
                            Txt(if (home) "Amor" else host, Page.muted.copy(alpha = .6f), 11.sp, maxLines = 1)
                        }
                    }
                    IconBtn(BrIcons.more, "更多") { menu = !menu; shieldOpen = false }
                }
                // 加载进度：一道杏色细线，到头淡掉
                val pa by animateFloatAsState(if (progress in 1..99) 1f else 0f, tween(300), label = "pa")
                val pw by animateFloatAsState(progress / 100f, tween(260, easing = Curves.decel), label = "pw")
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(pw).height(2.dp).graphicsLayer { alpha = pa }.background(Warm))
            }
            // 网页
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    factory = { c ->
                        WebView(c).apply {
                            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            setBackgroundColor(0xFF121418.toInt())
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            settings.setSupportMultipleWindows(false)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, p: Int) { progress = p }
                                override fun onReceivedTitle(view: WebView?, s: String?) { title = s.orEmpty() }
                                // 视频全屏：视频脱出来铺满、横过来、收起状态栏
                                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                                    if (view == null) return
                                    fullCb = callback; siteHid = false; exiting = false
                                    // 先问视频在网页里的位置，从那里长出来；200ms 没回就从中间长
                                    val w = this@apply
                                    var placed = false
                                    val go = { rr: Rect? -> if (!placed) { placed = true; fullFrom = rr; full = view } }
                                    w.evaluateJavascript(VideoCtl.LAST) { s -> go(lastRect(w, s, rootPos)) }
                                    w.postDelayed({ go(null) }, 200)
                                    c.activity()?.let { a ->
                                        WindowInsetsControllerCompat(a.window, a.window.decorView).apply {
                                            hide(WindowInsetsCompat.Type.systemBars())
                                            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                                        }
                                    }
                                }
                                override fun onHideCustomView() { siteHid = true; exitFull() }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, u: String?, favicon: Bitmap?) {
                                    val uri = Uri.parse(u.orEmpty())
                                    host = uri.host.orEmpty().removePrefix("www."); secure = uri.scheme == "https"
                                    blocked.fill(0); blockedTick++
                                    Sniffer.reset(u.orEmpty())
                                }
                                override fun onPageFinished(view: WebView?, u: String?) {
                                    canBack = view?.canGoBack() == true; canFwd = view?.canGoForward() == true
                                    if (AdBlock.on(host)) view?.evaluateJavascript(cosmeticJs(), null)
                                    view?.evaluateJavascript(Sniffer.PROBE) { r -> Sniffer.fromPage(r, u.orEmpty()) }
                                    view?.evaluateJavascript(VideoTouch.JS, null)
                                }
                                override fun doUpdateVisitedHistory(view: WebView?, u: String?, reload: Boolean) {
                                    canBack = view?.canGoBack() == true; canFwd = view?.canGoForward() == true
                                    Uri.parse(u.orEmpty()).host?.let { host = it.removePrefix("www.") }
                                }
                                override fun shouldInterceptRequest(view: WebView?, req: WebResourceRequest?): WebResourceResponse? {
                                    val r = req ?: return null
                                    val u = r.url.toString()
                                    Sniffer.saw(u, r.requestHeaders.orEmpty(), null)
                                    if (r.isForMainFrame) return null
                                    if (AdBlock.blocks(u, host)) {
                                        val k = if (Regex("analytics|stat|track|log\\.|beacon|mmstat|cnzz|hm\\.baidu|tagmanager|clarity|hotjar|segment|mixpanel").containsMatchIn(u)) 1 else 0
                                        blocked[k]++
                                        view?.post { blockedTick++ }
                                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                                    }
                                    return null
                                }
                                override fun shouldOverrideUrlLoading(view: WebView?, req: WebResourceRequest?): Boolean {
                                    val u = req?.url ?: return false
                                    val s = u.scheme?.lowercase()
                                    if (s == "http" || s == "https") return false
                                    // 磁力链接：不当成跳 App 拦掉，弹出磁力下载
                                    if (s == "magnet") { Magnet.ask = Magnet.Ask(u.toString(), view?.url.orEmpty()); return true }
                                    if (s == "tel" || s == "mailto" || s == "sms") { runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; return true }
                                    // 跳别的 App、跳应用商店：拦下，数一个
                                    if (AdBlock.isAppJump(s)) { blocked[2]++; blockedTick++; toast = "拦下了一次跳转到别的 App"; return true }
                                    return false
                                }
                            }
                            // 点到的是文件（视频、安装包、压缩包……）：认成一个可下载的东西，弹出下载
                            setDownloadListener { u, _, cd, mime, len ->
                                if (Torrent.isTorrent(u, mime)) { Magnet.ask = Magnet.Ask(u, url.orEmpty()); return@setDownloadListener }
                                Sniffer.direct(u, mime, len, cd, url); dlOpen = true
                            }
                            web = this
                        }
                    },
                    update = { wv ->
                        val key = "${InBrowser.nonce}"
                        if (wv.tag != key && target != null) { wv.tag = key; title = ""; progress = 1; wv.loadUrl(target) }
                    },
                    onRelease = { it.stopLoading(); it.destroy(); web = null },
                    modifier = Modifier.fillMaxSize(),
                )
                // 认出视频了：右下角浮一个下载钮
                val vids = Sniffer.found.size
                // 视频网站（yt-dlp 认得的那些）不等嗅探，直接给下载钮
                val vsite = Regex("(^|\\.)(bilibili\\.com|b23\\.tv|youtube\\.com|youtu\\.be|douyin\\.com|weibo\\.(com|cn)|xiaohongshu\\.com|ixigua\\.com|kuaishou\\.com|acfun\\.cn|twitter\\.com|x\\.com|vimeo\\.com|tiktok\\.com|instagram\\.com|zhihu\\.com|youku\\.com|iqiyi\\.com|v\\.qq\\.com|mgtv\\.com|huya\\.com|douyu\\.com)$").containsMatchIn(host)
                val dv by animateFloatAsState(if (vids > 0 || vsite) 1f else 0f, tween(320, easing = Curves.decel), label = "dl")
                if (dv > 0f) PressSurface({ dlOpen = true }, Modifier.align(Alignment.BottomEnd).padding(14.dp).graphicsLayer { alpha = dv; val s = .6f + .4f * dv; scaleX = s; scaleY = s }.height(40.dp),
                    shape = RoundedCornerShape(20.dp), fill = Color(0xE6262A32), pressScale = .92f, label = "下载视频") {
                    Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        SvgIcon(BrIcons.down, Warm, 16.dp, stroke = 2f); Spacer(Modifier.width(6.dp))
                        Txt(if (vids <= 1) "下载视频" else "下载 · $vids 个", Page.ink, 13.sp, maxLines = 1)
                    }
                }
                // 主页盖在网页上：淡入淡出 260ms；搜或开网址就回到网页
                val ha by animateFloatAsState(if (home) 1f else 0f, tween(260, easing = Curves.standard), label = "home")
                if (ha > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = ha; val sc = .985f + .015f * ha; scaleX = sc; scaleY = sc }.pointerInput(Unit) { detectTapGestures { } }) {
                    BrowserHome(homePlay, homeFocus) { u -> home = false; title = ""; progress = 1; web?.loadUrl(u) }
                }
            }
            // 底栏
            Row(Modifier.fillMaxWidth().background(Bar).windowInsetsPadding(WindowInsets.navigationBars).padding(vertical = 2.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceAround) {
                IconBtn(BrIcons.back, "后退", canBack || home) { if (home) home = false else web?.goBack() }
                // 主页：在主页时亮杏色；已经在主页再点，光标进搜索框
                IconBtn(BrIcons.home, "主页", tint = if (home) Warm else null) { if (home) homeFocus++ else { home = true; homePlay++; menu = false; shieldOpen = false } }
                IconBtn(BrIcons.fwd, "前进", canFwd && !home) { web?.goForward() }
                Box {
                    IconBtn(BrIcons.down, "下载") { Downloads.open = true }
                    val n = Downloads.running
                    if (n > 0) Txt("$n", Color(0xFF2A211B), 10.sp, Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp).background(Warm, CircleShape).padding(horizontal = 5.dp), maxLines = 1)
                }
                IconBtn(BrIcons.share, "分享") {
                    val u = web?.url ?: target ?: return@IconBtn
                    runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, (title.takeIf { it.isNotBlank() }?.let { "$it\n" } ?: "") + u), "分享").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
        }

        // 盾牌卡片：这页拦了什么、这个网站要不要拦
        Pop(shieldOpen, Alignment.TopStart) {
            val tick = blockedTick
            Txt("这页拦掉了 ${blocked.sum()} 个", Page.ink, 15.sp)
            Txt("只拦广告和跟踪，不拦网页本身。", Page.muted, 12.5.sp, Modifier.padding(top = 2.dp, bottom = 10.dp))
            listOf("横幅、浮窗广告" to blocked[0], "跟踪你的统计代码" to blocked[1], "跳别的 App、应用商店" to blocked[2]).forEach { (a, b) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) { Txt(a, Page.ink.copy(alpha = .86f), 13.sp, Modifier.weight(1f)); Txt("$b", Warm, 13.sp) }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Txt("在 ${host.ifBlank { "这个网站" }} 去广告", Page.ink, 13.5.sp, Modifier.weight(1f), maxLines = 1)
                CardSwitch(AdBlock.on(host), "在这个网站去广告") { AdBlock.allow(host, it); shieldOpen = false; toast = if (it) "这个网站重新去广告" else "这个网站不拦了"; web?.reload() }
            }
        }
        // 更多
        Pop(menu, Alignment.TopEnd) {
            val u = web?.url ?: target.orEmpty()
            MenuItem(BrIcons.reload, "刷新") { menu = false; web?.reload() }
            MenuItem(BrIcons.copy, "复制链接") {
                menu = false
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("链接", u)); toast = "链接已复制"
            }
            if (InBrowser.ask != null) MenuItem(BrIcons.chat, "让她看看这页") {
                menu = false
                val say = "帮我看看这页：" + (title.takeIf { it.isNotBlank() }?.let { "《$it》" } ?: "") + u
                // 先把这页的正文和素材留一份（登录后才看得到的也在），她打开时直接用
                val wv = web
                if (wv == null) { InBrowser.close(); InBrowser.ask?.invoke(say) } else PageGrab.remember(wv) { InBrowser.close(); InBrowser.ask?.invoke(say) }
            }
            MenuItem(BrIcons.out, "用系统浏览器打开") { menu = false; runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        }

        // 小提示
        val ta by animateFloatAsState(if (toast != null) 1f else 0f, tween(240), label = "toast")
        var lastToast by remember { mutableStateOf("") }
        toast?.let { lastToast = it }
        if (ta > 0f) Txt(lastToast, Page.ink, 13.sp, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 70.dp)
            .graphicsLayer { alpha = ta; translationY = (1f - ta) * 10.dp.toPx() }.background(Color(0xFF3A404C), RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 9.dp), maxLines = 1)

        // 选清晰度
        DownloadSheet(dlOpen, title, web?.url ?: target.orEmpty()) { dlOpen = false; if (it.isNotEmpty()) toast = it }

        // 磁力 / 种子
        MagnetSheet { toast = it }

        // 视频全屏
        full?.let { v ->
            FullPlayer(v, web, title, fullFrom, exiting, onExit = { exitFull() }, onDownload = { exitFull(); dlOpen = true }, onDone = { finishFull() })
        }
    }
}

@Composable
private fun IconBtn(icon: Ic, label: String, enabled: Boolean = true, tint: Color? = null, onClick: () -> Unit) {
    PressSurface(onClick, Modifier.size(44.dp), shape = CircleShape, fill = Color.Transparent, pressScale = .88f, enabled = enabled, label = label) {
        SvgIcon(icon, tint ?: Page.ink.copy(alpha = if (enabled) .9f else .3f), 20.dp, stroke = 1.8f)
    }
}

/** 从角上长出来的小卡片。 */
@Composable
private fun Pop(open: Boolean, align: Alignment, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val k by animateFloatAsState(if (open) 1f else 0f, tween(if (open) 260 else 160, easing = if (open) Curves.decel else Curves.accel), label = "pop")
    if (k <= 0f) return
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).padding(top = 48.dp, start = 8.dp, end = 8.dp), contentAlignment = align) {
        Column(
            Modifier.widthIn(max = 300.dp).fillMaxWidth(if (align == Alignment.TopEnd) .62f else 1f)
                .graphicsLayer { alpha = k; val s = .92f + .08f * k; scaleX = s; scaleY = s; transformOrigin = TransformOrigin(if (align == Alignment.TopEnd) 1f else 0f, 0f) }
                .background(Color(0xFF262A32), RoundedCornerShape(16.dp)).padding(if (align == Alignment.TopEnd) 6.dp else 16.dp),
            content = content,
        )
    }
}

@Composable
private fun MenuItem(icon: Ic, text: String, onClick: () -> Unit) {
    PressSurface(onClick, Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), fill = Color.Transparent, pressScale = .98f, label = text) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            SvgIcon(icon, Page.ink.copy(alpha = .8f), 16.dp, stroke = 1.7f); Spacer(Modifier.width(10.dp)); Txt(text, Page.ink, 14.sp, maxLines = 1)
        }
    }
}

private fun cosmeticJs(): String {
    val css = AdBlock.COSMETIC.replace("\\", "\\\\").replace("`", "\\`").replace("\n", " ")
    return "(function(){if(document.getElementById('fg-ab'))return;var s=document.createElement('style');s.id='fg-ab';s.textContent=`$css`;(document.head||document.documentElement).appendChild(s)})()"
}

/** 最近点到的视频在网页里的位置（CSS 像素）换成这一层里的像素。 */
private fun lastRect(w: WebView, s: String?, root: Offset): Rect? = runCatching {
    val o = JSONObject(JSONTokener(s).nextValue() as String)
    @Suppress("DEPRECATION") val k = w.scale
    val loc = IntArray(2).also { w.getLocationInWindow(it) }
    val l = o.getDouble("l").toFloat() * k + loc[0] - root.x
    val t = o.getDouble("t").toFloat() * k + loc[1] - root.y
    Rect(l, t, l + o.getDouble("w").toFloat() * k, t + o.getDouble("h").toFloat() * k)
}.getOrNull()
