package com.fuguang.lueying

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 回想：对话停下来以后，她把这一段从头看一遍，决定记忆要不要变。
 * 照 Claude App 和 RikkaHub：不是每句都记，而是整段看完再整理；能新增，也能改写、删掉旧的；
 * 只记对方自己说过、一个月后还用得上的事。剧情里发生的事、寒暄、一次性的问题都不记。
 */
object Reflect {
    /** 停手多久算这段聊完了。 */
    private const val QUIET = 90_000L
    /** 一次最多回看几条消息。 */
    private const val WINDOW = 16

    /** 不跟着页面走：离开聊天页也照样回想。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pendingConv: String? = null
    private var pendingJob: Job? = null
    private var pendingRun: (suspend () -> Unit)? = null
    /** 每段对话已经回想到第几条（只在这次打开 App 期间记着，重开后从最近一段看起）。 */
    private val upTo = mutableMapOf<String, Int>()

    /**
     * 每轮回答完调一次：等对方停手一会儿再回想。期间又说话就重新等；
     * 换到别的对话时，上一段马上回想，不等。
     */
    fun later(conv: String, msgs: List<ChatMsg>, run: suspend (List<ChatMsg>) -> Unit) {
        if (conv.isEmpty()) return
        val from = (upTo[conv] ?: 0).coerceAtMost(msgs.size)
        val window = msgs.subList(maxOf(from, msgs.size - WINDOW), msgs.size).toList()
        if (window.none { it.role == "user" }) return
        val block: suspend () -> Unit = { upTo[conv] = msgs.size; run(window) }
        val prev = pendingRun
        pendingJob?.cancel()
        if (pendingConv != null && pendingConv != conv && prev != null) scope.launch { runCatching { prev() } }
        pendingConv = conv; pendingRun = block
        pendingJob = scope.launch {
            delay(QUIET)
            if (pendingRun === block) { pendingRun = null; pendingConv = null }
            runCatching { block() }
        }
    }

    /** 证件号、银行卡、手机号这类，提示词说了不记，写入前再硬拦一道（照 RikkaHub 的 MemoryContentGuard）。 */
    private val secret = listOf(
        Regex("\\d{17}[\\dXx]"),
        Regex("(?<!\\d)\\d{16,19}(?!\\d)"),
        Regex("(?<!\\d)1[3-9]\\d{9}(?!\\d)"),
        Regex("(密码|验证码|口令)\\s*[:：是为]?\\s*\\S{4,}"),
    )

    fun sensitive(s: String) = secret.any { it.containsMatchIn(s) }

    /* ---------- 第 345 版：记忆模型写「标题 + 正文」，每天整理，按周月年收拢 ---------- */

    /** 记忆模型的活一件一件做：记下、整理、收拢都改同一份记忆，不能同时动。 */
    private val lock = Mutex()
    private val kinds = setOf("about", "pref", "self", "lesson", "promise", "talk")
    private fun kindOf(s: String, fallback: String) = s.trim().lowercase().let { when { it in kinds -> it; it == "know" -> "talk"; else -> fallback } }

    private fun sys(her: Persona) = "你是${her.name}的记忆，替她记下和整理她记得的事。称${Memory.userCall}为“他”或他的名字，她自己用“我”。只输出 JSON，不要别的文字。"
    private fun today() = SimpleDateFormat("yyyy年M月d日 EEEE", Locale.CHINA).format(Date())

    /** 给记忆模型看的目录：编号、日期、类别、标题，不是周月年那份的带正文（截一截）；mark 里的标 *。 */
    private fun listing(p: Persona, mark: Set<String> = emptySet()): String {
        val secs = MemTree.sections(p)
        if (secs.isEmpty()) return "（还没有）"
        return secs.joinToString("\n\n") { s ->
            "【${s.name}】\n" + s.items.joinToString("\n") { m ->
                (if (m.id in mark) "* " else "- ") + "[${m.code}] ${MemTree.date(m)} · ${MemTree.kindZh(m.kind)}" +
                    (if (m.pin) " · 常驻" else "") + (if (m.by == "you") " · 他亲手写的" else "") + " · 标题：" + m.text +
                    (if (!m.span) "\n  正文：" + MemTree.body(m).replace("\n", " / ").take(if (m.id in mark) 1500 else 240) else "")
            }
        }
    }

    private const val OPS = """输出：{"ops":[{"op":"add","kind":"pref","title":"…","body":"…","pin":false},{"op":"update","id":"编号","title":"…","body":"…","kind":"…","pin":false},{"op":"delete","id":"编号"}]}
kind 只能是 about（关于你）/ pref（你喜欢的，也包括他对她的纠正）/ lesson（我学会的，做事上的）/ self（关于我，她自己）/ talk（聊过的、发生的事）/ promise（答应你的）。
update 只写要改的字段；正文分段用换行。没什么要变就返回 {"ops":[]}。"""

