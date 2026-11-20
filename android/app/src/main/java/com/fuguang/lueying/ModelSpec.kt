package com.fuguang.lueying

/**
 * 每个模型的默认参数（照 Kelivo 2026-11-19 192a6a8 的 model_spec / model_defaults_guesser / vendor_defaults / model_spec_resolver 移植）。
 * 分三层，前面的说了算：供应商地址（VendorDefaults：这家接口认哪种思考写法）→ 型号名（SpecGuess：档位、能不能关、采样、输出上限、思考回传）→ 兜底。
 * Kelivo 还有一层 models.dev 目录（上下文窗口、价格、目录里的档位），这里先没接。
 * 和 Kelivo 不同的一处：qwen3.8 要回传历史思考（百炼默认 preserve_thinking，少带会 400，第 3xx 版真机踩过）。
 */

enum class ReasoningLevel { AUTO, OFF, ON, MINIMAL, LOW, MEDIUM, HIGH, XHIGH, MAX;
    val wire: String get() = name.lowercase()
    companion object { fun of(raw: String?): ReasoningLevel? = entries.firstOrNull { it.wire == raw?.trim()?.lowercase() } }
}

enum class ReasoningDialect {
    NONE, OPENAI_EFFORT, OPENAI_RESPONSES, OPENROUTER, ANTHROPIC_BUDGET, ANTHROPIC_ADAPTIVE, ANTHROPIC_BETWEEN_TOOLS,
    ANTHROPIC_EFFORT, GEMINI_BUDGET, GEMINI_LEVEL, QWEN, THINKING_TYPE, SILICONFLOW, INTERN, CHAT_TEMPLATE_KWARGS, KIMI,
}

enum class ReplayPolicy { NONE, TOOL_TURNS, ALL }
enum class ReplayField(val wire: String) { REASONING_CONTENT("reasoning_content"), REASONING("reasoning"), REASONING_DETAILS("reasoning_details") }
enum class SamplingPolicy { ALWAYS, ONLY_WHEN_REASONING_OFF, NEVER }

class ReasoningSpec(
    val levels: List<ReasoningLevel> = emptyList(),
    val canDisable: Boolean = true,
    val defaultLevel: ReasoningLevel = ReasoningLevel.AUTO,
    val dialect: ReasoningDialect = ReasoningDialect.NONE,
    val budgets: Map<ReasoningLevel, Int> = emptyMap(),
    val replay: ReplayPolicy = ReplayPolicy.NONE,
    val replayField: ReplayField = ReplayField.REASONING_CONTENT,
)

class ModelSpec(
    val id: String,
    val reasoningAbility: Boolean,
    val reasoning: ReasoningSpec,
    val sampling: SamplingPolicy,
    val maxOutput: Int?,
    /** OpenAI 新型号、Azure、小米 MiMo 要 max_completion_tokens。 */
    val maxTokensKey: String,
    val sendStreamOptions: Boolean,
) {
    val supportsReasoning get() = reasoningAbility
}

private class SpecHit(
    val spec: ReasoningSpec,
    val sampling: SamplingPolicy? = null,
    val maxOutput: Int? = null,
    val replay: ReplayPolicy? = null,
    val replayField: ReplayField? = null,
)

private class SpecGuess(val reasoningAbility: Boolean, val reasoning: ReasoningSpec?, val sampling: SamplingPolicy?, val maxOutput: Int?, val replay: ReplayPolicy?, val replayField: ReplayField?)

private class SpecVendor(
    val dialect: ReasoningDialect? = null,
    val levels: List<ReasoningLevel>? = null,
    val canDisable: Boolean? = null,
    val protocolDefault: ReasoningDialect,
    val maxTokensKey: String = "max_tokens",
    val sendStreamOptions: Boolean = true,
    val replay: ReplayPolicy? = null,
    val replayField: ReplayField? = null,
)

object ModelSpecs {
    private val memo = HashMap<String, ModelSpec>()

