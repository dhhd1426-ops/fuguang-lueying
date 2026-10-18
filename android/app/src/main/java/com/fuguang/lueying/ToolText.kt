package com.fuguang.lueying

import org.json.JSONObject
import org.json.JSONTokener

/**
 * DeepSeek 有时不走正规的 tool_calls，而是把工具调用当正文写出来：
 *   <｜DSML｜function_calls><｜DSML｜invoke name="learn_skill"><｜DSML｜parameter name="repo" string="true">…</｜DSML｜parameter>…
 * 或旧写法 <｜tool▁calls▁begin｜><｜tool▁call▁begin｜>function<｜tool▁sep｜>make_work ```json {…} ```<｜tool▁call▁end｜>…
 * 这些不该给人看到，也该真的去做（不然她嘴上说「做好了」，作品卡却没有）。
 *
 * 用法：流式时每来一段就 feed，拿回能显示的新文字（可能的标记开头先扣着不显示）；结束时 finish 拿剩下的文字和抠出来的调用。
 */
class ToolText {
    class Found(val name: String, val args: JSONObject)

    private val raw = StringBuilder()
    private var shown = 0

    /** 喂一段正文，返回现在可以显示的新文字。 */
    fun feed(s: String): String {
        raw.append(s)
        val v = visible(raw.toString(), final = false)
        if (v.length <= shown) return ""
        return v.substring(shown).also { shown = v.length }
    }

    /** 结束：剩下能显示的文字、全部正文（去掉标记）、抠出来的调用。 */
    fun finish(): Triple<String, String, List<Found>> {
        val all = raw.toString()
        val v = visible(all, final = true)
        val rest = if (v.length > shown) v.substring(shown) else ""
        shown = v.length
        return Triple(rest, v, parse(all))
    }

    companion object {
        private const val BAR = "[｜|]{1,2}"
        private val START = Regex("<\\s*$BAR\\s*(DSML\\s*$BAR\\s*function_calls|tool▁calls▁begin|tool_calls_begin)\\s*(?:$BAR)?\\s*>")
        private val END = Regex("<\\s*/\\s*$BAR\\s*DSML\\s*$BAR\\s*function_calls\\s*>|<\\s*$BAR\\s*(tool▁calls▁end|tool_calls_end)\\s*(?:$BAR)?\\s*>")
        private val INVOKE_END = Regex("<\\s*/\\s*$BAR\\s*DSML\\s*$BAR\\s*invoke\\s*>")
        /** 没有外层 function_calls、直接从 invoke 开始的也算。 */
        private val LOOSE = Regex("<\\s*$BAR\\s*DSML\\s*$BAR\\s*invoke")
        private val INVOKE = Regex("<\\s*$BAR\\s*DSML\\s*$BAR\\s*invoke\\s+name\\s*=\\s*\"([^\"]+)\"\\s*>(.*?)(<\\s*/\\s*$BAR\\s*DSML\\s*$BAR\\s*invoke\\s*>|$)", RegexOption.DOT_MATCHES_ALL)
        private val PARAM = Regex("<\\s*$BAR\\s*DSML\\s*$BAR\\s*parameter\\s+name\\s*=\\s*\"([^\"]+)\"(?:\\s+string\\s*=\\s*\"(true|false)\")?\\s*>(.*?)<\\s*/\\s*$BAR\\s*DSML\\s*$BAR\\s*parameter\\s*>", RegexOption.DOT_MATCHES_ALL)
        private val OLD = Regex("tool▁call▁begin(?:$BAR)?\\s*>?\\s*(?:function)?\\s*<?\\s*(?:$BAR)?\\s*tool▁sep\\s*$BAR\\s*>\\s*([\\w.\\-]+)\\s*(?:```(?:json)?)?\\s*(\\{.*?\\})\\s*(?:```)?\\s*<\\s*$BAR\\s*tool▁call▁end", RegexOption.DOT_MATCHES_ALL)
        /** 标记可能的开头：结尾停在这些前缀上时先扣着。 */
        private val HOLD = listOf("<｜DSML｜", "<|DSML|", "<｜tool▁calls▁begin｜>", "<｜｜DSML｜｜")

        /** 去掉工具标记后能给人看的文字。final=false 时，结尾可能是标记开头的那一截先不放出来。 */
        fun visible(s: String, final: Boolean): String {
            val out = StringBuilder()
            var i = 0
            while (i < s.length) {
                val m = (START.find(s, i) ?: LOOSE.find(s, i)) ?: break
                out.append(s, i, m.range.first)
                val loose = !START.matches(m.value)
                val e = END.find(s, m.range.last + 1)
                    ?: if (loose && final) INVOKE_END.findAll(s, m.range.last + 1).lastOrNull() else null
                if (e == null) { i = s.length; break }   // 还没收尾：后面都算标记
                i = e.range.last + 1
            }
            if (i < s.length) out.append(s, i, s.length)
            var v = out.toString()
            if (!final) {
                // 结尾是「<」「<｜」「<｜DS」这种半截：扣着
                val cut = v.lastIndexOf('<')
                if (cut >= 0 && v.length - cut <= 24) {
                    val tail = v.substring(cut).replace(" ", "")
                    if (HOLD.any { it.startsWith(tail) || tail.startsWith(it) }) v = v.substring(0, cut)
                }
            }
            return if (final) v.trimEnd() else v
        }

        fun has(s: String) = START.containsMatchIn(s) || LOOSE.containsMatchIn(s)

        /** 抠出写在正文里的调用。 */
        fun parse(s: String): List<Found> {
            if (!has(s) && !s.contains("tool▁call▁begin")) return emptyList()
            val l = mutableListOf<Found>()
            INVOKE.findAll(s).forEach { m ->
                val a = JSONObject()
                PARAM.findAll(m.groupValues[2]).forEach { p ->
                    val k = p.groupValues[1]; val v = p.groupValues[3]
                    if (p.groupValues[2] == "false") a.put(k, runCatching { JSONTokener(v.trim()).nextValue() }.getOrDefault(v))
                    else a.put(k, v)
                }
                l += Found(m.groupValues[1].trim(), a)
            }
            OLD.findAll(s).forEach { m -> runCatching { l += Found(m.groupValues[1], JSONObject(m.groupValues[2])) } }
            return l
        }

        /** 存下来、显示出来的旧回复里要是混着标记，去掉。 */
        fun clean(s: String): String = if (has(s)) visible(s, final = true) else s
    }
}
