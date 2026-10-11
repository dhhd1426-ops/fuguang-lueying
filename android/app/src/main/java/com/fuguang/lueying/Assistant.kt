package com.fuguang.lueying

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 一个模型供应商的固定信息。kind = "openai"（OpenAI 兼容接口）或 "anthropic"。 */
data class Provider(
    val id: String,
    val name: String,
    val kind: String,
    val base: String,
    val path: String,
    val letter: String,
    val note: String = "",
    val hasBalance: Boolean = false,
)

val PROVIDERS = listOf(
    Provider("deepseek", "DeepSeek", "openai", "https://api.deepseek.com/v1", "/chat/completions", "D", hasBalance = true),
    Provider("claude", "Claude", "anthropic", "https://api.anthropic.com/v1", "/messages", "C", note = "Anthropic 接口"),
    Provider("gemini", "Gemini", "openai", "https://generativelanguage.googleapis.com/v1beta/openai", "/chat/completions", "G"),
    Provider("openai", "OpenAI", "openai", "https://api.openai.com/v1", "/chat/completions", "O"),
    Provider("kimi", "月之暗面 Kimi", "openai", "https://api.moonshot.cn/v1", "/chat/completions", "月"),
    Provider("siliconflow", "硅基流动", "openai", "https://api.siliconflow.cn/v1", "/chat/completions", "硅"),
)

fun provider(id: String): Provider = PROVIDERS.firstOrNull { it.id == id } ?: PROVIDERS.first()

/** 用户对某个供应商的设置，只存在本机。 */
data class ProviderConfig(
    val name: String,
    val key: String = "",
    val base: String,
    val path: String,
    val enabled: Boolean = false,
    val balance: Boolean = false,
    val models: List<String> = emptyList(),
) {
    val configured get() = key.isNotBlank()

    companion object {
        fun default(p: Provider) = ProviderConfig(
            name = p.name, base = p.base, path = p.path, balance = p.hasBalance,
            models = if (p.id == "deepseek") listOf("deepseek-flash", "deepseek-v4-pro") else emptyList(),
        )
    }
}

/** 助理设置：名称、各供应商配置、当前用哪个供应商的哪个模型。保存在本机 SharedPreferences。 */
class AssistantStore(private val prefs: Prefs) {
    var name by mutableStateOf("Amor")
    var activeProvider by mutableStateOf("deepseek")
    var activeModel by mutableStateOf("deepseek-flash")
    /** 深度思考：开着时发送带思考参数；这家不认就自动去掉再发。 */
    var deepThink by mutableStateOf(false)
    val configs = mutableStateMapOf<String, ProviderConfig>()
    /** 最近一次查到的余额，只在本次打开 App 期间显示。 */
    val balances = mutableStateMapOf<String, String>()

    init {
        PROVIDERS.forEach { configs[it.id] = ProviderConfig.default(it) }
        runCatching {
            val raw = prefs.string(KEY) ?: return@runCatching
            val o = JSONObject(raw)
            name = o.optString("name", "Amor").ifBlank { "Amor" }
            activeProvider = o.optString("provider", "deepseek")
            activeModel = o.optString("model", "deepseek-flash")
            deepThink = o.optBoolean("think", false)
            val cs = o.optJSONObject("configs") ?: JSONObject()
            PROVIDERS.forEach { p ->
                val c = cs.optJSONObject(p.id) ?: return@forEach
                val d = ProviderConfig.default(p)
                configs[p.id] = ProviderConfig(
                    name = c.optString("name", d.name),
                    key = c.optString("key", ""),
                    base = c.optString("base", d.base),
                    path = c.optString("path", d.path),
                    enabled = c.optBoolean("enabled", false),
                    balance = c.optBoolean("balance", d.balance),
                    models = c.optJSONArray("models")?.let { a -> List(a.length()) { a.getString(it) } } ?: d.models,
                )
            }
        }
    }

    fun config(id: String): ProviderConfig = configs[id] ?: ProviderConfig.default(provider(id))

    fun update(id: String, c: ProviderConfig) { configs[id] = c; save() }

    fun use(providerId: String, model: String) {
        activeProvider = providerId; activeModel = model
        val c = config(providerId)
        if (!c.enabled && c.configured) configs[providerId] = c.copy(enabled = true)
        save()
    }

    fun rename(v: String) { name = v; save() }

    fun setThink(on: Boolean) { deepThink = on; save() }

    /** 填了密钥的供应商，按列表顺序。 */
    fun ready(): List<Provider> = PROVIDERS.filter { config(it.id).configured }

