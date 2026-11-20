package com.fuguang.lueying

import org.json.JSONObject

/**
 * 深度思考档位（照 Kelivo 2026-11-19 192a6a8 的 reasoning_dialects / reasoning_level_options 移植）。
 * 档位条：关闭（型号能关才有）→ 自动 → 型号自己有的几档（ModelSpecs 按型号名、供应商地址认）。
 * 自动 = 不动思考参数，交给型号和供应商的默认；选了某档就按这家的写法写进请求体。
 * 型号没有选的那档：取最近的一档（一样近取低的）；关不掉的型号选关闭：按它最低的一档发。
 */
class EffortLevel(val label: String, val value: String)

class EffortSpec(val levels: List<EffortLevel>, val def: Int, val note: String) {
    fun index(value: String?): Int = levels.indexOfFirst { it.value == value }.takeIf { it >= 0 } ?: def
}

object Effort {
    const val OFF = "off"
    const val AUTO = "auto"

    /** 供应商 id → 这家现在填的地址（中转站按地址认写法）。AssistantStore 启动时接上。 */
    @Volatile var baseOf: (String) -> String = { provider(it).base }

    fun specOf(providerId: String, model: String): ModelSpec = ModelSpecs.spec(provider(providerId).kind, baseOf(providerId), model.trim())

    fun label(v: String) = when (v) {
        OFF -> "关闭"; AUTO -> "自动"; "on" -> "开启"; "minimal" -> "最低"; "low" -> "低"; "medium" -> "中"
        "high" -> "高"; "xhigh" -> "极高"; "max" -> "最大"; else -> v
    }

    private fun stop(v: String) = EffortLevel(label(v), v)

    fun spec(providerId: String, model: String): EffortSpec {
        val s = specOf(providerId, model)
        if (!s.supportsReasoning || s.reasoning.dialect == ReasoningDialect.NONE) return EffortSpec(emptyList(), 0, "这个型号不收思考参数")
        val stops = buildList {
            if (s.reasoning.canDisable) add(stop(OFF))
            add(stop(AUTO))
            s.reasoning.levels.forEach { add(stop(it.wire)) }
        }
        val def = stops.indexOfFirst { it.value == s.reasoning.defaultLevel.wire }.takeIf { it >= 0 } ?: stops.indexOfFirst { it.value == AUTO }
        return EffortSpec(stops, def, "")
    }

    /** 这一档实际发了什么，档位条下面那行小字。 */
    fun note(spec: EffortSpec, value: String): String {
        if (spec.levels.isEmpty()) return spec.note
        val dflt = if (spec.index(value) == spec.def) "（默认）" else ""
        return when (value) {
            OFF -> "不思考$dflt"
            AUTO -> "不带思考参数，交给型号默认$dflt"
            else -> "发 $value$dflt"
        }
    }

    // ───────── 选的档 → 实际生效的档（resolveReasoning） ─────────

    private class Resolution(val requested: ReasoningLevel, val effective: ReasoningLevel, val budget: Int?)

    private fun explicit(l: ReasoningLevel) = l != ReasoningLevel.AUTO && l != ReasoningLevel.OFF

    private fun lowest(r: ReasoningSpec): ReasoningLevel =
        r.levels.minByOrNull { it.ordinal } ?: r.budgets.minByOrNull { it.value }?.key ?: ReasoningLevel.MINIMAL

    private fun nearest(want: ReasoningLevel, levels: List<ReasoningLevel>): ReasoningLevel {
        if (levels.isEmpty() || want in levels) return want
        var best = levels.first()
        var bd = kotlin.math.abs(best.ordinal - want.ordinal)
        for (l in levels.drop(1)) {
            val d = kotlin.math.abs(l.ordinal - want.ordinal)
            if (d < bd || (d == bd && l.ordinal < best.ordinal)) { best = l; bd = d }
        }
        return best
    }

    private fun resolve(s: ModelSpec, requested: ReasoningLevel): Resolution {
        val r = s.reasoning
        val effective = when {
            requested == ReasoningLevel.AUTO -> ReasoningLevel.AUTO
            requested == ReasoningLevel.OFF -> if (r.canDisable) ReasoningLevel.OFF else lowest(r)
            requested in r.levels || (r.levels.isEmpty() && ModelSpecs.isBudgetDialect(r.dialect)) -> requested
            else -> nearest(requested, r.levels)
        }
        val budget = if (ModelSpecs.isBudgetDialect(r.dialect) && explicit(effective)) r.budgets[effective] ?: ModelSpecs.fixedBudgets[effective] else null
        return Resolution(requested, effective, budget)
    }

    private fun level(value: String?) = ReasoningLevel.of(value) ?: ReasoningLevel.AUTO

    // ───────── 写进请求体（applyReasoning） ─────────

