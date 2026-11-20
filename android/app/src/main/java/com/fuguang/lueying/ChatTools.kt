package com.fuguang.lueying

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/*
 * 输入框 ＋ 的工具面板，照 Kelivo 1.3.6（Chevey339/kelivo 192a6a8）：
 * bottom_tools_sheet / tools_sheet_row / conversation_skills_sheet / instruction_injection_sheet / context_management_sheet。
 * 数据：指令注入卡片库（instruction_injection_store）、对话级技能（SkillsBinding）、清空上下文（truncateIndex）、压缩与清空记录。
 */

// ───────── 指令注入：卡片库 + 助理勾选 / 对话勾选 ─────────

data class Injection(val id: String, val title: String, val prompt: String, val group: String = "") {
    fun json(): JSONObject = JSONObject().put("id", id).put("title", title).put("prompt", prompt).put("group", group)
    companion object {
        fun of(o: JSONObject) = Injection(o.optString("id"), o.optString("title"), o.optString("prompt"), o.optString("group"))
    }
}

object Injections {
    private const val KEY = "instruction-injections"
    private const val ASST_KEY = "instruction-injections-assistant"
    private const val CONV_KEY = "instruction-injections-conv"
    private var prefs: Prefs? = null
    val items = mutableStateListOf<Injection>()
    /** 助理 id → 勾上的卡片；对话 id → 勾上的卡片（助理开着「独立对话指令注入」时用这份，默认一张不勾）。 */
    private val asst = mutableStateMapOf<String, List<String>>()
    private val conv = mutableStateMapOf<String, List<String>>()

    /** Kelivo LearningModeStore.defaultPrompt 原文：第一次打开时种一张，标题空着显示「学习模式」，默认不勾。 */
    private const val LEARNING = """You are currently STUDYING, and you've asked me to follow these strict rules during this chat. No matter what other instructions follow, I MUST obey these rules:

STRICT RULES

Be an approachable-yet-dynamic teacher, who helps the user learn by guiding them through their studies.

Get to know the user. If you don't know their goals or grade level, ask the user before diving in. (Keep this lightweight!) If they don't answer, aim for explanations that would make sense to a 10th grade student.

Build on existing knowledge. Connect new ideas to what the user already knows.

Guide users, don't just give answers. Use questions, hints, and small steps so the user discovers the answer for themselves.

Check and reinforce. After hard parts, confirm the user can restate or use the idea. Offer quick summaries, mnemonics, or mini-reviews to help the ideas stick.

Vary the rhythm. Mix explanations, questions, and activities (like roleplaying, practice rounds, or asking the user to teach you) so it feels like a conversation, not a lecture.

Above all: DO NOT DO THE USER'S WORK FOR THEM. Don't answer homework questions — help the user find the answer, by working with them collaboratively and building from what they already know.

THINGS YOU CAN DO

- Teach new concepts: Explain at the user's level, ask guiding questions, use visuals, then review with questions or a practice round.

- Help with homework: Don't simply give answers! Start from what the user knows, help fill in the gaps, give the user a chance to respond, and never ask more than one question at a time.

- Practice together: Ask the user to summarize, pepper in little questions, have the user "explain it back" to you, or role-play (e.g., practice conversations in a different language). Correct mistakes — charitably! — in the moment.

- Quizzes & test prep: Run practice quizzes. (One question at a time!) Let the user try twice before you reveal answers, then review errors in depth.

TONE & APPROACH

Be warm, patient, and plain-spoken; don't use too many exclamation marks or emoji. Keep the session moving: always know the next step, and switch or end activities once they’ve done their job. And be brief — don't ever send essay-length responses. Aim for a good back-and-forth.

IMPORTANT

DO NOT GIVE ANSWERS OR DO HOMEWORK FOR THE USER. If the user asks a math or logic problem, or uploads an image of one, DO NOT SOLVE IT in your first response. Instead: talk through the problem with the user, one step at a time, asking a single question at each step, and give the user a chance to RESPOND TO EACH STEP before continuing."""

