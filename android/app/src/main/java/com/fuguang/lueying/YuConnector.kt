package com.fuguang.lueying

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 连接器「羽」（笔记 App，com.yu.notes）。只读：搜索、读取笔记。
 * 数据走羽提供的 ContentProvider.call，不联网，羽没开着也能用。
 */
object Yu {
    const val PKG = "com.yu.notes"
    const val ACTION_CONNECT = "com.yu.notes.action.CONNECT"
    private val URI: Uri = Uri.parse("content://com.yu.notes.connector")

    /** "missing" 未安装 / "off" 未连接 / "on" 已连接 */
    var state by mutableStateOf("off")
    var notes by mutableIntStateOf(0)
    /** 羽的接口版本：2 起支持读笔记里的图片。 */
    var api by mutableIntStateOf(1)

    private var app: Context? = null
    fun init(ctx: Context) { app = ctx.applicationContext }

    val connected get() = state == "on"

    fun connectIntent(): Intent = Intent(ACTION_CONNECT).setPackage(PKG)

    private fun installed(ctx: Context) = ctx.packageManager.resolveActivity(connectIntent(), 0) != null

    /** 调羽的接口，拿回 json；羽没装或出错返回 null。 */
    private fun call(method: String, arg: String?, extras: Bundle? = null): JSONObject? {
        val ctx = app ?: return null
        return runCatching {
            ctx.contentResolver.call(URI, method, arg, extras)?.getString("json")?.let { JSONObject(it) }
        }.getOrNull()
    }

