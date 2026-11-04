package com.fuguang.lueying

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 选择模型面板（第 385 版，原型 https://claude.ai/artifact/8XPa85PfhoFS5TkHg5VBdM 第 6 版，方案 B + 图标方案 1）。
 * 用户：原来「最近用过」+ 各家分组卡太乱。现在两页：
 * - 第一页：标题就是供应商（logo + 小字「选择模型」+ 名字 + ⌄），下面只列这一家的主型号，型号用等宽字。没有 ✕、没有「更多」、和底下深度思考之间没有线。
 * - 点标题滑到第二页「所有模型」：每家一行（logo、名字、个数、正在用的型号），找型号的框在这一页；点一家滑回第一页换成那一家，点型号才真正切过去。
 * 日期快照、qwen2 这类老型号第一页不列，第二页搜得到。不能聊天的型号（语音、向量、画图…）照旧不列。
 */
object ModelSort {
    /** 不能拿来聊天的型号。 */
    private val noChat = Regex("embed|asr|tts|audio|realtime|image|wanx|-mt-|-mt$|livetranslate|ocr|paraformer|cosyvoice|sambert|rerank|speech|whisper|dall-e|moderation|text-to")
    /** 日期快照的尾巴：-0107、-1201、-2025-04-28。 */
    private val snap = Regex("-(\\d{4}-\\d{2}-\\d{2}|\\d{4})$")
    /** 老一代的型号。 */
    private val old = Regex("^qwen(1\\.5|2)-|^qwen-\\d+(\\.\\d+)?b-")

    fun chat(m: String) = !noChat.containsMatchIn(m.lowercase())
    fun base(m: String) = m.replace(snap, "")
    fun isOld(m: String) = old.containsMatchIn(m.lowercase())

    /** 一个型号和它的快照：lead 是露在外面的那一行。 */
    class Family(val lead: String, val snaps: List<String>)

    fun families(models: List<String>): List<Family> {
        val map = LinkedHashMap<String, MutableList<String>>()
        models.forEach { map.getOrPut(base(it)) { mutableListOf() }.add(it) }
        return map.map { (b, all) -> val lead = if (b in all) b else all.first(); Family(lead, all - lead) }
    }

    /** 第一页列的：主型号，去掉快照和老型号；全是老型号的那家就全列。正在用的不在里面也补上。 */
    fun home(models: List<String>, active: String?): List<String> {
        val chat = models.filter { chat(it) }
        val leads = families(chat).map { it.lead }.filterNot { isOld(it) }.ifEmpty { chat }
        return if (active != null && active.isNotEmpty() && active in models && active !in leads) leads + active else leads
    }
}

private val decel = CubicBezierEasing(.05f, .7f, .1f, 1f)
private val accel = CubicBezierEasing(.3f, 0f, .8f, .15f)
private val chevron = Ic("M9.5 6l6 6-6 6")
private val caret = Ic("M6 9.5l6 6 6-6")
private val backArrow = Ic("M14.5 6l-6 6 6 6")
private val tick = Ic("M5 12.5l4.5 4.5L19 7.5")
private val magnifier = Ic("M16 16l4 4", circles = listOf(floatArrayOf(11f, 11f, 6.5f)))
private val Mono = FontFamily.Monospace

/**
 * 各家的 logo（res/drawable-nodpi/logo_*.png，192px 透明底，取自 lobehub/lobe-icons，MIT）。
 * OpenAI、Grok 牌子本身是黑白的：深色主题用白的，白色主题用黑的。没有 logo 的用原来的字。
 */
private fun logoRes(id: String): Int = when (id) {
    "deepseek" -> R.drawable.logo_deepseek
    "claude" -> R.drawable.logo_claude
    "gemini" -> R.drawable.logo_gemini
    "openai" -> if (Skin.light) R.drawable.logo_openai_light else R.drawable.logo_openai
    "grok" -> if (Skin.light) R.drawable.logo_grok_light else R.drawable.logo_grok
    "kimi" -> R.drawable.logo_kimi
    "siliconflow" -> R.drawable.logo_siliconflow
    "qwen" -> R.drawable.logo_qwen
    else -> 0
}

