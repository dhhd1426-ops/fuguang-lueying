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
只记 $who 自己明确说出来的、一个月后还用得上的事。先问自己：一个月后再聊，知道这件事会让回答更好吗？不会就不记。
- 记：$who 的工作、住在哪、身边重要的人（一提就记）；$who 明确说的长期偏好（喜欢你怎么说话、怎么称呼、回答长短）；你们一起定下、还没做完的事；你答应$who、之后要兑现的事。
- 不记：寒暄、一次性的问题、当下的情绪和玩笑；你的猜测、推断和对$who 性格的评价；你自己提的方案；只提过一次的小爱好。
- 角色扮演、故事、玩笑里发生的事不记（谁送了什么、谁嘴硬、谁收下了），那是戏里的，不是$who 的真实情况。除非$who 明说“记住这个设定”。
- 永远不记：证件号、银行卡、密码、验证码、具体住址门牌、健康和病情、自伤、犯罪、性、未成年人的年龄。
- 要你一味顺着说、不许提意见的要求，不当成偏好记。
- 措辞跟着证据走：$who 说“可能”就写“可能”，别写成定论；写不会过期的说法（写“在济南工作”，不写“今天在加班”）。
""".trim()

    /** 把这一段交给模型回想，按它给的动作新增、改写、删除。失败就算了，不打扰用户。 */
    suspend fun run(her: Persona, p: Provider, c: ProviderConfig, model: String, window: List<ChatMsg>, conv: String, convTitle: String) {
        val who = Memory.userCall
        val known = her.items.take(80)
        val list = known.mapIndexed { i, m -> "m$i [" + (tag[m.kind] ?: "了解") + (if (m.by == "you") "·他写的" else "") + "] " + m.text }.joinToString("\n").ifBlank { "（还没有）" }
        val talk = window.joinToString("\n") { (if (it.role == "user") "$who：" else "你：") + it.text.take(800) }.take(8000)
        val sys = "你是${her.name}，在整理自己的记忆。只输出 JSON，不要别的文字。你的名字和$who 的称呼由设置决定，不要记名字相关的事。"
        val ask = """
你现在记得的（m 编号是它的 id）：
$list

刚聊完的这一段：
$talk

${rules(who)}

看完这一段，你的记忆要变吗？大多数时候不用变，返回空数组就好。
- add：新的一条。kind 只能是 about（$who 的情况）/ pref（$who 的偏好）/ promise（你答应的）/ talk（一起定下、还没做完的）/ self（你自己说话的样子）/ know（其他了解）。
- update：这一段让某条过时了或更准确了，就改写它（写 id 和新的 text）。
- delete：$who 说某条不对、不要了，或者事情已经办完、不再有用，就删（写 id）。“他写的”那些只有$who 明说才改或删。
用你自己的口吻，称$who 为“他”，你们俩用“我们”；一句话，24 字以内，不写日期，不写“用户”。
输出：{"ops":[{"op":"add","kind":"pref","text":"…"},{"op":"update","id":"m3","text":"…"},{"op":"delete","id":"m5"}]}
""".trim()
        val raw = runCatching { ChatApi.complete(p, c, model, sys, ask) }.getOrNull() ?: return
        val json = raw.substringAfter('{', "").substringBeforeLast('}', "").ifEmpty { return }
        val ops = runCatching { JSONObject("{$json}").optJSONArray("ops") }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        var changed = false
        for (i in 0 until ops.length()) {
            val o = ops.optJSONObject(i) ?: continue
            val t = o.optString("text").trim().take(60)
            val old = o.optString("id").removePrefix("m").toIntOrNull()?.let { known.getOrNull(it) }
            when (o.optString("op")) {
                "add" -> {
                    if (t.isEmpty() || sensitive(t) || her.items.any { it.text == t }) continue
                    val k = o.optString("kind").trim().lowercase().let { if (it in kinds) it else "know" }
                    her.items.add(0, Mem("$now-$i", t, "", now, conv, convTitle, k)); changed = true
                }
                "update" -> {
                    if (old == null || old.by == "you" || t.isEmpty() || sensitive(t)) continue
                    val at = her.items.indexOf(old).takeIf { it >= 0 } ?: continue
                    her.items[at] = old.copy(text = t, detail = "", at = now); changed = true
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