    fun save() {
        val cs = JSONObject()
        configs.forEach { (id, c) ->
            cs.put(id, JSONObject().apply {
                put("name", c.name); put("key", c.key); put("base", c.base); put("path", c.path)
                put("enabled", c.enabled); put("balance", c.balance); put("models", JSONArray(c.models))
            })
        }
        prefs.putString(KEY, JSONObject().apply {
            put("name", name); put("provider", activeProvider); put("model", activeModel); put("think", deepThink); put("configs", cs)
        }.toString())
    }

    companion object { private const val KEY = "assistant-config" }
}

/** 联网：列出模型、测试某个模型、查 DeepSeek 余额。失败时返回一句能看懂的中文原因。 */
object AssistantApi {
    private fun trim(base: String) = base.trim().trimEnd('/')

    private fun open(url: String, c: ProviderConfig, p: Provider, method: String = "GET"): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 12_000
        conn.readTimeout = 20_000
        if (p.kind == "anthropic") {
            conn.setRequestProperty("x-api-key", c.key.trim())
            conn.setRequestProperty("anthropic-version", "2023-06-01")
        } else {
            conn.setRequestProperty("Authorization", "Bearer " + c.key.trim())
        }
        conn.setRequestProperty("Accept", "application/json")
        return conn
    }

    private fun read(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull()
            throw IllegalStateException(
                when (code) {
                    401, 403 -> "密钥不对或没有权限（$code）"
                    402 -> "账户余额不足（402）"
                    404 -> "地址不对，找不到接口（404）"
                    429 -> "请求太频繁或额度用完（429）"
                    else -> "出错了（$code）" + (msg?.takeIf { it.isNotBlank() }?.let { "：" + it.take(80) } ?: "")
                },
            )
        }
        return body
    }

    private fun friendly(e: Throwable): String = when (e) {
        is IllegalStateException -> e.message ?: "出错了"
        is java.net.UnknownHostException -> "连不上服务器，检查网络或地址"
        is java.net.SocketTimeoutException -> "连接超时，稍后再试"
        is java.net.MalformedURLException -> "地址格式不对"
        else -> "连接失败：" + (e.message ?: e.javaClass.simpleName).take(60)
    }

    suspend fun models(p: Provider, c: ProviderConfig): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            if (c.key.isBlank()) throw IllegalStateException("先填 API Key")
            val body = read(open(trim(c.base) + "/models", c, p))
            val arr = JSONObject(body).optJSONArray("data") ?: JSONArray()
            List(arr.length()) { arr.getJSONObject(it).optString("id") }.filter { it.isNotBlank() }
        }.recoverCatching { throw IllegalStateException(friendly(it)) }
    }

    /** 发一条极短的消息，确认这个模型能用。 */
    suspend fun ping(p: Provider, c: ProviderConfig, model: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (c.key.isBlank()) throw IllegalStateException("先填 API Key")
            val path = c.path.trim().let { if (it.startsWith("/")) it else "/$it" }
            val conn = open(trim(c.base) + path, c, p, "POST")
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val payload = JSONObject().apply {
                put("model", model)
                put("max_tokens", 1)
                put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hi")))
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            read(conn)
            Unit
        }.recoverCatching { throw IllegalStateException(friendly(it)) }
    }

    /** DeepSeek 余额：返回“余额 ¥4.70 · 赠送 0.00 · 充值 4.70”。 */
    suspend fun balance(p: Provider, c: ProviderConfig): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (c.key.isBlank()) throw IllegalStateException("先填 API Key")
            val root = trim(c.base).removeSuffix("/v1")
            val body = read(open("$root/user/balance", c, p))
            val info = JSONObject(body).optJSONArray("balance_infos")?.optJSONObject(0)
                ?: throw IllegalStateException("没查到余额")
            val sign = if (info.optString("currency") == "USD") "$" else "¥"
            "余额 $sign${info.optString("total_balance")} · 赠送 ${info.optString("granted_balance")} · 充值 ${info.optString("topped_up_balance")}"
        }.recoverCatching { throw IllegalStateException(friendly(it)) }
    }
}

/** 一条聊天消息。role = "user" / "assistant"。 */
data class ChatMsg(val role: String, val text: String)

/**
 * 流式对话：OpenAI 兼容接口（stream=true 的 SSE）或 Anthropic messages 接口。
 * 每收到一段正文调 onText，收到思考过程调 onThink（只用来显示“正在思考”）。
 */
