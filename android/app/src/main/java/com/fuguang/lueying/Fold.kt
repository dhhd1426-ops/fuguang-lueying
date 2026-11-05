package com.fuguang.lueying

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * 前情（第 394 版，原型 https://claude.ai/artifact/Jr9YNGMkbogtrGgpf1GcKY 第 5 版，模型放法方案 1）：
 * 聊天聊长了，前面的原话按话题整理成几段（每段记着是第几条到第几条），她每轮先看前情 + 后面的原话；
 * 要细节时用 look_back 翻回那几条原话。原话一条都不删，聊天里照样都在，只是不再每轮整段发出去。
 * 他在这段聊天里定下的要求单独抽出来，每轮放在他最后一句话旁边提醒她。
 *
 * 什么时候整理（照 Claude API 的 compaction：默认 15 万 token，最低 5 万）：这个模型窗口的 75%，最多 15 万。
 * 窗口按「报错学到的 → 供应商模型列表里写的 → 内置表 → 12.8 万」认；用量按供应商回的 usage，没有就估，估的再按 usage 校准。
 * 发出去被拒、报错说太长了：记下窗口，马上整理一次，再发一遍。
 * 原来的「聊天摘要」（第 359–375 版）废弃：模型和写法搬到这里（FoldModel），以前写好的摘要留给跨聊天回忆看。
 */

/** 前情的一段：话题、第几条到第几条（从 1 数，对应聊天里的序号）、这段写了什么。 */
data class FoldSeg(val title: String, val from: Int, val to: Int, val body: String)

/** 一段聊天的前情：前 [upto] 条整理成了 [segs]；[rules] 是他在这段聊天里定下的要求。 */
data class FoldData(val upto: Int, val segs: List<FoldSeg>, val rules: List<String>, val at: Long) {
    fun json(): JSONObject = JSONObject().put("u", upto).put("at", at)
        .put("s", JSONArray(segs.map { JSONObject().put("t", it.title).put("a", it.from).put("b", it.to).put("x", it.body) }))
        .put("r", JSONArray(rules))

    companion object {
        fun of(o: JSONObject?): FoldData? {
            if (o == null) return null
            val s = o.optJSONArray("s") ?: JSONArray()
            val r = o.optJSONArray("r") ?: JSONArray()
            return FoldData(
                o.optInt("u"),
                List(s.length()) { i -> s.getJSONObject(i).let { FoldSeg(it.optString("t"), it.optInt("a"), it.optInt("b"), it.optString("x")) } },
                List(r.length()) { r.optString(it) }.filter { it.isNotBlank() },
                o.optLong("at"),
            ).takeIf { it.upto > 0 }
        }
    }
}

/** 整理前情用的模型和写法（原来的摘要模型，第 365 版；模型选择接着用，写法换成前情的）。 */
object FoldModel {
    private const val KEY = "gist-model"
    private var prefs: Prefs? = null
    private var loaded = false

    var pid by mutableStateOf("")
    var model by mutableStateOf("")
    var prompt by mutableStateOf("")

    val DEFAULT = """
你在帮她整理这段聊天前面的部分，整理好的叫「前情」。以后每一轮她先看前情，要细节时再翻原话。
- 按话题分段。每段第一行写「【话题】第 a–b 条」，a、b 是原话的序号，同一个话题隔开聊的可以写成几段。
- 每段写清说过什么、定下了什么：人名、地名、店名、价格、数量、日期时间都写全。还没办完的事单独一行，以「还没办完：」开头。
- 他在这段聊天里定下的要求（预算、口味、时间、不要什么）另外列在最后「要求：」下面，一条一行，照他的意思写，不添不改。
- 只写真的说过的，不猜、不评价。原来前情里有的都留着；被后来的话改掉的写成最新的。
- 不超过 1200 字。
""".trim()

    val text: String get() = prompt.trim().ifEmpty { DEFAULT }
    val custom: Boolean get() = prompt.isNotBlank() && prompt.trim() != DEFAULT

    fun init(p: Prefs) {
        prefs = p
        if (loaded) return
        loaded = true
        // 原来摘要的写法（"prompt"）是写摘要用的，不拿来整理前情；前情的写法存在 "fold"
        runCatching {
            val o = JSONObject(p.string(KEY) ?: return@runCatching)
            pid = o.optString("pid"); model = o.optString("model"); prompt = o.optString("fold")
        }
    }