private val gray = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/** 一家的头像：有 logo 放 logo（淡色圆底），没有放字；dim = 还没填 Key，灰掉调淡。 */
@Composable
fun ProviderLogo(id: String, letter: String, size: Dp, dim: Boolean = false) {
    val res = logoRes(id)
    Box(
        Modifier.size(size).background(if (res != 0) veil(.07f) else Skin.accent.copy(alpha = if (dim) .08f else .14f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (res != 0) Image(painterResource(res), null, Modifier.size(size * .62f).alpha(if (dim) .45f else 1f), colorFilter = if (dim) gray else null)
        else Txt(letter, if (dim) Page.muted else Page.accent, (size.value * .43f).sp, weight = FontWeight.Medium)
    }
}

/**
 * 面板的上半截（把手下面到深度思考上面）：两页左右滑。
 * 进第二页：新的从右边 320ms 减速滑进来，旧的往左挪四分之一淡掉；回来反过来，走的那页 240ms 加速。高度跟着 320ms 减速变。
 */
@Composable
fun ModelPages(
    store: AssistantStore, more: Boolean, view: String, find: String,
    onFind: (String) -> Unit, onMore: (Boolean) -> Unit, onView: (String) -> Unit,
    onPick: (String, String) -> Unit, onSetup: (String?) -> Unit, modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = more,
        transitionSpec = {
            val spec = if (targetState) {
                (slideInHorizontally(tween(320, easing = decel)) { it } + fadeIn(tween(240))) togetherWith
                    (slideOutHorizontally(tween(320, easing = decel)) { -it / 4 } + fadeOut(tween(180)))
            } else {
                (slideInHorizontally(tween(320, easing = decel)) { -it / 4 } + fadeIn(tween(240))) togetherWith
                    (slideOutHorizontally(tween(240, easing = accel)) { it } + fadeOut(tween(180)))
            }
            spec.using(SizeTransform(clip = true) { _, _ -> tween(320, easing = decel) })
        },
        modifier = modifier,
        label = "pages",
    ) { b ->
        Column {
            if (!b) {
                Box(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
                    ModelTitle(store, view) { onMore(true) }
                }
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) { ModelHome(store, view, onPick) }
            } else {
                Column(Modifier.padding(start = 14.dp, end = 20.dp, top = 6.dp, bottom = 6.dp)) {
                    Box(Modifier.fillMaxWidth()) {
                        Pressable({ onMore(false) }, Modifier.align(Alignment.CenterStart), label = "返回") { _ ->
                            SvgIcon(backArrow, Page.ink, 20.dp, modifier = Modifier.padding(8.dp))
                        }
                        Txt("所有模型", Page.ink, 16.sp, Modifier.align(Alignment.Center), weight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.padding(start = 6.dp)) { ModelFind(find, onFind) }
                }
                Column(
                    Modifier.weight(1f, fill = false)
                        .animateContentSize(tween(320, easing = decel))
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 10.dp),
                ) {
                    if (find.isBlank()) ProviderList(store, onGo = { onView(it); onMore(false) }, onSetup = onSetup)
                    else ModelSearch(store, find, onPick, onSetup)
                }
            }
        }
    }
}

