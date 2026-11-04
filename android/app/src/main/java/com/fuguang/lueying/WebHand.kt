package com.fuguang.lueying

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.os.SystemClock
import android.util.Base64
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * 她替你点网页（第 388 版，原型 https://claude.ai/artifact/7Fvq1gwHJT1bKNFSHCVkgb 第 1 版）。
 *
 * 网页从下面升起来盖住大半屏（进 decel 440ms，出 accel 280ms），顶上一行小字说她正在做什么（旧字往上淡出、新字从下面上来）。
 * 她的手指是一个杏色圆圈：移到要点的地方（520ms 减速），点下去缩一下、一圈波纹，再发一次真的触摸给网页。
 * 登录、验证码、付款：她调 web_your_turn 停手，点变绿，底下升起「到你了」，你弄好点「好了」她接着做。
 * 她从不往密码框、银行卡框里填字，也不点「支付 / 付款」按钮（这里直接拒掉，不靠她自觉）。
 * 「我来」：她停下等你（工具调用在这里等着），再点一下她接着做。「收起」：缩成聊天页底上一颗小胶囊，她在后台接着做；轮到你时自己升起来。
 * ask_choice：要你拿主意时弹选择卡（网页开着时贴在网页底下，没开时浮在聊天页底上），你点了她才往下走。
 * 登录状态靠 CookieManager，和内置浏览器同一份；关掉网页不清。每一步至少隔 0.7 秒，别太快被风控当成脚本。
 */
object WebHand {
    const val OPEN = "web_open"
    const val LOOK = "web_look"
    const val TAP = "web_tap"
    const val TYPE = "web_type"
    const val YOU = "web_your_turn"
    const val CLOSE = "web_close"
    const val ASK = "ask_choice"
    val names = setOf(OPEN, LOOK, TAP, TYPE, YOU, CLOSE, ASK)

    class Choice(val question: String, val options: List<Pair<String, String>>)

    /** 网页这一趟开着（收起了也算）。 */
    var active by mutableStateOf(false)
        private set
    /** 网页升着。 */
    var shown by mutableStateOf(false)
    var step by mutableStateOf("")
        private set
    var host by mutableStateOf("")
        internal set
    var progress by mutableIntStateOf(0)
        internal set
    /** 不是 null：到你了，写着要你做什么。 */
    var turn by mutableStateOf<String?>(null)
        private set
    /** 「我来」：她停手。 */
    var paused by mutableStateOf(false)
    var choice by mutableStateOf<Choice?>(null)
        private set
    /** 手指要去的地方（网页里的像素）；tapTick 变一次点一下。 */
    var finger by mutableStateOf<Offset?>(null)
        private set
    var tapTick by mutableIntStateOf(0)
        private set

    internal var web: WebView? = null
    private var pendingUrl: String? = null
    private var turnWait: CompletableDeferred<Boolean>? = null
    private var choiceWait: CompletableDeferred<String?>? = null
    private var lastAct = 0L
    @Volatile internal var loading = false

    private val PAY = Regex("支付|付款|付钱|买单|收银|pay", RegexOption.IGNORE_CASE)

    /* ---------- 给模型 ---------- */

    private fun obj(vararg p: Pair<String, JSONObject>) = JSONObject().put("type", "object").put("properties", JSONObject().apply { p.forEach { put(it.first, it.second) } })
    private fun str(d: String) = JSONObject().put("type", "string").put("description", d)
    private fun int(d: String) = JSONObject().put("type", "integer").put("description", d)
    private val noteP = "note" to str("这一步你在做什么，十个字以内，显示在网页顶上给对方看，比如「填出发地」「找下午的车」")