    fun save() {
        val p = prefs ?: return
        val o = runCatching { JSONObject(p.string(KEY) ?: "{}") }.getOrDefault(JSONObject())
        p.putString(KEY, o.put("pid", pid).put("model", model).put("fold", prompt).toString())
    }

    fun use(id: String, m: String) { pid = id; model = m; save() }

    fun own(store: AssistantStore) = pid.isNotEmpty() && model.isNotBlank() && store.config(pid).configured

    /** 问一次：自己选了就用自己的，不然交给记忆模型（失败原因记在 MemModel.lastError）。 */
    suspend fun ask(system: String, user: String, maxTokens: Int): String? {
        val store = AttachLook.store ?: return null.also { MemModel.lastError = "App 还没准备好，等一下再试" }
        if (!own(store)) return MemModel.ask(system, user, maxTokens)
        val c = store.config(pid)
        val r = runCatching { ChatApi.complete(provider(pid), c, model, system, user, maxTokens) }
        r.exceptionOrNull()?.let { MemModel.lastError = MemModel.why(it, c.name, model) }
        return r.getOrNull()
    }
}

/** 发出去被拒，报错说内容太长了（limit 是报错里写的上限，看不出来是 0）。 */
class ContextFull(val limit: Int) : Exception("context full")

/** 每个模型的窗口有多大、这一轮大概用了多少（软件内部的事，界面上不显示）。 */
object CtxWin {
    private const val KEY = "ctx-win"
    private var prefs: Prefs? = null
    /** 报错学到的（最准）。 */
    private val learned = HashMap<String, Int>()
    /** 供应商模型列表里写的。 */
    private val listed = HashMap<String, Int>()
    /** 估算 × 这个比例 ≈ 供应商实际算的 token（按 usage 慢慢校准）。 */
    private val ratio = HashMap<String, Float>()

    private const val DEFAULT = 128_000
    private const val MAX_TRIGGER = 150_000

    private fun id(pid: String, model: String) = "$pid|${model.trim().lowercase()}"

    fun init(p: Prefs) {
        prefs = p
        runCatching {
            val o = JSONObject(p.string(KEY) ?: return@runCatching)
            o.optJSONObject("l")?.let { m -> m.keys().forEach { learned[it] = m.optInt(it) } }
            o.optJSONObject("d")?.let { m -> m.keys().forEach { listed[it] = m.optInt(it) } }
            o.optJSONObject("r")?.let { m -> m.keys().forEach { ratio[it] = m.optDouble(it, 1.0).toFloat() } }
        }
    }

    private fun save() {
        val o = JSONObject()
            .put("l", JSONObject(learned.mapValues { it.value }))
            .put("d", JSONObject(listed.mapValues { it.value }))
            .put("r", JSONObject(ratio.mapValues { it.value.toDouble() }))
        prefs?.putString(KEY, o.toString())
    }

    /** 模型列表里带了窗口大小（context_length 之类）：记下。 */
    fun know(pid: String, model: String, n: Int) {
        if (n < 4_000) return
        if (listed.put(id(pid, model), n) != n) save()
    }

    /** 被拒了：报错写了上限就记它，没写就按这次发出去的量打九折。 */
    fun full(pid: String, model: String, limit: Int, sent: Int) {
        val n = if (limit >= 4_000) limit else (sent * 9 / 10).coerceAtLeast(8_000)
        val k = id(pid, model)
        if (learned[k]?.let { it <= n } == true) return
        learned[k] = n; save()
    }

    fun window(pid: String, model: String): Int {
        val k = id(pid, model)
        return learned[k] ?: listed[k] ?: table(model) ?: DEFAULT
    }

    /** 发出去的超过这个就整理：窗口的 75%，最多 15 万（照 Claude API compaction 的默认）。 */
    fun trigger(pid: String, model: String): Int = minOf(MAX_TRIGGER, window(pid, model) * 3 / 4)

    /** 估算换成这个模型的 token。 */
    fun tokens(pid: String, model: String, est: Int): Int = (est * (ratio[id(pid, model)] ?: 1f)).toInt()