    /**
     * 按这一家的写法写进请求体。value 为 null：什么思考参数都不带（被拒后退回这一步）。
     * 返回有没有带思考参数（带了才有理由怀疑被拒）。
     */
    fun apply(p: Provider, model: String, value: String?, body: JSONObject): Boolean {
        if (value == null) return false
        val s = specOf(p.id, model)
        if (!s.supportsReasoning || s.reasoning.dialect == ReasoningDialect.NONE) return false
        val anthropic = p.kind == "anthropic"
        val res = resolve(s, level(value))
        val before = body.toString()
        if (res.effective == ReasoningLevel.AUTO) autoFlags(body, s.reasoning.dialect)
        else { strip(body, s.reasoning.dialect); write(body, s, res, anthropic) }
        return body.toString() != before
    }

    /** 自动：什么都不拿掉，只补上看得见思考的开关。 */
    private fun autoFlags(body: JSONObject, d: ReasoningDialect) {
        when (d) {
            ReasoningDialect.OPENROUTER -> { val o = body.ensure("reasoning"); if (!o.has("enabled")) o.put("enabled", true) }
            // Claude 4.7 以后不写 display 就不回思考的字
            ReasoningDialect.ANTHROPIC_ADAPTIVE, ReasoningDialect.ANTHROPIC_BETWEEN_TOOLS ->
                if (!body.has("thinking")) body.put("thinking", JSONObject().put("type", "adaptive").put("display", "summarized"))
            else -> Unit
        }
    }

    private fun strip(body: JSONObject, d: ReasoningDialect) {
        when (d) {
            ReasoningDialect.OPENAI_EFFORT, ReasoningDialect.OPENAI_RESPONSES, ReasoningDialect.GEMINI_BUDGET, ReasoningDialect.GEMINI_LEVEL -> body.remove("reasoning_effort")
            ReasoningDialect.OPENROUTER -> { body.remove("reasoning"); body.remove("reasoning_effort") }
            ReasoningDialect.ANTHROPIC_BUDGET, ReasoningDialect.ANTHROPIC_ADAPTIVE, ReasoningDialect.ANTHROPIC_BETWEEN_TOOLS, ReasoningDialect.ANTHROPIC_EFFORT -> { body.remove("thinking"); body.remove("output_config") }
            ReasoningDialect.QWEN, ReasoningDialect.SILICONFLOW -> { body.remove("enable_thinking"); body.remove("thinking_budget") }
            ReasoningDialect.THINKING_TYPE -> { body.remove("thinking"); body.remove("reasoning_effort") }
            ReasoningDialect.KIMI -> { body.remove("thinking"); body.remove("reasoning_effort"); body.remove("reasoning"); body.remove("output_config") }
            ReasoningDialect.INTERN -> body.remove("thinking_mode")
            ReasoningDialect.CHAT_TEMPLATE_KWARGS -> body.optJSONObject("chat_template_kwargs")?.remove("enable_thinking")
            ReasoningDialect.NONE -> Unit
        }
    }

    private fun write(body: JSONObject, s: ModelSpec, res: Resolution, anthropic: Boolean) {
        val e = res.effective
        val off = e == ReasoningLevel.OFF
        val r = s.reasoning
        fun disabled(type: String = "disabled") = body.put("thinking", JSONObject().put("type", type))
        when (r.dialect) {
            ReasoningDialect.OPENAI_EFFORT, ReasoningDialect.OPENAI_RESPONSES ->
                body.put("reasoning_effort", when (e) { ReasoningLevel.OFF -> "none"; ReasoningLevel.ON -> "high"; else -> e.wire })
            ReasoningDialect.OPENROUTER -> body.put("reasoning", when {
                off -> JSONObject().put("enabled", false)
                r.levels.isNotEmpty() -> JSONObject().put("effort", e.wire)
                else -> JSONObject().put("enabled", true).apply { res.budget?.let { put("max_tokens", it) } }
            })
            ReasoningDialect.ANTHROPIC_BUDGET -> if (off) disabled() else
                body.put("thinking", JSONObject().put("type", "enabled").put("budget_tokens", maxOf(res.budget ?: 1024, 1024)))
            ReasoningDialect.ANTHROPIC_ADAPTIVE, ReasoningDialect.ANTHROPIC_BETWEEN_TOOLS ->
                if (off) disabled(if (r.dialect == ReasoningDialect.ANTHROPIC_BETWEEN_TOOLS) "between_tools" else "disabled")
                else { body.put("thinking", JSONObject().put("type", "adaptive").put("display", "summarized")); body.put("output_config", JSONObject().put("effort", e.wire)) }
            ReasoningDialect.ANTHROPIC_EFFORT -> if (off) disabled() else { body.put("thinking", JSONObject().put("type", "enabled")); body.put("output_config", JSONObject().put("effort", e.wire)) }
            // Gemini 走的是 OpenAI 兼容接口：Kelivo 写原生 generationConfig，这里换成兼容接口认的 reasoning_effort
            ReasoningDialect.GEMINI_LEVEL -> body.put("reasoning_effort", if (off) "none" else e.wire)
            ReasoningDialect.GEMINI_BUDGET -> body.put("reasoning_effort", when (e) {
                ReasoningLevel.OFF -> "none"; ReasoningLevel.MINIMAL, ReasoningLevel.LOW -> "low"; ReasoningLevel.MEDIUM -> "medium"; else -> "high"
            })
            ReasoningDialect.QWEN -> when {
                !r.canDisable -> res.budget?.let { body.put("thinking_budget", it) }
                off -> body.put("enable_thinking", false)
                else -> { body.put("enable_thinking", true); if (r.budgets.isNotEmpty()) res.budget?.let { body.put("thinking_budget", it) } }
            }
            ReasoningDialect.SILICONFLOW -> if (off) body.put("enable_thinking", false) else res.budget?.let { body.put("thinking_budget", it) }
            ReasoningDialect.THINKING_TYPE -> if (off) disabled() else {
                body.put("thinking", JSONObject().put("type", "enabled"))
                if (r.levels.isNotEmpty()) body.put("reasoning_effort", e.wire)
            }
            ReasoningDialect.KIMI -> if (off) disabled() else
                body.put("thinking", JSONObject().put("type", "enabled").apply { if (r.levels.isNotEmpty()) put("effort", e.wire) })
            ReasoningDialect.INTERN -> body.put("thinking_mode", !off)
            ReasoningDialect.CHAT_TEMPLATE_KWARGS -> body.ensure("chat_template_kwargs").put("enable_thinking", !off)
            ReasoningDialect.NONE -> Unit
        }
    }

