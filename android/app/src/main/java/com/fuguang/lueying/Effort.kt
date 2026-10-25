package com.fuguang.lueying

import org.json.JSONObject

/**
 * 思考档位跟着模型走（照 Claude App：选哪档就原样发哪档，App 不替模型猜）。
 * 每家、每个型号只列它官方真有的档（2026-10 查的官方文档），默认档 = 这家不传参数时的默认。
 * 值：OFF 是关掉思考，其余是发给供应商的原值（low / medium / high / xhigh / max …）。
 */
class EffortLevel(val label: String, val value: String)

class EffortSpec(val levels: List<EffortLevel>, val def: Int, val note: String) {
    fun index(value: String?): Int = levels.indexOfFirst { it.value == value }.takeIf { it >= 0 } ?: def
}

object Effort {
    const val OFF = "off"

    private fun lv(vararg v: String) = v.map { EffortLevel(label(it), it) }

    fun label(v: String) = when (v) {
        OFF -> "关"; "on" -> "开"; "minimal" -> "极少"; "low" -> "低"; "medium" -> "中"
        "high" -> "高"; "xhigh" -> "超高"; "max" -> "最高"; else -> v
    }

    /** 自己决定想多少、不收档位参数的型号：档位条换成一句话。 */
    val NONE = EffortSpec(emptyList(), 0, "她自己决定想多少，这个型号不收档位")

    fun spec(providerId: String, model: String): EffortSpec {
        val m = model.trim().lowercase()
        val kind = provider(providerId).kind
        return when {
            kind == "anthropic" || providerId == "claude" -> when {
                "opus" in m && Regex("opus-5").containsMatchIn(m) -> EffortSpec(lv("low", "medium", "high", "xhigh", "max"), 1, "Opus 一直在想、关不掉，档位定想得多深")
                "sonnet-5" in m -> EffortSpec(lv("low", "medium", "high", "xhigh", "max"), 2, "Sonnet 只能少想、不能全关")
                "haiku" in m -> EffortSpec(lv(OFF, "low", "medium", "high", "xhigh", "max"), 2, "Haiku 高及以下能关掉思考")
                else -> EffortSpec(lv("low", "medium", "high", "xhigh", "max"), 2, "Claude 按档位决定想多深")
            }
            providerId == "deepseek" -> EffortSpec(lv(OFF, "low", "high", "max"), 2, "DeepSeek 只有三档：low、high、max，默认 high")
            providerId == "gemini" -> if ("2.5" in m && "flash" in m) EffortSpec(lv(OFF, "low", "medium", "high"), 3, "Gemini 2.5 Flash 能关掉思考")
                else EffortSpec(lv("low", "medium", "high"), 2, "Gemini 3 以后关不掉思考，只有三档")
            providerId == "openai" -> when {
                m.startsWith("gpt-6") -> EffortSpec(lv("low", "medium", "high", "xhigh"), 1, "GPT-6 不收 none、minimal，默认 medium")
                m.startsWith("gpt-5") -> EffortSpec(lv("minimal", "low", "medium", "high"), 2, "GPT-5 默认 medium")
                Regex("^o\\d").containsMatchIn(m) -> EffortSpec(lv("low", "medium", "high"), 1, "o 系列默认 medium")
                else -> NONE
            }
            providerId == "grok" -> if (m.startsWith("grok-4.3")) EffortSpec(lv(OFF, "low", "medium", "high"), 1, "Grok 里只有 4.3 收档位，默认 low") else NONE
            providerId == "kimi" -> when {
                m.startsWith("kimi-k3") -> EffortSpec(lv("low", "high", "max"), 2, "Kimi K3 一直在想，默认 max")
                m.startsWith("kimi-k2") -> EffortSpec(lv(OFF, "on"), 1, "K2 只有开、关，没有深浅")
                else -> NONE
            }
            else -> EffortSpec(lv(OFF, "low", "medium", "high"), 2, "按通用写法发 reasoning_effort")
        }
    }

    /** 这一档实际发了什么，档位条下面那行小字。 */
    fun note(spec: EffortSpec, value: String): String {
        if (spec.levels.isEmpty()) return spec.note
        val sent = if (value == OFF) "不思考" else "发 $value"
        return sent + (if (spec.index(value) == spec.def) "（默认）" else "") + " · " + spec.note
    }

    /**
     * 按这一家的写法写进请求体。value 为 null：什么思考参数都不带（被拒后退回这一步）。
     * 返回有没有带思考参数（带了才有理由怀疑被拒）。
     */
    fun apply(p: Provider, model: String, value: String?, body: JSONObject): Boolean {
        if (value == null) return false
        val m = model.trim().lowercase()
        when {
            p.kind == "anthropic" -> {
                if (value == OFF) body.put("thinking", JSONObject().put("type", "disabled"))
                else {
                    body.put("thinking", JSONObject().put("type", "adaptive"))
                    body.put("output_config", JSONObject().put("effort", value))
                    // 高档以上要留足长度：思考和正文一起算在 max_tokens 里
                    if (value == "xhigh" || value == "max") body.put("max_tokens", 64000) else if (value == "high") body.put("max_tokens", 32000)
                }
            }
            p.id == "deepseek" -> if (value == OFF) body.put("thinking", JSONObject().put("type", "disabled"))
                else body.put("thinking", JSONObject().put("type", "enabled")).put("reasoning_effort", value)
            p.id == "kimi" && m.startsWith("kimi-k2") -> body.put("thinking", JSONObject().put("type", if (value == OFF) "disabled" else "enabled"))
            p.id == "siliconflow" -> if (value == OFF) body.put("enable_thinking", false)
                else body.put("enable_thinking", true).put("thinking_budget", mapOf("low" to 2048, "medium" to 8000, "high" to 16000)[value] ?: 8000)
            p.id == "grok" || p.id == "gemini" -> body.put("reasoning_effort", if (value == OFF) "none" else value)
            else -> { if (value == OFF) return false; body.put("reasoning_effort", value) }
        }
        return true
    }

    /** 这一档被拒：往下退一档（关掉的那档不算）；已经是最低一档就返回 null（整个不带思考参数）。 */
    fun lower(spec: EffortSpec, value: String): String? {
        val on = spec.levels.filter { it.value != OFF }
        val i = on.indexOfFirst { it.value == value }
        return if (i > 0) on[i - 1].value else null
    }
}
