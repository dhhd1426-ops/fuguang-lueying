package com.fuguang.lueying

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar

/**
 * 新对话正中那一句问候：由她自己想。
 * 她看得到此刻几点、星期几、他多久没来、认识第几天、这几天的节气节日、她记得的事、上次聊到哪，
 * 口吻是她自己的（成熟、温和，不撒娇），一句不超过十六个字、带标点。
 * 开新对话时：手上有刚想好的（一个半小时内）就直接用，没有就当场问她（最多等 [WAIT] 毫秒），再不行用备用表。
 * 用掉一句后，后台接着想下一句。
 */
object Greet {
    private const val WAIT = 2600L
    private const val FRESH = 90L * 60_000
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** 每位助理想好、还没用的一句：(想好的时间, 句子)。 */
    private val ready = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()
    /** 每位助理最近说过的几句，叫她别重复。 */
    private val said = HashMap<String, ArrayDeque<String>>()

    /** 开新对话时拿一句。 */
    suspend fun line(p: Persona, store: AssistantStore, convs: ConvStore): String {
        val now = System.currentTimeMillis()
        val got = ready.remove(p.id)?.takeIf { now - it.first < FRESH }?.second
            ?: withTimeoutOrNull(WAIT) { ask(p, store, convs) }
            ?: spare(p)
        remember(p.id, got)
        scope.launch { ask(p, store, convs)?.let { ready[p.id] = System.currentTimeMillis() to it } }
        return got
    }

    private fun remember(id: String, s: String) {
        val q = said.getOrPut(id) { ArrayDeque() }
        q.addLast(s); while (q.size > 6) q.removeFirst()
    }

    private suspend fun ask(p: Persona, store: AssistantStore, convs: ConvStore): String? {
        val c = store.config(store.activeProvider)
        val model = store.activeModel
        if (!c.configured || model.isBlank()) return null
        val raw = runCatching { ChatApi.complete(provider(store.activeProvider), c, model, Prompt.system(p, model), prompt(p, convs), maxTokens = 200) }.getOrNull()
        return clean(raw)
    }

    private fun prompt(p: Persona, convs: ConvStore): String {
        val who = Memory.userCall
        val sb = StringBuilder(Prompt.aside(p, emptyList(), p.lastAt))
        season()?.let { sb.append("\n（").append(it).append("）") }
        convs.of(p.id).maxByOrNull { it.updated }?.let { last ->
            val tail = last.msgs.takeLast(4).joinToString(" / ") { (if (it.role == "user") who else "你") + "：" + it.text.replace("\n", " ").take(50) }
            if (tail.isNotBlank()) sb.append("\n（你们上次聊的是「").append(last.title).append("」，最后几句：").append(tail).append("）")
        }
        said[p.id]?.takeIf { it.isNotEmpty() }?.let { sb.append("\n（你最近打招呼说过：").append(it.joinToString(" ")).append(" 这次换个说法。）") }
        sb.append("\n\n").append(who).append("刚来找你，还没开口。你先跟").append(who).append("打个招呼，只说一句：不超过十六个字，带标点，")
            .append("像你平时那样自然地说。语气成熟、温和，像知性的大姐姐，不撒娇，不卖萌，不用表情和颜文字。")
            .append("可以从此刻、节令、").append(who).append("最近的事或你们上次聊的说起。只写这一句，不加引号。")
        return sb.toString()
    }

    /** 只留第一行、去掉引号；太长或空的不要；没有标点结尾补一个句号。 */
    private fun clean(raw: String?): String? {
        var s = raw?.trim()?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim() ?: return null
        s = s.trim('"', '“', '”', '「', '」', '『', '』', '\'', ' ')
        if (s.isEmpty() || s.length > 24) return null
        if (s.last() !in "。！？…，～.!?") s += "。"
        return s
    }

    /** 这几天的节气、节日（节气按常见日期，前后一天都算）。 */
    private fun season(): String? {
        val c = Calendar.getInstance()
        val m = c.get(Calendar.MONTH) + 1
        val d = c.get(Calendar.DAY_OF_MONTH)
        FESTIVALS[m * 100 + d]?.let { return "今天是$it" }
        TERMS.firstOrNull { (mm, dd, _) -> mm == m && kotlin.math.abs(dd - d) <= 1 }?.let { return "这几天是${it.third}" }
        return null
    }