    /** 聊完回头看这一段：新开、更新，或者不动。失败就算了，不打扰用户。 */
    suspend fun run(her: Persona, window: List<ChatMsg>, conv: String, convTitle: String) {
        lock.withLock {
            val who = Memory.userCall
            val talk = window.joinToString("\n") { (if (it.role == "user") "$who：" else "${her.name}：") + it.text.take(800) }.take(8000)
            val ask = """
${MemModel.text}

今天是 ${today()}。

她现在的目录（方括号里是编号）：
${listing(her)}

刚聊完的这一段：
$talk

$OPS
""".trim()
            val raw = MemModel.ask(sys(her), ask, 3000) ?: return
            apply(her, raw, conv, convTitle, emptySet())
        }
    }

    /** 她当场记下的、你手写的、做事上记的一句：交给记忆模型写成标题 + 正文，或者并进说同一件事的那条。 */
    fun place(p: Persona, m: Mem) { scope.launch { runCatching { placeNow(p, m) } } }

    private suspend fun placeNow(p: Persona, m: Mem) {
        lock.withLock {
            if (p.items.none { it.id == m.id }) return
            val ask = """
${MemModel.text}

今天是 ${today()}。

她刚记下一件事：目录里标 * 的那条（编号 ${m.code}），现在只有原话。${if (m.by == "you") "这是他亲手写下的，正文用他的原话，一个字不改，只在前面加一句时间。" else ""}

她的目录：
${listing(p, setOf(m.id))}

把标 * 的这条写好：是新的事，就 update 它，给标题、正文、类别、要不要常驻；和目录里另一条说的是同一件事，就把内容并进那条（update 那条），再 delete 标 * 的这条。

$OPS
""".trim()
            val raw = MemModel.ask(sys(p), ask, 2000) ?: return
            apply(p, raw, m.conv, m.convTitle, setOf(m.id))
        }
    }

    /**
     * 每天第一次来找她（发出第一句时调）：后台把之前还没整理过的理一遍，再把过完的周、月、年收拢。
     * 按聊过的那天算：今天新记的留到下次来再理。
     */
    fun daily(p: Persona) {
        val today = MemTree.dayKey(System.currentTimeMillis())
        if (p.tidyDay == today) return
        p.tidyDay = today; Memory.save()
        scope.launch { runCatching { tidy(p) }; runCatching { roll(p) } }
    }

    private suspend fun tidy(her: Persona) {
        val start = MemTree.dayStart(System.currentTimeMillis())
        repeat(20) {
            val ok = lock.withLock {
                val batch = MemTree.top(her).filter { !it.done && !it.span && it.at < start }.sortedBy { it.at }.take(10)
                if (batch.isEmpty()) return
                val ids = batch.map { it.id }.toSet()
                val ask = """
${MemModel.text}

今天是 ${today()}。现在是每天的整理：目录里标 * 的那几条还没整理过。

${listing(her, ids)}

把标 * 的这几条理一遍，对照整个目录：
- 和别的条说的是同一件事：并成一条（update 留下的那条，可以改标题、补正文），delete 多余的。
- 被后来的话推翻了、过时了、事情已经办完不再有用的：改掉或删掉。
- 标题不够准、类别贴错的改过来；正文不合上面写法的（抒情多、事实少、没有层次），照写法重写。
- 他亲手写的，原话不改。
- 大多数条目不用动。

$OPS
""".trim()
                val raw = MemModel.ask(sys(her), ask, 4000) ?: return@withLock false
                apply(her, raw, "", "", emptySet())
                ids.forEach { id -> val i = her.items.indexOfFirst { it.id == id }; if (i >= 0) her.items[i] = her.items[i].copy(done = true) }
                Memory.save()
                true
            }
            if (!ok) return
        }
    }

    /** 收拢：过完的周归成「这一周」，过完的月把几周归成「这个月」，过完的年把几个月归成「这一年」。 */
    private suspend fun roll(her: Persona) {
        val now = System.currentTimeMillis()
        up(her, "week", "周", MemTree.weekStart(now), { MemTree.weekStart(it) }, { s -> s + 7 * MemTree.DAY - 1 }) { !it.rule && !it.span && it.done }
        up(her, "month", "个月", MemTree.monthStart(now), { MemTree.monthStart(it) }, { s -> Calendar.getInstance().apply { timeInMillis = s; add(Calendar.MONTH, 1) }.timeInMillis - 1 }) { it.kind == "week" }
        up(her, "year", "年", MemTree.yearStart(now), { MemTree.yearStart(it) }, { s -> Calendar.getInstance().apply { timeInMillis = s; add(Calendar.YEAR, 1) }.timeInMillis - 1 }) { it.kind == "month" }
    }