object ChatApi {
    private fun trim(base: String) = base.trim().trimEnd('/')

    /** 这家不认思考参数（400 / 422）。 */
    private class ThinkRejected : Exception()

    suspend fun stream(
        p: Provider,
        c: ProviderConfig,
        model: String,
        system: String,
        history: List<ChatMsg>,
        think: Boolean,
        onThink: suspend () -> Unit,
        onText: suspend (String) -> Unit,
    ) {
        try {
            once(p, c, model, system, history, think, onThink, onText)
        } catch (e: ThinkRejected) {
            // 和云朵一样：先怀疑思考参数，去掉再发一次
            once(p, c, model, system, history, false, onThink, onText)
        }
    }

    private suspend fun once(
        p: Provider,
        c: ProviderConfig,
        model: String,
        system: String,
        history: List<ChatMsg>,
        think: Boolean,
        onThink: suspend () -> Unit,
        onText: suspend (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        if (c.key.isBlank()) throw IllegalStateException("还没填 ${c.name} 的 API Key")
        val anthropic = p.kind == "anthropic"
        val url = if (anthropic) trim(c.base) + "/messages"
        else trim(c.base) + c.path.trim().let { if (it.startsWith("/")) it else "/$it" }
        val conn = URL(url).openConnection() as HttpURLConnection
        val job = coroutineContext[Job]
        val handle = job?.invokeOnCompletion { runCatching { conn.disconnect() } }
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 90_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "text/event-stream")
            if (anthropic) {
                conn.setRequestProperty("x-api-key", c.key.trim())
                conn.setRequestProperty("anthropic-version", "2023-06-01")
            } else conn.setRequestProperty("Authorization", "Bearer " + c.key.trim())

            val msgs = JSONArray()
            if (!anthropic) msgs.put(JSONObject().put("role", "system").put("content", system))
            history.forEach { msgs.put(JSONObject().put("role", it.role).put("content", it.text)) }
            val body = JSONObject().put("model", model).put("messages", msgs).put("stream", true)
            if (anthropic) body.put("system", system).put("max_tokens", 4096)
            if (think) {
                if (anthropic) body.put("thinking", JSONObject().put("type", "enabled").put("budget_tokens", 4000)).put("max_tokens", 8192)
                else body.put("reasoning_effort", "high")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            if (think && (code == 400 || code == 422)) throw ThinkRejected()
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                val msg = runCatching { JSONObject(err).optJSONObject("error")?.optString("message") }.getOrNull()
                throw IllegalStateException(
                    when (code) {
                        401, 403 -> "密钥不对或没有权限（$code）"
                        402 -> "账户余额不足（402）"
                        404 -> "地址或模型名不对（404）"
                        429 -> "请求太频繁或额度用完（429）"
                        else -> "出错了（$code）" + (msg?.takeIf { it.isNotBlank() }?.let { "：" + it.take(80) } ?: "")
                    },
                )
            }
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                while (true) {
                    ensureActive()
                    val line = r.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val o = runCatching { JSONObject(data) }.getOrNull() ?: continue
                    if (anthropic) {
                        if (o.optString("type") == "error") throw IllegalStateException("出错了：" + o.optJSONObject("error")?.optString("message").orEmpty().take(80))
                        val d = o.optJSONObject("delta") ?: continue
                        when (d.optString("type")) {
                            "text_delta" -> d.optString("text").takeIf { it.isNotEmpty() }?.let { withContext(Dispatchers.Main) { onText(it) } }
                            "thinking_delta" -> withContext(Dispatchers.Main) { onThink() }
                        }
                    } else {
                        val d = o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: continue
                        if (!d.isNull("reasoning_content") && d.optString("reasoning_content").isNotEmpty()) withContext(Dispatchers.Main) { onThink() }
                        if (!d.isNull("content")) d.optString("content").takeIf { it.isNotEmpty() }?.let { withContext(Dispatchers.Main) { onText(it) } }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: ThinkRejected) {
            throw e
        } catch (e: java.net.UnknownHostException) {
            throw IllegalStateException("连不上服务器，检查网络或地址")
        } catch (e: java.net.SocketTimeoutException) {
            throw IllegalStateException("等太久没有回应，稍后再试")
        } catch (e: Exception) {
            if (job?.isActive == false) throw CancellationException()
            throw IllegalStateException("连接中断：" + (e.message ?: e.javaClass.simpleName).take(60))
        } finally {
            handle?.dispose()
            runCatching { conn.disconnect() }
        }
    }
}