    /** 供应商回了这次实际用了多少：校准估算的比例。 */
    fun calibrate(pid: String, model: String, actual: Int, est: Int) {
        if (actual < 1_000 || est < 1_000) return
        val r = (actual.toFloat() / est).coerceIn(.3f, 3f)
        val k = id(pid, model)
        ratio[k] = ratio[k]?.let { it * .6f + r * .4f } ?: r
        save()
    }

    /** 粗估一段字有多少 token：中日韩一个字算 1，其余 10 个字符算 3。 */
    fun est(s: String): Int {
        var cjk = 0; var other = 0
        for (ch in s) if (ch.code >= 0x2E80) cjk++ else other++
        return cjk + other * 3 / 10
    }

    fun est(m: ChatMsg): Int = est(m.text) + m.files.size * 1_200 + 4

    fun est(list: List<ChatMsg>): Int = list.sumOf { est(it) }

    /** 常见模型的窗口（大概数，只在没学到、列表里也没写时用）。 */
    private fun table(model: String): Int? {
        val m = model.trim().lowercase()
        Regex("(?<![a-z0-9.])(\\d{1,4})k(?![a-z0-9])").find(m)?.groupValues?.get(1)?.toIntOrNull()?.let { if (it >= 4) return it * 1_000 }
        return when {
            "claude" in m -> 200_000
            "gemini" in m || "minimax" in m || "qwen-long" in m || "qwen-turbo" in m || m.startsWith("gpt-4.1") -> 1_000_000
            m.startsWith("gpt-5") -> 400_000
            "grok" in m -> 256_000
            "deepseek" in m || "qwen" in m || "kimi" in m || "moonshot" in m || "glm" in m || "doubao" in m || m.startsWith("gpt-4o") -> 128_000
            else -> null
        }
    }

    private val overflowWords = listOf(
        "context length", "context_length", "context window", "maximum context", "prompt is too long", "too many tokens",
        "input is too long", "exceeds the context", "reduce the length", "range of input length", "input length",
        "上下文长度", "上下文过长", "超出最大", "超过最大长度", "输入过长", "超出模型",
    )

    /** 报错是在说内容太长了：返回报错里写的上限（看不出来是 0）；不是这类报错返回 null。 */
    fun overflow(err: String): Int? {
        val e = err.lowercase()
        if (overflowWords.none { it in e }) return null
        Regex("(maximum context length is|maximum context length of|context length is|上限为|最大长度为)\\D{0,12}(\\d{4,8})").find(e)?.groupValues?.get(2)?.toIntOrNull()?.let { return it }
        return Regex("\\d{4,8}").findAll(e).mapNotNull { it.value.toIntOrNull() }.filter { it >= 4_000 }.minOrNull() ?: 0
    }
}

object Fold {
    const val LOOK = "look_back"
    /** 整理时最近这么多原样留着（token）。 */
    private const val KEEP = 8_000
    /** 而且至少留几句他说的话。 */
    private const val KEEP_TURNS = 6
    /** 一次交给模型的原话最多多少字（太长就分几次并进去）。 */
    private const val BATCH = 24_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 正在整理的对话 → 整理到第几条（分隔线先画在那里，小点呼吸）。 */
    val busy = mutableStateMapOf<String, Int>()

    /** 这一轮她看到的前情（look_back 的时间线标题要用）。 */
    @Volatile var shown: FoldData? = null

    /** 开着前情、这段有前情：给她看的那份。 */
    fun of(c: Conv?): FoldData? = c?.fold?.takeIf { Memory.active.foldOn && it.upto < c.msgs.size }

    private fun day(t: Long) = SimpleDateFormat("M 月 d 日", Locale.CHINA).format(Date(t))

    /** 前情正文：给模型看、给她看都用这份。 */
    fun render(f: FoldData, withRules: Boolean): String {
        val segs = f.segs.joinToString("\n\n") { s -> "【${s.title}】第 ${s.from}–${s.to} 条\n${s.body.trim()}" }
        if (!withRules || f.rules.isEmpty()) return segs
        return segs + "\n\n要求：\n" + f.rules.joinToString("\n") { "- $it" }
    }

