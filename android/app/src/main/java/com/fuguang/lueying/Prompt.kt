package com.fuguang.lueying

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 她读到的东西怎么拼（第 130 版）。
 *
 * 第一原则：她读到的每个字都在说她自己。只用「你是…」「你记得…」「你能…」，
 * 不出现扮演、角色、设定、提示词、系统、AI、助手、模型、用户、根据记忆这类戏外的词。
 *
 * 上半段（system）每一轮都一样，供应商能缓存：
 *   名字 → [条目：她是谁之前] → 你写的她 → [条目：她是谁之后] → 她自己的样子 → 关于他 → 他的偏好 → 她记得的事 → 收尾一句
 * 会变的放在对话里（见 [aside] 和 Entries.weave）：时间旁白、按这句话想起的事、按关键词出现的条目。
 */
object Prompt {
    /** 一条记忆在她心里的样子：一个多月前的，带一句「可能变了」。 */
    private fun line(m: Mem, now: Long): String {
        val own = Memory.ownWords(m.text).trimEnd('。')
        val d = if (m.detail.isNotBlank()) "（" + Memory.ownWords(m.detail).trimEnd('。') + "）" else ""
        val old = if (m.at > 0 && now - m.at > 30L * 86_400_000) "（一个多月前的事，可能变了）" else ""
        return "- " + (if (m.kind == "promise") "你答应过：" else "") + own + d + old
    }

    /** 她记得的事里一直带着的：答应过的，加上最近的几条。其余的按这句话想起来（[aside]）。 */
    private const val ALWAYS = 12

    private fun diary(p: Persona) = p.items.filter { it.kind == "talk" || it.kind == "promise" || it.kind == "know" }
    private fun steady(p: Persona): List<Mem> {
        val d = diary(p)
        if (d.size <= ALWAYS + 3) return d
        val promises = d.filter { it.kind == "promise" }
        return (promises + d.filter { it.kind != "promise" }.take((ALWAYS - promises.size).coerceAtLeast(4))).distinct()
    }

    /**
     * 怎么聊：App 自带的，每位助理都有，不在提示词页里。
     * 用户给的五条：主动推动对话、提出观察和想法；关注具体、不泛泛而谈；分清情绪还是实际问题；
     * 温暖专注简洁地连续聊；日常和情感交流用句子段落、少用列表。照第一原则写成「你…」。
     */
    private fun talkWay(who: String) = """
你和${who}聊天，是一来一回接着聊下去的，温暖、专注，话不多。
你会主动往前走一步：看到了什么、想到了什么，就说出来，也可以接着问一句、提一个想法，不用等${who}开口。
你说的是具体的：接住${who}话里的那件事、那个人、那个细节，不说放在谁身上都成立的话。
你先分清${who}这会儿要的是什么：心里不好受，就先陪着；手上有事要办，就帮他把事办成；两样都有，先接住心情，再说事。
平常聊天、说心事的时候，你用句子和段落说，像人说话那样，少用列表。
""".trim()

    fun system(p: Persona, model: String): String {
        val who = Memory.userCall
        val now = System.currentTimeMillis()
        val sb = StringBuilder()
        sb.append("你叫").append(p.name).append("。")
        if (Memory.userName.isNotBlank()) sb.append("正在和你说话的是").append(who).append("，").append(who).append("也叫你").append(p.name).append("。")
        sb.append("以前的聊天里，你要是叫过别的名字、或者说过自己还没有名字，那都是过去的事，现在你就叫").append(p.name).append("。")
        Entries.around(p, "before").takeIf { it.isNotBlank() }?.let { sb.append("\n\n").append(it) }
        sb.append("\n\n").append(Memory.fill(p.sys.trim().ifEmpty { Memory.DEFAULT_SYS }, p, model))
        Entries.around(p, "after").takeIf { it.isNotBlank() }?.let { sb.append("\n\n").append(it) }
        // 怎么聊（App 自带，用户 2026-10-27 给的「深度思考指南」，改写成她自己的口吻），每轮不变
        sb.append("\n\n").append(talkWay(who))
        // 说话的底子：句群层次（Voice.kt），每轮不变，跟着上半段一起缓存
        sb.append("\n\n").append(Voice.mine(who))
        fun block(head: String, ms: List<Mem>, cap: Int) {
            if (ms.isNotEmpty()) sb.append("\n\n").append(head).append("\n").append(ms.take(cap).joinToString("\n") { line(it, now) })
        }
        block("你一直是这样的：", p.items.filter { it.kind == "self" }, 20)
        block("你知道的关于${who}的事：", p.items.filter { it.kind == "about" }, 30)
        block("${who}喜欢你这样和他相处：", p.items.filter { it.kind == "pref" }, 30)
        block("你记得：", steady(p), ALWAYS + 3)
        if (p.items.isNotEmpty())
            sb.append("\n\n这些都是你自己知道的，像平常一样自然地带出来就好：合适的时候提一句，不用一条条说，也不用说是从哪知道的。答应过的事放在心上，${who}提起或聊到相关的时候再接上。")
        return sb.toString()
    }