    /** kind：Provider.kind（"anthropic" / "openai"）；base：这家现在填的地址（中转站按它认）。 */
    @Synchronized
    fun spec(kind: String, base: String, model: String): ModelSpec = memo.getOrPut("$kind|$base|$model") { resolve(kind, base, model) }

    private val effortLadder = listOf(ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH)
    private val budgetLadder = listOf(ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH, ReasoningLevel.XHIGH, ReasoningLevel.MAX)
    val fixedBudgets = mapOf(
        ReasoningLevel.MINIMAL to 512, ReasoningLevel.LOW to 1024, ReasoningLevel.MEDIUM to 4096,
        ReasoningLevel.HIGH to 8192, ReasoningLevel.XHIGH to 16000, ReasoningLevel.MAX to 32000,
    )
    private val budgetDialects = setOf(ReasoningDialect.ANTHROPIC_BUDGET, ReasoningDialect.GEMINI_BUDGET, ReasoningDialect.QWEN, ReasoningDialect.SILICONFLOW, ReasoningDialect.OPENROUTER)
    fun isBudgetDialect(d: ReasoningDialect) = d in budgetDialects

    private fun resolve(kind: String, base: String, modelKey: String): ModelSpec {
        val g = guess(modelKey)
        val v = vendor(kind, base, modelKey)
        val dialect = v.dialect ?: g.reasoning?.dialect ?: v.protocolDefault
        val levels = v.levels
            ?: g.reasoning?.levels?.takeIf { it.isNotEmpty() }
            ?: if (dialect in budgetDialects) budgetLadder else if (g.reasoning != null) emptyList() else effortLadder
        val canDisable = v.canDisable
            ?: if (dialect == ReasoningDialect.ANTHROPIC_BETWEEN_TOOLS) true else g.reasoning?.canDisable ?: true
        val budgets = if (dialect !in budgetDialects) emptyMap() else levels.mapNotNull { l -> fixedBudgets[l]?.let { l to it } }.toMap()
        val (replay, field) = when {
            g.replay != null -> g.replay to (g.replayField ?: ReplayField.REASONING_CONTENT)
            v.replay != null -> v.replay to (v.replayField ?: ReplayField.REASONING_CONTENT)
            else -> ReplayPolicy.NONE to ReplayField.REASONING_CONTENT
        }
        return ModelSpec(
            id = modelKey,
            reasoningAbility = g.reasoningAbility,
            reasoning = ReasoningSpec(levels, canDisable, g.reasoning?.defaultLevel ?: ReasoningLevel.AUTO, dialect, budgets, replay, field),
            sampling = g.sampling ?: SamplingPolicy.ALWAYS,
            maxOutput = g.maxOutput,
            maxTokensKey = v.maxTokensKey,
            sendStreamOptions = v.sendStreamOptions,
        )
    }

    // ───────── 供应商地址（vendor_defaults.dart） ─────────

    private fun hostOf(base: String) = runCatching { java.net.URI(base.trim()).host?.lowercase() }.getOrNull().orEmpty()

