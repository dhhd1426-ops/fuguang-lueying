package com.fuguang.lueying

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * 每位助理照 Kelivo（2026-11-19 192a6a8 的 core/models/assistant.dart）多出来的设置。
 * Persona 里已有的（名字、提示词、温度、Top P、上下文条数、记忆开关）不搬，这里只放 Kelivo 有、原来没有的。
 * 存在设置 "assistant-ext"：{ 助理 id: {...} }。
 */
class PhraseItem(val title: String, val content: String)
class KvItem(val key: String, val value: String)
class PresetMsg(val role: String, val text: String)

/** 正则规则（Kelivo assistant_regex.dart）：visualOnly 只改显示；replaceOnly 只改发出去的；都不勾 = 改存下来的。 */
class RegexRule(
    val id: String, val name: String, val pattern: String, val replacement: String,
    val user: Boolean, val assistant: Boolean, val visualOnly: Boolean, val replaceOnly: Boolean, val enabled: Boolean,
)

class AsstExt {
    /** null = 不限（Kelivo：null 不带 max_tokens）。 */
    var maxTokens by mutableStateOf<Int?>(null)
    var useAvatar by mutableStateOf(true)
    var useName by mutableStateOf(true)
    var stream by mutableStateOf(true)
    /** 这位助理默认的聊天模型 "供应商id|型号"；null = 跟着全局。 */
    var chatModel by mutableStateOf<String?>(null)
    /** 聊天背景（图片 uri）；null = 跟着主题。 */
    var background by mutableStateOf<String?>(null)
    /** 这位助理的思考默认档（Effort 的值）；null = 跟随模型默认。按型号选过的优先（Kelivo selectReasoningRequest）。 */
    var think by mutableStateOf<String?>(null)

    var template by mutableStateOf("{{ message }}")
    val presets = mutableStateListOf<PresetMsg>()
    var perConvSys by mutableStateOf(false)
    var perConvInject by mutableStateOf(false)
    var appendTime by mutableStateOf(false)
    var iso8601 by mutableStateOf(false)

    var autoOrganize by mutableStateOf(true)
    var organizeEvery by mutableStateOf(5)

    /** 本地工具里关掉的那几样（LocalTools 的 key）。Kelivo 默认不带；这里沿用你原来全开、新加的 6 样默认关。 */
    val toolsOff = mutableStateListOf<String>()
    var allSkills by mutableStateOf(true)
    val skills = mutableStateListOf<String>()
    var mcpPerConv by mutableStateOf(false)
    /** null = 全部已启用的 MCP；否则只用这几个。 */
    var mcpIds by mutableStateOf<List<String>?>(null)

    val phrases = mutableStateListOf<PhraseItem>()
    val headers = mutableStateListOf<KvItem>()
    val bodies = mutableStateListOf<KvItem>()
    val regex = mutableStateListOf<RegexRule>()
    /** 这位助理用的世界书（WorldBooks 的 id）。 */
    val worldBooks = mutableStateListOf<String>()
}

object AssistantExt {
    private const val KEY = "assistant-ext"
    /** 换助理时切到她的默认聊天模型（AssistantStore 接上）。 */
    @Volatile var useModel: ((String) -> Unit)? = null
    private var prefs: Prefs? = null
    private val map = HashMap<String, AsstExt>()

    fun init(p: Prefs) {
        prefs = p
        runCatching {
            val o = JSONObject(p.string(KEY) ?: return@runCatching)
            o.keys().forEach { id -> map[id] = read(o.getJSONObject(id)) }
        }
    }

    fun of(p: Persona): AsstExt = of(p.id)
    fun of(id: String): AsstExt = map.getOrPut(id) { AsstExt().apply { toolsOff.addAll(LocalTools.NEW_DEFAULT_OFF) } }

    fun save() {
        val o = JSONObject()
        map.forEach { (id, x) -> o.put(id, write(x)) }
        prefs?.putString(KEY, o.toString())
    }