    /** 发出去的那份：前 upto 条换成前情，接在后面原话的第一句前面。 */
    fun view(c: Conv?, history: List<ChatMsg>): List<ChatMsg> {
        val f = of(c) ?: return history
        if (f.upto >= history.size || history[f.upto].role != "user") return history
        val rest = history.drop(f.upto).toMutableList()
        val head = "（这段聊天前面 ${f.upto} 条已经整理成前情，原话还在，要细节用 $LOOK 按序号翻。）\n前情：\n" + render(f, false) + "\n（前情到这里，下面是后面的原话。）\n\n"
        rest[0] = rest[0].copy(text = head + rest[0].text)
        return rest
    }

    /** 他在这段聊天里定下的要求：每轮放在他最后一句旁边。 */
    fun note(c: Conv?): String {
        val f = of(c) ?: return ""
        if (f.rules.isEmpty()) return ""
        return "（这段聊天里${Memory.userCall}定下的，照着做：" + f.rules.joinToString("；") + "）"
    }

    fun system(): String =
        "这段聊天前面的部分整理成了前情（在对话开头），每段写着是第几条到第几条。前情够答就直接答；要核对${Memory.userCall}当时具体怎么说的，用 $LOOK 翻回那几条原话。"

    val spec = ToolSpec(
        LOOK,
        "翻回这段聊天前面的原话。前情不够细、要核对当时具体怎么说的时候用。seg 写前情里第几段（从 1 数）；或者用 from、to 写原话的序号。",
        JSONObject().put("type", "object").put(
            "properties", JSONObject()
                .put("seg", JSONObject().put("type", "integer").put("description", "前情里第几段，从 1 数"))
                .put("from", JSONObject().put("type", "integer").put("description", "从第几条原话起"))
                .put("to", JSONObject().put("type", "integer").put("description", "到第几条原话")),
        ),
    )

    private fun range(f: FoldData?, a: JSONObject): Triple<Int, Int, String> {
        val seg = a.optInt("seg", 0)
        val s = f?.segs?.getOrNull(seg - 1)
        if (s != null) return Triple(s.from, s.to, s.title)
        val from = a.optInt("from", 0); val to = a.optInt("to", 0)
        if (from > 0) {
            val t = f?.segs?.firstOrNull { from in it.from..it.to }?.title.orEmpty()
            return Triple(from, if (to >= from) to else from + 9, t)
        }
        return Triple(0, 0, "")
    }

    fun liveTitle() = "正在翻回前面"

    fun doneTitle(a: JSONObject): String {
        val (_, _, t) = range(shown, a)
        return if (t.isNotEmpty()) "翻回前面「${t.take(12)}」那段" else "翻回前面的原话"
    }

    fun detail(a: JSONObject): String {
        val (from, to, _) = range(shown, a)
        return if (from > 0) "第 $from–$to 条" else ""
    }

    /** look_back：那几条原话（最多 30 条、每条 800 字）。 */
    fun look(store: ConvStore, conv: String?, a: JSONObject): ToolOut {
        val c = store.get(conv) ?: return ToolOut("这段聊天还没存好，翻不了。")
        val f = c.fold
        var (from, to, _) = range(f, a)
        if (from <= 0) return ToolOut("要写 seg（前情里第几段）或 from、to（原话序号）。")
        to = minOf(to, c.msgs.size, from + 29)
        from = from.coerceIn(1, c.msgs.size)
        if (to < from) return ToolOut("这段聊天只有 ${c.msgs.size} 条。")
        val her = Memory.get(c.who)?.name ?: "你"
        val lines = (from..to).map { n -> c.msgs[n - 1] }.zip(from..to).filter { it.first.text.isNotBlank() }
            .joinToString("\n") { (m, n) -> "[$n] " + (if (m.role == "user") Memory.userCall else her) + "：" + m.text.take(800) }
        return ToolOut("第 $from–$to 条原话：\n$lines")
    }

    /* ---------- 整理 ---------- */

    /** 这一轮说完：发出去的快到窗口的 75% 了，后台整理。sent 是这轮第一次发出去的量（token，供应商回的或估的）。 */
    fun check(store: ConvStore, conv: String?, pid: String, model: String, sent: Int) {
        val id = conv ?: return
        if (!Memory.active.foldOn || id in busy) return
        val c = store.get(id) ?: return
        val last = c.msgs.lastOrNull() ?: return
        val now = sent + CtxWin.tokens(pid, model, CtxWin.est(last))
        if (now < CtxWin.trigger(pid, model)) return
        scope.launch { runCatching { run(store, id, false) } }
    }