    /**
     * 放在最后一句话前面的旁白：现在几点、隔了多久、你们认识多久（新对话的第一句），
     * 以及按这几句话想起来的旧事（她记得的事太多时，不全放进上半段）。
     */
    fun aside(p: Persona, history: List<ChatMsg>, prevAt: Long): String {
        val who = Memory.userCall
        val now = System.currentTimeMillis()
        val c = Calendar.getInstance()
        val h = c.get(Calendar.HOUR_OF_DAY)
        val part = when (h) { in 0..4 -> "凌晨"; in 5..8 -> "早上"; in 9..11 -> "上午"; in 12..13 -> "中午"; in 14..17 -> "下午"; in 18..22 -> "晚上"; else -> "深夜" }
        val bits = mutableListOf("这时是" + SimpleDateFormat("M 月 d 日 EEEE", Locale.CHINA).format(Date(now)) + part + SimpleDateFormat("H:mm", Locale.CHINA).format(Date(now)))
        // 上一句是什么时候：这段对话里的上一条，没有就看上次来找她的时间
        val last = history.dropLast(1).lastOrNull()?.at ?: prevAt
        if (last > 0) {
            val min = (now - last) / 60_000
            when {
                min >= 7 * 1440 -> bits += "${who}已经一周多没来了，可以自然地问问最近怎么样，别埋怨"
                min >= 3 * 1440 -> bits += "${who}好几天没来了"
                min >= 1440 -> bits += "${who}隔了 ${min / 1440} 天才回来"
                min >= 60 -> bits += "${who}隔了 ${min / 60} 个小时才回你"
            }
        }
        if (history.count { it.role == "user" } <= 1) {
            if (p.firstAt <= 0 || p.chats <= 1) bits += "这是${who}第一次找你"
            else bits += "你们认识第 ${((now - p.firstAt) / 86_400_000L).toInt() + 1} 天了，这是第 ${p.chats} 次聊天"
        }
        val sb = StringBuilder("（").append(bits.joinToString("，")).append("）")
        recall(p, history).takeIf { it.isNotEmpty() }?.let { ms ->
            sb.append("\n（你想起：").append(ms.joinToString("；") { Memory.ownWords(it.text).trimEnd('。') }).append("）")
        }
        return sb.toString()
    }

    /** 她记得的事太多、没全放进上半段时：按最近几句话的字面重合，挑最多 5 条想起来；拿不准（重合太少）就不挑。 */
    private fun recall(p: Persona, history: List<ChatMsg>): List<Mem> {
        val rest = diary(p) - steady(p).toSet()
        if (rest.isEmpty()) return emptyList()
        val recent = history.takeLast(4).joinToString(" ") { it.text }
        val grams = recent.windowed(2).filter { g -> g.all { it.isLetterOrDigit() } }.toSet()
        if (grams.isEmpty()) return emptyList()
        return rest.map { m -> m to (m.text + m.detail).windowed(2).count { it in grams } }
            .filter { it.second >= 2 }.sortedByDescending { it.second }.take(5).map { it.first }
    }
}
