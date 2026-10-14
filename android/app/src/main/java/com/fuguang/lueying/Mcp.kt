package com.fuguang.lueying

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * MCP 服务：别人托管在网上的工具箱（高德地图、12306、网页抓取……），她连上就能用。
 *
 * 只做远程的两种传输：
 *  - Streamable HTTP：每次 POST 一条 JSON-RPC，回 JSON 或 SSE；会话 id 放在 Mcp-Session-Id 头里
 *  - 旧式 SSE：GET 拿到 endpoint，再 POST 到 endpoint，回复从 GET 的流里来
 * 本机 stdio 那种是桌面的，手机上不做。
 */

/** 一个服务里的一个工具。 */
class McpTool(val name: String, val description: String, val schema: JSONObject)

/** 用户连上的一个服务。 */
class McpServer(
    val id: String,
    name: String,
    url: String,
    token: String,
    tools: List<McpTool>,
    off: Set<String>,
    enabled: Boolean,
    /** 来自精选单的哪一项（空 = 自己粘贴的）。 */
    val catalog: String = "",
) {
    var name by mutableStateOf(name)
    var url by mutableStateOf(url)
    var token by mutableStateOf(token)
    var tools by mutableStateOf(tools)
    /** 关掉的工具名；默认全开。 */
    var off by mutableStateOf(off)
    var enabled by mutableStateOf(enabled)

    val initial get() = name.trim().take(1).ifEmpty { "M" }
    val onTools get() = tools.filter { it.name !in off }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("url", url); put("token", token); put("enabled", enabled); put("catalog", catalog)
        put("off", JSONArray(off.toList()))
        put("tools", JSONArray().apply { tools.forEach { t -> put(JSONObject().put("name", t.name).put("description", t.description).put("schema", t.schema)) } })
    }

    companion object {
        fun fromJson(o: JSONObject): McpServer {
            val tools = o.optJSONArray("tools")?.let { a -> List(a.length()) { i -> a.getJSONObject(i).let { t -> McpTool(t.optString("name"), t.optString("description"), t.optJSONObject("schema") ?: JSONObject()) } } }.orEmpty()
            val off = o.optJSONArray("off")?.let { a -> List(a.length()) { a.optString(it) } }?.toSet().orEmpty()
            return McpServer(o.optString("id"), o.optString("name"), o.optString("url"), o.optString("token"), tools, off, o.optBoolean("enabled", true), o.optString("catalog"))
        }
    }
}

/**
 * 精选单里的一项。
 * auth：
 *  - "key"：地址里带服务商的 key（高德、百度），用户去服务商那里申请
 *  - "paste"：魔搭这类托管，每个人地址不一样，去页面复制自己的
 *  - "bearer"：地址固定，token 放 Authorization 头（GitHub）
 *  - "none"：公开、不用任何东西
 */
class McpCatalogItem(
    val id: String,
    val name: String,
    val desc: String,
    val category: String,
    val auth: String,
    /** 地址，{key} 处填 key；paste 时为空。 */
    val url: String,
    /** 去哪里拿 key / 地址。 */
    val help: String,
    val helpUrl: String,
    /** 默认只开这几个工具；空 = 全开。 */
    val defaultTools: List<String> = emptyList(),
)

object Mcp {
    private lateinit var prefs: Prefs
    val servers = mutableStateListOf<McpServer>()

    /** 精选单：内置一份，公开仓库里有新的就换。 */
    var catalog by mutableStateOf<List<McpCatalogItem>>(emptyList())
        private set

    fun init(p: Prefs) {
        prefs = p
        catalog = BUILTIN
        runCatching {
            val a = JSONArray(prefs.string(KEY) ?: "[]")
            for (i in 0 until a.length()) servers.add(McpServer.fromJson(a.getJSONObject(i)))
        }
    }

    fun save() { prefs.putString(KEY, JSONArray().apply { servers.forEach { put(it.toJson()) } }.toString()) }

    fun get(id: String) = servers.firstOrNull { it.id == id }
    fun byCatalog(cid: String) = servers.firstOrNull { it.catalog == cid }
    fun enabled() = servers.filter { it.enabled && it.onTools.isNotEmpty() }

