package com.fuguang.lueying

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    Provider("grok", "Grok", "openai", "https://api.x.ai/v1", "/chat/completions", "X", note = "xAI"),
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
            models = DEFAULT_MODELS[p.id].orEmpty(),
        )

        /** 各家现在的主力型号（2026 年 10 月查的官方文档）；以后有新的，在配置页「获取模型」也能拉到。 */
        val DEFAULT_MODELS = mapOf(
            "deepseek" to listOf("deepseek-flash", "deepseek-v4-pro"),
            "claude" to listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5", "claude-fable-5-1"),
            "gemini" to listOf("gemini-4-argon", "gemini-3.8-flash", "gemini-3.5-flash-lite"),
            "openai" to listOf("gpt-6.1-sol", "gpt-6-luna"),
            "grok" to listOf("grok-4.7", "grok-4.3"),
            "kimi" to listOf("kimi-k3", "kimi-k2.6"),
        )
    }
}

/** 助理设置：名称、各供应商配置、当前用哪个供应商的哪个模型。保存在本机 SharedPreferences。 */
class AssistantStore(private val prefs: Prefs) {
    var name by mutableStateOf("Amor")
    var activeProvider by mutableStateOf("deepseek")
    var activeModel by mutableStateOf("deepseek-flash")
    /** 每个型号选的思考档（「供应商|型号」→ 发出去的原值，见 Effort）；没选过用这家的默认档。 */
    val efforts = mutableStateMapOf<String, String>()
    val configs = mutableStateMapOf<String, ProviderConfig>()
    /** 最近一次查到的余额，只在本次打开 App 期间显示。 */
    val balances = mutableStateMapOf<String, String>()
    /** 供应商的排列顺序（长按拖动调整），存的是 id。 */
    val order = mutableStateListOf<String>().apply { addAll(PROVIDERS.map { it.id }) }