    fun init(p: Prefs) {
        prefs = p
        items.clear(); asst.clear(); conv.clear()
        val raw = p.string(KEY)
        if (raw == null) { items += Injection(UUID.randomUUID().toString(), "", LEARNING); save() }
        else runCatching { val a = JSONArray(raw); for (i in 0 until a.length()) items += Injection.of(a.getJSONObject(i)) }
        runCatching { readMap(p.string(ASST_KEY), asst) }
        runCatching { readMap(p.string(CONV_KEY), conv) }
    }
    private fun readMap(s: String?, into: MutableMap<String, List<String>>) {
        val o = JSONObject(s ?: "{}"); o.keys().forEach { k -> val a = o.getJSONArray(k); into[k] = (0 until a.length()).map { a.getString(it) } }
    }
    private fun writeMap(m: Map<String, List<String>>) = JSONObject(m.mapValues { JSONArray(it.value) }).toString()
    private fun save() { prefs?.putString(KEY, JSONArray(items.map { it.json() }).toString()) }
    private fun saveSel() { prefs?.putString(ASST_KEY, writeMap(asst)); prefs?.putString(CONV_KEY, writeMap(conv)) }

    fun put(x: Injection) { val i = items.indexOfFirst { it.id == x.id }; if (i >= 0) items[i] = x else items += x; save() }
    fun remove(id: String) {
        items.removeAll { it.id == id }; save()
        asst.keys.toList().forEach { k -> asst[k] = asst[k].orEmpty() - id }
        conv.keys.toList().forEach { k -> conv[k] = conv[k].orEmpty() - id }
        saveSel()
    }

    /** 这一轮用谁的勾选：助理开着「独立对话指令注入」且有对话 → 对话的；否则助理的。 */
    fun scoped(p: Persona, convId: String?) = convId != null && AssistantExt.of(p).perConvInject
    fun picked(p: Persona, convId: String?): List<String> =
        if (scoped(p, convId)) conv[convId].orEmpty() else asst[p.id].orEmpty()
    fun toggle(p: Persona, convId: String?, id: String) {
        val cur = picked(p, convId)
        val next = if (id in cur) cur - id else cur + id
        if (scoped(p, convId)) conv[convId!!] = next else asst[p.id] = next
        saveSel()
    }
    fun count(p: Persona, convId: String?) = picked(p, convId).count { id -> items.any { it.id == id } }

    /** 发送时追加到系统提示词末尾（Kelivo promptFor：按库里顺序，空行隔开）。 */
    fun prompt(p: Persona, convId: String?): String {
        val ids = picked(p, convId).toSet()
        return items.filter { it.id in ids }.map { it.prompt.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")
    }
}

// ───────── 对话级技能（Kelivo SkillsBinding）：null = 跟随助手 ─────────

object ConvSkills {
    private const val KEY = "skills-conv"
    private var prefs: Prefs? = null
    private val map = mutableStateMapOf<String, List<String>>()
    /** 这一轮正在发的对话：Skills.usable() 默认按它取（Chat.kt 发之前设上）。 */
    @Volatile var turn: String? = null

    fun init(p: Prefs) {
        prefs = p
        map.clear()
        runCatching { val o = JSONObject(p.string(KEY) ?: "{}"); o.keys().forEach { k -> val a = o.getJSONArray(k); map[k] = (0 until a.length()).map { a.getString(it) } } }
    }
    private fun save() { prefs?.putString(KEY, JSONObject(map.mapValues { JSONArray(it.value) }).toString()) }
    fun of(convId: String?): List<String>? = convId?.let { map[it] }
    fun set(convId: String, ids: List<String>?) { if (ids == null) map.remove(convId) else map[convId] = ids; save() }
}

// ───────── 清空上下文（Kelivo truncateIndex）+ 压缩与清空记录 ─────────

data class CtxOp(val conv: String, val kind: String, val at: Long, val info: String, val cut: Int = -1, val prevCut: Int = 0)

object CtxCut {
    private const val KEY = "context-cut"
    private const val OPS_KEY = "context-ops"
    private var prefs: Prefs? = null
    private val cuts = mutableStateMapOf<String, Int>()
    val ops = mutableStateListOf<CtxOp>()