    val specs get() = listOf(
        ToolSpec(OPEN, "在 App 里打开一个网站，你接下来在上面替对方操作（订票、点单这类）。网页会升起来，对方看得到你每一步。返回这页能点、能填的东西。",
            obj("url" to str("网址，https:// 开头"), noteP).put("required", JSONArray().put("url"))),
        ToolSpec(LOOK, "重新看一遍现在这页：能点、能填的东西（带编号）和页面上的字。shot 为 true 时再附一张截图，页面复杂、看字看不明白时用。",
            obj("shot" to JSONObject().put("type", "boolean").put("description", "要不要截图"), noteP)),
        ToolSpec(TAP, "点网页上的一样东西（编号来自 web_open / web_look 的结果）。点完等页面稳下来，返回新的样子。付款、支付按钮你点不了，交给对方。",
            obj("id" to int("要点的编号"), noteP).put("required", JSONArray().put("id"))),
        ToolSpec(TYPE, "往网页的输入框里填字（编号来自 web_open / web_look）。密码、支付密码、银行卡号、验证码不能填，交给对方。",
            obj("id" to int("输入框编号"), "text" to str("要填的字"), noteP).put("required", JSONArray().put("id").put("text"))),
        ToolSpec(YOU, "停手，请对方自己做一步：登录、滑块、验证码、输密码、付款，或者你卡住了。网页会升起来提示对方，等对方点「好了」才回来，返回那时的页面。",
            obj("reason" to str("要对方做什么，一句话，比如「登录一下」「付款」")).put("required", JSONArray().put("reason"))),
        ToolSpec(ASK, "弹一张选择卡让对方挑（哪趟车、哪家店、哪个口味），对方点了才回来。选项 2 到 6 个。对方也可能一个都不选。",
            obj(
                "question" to str("问对方的话，一句"),
                "options" to JSONObject().put("type", "array").put("items", obj("label" to str("选项，短"), "note" to str("下面一行小字，可以不写")).put("required", JSONArray().put("label"))),
            ).put("required", JSONArray().put("question").put("options"))),
        ToolSpec(CLOSE, "事情办完或不办了，收起网页。", obj()),
    )

    fun system(call: String) = "你可以在 App 里打开网站替$call 办事（订火车票、点外卖这类）：$call 明确要你帮他在网上办的时候才用，别自己找事做。" +
        "先 $OPEN 打开网站，照返回的编号 $TAP / $TYPE，一步一步来，每步写 note。拿不准页面就 $LOOK。" +
        "登录、滑块、短信验证码、输密码、付款，一律用 $YOU 交给$call，你不填密码，也不点付款。" +
        "要$call 拿主意的（坐哪趟车、哪家店、几分糖），用 $ASK 弹卡片问，别自己定；$call 话里已经说清楚的就别再问。" +
        "办完 $CLOSE，回复里说清楚办成了什么、还差什么。中途打不开、被拦住了，如实说，别反复硬试。"

    fun liveTitle(n: String): String = when (n) {
        OPEN -> "正在打开网站"; LOOK -> "正在看网页"; TAP -> "正在点网页"; TYPE -> "正在填网页"; YOU -> "等你来"; ASK -> "等你挑"; else -> "收起网页"
    }
    fun doneTitle(n: String, a: JSONObject): String = when (n) {
        OPEN -> "打开了 " + (runCatching { android.net.Uri.parse(a.optString("url")).host.orEmpty().removePrefix("www.") }.getOrNull() ?: "网站")
        LOOK -> "看了网页"; TAP, TYPE -> a.optString("note").trim().ifEmpty { if (n == TAP) "点了网页" else "填了网页" }.take(16)
        YOU -> "你做了：" + a.optString("reason").trim().take(12); ASK -> "问你：" + a.optString("question").trim().take(14); else -> "收起了网页"
    }

    suspend fun run(n: String, a: JSONObject): ToolOut {
        a.optString("note").trim().takeIf { it.isNotEmpty() }?.let { step = it.take(16) }
        if (n != ASK && n != YOU) waitResume()
        return when (n) {
            OPEN -> open(a.optString("url").trim())
            LOOK -> look(a.optBoolean("shot"))
            TAP -> tap(a.optInt("id", -1))
            TYPE -> type(a.optInt("id", -1), a.optString("text"))
            YOU -> yourTurn(a.optString("reason").trim().ifEmpty { "这一步你来" })
            ASK -> ask(a)
            CLOSE -> { close(); ToolOut("网页收起了。") }
            else -> ToolOut("没有这个工具。")
        }
    }

    /* ---------- 动作 ---------- */

    private suspend fun waitResume() { while (paused) delay(150) }

