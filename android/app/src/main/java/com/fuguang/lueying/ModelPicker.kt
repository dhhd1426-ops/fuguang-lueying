package com.fuguang.lueying

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 选择模型面板的列表（原型 https://claude.ai/artifact/Bcgv6YLWtxKAqbXcTWkakn 第 3 版）。
 * 千问「获取模型」一下拉进来上百个，原来全摊在面板里，思考档和搜索开关要翻到底才看得到。现在：
 * - 最上面「最近用过」三个（跨供应商）；
 * - 按供应商分组，当前那家展开，别的收起只露名字和个数（收起的那家正在用，标题上写型号）；
 * - 同一型号的日期快照收在它下面「+N 个快照」；qwen1.5、qwen2、qwen-7b 这类老型号收进组底「更早的型号」；
 * - 语音、向量、画图、翻译这类不能聊天的不列（配置里还在），组底写一句「另有 N 个…没列」；
 * - 「图」= 能看图，「搜」= 千问自带搜索；顶上搜索框找型号，找的时候分层全摊开、对上的字标杏色。
 * 思考档、搜索开关、更多模型钉在面板底下，不跟着列表滚（在 Chat.kt 的 ModelDrawer）。
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
}

private val decel = CubicBezierEasing(.05f, .7f, .1f, 1f)
private val accel = CubicBezierEasing(.3f, 0f, .8f, .15f)
private val chevron = Ic("M9.5 6l6 6-6 6")
private val magnifier = Ic("M16 16l4 4", circles = listOf(floatArrayOf(11f, 11f, 6.5f)))

@Composable
private fun Fold(open: Boolean, content: @Composable () -> Unit) = AnimatedVisibility(
    open,
    enter = expandVertically(tween(320, easing = decel)) + fadeIn(tween(240)),
    exit = shrinkVertically(tween(220, easing = accel)) + fadeOut(tween(160)),
) { Column { content() } }

/** 顶上的搜索框。 */
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

/**
 * 列表本体（放在可滚的那一段里）。第 4 版（同一原型第 4 版）：每家一张卡片（头像字 + 名字 + 个数），展开时型号收在卡片里、
 * 和标题隔一道线；「最近用过」没有卡片底；快照、更早的型号小一号、淡一点、左边一道细线。
 * 还没填 Key 的几家：平时在最下面虚线框里；找的时候对上名字或型号就列出来（灰的）+「去填 Key」。onSetup(供应商) 打开它的配置。
 */
@Composable
fun ModelList(store: AssistantStore, q: String, onPick: (String, String) -> Unit, onSetup: (String?) -> Unit) {
    val ready = store.ready()
    val todo = store.ordered().filter { !store.config(it.id).configured }
    val opened = remember { mutableStateMapOf<String, Boolean>() }
    val snapsOpen = remember { mutableStateMapOf<String, Boolean>() }
    val oldOpen = remember { mutableStateMapOf<String, Boolean>() }
    val query = q.trim().lowercase()
    val nameHit = { p: Provider -> query.isNotEmpty() && (store.config(p.id).name.lowercase().contains(query) || p.id.contains(query) || p.note.lowercase().contains(query)) }
    val isSel = { pid: String, m: String -> store.activeProvider == pid && store.activeModel == m }
    val row = @Composable { pid: String, m: String, sub: String ->
        PickRow(m, sub, tags(pid, m), isSel(pid, m), q = query) { onPick(pid, m) }
    }
    if (query.isEmpty()) {
        val recent = store.recent.mapNotNull { k -> val pid = k.substringBefore('|'); val m = k.substringAfter('|'); ready.firstOrNull { it.id == pid }?.let { p -> Triple(p, m, store.config(p.id).name) }?.takeIf { m in store.config(pid).models } }
        if (recent.isNotEmpty()) {
            Cap("最近用过")
            recent.forEach { (p, m, name) -> row(p.id, m, name) }
        }
    }
    var any = false
    ready.forEach { p ->
        val c = store.config(p.id)
        val chat = c.models.filter { ModelSort.chat(it) }
        val hidden = c.models.size - chat.size
        val ms = when { query.isEmpty() -> chat; nameHit(p) -> c.models; else -> c.models.filter { query in it.lowercase() } }
        if (query.isNotEmpty() && ms.isEmpty()) return@forEach
        any = true
        val cur = store.activeProvider == p.id
        val open = query.isNotEmpty() || (opened[p.id] ?: cur)
        GroupCard(p.letter, c.name, query, open, count = ms.size.toString(), cur = if (cur && !open) store.activeModel else null, onHead = { if (query.isEmpty()) opened[p.id] = !open }) {
            if (query.isNotEmpty()) ms.forEach { row(p.id, it, "") }
            else {
                val fams = ModelSort.families(ms)
                val (olds, leads) = fams.partition { ModelSort.isOld(it.lead) }
                val fam = @Composable { f: ModelSort.Family ->
                    row(p.id, f.lead, "")
                    if (f.snaps.isNotEmpty()) {
                        val k = p.id + "|" + f.lead
                        val o = snapsOpen[k] ?: (cur && store.activeModel in f.snaps)
                        SubFold("+${f.snaps.size} 个快照", o) { snapsOpen[k] = !o }
                        Fold(o) { Level { f.snaps.forEach { SubRow(it, isSel(p.id, it), query) { onPick(p.id, it) } } } }
                    }
                }
                leads.forEach { fam(it) }
                if (olds.isNotEmpty()) {
                    val o = oldOpen[p.id] ?: (cur && olds.any { f -> store.activeModel == f.lead || store.activeModel in f.snaps })
                    SubFold("更早的型号 ${olds.size}", o) { oldOpen[p.id] = !o }
                    Fold(o) { Level { olds.forEach { f -> (listOf(f.lead) + f.snaps).forEach { SubRow(it, isSel(p.id, it), query) { onPick(p.id, it) } } } } }
                }
                if (hidden > 0) Txt("另有 $hidden 个语音、向量、画图、翻译型号不能聊天，没列", Page.muted.copy(alpha = .75f), 11.5.sp, Modifier.padding(start = 12.dp, top = 6.dp, bottom = 2.dp))
            }
        }
    }
    if (query.isNotEmpty()) {
        todo.forEach { p ->
            val c = store.config(p.id)
            val ms = if (nameHit(p)) c.models else c.models.filter { query in it.lowercase() }
            if (!nameHit(p) && ms.isEmpty()) return@forEach
            any = true
            GroupCard(p.letter, c.name, query, true, dim = true, action = { KeyButton { onSetup(p.id) } }, onHead = { onSetup(p.id) }) {
                if (ms.isEmpty()) Txt("填好 Key 后点「获取模型」拉型号", Page.muted.copy(alpha = .75f), 11.5.sp, Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp))
                ms.forEach { m -> PickRow(m, "", "", sel = false, dim = true, q = query) { onSetup(p.id) } }
            }
        }
        if (!any) Txt("没有叫「$q」的型号，也没有这一家", Page.muted, 13.sp, Modifier.fillMaxWidth().padding(vertical = 24.dp), align = androidx.compose.ui.text.style.TextAlign.Center)
    } else if (todo.isNotEmpty()) {
        TodoCard(todo.map { it.id to (it.letter to store.config(it.id).name) }, onSetup)
    }
}