    private fun vendor(kind: String, base: String, modelId: String): SpecVendor {
        val host = hostOf(base)
        val m = modelId.lowercase()
        val protocolDefault = if (kind == "anthropic") ReasoningDialect.ANTHROPIC_BUDGET else ReasoningDialect.OPENAI_EFFORT
        val longCat = host.contains("longcat") || base.lowercase().contains("longcat")
        val stream = !longCat && !host.contains("mistral.ai") && !host.contains("openrouter")
        val isMimoHost = host.contains("xiaomimimo")
        val modernOpenAI = host == "api.openai.com" && Regex("^(?:o\\d+|gpt-[56])(?:$|[-.])").containsMatchIn(m)
        val key = if (host.contains("openai.azure.com") || isMimoHost || modernOpenAI) "max_completion_tokens" else "max_tokens"
        val all = ReplayPolicy.ALL
        val rc = ReplayField.REASONING_CONTENT

        if (kind == "anthropic") {
            // DeepSeek 的 Anthropic 兼容接口
            if (host.contains("deepseek")) return SpecVendor(ReasoningDialect.ANTHROPIC_EFFORT, listOf(ReasoningLevel.LOW, ReasoningLevel.HIGH, ReasoningLevel.MAX), false, protocolDefault, key, stream, all, rc)
            if (host == "api.kimi.com" || host.contains("moonshot")) return SpecVendor(ReasoningDialect.ANTHROPIC_BUDGET, effortLadder, true, protocolDefault, key, stream, all)
            // Anthropic 接口：思考写法跟着型号名走
            return SpecVendor(protocolDefault = protocolDefault, maxTokensKey = key, sendStreamOptions = stream, replay = all)
        }
        var replay: ReplayPolicy? = null
        var field: ReplayField? = null
        val dialect = when {
            host.contains("openrouter.ai") -> ReasoningDialect.OPENROUTER
            host.contains("dashscope") || host.contains("aliyun") -> ReasoningDialect.QWEN
            host.contains("siliconflow") -> ReasoningDialect.SILICONFLOW
            host.contains("open.bigmodel.cn") || host.contains("bigmodel") || host == "api.z.ai" -> { replay = all; field = rc; ReasoningDialect.THINKING_TYPE }
            host.contains("ark.cn-beijing.volces.com") || host.contains("volc") || host.contains("ark") -> ReasoningDialect.THINKING_TYPE
            host.contains("deepseek") -> { replay = all; field = rc; ReasoningDialect.THINKING_TYPE }
            host.contains("intern") -> ReasoningDialect.INTERN
            host == "poolside.ai" || host.endsWith(".poolside.ai") -> { replay = all; field = rc; ReasoningDialect.CHAT_TEMPLATE_KWARGS }
            isMimoHost -> { replay = all; field = rc; ReasoningDialect.THINKING_TYPE }
            host == "api.kimi.com" || host == "api.moonshot.ai" || host == "api.moonshot.cn" -> ReasoningDialect.KIMI
            else -> null
        }
        return SpecVendor(dialect, null, null, protocolDefault, key, stream, replay, field)
    }

    // ───────── 型号名（model_defaults_guesser.dart） ─────────

    private val reasoningRe = Regex(
        "(gpt-oss|gpt-5(?!-chat)|gpt-6|o\\d|gemini-(?:2\\.5|3|4).*|gemini-(?:flash-latest|pro-latest)|gemini-3-pro-image-preview|gemma[-_]?4|claude|" +
            "qwen-?3|doubao.+(?:1([-.])(?:6|8)|seed-2|seed-evolving)|grok-4|kimi-k2|kimi-k3(?:$|[/_:@.-])|muse-spark-1(?:$|[/_:@.-])|" +
            "step-3|intern-s1|glm-4([-.])(?:5|6|7)|glm-5|minimax-(?:m2|m3)|deepseek-(?:r1|v3\\.1|v3\\.2|v4|flash)|deepseek-reasoner|mimo-v2|laguna)",
    )

    private fun has(id: String, p: String) = Regex(p, RegexOption.IGNORE_CASE).containsMatchIn(id)

    private fun normalize(modelId: String): String {
        val id = modelId.lowercase()
        val at = id.lastIndexOf('@')
        if (at <= 0) return id
        val suffix = id.substring(at + 1)
        return if (suffix.isNotEmpty() && suffix.all { it.isDigit() }) id.substring(0, at) else id
    }

    private fun guess(modelId: String): SpecGuess {
        val id = normalize(modelId)
        val embedding = id.contains("embedding") || has(id, "(^|[-_/])embed(?:dings?)?([-.]|$)")
        val image = id.contains("image")
        val gemini3Image = image && id.contains("gemini-3")
        val ability = !embedding && (!image || gemini3Image) &&
            (isGemini35Flash(id) || ((reasoningRe.containsMatchIn(id) || isKimiCode(id)) && !isGemini25NonText(id)))
        var hit: SpecHit? = null
        if (!embedding) for (r in rules) if (r.match(id)) { hit = r.apply(id); break }
        var maxOutput = hit?.maxOutput
        if (maxOutput == null && (id.contains("kimi") || isKimiCodeK3Alias(id))) maxOutput = 32000
        return SpecGuess(ability, hit?.spec, hit?.sampling, maxOutput, hit?.replay, hit?.replayField)
    }