    /** 每一步至少隔 0.7 秒。 */
    private suspend fun pace() {
        val gap = SystemClock.uptimeMillis() - lastAt()
        if (gap < 700) delay(700 - gap)
        lastAct = SystemClock.uptimeMillis()
    }
    private fun lastAt() = lastAct

    private suspend fun open(url: String): ToolOut {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return ToolOut("网址要 https:// 开头。")
        if (step.isEmpty()) step = "打开网站"
        withContext(Dispatchers.Main) {
            host = runCatching { android.net.Uri.parse(url).host.orEmpty().removePrefix("www.") }.getOrDefault("")
            shown = true
            if (web == null) { pendingUrl = url; active = true } else { loading = true; web?.loadUrl(url) }
        }
        // 网页窗口还没建好：等面板画出来
        withTimeoutOrNull(4000) { while (web == null) delay(80) } ?: return ToolOut("网页没打开。")
        settle()
        return look(false)
    }

    /** 等页面稳下来：最多 10 秒，再停 0.4 秒。 */
    private suspend fun settle() {
        delay(500)
        withTimeoutOrNull(10_000) { while (loading) delay(120) }
        delay(400)
    }

    private suspend fun js(code: String): Any? = withContext(Dispatchers.Main) {
        val w = web ?: return@withContext null
        suspendCancellableCoroutine<Any?> { c -> w.evaluateJavascript(code) { r -> c.resume(runCatching { JSONTokener(r ?: "null").nextValue() }.getOrNull()) } }
    }

    private suspend fun look(shot: Boolean): ToolOut {
        val raw = js(LOOK_JS) as? String ?: return ToolOut("看不到网页（可能还在打开）。可以等一下再 $LOOK。")
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return ToolOut("没读懂这页。")
        val sb = StringBuilder()
        sb.append("网页：").append(o.optString("title")).append("  ").append(o.optString("url")).append('\n')
        val items = o.optJSONArray("items") ?: JSONArray()
        if (items.length() == 0) sb.append("这一屏没有能点的东西。\n")
        else {
            sb.append("能点、能填的（[编号] 种类 文字）：\n")
            for (i in 0 until items.length()) sb.append(items.optString(i)).append('\n')
        }
        sb.append("\n页面上的字：\n").append(o.optString("text"))
        val imgs = if (shot) listOfNotNull(snapshot()) else emptyList()
        return ToolOut(sb.toString(), imgs)
    }

