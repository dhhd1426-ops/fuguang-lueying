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
 * 思考摘要（照 Claude App）：她想完后，请帮手把思考原文重写成几步「判断」，记做了哪些决定，不记冒过的念头。
 * 原文按工具切成几段，每段各自整理，工具还排在原来的位置。结果存 files/tidy/<原文的 hash>.json，下次直接用。
 * 帮手和看照片同一个（AttachLook.helper），不开思考。整理不出来就还显示原文。
 */
object ThinkTidy {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** 原文 hash → 每段的步骤；空列表 = 整理过但不成，用原文。 */
    private val done = mutableStateMapOf<String, List<List<TidyStep>>>()
    private val busy = mutableSetOf<String>()
    /** 从文件读出来的（读的时候在界面里，不能改 done）。 */
    private val disk = HashMap<String, List<List<TidyStep>>>()
    var dir: File? = null

    private fun key(raw: String, cuts: List<Int>) = MessageDigest.getInstance("SHA-1").digest((raw + "|" + cuts.joinToString(",")).toByteArray())
        .joinToString("") { "%02x".format(it) }.take(20)

    /** 按工具的位置把原文切开。 */
    fun chunks(raw: String, cuts: List<Int>): List<String> {
        val pts = (listOf(0) + cuts.map { it.coerceIn(0, raw.length) } + raw.length).sorted()
        return pts.zipWithNext { a, b -> raw.substring(a, b).trim() }
    }

    /** 整理好的；还没整理或整理不成返回 null。 */
    fun get(raw: String, cuts: List<Int>): List<List<TidyStep>>? {
        val k = key(raw, cuts)
        done[k]?.let { return it.takeIf { l -> l.isNotEmpty() } }
        disk[k]?.let { return it }
        val f = dir?.let { File(it, "$k.json") }?.takeIf { it.exists() } ?: return null
        return runCatching { parse(JSONObject(f.readText()).getJSONArray("chunks"), chunks(raw, cuts).size) }.getOrNull()
            ?.also { disk[k] = it }
    }

    fun pending(raw: String, cuts: List<Int>) = synchronized(busy) { key(raw, cuts) in busy }

    /** 还没整理过就去整理（原文太短不整理）。 */
    fun request(raw: String, cuts: List<Int>, store: AssistantStore?) {
        if (raw.trim().length < 60 || get(raw, cuts) != null) return
        val k = key(raw, cuts)
        if (done.containsKey(k)) return
        val (pid, model) = AttachLook.helper(store) ?: return
        val s = store ?: return
        synchronized(busy) { if (!busy.add(k)) return }
        scope.launch {
            val parts = chunks(raw, cuts)
            val out = runCatching { ask(provider(pid), s.config(pid), model, parts) }.getOrNull()
            synchronized(busy) { busy.remove(k) }
            if (out == null) { done[k] = emptyList(); return@launch }
            runCatching {
                val arr = JSONArray(out.map { c -> JSONArray(c.map { JSONObject().put("t", it.title).put("d", it.detail) }) })
                dir?.let { File(it, "$k.json").apply { parentFile?.mkdirs() }.writeText(JSONObject().put("chunks", arr).toString()) }
            }
            done[k] = out
        }
    }

    private fun prompt(parts: List<String>): String {
        val who = Memory.userCall.ifBlank { "他" }
        return """
下面是一段思考草稿，按顺序分成 ${parts.size} 段（段与段之间她去用了一次工具）。把每一段重写成 0 到 4 步「判断」，记她做了哪些决定，而不是脑子里冒过哪些念头。

每一步：
- t：一句话结论，不超过 12 个字，像「他可能一夜没睡」「不用上网查」「先关心，再给建议」。
- d：一两句理由，不超过 60 个字，写成通顺的中文。

规矩：
- 兜圈子、来回改主意的，只留最后定下来的那一个；前后几段重复的不再写。
- 不写给自己的提醒和语气设定（「要温柔」「自然一点」「不要暴露」），除非它就是这一段唯一的决定，那就合成一句「怎么说」。
- 不出现「用户」「我作为…」「设定」「角色」「提示词」「系统」「模型」「AI」、工具的英文名和英文术语。称对方为「$who」。
- 想得很少的段落，0 步或 1 步就够，不要硬凑。

只输出 JSON，不要别的字：{"chunks":[[{"t":"…","d":"…"}], …]}，chunks 的个数等于段数。

${parts.mapIndexed { i, p -> "【第 ${i + 1} 段】\n" + p.take(6000) }.joinToString("\n\n")}
""".trim()
    }

    private val banned = Regex("我作为|作为.{0,6}(助理|助手|AI)|人设|角色设定|提示词|system|prompt|\\bAI\\b|大模型|remember|recall|web_search", RegexOption.IGNORE_CASE)

    private fun parse(arr: JSONArray, n: Int): List<List<TidyStep>>? {
        if (arr.length() != n) return null
        val who = Memory.userCall.ifBlank { "他" }
        val out = List(n) { i ->
            val c = arr.optJSONArray(i) ?: JSONArray()
            (0 until c.length()).mapNotNull { j ->
                val o = c.optJSONObject(j) ?: return@mapNotNull null
                val t = o.optString("t").trim().replace("用户", who)
                val d = o.optString("d").trim().replace("用户", who)
                if (t.isEmpty() || banned.containsMatchIn(t) || banned.containsMatchIn(d)) null else TidyStep(t, d)
            }
        }
        return out.takeIf { l -> l.any { it.isNotEmpty() } }
    }

    private suspend fun ask(p: Provider, c: ProviderConfig, model: String, parts: List<String>): List<List<TidyStep>>? = withContext(Dispatchers.IO) {
        val anthropic = p.kind == "anthropic"
        val body = JSONObject().put("model", model).put("stream", false).put("max_tokens", 2000)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt(parts))))
        if (p.id == "deepseek") body.put("thinking", JSONObject().put("type", "disabled"))
        if (!anthropic) body.put("response_format", JSONObject().put("type", "json_object"))
        val base = c.base.trim().trimEnd('/')
        val url = if (anthropic) "$base/messages" else base + c.path.trim().ifEmpty { "/chat/completions" }.let { if (it.startsWith("/")) it else "/$it" }
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"; conn.connectTimeout = 15_000; conn.readTimeout = 60_000; conn.doOutput = true
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
            val json = text.substring(text.indexOf('{').coerceAtLeast(0), (text.lastIndexOf('}') + 1).coerceAtLeast(0))
            parse(JSONObject(json).getJSONArray("chunks"), parts.size)
        } finally { runCatching { conn.disconnect() } }
    }
}
