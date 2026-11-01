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
                    if (q.isEmpty()) Txt("找型号，比如 max、flash、3.8", Page.muted.copy(alpha = .6f), 14.5.sp, maxLines = 1)
                    inner()
                }
                if (q.isNotEmpty()) Pressable({ onChange("") }, label = "清除") { _ -> Txt("清除", Page.muted, 13.sp, Modifier.padding(6.dp)) }
            }
        },
    )
}

/** 列表本体（放在可滚的那一段里）。 */
@Composable
fun ModelList(store: AssistantStore, q: String, onPick: (String, String) -> Unit, onEmpty: () -> Unit) {
    val ready = store.ready()
    val opened = remember { mutableStateMapOf<String, Boolean>() }
    val snapsOpen = remember { mutableStateMapOf<String, Boolean>() }
    val oldOpen = remember { mutableStateMapOf<String, Boolean>() }
    val query = q.trim().lowercase()
    if (ready.isEmpty()) { PickRow("还没有可用的模型", "", "", sel = false, dim = true, q = "") { onEmpty() }; return }
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
        val ms = if (query.isEmpty()) chat else c.models.filter { query in it.lowercase() }
        if (query.isNotEmpty() && ms.isEmpty()) return@forEach
        any = true
        val cur = store.activeProvider == p.id
        val open = query.isNotEmpty() || (opened[p.id] ?: cur)
        val turn by animateFloatAsState(if (open) 90f else 0f, tween(320, easing = decel), label = "chev")
        Pressable({ if (query.isEmpty()) opened[p.id] = !open }, Modifier.fillMaxWidth(), label = c.name) { _ ->
            Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt(c.name.uppercase(), Page.muted, 10.sp, spacing = ls(2.5f, 10f), maxLines = 1)
                Spacer(Modifier.width(8.dp))
                Txt(ms.size.toString(), Page.muted.copy(alpha = .8f), 11.5.sp)
                Spacer(Modifier.weight(1f))
                if (cur && !open) Txt(store.activeModel, Page.accent, 11.5.sp, Modifier.padding(start = 8.dp, end = 4.dp), maxLines = 1)
                SvgIcon(chevron, Page.muted, 13.dp, modifier = Modifier.rotate(turn))
            }
        }
        Fold(open) {
            if (query.isNotEmpty()) ms.forEach { row(p.id, it, "") }
            else {
                val fams = ModelSort.families(ms)
                val (olds, leads) = fams.partition { ModelSort.isOld(it.lead) }
                val fam = @Composable { f: ModelSort.Family ->
                    row(p.id, f.lead, "")
                    if (f.snaps.isNotEmpty()) {
                        val k = p.id + "|" + f.lead
                        val o = snapsOpen[k] ?: (cur && store.activeModel in f.snaps)
                        SubFold("+${f.snaps.size} 个快照", o, Modifier.padding(start = 16.dp)) { snapsOpen[k] = !o }
                        Fold(o) { Column(Modifier.padding(start = 14.dp)) { f.snaps.forEach { row(p.id, it, "") } } }
                    }
                }
                leads.forEach { fam(it) }
                if (olds.isNotEmpty()) {
                    val o = oldOpen[p.id] ?: (cur && olds.any { f -> store.activeModel == f.lead || store.activeModel in f.snaps })
                    SubFold("更早的型号 ${olds.size}", o) { oldOpen[p.id] = !o }
                    Fold(o) { Column(Modifier.padding(start = 14.dp)) { olds.forEach { fam(it) } } }
                }
                if (hidden > 0) Txt("另有 $hidden 个语音、向量、画图、翻译型号不能聊天，没列", Page.muted, 11.5.sp, Modifier.padding(start = 16.dp, top = 2.dp, bottom = 8.dp))
            }
        }
    }
    if (query.isNotEmpty() && !any) Txt("没有叫「$q」的型号", Page.muted, 13.sp, Modifier.fillMaxWidth().padding(vertical = 28.dp), align = androidx.compose.ui.text.style.TextAlign.Center)
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
            val i = if (q.isEmpty()) -1 else text.lowercase().indexOf(q)
            BasicText(
                buildAnnotatedString {
                    if (i < 0) append(text)
                    else { append(text.substring(0, i)); withStyle(SpanStyle(color = Page.accent)) { append(text.substring(i, i + q.length)) }; append(text.substring(i + q.length)) }
                },
                Modifier.weight(1f), style = TextStyle(fontFamily = Sans, color = ink, fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
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