    private fun lv(vararg names: String) = names.mapNotNull { ReasoningLevel.of(it) }

    private fun ladder(
        efforts: List<String>, dialect: ReasoningDialect, samplingRequiresNone: Boolean = false, sampling: SamplingPolicy? = null,
        default: ReasoningLevel = ReasoningLevel.AUTO, maxOutput: Int? = null, replay: ReplayPolicy? = null, replayField: ReplayField? = null, canDisable: Boolean? = null,
    ): SpecHit {
        val levels = efforts.filter { it != "none" }.mapNotNull { ReasoningLevel.of(it) }.filter { it != ReasoningLevel.AUTO && it != ReasoningLevel.OFF }
        return SpecHit(
            ReasoningSpec(levels, canDisable ?: ("none" in efforts), default, dialect, emptyMap(), replay ?: ReplayPolicy.NONE, replayField ?: ReplayField.REASONING_CONTENT),
            sampling ?: if (samplingRequiresNone) SamplingPolicy.ONLY_WHEN_REASONING_OFF else null, maxOutput, replay, replayField,
        )
    }

    private fun fixed(
        dialect: ReasoningDialect, levels: List<ReasoningLevel> = emptyList(), canDisable: Boolean, default: ReasoningLevel = ReasoningLevel.AUTO,
        sampling: SamplingPolicy? = null, maxOutput: Int? = null, replay: ReplayPolicy? = null, replayField: ReplayField? = null,
    ) = SpecHit(ReasoningSpec(levels, canDisable, default, dialect, emptyMap(), replay ?: ReplayPolicy.NONE, replayField ?: ReplayField.REASONING_CONTENT), sampling, maxOutput, replay, replayField)

    private val A = ReplayPolicy.ALL
    private val RC = ReplayField.REASONING_CONTENT

    private class Rule(val match: (String) -> Boolean, val apply: (String) -> SpecHit?)