    /** 重新查一次状态。进「我的」、授权回来、每次发消息前都会查。 */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val ctx = app ?: return@withContext
        if (!installed(ctx)) { set("missing", 0); return@withContext }
        val s = call("status", null)
        if (s != null) withContext(Dispatchers.Main) { api = s.optInt("api", 1) }
        if (s != null && s.optBoolean("connected")) set("on", s.optInt("notes")) else set("off", 0)
    }

    private suspend fun set(st: String, n: Int) = withContext(Dispatchers.Main) { state = st; notes = n }

    /** 出错时给模型看的话；not_connected 时顺便把状态改成未连接。 */
    private suspend fun error(o: JSONObject?): String? {
        if (o == null) return "暂时读不到羽的笔记。"
        return when (o.optString("error")) {
            "" -> null
            "not_connected" -> { set("off", 0); "羽还没有连接，或者已经断开。请告诉用户到「我的 → 连接器」里重新连接羽。" }
            "not_found" -> "没有找到这篇笔记。"
            else -> "读取羽的笔记出错：" + o.optString("error")
        }
    }

    suspend fun search(query: String, limit: Int = 10): String = withContext(Dispatchers.IO) {
        val o = call("search", query.trim(), Bundle().apply { putInt("limit", limit) })
        error(o)?.let { return@withContext it }
        val arr = o!!.optJSONArray("notes") ?: JSONArray()
        if (arr.length() == 0) return@withContext "没有找到相关的笔记。"
        // 原样转给模型，去掉没用的字段
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val n = arr.getJSONObject(i)
            out.put(
                JSONObject().put("id", n.optString("id")).put("title", n.optString("title"))
                    .put("snippet", n.optString("snippet")).put("folder", n.optString("folder"))
                    .put("tags", n.optJSONArray("tags") ?: JSONArray()).put("updated", date(n.optLong("updated"))),
            )
        }
        JSONObject().put("notes", out).toString()
    }

    /**
     * 读一篇笔记。羽支持图片（api ≥ 2）时：图片 id 记进 onImgs 给界面显示；
     * vision = true 时再把前几张缩小后带给模型看。
     */
    suspend fun read(id: String, vision: Boolean, onImgs: suspend (List<String>) -> Unit): ToolOut = withContext(Dispatchers.IO) {
        val o = call("read", id.trim())
        error(o)?.let { return@withContext ToolOut(it) }
        o!!.put("created", date(o.optLong("created"))).put("updated", date(o.optLong("updated")))
        val imgs = o.optJSONArray("images")
        if (api < 2 || imgs == null || imgs.length() == 0) return@withContext ToolOut(o.toString())
        val noteId = o.optString("id").ifBlank { id.trim() }
        val refs = List(imgs.length()) { k -> noteId + "/" + imgs.optJSONObject(k)?.optString("id").orEmpty() }.filter { !it.endsWith("/") }
        withContext(Dispatchers.Main) { onImgs(refs) }
        val pics = if (!vision) emptyList() else refs.take(MAX_VISION).mapNotNull { ref ->
            bytes(ref, 1024)?.let { ToolImage("image/jpeg", android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP)) }
        }
        val note = if (pics.isNotEmpty()) "\n（笔记里有 ${refs.size} 张图片，附上了前 ${pics.size} 张；图片已经显示给用户看了。）"
        else "\n（笔记里有 ${refs.size} 张图片，已经显示给用户看了；你这边看不到图片内容。）"
        ToolOut(o.toString() + note, pics)
    }

    private const val MAX_VISION = 4

    private fun imageUri(ref: String, max: Int): Uri =
        Uri.parse("content://com.yu.notes.connector/image/" + ref.split('/').joinToString("/") { Uri.encode(it) } + "?max=$max")

    /** 从羽读一张图（羽缩放成 JPEG，长边不超过 max）。 */
    private fun bytes(ref: String, max: Int): ByteArray? {
        val ctx = app ?: return null
        return runCatching { ctx.contentResolver.openInputStream(imageUri(ref, max))?.use { it.readBytes() } }.getOrNull()
    }

    private val cache = android.util.LruCache<String, android.graphics.Bitmap>(24)

    /** 给界面用：读一张图成 Bitmap，带缓存。 */
    suspend fun bitmap(ref: String, max: Int): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
        val key = "$ref@$max"
        cache.get(key) ?: bytes(ref, max)?.let { b -> android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size) }?.also { cache.put(key, it) }
    }

    suspend fun todos(includeDone: Boolean): String = withContext(Dispatchers.IO) {
        val o = call("todos", null)
        error(o)?.let { return@withContext it }
        val arr = o!!.optJSONArray("todos") ?: JSONArray()
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            if (!includeDone && t.optBoolean("done")) continue
            out.put(
                JSONObject().put("id", t.optString("id")).put("text", t.optString("text")).put("done", t.optBoolean("done"))
                    .put("remindAt", date(t.optLong("remindAt"))).put("repeat", t.optString("repeat")),
            )
        }
        if (out.length() == 0) return@withContext if (includeDone) "羽里没有待办。" else "羽里没有未完成的待办。"
        JSONObject().put("todos", out).toString()
    }

    private fun date(ms: Long) = if (ms <= 0) "" else
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(ms))

    /* ---------- 给模型的工具 ---------- */

    /** 告诉模型：羽已经连上、本机就能查，提到笔记就一定先查，不要说自己访问不了。 */
    fun system(): String =
        "用户已经把自己的笔记 App「羽」连接给你了（就在这台手机上，已授权，可以直接查），里面有 $notes 篇笔记。" +
            "你有三个工具：search_notes 搜索笔记，read_note 读一篇笔记的全文和图片，list_todos 看待办。" +
            "只要用户提到笔记、记录、写过的东西、笔记里的图片、要做的事或提醒，就是指羽里的内容：先调用工具去查，再回答。" +
            "不要说你无法访问用户的笔记，也不要让用户把内容发给你。" +
            "不知道搜什么关键词时，用空字符串调用 search_notes 看最近的笔记。引用笔记时说出笔记标题。"

    val tools = listOf(
        ToolSpec(
            "search_notes",
            "在用户的笔记 App「羽」里搜索笔记，返回标题、片段和 id。用户问到自己的笔记、记录、笔记里的图片时使用。" +
                "关键词可以有多个，用空格分开；#标签 按标签筛选；空字符串返回最近的笔记。",
            JSONObject().put("type", "object")
                .put("properties", JSONObject().put("query", JSONObject().put("type", "string").put("description", "搜索关键词")))
                .put("required", JSONArray().put("query")),
        ),
        ToolSpec(
            "read_note",
            "读取「羽」里一篇笔记的全文。id 来自 search_notes 的结果。",
            JSONObject().put("type", "object")
                .put("properties", JSONObject().put("id", JSONObject().put("type", "string").put("description", "笔记 id")))
                .put("required", JSONArray().put("id")),
        ),
        ToolSpec(
            "list_todos",
            "列出用户在「羽」里的待办（内容、是否完成、提醒时间、重复规则），未完成的在前。",
            JSONObject().put("type", "object")
                .put("properties", JSONObject().put("include_done", JSONObject().put("type", "boolean").put("description", "是否包含已完成的待办，默认 false"))),
        ),
    )

    suspend fun run(name: String, args: JSONObject, vision: Boolean, onImgs: suspend (List<String>) -> Unit): ToolOut = when (name) {
        "search_notes" -> ToolOut(search(args.optString("query")))
        "read_note" -> read(args.optString("id"), vision, onImgs)
        "list_todos" -> ToolOut(todos(args.optBoolean("include_done", false)))
        else -> ToolOut("没有这个工具：$name")
    }

    /** 能看图的供应商：Claude、OpenAI、Gemini。其他家不一定支持，就只把图显示给用户。 */
    fun vision(providerId: String) = providerId in setOf("claude", "openai", "gemini")

    /** 不支持工具的模型：先用用户的问题搜一次，把结果放进 system 提示。 */
    suspend fun context(question: String): String {
        var r = search(question, 5)
        // 整句话搜不到时，退回给最近的笔记
        if (!r.startsWith("{")) r = search("", 8)
        return "以下是在用户的笔记 App「羽」里按这次问题搜到的笔记（可能不相关，引用时说出笔记标题）：\n$r"
    }
}

/** 给模型的一个工具：名字、说明、参数的 JSON Schema。 */
class ToolSpec(val name: String, val description: String, val schema: JSONObject)