    fun add(s: McpServer) { servers.removeAll { it.id == s.id }; servers.add(s); save() }
    fun remove(id: String) { servers.removeAll { it.id == id }; save() }

    fun newId(): String = "m" + java.lang.Long.toString(System.currentTimeMillis(), 36)

    suspend fun refreshCatalog() = withContext(Dispatchers.IO) {
        val raw = fetchFirst(ContentStore.MIRRORS.map { "$it/mcp/catalog.json" }) ?: return@withContext
        val list = runCatching {
            val a = JSONObject(raw).optJSONArray("items") ?: return@runCatching null
            List(a.length()) { a.getJSONObject(it) }.map { o ->
                McpCatalogItem(
                    o.optString("id"), o.optString("name"), o.optString("desc"), o.optString("category", "其他"),
                    o.optString("auth", "paste"), o.optString("url"), o.optString("help"), o.optString("helpUrl"),
                    o.optJSONArray("defaultTools")?.let { d -> List(d.length()) { d.optString(it) } }.orEmpty(),
                )
            }.filter { it.id.isNotBlank() && it.name.isNotBlank() }
        }.getOrNull()
        if (!list.isNullOrEmpty()) withContext(Dispatchers.Main) { catalog = list }
    }

    /* ---------- 给模型 ---------- */

    private const val SEP = "__"
    fun toolName(s: McpServer, t: McpTool) = "${s.id}$SEP${t.name}"
    fun owns(name: String) = name.contains(SEP) && get(name.substringBefore(SEP)) != null

    val specs: List<ToolSpec> get() = enabled().flatMap { s -> s.onTools.map { t -> ToolSpec(toolName(s, t), "〈${s.name}〉${t.description}".take(1000), t.schema) } }

    fun system(): String {
        val on = enabled()
        if (on.isEmpty()) return ""
        return "还连着这些服务，也在你手边：" + on.joinToString("；") { s -> s.name + "（" + s.onTools.joinToString("、") { toolName(s, it) } + "）" } +
            "。用不用、什么时候用，你自己判断；用的时候不用说“我去查一下”，看完自然地说就好。"
    }

    fun note(name: String): String = get(name.substringBefore(SEP))?.let { "正在用〈${it.name}〉…" } ?: "正在查…"

    suspend fun run(name: String, args: JSONObject): ToolOut = withContext(Dispatchers.IO) {
        val s = get(name.substringBefore(SEP)) ?: return@withContext ToolOut("没有这个服务。")
        val tool = name.substringAfter(SEP)
        runCatching {
            val r = Client(s.url, s.token).call("tools/call", JSONObject().put("name", tool).put("arguments", args))
            val result = r.optJSONObject("result") ?: return@runCatching ToolOut("〈${s.name}〉出错：" + (r.optJSONObject("error")?.optString("message") ?: r.toString().take(300)))
            // content 里把文字拼起来；图片交给模型（能看图的话）
            val texts = ArrayList<String>()
            val imgs = ArrayList<ToolImage>()
            val content = result.optJSONArray("content") ?: JSONArray()
            for (i in 0 until content.length()) {
                val c = content.optJSONObject(i) ?: continue
                when (c.optString("type")) {
                    "text" -> texts.add(c.optString("text"))
                    "image" -> imgs.add(ToolImage(c.optString("mimeType", "image/png"), c.optString("data")))
                    "resource" -> c.optJSONObject("resource")?.optString("text")?.takeIf { it.isNotEmpty() }?.let { texts.add(it) }
                }
            }
            if (texts.isEmpty() && result.has("structuredContent")) texts.add(result.optJSONObject("structuredContent").toString())
            val text = texts.joinToString("\n").ifBlank { if (imgs.isEmpty()) "（没有返回内容）" else "" }
            ToolOut((if (result.optBoolean("isError")) "〈${s.name}〉出错：" else "") + text.take(24_000), imgs)
        }.getOrElse { ToolOut("〈${s.name}〉连不上：${it.message}") }
    }

    /* ---------- 连接 / 拉工具 ---------- */