    private val rules: List<Rule> = listOf(
        Rule({ id: String -> isKimiCodeHighSpeed(id) }) { _ -> fixed(ReasoningDialect.KIMI, canDisable = true, replay = A, replayField = RC) },
        Rule({ id: String -> isKimiCodeFamily(id) }) { _ -> ladder(listOf("none", "low", "high", "max"), ReasoningDialect.KIMI, replay = A, replayField = RC) },
        Rule({ id: String -> has(id, "(^|[/_:@])kimi-k3(?:$|[-.:])") }) { _ -> ladder(listOf("low", "high", "max"), ReasoningDialect.OPENAI_EFFORT, sampling = SamplingPolicy.NEVER, replay = A, replayField = RC) },
        Rule({ id: String -> id.contains("kimi-k2.5") || id.contains("kimi-k2.6") }) { id -> fixed(ReasoningDialect.KIMI, canDisable = true, sampling = if (id.contains("kimi-k2.5")) SamplingPolicy.NEVER else null, replay = A, replayField = RC) },
        Rule({ id: String -> id.contains("kimi-k2-thinking") || id.contains("kimi-k2.7") }) { id -> fixed(ReasoningDialect.KIMI, canDisable = false, sampling = if (id.contains("kimi-k2.7")) SamplingPolicy.NEVER else null, replay = A, replayField = RC) },
        Rule({ id: String -> id.contains("deepseek") }) { _ -> ladder(listOf("low", "high", "max"), ReasoningDialect.THINKING_TYPE, canDisable = true, replay = A, replayField = RC) },
        Rule({ id: String -> has(id, "(^|[/_:@])mimo-v2(?:$|[-.])") }) { _ -> ladder(listOf("none", "low", "medium", "high"), ReasoningDialect.THINKING_TYPE, replay = A, replayField = RC) },
        Rule({ id: String -> has(id, "(^|[/_:@])grok-4\\.(?:6|7)(?:$|[-.])") }) { _ -> ladder(listOf("low", "medium", "high", "xhigh"), ReasoningDialect.OPENAI_EFFORT) },
        Rule({ id: String -> has(id, "(^|[/_:@])grok-4\\.5(?:$|[-.])") }) { _ -> ladder(listOf("low", "medium", "high"), ReasoningDialect.OPENAI_EFFORT) },
        Rule({ id: String -> has(id, "(^|[/_:@])muse-spark-1\\.3(?:$|[-.])") }) { id -> ladder(if (id.contains("contributor")) listOf("low", "medium", "high", "xhigh") else listOf("low", "medium", "high", "xhigh", "max"), ReasoningDialect.OPENAI_EFFORT) },
        Rule({ id: String -> has(id, "(^|[/_:@])muse-spark-1(?:$|[-.])") }) { _ -> ladder(listOf("low", "medium", "high", "xhigh"), ReasoningDialect.OPENAI_EFFORT) },
        Rule({ id: String -> has(id, "(^|[/_:@])glm-5\\.3(?:$|[-.])") }) { _ -> ladder(listOf("low", "high", "max"), ReasoningDialect.THINKING_TYPE, replay = A, replayField = RC) },
        Rule({ id: String -> has(id, "(^|[/_:@])glm-5\\.2(?:$|[-.])") }) { _ -> ladder(listOf("low", "medium", "high", "xhigh", "max"), ReasoningDialect.THINKING_TYPE, canDisable = true, replay = A, replayField = RC) },
        Rule({ id: String -> has(id, "(^|[/_:@])glm-(?:4([-.])(?:5|6|7)|5)(?:$|[-.])") }) { _ -> fixed(ReasoningDialect.THINKING_TYPE, canDisable = true, replay = A, replayField = RC) },
        Rule({ id: String -> has(id, "gpt-6(?=$|[-.])") }) { _ -> ladder(listOf("low", "medium", "high", "xhigh", "max"), ReasoningDialect.OPENAI_EFFORT, samplingRequiresNone = true) },
        Rule({ id: String -> has(id, "gpt-5(?=$|[-.])") }) { id -> gpt5(id) },
        Rule({ id: String -> has(id, "(^|[/_:@])o[134](?:$|[-.])") }) { _ -> ladder(listOf("low", "medium", "high"), ReasoningDialect.OPENAI_EFFORT) },
        Rule({ id: String -> id.contains("claude-") }) { id -> claude(id) },
        Rule({ id: String -> has(id, "(^|[/:_-])gemma[-_]?4([._-]|$)") }) { _ -> fixed(ReasoningDialect.GEMINI_LEVEL, lv("minimal", "high"), false, ReasoningLevel.HIGH) },
        Rule({ id: String -> isGemini3FlashImage(id) }) { id -> fixed(ReasoningDialect.GEMINI_LEVEL, lv("minimal", "high"), false, ReasoningLevel.MINIMAL, if (isGemini3Text(id)) SamplingPolicy.NEVER else null, replay = A) },
        Rule({ id: String -> gemini3Minor(id, gemini3Pro) != null }) { id ->
            val minor = gemini3Minor(id, gemini3Pro) ?: 0
            fixed(ReasoningDialect.GEMINI_LEVEL, if (minor >= 1) lv("low", "medium", "high") else lv("low", "high"), false, ReasoningLevel.HIGH, SamplingPolicy.NEVER, replay = A)
        },
        Rule({ id: String -> gemini3Minor(id, gemini3Flash) != null }) { id ->
            val minor = gemini3Minor(id, gemini3Flash) ?: 0
            val noMinimal = minor >= 7
            val lite = has(id, "gemini-3(?:\\.\\d+)?-flash-lite([._:@/-]|$)")
            val def = if (lite) (if (noMinimal) ReasoningLevel.LOW else ReasoningLevel.MINIMAL) else (if (minor >= 5) ReasoningLevel.MEDIUM else ReasoningLevel.HIGH)
            fixed(ReasoningDialect.GEMINI_LEVEL, if (noMinimal) lv("low", "medium", "high") else lv("minimal", "low", "medium", "high"), false, def, SamplingPolicy.NEVER, if (minor >= 5) 65536 else null, A)
        },
        Rule({ id: String -> id.contains("gemini-3") && isGeminiNonText(id) }) { _ -> fixed(ReasoningDialect.NONE, canDisable = true, replay = A) },
        Rule({ id: String -> has(id, "(^|[/:_-])gemini-4(?:\\.\\d+)?-") && !isGeminiNonText(id) }) { id ->
            fixed(ReasoningDialect.GEMINI_LEVEL, lv("low", "medium", "high"), false, if (id.contains("cyber-permissive")) ReasoningLevel.HIGH else ReasoningLevel.MEDIUM, SamplingPolicy.NEVER, 65536, A)
        },
        Rule({ id: String -> id.contains("gemini") && !isGemini3FlashImage(id) && gemini3Minor(id, gemini3Pro) == null && gemini3Minor(id, gemini3Flash) == null && !isGeminiNonText(id) }) { id ->
            fixed(ReasoningDialect.GEMINI_BUDGET, canDisable = !has(id, "(^|[/_:@])gemini-2\\.5-pro(?:$|[-.])"), sampling = if (isGemini3Text(id)) SamplingPolicy.NEVER else null, replay = if (id.contains("gemini-3")) A else null)
        },
        Rule({ id: String -> Regex("qwen-?3").containsMatchIn(id) || has(id, "(^|[/_:@])qwq(?:$|[-.])") }) { id -> fixed(ReasoningDialect.QWEN, canDisable = !isThinkingOnly(id), replay = if (id.startsWith("qwen3.8")) A else null) },
        Rule({ id: String -> id.startsWith("laguna-") || id.contains("/laguna-") }) { _ -> fixed(ReasoningDialect.CHAT_TEMPLATE_KWARGS, canDisable = true, replay = A, replayField = RC) },
        Rule({ id: String -> Regex("doubao.+(?:1([-.])(?:6|8)|seed-2|seed-evolving)").containsMatchIn(id) || has(id, "(^|[/_:@])seed-(?:1|2|evolving)(?:$|[-.])") }) { _ -> fixed(ReasoningDialect.THINKING_TYPE, canDisable = true) },
    )