    private suspend fun snapshot(): ToolImage? = withContext(Dispatchers.Main) {
        val w = web ?: return@withContext null
        if (w.width <= 0 || w.height <= 0) return@withContext null
        val k = (720f / w.width).coerceAtMost(1f)
        val bm = Bitmap.createBitmap((w.width * k).toInt(), (w.height * k).toInt(), Bitmap.Config.RGB_565)
        val c = AndroidCanvas(bm); c.scale(k, k); w.draw(c)
        val out = ByteArrayOutputStream()
        bm.compress(Bitmap.CompressFormat.JPEG, 72, out); bm.recycle()
        ToolImage("image/jpeg", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
    }

    /** 把编号那样东西滚到中间，回它的样子和中心（相对网页窗口 0–1）。 */
    private suspend fun locate(id: Int): JSONObject? {
        val r = js("(function(){var e=document.querySelector('[data-fgh=\"$id\"]');if(!e)return null;e.scrollIntoView({block:'center',inline:'center'});" +
            "var b=e.getBoundingClientRect();var t=(e.innerText||e.value||e.getAttribute('aria-label')||e.getAttribute('placeholder')||'').trim().slice(0,40);" +
            "var ty=(e.getAttribute('type')||'').toLowerCase();var ac=(e.getAttribute('autocomplete')||'')+' '+(e.name||'')+' '+(e.id||'');" +
            "return JSON.stringify({x:(b.left+b.width/2)/innerWidth,y:(b.top+b.height/2)/innerHeight,t:t,ty:ty,ac:ac,tag:e.tagName});})()") as? String ?: return null
        return runCatching { JSONObject(r) }.getOrNull()
    }

    private suspend fun moveFinger(o: JSONObject): Offset? = withContext(Dispatchers.Main) {
        val w = web ?: return@withContext null
        val p = Offset((o.optDouble("x").toFloat() * w.width).coerceIn(0f, w.width.toFloat()), (o.optDouble("y").toFloat() * w.height).coerceIn(0f, w.height.toFloat()))
        finger = p
        p
    }

    /** 真的点一下：按下、松开两个触摸事件。 */
    private suspend fun touch(p: Offset) = withContext(Dispatchers.Main) {
        val w = web ?: return@withContext
        tapTick++
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, p.x, p.y, 0)
        val up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, p.x, p.y, 0)
        w.dispatchTouchEvent(down); w.dispatchTouchEvent(up)
        down.recycle(); up.recycle()
    }

    private suspend fun tap(id: Int): ToolOut {
        pace()
        val o = locate(id) ?: return ToolOut("找不到编号 $id，页面可能变了。先 $LOOK 再点。")
        if (PAY.containsMatchIn(o.optString("t"))) return ToolOut("「${o.optString("t")}」是付款，你不能点。用 $YOU 请对方自己付。")
        if (step.isEmpty()) step = "点「${o.optString("t").take(10)}」"
        delay(250) // 滚动停下
        val p = moveFinger(locate(id) ?: o) ?: return ToolOut("网页没开着。")
        delay(560)
        touch(p)
        settle()
        return look(false)
    }

    private suspend fun type(id: Int, text: String): ToolOut {
        pace()
        val o = locate(id) ?: return ToolOut("找不到编号 $id，页面可能变了。先 $LOOK 再填。")
        val ty = o.optString("ty"); val ac = o.optString("ac").lowercase()
        if (ty == "password" || "cc-" in ac || Regex("pass|pwd|cvv|cvc|card|验证码|captcha|sms").containsMatchIn(ac + o.optString("t")))
            return ToolOut("这是密码 / 卡号 / 验证码一类的框，你不能填。用 $YOU 请对方自己填。")
        delay(250)
        val p = moveFinger(locate(id) ?: o) ?: return ToolOut("网页没开着。")
        delay(560)
        touch(p)
        delay(200)
        // 一个字一个字出来（太长就一次放进去）；用原生 setter，React 这类网站才认
        val chunks = if (text.length <= 40) (1..text.length).map { text.take(it) } else listOf(text)
        for (s in chunks) {
            js("(function(){var e=document.querySelector('[data-fgh=\"$id\"]');if(!e)return;e.focus();" +
                "var pr=e.tagName=='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;var d=Object.getOwnPropertyDescriptor(pr,'value');" +
                "if(e.isContentEditable){e.innerText=${JSONObject.quote(s)};}else if(d&&d.set){d.set.call(e,${JSONObject.quote(s)});}else{e.value=${JSONObject.quote(s)};}" +
                "e.dispatchEvent(new Event('input',{bubbles:true}));})()")
            delay(80)
        }
        js("(function(){var e=document.querySelector('[data-fgh=\"$id\"]');if(e)e.dispatchEvent(new Event('change',{bubbles:true}));})()")
        settle()
        return look(false)
    }

    private suspend fun yourTurn(reason: String): ToolOut {
        val d = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {
            turnWait?.complete(false)
            turnWait = d; turn = reason; finger = null; step = "到你了"
            if (active) shown = true
        }
        // 她这一轮被停掉（对方点了停）：提示条也收起来
        val ok = try { d.await() } catch (e: kotlinx.coroutines.CancellationException) { withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { if (turnWait === d) { turn = null; turnWait = null } }; throw e }
        if (!ok) return ToolOut("对方没做这一步，把网页关了。别再接着操作，问问他怎么打算。")
        lastAct = SystemClock.uptimeMillis()
        if (!active) return ToolOut("对方说好了。")
        settle()
        return ToolOut("对方说好了。\n\n" + look(false).text)
    }

    /** 「好了」：她接着做。 */
    fun done() { turn = null; turnWait?.complete(true); turnWait = null }

    private suspend fun ask(a: JSONObject): ToolOut {
        val arr = a.optJSONArray("options") ?: JSONArray()
        val opts = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { it.optString("label").trim().take(30) to it.optString("note").trim().take(40) }.filter { it.first.isNotEmpty() }.take(6)
        if (opts.size < 2) return ToolOut("选项至少两个。")
        val d = CompletableDeferred<String?>()
        withContext(Dispatchers.Main) {
            choiceWait?.complete(null)
            choiceWait = d; choice = Choice(a.optString("question").trim().take(60), opts); finger = null
            if (active) shown = true
        }
        val r = try { d.await() } catch (e: kotlinx.coroutines.CancellationException) { withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { if (choiceWait === d) { choice = null; choiceWait = null } }; throw e }
        lastAct = SystemClock.uptimeMillis()
        return ToolOut(if (r == null) "对方一个都没选。别替他定，问问他想要什么。" else "对方选了：$r")
    }

    fun pick(label: String?) { choice = null; choiceWait?.complete(label); choiceWait = null }

    fun close() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            turnWait?.complete(false); turnWait = null
            choiceWait?.complete(null); choiceWait = null
            turn = null; choice = null; finger = null; paused = false
            shown = false; active = false; step = ""
            CookieManager.getInstance().flush()
        }
    }

    internal fun attach(w: WebView) { web = w; pendingUrl?.let { loading = true; w.loadUrl(it) }; pendingUrl = null }
    internal fun detach(w: WebView) { if (web === w) web = null }

    /** 给每样能点能填的东西编号，连同页面上的字一起拿回来。 */
    private const val LOOK_JS = """(function(){
var q='a,button,input,select,textarea,label,summary,[role=button],[role=link],[role=tab],[role=checkbox],[role=radio],[role=option],[role=menuitem],[onclick],[contenteditable=true],[tabindex]';
var els=document.querySelectorAll(q),out=[],n=0;
document.querySelectorAll('[data-fgh]').forEach(function(e){e.removeAttribute('data-fgh')});
for(var i=0;i<els.length&&n<150;i++){var e=els[i];var b=e.getBoundingClientRect();if(b.width<2||b.height<2)continue;
var s=getComputedStyle(e);if(s.visibility=='hidden'||s.display=='none'||+s.opacity<0.05)continue;
if(b.bottom<-innerHeight||b.top>innerHeight*3)continue;
var tag=e.tagName.toLowerCase(),ty=(e.getAttribute('type')||'').toLowerCase();
var t=(e.innerText||'').replace(/\s+/g,' ').trim();var lab=e.getAttribute('aria-label')||e.getAttribute('placeholder')||e.getAttribute('title')||'';
var kind=tag=='a'?'链接':tag=='input'?(ty=='checkbox'?'勾选框':ty=='radio'?'单选':ty=='password'?'密码框':ty=='submit'||ty=='button'?'按钮':'输入框'):tag=='textarea'?'输入框':tag=='select'?'下拉':tag=='label'?'标签':'按钮';
var v='';if(tag=='input'||tag=='textarea'){v=ty=='password'?'':(e.value||'');}if(tag=='select'&&e.selectedIndex>=0)v=e.options[e.selectedIndex].text;
var line=(t||lab).slice(0,50);if(!line&&!v&&kind!='输入框')continue;
n++;e.setAttribute('data-fgh',n);
var x='['+n+'] '+kind+' '+line;if(lab&&t&&lab!=t)x+='（'+lab.slice(0,20)+'）';if(v)x+=' = "'+v.slice(0,30)+'"';
if(e.checked)x+=' 已勾';if(e.disabled||e.getAttribute('aria-disabled')=='true')x+=' 不能点';if(b.top>innerHeight||b.bottom<0)x+=' （不在这一屏）';
out.push(x);}
var txt=(document.body&&document.body.innerText||'').replace(/\n\s*\n+/g,'\n').trim().slice(0,2500);
return JSON.stringify({title:document.title,url:location.href,items:out,text:txt});})()"""
}