    private val FESTIVALS = mapOf(101 to "元旦", 214 to "情人节", 501 to "劳动节", 1001 to "国庆节", 1224 to "平安夜", 1225 to "圣诞节", 1231 to "一年的最后一天")
    private val TERMS = listOf(
        Triple(1, 5, "小寒"), Triple(1, 20, "大寒"), Triple(2, 4, "立春"), Triple(2, 19, "雨水"), Triple(3, 5, "惊蛰"), Triple(3, 20, "春分"),
        Triple(4, 4, "清明"), Triple(4, 20, "谷雨"), Triple(5, 5, "立夏"), Triple(5, 21, "小满"), Triple(6, 5, "芒种"), Triple(6, 21, "夏至"),
        Triple(7, 7, "小暑"), Triple(7, 22, "大暑"), Triple(8, 7, "立秋"), Triple(8, 23, "处暑"), Triple(9, 7, "白露"), Triple(9, 23, "秋分"),
        Triple(10, 8, "寒露"), Triple(10, 23, "霜降"), Triple(11, 7, "立冬"), Triple(11, 22, "小雪"), Triple(12, 7, "大雪"), Triple(12, 21, "冬至"),
    )

    /** 想不出来时（没网、没配好）：按情形从备用表里挑，口吻同上。{n} 是他的名字，没填名字时连逗号一起去掉。 */
    fun spare(p: Persona): String {
        val c = Calendar.getInstance()
        val h = c.get(Calendar.HOUR_OF_DAY)
        val wd = c.get(Calendar.DAY_OF_WEEK)
        val now = System.currentTimeMillis()
        val gap = if (p.lastAt > 0) now - p.lastAt else -1L
        val pool = when {
            p.chats <= 0 -> FIRST
            gap > 3 * 86_400_000L -> LONG
            gap in 0..3_600_000L -> BACK
            (wd == Calendar.SATURDAY || wd == Calendar.SUNDAY) && h in 9..17 -> WEEKEND
            wd == Calendar.FRIDAY && h >= 18 -> FRIDAY
            else -> SLOTS.first { h in it.first }.second
        }
        val recent = said[p.id].orEmpty()
        val pick = pool.filter { it !in recent }.ifEmpty { pool }.random()
        val n = Memory.userName.trim()
        return if (n.isNotEmpty()) pick.replace("{n}", n) else pick.replace(Regex("，?\\{n\\}"), "")
    }

    private val SLOTS = listOf(
        0..4 to listOf("这么晚了，还没睡？", "又睡不着了？我在。", "夜深了，{n}。", "说好的早睡呢？"),
        5..8 to listOf("早，{n}。", "醒了？先喝口温水。", "早安，昨晚睡得好吗？", "吃了早饭再出门。"),
        9..10 to listOf("上午好，{n}。", "今天打算做些什么？", "忙起来了吗？"),
        11..13 to listOf("午饭吃了吗？", "中午了，歇一歇吧。", "先去吃饭，我等你。"),
        14..17 to listOf("下午好，{n}。", "困了就喝口茶。", "下午过得还好吗？"),
        18..19 to listOf("下班了吗？", "傍晚了，{n}。", "今天辛苦了。"),
        20..23 to listOf("晚上好，{n}。", "今天累不累？", "忙完了？过来坐会儿。", "今天过得怎么样？"),
    )
    private val FIRST = listOf("你好，{n}。", "终于见到你了。", "初次见面，{n}。")
    private val LONG = listOf("好久不见，{n}。", "这几天，过得还好吗？", "回来就好。", "我一直在等你。")
    private val BACK = listOf("又回来了？", "还有话没说完吧。", "我还在这儿。")
    private val WEEKEND = listOf("周末了，好好歇歇。", "难得周末，{n}。", "今天不用忙了吧？")
    private val FRIDAY = listOf("周五了，这周辛苦了。", "总算到周五了。", "这周过得还好吗？")
}