    private fun write(x: AsstExt) = JSONObject().apply {
        x.maxTokens?.let { put("maxTokens", it) }
        put("useAvatar", x.useAvatar).put("useName", x.useName).put("stream", x.stream)
        x.chatModel?.let { put("chatModel", it) }
        x.background?.let { put("background", it) }
        x.think?.let { put("think", it) }
        put("template", x.template)
        put("presets", JSONArray(x.presets.map { JSONObject().put("role", it.role).put("text", it.text) }))
        put("perConvSys", x.perConvSys).put("perConvInject", x.perConvInject).put("appendTime", x.appendTime).put("iso8601", x.iso8601)
        put("autoOrganize", x.autoOrganize).put("organizeEvery", x.organizeEvery)
        put("toolsOff", JSONArray(x.toolsOff.toList())).put("allSkills", x.allSkills).put("skills", JSONArray(x.skills.toList()))
        put("mcpPerConv", x.mcpPerConv)
        x.mcpIds?.let { put("mcpIds", JSONArray(it)) }
        put("phrases", JSONArray(x.phrases.map { JSONObject().put("title", it.title).put("content", it.content) }))
        put("headers", JSONArray(x.headers.map { JSONObject().put("key", it.key).put("value", it.value) }))
        put("bodies", JSONArray(x.bodies.map { JSONObject().put("key", it.key).put("value", it.value) }))
        put("regex", JSONArray(x.regex.map {
            JSONObject().put("id", it.id).put("name", it.name).put("pattern", it.pattern).put("replacement", it.replacement)
                .put("user", it.user).put("assistant", it.assistant).put("visualOnly", it.visualOnly).put("replaceOnly", it.replaceOnly).put("enabled", it.enabled)
        }))
        put("worldBooks", JSONArray(x.worldBooks.toList()))
    }

    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }

    private fun read(o: JSONObject) = AsstExt().apply {
        maxTokens = if (o.has("maxTokens")) o.optInt("maxTokens").takeIf { it > 0 } else null
        useAvatar = o.optBoolean("useAvatar", true); useName = o.optBoolean("useName", true); stream = o.optBoolean("stream", true)
        chatModel = o.optString("chatModel").ifBlank { null }
        background = o.optString("background").ifBlank { null }
        think = o.optString("think").ifBlank { null }
        template = o.optString("template", "{{ message }}")
        o.optJSONArray("presets")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { presets += PresetMsg(it.optString("role", "user"), it.optString("text")) } }
        perConvSys = o.optBoolean("perConvSys"); perConvInject = o.optBoolean("perConvInject")
        appendTime = o.optBoolean("appendTime"); iso8601 = o.optBoolean("iso8601")
        autoOrganize = o.optBoolean("autoOrganize", true); organizeEvery = o.optInt("organizeEvery", 5).coerceIn(1, 20)
        toolsOff.addAll(strings(o.optJSONArray("toolsOff"))); allSkills = o.optBoolean("allSkills", true); skills.addAll(strings(o.optJSONArray("skills")))
        mcpPerConv = o.optBoolean("mcpPerConv")
        mcpIds = o.optJSONArray("mcpIds")?.let { strings(it) }
        o.optJSONArray("phrases")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { phrases += PhraseItem(it.optString("title"), it.optString("content")) } }
        o.optJSONArray("headers")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { headers += KvItem(it.optString("key"), it.optString("value")) } }
        o.optJSONArray("bodies")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { bodies += KvItem(it.optString("key"), it.optString("value")) } }
        o.optJSONArray("regex")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let {
                regex += RegexRule(it.optString("id"), it.optString("name"), it.optString("pattern"), it.optString("replacement"),
                    it.optBoolean("user", true), it.optBoolean("assistant", true), it.optBoolean("visualOnly"), it.optBoolean("replaceOnly"), it.optBoolean("enabled", true))
            }
        }
        worldBooks.addAll(strings(o.optJSONArray("worldBooks")))
    }

    // ───────── Kelivo PromptTransformer / MemoryPrompts.formatCurrentTimeTag ─────────

    private val weekday = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private fun p2(n: Int) = n.toString().padStart(2, '0')

    /** 消息末尾的时间标签：<current_time>EEE yyyy-MM-dd HH:mm:ss</current_time>，或 ISO 8601 带时区。 */
    fun timeTag(at: Long, iso: Boolean): String {
        val c = Calendar.getInstance().apply { timeInMillis = at }
        val y = c.get(Calendar.YEAR).toString().padStart(4, '0'); val mo = p2(c.get(Calendar.MONTH) + 1); val d = p2(c.get(Calendar.DAY_OF_MONTH))
        val h = p2(c.get(Calendar.HOUR_OF_DAY)); val mi = p2(c.get(Calendar.MINUTE)); val s = p2(c.get(Calendar.SECOND))
        if (iso) {
            val off = TimeZone.getDefault().getOffset(at) / 60000
            val sign = if (off < 0) "-" else "+"
            return "<current_time>$y-$mo-${d}T$h:$mi:$s$sign${p2(kotlin.math.abs(off) / 60)}:${p2(kotlin.math.abs(off) % 60)}</current_time>"
        }
        val dow = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7 // 周一 = 0
        return "<current_time>${weekday[dow]} $y-$mo-$d $h:$mi:$s</current_time>"
    }

    /** 聊天内容模板：{{ role }}、{{ message }}、{{ time }}、{{ date }}。 */
    fun applyTemplate(template: String, role: String, message: String, at: Long): String {
        val t = template.ifBlank { "{{ message }}" }
        val c = Calendar.getInstance().apply { timeInMillis = at }
        val date = "${c.get(Calendar.YEAR).toString().padStart(4, '0')}-${p2(c.get(Calendar.MONTH) + 1)}-${p2(c.get(Calendar.DAY_OF_MONTH))}"
        val time = "${p2(c.get(Calendar.HOUR_OF_DAY))}:${p2(c.get(Calendar.MINUTE))}"
        val vars = mapOf("role" to role, "message" to message, "time" to time, "date" to date)
        return Regex("\\{\\{\\s*(\\w+)\\s*\\}\\}").replace(t) { m -> vars[m.groupValues[1]] ?: m.value }
    }

    /** 系统提示词里的变量（Kelivo buildPlaceholders）。 */
    fun placeholders(p: Persona, modelId: String, battery: Int?): Map<String, String> {
        val c = Calendar.getInstance()
        val date = "${c.get(Calendar.YEAR).toString().padStart(4, '0')}-${p2(c.get(Calendar.MONTH) + 1)}-${p2(c.get(Calendar.DAY_OF_MONTH))}"
        val time = "${p2(c.get(Calendar.HOUR_OF_DAY))}:${p2(c.get(Calendar.MINUTE))}"
        return mapOf(
            "{cur_date}" to date, "{cur_time}" to time, "{cur_datetime}" to "$date $time",
            "{model_id}" to modelId, "{model_name}" to modelId,
            "{locale}" to java.util.Locale.getDefault().toLanguageTag(),
            "{timezone}" to TimeZone.getDefault().getDisplayName(TimeZone.getDefault().inDaylightTime(java.util.Date()), TimeZone.SHORT),
            "{system_version}" to "android ${android.os.Build.VERSION.RELEASE}",
            "{device_info}" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            "{battery_level}" to (battery?.let { "$it%" } ?: "unknown"),
            "{nickname}" to Memory.userName.trim(),
            "{assistant_name}" to p.name,
        )
    }

    fun fillVars(text: String, vars: Map<String, String>): String { var out = text; vars.forEach { (k, v) -> out = out.replace(k, v) }; return out }

    // ───────── 正则（Kelivo utils/assistant_regex.dart） ─────────

    enum class Target { PERSIST, VISUAL, SEND }

    private val cache = HashMap<String, Regex?>()

    fun applyRegex(x: AsstExt, input: String, assistantRole: Boolean, target: Target): String {
        if (input.isEmpty() || x.regex.isEmpty()) return input
        var out = input
        for (r in x.regex) {
            if (!r.enabled) continue
            if (if (assistantRole) !r.assistant else !r.user) continue
            val t = when { r.visualOnly -> Target.VISUAL; r.replaceOnly -> Target.SEND; else -> Target.PERSIST }
            if (t != target) continue
            val pat = r.pattern.trim()
            if (pat.isEmpty()) continue
            if (cache.size > 64 && !cache.containsKey(pat)) cache.clear()
            val re = cache.getOrPut(pat) { runCatching { Regex(pat) }.getOrNull() } ?: continue
            out = re.replace(out) { m ->
                Regex("\\$(\\d{1,2})").replace(r.replacement) { g ->
                    val i = g.groupValues[1].toInt()
                    if (i <= m.groupValues.lastIndex) m.groupValues[i] else g.value
                }
            }
        }
        return out
    }

    // ───────── 自定义请求（Kelivo CustomRequestMerger） ─────────

    /** Body 值按 JSON 解析（对象、数组、数字、true/false/null），解析不了就当字符串。 */
    private fun parseValue(raw: String): Any? {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        return runCatching {
            when {
                t.startsWith("{") -> JSONObject(t)
                t.startsWith("[") -> JSONArray(t)
                t == "true" -> true; t == "false" -> false; t == "null" -> JSONObject.NULL
                t.toLongOrNull() != null -> t.toLong()
                t.toDoubleOrNull() != null -> t.toDouble()
                t.startsWith("\"") && t.endsWith("\"") && t.length >= 2 -> t.substring(1, t.length - 1)
                else -> t
            }
        }.getOrDefault(t)
    }

    /** 自定义 Body 合进请求体：同名对象往里合，thinking / reasoning / thinkingConfig 整个替换。 */
    fun mergeBody(x: AsstExt, body: JSONObject) {
        x.bodies.forEach { kv ->
            val k = kv.key.trim().ifEmpty { return@forEach }
            val v = parseValue(kv.value)
            val old = body.opt(k)
            if (v is JSONObject && old is JSONObject && k !in setOf("thinking", "reasoning", "thinkingConfig")) deepMerge(old, v) else body.put(k, v)
        }
    }

    private fun deepMerge(target: JSONObject, patch: JSONObject) {
        patch.keys().forEach { k ->
            val a = target.opt(k); val b = patch.opt(k)
            if (a is JSONObject && b is JSONObject) deepMerge(a, b) else target.put(k, b)
        }
    }

    /** 自定义 Header（x-api-key、Authorization 这类认证头也能被盖掉，和 Kelivo 一样由用户负责）。 */
    fun applyHeaders(x: AsstExt, conn: java.net.HttpURLConnection) {
        x.headers.forEach { h -> val k = h.key.trim(); if (k.isNotEmpty()) conn.setRequestProperty(k, h.value) }
    }
}