private val dashed = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f))

/** 一家一张卡片：头像字、名字、个数（或右边一个按钮）；展开时内容收在卡片里，和标题隔一道线。 */
@Composable
private fun GroupCard(
    letter: String, name: String, q: String, open: Boolean, count: String? = null, cur: String? = null, dim: Boolean = false,
    action: (@Composable () -> Unit)? = null, onHead: () -> Unit, content: @Composable () -> Unit,
) {
    val fill by androidx.compose.animation.animateColorAsState(if (open) veil(.075f) else veil(.05f), tween(220), label = "card")
    val turn by animateFloatAsState(if (open) 90f else 0f, tween(320, easing = decel), label = "chev")
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).background(fill, RoundedCornerShape(18.dp))) {
        Pressable(onHead, Modifier.fillMaxWidth(), label = name) { pressed ->
            Row(
                Modifier.fillMaxWidth().background(if (pressed) veil(.04f) else Color.Transparent, RoundedCornerShape(18.dp)).padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(30.dp).background(Skin.accent.copy(alpha = if (dim) .08f else .14f), CircleShape), contentAlignment = Alignment.Center) {
                    Txt(letter, if (dim) Page.muted else Page.accent, 13.sp, weight = FontWeight.Medium)
                }
                Hi(name, q, if (dim) Page.muted else Page.ink, 14.5.sp, Modifier.weight(1f, fill = false), FontWeight.Medium)
                if (count != null) Txt(count, Page.muted, 11.sp, Modifier.background(veil(.06f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp))
                Spacer(Modifier.weight(1f))
                if (cur != null) Txt(cur, Page.accent, 12.sp, Modifier.weight(1f, fill = false), maxLines = 1)
                if (action != null) action() else SvgIcon(chevron, Page.muted, 14.dp, modifier = Modifier.rotate(turn))
            }
        }
        Fold(open) {
            Column(Modifier.padding(horizontal = 12.dp)) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(veil(.08f)))
                Column(Modifier.padding(top = 6.dp, bottom = 8.dp)) { content() }
            }
        }
    }
}

@Composable
private fun KeyButton(onClick: () -> Unit) = PressSurface(onClick, shape = RoundedCornerShape(50), fill = Skin.accent.copy(alpha = .9f), pressScale = .94f, label = "去填 Key") {
    Txt("去填 Key", Skin.onAccent, 12.sp, Modifier.padding(horizontal = 11.dp, vertical = 5.dp))
}