    private fun JSONObject.ensure(key: String): JSONObject = optJSONObject(key) ?: JSONObject().also { put(key, it) }

    // ───────── 采样（applySamplingPolicy） ─────────

    /** 这个型号这一档不收温度 / Top P 的，从请求体里拿掉。 */
    fun sampling(p: Provider, model: String, value: String?, body: JSONObject) {
        val s = specOf(p.id, model)
        val strip = when (s.sampling) {
            SamplingPolicy.ALWAYS -> false
            SamplingPolicy.NEVER -> true
            SamplingPolicy.ONLY_WHEN_REASONING_OFF -> resolve(s, level(value)).effective != ReasoningLevel.OFF
        }
        if (strip) listOf("temperature", "top_p", "top_k", "logprobs", "top_logprobs").forEach { body.remove(it) }
    }

    // ───────── 其他按型号的默认 ─────────

    /** Anthropic 的 max_tokens：型号自己的输出上限，认不出给 64000。 */
    fun maxOutput(p: Provider, model: String): Int = specOf(p.id, model).maxOutput ?: 64000

    /** max_tokens 用哪个键（OpenAI 新型号、Azure、MiMo 要 max_completion_tokens）。 */
    fun maxTokensKey(p: Provider, model: String): String = specOf(p.id, model).maxTokensKey

    /** 这家收不收 stream_options（Mistral、OpenRouter、LongCat 不收）。 */
    fun streamOptions(p: Provider, model: String): Boolean = specOf(p.id, model).sendStreamOptions

    /** 历史回复的思考要不要原样带回去（DeepSeek、Kimi、GLM、MiMo 等）。 */
    fun echoes(p: Provider, model: String): Boolean {
        if (p.kind == "anthropic") return false
        val r = specOf(p.id, model).reasoning
        // Kelivo 回传 Gemini 的思考走原生接口（thought signature）；这里走 OpenAI 兼容接口，不回传
        return r.replay != ReplayPolicy.NONE && r.dialect != ReasoningDialect.GEMINI_LEVEL && r.dialect != ReasoningDialect.GEMINI_BUDGET
    }

    /** 带回去时用哪个字段名。 */
    fun echoField(p: Provider, model: String): String = specOf(p.id, model).reasoning.replayField.wire

    /** 这一档被拒：往下退一档（关闭、自动不算）；已经是最低一档就返回 null（整个不带思考参数）。 */
    fun lower(spec: EffortSpec, value: String): String? {
        val on = spec.levels.filter { it.value != OFF && it.value != AUTO }
        val i = on.indexOfFirst { it.value == value }
        return if (i > 0) on[i - 1].value else null
    }

    /** 助理设置里的思考默认档落到这个型号的档位条上：有就用；没有就取最近的一档（关不掉的型号「关闭」给最低一档）。 */
    fun fit(spec: EffortSpec, value: String): String? {
        if (spec.levels.isEmpty()) return null
        if (spec.levels.any { it.value == value }) return value
        if (value == OFF) return lightest(spec)
        val want = ReasoningLevel.of(value) ?: return null
        return spec.levels.mapNotNull { s -> ReasoningLevel.of(s.value)?.takeIf { explicit(it) }?.let { s.value to it } }
            .minWithOrNull(compareBy({ kotlin.math.abs(it.second.ordinal - want.ordinal) }, { it.second.ordinal }))?.first
    }

    /** 这个型号默认那档；默认是自动、或者没有档位时，给最低的一档（分身、记忆整理用）。 */
    fun lightest(spec: EffortSpec): String? = spec.levels.firstOrNull { it.value != OFF && it.value != AUTO }?.value
}