    private suspend fun up(her: Persona, kind: String, unit: String, current: Long, startOf: (Long) -> Long, endOf: (Long) -> Long, pick: (Mem) -> Boolean) {
        val groups = lock.withLock { MemTree.top(her).filter(pick).groupBy { startOf(it.at) }.filterKeys { it < current }.toSortedMap() }
        for ((start, ms) in groups) {
            lock.withLock {
                val live = ms.mapNotNull { m -> her.items.firstOrNull { it.id == m.id } }
                if (live.isEmpty()) return@withLock
                // 这一段已经归过一份（后来又理出几天）：并进那份，重写
                val old = MemTree.top(her).firstOrNull { it.kind == kind && it.at == start }
                val all = (old?.let { MemTree.kids(her, it) }.orEmpty() + live).distinctBy { it.id }.sortedBy { it.at }
                val ask = """
今天是 ${today()}。把下面这一${unit}里的记忆归成一份「这一$unit」，以后她先看这一份，需要细节再往下翻。

${all.joinToString("\n\n") { "[${it.code}] ${MemTree.date(it)} · ${MemTree.kindZh(it.kind)} · ${it.text}\n" + MemTree.body(it) }}

写一份：
- title：一句话，说清这一${unit}里最要紧的是什么。
- body：她的第一人称，他用名字或「他」，照事实，不添不猜。先总说这一${unit}里哪些时候聊过，再分几块说发生了什么、有什么变化；比下面每条概括，答应了还没做的事要写上。分段用换行。
输出：{"title":"…","body":"…"}
""".trim()
                val raw = MemModel.ask(sys(her), ask, 2500) ?: return@withLock
                val o = runCatching { JSONObject("{" + raw.substringAfter('{', "").substringBeforeLast('}', "") + "}") }.getOrNull() ?: return@withLock
                val title = o.optString("title").trim().trimEnd('。').take(60)
                val body = o.optString("body").trim().take(3000)
                if (title.isEmpty() || body.isEmpty()) return@withLock
                val span = if (old != null) old.copy(text = title, diary = body).also { n -> her.items[her.items.indexOf(old)] = n }
                else Mem("${System.currentTimeMillis()}-$kind", title, "", start, "", "", kind, "her", diary = body, done = true, till = endOf(start)).also { her.items.add(it) }
                live.forEach { m -> val i = her.items.indexOfFirst { it.id == m.id }; if (i >= 0) her.items[i] = her.items[i].copy(under = span.id) }
                Memory.save()
            }
        }
    }

    private fun apply(her: Persona, raw: String, conv: String, convTitle: String, mark: Set<String>) {
        val json = raw.substringAfter('{', "").substringBeforeLast('}', "").ifEmpty { return }
        val ops = runCatching { JSONObject("{$json}").optJSONArray("ops") }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        var changed = false
        for (i in 0 until ops.length()) {
            val o = ops.optJSONObject(i) ?: continue
            val title = o.optString("title").trim().trimEnd('。').take(60)
            val body = o.optString("body").trim().take(3000)
            val old = o.optString("id").trim().trim('[', ']', '#', '*', ' ').takeIf { it.isNotEmpty() }?.let { c -> her.items.firstOrNull { it.code == c } }
            when (o.optString("op")) {
                "add" -> {
                    if (title.isEmpty() || sensitive(title) || sensitive(body) || her.items.any { it.text == title }) continue
                    val k = kindOf(o.optString("kind"), "talk")
                    her.items.add(0, Mem("$now-$i", title, "", now, conv, convTitle, k, "her", diary = body, pin = o.optBoolean("pin"), scene = if (k == Lessons.KIND) Lessons.guessScene(title + body) else ""))
                    changed = true
                }
                "update" -> {
                    if (old == null || old.span || sensitive(title) || sensitive(body)) continue
                    val at = her.items.indexOf(old).takeIf { it >= 0 } ?: continue
                    val k = if (o.has("kind")) kindOf(o.optString("kind"), old.kind) else old.kind
                    // 他亲手写的：原话不改（第一次写正文时照原话加时间，由模型照提示词做）
                    val keep = old.by == "you" && old.diary.isNotBlank()
                    her.items[at] = old.copy(
                        text = title.ifEmpty { old.text },
                        diary = if (keep || body.isEmpty()) old.diary else body,
                        kind = k, pin = if (o.has("pin")) o.optBoolean("pin") else old.pin,
                        scene = if (k == Lessons.KIND && old.scene.isEmpty()) Lessons.guessScene(title + body) else old.scene,
                    )
                    changed = true
                }
                "delete" -> {
                    if (old == null || old.span || (old.by == "you" && old.id !in mark)) continue
                    changed = her.items.remove(old) || changed
                }
            }
        }
        if (changed) Memory.save()
    }
}