/** 还没填 Key 的几家：虚线框，点一家去它的配置。 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TodoCard(items: List<Pair<String, Pair<String, String>>>, onSetup: (String?) -> Unit) {
    val line = veil(.14f)
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp)
            .drawBehind { drawRoundRect(line, cornerRadius = androidx.compose.ui.geometry.CornerRadius(18.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx(), pathEffect = dashed)) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Txt("还没填 Key 的 ${items.size} 家，点一下去填", Page.muted, 12.sp, Modifier.padding(start = 2.dp, bottom = 8.dp))
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items.forEach { (id, v) ->
                val (letter, name) = v
                PressSurface({ onSetup(id) }, shape = RoundedCornerShape(50), fill = veil(.06f), pressScale = .95f, label = name) {
                    Row(Modifier.padding(start = 5.dp, end = 10.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(22.dp).background(Skin.accent.copy(alpha = .14f), CircleShape), contentAlignment = Alignment.Center) { Txt(letter, Page.accent, 11.sp) }
                        Spacer(Modifier.width(6.dp))
                        Txt(name, Page.ink, 12.5.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** 下一层（快照、更早的型号）：左边一道细线。 */
@Composable
private fun Level(content: @Composable () -> Unit) {
    val line = veil(.1f)
    Column(Modifier.padding(start = 14.dp).drawBehind { drawLine(line, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx()) }.padding(start = 8.dp)) { content() }
}

/** 下一层的一行：小一号、淡一点。 */
@Composable
private fun SubRow(text: String, sel: Boolean, q: String, onClick: () -> Unit) {
    Pressable(onClick, Modifier.fillMaxWidth().padding(bottom = 2.dp), label = text) { pressed ->
        Row(
            Modifier.fillMaxWidth().background(when { sel -> Skin.accent.copy(alpha = .16f); pressed -> veil(.05f); else -> Color.Transparent }, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Hi(text, q, if (sel) Page.ink else Page.ink.copy(alpha = .62f), 13.5.sp, Modifier.weight(1f))
            if (sel) SvgIcon(Ic("M5 12.5l4.5 4.5L19 7.5"), Skin.accent.copy(alpha = 1f), 16.dp, stroke = 1.8f)
        }
    }
}

/** 一行字，对上找的那几个字标杏色。 */
@Composable
private fun Hi(text: String, q: String, ink: Color, size: androidx.compose.ui.unit.TextUnit, modifier: Modifier = Modifier, weight: FontWeight = FontWeight.Normal) {
    val i = if (q.isEmpty()) -1 else text.lowercase().indexOf(q)
    BasicText(
        buildAnnotatedString {
            if (i < 0) append(text)
            else { append(text.substring(0, i)); withStyle(SpanStyle(color = Page.accent)) { append(text.substring(i, i + q.length)) }; append(text.substring(i + q.length)) }
        },
        modifier, style = TextStyle(fontFamily = Sans, color = ink, fontSize = size, fontWeight = weight), maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

private fun tags(pid: String, m: String): String =
    (if (Attach.vision(pid, m)) "图" else "") + (if (QwenSearch.supports(pid, m)) "搜" else "")

@Composable
private fun Cap(text: String) = Txt(text, Page.muted, 10.sp, Modifier.padding(start = 6.dp, top = 12.dp, bottom = 8.dp), spacing = ls(2.5f, 10f))

@Composable
private fun SubFold(text: String, open: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val turn by animateFloatAsState(if (open) 90f else 0f, tween(320, easing = decel), label = "sub")
    Pressable(onClick, modifier.fillMaxWidth(), label = text) { pressed ->
        Row(
            Modifier.fillMaxWidth().background(if (pressed) veil(.05f) else Color.Transparent, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt(text, Page.muted, 12.sp)
            Spacer(Modifier.width(4.dp))
            SvgIcon(chevron, Page.muted, 12.dp, modifier = Modifier.rotate(turn))
        }
    }
}

/** 一行型号：选中淡暖色块 + 勾；sub 是「最近用过」里的供应商名；tags 是「图」「搜」。 */
@Composable
private fun PickRow(text: String, sub: String, tags: String, sel: Boolean, dim: Boolean = false, q: String, onClick: () -> Unit) {
    Pressable(onClick, Modifier.fillMaxWidth().padding(bottom = 4.dp), label = text) { pressed ->
        Row(
            Modifier.fillMaxWidth()
                .background(when { sel -> Skin.accent.copy(alpha = .16f); pressed -> veil(.05f); else -> Color.Transparent }, RoundedCornerShape(14.dp))
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val ink = if (sel) Page.ink else if (dim) Page.muted else Page.ink.copy(alpha = .78f)
            Hi(text, q, ink, 15.sp, Modifier.weight(1f))
            if (sub.isNotEmpty()) Txt(sub, Page.muted, 11.sp, maxLines = 1)
            tags.forEach { t ->
                val warm = t == '搜'
                Txt(t.toString(), if (warm) Page.accent else Page.muted, 10.sp,
                    Modifier.background(if (warm) Skin.accent.copy(alpha = .16f) else veil(.06f), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 2.dp))
            }
            if (sel) SvgIcon(Ic("M5 12.5l4.5 4.5L19 7.5"), Skin.accent.copy(alpha = 1f), 18.dp, stroke = 1.8f)
        }
    }
}