private val HBar = Color(0xFF1E2127)
private val HGreen = Color(0xFF8FC7A8)
private val LockIc = Ic("M5 11h14v10H5zM8 11V8a4 4 0 0 1 8 0v3")

/** 她在操作的网页：从下面升起；收起时是聊天页底上一颗小胶囊；ask_choice 没开网页时也画在这层。 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebHandLayer() {
    val active = WebHand.active
    val up = active && WebHand.shown
    val t by animateFloatAsState(if (up) 1f else 0f, tween(if (up) 440 else 280, easing = if (up) Curves.decel else Curves.accel), label = "hand")
    // 轮到他、要他挑：收起着也升起来
    LaunchedEffect(WebHand.turn, WebHand.choice) { if (active && (WebHand.turn != null || WebHand.choice != null)) WebHand.shown = true }
    BackHandler(enabled = up) { WebHand.shown = false }
    var h by remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize().onSizeChanged { h = it.height }) {
        if (active) {
            // 底下的遮罩
            if (t > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = t }.background(Color.Black.copy(alpha = .4f)).pointerInput(Unit) { detectTapGestures { WebHand.shown = false } })
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(.86f)
                    .offset { IntOffset(0, ((1f - t) * h).toInt()) }
                    .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)).background(HBar)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .windowInsetsPadding(WindowInsets.navigationBars),
            ) {
                HandHead()
                Box(Modifier.weight(1f).fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp).clip(RoundedCornerShape(16.dp)).background(Color.White)) {
                    AndroidView(
                        factory = { c ->
                            WebView(c).apply {
                                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.databaseEnabled = true
                                settings.loadWithOverviewMode = true
                                settings.useWideViewPort = true
                                settings.setSupportMultipleWindows(false)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, p: Int) { WebHand.progress = p }
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) { WebHand.loading = true; url?.let { u -> WebHand.host = runCatching { android.net.Uri.parse(u).host.orEmpty().removePrefix("www.") }.getOrDefault(WebHand.host) } }
                                    override fun onPageFinished(view: WebView?, url: String?) { WebHand.loading = false }
                                    // 网站想跳 App（alipays://、weixin:// 这类）：交给系统，付款 App 能拉起来
                                    override fun shouldOverrideUrlLoading(view: WebView?, req: android.webkit.WebResourceRequest?): Boolean {
                                        val u = req?.url ?: return false
                                        if (u.scheme == "http" || u.scheme == "https") return false
                                        runCatching { view?.context?.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, u).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                        return true
                                    }
                                }
                                WebHand.attach(this)
                            }
                        },
                        onRelease = { w -> WebHand.detach(w); w.stopLoading(); w.destroy() },
                        modifier = Modifier.fillMaxSize(),
                    )
                    HandFinger()
                    // 加载：一道杏色细线
                    val pa by animateFloatAsState(if (WebHand.progress in 1..99) 1f else 0f, tween(300), label = "hp")
                    Box(Modifier.align(Alignment.TopStart).fillMaxWidth(WebHand.progress / 100f).height(2.dp).graphicsLayer { alpha = pa }.background(Skin.accent))
                    androidx.compose.animation.AnimatedVisibility(
                        WebHand.turn != null, Modifier.align(Alignment.BottomCenter),
                        enter = slideInVertically(tween(380, easing = Curves.decel)) { it } + fadeIn(tween(380, easing = Curves.decel)),
                        exit = slideOutVertically(tween(240, easing = Curves.accel)) { it } + fadeOut(tween(240, easing = Curves.accel)),
                    ) { TurnBar(WebHand.turn.orEmpty()) }
                    androidx.compose.animation.AnimatedVisibility(
                        WebHand.choice != null, Modifier.align(Alignment.BottomCenter),
                        enter = slideInVertically(tween(380, easing = Curves.decel)) { it } + fadeIn(tween(380, easing = Curves.decel)),
                        exit = slideOutVertically(tween(240, easing = Curves.accel)) { it } + fadeOut(tween(240, easing = Curves.accel)),
                    ) { WebHand.choice?.let { ChoiceCard(it) } }
                }
            }
            // 收起后的小胶囊
            androidx.compose.animation.AnimatedVisibility(
                !WebHand.shown, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp).windowInsetsPadding(WindowInsets.navigationBars),
                enter = slideInVertically(tween(320, 120, Curves.decel)) { it / 2 } + fadeIn(tween(320, 120, Curves.decel)),
                exit = fadeOut(tween(160, easing = Curves.accel)),
            ) {
                Pressable({ WebHand.shown = true }, label = "展开网页") { pr ->
                    Row(
                        Modifier.graphicsLayer { val s = if (pr) .96f else 1f; scaleX = s; scaleY = s }
                            .background(HBar, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 10.dp).widthIn(max = 280.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Pulse(WebHand.turn != null || WebHand.choice != null)
                        Spacer(Modifier.width(10.dp))
                        Txt(if (WebHand.turn != null) "到你了：" + WebHand.turn else "她在网页上：" + WebHand.step.ifEmpty { "办事中" }, Page.INK, 13.sp, maxLines = 1)
                    }
                }
            }
        } else {
            // 没开网页时的选择卡：浮在聊天页底上
            androidx.compose.animation.AnimatedVisibility(
                WebHand.choice != null, Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = 92.dp).windowInsetsPadding(WindowInsets.navigationBars),
                enter = slideInVertically(tween(380, easing = Curves.decel)) { it / 2 } + fadeIn(tween(380, easing = Curves.decel)),
                exit = slideOutVertically(tween(240, easing = Curves.accel)) { it / 2 } + fadeOut(tween(240, easing = Curves.accel)),
            ) { WebHand.choice?.let { ChoiceCard(it) } }
        }
    }
}

@Composable
private fun Pulse(you: Boolean) {
    val c = if (you) HGreen else Skin.accent
    val inf = rememberInfiniteTransition(label = "pulse")
    val k by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = Curves.decel), RepeatMode.Restart), label = "pk")
    val still = WebHand.paused
    Canvas(Modifier.size(16.dp)) {
        val r = 4.dp.toPx()
        if (!still) drawCircle(c.copy(alpha = .8f * (1f - k)), r * (1f + 1.2f * k), style = Stroke(1.5.dp.toPx()))
        drawCircle(c, r)
    }
}

@Composable
private fun HandHead() {
    Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 10.dp)) {
        Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp).background(Color.White.copy(alpha = .18f), RoundedCornerShape(2.dp)))
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pulse(WebHand.turn != null || WebHand.paused)
            Spacer(Modifier.width(8.dp))
            val s = when { WebHand.paused -> "你在操作，她等着"; WebHand.turn != null -> "到你了"; else -> WebHand.step.ifEmpty { "准备中" } }
            AnimatedContent(
                s, Modifier.weight(1f),
                transitionSpec = {
                    (slideInVertically(tween(240, 50, Curves.decel)) { it / 3 } + fadeIn(tween(240, 50, Curves.decel)))
                        .togetherWith(slideOutVertically(tween(140, easing = Curves.accel)) { -it / 3 } + fadeOut(tween(140, easing = Curves.accel)))
                },
                label = "step",
            ) { Txt(it, Page.INK, 13.5.sp, maxLines = 1) }
            HeadBtn(if (WebHand.paused) "你继续" else "我来", WebHand.paused) { WebHand.paused = !WebHand.paused }
            Spacer(Modifier.width(6.dp))
            HeadBtn("收起", false) { WebHand.shown = false }
            Spacer(Modifier.width(6.dp))
            HeadBtn("关掉", false) { WebHand.close() }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = .24f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            SvgIcon(LockIc, Page.MUTED.copy(alpha = .7f), 11.dp, stroke = 2.2f)
            Spacer(Modifier.width(6.dp))
            Txt(WebHand.host.ifEmpty { "网页" } + " · 登录和付款都由你来", Page.MUTED.copy(alpha = .8f), 11.5.sp, maxLines = 1)
        }
    }
}

@Composable
private fun HeadBtn(text: String, hot: Boolean, onClick: () -> Unit) {
    Pressable(onClick, label = text) { pr ->
        val bg = when { hot -> Skin.accent; pr -> Color.White.copy(alpha = .14f); else -> Color.White.copy(alpha = .08f) }
        Txt(text, if (hot) Skin.onAccent else Page.INK, 12.5.sp, Modifier.graphicsLayer { val s = if (pr) .95f else 1f; scaleX = s; scaleY = s }.background(bg, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 7.dp), maxLines = 1)
    }
}

/** 她的手指：杏色圆圈跟到要点的地方，点下去缩一下、一圈波纹。 */
@Composable
private fun HandFinger() {
    val target = WebHand.finger
    val d = LocalDensity.current
    val x = remember { Animatable(0f) }
    val y = remember { Animatable(0f) }
    var seen by remember { mutableStateOf(false) }
    LaunchedEffect(target) {
        val p = target ?: return@LaunchedEffect
        if (!seen) { x.snapTo(p.x); y.snapTo(p.y + with(d) { 60.dp.toPx() }); seen = true }
        launch { x.animateTo(p.x, tween(520, easing = Curves.decel)) }
        y.animateTo(p.y, tween(520, easing = Curves.decel))
    }
    val a by animateFloatAsState(if (target != null) 1f else 0f, tween(240, easing = Curves.standard), label = "fa")
    val press = remember { Animatable(0f) }
    val ripple = remember { Animatable(1f) }
    LaunchedEffect(WebHand.tapTick) {
        if (WebHand.tapTick == 0) return@LaunchedEffect
        launch { ripple.snapTo(0f); ripple.animateTo(1f, tween(460, easing = Curves.decel)) }
        press.animateTo(1f, tween(100, easing = Curves.standard)); press.animateTo(0f, tween(160, easing = Curves.standard))
    }
    if (a <= 0f) return
    val c = Skin.accent
    Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = a }) {
        val p = Offset(x.value, y.value)
        val r = 13.dp.toPx() * (1f - .3f * press.value)
        if (ripple.value < 1f) drawCircle(c.copy(alpha = .9f * (1f - ripple.value)), 22.dp.toPx() * (.3f + ripple.value), p, style = Stroke(2.dp.toPx()))
        drawCircle(c.copy(alpha = .35f), r, p)
        drawCircle(c, r, p, style = Stroke(2.dp.toPx()))
    }
}

