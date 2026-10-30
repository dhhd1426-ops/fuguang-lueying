package com.fuguang.lueying

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 记忆的样子（第 345 版，设计见项目《记忆重新设计-2026-10-29》）：
 * - ★ 人和规矩（[Mem.rule]）：一直单独挂着，有变化原地更新。
 * - 事：按天记，每天第一次聊天时整理；上一周过完了归成一份「这一周」，月、年同理（[Reflect.roll]）。
 * - 她身上带目录（[catalog]）：每条「[编号] 日期 · 类别 · 标题」；人和规矩、常驻的带正文（第 357 版），事要细节自己用 read_memory 翻（[full]）。
 */
object MemTree {
    /** 记忆本、目录里的分段：名字、小字、哪些。 */
    class Section(val name: String, val sub: String, val items: List<Mem>)

    /** 挂在最上层的：没收进周月年的（收进去的那份被删了，也算回最上层）。 */
    fun top(p: Persona): List<Mem> {
        val ids = p.items.map { it.id }.toSet()
        return p.items.filter { it.under.isEmpty() || it.under !in ids }
    }

    fun kids(p: Persona, m: Mem): List<Mem> = p.items.filter { it.under == m.id }.sortedByDescending { it.at }

    fun sections(p: Persona): List<Section> {
        val t = top(p)
        return listOf(
            Section("一直记着", "人和规矩，不按时间收拢", t.filter { it.rule }.sortedWith(compareByDescending<Mem> { it.pin }.thenByDescending { it.at })),
            Section("最近", "按天", t.filter { !it.rule && !it.span }.sortedByDescending { it.at }),
            Section("本月更早", "按周收拢", t.filter { it.kind == "week" }.sortedByDescending { it.at }),
            Section("今年更早", "按月收拢", t.filter { it.kind == "month" }.sortedByDescending { it.at }),
            Section("往年", "按年收拢", t.filter { it.kind == "year" }.sortedByDescending { it.at }),
        ).filter { it.items.isNotEmpty() }
    }

    fun kindZh(k: String) = when (k) {
        "about" -> "关于你"; "pref" -> "你喜欢的"; "promise" -> "答应你的"; Lessons.KIND -> "我学会的"; "self" -> "关于我"
        "week" -> "这一周"; "month" -> "这个月"; "year" -> "这一年"; else -> "聊过的"
    }

    /** 日期：一天的写「10月28日」；周写「10月19日—25日」；月写「2026年10月」；年写「2026年」。 */
    fun date(m: Mem): String {
        if (m.at <= 0) return ""
        val d = Date(m.at)
        return when (m.kind) {
            "year" -> SimpleDateFormat("yyyy年", Locale.CHINA).format(d)
            "month" -> SimpleDateFormat("yyyy年M月", Locale.CHINA).format(d)
            "week" -> {
                val a = Calendar.getInstance().apply { timeInMillis = m.at }
                val b = Calendar.getInstance().apply { timeInMillis = (if (m.till > 0) m.till else m.at + 6 * DAY) }
                SimpleDateFormat("M月d日", Locale.CHINA).format(d) + "—" +
                    (if (a.get(Calendar.MONTH) == b.get(Calendar.MONTH)) "${b.get(Calendar.DAY_OF_MONTH)}日" else SimpleDateFormat("M月d日", Locale.CHINA).format(b.time))
            }
            else -> SimpleDateFormat("M月d日", Locale.CHINA).format(d)
        }
    }

    /** 正文：写好了用正文，还没写好用标题和原话。 */
    fun body(m: Mem): String = m.diary.ifBlank { listOf(m.text, m.detail).filter { it.isNotBlank() }.distinct().joinToString("\n") }

    private fun line(m: Mem, p: Persona) = "- [${m.code}] ${date(m)} · ${kindZh(m.kind)} · ${Memory.ownWords(m.text).trimEnd('。')}" +
        (if (m.span) "（下面 ${kids(p, m).size} 份）" else "")

    /** 她身上带的目录。空的时候返回空串。 */
    fun catalog(p: Persona): String {
        val secs = sections(p)
        if (secs.isEmpty()) return ""
        val sb = StringBuilder()
        secs.forEach { s ->
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append("【").append(s.name).append("】")
            if (s.items.firstOrNull()?.rule == true) sb.append("（正文都在，做相关的事时照着做）")
            s.items.forEach { m ->
                sb.append("\n").append(line(m, p))
                // 人和规矩：正文一直带着，不靠她判断要不要翻（常驻的在下面单独列，这里不重复）
                if (m.rule && !m.pin) sb.append("\n  ").append(Memory.ownWords(body(m)).replace("\n", " ").let { if (it.length > RULE_MAX) it.take(RULE_MAX) + "…" else it })
            }
        }
        val pins = p.items.filter { it.pin }
        if (pins.isNotEmpty()) {
            sb.append("\n\n【常驻：每句话都照着做】")
            pins.forEach { m -> sb.append("\n").append(Memory.ownWords(body(m)).replace("\n", " ")) }
        }
        return sb.toString()
    }

    /** 翻开一条：标题、日期、类别、正文；周月年那份再列出下面挂着的。 */
    fun full(p: Persona, m: Mem): String {
        val sb = StringBuilder("[${m.code}] ${date(m)} · ${kindZh(m.kind)} · ${Memory.ownWords(m.text)}\n${Memory.ownWords(body(m))}")
        val k = kids(p, m)
        if (k.isNotEmpty()) sb.append("\n下面挂着：\n").append(k.joinToString("\n") { line(it, p) })
        return sb.toString()
    }

    const val DAY = 86_400_000L
    /** 人和规矩每条正文带多少字。 */
    private const val RULE_MAX = 300

    fun dayKey(t: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(t))
    fun dayStart(t: Long): Long = Calendar.getInstance().apply { timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    /** 这一周的周一 0 点。 */
    fun weekStart(t: Long): Long {
        val c = Calendar.getInstance().apply { timeInMillis = dayStart(t) }
        val dow = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7 // 周一 = 0
        c.add(Calendar.DAY_OF_MONTH, -dow)
        return c.timeInMillis
    }
    fun monthStart(t: Long): Long = Calendar.getInstance().apply { timeInMillis = dayStart(t); set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
    fun yearStart(t: Long): Long = Calendar.getInstance().apply { timeInMillis = dayStart(t); set(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
}