    fun init(p: Prefs) {
        prefs = p
        cuts.clear(); ops.clear()
        runCatching { val o = JSONObject(p.string(KEY) ?: "{}"); o.keys().forEach { cuts[it] = o.getInt(it) } }
        runCatching {
            val a = JSONArray(p.string(OPS_KEY) ?: "[]")
            for (i in 0 until a.length()) a.getJSONObject(i).let { ops += CtxOp(it.optString("c"), it.optString("k"), it.optLong("at"), it.optString("i"), it.optInt("cut", -1), it.optInt("prev")) }
        }
    }
    private fun save() {
        prefs?.putString(KEY, JSONObject(cuts.toMap()).toString())
        prefs?.putString(OPS_KEY, JSONArray(ops.takeLast(200).map { JSONObject().put("c", it.conv).put("k", it.kind).put("at", it.at).put("i", it.info).put("cut", it.cut).put("prev", it.prevCut) }).toString())
    }

    /** 从第几条起才发给模型（0 = 全发）。 */
    fun at(convId: String?): Int = convId?.let { cuts[it] } ?: 0

    /** 清空：聊天记录都在，分隔线以上不再发给模型。 */
    fun clear(convId: String, size: Int) {
        val prev = at(convId)
        if (size <= prev) return
        cuts[convId] = size
        ops += CtxOp(convId, "清空", System.currentTimeMillis(), "${size - prev} 条不再发给模型", size, prev)
        save()
    }
    fun logCompress(convId: String, info: String) { ops += CtxOp(convId, "压缩", System.currentTimeMillis(), info); save() }
    fun undo(op: CtxOp, store: ConvStore) {
        if (op.kind == "清空") { if (op.prevCut <= 0) cuts.remove(op.conv) else cuts[op.conv] = op.prevCut }
        else store.setFold(op.conv, null)
        ops.remove(op); save()
    }
    fun of(convId: String?) = ops.filter { it.conv == convId }.reversed()

