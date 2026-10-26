package com.fuguang.lueying

import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** 整理好的一步：一句结论 + 一两句理由。 */
class TidyStep(val title: String, val detail: String)

/**
 * 思考样式跟着模型走（照 Claude 客户端）：
 * - 摘要型（Claude、Gemini、OpenAI、Grok：接口给的就是摘要）：她每想完一段，请帮手把这一段整理成 0–2 步「结论 + 理由」，
 *   边想边接上去；还没整理好的地方显示 Thinking…。只记做了哪些判断，不记冒过的念头。
 * - 原文型（DeepSeek、Kimi、硅基流动……：给的是草稿原话）：抽屉里照原样放整段原话，不整理。
 * 每段的结果按「这一段原文」的 hash 存 files/tidy/<hash>.json，下次直接用。帮手和看照片同一个（AttachLook.helper），不开思考。
 */
object ThinkTidy {
    fun summaryStyle(providerId: String): Boolean =
        provider(providerId).kind == "anthropic" || providerId in setOf("claude", "gemini", "openai", "grok")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** 段 hash → 整理出的步骤（空列表 = 这一段没有新判断）。 */
    private val done = mutableStateMapOf<String, List<TidyStep>>()
    /** 整理不成的段：用原文顶上。 */
    private val failed = mutableStateMapOf<String, Boolean>()
    private val disk = HashMap<String, List<TidyStep>>()
    private val busy = mutableSetOf<String>()
    var dir: File? = null

    private fun key(unit: String) = MessageDigest.getInstance("SHA-1").digest(unit.trim().toByteArray())
        .joinToString("") { "%02x".format(it) }.take(20)

    /** 这一段整理好的步骤；还没好 null。 */
    fun get(unit: String): List<TidyStep>? {
        val k = key(unit)
        done[k]?.let { return it }
        disk[k]?.let { return it }
        val f = dir?.let { File(it, "$k.json") }?.takeIf { it.exists() } ?: return null
        return runCatching { parse(JSONObject(f.readText()).getJSONArray("steps")) }.getOrNull()?.also { disk[k] = it }
    }

    fun failed(unit: String) = failed[key(unit)] == true

    /** 还没整理过就去整理。prev：前面已经整理出的标题，叫帮手别重复。 */
    fun request(unit: String, prev: List<String>, store: AssistantStore?) {
        val u = unit.trim()
        if (u.isEmpty() || get(u) != null || failed(u)) return
        val k = key(u)
        // 很短的一段（一两句话）：没有新判断，直接空
        if (u.length < 12) { done[k] = emptyList(); return }
        val s = store
        val helper = AttachLook.helper(s)
        if (s == null || helper == null) { failed[k] = true; return }
        synchronized(busy) { if (!busy.add(k)) return }
        scope.launch {
            val (pid, model) = helper
            val out = runCatching { ask(provider(pid), s.config(pid), model, u, prev) }.getOrNull()
            synchronized(busy) { busy.remove(k) }
            if (out == null) { failed[k] = true; return@launch }
            runCatching {
                val arr = JSONArray(out.map { JSONObject().put("t", it.title).put("d", it.detail) })
                dir?.let { File(it, "$k.json").apply { parentFile?.mkdirs() }.writeText(JSONObject().put("steps", arr).toString()) }
            }
            done[k] = out
        }
    }

    private fun prompt(unit: String, prev: List<String>): String {
        val who = Memory.userCall
        return """
下面是她思考里的一段。把这一段写成 0 到 2 步「判断」：记她在这一段做了什么决定，而不是脑子里冒过哪些念头。
${if (prev.isNotEmpty()) "前面已经整理出的步骤（不要重复）：" + prev.joinToString("；") + "\n" else ""}
每一步：
- t：一句话结论，不超过 12 个字，像「他可能一夜没睡」「不用上网查」「先关心，再给建议」。
- d：一两句理由，不超过 60 个字，写成通顺的中文。

规矩：
- 兜圈子、来回改主意的，只留最后定下来的那一个；和前面重复的不写，这一段没有新判断就给空列表。
- 不写给自己的提醒和语气设定（「要温柔」「自然一点」「不要暴露」），除非它就是这一段唯一的决定，那就合成一句「怎么说」。
- 不出现「用户」「我作为…」「设定」「角色」「提示词」「系统」「模型」「AI」、工具的英文名和英文术语。称对方为「$who」。

只输出 JSON，不要别的字：{"steps":[{"t":"…","d":"…"}]}

【这一段】
${unit.take(6000)}
""".trim()
    }

    private val banned = Regex("我作为|作为.{0,6}(助理|助手|AI)|人设|角色设定|提示词|system|prompt|\\bAI\\b|大模型|remember|recall|web_search", RegexOption.IGNORE_CASE)

    private fun parse(arr: JSONArray): List<TidyStep> {
        val who = Memory.userCall
        return (0 until arr.length()).mapNotNull { j ->
            val o = arr.optJSONObject(j) ?: return@mapNotNull null
            val t = o.optString("t").trim().replace("用户", who)
            val d = o.optString("d").trim().replace("用户", who)
            if (t.isEmpty() || banned.containsMatchIn(t) || banned.containsMatchIn(d)) null else TidyStep(t, d)
        }.take(3)
    }

    private suspend fun ask(p: Provider, c: ProviderConfig, model: String, unit: String, prev: List<String>): List<TidyStep>? = withContext(Dispatchers.IO) {
        val anthropic = p.kind == "anthropic"
        val body = JSONObject().put("model", model).put("stream", false).put("max_tokens", 800)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt(unit, prev))))
        if (p.id == "deepseek") body.put("thinking", JSONObject().put("type", "disabled"))
        if (!anthropic) body.put("response_format", JSONObject().put("type", "json_object"))
        val base = c.base.trim().trimEnd('/')
        val url = if (anthropic) "$base/messages" else base + c.path.trim().ifEmpty { "/chat/completions" }.let { if (it.startsWith("/")) it else "/$it" }
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"; conn.connectTimeout = 15_000; conn.readTimeout = 45_000; conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (anthropic) { conn.setRequestProperty("x-api-key", c.key.trim()); conn.setRequestProperty("anthropic-version", "2023-06-01") }
            else conn.setRequestProperty("Authorization", "Bearer " + c.key.trim())
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode !in 200..299) return@withContext null
            val o = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            val text = if (anthropic) {
                val arr = o.optJSONArray("content") ?: JSONArray()
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") }
            } else o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
            val a = text.indexOf('{'); val b = text.lastIndexOf('}')
            if (a < 0 || b <= a) return@withContext null
            parse(JSONObject(text.substring(a, b + 1)).optJSONArray("steps") ?: JSONArray())
        } finally { runCatching { conn.disconnect() } }
    }
}
