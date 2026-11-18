package com.fuguang.lueying

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate

/** 用量按渠道、凭据和请求模型统计。只保存计数，不保存密钥、请求内容或响应。 */
internal object ModelUsage {
    private const val PREF = "model-usage-v1"
    private const val DAY_MS = 86_400_000L
    private var prefs: Prefs? = null
    private var days = JSONObject()
    private val rates = mutableMapOf<String, List<Rate>>()
    private val accounts = mutableMapOf<String, Account>()
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()

    data class Tokens(val input: Long?, val output: Long?)
    data class Totals(val requests: Long, val input: Long, val output: Long, val incomplete: Long)
    data class Rate(val name: String, val limit: Long, val remaining: Long, val reset: Long?, val observed: Long) {
        val fraction: Float get() = ((limit - remaining).toDouble() / limit).toFloat().coerceIn(0f, 1f)
    }
    data class Account(
        val at: Long,
        val currency: String,
        val remaining: Double,
        val limit: Double? = null,
        val period: String? = null,
    ) {
        val fraction: Float? get() = limit?.takeIf { it > 0 }?.let { ((it - remaining) / it).toFloat().coerceIn(0f, 1f) }
    }
    data class Snapshot(val totals: Totals, val rates: List<Rate>, val account: Account?)

    @Synchronized
    fun init(storage: Prefs) {
        if (prefs != null) return
        prefs = storage
        days = runCatching { JSONObject(storage.string(PREF).orEmpty()) }.getOrDefault(JSONObject())
        prune(LocalDate.now().toEpochDay())
    }