@Composable
private fun TurnBar(text: String) {
    Row(
        Modifier.padding(10.dp).fillMaxWidth().background(Color(0xFF2C2F37), RoundedCornerShape(16.dp)).padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(HGreen, CircleShape))
        Spacer(Modifier.width(10.dp))
        Txt("到你了：$text", Page.INK, 13.sp, Modifier.weight(1f), lineHeight = 20.sp)
        Spacer(Modifier.width(8.dp))
        HeadBtn("好了", true) { WebHand.done() }
    }
}

@Composable
private fun ChoiceCard(c: WebHand.Choice) {
    Column(
        Modifier.padding(10.dp).fillMaxWidth().background(Color(0xFF25282F), RoundedCornerShape(18.dp))
            .border(1.dp, Color.White.copy(alpha = .06f), RoundedCornerShape(18.dp)).padding(start = 6.dp, end = 6.dp, top = 12.dp, bottom = 6.dp),
    ) {
        Txt(c.question, Page.INK, 14.sp, Modifier.padding(start = 10.dp, end = 10.dp, bottom = 6.dp), family = Serif, lineHeight = 21.sp)
        c.options.forEachIndexed { i, (label, note) ->
            val shown = remember(c) { Animatable(0f) }
            LaunchedEffect(c) { delay(80L + 40L * i); shown.animateTo(1f, tween(300, easing = Curves.decel)) }
            Pressable({ WebHand.pick(label) }, Modifier.fillMaxWidth().graphicsLayer { alpha = shown.value; translationY = (1f - shown.value) * 8.dp.toPx() }, label = label) { pr ->
                Row(
                    Modifier.fillMaxWidth().graphicsLayer { val s = if (pr) .98f else 1f; scaleX = s; scaleY = s }
                        .background(if (pr) Color.White.copy(alpha = .1f) else Color.Transparent, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Txt(label, Page.INK, 13.5.sp, Modifier.weight(1f), maxLines = 2)
                    if (note.isNotEmpty()) Txt(note, Page.MUTED, 12.sp, Modifier.padding(start = 8.dp).widthIn(max = 170.dp), maxLines = 2)
                }
            }
        }
        Pressable({ WebHand.pick(null) }, Modifier.align(Alignment.End), label = "都不要") { pr ->
            Txt("都不要", Page.MUTED, 12.5.sp, Modifier.background(if (pr) Color.White.copy(alpha = .08f) else Color.Transparent, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 8.dp))
        }
    }
}