    /** 连一次，拿回服务名和工具列表；失败抛异常，message 是能看懂的中文。 */
    suspend fun probe(url: String, token: String): Pair<String, List<McpTool>> = withContext(Dispatchers.IO) {
        val c = Client(url, token)
        val init = c.initialize()
        val name = init.optJSONObject("result")?.optJSONObject("serverInfo")?.optString("name").orEmpty()
        val r = c.call("tools/list", JSONObject())
        val arr = r.optJSONObject("result")?.optJSONArray("tools") ?: throw Exception(r.optJSONObject("error")?.optString("message") ?: "服务没有返回工具列表")
        val tools = List(arr.length()) { i ->
            val t = arr.getJSONObject(i)
            McpTool(t.optString("name"), t.optString("description"), t.optJSONObject("inputSchema") ?: JSONObject().put("type", "object"))
        }.filter { it.name.isNotBlank() }
        name to tools
    }

    /** 一次连接：先 initialize，之后每次 call 复用会话。 */
    private class Client(val url: String, val token: String) {
        private var session: String? = null
        private var endpoint: String? = null
        private var inited = false
        private var seq = 0
        private val legacy = url.trimEnd('/').endsWith("/sse") || url.contains("/sse?")

        fun initialize(): JSONObject {
            val r = send(rpc("initialize", JSONObject()
                .put("protocolVersion", "2025-03-26")
                .put("capabilities", JSONObject())
                .put("clientInfo", JSONObject().put("name", "fuguang-lueying").put("version", "1"))))
            if (r.has("error")) throw Exception(r.getJSONObject("error").optString("message", "初始化失败"))
            inited = true
            runCatching { send(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"), expectReply = false) }
            return r
        }

        fun call(method: String, params: JSONObject): JSONObject {
            if (!inited) initialize()
            return send(rpc(method, params))
        }

        private fun rpc(method: String, params: JSONObject) = JSONObject().put("jsonrpc", "2.0").put("id", ++seq).put("method", method).put("params", params)

        private fun open(u: String, method: String): HttpURLConnection = (URL(u).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000; readTimeout = 60_000
            setRequestProperty("User-Agent", "fuguang-lueying")
            if (token.isNotBlank()) setRequestProperty("Authorization", if (token.startsWith("Bearer ", true)) token else "Bearer $token")
            session?.let { setRequestProperty("Mcp-Session-Id", it) }
        }

        private fun send(msg: JSONObject, expectReply: Boolean = true): JSONObject =
            if (legacy) sendLegacy(msg, expectReply) else sendStreamable(msg, expectReply)

        private fun sendStreamable(msg: JSONObject, expectReply: Boolean): JSONObject {
            val conn = open(url, "POST")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json, text/event-stream")
            conn.doOutput = true
            conn.outputStream.use { it.write(msg.toString().toByteArray()) }
            val code = conn.responseCode
            conn.getHeaderField("Mcp-Session-Id")?.let { session = it }
            if (code == 202 || !expectReply) { conn.disconnect(); return JSONObject() }
            if (code >= 400) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw Exception(when (code) { 401, 403 -> "服务拒绝了：token 不对或没填"; 404 -> "地址不对（404）"; else -> "服务返回 $code ${err.take(120)}" })
            }
            val type = conn.contentType.orEmpty()
            val id = msg.opt("id")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                if (type.contains("text/event-stream")) readSse(r, id) ?: throw Exception("服务没有回复")
                else JSONObject(r.readText().ifBlank { "{}" })
            }
        }

        /** 旧式 SSE：先 GET 拿 endpoint，POST 过去，回复从同一条流里读。 */
        private fun sendLegacy(msg: JSONObject, expectReply: Boolean): JSONObject {
            val stream = open(url, "GET").apply { setRequestProperty("Accept", "text/event-stream") }
            val code = stream.responseCode
            if (code >= 400) throw Exception(when (code) { 401, 403 -> "服务拒绝了：key 或 token 不对"; 404 -> "地址不对（404）"; else -> "服务返回 $code" })
            val reader = stream.inputStream.bufferedReader(Charsets.UTF_8)
            try {
                // 第一个事件：endpoint
                var ev = ""; var data = StringBuilder(); var ep: String? = null
                while (ep == null) {
                    val line = reader.readLine() ?: throw Exception("服务没有给出 endpoint")
                    when {
                        line.startsWith("event:") -> ev = line.removePrefix("event:").trim()
                        line.startsWith("data:") -> data.append(line.removePrefix("data:").trim())
                        line.isEmpty() -> { if (ev == "endpoint") ep = data.toString(); ev = ""; data = StringBuilder() }
                    }
                }
                endpoint = if (ep.startsWith("http")) ep else URL(URL(url), ep).toString()
                val post = open(endpoint!!, "POST")
                post.setRequestProperty("Content-Type", "application/json")
                post.doOutput = true
                post.outputStream.use { it.write(msg.toString().toByteArray()) }
                val pc = post.responseCode
                if (pc >= 400) throw Exception("服务返回 $pc")
                post.disconnect()
                if (!expectReply) return JSONObject()
                return readSse(reader, msg.opt("id")) ?: throw Exception("服务没有回复")
            } finally {
                runCatching { reader.close() }; stream.disconnect()
            }
        }