    private fun isKimiCodeK3Alias(id: String) = id.trim() == "k3" || id.trim() == "k3-256k"
    private fun isKimiCodeHighSpeed(id: String) = has(id, "(^|[/_:@])kimi-for-coding-highspeed(?:$|[:])")
    private fun isKimiCode(id: String) = isKimiCodeK3Alias(id) || has(id, "(^|[/_:@])kimi-for-coding(?:-highspeed)?(?:$|[:])") || has(id, "(^|[/_:@])kimi-k2\\.8(?:$|[-.:])")
    private fun isKimiCodeFamily(id: String) = !isKimiCodeHighSpeed(id) && isKimiCode(id)
    private fun isGemini35Flash(id: String) = has(id, "(^|[/:_-])gemini-3\\.5-flash([._:@/-]|$)")

    private val gemini3NonTextSuffix = Regex("(^|[-_/])(image|tts|live)([-._:@/]|$)", RegexOption.IGNORE_CASE)
    private val gemini3FlashImage = Regex("gemini-3(?:\\.\\d+)?-flash(-lite)?-image([._:@/-]|$)", RegexOption.IGNORE_CASE)
    private val gemini3Flash = Regex("gemini-3(?:\\.(\\d+))?-flash([._:@/-]|$)", RegexOption.IGNORE_CASE)
    private val gemini3Pro = Regex("gemini-3(?:\\.(\\d+))?-pro(-preview)?([._:@/-]|$)", RegexOption.IGNORE_CASE)