    fun accountKey(p: Provider, c: ProviderConfig): String {
        val bytes = "${p.id}\u0000${c.base.trim().trimEnd('/')}\u0000${c.key.trim()}".toByteArray()
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    fun modelKey(accountKey: String, c: ProviderConfig, model: String) = "$accountKey|${c.path.trim()}|${model.trim()}"

    @Synchronized
    fun record(key: String, tokens: Tokens) {
        val day = LocalDate.now().toEpochDay()
        prune(day)
        val daily = days.optJSONObject(day.toString()) ?: JSONObject().also { days.put(day.toString(), it) }
        val row = daily.optJSONObject(key) ?: JSONObject().also { daily.put(key, it) }
        row.put("requests", row.optLong("requests") + 1)
        row.put("input", row.optLong("input") + (tokens.input ?: 0))
        row.put("output", row.optLong("output") + (tokens.output ?: 0))
        if (tokens.input == null || tokens.output == null) row.put("incomplete", row.optLong("incomplete") + 1)
        prefs?.putString(PREF, days.toString())
        changes.value++
    }

    private fun prune(today: Long) {
        days.keys().asSequence().toList().filter { (it.toLongOrNull() ?: 0) < today - 6 }.forEach { days.remove(it) }
    }

    @Synchronized
    fun snapshot(accountKey: String, key: String, now: Long): Snapshot {
        val today = LocalDate.now().toEpochDay()
        val rows = (today - 6..today).mapNotNull { days.optJSONObject(it.toString())?.optJSONObject(key) }
        return Snapshot(
            Totals(rows.sumOf { it.optLong("requests") }, rows.sumOf { it.optLong("input") }, rows.sumOf { it.optLong("output") }, rows.sumOf { it.optLong("incomplete") }),
            rates[key].orEmpty().filter { now < (it.reset ?: it.observed + 5 * 60_000L) },
            accounts[accountKey],
        )
    }

    /** Anthropic 的 message_delta 是累计值，缺字段时沿用同一 HTTP 请求内已收到的字段。 */
    fun merge(previous: Tokens, usage: JSONObject?, anthropic: Boolean): Tokens {
        if (usage == null) return previous
        val input = if (anthropic) {
            usage.count("input_tokens")?.let { it + (usage.count("cache_read_input_tokens") ?: 0) + (usage.count("cache_creation_input_tokens") ?: 0) }
        } else usage.count("prompt_tokens") ?: usage.count("input_tokens")
        val output = if (anthropic) usage.count("output_tokens") else usage.count("completion_tokens") ?: usage.count("output_tokens")
        return Tokens(input ?: previous.input, output ?: previous.output)
    }

    private fun JSONObject.count(name: String) = optString(name).toLongOrNull()?.takeIf { it >= 0 }

    fun receiveLimits(key: String, connection: HttpURLConnection) {
        val now = System.currentTimeMillis()
        val labels = listOf("requests" to "请求数", "tokens" to "Token", "input-tokens" to "输入 Token", "output-tokens" to "输出 Token")
        val values = labels.mapNotNull { (field, name) ->
            val generic = "x-ratelimit"
            val claude = "anthropic-ratelimit-$field"
            val limit = connection.getHeaderField("$claude-limit") ?: connection.getHeaderField("$generic-limit-$field")
            val remaining = connection.getHeaderField("$claude-remaining") ?: connection.getHeaderField("$generic-remaining-$field")
            val reset = connection.getHeaderField("$claude-reset") ?: connection.getHeaderField("$generic-reset-$field")
            val total = limit?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val left = remaining?.trim()?.toLongOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null
            Rate(name, total, left, resetAt(reset, now), now)
        }
        if (values.isEmpty()) return
        synchronized(this) {
            rates[key] = values
            changes.value++
        }
    }

    private val durationPart = Regex("(\\d+(?:\\.\\d+)?)(ms|s|m|h|d)")
    internal fun resetAt(raw: String?, now: Long): Long? {
        val value = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        runCatching { Instant.parse(raw.trim()).toEpochMilli() }.getOrNull()?.let { return it }
        val parts = durationPart.findAll(value).toList()
        if (parts.isEmpty() || parts.joinToString("") { it.value } != value) return null
        val ms = parts.sumOf {
            val factor = when (it.groupValues[2]) { "d" -> DAY_MS; "h" -> 3_600_000L; "m" -> 60_000L; "s" -> 1000L; else -> 1L }
            it.groupValues[1].toDouble() * factor
        }
        return if (ms.isFinite() && ms in 0.0..(30 * DAY_MS).toDouble()) now + ms.toLong() else null
    }

    private fun officialHost(base: String): String? = runCatching {
        URI(base.trim()).takeIf { it.scheme.equals("https", true) && it.userInfo == null && (it.port == -1 || it.port == 443) }?.host?.lowercase()
    }.getOrNull()

    private val accountPaths = mapOf(
        "api.deepseek.com" to "/user/balance",
        "openrouter.ai" to "/api/v1/key",
        "api.moonshot.cn" to "/v1/users/me/balance",
        "api.moonshot.ai" to "/v1/users/me/balance",
        "api.siliconflow.cn" to "/v1/user/info",
    )

    fun canRefresh(c: ProviderConfig) = c.configured && officialHost(c.base) in accountPaths.keys

    /** 只向与当前渠道一致的官方 HTTPS 主机发送凭据，账户接口不跟随重定向。 */
    suspend fun refresh(p: Provider, c: ProviderConfig): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching<Unit> {
            if (!c.configured) return@runCatching
            val host = officialHost(c.base) ?: return@runCatching
            val path = accountPaths[host] ?: return@runCatching
            val conn = URI("https://$host$path").toURL().openConnection() as HttpURLConnection
            val body = try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("Authorization", "Bearer ${c.key.trim()}")
                check(conn.responseCode in 200..299) { "无法更新账户额度" }
                JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            } finally { conn.disconnect() }
            val account = decodeAccount(host, body, System.currentTimeMillis())
            val route = accountKey(p, c)
            synchronized(this@ModelUsage) {
                if (account == null) accounts.remove(route) else accounts[route] = account
                changes.value++
            }
        }
    }

    internal fun decodeAccount(host: String, body: JSONObject, now: Long): Account? {
        if (host == "api.deepseek.com") {
            val items = body.optJSONArray("balance_infos") ?: error("供应商未返回余额")
            return (0 until items.length()).asSequence().mapNotNull { items.optJSONObject(it) }.mapNotNull {
                val currency = it.optString("currency")
                val balance = it.money("total_balance")
                if (currency in setOf("CNY", "USD") && balance != null) Account(now, currency, balance) else null
            }.firstOrNull() ?: error("供应商未返回余额")
        }
        val data = body.optJSONObject("data") ?: error("供应商未返回账户数据")
        if (host == "openrouter.ai") {
            val limit = data.money("limit") ?: return null
            val remaining = data.money("limit_remaining") ?: error("供应商未返回剩余额度")
            val period = data.optString("limit_reset").takeUnless { it.isBlank() || it == "null" }
            return Account(now, "USD", remaining, limit, period)
        }
        val field = if (host == "api.siliconflow.cn") "totalBalance" else "available_balance"
        val currency = data.optString("currency").takeIf { it == "USD" || it == "CNY" }
            ?: if (host == "api.moonshot.ai") "USD" else "CNY"
        return Account(now, currency, data.money(field) ?: error("供应商未返回余额"))
    }

    private fun JSONObject.money(key: String) = optString(key).toDoubleOrNull()?.takeIf { it.isFinite() }

    /** 官方 DeepSeek 高峰、未缓存单价，用于保守换算。其它渠道绝不套用此价格。 */
    fun tokenEstimate(c: ProviderConfig, model: String, account: Account): Pair<Long, Long>? {
        if (officialHost(c.base) != "api.deepseek.com" || account.remaining < 0) return null
        val flash = model in setOf("deepseek-flash", "deepseek-v4-flash", "deepseek-v4-flash-vision-exp")
        if (!flash && model != "deepseek-v4-pro") return null
        val rates = when (account.currency) {
            "CNY" -> if (flash) 2.0 to 8.0 else 9.0 to 27.0
            "USD" -> if (flash) 0.30 to 1.20 else 1.32 to 3.96
            else -> return null
        }
        return (account.remaining / rates.first * 1_000_000).toLong() to (account.remaining / rates.second * 1_000_000).toLong()
    }
}
