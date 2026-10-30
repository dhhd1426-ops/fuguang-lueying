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
 *   名字 → [条目：她是谁之前] → 你写的她 → [条目：她是谁之后] → 思考指南 → 说话的底子 → 记忆目录（第 345 版，只有标题，常驻带正文）
 * 会变的放在对话里（见 [aside] 和 Entries.weave）：时间旁白、按关键词出现的条目。
 */
object Prompt {
    /**
     * App 自带的，每位助理都有，不在提示词页里。用户 2026-10-27 给的原文，照录不改一个字（用户说「用我的原文来」）。
     */
    private const val GUIDE = """深度思考指南：
可以主动推动对话，提出自己的观察和想法。
自然交流，关注具体信息，避免泛泛而谈。
判断什么时候关注情绪，什么时候关注实际问题。
保持温暖、专注、简洁的连续对话。
日常和情感交流优先使用句子、段落，减少列表。"""

    fun system(p: Persona, model: String): String {
        val who = Memory.userCall
        val sb = StringBuilder()
        sb.append("你叫").append(p.name).append("。")
        if (Memory.userName.isNotBlank()) sb.append("正在和你说话的是").append(who).append("，").append(who).append("也叫你").append(p.name).append("。")
        sb.append("以前的聊天里，你要是叫过别的名字、或者说过自己还没有名字，那都是过去的事，现在你就叫").append(p.name).append("。")
        Entries.around(p, "before").takeIf { it.isNotBlank() }?.let { sb.append("\n\n").append(it) }
        sb.append("\n\n").append(Memory.fill(p.sys.trim().ifEmpty { Memory.DEFAULT_SYS }, p, model))
        Entries.around(p, "after").takeIf { it.isNotBlank() }?.let { sb.append("\n\n").append(it) }
        // 深度思考指南（App 自带，用户 2026-10-27 给的原文照录），每轮不变
        sb.append("\n\n").append(GUIDE)
        // 说话的底子：句群层次（Voice.kt），每轮不变，跟着上半段一起缓存
        sb.append("\n\n").append(Voice.mine(who))
        // 第 345 版：只带目录（每条一个标题），常驻的带正文；要细节她自己翻（Memory.READ），只在相关时翻
        MemTree.catalog(p).takeIf { it.isNotBlank() }?.let { cat ->
            sb.append("\n\n你记得的事，这里是目录（方括号里是编号）：\n").append(cat)
            sb.append("\n\n「一直记着」那组的正文都在上面，做相关的事时照着做，不用翻。聊过的事只有标题：${who}说的话和某件直接有关时，才用 ${Memory.READ} 翻那条的正文；翻到了，只用和他这句话有关的内容，不要顺带讲别的记忆，也不要主动提起以前的事。常驻的那几句每句话都照着做。")
        }
        return sb.toString()
    }

    /**
     * 放在最后一句话前面的旁白：现在几点、隔了多久、你们认识多久（新对话的第一句）。
     * 第 345 版起不再按字面替她「想起」旧事，要不要翻记忆由她自己定。
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
        return sb.toString()
    }
}
