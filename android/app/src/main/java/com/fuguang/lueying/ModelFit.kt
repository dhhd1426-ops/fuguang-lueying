package com.fuguang.lueying

import org.json.JSONArray
import org.json.JSONObject

/**
 * 每个模型自己适配：不用事先知道哪家认哪个参数。
 * 发出去被 400 / 422 拒了，就看报错里点名的是哪个参数（思考强度、温度、Top P、工具……），
 * 把它记在「这家 · 这个模型」名下，去掉再发；以后同一个模型一开始就不带它。换了模型从头试。
 * 报错没点名时，交给 ChatApi.stream 原来的逐级退让；退让成功了也记下来。
 */
object ModelFit {
    private const val KEY = "model-fit"
    private var prefs: Prefs? = null
    private val map = HashMap<String, MutableSet<String>>()

    /** 一类参数 → 请求体里对应的键。 */
    private val keys = mapOf(
        "think" to listOf("reasoning_effort", "thinking", "enable_thinking", "thinking_budget", "reasoning"),
        "temp" to listOf("temperature"),
        "top_p" to listOf("top_p"),
        "tools" to listOf("tools", "tool_choice"),
    )
    /** 报错里出现这些词，就是在说这一类参数。 */
    private val words = mapOf(
        "think" to listOf("reasoning_effort", "reasoning effort", "thinking", "enable_thinking", "thinking_budget", "budget_tokens", "reasoning"),
        "temp" to listOf("temperature"),
        "top_p" to listOf("top_p", "top-p", "topp"),
        "tools" to listOf("tools", "tool_choice", "function calling", "functions", "tool use"),
    )

    fun init(p: Prefs) {
        prefs = p
        runCatching {
            val o = JSONObject(p.string(KEY) ?: return@runCatching)
            o.keys().forEach { k -> val a = o.getJSONArray(k); map[k] = MutableList(a.length()) { a.getString(it) }.toMutableSet() }
        }
    }

    private fun id(provider: String, model: String) = "$provider|${model.trim().lowercase()}"

    /**
     * 已知的规矩（照 RikkaHub 的模型表、LiteLLM 的 drop_params 那种按型号分的写法）：先按名字认，认不出来再靠报错学。
     * Grok 4 系列自己会想，不收 reasoning_effort。
     */
    private val rules = listOf(
        Regex("^grok-4") to "think",
    )

    fun drops(provider: String, model: String, what: String): Boolean =
        map[id(provider, model)]?.contains(what) == true || rules.any { (r, w) -> w == what && r.containsMatchIn(model.trim().lowercase()) }

    fun learn(provider: String, model: String, what: String) {
        if (map.getOrPut(id(provider, model)) { mutableSetOf() }.add(what)) save()
    }

    /** 这个模型记过要去掉的参数，从请求体里拿掉。 */
    fun strip(provider: String, model: String, body: JSONObject) {
        keys.forEach { (w, ks) -> if (drops(provider, model, w)) ks.forEach { body.remove(it) } }
    }

    /** 报错点名了哪一类、而且这次确实带了它：回这一类；看不出来回 null。 */
    fun blame(err: String, body: JSONObject): String? {
        val e = err.lowercase()
        // 先看最具体的：思考、Top P、温度，最后才是工具（工具的报错常常顺带提到别的）
        return listOf("think", "top_p", "temp", "tools").firstOrNull { w ->
            keys[w]!!.any { body.has(it) } && words[w]!!.any { it in e }
        }
    }

    /** 记了哪些（给界面以后用）：「不带思考参数」「不带温度」…… */
    fun note(provider: String, model: String): String = map[id(provider, model)].orEmpty().joinToString("、") {
        when (it) { "think" -> "不带思考参数"; "temp" -> "不带温度"; "top_p" -> "不带 Top P"; else -> "不用工具" }
    }

    private fun save() {
        val o = JSONObject()
        map.forEach { (k, v) -> if (v.isNotEmpty()) o.put(k, JSONArray(v.toList())) }
        prefs?.putString(KEY, o.toString())
    }
}