    private fun gemini3Minor(id: String, family: Regex): Int? {
        if (gemini3NonTextSuffix.containsMatchIn(id)) return null
        val m = family.find(id) ?: return null
        return m.groupValues[1].toIntOrNull() ?: 0
    }
    private fun isGemini3FlashImage(id: String) = gemini3FlashImage.containsMatchIn(id)
    private fun isGemini3Text(id: String) = Regex("gemini-3(?:\\.\\d+)?-(?!pro-image)", RegexOption.IGNORE_CASE).containsMatchIn(id)
    private fun isGeminiNonText(id: String) = id.contains("gemini") && !isGemini3FlashImage(id) && gemini3NonTextSuffix.containsMatchIn(id)
    private fun isGemini25NonText(id: String) = isGeminiNonText(id) && !id.contains("gemini-3")

    private fun isThinkingOnly(id: String): Boolean {
        if (id.contains("qwen3.7-max-preview") || id.contains("qwen3.7-max-2026-05-17")) return true
        if (has(id, "(^|[/_:@])qwq(?:$|[-.])")) return true
        if (has(id, "(^|[/_:@])deepseek-r1(?:$|[-.])")) return true
        if (id.contains("kimi-k2.7-code") || id.contains("kimi-k2-thinking")) return true
        if (has(id, "(^|[/_:@])minimax-m2\\.(?:1|5)(?:$|[-.])")) return true
        return id.contains("-thinking")
    }

    // Claude：按版本号认 adaptive / 预算写法、档位、输出上限
    private val claudeVersionRe = Regex("claude-(?:(opus|sonnet|haiku)-(\\d+)(?:[.-](\\d{1,2})(?!\\d))?|(\\d+)(?:[.-](\\d{1,2})(?!\\d))?-(opus|sonnet|haiku))(?:$|[._:@/-])")
    private class ClaudeVer(val family: String, val major: Int, val minor: Int)
    private fun claudeVersion(id: String): ClaudeVer? {
        val m = claudeVersionRe.find(id) ?: return null
        val g = m.groupValues
        val major = (g[2].ifEmpty { g[4] }).toIntOrNull() ?: return null
        return ClaudeVer(g[1].ifEmpty { g[6] }, major, (g[3].ifEmpty { g[5] }).ifEmpty { "0" }.toInt())
    }
    private fun isClaude5(id: String) = claudeVersion(id)?.major == 5
    private fun isClaude55(id: String, family: String) = claudeVersion(id)?.let { it.family == family && it.major == 5 && it.minor >= 5 } == true
    private fun alwaysOn(id: String) = id.contains("claude-fable") || id.contains("claude-mythos") || isClaude55(id, "opus")
    private fun adaptive(id: String) = alwaysOn(id) || claudeVersion(id)?.let { it.major > 4 || (it.major == 4 && it.minor >= 6) } == true
    private fun adaptiveOnly(id: String) = alwaysOn(id) || claudeVersion(id)?.let { it.major > 4 || (it.major == 4 && it.minor >= 7) } == true
    private fun claudeXhigh(id: String) = isClaude5(id) || listOf("claude-opus-4-7", "claude-opus-4.7", "claude-opus-4-8", "claude-opus-4.8", "claude-fable", "claude-mythos").any { id.contains(it) }
    private fun claudeMax(id: String) = claudeXhigh(id) || listOf("claude-opus-4-6", "claude-opus-4.6", "claude-sonnet-4-6", "claude-sonnet-4.6", "mythos").any { id.contains(it) }
    private fun claudeMaxOutput(id: String): Int {
        if (isClaude5(id) || has(id, "claude-(?:fable|mythos)-5(?:$|[._:@/-])")) return 128000
        val v = claudeVersion(id)
        if (v != null && v.major == 4) {
            if (v.family == "opus" && v.minor <= 1) return 32000
            if ((v.family == "opus" || v.family == "sonnet") && v.minor >= 6) return 128000
        }
        if (has(id, "claude-3-haiku(?:$|[._:/])")) return 8000
        if (id.contains("claude-3-5-sonnet") || id.contains("claude-3.5-sonnet")) return 8192
        return 64000
    }