    init {
        PROVIDERS.forEach { configs[it.id] = ProviderConfig.default(it) }
        runCatching {
            val raw = prefs.string(KEY) ?: return@runCatching
            val o = JSONObject(raw)
            name = o.optString("name", "Amor").ifBlank { "Amor" }
            activeProvider = o.optString("provider", "deepseek")
            activeModel = o.optString("model", "deepseek-flash")
            o.optJSONObject("efforts")?.let { e -> e.keys().forEach { k -> efforts[k] = e.optString(k) } }
            o.optJSONArray("order")?.let { a ->
                val saved = List(a.length()) { a.getString(it) }.filter { id -> PROVIDERS.any { it.id == id } }.distinct()
                order.clear(); order.addAll(saved + PROVIDERS.map { it.id }.filter { it !in saved })
            }
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
                    // 以前存的是空列表（那时只有 DeepSeek 带默认型号）：补上现在的默认型号
                    models = c.optJSONArray("models")?.let { a -> List(a.length()) { a.getString(it) } }?.takeIf { it.isNotEmpty() } ?: d.models,
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

    private fun effortKey(pid: String, model: String) = pid + "|" + model.trim().lowercase()

    /** 这个型号现在用哪一档（原值）；没档位可选的型号是 null。 */
    fun effort(pid: String = activeProvider, model: String = activeModel): String? {
        val spec = Effort.spec(pid, model)
        if (spec.levels.isEmpty()) return null
        return spec.levels[spec.index(efforts[effortKey(pid, model)])].value
    }

    fun setEffort(value: String, pid: String = activeProvider, model: String = activeModel) { efforts[effortKey(pid, model)] = value; save() }

    /** 按用户排好的顺序。 */
    fun ordered(): List<Provider> = order.mapNotNull { id -> PROVIDERS.firstOrNull { it.id == id } }

    fun move(from: Int, to: Int) {
        if (from == to || from !in order.indices || to !in order.indices) return
        order.add(to, order.removeAt(from)); save()
    }

    /** 填了密钥的供应商，按列表顺序。 */
    fun ready(): List<Provider> = ordered().filter { config(it.id).configured }

    fun save() {
        val cs = JSONObject()
        configs.forEach { (id, c) ->
            cs.put(id, JSONObject().apply {
                put("name", c.name); put("key", c.key); put("base", c.base); put("path", c.path)
                put("enabled", c.enabled); put("balance", c.balance); put("models", JSONArray(c.models))
            })
        }
        prefs.putString(KEY, JSONObject().apply {
            put("name", name); put("provider", activeProvider); put("model", activeModel); put("efforts", JSONObject(efforts.toMap())); put("configs", cs); put("order", JSONArray(order.toList()))
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
                if (p.id == "deepseek") put("thinking", JSONObject().put("type", "disabled"))
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
/** files：你发来的照片和文件（Attach.kt 的引用）。imgs：这条回复里引用到的羽的笔记图片，每项是 "笔记id/图片id"。works：这条回复里做的作品 id，workAt：每张卡插在正文第几个字后面（照她做的先后，旧记录没有就都在最后）。steps：这轮用过的工具（思考过程时间线）。 */
data class ChatMsg(val role: String, val text: String, val think: String = "", val imgs: List<String> = emptyList(), val at: Long = System.currentTimeMillis(), val works: List<String> = emptyList(), val steps: List<ToolStep> = emptyList(), val clips: List<String> = emptyList(), val files: List<String> = emptyList(), val workAt: List<Int> = emptyList(),
    /** 这条是哪家答的（定思考样式）；旧记录是空的。 */ val pid: String = "",
    /** 想了多久（毫秒）；旧记录是 0。 */ val thinkMs: Long = 0,
    /** 这一轮之后她记下的做事经验（Mem.id，Lessons.kt）；回复下面多一行「记住了一件事」。 */ val lesson: String = "")

/** 把思考原文切成段：按空行分；只有一段时按句号分成几小段。 */
fun thinkSegments(raw: String): List<String> {
    val t = raw.trim()
    if (t.isEmpty()) return emptyList()
    val paras = t.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
    if (paras.size > 1) return paras
    val sents = Regex("[^。！？!?\\n]+[。！？!?]?").findAll(t).map { it.value.trim() }.filter { it.isNotEmpty() }.toList()
    return sents.chunked(3).map { it.joinToString("") }
}

/** 一段话的第一句，最多 n 个字。 */
fun firstSentence(s: String, n: Int = 28): String {
    val one = Regex("[^。！？!?\\n]+").find(s.trim())?.value?.trim().orEmpty()
    return if (one.length > n) one.take(n) + "…" else one
}

/** 思考里最新的一句（正在写的那句）。 */
fun latestSentence(raw: String, n: Int = 28): String {
    val parts = Regex("[^。！？!?\\n]+").findAll(raw).map { it.value.trim() }.filter { it.length > 1 }.toList()
    val last = parts.lastOrNull().orEmpty()
    return if (last.length > n) "…" + last.takeLast(n) else last
}

/** 思考结束后的一句总结：取最后一段的第一句。 */
fun thinkSummary(raw: String): String = thinkSegments(raw).lastOrNull()?.let { firstSentence(it, 32) }.orEmpty()

/** 这一轮对话可以用的工具：工具说明、怎么执行、执行时告诉界面一声。 */
class Tools(
    val specs: List<ToolSpec>,
    val run: suspend (String, JSONObject) -> ToolOut,
    val onUse: suspend (String) -> Unit,
    /** 每做完一步工具问一声：你在她做事时又发来的话（没有就是 null），她接着做时一起看到。 */
    val inbox: suspend () -> String? = { null },
    /** 做作品的参数还在流式来：名字、已写多少字、开头一段（取标题）。 */
    val onDraft: (String, Int, String) -> Unit = { _, _, _ -> },
)

/**
 * 她说「做好了」却没调用工具时用（2026-10 DeepSeek 真机：先说「写好了，卡在下面」，作品没做）。
 * 只看这一轮最后那段正文；整轮里调用过 make_work / edit_work 就不管。
 */
/** 一轮里最多连着用几次工具（看素材、看原稿、交代作品、上网查都算一次）。2026-10 以前是 5，看 6 件素材再看原稿就用完了。 */
private const val MAX_ROUNDS = 10
private const val CAP_NOTE = "（这是 App 的提醒，不是对方说的话：这一轮能用工具的次数用完了，现在不能再动手。如实告诉对方做到了哪一步，还没做的就说还没做，等他说一声你再接着做。别说已经做好。）"

object Claims {
    private val DONE = Regex("(写|做|改|弄|搭|画|排)(好|完)了|已经(写|做|改|弄|搭|生成)(好|完|出来)|生成好了|做出来了|卡(片)?(就)?(在|放在)下面|(在|放在)下面(的卡|那张)")
    fun saysDone(text: String) = DONE.containsMatchIn(text)
    const val NUDGE = "（这是 App 的提醒，不是对方说的话：你刚才说作品做好了 / 改好了，可这一轮你没有调用 make_work 或 edit_work，作品卡并没有出现。" +
        "要做就现在真的调用工具去做，等它回来再说；如果你其实没打算做作品，就自然地接着聊。不要提这条提醒，也不用道歉。）"
}

/** 做事中途收到的话，放进工具结果后面给她看。 */
private fun midTurn(q: String) = "（你在忙的时候，${Memory.userCall}又说：）\n$q\n\n（把这句也放在心上，接着做。）"

/** 工具结果：文字，加上要给模型看的图片。 */
class ToolOut(val text: String, val images: List<ToolImage> = emptyList())
class ToolImage(val mime: String, val base64: String)

/**
 * 流式对话：OpenAI 兼容接口（stream=true 的 SSE）或 Anthropic messages 接口。
 * 每收到一段正文调 onText，收到思考过程调 onThink（只用来显示“正在思考”）。
 * 带工具时：模型要用工具 → 执行 → 把结果发回去 → 继续写，最多来回几轮。
 */
object ChatApi {
    private fun trim(base: String) = base.trim().trimEnd('/')

    /** 这家不认思考参数或工具（400 / 422）。 */
    private class Rejected : Exception()
    /** 报错点名了某个参数，已经记进 ModelFit：去掉它重新发。 */
    private class Adapted : Exception()

    private class Call(var id: String = "", val name: StringBuilder = StringBuilder(), val args: StringBuilder = StringBuilder()) { var told = -1 }

    /** 做作品的参数还在一段段来：告诉聊天页卡片该占位了、写到多少字（每 400 字一次）。 */
    private suspend fun draft(tools: Tools?, call: Call) {
        if (tools == null || call.name.isEmpty() || (call.told >= 0 && call.args.length - call.told < 400)) return
        val n = call.name.toString(); if (n != Works.MAKE && n != Works.EDIT) return
        call.told = call.args.length
        val len = call.args.length; val head = call.args.substring(0, minOf(len, 800))
        withContext(Dispatchers.Main) { tools.onDraft(n, len, head) }
    }

    /** 一次请求的结果。 */
    private class Round(val text: String, val reasoning: String, val calls: List<Call>, val blocks: JSONArray)

    /** 这一轮聊天用的温度和 Top P；null = 不带，交给供应商默认。 */
    @Volatile var temperature: Float? = null
    @Volatile var topP: Float? = null
    /** 这一轮里 App 认出的岔子（Lessons.CLAIM 说做好了没动手、Lessons.CAP 工具用到顶）；每次 stream 开始时清空。 */
    val slips: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    suspend fun stream(
        p: Provider,
        c: ProviderConfig,
        model: String,
        system: String,
        history: List<ChatMsg>,
        /** 这一档发出去的原值（Effort）：Effort.OFF 是关掉思考；null 是什么思考参数都不带。 */
        effort: String?,
        onThink: suspend (String) -> Unit,
        tools: Tools? = null,
        /** 模型不支持工具时，放进 system 提示的补充内容。 */
        fallback: (suspend () -> String)? = null,
        /** 只在真的带上工具时才拼进 system 的说明：工具被去掉了还写着「你有笔」，她会说做好了其实没做。 */
        toolNote: String = "",
        onText: suspend (String) -> Unit,
    ) {
        slips.clear()
        // 先按 ModelFit 认这个模型（认过 / 学过的参数一开始就不带）；报错点名了哪个参数就记下、去掉重发（最多 5 次）；思考档位先一档档往下退，退到底才不带。
        repeat(5) {
            try { return tiers(p, c, model, system, history, effort, onThink, tools, fallback, toolNote, onText) } catch (_: Adapted) { }
        }
        tiers(p, c, model, system, history, effort, onThink, tools, fallback, toolNote, onText)
    }

    private suspend fun tiers(
        p: Provider, c: ProviderConfig, model: String, system: String, history: List<ChatMsg>, effort: String?,
        onThink: suspend (String) -> Unit, tools: Tools?, fallback: (suspend () -> String)?, toolNote: String, onText: suspend (String) -> Unit,
    ) {
        // 报错没点名时：先怀疑工具，再怀疑思考参数，逐个去掉再发；null = 什么思考参数都不带
        val hasTools = tools != null && !ModelFit.drops(p.id, model, "tools")
        val tries = listOf(effort to hasTools, effort to false, null to hasTools, null to false).distinct()
        // 关闭时就算模型自己想了也不显示
        val show: suspend (String) -> Unit = if (effort == Effort.OFF) ({ }) else onThink
        tries.forEachIndexed { i, (lv, useTools) ->
            try {
                val sys = when {
                    tools == null -> system
                    useTools -> system + toolNote
                    else -> system + "\n\n这一会儿你手边那些本事用不上：记不了事，也做不了作品。没真做出来的东西，别说已经做好了。" +
                        (fallback?.let { "\n\n" + runCatching { it() }.getOrDefault("") } ?: "")
                }
                conversation(p, c, model, sys, history, lv, if (useTools) tools else null, show, onText)
                // 去掉思考参数、工具留着就通了：这个模型不收思考参数，记下来，下次直接不带
                if (lv == null && effort != null && useTools == hasTools) ModelFit.learn(p.id, model, "think")
                return
            } catch (e: Rejected) {
                if (i == tries.lastIndex) throw IllegalStateException("出错了（400）")
            }
        }
    }

    private suspend fun conversation(
        p: Provider, c: ProviderConfig, model: String, system: String, history: List<ChatMsg>,
        effort: String?, tools: Tools?, onThink: suspend (String) -> Unit, onText: suspend (String) -> Unit,
    ) {
        val anthropic = p.kind == "anthropic"
        val msgs = JSONArray()
        if (!anthropic) msgs.put(JSONObject().put("role", "system").put("content", system))
        // DeepSeek 思考模式下带工具：以前每一条回复的 reasoning_content 都要带回去，没想过的也要带一个空的，
        // 少一条就 400，然后会退成不带工具再发，她就只能嘴上说「做好了」（作品卡丢失的根子）
        val echo = p.id == "deepseek" && tools != null && effort != Effort.OFF
        // 做事中途收到新消息时，那一段回复可能没有正文：空的不带，免得供应商报错
        // 你发来的照片、文件：最近两条带东西的原样带上，更早的只说一句
        val withFiles = history.indices.filter { history[it].role == "user" && history[it].files.isNotEmpty() }.takeLast(2).toSet()
        val sees = Attach.vision(p.id, model)
        history.forEachIndexed { i, it ->
            if (it.role == "assistant" && it.text.isBlank()) return@forEachIndexed
            val body: Any = when {
                it.role == "assistant" -> ToolText.clean(it.text)
                it.files.isNotEmpty() -> Attach.content(it, anthropic, sees, i in withFiles)
                else -> it.text
            }
            val m = JSONObject().put("role", it.role).put("content", body)
            if (echo && it.role == "assistant") m.put("reasoning_content", it.think)
            msgs.put(m)
        }
        var round = 0
        var worked = false
        var nudged = false
        while (true) {
            // 工具次数到顶：以前是悄悄收走工具，她还以为能动手，写了调用也被丢掉，只能嘴上说「做好了」。现在明说
            if (round == MAX_ROUNDS && tools != null) {
                slips += Lessons.CAP
                val last = msgs.optJSONObject(msgs.length() - 1)
                val arr = last?.optJSONArray("content")
                if (anthropic && last?.optString("role") == "user" && arr != null) arr.put(JSONObject().put("type", "text").put("text", CAP_NOTE))
                else msgs.put(JSONObject().put("role", "user").put("content", CAP_NOTE))
            }
            val r = once(p, c, model, system, msgs, effort, if (round < MAX_ROUNDS) tools else null, round == 0, onThink, onText)
            if (r.calls.any { it.name.toString() in setOf(Works.MAKE, Works.EDIT, WorkJobs.START) }) worked = true
            // 嘴上说「做好了 / 卡在下面」，这一轮却没真的调用做作品的工具：提醒她一次，接着真的去做
            if (r.calls.isEmpty() && tools != null && round < MAX_ROUNDS && !nudged && !worked && Claims.saysDone(r.text)) {
                nudged = true; round++; slips += Lessons.CLAIM
                if (r.text.isNotBlank()) msgs.put(if (anthropic) JSONObject().put("role", "assistant").put("content", r.blocks) else JSONObject().put("role", "assistant").put("content", r.text).apply { if (p.id == "deepseek" && (echo || r.reasoning.isNotEmpty())) put("reasoning_content", r.reasoning) })
                msgs.put(JSONObject().put("role", "user").put("content", Claims.NUDGE))
                continue
            }
            if (r.calls.isEmpty() || tools == null || round >= MAX_ROUNDS) return
            round++
            if (anthropic) {
                msgs.put(JSONObject().put("role", "assistant").put("content", r.blocks))
                val results = JSONArray()
                val outs: List<ToolOut> = if (r.calls.size > 1 && r.calls.all { it.name.toString() in READ_ONLY }) coroutineScope {
                    r.calls.map { call -> async { tools.onUse(call.name.toString()); tools.run(call.name.toString(), args(call)) } }.map { it.await() }
                } else r.calls.map { call -> tools.onUse(call.name.toString()); tools.run(call.name.toString(), args(call)) }
                r.calls.forEachIndexed { k, call ->
                    val out = outs[k]
                    val content: Any = if (out.images.isEmpty()) out.text else JSONArray().apply {
                        put(JSONObject().put("type", "text").put("text", out.text))
                        out.images.forEach { im ->
                            put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", im.mime).put("data", im.base64)))
                        }
                    }
                    results.put(JSONObject().put("type", "tool_result").put("tool_use_id", call.id).put("content", content))
                }
                tools.inbox()?.let { results.put(JSONObject().put("type", "text").put("text", midTurn(it))) }
                msgs.put(JSONObject().put("role", "user").put("content", results))
            } else {
                val tc = JSONArray()
                r.calls.forEachIndexed { k, call ->
                    if (call.id.isBlank()) call.id = "call_${round}_$k"
                    tc.put(
                        JSONObject().put("id", call.id).put("type", "function")
                            .put("function", JSONObject().put("name", call.name.toString()).put("arguments", call.args.toString().ifBlank { "{}" })),
                    )
                }
                val am = JSONObject().put("role", "assistant").put("content", r.text).put("tool_calls", tc)
                // DeepSeek 思考模式下用工具，要把这一轮的思考原样带回去
                if (echo || (p.id == "deepseek" && r.reasoning.isNotEmpty())) am.put("reasoning_content", r.reasoning)
                msgs.put(am)
                // 工具消息只能放文字，图片另起一条放在后面给模型看
                val pics = JSONArray()
                // 只读的事（翻本事、找本事、上网查、翻聊天、看原稿）几件一起做；要动东西、要你点头的还是一件件来
                val outs: List<ToolOut> = if (r.calls.size > 1 && r.calls.all { it.name.toString() in READ_ONLY }) coroutineScope {
                    r.calls.map { call -> async { tools.onUse(call.name.toString()); tools.run(call.name.toString(), args(call)) } }.map { it.await() }
                } else r.calls.map { call -> tools.onUse(call.name.toString()); tools.run(call.name.toString(), args(call)) }
                r.calls.forEachIndexed { k, call ->
                    val out = outs[k]
                    msgs.put(JSONObject().put("role", "tool").put("tool_call_id", call.id).put("content", out.text))
                    out.images.forEach { im ->
                        pics.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:${im.mime};base64,${im.base64}")))
                    }
                }
                if (pics.length() > 0) {
                    val content = JSONArray().put(JSONObject().put("type", "text").put("text", "（这是刚才读取的笔记里的图片）"))
                    for (k in 0 until pics.length()) content.put(pics.get(k))
                    msgs.put(JSONObject().put("role", "user").put("content", content))
                }
                tools.inbox()?.let { msgs.put(JSONObject().put("role", "user").put("content", midTurn(it))) }
            }
        }
    }

    /** 不流式、不思考的一次问答（整理记忆用），返回正文。 */
    suspend fun complete(p: Provider, c: ProviderConfig, model: String, system: String, user: String, maxTokens: Int = 1024, think: Boolean = false): String = withContext(Dispatchers.IO) {
        val anthropic = p.kind == "anthropic"
        val url = if (anthropic) trim(c.base) + "/messages"
        else trim(c.base) + c.path.trim().let { if (it.startsWith("/")) it else "/$it" }
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = if (think) 180_000 else 90_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (anthropic) {
                conn.setRequestProperty("x-api-key", c.key.trim())
                conn.setRequestProperty("anthropic-version", "2023-06-01")
            } else conn.setRequestProperty("Authorization", "Bearer " + c.key.trim())
            val msgs = JSONArray()
            if (!anthropic) msgs.put(JSONObject().put("role", "system").put("content", system))
            msgs.put(JSONObject().put("role", "user").put("content", user))
            val body = JSONObject().put("model", model).put("messages", msgs).put("stream", false)
            if (anthropic) body.put("system", system).put("max_tokens", if (think) maxOf(maxTokens, 16000) else maxTokens)
            // 记忆模型开了「启用思考」：按这个型号的默认档想（不支持的型号照常不带）；没开就不想，DeepSeek 默认会思考，明确关掉
            if (think) {
                val spec = Effort.spec(p.id, model)
                val lv = spec.levels.getOrNull(spec.def)?.value?.takeIf { it != Effort.OFF } ?: spec.levels.firstOrNull { it.value != Effort.OFF }?.value
                if (lv != null) Effort.apply(p, model, lv, body)
            } else if (p.id == "deepseek") body.put("thinking", JSONObject().put("type", "disabled"))
            ModelFit.strip(p.id, model, body)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode !in 200..299) throw IllegalStateException("记忆整理失败（${conn.responseCode}）")
            val o = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            if (anthropic) {
                val arr = o.optJSONArray("content") ?: JSONArray()
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") }
            } else o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
        } finally { runCatching { conn.disconnect() } }
    }

    /** 能一起并发着做的工具（只读、不问人）。 */
    private val READ_ONLY = setOf(Memory.READ, Skills.OPEN, Skills.FIND, WebSearch.TOOL, Recall.TOOL, Works.READ, Materials.OPEN, Materials.LOOK, VideoParse.TOOL, PanTools.OPEN, ClockTools.NEXT, ClockTools.LIST)

    private fun args(call: Call) = runCatching { JSONObject(call.args.toString().ifBlank { "{}" }) }.getOrDefault(JSONObject())

    private suspend fun once(
        p: Provider,
        c: ProviderConfig,
        model: String,
        system: String,
        msgs: JSONArray,
        effort: String?,
        tools: Tools?,
        canReject: Boolean,
        onThink: suspend (String) -> Unit,
        onText: suspend (String) -> Unit,
    ): Round = withContext(Dispatchers.IO) {
        if (c.key.isBlank()) throw IllegalStateException("还没填 ${c.name} 的 API Key")
        val anthropic = p.kind == "anthropic"
        val url = if (anthropic) trim(c.base) + "/messages"
        else trim(c.base) + c.path.trim().let { if (it.startsWith("/")) it else "/$it" }
        val conn = URL(url).openConnection() as HttpURLConnection
        val job = coroutineContext[Job]
        val handle = job?.invokeOnCompletion { runCatching { conn.disconnect() } }
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val calls = sortedMapOf<Int, Call>()
        // Anthropic：按序号收集每一块（思考、正文、工具调用），用工具时要原样发回
        val blocks = sortedMapOf<Int, JSONObject>()
        val partial = mutableMapOf<Int, StringBuilder>()
        // DeepSeek 把工具调用写进正文时（DSML 标记）：不显示，抠出来照样去做
        val inline = ToolText()
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

            val body = JSONObject().put("model", model).put("messages", msgs).put("stream", true)
            // 做作品时一整张网页写在工具参数里，留足长度
            if (anthropic) body.put("system", system).put("max_tokens", 16000)
            // 这个模型学到的最高档：超过的按能到的最高一档发
            val spec = Effort.spec(p.id, model)
            val lv = effort?.let { e -> ModelFit.cap(p.id, model)?.takeIf { e != Effort.OFF && spec.index(it) < spec.index(e) } ?: e }
            val sentThink = Effort.apply(p, model, lv, body)
            // 回答的手感：温度、Top P（Anthropic 想着的时候不能带）
            if (!(anthropic && lv != null && lv != Effort.OFF)) {
                temperature?.let { body.put("temperature", it.toDouble()) }
                topP?.let { body.put("top_p", it.toDouble()) }
            }
            if (tools != null) {
                val arr = JSONArray()
                tools.specs.forEach { t ->
                    arr.put(
                        if (anthropic) JSONObject().put("name", t.name).put("description", t.description).put("input_schema", t.schema)
                        else JSONObject().put("type", "function").put(
                            "function", JSONObject().put("name", t.name).put("description", t.description).put("parameters", t.schema),
                        ),
                    )
                }
                body.put("tools", arr)
            }
            ModelFit.strip(p.id, model, body)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            val err = if (code !in 200..299) conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "" else ""
            if (code == 400 || code == 422) {
                // 报错点名了哪个参数：记在这个模型名下，去掉重发
                ModelFit.blame(err, body)?.let { w ->
                    if (w == "think" && lv != null && lv != Effort.OFF) ModelFit.stepDown(p.id, model, Effort.lower(spec, lv)) else ModelFit.learn(p.id, model, w)
                    throw Adapted()
                }
                if (canReject && (sentThink || tools != null)) throw Rejected()
            }
            if (code !in 200..299) {
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
                        when (o.optString("type")) {
                            "error" -> throw IllegalStateException("出错了：" + o.optJSONObject("error")?.optString("message").orEmpty().take(80))
                            "content_block_start" -> {
                                val i = o.optInt("index")
                                val cb = o.optJSONObject("content_block") ?: JSONObject()
                                blocks[i] = JSONObject(cb.toString())
                                if (cb.optString("type") == "tool_use") calls[i] = Call(cb.optString("id")).also { it.name.append(cb.optString("name")) }
                            }
                            "content_block_delta" -> {
                                val i = o.optInt("index")
                                val d = o.optJSONObject("delta") ?: continue
                                val b = blocks[i]
                                when (d.optString("type")) {
                                    "text_delta" -> d.optString("text").takeIf { it.isNotEmpty() }?.let {
                                        text.append(it); b?.put("text", b.optString("text") + it)
                                        withContext(Dispatchers.Main) { onText(it) }
                                    }
                                    "thinking_delta" -> d.optString("thinking").takeIf { it.isNotEmpty() }?.let {
                                        b?.put("thinking", b.optString("thinking") + it)
                                        withContext(Dispatchers.Main) { onThink(it) }
                                    }
                                    "signature_delta" -> b?.put("signature", b.optString("signature") + d.optString("signature"))
                                    "input_json_delta" -> {
                                        val pj = d.optString("partial_json")
                                        partial.getOrPut(i) { StringBuilder() }.append(pj)
                                        calls[i]?.let { cl -> cl.args.append(pj); draft(tools, cl) }
                                    }
                                }
                            }
                        }
                    } else {
                        val d = o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: continue
                        if (!d.isNull("reasoning_content")) d.optString("reasoning_content").takeIf { it.isNotEmpty() }?.let {
                            reasoning.append(it); withContext(Dispatchers.Main) { onThink(it) }
                        }
                        if (!d.isNull("content")) d.optString("content").takeIf { it.isNotEmpty() }?.let { s ->
                            inline.feed(s).takeIf { it.isNotEmpty() }?.let { withContext(Dispatchers.Main) { onText(it) } }
                        }
                        d.optJSONArray("tool_calls")?.let { arr ->
                            for (k in 0 until arr.length()) {
                                val tc = arr.optJSONObject(k) ?: continue
                                val call = calls.getOrPut(tc.optInt("index", k)) { Call() }
                                tc.optString("id").takeIf { it.isNotEmpty() && call.id.isEmpty() }?.let { call.id = it }
                                tc.optJSONObject("function")?.let { f ->
                                    if (!f.isNull("name")) call.name.append(f.optString("name"))
                                    if (!f.isNull("arguments")) call.args.append(f.optString("arguments"))
                                }
                                draft(tools, call)
                            }
                        }
                    }
                }
            }
            if (!anthropic) {
                val (rest, all, found) = inline.finish()
                if (rest.isNotEmpty()) withContext(Dispatchers.Main) { onText(rest) }
                text.append(all)
                // 正规的 tool_calls 没有、正文里却写了调用：照着做
                if (tools != null && calls.isEmpty()) found.forEachIndexed { k, f ->
                    calls[k] = Call("inline_$k").also { it.name.append(f.name); it.args.append(f.args.toString()) }
                }
            }
            // 整理成要发回去的内容块
            val out = JSONArray()
            blocks.forEach { (i, b) ->
                when (b.optString("type")) {
                    "text" -> if (b.optString("text").isNotEmpty()) out.put(b)
                    "tool_use" -> out.put(b.put("input", runCatching { JSONObject(partial[i]?.toString().orEmpty().ifBlank { "{}" }) }.getOrDefault(JSONObject())))
                    else -> out.put(b)
                }
            }
            Round(text.toString(), reasoning.toString(), calls.values.filter { it.name.isNotEmpty() }, out)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Rejected) {
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