    /** 从哪条起原样留着：最近 KEEP token、至少 KEEP_TURNS 句他的话；落在他说的话上。null = 没什么可整理的。 */
    private fun cutAt(msgs: List<ChatMsg>, from: Int, keep: Int, turns: Int): Int? {
        var i = msgs.size; var tokens = 0; var users = 0
        while (i > from) {
            val m = msgs[i - 1]
            val t = CtxWin.est(m)
            if (tokens + t > keep && users >= turns) break
            tokens += t; i--
            if (m.role == "user") users++
        }
        var cut = i
        while (cut < msgs.size && msgs[cut].role != "user") cut++
        return cut.takeIf { it > from && it < msgs.size && it - from >= 2 }
    }

    /** 整理一次（force：发出去被拒了，留得少一点）。整理好了返回 true。 */
    suspend fun run(store: ConvStore, id: String, force: Boolean): Boolean {
        if (id in busy) return false
        val c = store.get(id) ?: return false
        val msgs = c.msgs
        val old = c.fold?.takeIf { it.upto < msgs.size }
        val from = old?.upto ?: 0
        val cut = cutAt(msgs, from, if (force) KEEP / 2 else KEEP, if (force) 2 else KEEP_TURNS) ?: return false
        val her = Memory.get(c.who) ?: Memory.active
        busy[id] = cut
        try {
            var fold = old
            var start = from
            while (start < cut) {
                var end = start; var len = 0
                while (end < cut && (end == start || len < BATCH)) { len += minOf(msgs[end].text.length, 2_000); end++ }
                while (end < cut && msgs[end].role != "user") end++
                fold = ask(her, fold, msgs, start, end) ?: break
                start = end
            }
            if (fold == null || fold.upto <= from) return false
            store.setFold(id, fold)
            return true
        } finally { busy.remove(id) }
    }

    /** 前情卡里「重新整理」：从头整理到原来那条。 */
    fun redo(store: ConvStore, id: String) {
        val c = store.get(id) ?: return
        val upto = c.fold?.upto ?: return
        if (id in busy) return
        val her = Memory.get(c.who) ?: Memory.active
        val keepRules = c.fold?.rules.orEmpty()
        scope.launch {
            busy[id] = upto
            try {
                var fold: FoldData? = null
                var start = 0
                while (start < upto) {
                    var end = start; var len = 0
                    while (end < upto && (end == start || len < BATCH)) { len += minOf(c.msgs[end].text.length, 2_000); end++ }
                    while (end < upto && c.msgs[end].role != "user") end++
                    val prev = fold
                    fold = (try { ask(her, prev, c.msgs, start, end) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }) ?: break
                    start = end
                }
                // 要求是他在卡里删改过的那份：重新整理只换前情的段落，不把删掉的要求加回来
                val done = fold
                if (done != null && done.upto == upto) store.setFold(id, done.copy(rules = keepRules))
            } finally { busy.remove(id) }
        }
    }

    /** 请模型把 [start, end) 这几条并进前情；没成返回 null。 */
    private suspend fun ask(her: Persona, old: FoldData?, msgs: List<ChatMsg>, start: Int, end: Int): FoldData? {
        val who = Memory.userCall
        val talk = (start until end).filter { msgs[it].text.isNotBlank() }.joinToString("\n") { i ->
            "[${i + 1}] " + (if (msgs[i].role == "user") who else her.name) + "：" + msgs[i].text.take(2_000)
        }
        if (talk.isBlank()) return old?.copy(upto = end) ?: FoldData(end, emptyList(), emptyList(), System.currentTimeMillis())
        val system = FoldModel.text + "\n\n这里的「她」是${her.name}，「他」是$who。称呼用「$who」和「${her.name}」，不用「我」「你」。直接输出前情正文，不要解释、不要代码块。"
        val user = buildString {
            append("今天是 ").append(SimpleDateFormat("yyyy年M月d日 EEEE", Locale.CHINA).format(Date())).append("。\n\n")
            append("原来的前情：\n").append(old?.let { render(it, true) } ?: "（还没有）").append("\n\n")
            append(if (old == null) "这段聊天前面的原话" else "后来又聊的原话").append("（方括号里是序号，第 ${start + 1}–$end 条）：\n").append(talk).append("\n\n")
            append(if (old == null) "把这些整理成前情。" else "把后来聊的并进原来的前情，整份重写；原来的段和序号照留，新的接着写。")
        }
        val raw = FoldModel.ask(system, user, 4_000) ?: return null
        return parse(raw.trim().removePrefix("```").removeSuffix("```").trim(), end, old, start)
    }

