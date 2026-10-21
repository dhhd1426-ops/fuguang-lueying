package com.fuguang.lueying

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 她用的型号看不了图时（DeepSeek 的聊天型号、Kimi）：先请一个能看图的帮她看一眼，写成一段话存起来，
 * 再把这段话当成「她看到的」交给她。照片旁边存一份 look.txt，以后每轮都用这份，不重复看。
 * 谁来看：DeepSeek 有 Key 就用它的看图型号 deepseek-v4-flash-vision-exp（2026-08 起，官方 OpenAI 格式 image_url）；
 * 否则用配好 Key 的 Claude / Gemini / GPT / Grok 里排在前面的那家。
 */
object AttachLook {
    private const val DS_VISION = "deepseek-v4-flash-vision-exp"
    /** 聊天页的 store，输入框里判断「有没有人能帮她看」用。 */
    var store: AssistantStore? = null

    /** 能帮她看图的那家和型号；一个都没配返回 null。 */
    fun helper(store: AssistantStore?): Pair<String, String>? {
        val s = store ?: return null
        if (s.config("deepseek").configured) return "deepseek" to DS_VISION
        for (id in listOf("claude", "gemini", "openai", "grok")) {
            val c = s.config(id)
            if (c.configured) (c.models.firstOrNull() ?: ProviderConfig.DEFAULT_MODELS[id]?.firstOrNull())?.let { return id to it }
        }
        return null
    }

    /** 她这会儿能不能看到照片：自己能看，或者有人帮她看。 */
    fun sees(providerId: String, store: AssistantStore?) = Attach.vision(providerId) || helper(store) != null

    private fun cache(ref: String) = File(Attach.file(ref).parentFile, "look.txt")

    /** 已经看过的那段话。 */
    fun seen(ref: String): String? = cache(ref).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }

    /** 还没看过的照片挨个看；返回出错的原因（给时间线的灰字），都好了返回 null。 */
    suspend fun ensure(refs: List<String>, store: AssistantStore): String? {
        val todo = refs.filter { Attach.isImage(it) && seen(it) == null }
        if (todo.isEmpty()) return null
        val (pid, model) = helper(store) ?: return "没有能看图的"
        var err: String? = null
        todo.forEach { r ->
            runCatching { describe(provider(pid), store.config(pid), model, Attach.file(r).readBytes()) }
                .onSuccess { if (it.isNotBlank()) cache(r).writeText(it.trim()) else err = "LOOK · $pid 没回话" }
                .onFailure { err = "LOOK · $pid · " + (it.message ?: it.javaClass.simpleName).take(80) }
        }
        return err
    }

    private const val ASK = "仔细看这张图，用中文写下你看到的一切：画面里有什么、人和物在做什么、构图和颜色、给人的感觉；" +
        "图里所有能看清的字逐字照抄（标明在什么位置）。只描述，不评论，不加开场白。"

    private suspend fun describe(p: Provider, c: ProviderConfig, model: String, jpg: ByteArray): String = withContext(Dispatchers.IO) {
        val anthropic = p.kind == "anthropic"
        val b64 = android.util.Base64.encodeToString(jpg, android.util.Base64.NO_WRAP)
        val img = if (anthropic) JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", b64))
        else JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64"))
        val content = JSONArray().put(img).put(JSONObject().put("type", "text").put("text", ASK))
        val body = JSONObject().put("model", model).put("stream", false)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        if (anthropic) body.put("max_tokens", 1500) else body.put("max_tokens", 1500)
        val base = c.base.trim().trimEnd('/')
        val url = if (anthropic) "$base/messages" else base + c.path.trim().let { if (it.startsWith("/")) it else "/$it" }
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"; conn.connectTimeout = 15_000; conn.readTimeout = 90_000; conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (anthropic) { conn.setRequestProperty("x-api-key", c.key.trim()); conn.setRequestProperty("anthropic-version", "2023-06-01") }
            else conn.setRequestProperty("Authorization", "Bearer " + c.key.trim())
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode !in 200..299) {
                val e = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
                throw IllegalStateException("${conn.responseCode} ${e.take(60)}")
            }
            val o = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            if (anthropic) {
                val arr = o.optJSONArray("content") ?: JSONArray()
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") }
            } else o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
        } finally { runCatching { conn.disconnect() } }
    }
}