    /** 发出去的那份：清空点在前情之后，直接从清空点发；否则照旧走前情。 */
    fun view(c: Conv?, history: List<ChatMsg>): List<ChatMsg> {
        val cut = at(c?.id)
        if (cut <= 0) return Fold.view(c, history)
        val f = Fold.of(c)
        if (f != null && f.upto > cut) return Fold.view(c, history)
        return history.drop(cut.coerceAtMost(history.size))
    }
}

// ───────── 面板 ─────────

private object TI {
    val camera = Ic("M14.5 4h-5L7 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3l-2.5-3z", listOf(floatArrayOf(12f, 13f, 3f)))
    val image = Ic("M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2ZM21 15l-3.086-3.086a2 2 0 0 0-2.828 0L6 21", listOf(floatArrayOf(9f, 9f, 2f)))
    val clip = Ic("m21.44 11.05-9.19 9.19a6 6 0 0 1-8.49-8.49l8.57-8.57A4 4 0 1 1 18 8.84l-8.59 8.57a2 2 0 0 1-2.83-2.83l8.49-8.48")
    val window = Ic("M4 3h16a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2ZM10 4v4M2 8h20M6 4v4")
    val layers = Ic("M12.83 2.18a2 2 0 0 0-1.66 0L2.6 6.08a1 1 0 0 0 0 1.83l8.58 3.91a2 2 0 0 0 1.66 0l8.58-3.9a1 1 0 0 0 0-1.83ZM22 17.65l-9.17 4.16a2 2 0 0 1-1.66 0L2 17.65M22 12.65l-9.17 4.16a2 2 0 0 1-1.66 0L2 12.65")
    val book = Ic("M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2zM22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z")
    val flow = Ic("M5 3h4a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2ZM7 11v4a2 2 0 0 0 2 2h4M15 13h4a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2h-4a2 2 0 0 1-2-2v-4a2 2 0 0 1 2-2Z")
    val pkg = Ic("M11 21.73a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73zM12 22V12M3.29 7 12 12l8.71-5M7.5 4.27l9 5.15")
    val eraser = Ic("m7 21-4.3-4.3c-1-1-1-2.5 0-3.4l9.6-9.6c1-1 2.5-1 3.4 0l5.6 5.6c1 1 1 2.5 0 3.4L13 21M22 21H7M5 11l9 9")
    val pen = Ic("M21.17 6.81a1 1 0 0 0-3.98-3.98L3.84 16.17a2 2 0 0 0-.5.83l-1.32 4.35a.5.5 0 0 0 .62.62l4.35-1.32a2 2 0 0 0 .83-.5z")
    val down = Ic("m6 9 6 6 6-6")
}

/** Kelivo ToolsSheetRow：48dp、圆角 14、图标 20、隔 10、字 15 中粗、右边计数或箭头（18、透明度 .55）；按下缩到 .98。 */
@Composable
private fun ToolRow(icon: Ic, label: String, sub: String? = null, onLong: (() -> Unit)? = null, onClick: () -> Unit, trailing: @Composable () -> Unit = { Chev() }) {
    val haptic = LocalHapticFeedback.current
    var down by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (down) .98f else 1f, tween(if (down) 90 else 260, easing = K.std), label = "trS")
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).heightIn(min = if (sub == null) 48.dp else 56.dp)
            .graphicsLayer { scaleX = s; scaleY = s }.clip(RoundedCornerShape(14.dp))
            .background(if (down) K.card2 else Color.Transparent)
            .pointerInput(onClick, onLong) {
                detectTapGestures(
                    onPress = { down = true; tryAwaitRelease(); down = false },
                    onTap = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() },
                    onLongPress = onLong?.let { f -> { _: androidx.compose.ui.geometry.Offset -> haptic.performHapticFeedback(HapticFeedbackType.LongPress); f() } },
                )
            }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SvgIcon(icon, K.ink, 20.dp, stroke = 1.8f)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Txt(label, K.ink, 15.sp, weight = FontWeight.Medium, maxLines = 1)
            if (sub != null) Txt(sub, K.sub, 12.5.sp, maxLines = 1)
        }
        trailing()
    }
}

@Composable private fun Chev() = SvgIcon(KI.chev, K.ink.copy(alpha = .55f), 18.dp, stroke = 1.8f)
@Composable private fun Count(n: Int, total: Int) { if (n == 0) Chev() else Txt("$n/$total", K.acc, 13.sp, weight = FontWeight.Medium) }

@Composable
private fun Tile(icon: Ic, label: String, modifier: Modifier, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    var down by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (down) .98f else 1f, tween(if (down) 90 else 260, easing = K.std), label = "tlS")
    Column(
        modifier.height(72.dp).graphicsLayer { scaleX = s; scaleY = s }.clip(RoundedCornerShape(14.dp))
            .background(if (down) K.card2 else Color.Transparent)
            .pointerInput(Unit) { detectTapGestures(onPress = { down = true; tryAwaitRelease(); down = false }, onTap = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() }) },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        SvgIcon(icon, K.ink, 24.dp, stroke = 1.8f)
        Spacer(Modifier.height(6.dp))
        Txt(label, K.ink, 13.sp)
    }
}

object ChatTools {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sheet get() = ChatSheets.sheet
    /** 长按跳到「我的」里的页面（App.kt 接上）：skills / worldbook。 */
    var go: ((String) -> Unit)? = null
    /** ＋ 打开的这一串面板还开着：＋ 转成 ×。 */
    var open by mutableStateOf(false)
    /** 要在聊天页底部提示的一句（Chat.kt 接过去显示）。 */
    var toast by mutableStateOf<String?>(null)
    private fun say(t: String) { toast = t }