        /** 从 SSE 流里读到 id 对上的那条 JSON-RPC 回复。 */
        private fun readSse(r: BufferedReader, id: Any?): JSONObject? {
            var data = StringBuilder()
            while (true) {
                val line = r.readLine() ?: return null
                when {
                    line.startsWith("data:") -> data.append(line.removePrefix("data:").trim())
                    line.isEmpty() -> {
                        val s = data.toString(); data = StringBuilder()
                        if (s.isBlank()) continue
                        val o = runCatching { JSONObject(s) }.getOrNull() ?: continue
                        if (o.has("id") && o.opt("id").toString() == id.toString() && (o.has("result") || o.has("error"))) return o
                    }
                }
            }
        }
    }

    private fun fetchFirst(urls: List<String>): String? {
        for (u in urls) runCatching {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 8_000; c.readTimeout = 15_000
            if (c.responseCode == 200) return c.inputStream.bufferedReader().use { it.readText() }
        }
        return null
    }

    private const val KEY = "mcp-servers"

    /** 内置精选单。地址都是服务商官方给的；魔搭托管的每个人不一样，要自己复制。 */
    val BUILTIN = listOf(
        McpCatalogItem(
            "amap", "高德地图", "查天气、找地方、路线规划、周边吃喝。她问“附近有什么好吃的”“去机场怎么走”都靠它。", "生活", "key",
            "https://mcp.amap.com/mcp?key={key}", "去高德开放平台申请一个 Web 服务 key（免费）", "https://lbs.amap.com/api/mcp-server/summary",
            listOf("maps_weather", "maps_text_search", "maps_around_search", "maps_direction_transit_integrated", "maps_direction_driving", "maps_geo", "maps_regeocode"),
        ),
        McpCatalogItem(
            "baidu-map", "百度地图", "和高德差不多：地点、路线、天气、周边。用哪个看你习惯。", "生活", "key",
            "https://mcp.map.baidu.com/sse?ak={key}", "去百度地图开放平台申请一个服务端 AK（免费）", "https://lbsyun.baidu.com/faq/api?title=mcpserver/base",
        ),
        McpCatalogItem(
            "12306", "12306 车票", "查车次、余票、经停站。出门前问她一句就行。", "出行", "paste",
            "", "在魔搭 MCP 广场打开「12306-mcp」，点托管，复制你的地址粘贴进来", "https://modelscope.cn/mcp/servers/@Joooook/12306-mcp",
        ),
        McpCatalogItem(
            "bing", "Bing 搜索", "让她能上网查最新的事。中文结果，免费托管。", "搜索", "paste",
            "", "在魔搭 MCP 广场打开「bing-cn-mcp-server」，点托管，复制你的地址粘贴进来", "https://modelscope.cn/mcp/servers/@yan5236/bing-cn-mcp-server",
        ),
        McpCatalogItem(
            "fetch", "网页抓取", "贴一个链接给她，她能读全文、讲重点。", "搜索", "paste",
            "", "在魔搭 MCP 广场打开「fetch」，点托管，复制你的地址粘贴进来", "https://modelscope.cn/mcp/servers/@modelcontextprotocol/fetch",
        ),
        McpCatalogItem(
            "github", "GitHub", "看仓库、issue、PR。写代码的人用。", "开发", "bearer",
            "https://api.githubcopilot.com/mcp/", "在 GitHub 设置里生成一个 Personal access token 填进来", "https://github.com/settings/tokens",
        ),
    )
}