    private fun claude(id: String): SpecHit {
        val maxOutput = claudeMaxOutput(id)
        val sampling = if (adaptiveOnly(id)) SamplingPolicy.NEVER else null
        if (alwaysOn(id) || adaptive(id)) {
            val levels = buildList { addAll(effortLadder); if (claudeXhigh(id)) add(ReasoningLevel.XHIGH); if (claudeMax(id)) add(ReasoningLevel.MAX) }
            return fixed(
                if (isClaude55(id, "sonnet")) ReasoningDialect.ANTHROPIC_BETWEEN_TOOLS else ReasoningDialect.ANTHROPIC_ADAPTIVE,
                levels, !alwaysOn(id), sampling = sampling, maxOutput = maxOutput,
            )
        }
        return fixed(ReasoningDialect.ANTHROPIC_BUDGET, effortLadder, true, sampling = sampling, maxOutput = maxOutput)
    }

    private fun gpt5(id: String): SpecHit? {
        val e = ReasoningDialect.OPENAI_EFFORT
        fun m(p: String) = has(id, "(^|[/_:@])$p")
        return when {
            m("gpt-5\\.6(?:-(?:sol|terra|luna))?(?:$|[.@])") -> ladder(listOf("none", "low", "medium", "high", "xhigh", "max"), e, samplingRequiresNone = true)
            m("gpt-5\\.5-pro(?:$|[-.])") -> ladder(listOf("medium", "high", "xhigh"), e)
            m("gpt-5\\.5-(?:codex|chat-latest)(?:$|[-.])") -> null
            m("gpt-5\\.5(?:$|[-.])") -> ladder(listOf("none", "low", "medium", "high", "xhigh"), e, samplingRequiresNone = true)
            m("gpt-5\\.4-pro(?:$|[-.])") -> ladder(listOf("medium", "high", "xhigh"), e)
            m("gpt-5\\.4-(?:codex|chat-latest)(?:$|[-.])") -> null
            m("gpt-5\\.4(?:$|[-.])") -> ladder(listOf("none", "low", "medium", "high", "xhigh"), e, samplingRequiresNone = true)
            m("gpt-5\\.3-codex(?:$|[-.])") -> ladder(listOf("low", "medium", "high", "xhigh"), e)
            m("gpt-5\\.3-chat-latest(?:$|[-.])") -> ladder(listOf("none", "low", "medium", "high", "xhigh"), e)
            m("gpt-5\\.3(?:$|[-.])") -> null
            m("gpt-5\\.2-pro(?:$|[-.])") -> ladder(listOf("medium", "high", "xhigh"), e)
            m("gpt-5\\.2-codex(?:$|[-.])") -> ladder(listOf("low", "medium", "high", "xhigh"), e)
            m("gpt-5\\.2-chat-latest(?:$|[-.])") -> ladder(listOf("none", "low", "medium", "high", "xhigh"), e)
            m("gpt-5\\.2(?:$|[-.])") -> ladder(listOf("none", "low", "medium", "high", "xhigh"), e, samplingRequiresNone = true)
            m("gpt-5\\.1-chat-latest(?:$|[-.])") -> ladder(listOf("none", "low", "medium", "high"), e)
            m("gpt-5\\.1-codex-max(?:$|[-.])") -> ladder(listOf("low", "medium", "high", "xhigh"), e)
            m("gpt-5\\.1-codex(?:$|[-.])") -> ladder(listOf("low", "medium", "high"), e)
            m("gpt-5\\.1-pro(?:$|[-.])") -> null
            m("gpt-5\\.1(?:$|[-.])") -> ladder(listOf("none", "low", "medium", "high"), e)
            m("gpt-5-pro(?:$|[-.])") -> ladder(listOf("high"), e)
            m("gpt-5-codex(?:$|[-.])") -> ladder(listOf("low", "medium", "high"), e)
            m("gpt-5-chat-latest(?:$|[-.])") -> null
            m("gpt-5(?:$|-)") -> ladder(listOf("minimal", "low", "medium", "high"), e)
            else -> null
        }
    }
}