    class Ctx(val chat: ChatState, val provider: String, val model: String, val attach: (Int) -> Unit)
    private var ctx: Ctx? = null

    /** 照 Kelivo：先收起当前面板（220ms），再升起下一个。 */
    private fun next(c: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
        sheet.close()
        scope.launch { delay(230); sheet.open(c); open = true }
    }
    private fun done(then: () -> Unit = {}) { sheet.close(); open = false; scope.launch { delay(200); then() } }

    fun toggle(c: Ctx) {
        if (open && sheet.shown) { done(); return }
        ctx = c; open = true
        sheet.open { Main(c) }
    }
    /** 面板被点暗处 / 下拖收起时，＋ 跟着转回来。 */
    fun sync() { if (!sheet.shown) open = false }

    @Composable
    private fun Main(c: Ctx) {
        val p = Memory.active
        val cid = c.chat.convId
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile(TI.camera, "拍照", Modifier.weight(1f)) { done { c.attach(1) } }
            Tile(TI.image, "照片", Modifier.weight(1f)) { done { c.attach(0) } }
            Tile(TI.clip, "上传文件", Modifier.weight(1f)) { done { c.attach(2) } }
        }
        ToolRow(TI.window, "浏览器", onClick = { done { InBrowser.openHome() } })
        Spacer(Modifier.height(4.dp))
        val usable = Skills.of(p.id).filter { it.on }
        val active = Skills.usable(p.id, cid)
        ToolRow(KI.wand, "技能", onLong = { done { go?.invoke("skills") } }, onClick = { next { SkillsSheet(c) } }) { Count(active.size, usable.size) }
        ToolRow(TI.layers, "指令注入", onLong = { next { InjectSheet(c) } }, onClick = { next { InjectSheet(c) } }) { Count(Injections.count(p, cid), Injections.items.size) }
        if (WorldBooks.books.isNotEmpty()) {
            val picked = (cid?.let { WorldBooks.convPick(it) } ?: AssistantExt.of(p).worldBooks).count { id -> WorldBooks.books.any { it.id == id } }
            ToolRow(TI.book, "世界书", onLong = { done { go?.invoke("worldbook") } }, onClick = { sheet.close(); scope.launch { delay(230); ChatSheets.worldBooks(cid); open = true } }) { Count(picked, WorldBooks.books.size) }
        }
        ToolRow(TI.flow, "上下文管理", onClick = { next { ContextSheet(c) } })
    }

    // 此对话的技能（conversation_skills_sheet）
    @Composable
    private fun SkillsSheet(c: Ctx) {
        val p = Memory.active
        val cid = c.chat.convId
        val listed = Skills.of(p.id).filter { it.on }
        val inherit = ConvSkills.of(cid) == null
        val on = Skills.usable(p.id, cid).map { it.id }.toSet()
        KSheetTitle("此对话的技能")
        if (cid == null) { Txt("先发一句话开始对话，再给这个对话单独选技能", K.sub, 13.sp, Modifier.fillMaxWidth().padding(vertical = 20.dp), align = TextAlign.Center); return }
        KCard { KRow("跟随助手", trailing = { KSwitch(inherit) { v -> ConvSkills.set(cid, if (v) null else on.toList()) } }) }
        if (listed.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                SvgIcon(KI.wand, K.ink.copy(alpha = .26f), 36.dp, stroke = 1.8f)
                Spacer(Modifier.height(12.dp))
                Txt("还没有已启用的技能，请先在技能库中启用", K.sub, 13.sp, align = TextAlign.Center)
            }
        } else KCard {
            listed.forEachIndexed { i, s ->
                KRow(s.title, sub = s.shown.take(60), divider = i > 0, onClick = {
                    val cur = ConvSkills.of(cid) ?: on.toList()
                    ConvSkills.set(cid, if (s.id in cur) cur - s.id else cur + s.id)
                }) { KCheck(s.id in on) }
            }
        }
    }

    // 指令注入（instruction_injection_sheet）：按分组折叠，勾选，编辑，添加
    private val collapsed = mutableStateMapOf<String, Boolean>()

    @Composable
    private fun InjectSheet(c: Ctx) {
        val p = Memory.active
        val cid = c.chat.convId
        val picked = Injections.picked(p, cid).toSet()
        KSheetTitle("指令注入") { if (Injections.scoped(p, cid)) KTag("仅当前对话", true) }
        if (Injections.items.isEmpty()) Txt("暂无指令注入卡片", K.sub, 13.5.sp, Modifier.fillMaxWidth().padding(24.dp), align = TextAlign.Center)
        val groups = Injections.items.groupBy { it.group.trim() }.toSortedMap(compareBy<String> { it.isNotEmpty() }.thenBy { it })
        groups.forEach { (g, list) ->
            val shut = collapsed[g] == true
            val r by animateFloatAsState(if (shut) -90f else 0f, spring(dampingRatio = .8f, stiffness = 600f), label = "grpR")
            Row(Modifier.fillMaxWidth().pointerInput(g) { detectTapGestures { collapsed[g] = collapsed[g] != true } }.padding(start = 6.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SvgIcon(TI.down, K.sub, 14.dp, stroke = 2f, modifier = Modifier.rotate(r))
                Spacer(Modifier.width(4.dp))
                Txt(g.ifEmpty { "未分组" }, K.sub, 12.5.sp)
            }
            if (!shut) KCard {
                list.forEachIndexed { i, x ->
                    KRow(x.title.ifBlank { "学习模式" }, sub = x.prompt.replace('\n', ' ').take(60), divider = i > 0, onClick = { Injections.toggle(p, cid, x.id) }) {
                        KCheck(x.id in picked)
                        Spacer(Modifier.width(8.dp))
                        SvgIcon(TI.pen, K.sub, 16.dp, stroke = 1.8f, modifier = Modifier.clip(CircleShape).pointerInput(x) { detectTapGestures { next { EditSheet(c, x) } } }.padding(6.dp))
                    }
                }
            }
        }
        KButton("添加指令注入", Modifier.fillMaxWidth()) { next { EditSheet(c, null) } }
    }

    @Composable
    private fun EditSheet(c: Ctx, x0: Injection?) {
        var title by remember { mutableStateOf(x0?.title.orEmpty()) }
        var group by remember { mutableStateOf(x0?.group.orEmpty()) }
        var prompt by remember { mutableStateOf(x0?.prompt.orEmpty()) }
        KSheetTitle(if (x0 == null) "添加指令注入" else "编辑指令注入")
        KLabel("标题"); KField(title, { title = it }, hint = "学习模式")
        KLabel("分组"); KField(group, { group = it }, hint = "未分组")
        KLabel("内容（发送时追加到系统提示词末尾）"); KField(prompt, { prompt = it }, lines = 6)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (x0 != null) KButton("删除", Modifier.weight(1f), color = K.danger) { Injections.remove(x0.id); next { InjectSheet(c) } }
            else KButton("取消", Modifier.weight(1f)) { next { InjectSheet(c) } }
            KButton("保存", Modifier.weight(1f), primary = true) {
                if (prompt.isBlank()) return@KButton
                val x = Injection(x0?.id ?: UUID.randomUUID().toString(), title.trim(), prompt.trim(), group.trim())
                Injections.put(x)
                if (x0 == null) Injections.toggle(Memory.active, c.chat.convId, x.id)
                next { InjectSheet(c) }
            }
        }
    }

    // 上下文管理（context_management_sheet）
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    @Composable
    private fun ContextSheet(c: Ctx) {
        val p = Memory.active
        val cid = c.chat.convId
        val conv = c.chat.history.get(cid)
        val hist = c.chat.messages.toList()
        val sent = CtxCut.view(conv, hist)
        val win = CtxWin.window(c.provider, c.model)
        val parts = listOf(
            Triple("系统提示词", CtxWin.est(Prompt.system(p, c.model)), Color(0xFF8E9BFF)),
            Triple("指令注入", CtxWin.est(Injections.prompt(p, cid)), Color(0xFFC79BFF)),
            Triple("技能", if (Skills.usable(p.id, cid).isEmpty()) 0 else CtxWin.est(Skills.system()), Color(0xFFF2C46D)),
            Triple("历史消息", CtxWin.est(sent), Skin.accent),
            Triple("草稿", CtxWin.est(c.chat.draft), Color(0xFF7FD1B9)),
        ).map { (n, t, col) -> Triple(n, CtxWin.tokens(c.provider, c.model, t), col) }.filter { it.second > 0 }
        val used = parts.sumOf { it.second }
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(14.dp)).background(K.card).padding(12.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Txt("上下文窗口", K.ink, 13.sp, Modifier.weight(1f), weight = FontWeight.Medium)
                Txt("估算 · ${k(used)} / ${k(win)}", K.sub, 13.sp)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)).background(K.line), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                parts.forEach { (_, t, col) -> val f = t.toFloat() / win.coerceAtLeast(1); if (f > 0f) Box(Modifier.weight(f.coerceAtLeast(.004f)).height(8.dp).background(col)) }
                val rest = 1f - used.toFloat() / win.coerceAtLeast(1)
                if (rest > 0f) Spacer(Modifier.weight(rest))
            }
            Spacer(Modifier.height(10.dp))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                parts.forEach { (n, t, col) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(col)); Spacer(Modifier.width(5.dp)); Txt("$n ${k(t)}", K.sub, 12.sp)
                    }
                }
            }
        }
        KCard {
            KRow("自动压缩", sub = "快到窗口 75% 时在后台整理前情", trailing = { KSwitch(p.foldOn) { p.foldOn = it; Memory.save() } })
            if (p.foldOn) Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FoldModel.MODES.forEach { m -> KChipOn(m, FoldModel.mode == m) { FoldModel.mode = m; FoldModel.save() } }
            }
        }
        val busy = cid != null && cid in Fold.busy
        ToolRow(TI.pkg, if (busy) "正在压缩…" else "压缩上下文", onClick = { if (cid != null && !busy) next { CompressSheet(c) } })
        ToolRow(KI.recall, "压缩与清空记录", onClick = { next { RecordsSheet(c) } })
        val n = (hist.size - CtxCut.at(cid)).coerceAtLeast(0)
        val lim = p.limit
        ToolRow(TI.eraser, "清空上下文", onClick = {
            if (cid != null && hist.isNotEmpty()) { CtxCut.clear(cid, hist.size); done { say("已清空上下文：聊天记录还在，分隔线以上不再发给模型") } }
        }) { Txt(if (lim > 0) "$n/$lim 条消息" else "$n 条消息", K.ink.copy(alpha = .55f), 13.sp, weight = FontWeight.Medium) }
    }

    private fun k(t: Int) = if (t >= 1000) String.format(Locale.US, "%.1fk", t / 1000f) else "$t"

    @Composable
    private fun CompressSheet(c: Ctx) {
        val cid = c.chat.convId ?: return
        var keep by remember { mutableIntStateOf(4) }
        val msgs = c.chat.messages.toList()
        val from = maxOf(Fold.of(c.chat.history.get(cid))?.upto ?: 0, CtxCut.at(cid))
        var users = 0; var cut = msgs.size
        while (cut > from && users < keep) { cut--; if (msgs[cut].role == "user") users++ }
        val n = (cut - from).coerceAtLeast(0)
        KSheetTitle("压缩上下文")
        Txt("把较早的对话收成一份摘要，下次发给模型时用摘要代替原文。聊天记录还在，摘要可以改，也可以撤销。", K.sub, 12.5.sp, Modifier.padding(bottom = 12.dp), lineHeight = 19.sp)
        KCard {
            KRow("保留最近几条用户消息", trailing = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Step("−") { keep = (keep - 1).coerceAtLeast(1) }
                    Txt("$keep", K.ink, 15.sp, weight = FontWeight.Medium)
                    Step("+") { keep = (keep + 1).coerceAtMost(20) }
                }
            })
            KRow("压缩模型", value = FoldModel.model.ifBlank { "跟随当前模型" }, divider = true)
        }
        Txt(if (n >= 2) "将总结较早的 $n 条，原样保留最近 ${msgs.size - cut} 条。" else "要保留的条数已经覆盖全部聊天，没有可压缩的内容了。", K.sub, 12.5.sp, Modifier.padding(start = 2.dp, bottom = 12.dp))
        KButton("开始压缩", Modifier.fillMaxWidth().graphicsLayer { alpha = if (n >= 2) 1f else .4f }, primary = true) {
            if (n < 2) return@KButton
            done()
            scope.launch {
                val ok = runCatching { Fold.run(c.chat.history, cid, false, keep) }.getOrDefault(false)
                if (ok) { CtxCut.logCompress(cid, "$n 条 → 前情"); say("已压缩，聊天里出现前情卡；可在记录里撤销") }
                else say("压缩没有完成")
            }
        }
    }

    @Composable private fun Step(t: String, on: () -> Unit) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(K.card2).pointerInput(t) { detectTapGestures { on() } }, contentAlignment = Alignment.Center) { Txt(t, K.ink, 17.sp) }
    }

    @Composable private fun KChipOn(t: String, on: Boolean, click: () -> Unit) {
        Txt(t, if (on) K.onAcc else K.ink, 12.5.sp, Modifier.clip(RoundedCornerShape(50)).background(if (on) K.acc else K.card2).pointerInput(t) { detectTapGestures { click() } }.padding(horizontal = 11.dp, vertical = 6.dp))
    }

    @Composable
    private fun RecordsSheet(c: Ctx) {
        val cid = c.chat.convId
        val list = CtxCut.of(cid)
        val fmt = remember { SimpleDateFormat("M月d日 HH:mm", Locale.CHINA) }
        KSheetTitle("压缩与清空记录")
        if (list.isEmpty()) Txt("还没有记录", K.sub, 13.5.sp, Modifier.fillMaxWidth().padding(24.dp), align = TextAlign.Center)
        list.forEach { op ->
            KCard {
                KRow(op.kind, sub = fmt.format(Date(op.at)) + " · " + op.info, trailing = {
                    Txt("撤销", K.acc, 13.sp, Modifier.clip(RoundedCornerShape(50)).pointerInput(op) { detectTapGestures { CtxCut.undo(op, c.chat.history); say("已撤销${op.kind}") } }.padding(horizontal = 8.dp, vertical = 4.dp))
                })
            }
        }
    }
}

/** 输入框里的 ＋：点一下开工具面板，开着时转成 ×（弹性 45°）。 */
@Composable
fun PlusButton(onClick: () -> Unit) {
    var down by remember { mutableStateOf(false) }
    val rot by animateFloatAsState(if (ChatTools.open) 45f else 0f, spring(dampingRatio = .5f, stiffness = 400f), label = "plusRot")
    val lit by animateFloatAsState(if (down || ChatTools.open) .15f else .08f, tween(if (down) 80 else 200), label = "plusLit")
    val press by animateFloatAsState(if (down) .92f else 1f, tween(if (down) 70 else 200), label = "plusPress")
    Box(
        Modifier.size(36.dp).graphicsLayer { scaleX = press; scaleY = press }.background(veil(lit), CircleShape)
            .pointerInput(Unit) { detectTapGestures(onPress = { down = true; tryAwaitRelease(); down = false }, onTap = { onClick() }) },
        contentAlignment = Alignment.Center,
    ) { SvgIcon(Ic("M12 5v14M5 12h14"), Page.ink, 18.dp, modifier = Modifier.rotate(rot)) }
}
