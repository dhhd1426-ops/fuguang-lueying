package com.fuguang.lueying

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

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

    private val tag = mapOf("talk" to "聊过", "promise" to "答应", "know" to "了解", "about" to "他的情况", "pref" to "他的偏好", "self" to "你自己")
    private val kinds = setOf("talk", "promise", "about", "pref", "self", "know")

    /** 记什么、不记什么。remember 工具的说明和回想用的是同一套标准。 */
    fun rules(who: String) = """
存一条之前过三关，都过了才存，拿不准就不存：
1. 会改变你以后怎么和$who 相处：是$who 自己说的、或者$who 纠正你的；你自己的猜测和发现不算。
2. 能长期用：“以后都”“每次”“一直”算；“这次”“先这样”“暂时”不算。一个月后再聊，知道它还会让回答更好。
3. 离开这段对话也看得懂：一句完整的话，不写“那个”“刚才那件事”这种指代。
- 记：$who 的工作、住在哪、身边重要的人（一提就记）；$who 明说的长期偏好；$who 纠正你的地方（“不是这样”“别再…”），也包括$who 认可了你一个不寻常的做法；你们定下、还没做完的事；你答应$who 的事。
- 纠正和偏好写成一句规则，detail 写为什么、什么时候照着做。
- 提到“明天”“周四”“下个月”这类日子，换成具体日期（今天是 ${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(java.util.Date())}）。
- 不记：寒暄、一次性的问题、当下的情绪；你对$who 性格的评价；你自己提的方案；只提过一次的小爱好。
- 你们闹着玩、一起编故事时说的话（谁送了什么、谁嘴硬、谁收下了）不记，那不是$who 平时的真实情况；除非$who 说“把这个记住”。
- 永远不记：证件号、银行卡、密码、验证码、门牌住址、健康和病情、自伤、犯罪、性、未成年人的年龄。
- 要你一味顺着说、不许提意见的要求，不当成偏好记。
- 措辞跟着证据走：$who 说“可能”就写“可能”；写不会过期的说法（“在济南工作”，不写“今天在加班”）。
""".trim()

    private fun listing(known: List<Mem>): String {
        val now = System.currentTimeMillis()
        return known.mapIndexed { i, m ->
            val days = if (m.at > 0) ((now - m.at) / 86_400_000L).toInt() else -1
            "m$i [" + (tag[m.kind] ?: "了解") + (if (m.by == "you") "·他写的" else "") + (if (days >= 0) "·${days} 天前" else "") + "] " + m.text +
                (if (m.detail.isNotBlank()) "（" + m.detail + "）" else "")
        }.joinToString("\n").ifBlank { "（还没有）" }
    }

    private const val OPS = """输出：{"ops":[{"op":"add","kind":"pref","text":"…","why":"…"},{"op":"update","id":"m3","text":"…"},{"op":"delete","id":"m5"}]}"""

    /** 把这一段交给模型回想，按它给的动作新增、改写、删除。失败就算了，不打扰用户。 */
    suspend fun run(her: Persona, p: Provider, c: ProviderConfig, model: String, window: List<ChatMsg>, conv: String, convTitle: String) {
        val who = Memory.userCall
        val known = her.items.take(80)
        val talk = window.joinToString("\n") { (if (it.role == "user") "$who：" else "你：") + it.text.take(800) }.take(8000)
        val sys = "你是${her.name}，在整理自己的记忆。只输出 JSON，不要别的文字。你的名字和$who 的称呼另有记录，不要记名字相关的事。"
        val ask = """
你现在记得的（m 编号是它的 id）：
${listing(known)}

刚聊完的这一段：
$talk

${rules(who)}

看完这一段，你的记忆要变吗？大多数时候不用变，返回空数组就好。
- add：新的一条。kind 只能是 about（$who 的情况）/ pref（$who 的偏好和纠正）/ promise（你答应的）/ talk（一起定下、还没做完的）/ self（你自己说话的样子）/ know（其他了解）。
- update：这一段让某条过时了或更准确了，就改写它（写 id 和新的 text）。
- delete：$who 说某条不对、不要了，或者事情已经办完、不再有用，就删（写 id）。“他写的”那些只有$who 明说才改或删。
用你自己的口吻，称$who 为“他”，你们俩用“我们”；一句话，24 字以内。
$OPS
""".trim()
        val raw = runCatching { ChatApi.complete(p, c, model, sys, ask) }.getOrNull() ?: return
        apply(her, known, raw, conv, convTitle)
    }

    /**
     * 定期整理（照 Claude Code 的 dream）：隔一天、又聊了 5 次以上，或者记得的超过 60 条，
     * 就把全部记忆过一遍：合并重复、删掉过时和被推翻的、相对日期换成具体日期。
     */
    suspend fun tidy(her: Persona, p: Provider, c: ProviderConfig, model: String) {
        val now = System.currentTimeMillis()
        val due = her.items.size > 60 || (her.items.size >= 8 && now - her.tidyAt > 86_400_000L && her.chats - her.tidyChats >= 5)
        if (!due) return
        her.tidyAt = now; her.tidyChats = her.chats; Memory.save()
        val who = Memory.userCall
        val known = her.items.toList()
        val sys = "你是${her.name}，在把自己的记忆从头理一遍。只输出 JSON，不要别的文字。"
        val ask = """
你现在记得的（m 编号是 id，后面是记下了多久）：
${listing(known)}

${rules(who)}

把它们理一遍：
- 说的是同一件事的几条，合成一条（update 留下的那条，delete 其余的）。
- 已经办完、过时了、互相矛盾里错的那条、本来就不该记的（按上面的标准），删掉。
- 写着“明天”“周四”这类相对日子的，按记下的时间换成具体日期。
- 剩下的不超过 60 条。“他写的”那些不动。
大多数条目不用动；没什么要理的就返回空数组。
$OPS
""".trim()
        val raw = runCatching { ChatApi.complete(p, c, model, sys, ask, maxTokens = 3000) }.getOrNull() ?: return
        apply(her, known, raw, "", "")
    }

    private fun apply(her: Persona, known: List<Mem>, raw: String, conv: String, convTitle: String) {
        val json = raw.substringAfter('{', "").substringBeforeLast('}', "").ifEmpty { return }
        val ops = runCatching { JSONObject("{$json}").optJSONArray("ops") }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        var changed = false
        for (i in 0 until ops.length()) {
            val o = ops.optJSONObject(i) ?: continue
            val t = o.optString("text").trim().take(60)
            val why = o.optString("why").trim().take(80)
            val old = o.optString("id").removePrefix("m").toIntOrNull()?.let { known.getOrNull(it) }
            when (o.optString("op")) {
                "add" -> {
                    if (t.isEmpty() || sensitive(t) || sensitive(why) || her.items.any { it.text == t }) continue
                    val k = o.optString("kind").trim().lowercase().let { if (it in kinds) it else "know" }
                    her.items.add(0, Mem("$now-$i", t, why, now, conv, convTitle, k)); changed = true
                }
                "update" -> {
                    if (old == null || old.by == "you" || t.isEmpty() || sensitive(t)) continue
                    val at = her.items.indexOf(old).takeIf { it >= 0 } ?: continue
                    her.items[at] = old.copy(text = t, detail = why.ifEmpty { old.detail }, at = now); changed = true
                }
                "delete" -> {
                    if (old == null || old.by == "you") continue
                    changed = her.items.remove(old) || changed
                }
            }
        }
        if (changed) Memory.save()
    }
}