/** 第一页的标题：logo、小字「选择模型」、供应商名、⌄；点一下去所有模型。 */
@Composable
private fun ModelTitle(store: AssistantStore, pid: String, onClick: () -> Unit) {
    val p = provider(pid)
    val name = store.config(pid).name
    Pressable(onClick, label = "$name，看所有模型") { pressed ->
        val sc by animateFloatAsState(if (pressed) .97f else 1f, tween(if (pressed) 80 else 200), label = "title")
        Row(
            Modifier.graphicsLayer { scaleX = sc; scaleY = sc }
                .background(if (pressed) veil(.07f) else Color.Transparent, RoundedCornerShape(14.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ProviderLogo(pid, p.letter, 30.dp)
            Column {
                Txt("选择模型", Page.muted, 10.sp, spacing = ls(2f, 10f))
                Txt(name, Page.ink, 18.sp, weight = FontWeight.SemiBold, maxLines = 1)
            }
            SvgIcon(caret, Page.muted, 14.dp)
        }
    }
}

/** 第一页的型号：这一家的主型号。 */
@Composable
private fun ModelHome(store: AssistantStore, pid: String, onPick: (String, String) -> Unit) {
    val c = store.config(pid)
    val cur = if (store.activeProvider == pid) store.activeModel else null
    val ms = ModelSort.home(c.models, cur)
    if (ms.isEmpty()) {
        Txt(
            if (!c.configured) "这一家还没填 Key，点上面的名字去所有模型里填" else "这一家还没有型号，去它的配置里点「获取模型」",
            Page.muted, 13.sp, Modifier.fillMaxWidth().padding(vertical = 20.dp), align = TextAlign.Center,
        )
        return
    }
    ms.forEach { m -> PickRow(m, tags(pid, m), sel = m == cur, q = "", mono = true) { onPick(pid, m) } }
}

/** 第二页：每家一行；还没填 Key 的排在后面。 */
@Composable
private fun ProviderList(store: AssistantStore, onGo: (String) -> Unit, onSetup: (String?) -> Unit) {
    val ready = store.ready()
    val todo = store.ordered().filter { !store.config(it.id).configured }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ready.forEach { p ->
            val c = store.config(p.id)
            val cur = store.activeProvider == p.id
            PressSurface({ onGo(p.id) }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), fill = veil(.05f), pressedFill = veil(.09f), pressScale = .985f, label = c.name) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ProviderLogo(p.id, p.letter, 32.dp)
                    Txt(c.name, Page.ink, 14.5.sp, Modifier.weight(1f, fill = false), weight = FontWeight.Medium, maxLines = 1)
                    Txt(c.models.count { ModelSort.chat(it) }.toString(), Page.muted, 11.sp, Modifier.background(veil(.06f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp))
                    Spacer(Modifier.weight(1f))
                    if (cur) Txt(store.activeModel, Page.accent, 12.sp, Modifier.weight(1f, fill = false), family = Mono, maxLines = 1)
                    SvgIcon(chevron, Page.muted, 14.dp)
                }
            }
        }
    }
    if (todo.isNotEmpty()) {
        Cap("还没填 KEY")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            todo.forEach { p ->
                val c = store.config(p.id)
                Row(
                    Modifier.fillMaxWidth().background(veil(.025f), RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ProviderLogo(p.id, p.letter, 32.dp, dim = true)
                    Txt(c.name, Page.muted, 14.5.sp, Modifier.weight(1f), maxLines = 1)
                    KeyButton { onSetup(p.id) }
                }
            }
        }
    }
}

/** 顶上的搜索框（在第二页）。 */
@Composable
fun ModelFind(q: String, onChange: (String) -> Unit) {
    BasicTextField(
        q, onChange, singleLine = true,
        textStyle = TextStyle(fontFamily = Sans, color = Page.ink, fontSize = 14.5.sp),
        cursorBrush = SolidColor(Page.accent),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Row(Modifier.fillMaxWidth().height(42.dp).background(veil(.06f), RoundedCornerShape(14.dp)).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                SvgIcon(magnifier, Page.muted, 16.dp)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (q.isEmpty()) Txt("找型号或哪一家，比如 max、grok、千问", Page.muted.copy(alpha = .6f), 14.5.sp, maxLines = 1)
                    inner()
                }
                if (q.isNotEmpty()) Pressable({ onChange("") }, label = "清除") { _ -> Txt("清除", Page.muted, 13.sp, Modifier.padding(6.dp)) }
            }
        },
    )
}