    private val header = Regex("^\\s*[#*\\s]*【(.+?)】(.*)$")
    private val span = Regex("第?\\s*(\\d+)\\s*[–\\-~～—至到]+\\s*(\\d+)\\s*条?")
    private val rulesHead = Regex("^\\s*[#*\\s]*(这段聊天的)?要求\\s*[:：]?\\s*$")
    private val bullet = Regex("^\\s*([-•·*]|\\d+[.、)）])\\s*")

    /** 读模型写的前情：「【话题】第 a–b 条」起一段，「要求：」下面一条一行。读不出段落就整篇算一段。 */
    private fun parse(raw: String, upto: Int, old: FoldData?, start: Int): FoldData? {
        if (raw.isBlank()) return null
        val segs = mutableListOf<FoldSeg>()
        val rules = mutableListOf<String>()
        var title: String? = null; var a = 0; var b = 0
        val body = StringBuilder()
        var inRules = false
        fun close() {
            val t = title ?: return
            val from = (if (a > 0) a else (segs.lastOrNull()?.to ?: 0) + 1).coerceIn(1, upto)
            val to = (if (b >= a && b > 0) b else upto).coerceIn(from, upto)
            segs += FoldSeg(t.take(24), from, to, body.toString().trim().take(1_200))
            title = null; body.clear(); a = 0; b = 0
        }
        for (line in raw.lines()) {
            if (rulesHead.matches(line)) { close(); inRules = true; continue }
            val h = header.find(line)
            if (h != null) {
                close(); inRules = false
                val inner = h.groupValues[1]; val tail = h.groupValues[2]
                val sp = span.find(inner) ?: span.find(tail)
                a = sp?.groupValues?.get(1)?.toIntOrNull() ?: 0
                b = sp?.groupValues?.get(2)?.toIntOrNull() ?: 0
                title = inner.replace(span, "").replace(Regex("[（(][^（()）]*[)）]\\s*$"), "").trim().ifEmpty { "前面聊的" }
                tail.replace(span, "").trim().takeIf { it.isNotEmpty() }?.let { body.append(it).append('\n') }
                continue
            }
            if (inRules) { line.replace(bullet, "").trim().takeIf { it.isNotEmpty() }?.let { rules += it.take(80) }; continue }
            if (title != null) body.append(line).append('\n')
        }
        close()
        if (segs.isEmpty()) {
            // 没按格式写：整篇算一段，接在原来的后面
            val text = raw.lines().filterNot { rulesHead.matches(it) }.joinToString("\n").trim()
            segs += old?.segs.orEmpty()
            segs += FoldSeg("前面聊的", start + 1, upto, text.take(1_200))
            if (rules.isEmpty()) rules += old?.rules.orEmpty()
        }
        return FoldData(upto, segs, rules.distinct().take(12), System.currentTimeMillis())
    }

    /* ---------- 前情卡里删一条要求 / 撤销 ---------- */

    fun dropRule(store: ConvStore, id: String, rule: String) {
        val f = store.get(id)?.fold ?: return
        store.setFold(id, f.copy(rules = f.rules.filter { it != rule }))
    }

    fun putRule(store: ConvStore, id: String, rule: String, at: Int) {
        val f = store.get(id)?.fold ?: return
        if (rule in f.rules) return
        val r = f.rules.toMutableList(); r.add(at.coerceIn(0, r.size), rule)
        store.setFold(id, f.copy(rules = r))
    }

    /** 跨聊天回忆看的：没有旧摘要的段落用前情顶上。 */
    fun gist(c: Conv): String = c.fold?.let { f -> f.segs.joinToString("\n") { "【${it.title}（${day(f.at)}）】\n${it.body}" } }.orEmpty()
}