/** 第二页找型号：对上名字的那家全列，否则列对上的型号（快照、老型号也在）；没填 Key 的灰着，点了去填。 */
@Composable
private fun ModelSearch(store: AssistantStore, q: String, onPick: (String, String) -> Unit, onSetup: (String?) -> Unit) {
    val query = q.trim().lowercase()
    val nameHit = { p: Provider -> store.config(p.id).name.lowercase().contains(query) || p.id.contains(query) || p.note.lowercase().contains(query) }
    var any = false
    store.ordered().forEach { p ->
        val c = store.config(p.id)
        val ms = if (nameHit(p)) c.models else c.models.filter { query in it.lowercase() }
        if (!nameHit(p) && ms.isEmpty()) return@forEach
        any = true
        val ready = c.configured
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProviderLogo(p.id, p.letter, 22.dp, dim = !ready)
            Hi(c.name, query, if (ready) Page.muted else Page.muted.copy(alpha = .7f), 12.5.sp, Modifier.weight(1f))
            if (!ready) KeyButton { onSetup(p.id) }
        }
        if (ready && ms.isEmpty()) Txt("这一家还没有型号", Page.muted.copy(alpha = .75f), 12.sp, Modifier.padding(start = 12.dp, bottom = 4.dp))
        if (!ready && ms.isEmpty()) Txt("填好 Key 后点「获取模型」拉型号", Page.muted.copy(alpha = .75f), 12.sp, Modifier.padding(start = 12.dp, bottom = 4.dp))
        ms.forEach { m ->
            val sel = store.activeProvider == p.id && store.activeModel == m
            if (ready) PickRow(m, tags(p.id, m), sel, q = query, mono = true) { onPick(p.id, m) }
            else PickRow(m, "", sel = false, dim = true, q = query, mono = true) { onSetup(p.id) }
        }
    }
    if (!any) Txt("没有叫「$q」的型号，也没有这一家", Page.muted, 13.sp, Modifier.fillMaxWidth().padding(vertical = 24.dp), align = TextAlign.Center)
}

@Composable
private fun KeyButton(onClick: () -> Unit) = PressSurface(onClick, shape = RoundedCornerShape(50), fill = Skin.accent.copy(alpha = .9f), pressScale = .94f, label = "去填 Key") {
    Txt("去填 Key", Skin.onAccent, 12.sp, Modifier.padding(horizontal = 11.dp, vertical = 5.dp))
}

/** 一行字，对上找的那几个字标杏色。 */
@Composable
private fun Hi(text: String, q: String, ink: Color, size: TextUnit, modifier: Modifier = Modifier, weight: FontWeight = FontWeight.Normal, family: FontFamily = Sans) {
    val i = if (q.isEmpty()) -1 else text.lowercase().indexOf(q)
    BasicText(
        buildAnnotatedString {
            if (i < 0) append(text)
            else { append(text.substring(0, i)); withStyle(SpanStyle(color = Page.accent)) { append(text.substring(i, i + q.length)) }; append(text.substring(i + q.length)) }
        },
        modifier, style = TextStyle(fontFamily = family, color = ink, fontSize = size, fontWeight = weight), maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

private fun tags(pid: String, m: String): String =
    (if (Attach.vision(pid, m)) "图" else "") + (if (QwenSearch.supports(pid, m)) "搜" else "")

@Composable
private fun Cap(text: String) = Txt(text, Page.muted, 10.sp, Modifier.padding(start = 6.dp, top = 16.dp, bottom = 8.dp), spacing = ls(2.5f, 10f))

/** 一行型号：选中淡暖色块 + 勾；tags 是「图」「搜」；mono = 型号用等宽字。 */
@Composable
private fun PickRow(text: String, tags: String, sel: Boolean, dim: Boolean = false, q: String, mono: Boolean = false, onClick: () -> Unit) {
    Pressable(onClick, Modifier.fillMaxWidth(), label = text) { pressed ->
        Row(
            Modifier.fillMaxWidth()
                .background(when { sel -> Skin.accent.copy(alpha = .16f); pressed -> veil(.05f); else -> Color.Transparent }, RoundedCornerShape(14.dp))
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val ink = if (sel) Page.ink else if (dim) Page.muted else Page.ink.copy(alpha = .78f)
            Hi(text, q, ink, if (mono) 14.5.sp else 15.sp, Modifier.weight(1f), family = if (mono) Mono else Sans)
            tags.forEach { t ->
                val warm = t == '搜'
                Txt(t.toString(), if (warm) Page.accent else Page.muted, 10.sp,
                    Modifier.background(if (warm) Skin.accent.copy(alpha = .16f) else veil(.06f), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 2.dp))
            }
            if (sel) SvgIcon(tick, Skin.accent.copy(alpha = 1f), 18.dp, stroke = 1.8f)
        }
    }
}
