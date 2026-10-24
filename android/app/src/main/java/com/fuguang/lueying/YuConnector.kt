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

    /** 此刻还连着吗（同步问一次羽的 status）。羽反向请求时用来把关。 */
    fun connectedNow(): Boolean = call("status", null)?.optBoolean("connected") == true

    /** 出错时给模型看的话；not_connected 时顺便把状态改成未连接。 */
    private suspend fun error(o: JSONObject?): String? {
        if (o == null) return "暂时读不到羽的笔记。"
        return when (o.optString("error")) {
            "" -> null
            "not_connected" -> { set("off", 0); "羽还没有连接，或者已经断开。请告诉他到「我的 → 连接器」里重新连接羽。" }
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
                    .put("tags", n.optJSONArray("tags") ?: JSONArray()).put("updated", date(n.optLong("updated")))
                    // 羽 1.6.4 起：note 笔记 / mindmap 思维导图 / graph 人物关系网
                    .apply { n.optString("kind").takeIf { it.isNotEmpty() }?.let { put("kind", it).put("kindName", n.optString("kindName")) } },
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
        val note = if (pics.isNotEmpty()) "\n（笔记里有 ${refs.size} 张图片，附上了前 ${pics.size} 张；图片已经显示给他看了。）"
        else "\n（笔记里有 ${refs.size} 张图片，已经显示给他看了；你这边看不到图片内容。）"
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
    /** 告诉她：羽是手边的一样东西，看得到、随时能翻；要不要翻、什么时候翻，她自己拿主意。 */
    fun system(): String =
        "他的笔记 App「羽」连在你手边（就在这台手机上，已授权），里面有 $notes 篇笔记和他的待办。" +
            "search_notes 搜笔记，read_note 读一篇的全文和图片，list_todos 看待办。" +
            "笔记分三种（结果里的 kind）：note 普通笔记、mindmap 思维导图、graph 人物关系网。他说「关系网 / 关系图 / 人物关系」时，搜这些词或找 kind=graph 的；说「导图」时找 kind=mindmap 的。" +
            "用不用、什么时候用，由你自己判断：他问到笔记里的东西、让你找什么、或者话题自然走到那里时，再去翻；" +
            "打招呼、闲聊、说心情的时候，就像平常一样回他，不用去翻。" +
            "翻的时候不用说“我去查一下”，看完自然地说就好，提到笔记时说出标题。他问起时，不要说你看不到他的笔记。"

    val tools = listOf(
        ToolSpec(
            "search_notes",
            "在他的笔记 App「羽」里搜索笔记，返回标题、片段、id 和种类 kind（note 笔记 / mindmap 思维导图 / graph 人物关系网）。他问到自己的笔记、记录、笔记里的图片时使用。" +
                "要找人物关系网就搜「关系网」「人物关系」，要找导图就搜「导图」。" +
                "关键词可以有多个，用空格分开；#标签 按标签筛选；空字符串返回最近的笔记。",
            JSONObject().put("type", "object")
                .put("properties", JSONObject().put("query", JSONObject().put("type", "string").put("description", "搜索关键词")))
                .put("required", JSONArray().put("query")),
        ),
        ToolSpec(
            "read_note",
            "读取「羽」里一篇笔记的全文。id 来自 search_notes 的结果。" +
                "人物关系网的正文：第一行「【人物关系网 · N 人 · M 条关系】」，下面每人一行「- 名字（身份 · 职业）：备注」，「## 关系」下每条「- 甲 — 乙：关系」。",
            JSONObject().put("type", "object")
                .put("properties", JSONObject().put("id", JSONObject().put("type", "string").put("description", "笔记 id")))
                .put("required", JSONArray().put("id")),
        ),
        ToolSpec(
            "list_todos",
            "列出他在「羽」里的待办（内容、是否完成、提醒时间、重复规则），未完成的在前。",
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
        return "（手边顺手翻到的羽里的笔记，和他这句话不一定有关。用得上就自然地带出来，提到时说出标题；用不上就当没看到。）\n$r"
    }
}

/** 给模型的一个工具：名字、说明、参数的 JSON Schema。 */
class ToolSpec(val name: String, val description: String, val schema: JSONObject)

/* ---------- 连接器 api 3：羽 → 浮光掠影 ---------- */

/**
 * 羽请助理把一段文字整理成思维导图。同一条连接的反方向：不新建连接、不弹授权，
 * 开关还是羽「设置 › 连接器」里那一个。每次先问羽「我们还连着吗」，断了就拒绝。
 *
 * 羽在后台线程调：ContentResolver.call(content://com.fuguang.lueying.yu, "outline", 文字, extras{"hint"})
 * 返回 Bundle 的 "json"：
 *   {"api":3,"root":"…","items":[{"t":"…","c":[…]}]}
 *   {"error":"not_connected"} / {"error":"no_model"} / {"error":"failed","message":"…"}
 */
class YuProvider : android.content.ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle =
        // 这里抛出去的任何东西，羽那边只看得到「需要更新」：一律接住，把原因写成原因码带回去
        try { serve(method, arg, extras) } catch (t: Throwable) {
            reply(JSONObject().put("error", "failed").put("message", "Vignette 出错了 · FG " + t.javaClass.simpleName + " " + (t.message ?: t.cause?.message ?: "").take(80)))
        }

    private fun serve(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context ?: return reply(JSONObject().put("error", "failed").put("message", "Vignette 还没准备好"))
        if (method != "outline") return reply(JSONObject().put("error", "failed").put("message", "没有这个接口：$method"))
        Yu.init(ctx) // 浮光掠影可能还没打开过，进程是被羽拉起来的
        ModelFit.init(Prefs(ctx)) // 记过的「这个型号不收哪些参数」，没开过 App 时也要读到
        // 两道门：是羽本尊，而且羽那边还连着
        if (!callerIsYu(ctx) || !Yu.connectedNow()) return reply(JSONObject().put("error", "not_connected"))
        val text = arg.orEmpty().trim().take(20_000)
        if (text.isEmpty()) return reply(JSONObject().put("error", "failed").put("message", "没有内容可以整理"))
        val hint = extras?.getString("hint").orEmpty().trim()
        return reply(outline(ctx, text, hint))
    }

    private fun reply(o: JSONObject) = Bundle().apply { putString("json", o.toString()) }

    /** 调用方的 uid 对应包名是 com.yu.notes，且签名是羽的。 */
    private fun callerIsYu(ctx: Context): Boolean {
        val uid = android.os.Binder.getCallingUid()
        val pm = ctx.packageManager
        val pkgs = pm.getPackagesForUid(uid) ?: return false
        if (Yu.PKG !in pkgs) return false
        return runCatching {
            val info = pm.getPackageInfo(Yu.PKG, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
            val signers = info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory } ?: return false
            val md = java.security.MessageDigest.getInstance("SHA-256")
            signers.any { s -> md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) } == YU_SHA256 }
        }.getOrDefault(false)
    }

    /** 用用户当前选的模型整理；同步等，最多 60 秒。 */
    private fun outline(ctx: Context, text: String, hint: String): JSONObject {
        val store = AssistantStore(Prefs(ctx))
        val p = provider(store.activeProvider)
        val c = store.config(p.id)
        if (!c.configured || store.activeModel.isBlank()) return JSONObject().put("error", "no_model")
        val system = "把用户给的文字整理成思维导图。要求：\n" +
            "- 提炼层级清楚的要点，用自己的话概括，不照抄原句\n" +
            "- 每个主题不超过 20 个字，像标题，不要句号\n" +
            "- 最多 4 层（中心主题不算），总共不超过 60 个主题\n" +
            "- 同一层按原文的顺序或逻辑顺序排列\n" +
            (if (hint.isNotEmpty()) "- 中心主题用：$hint\n" else "- 中心主题由你根据内容起，不超过 12 个字\n") +
            "只输出 JSON，不要任何别的文字、不要代码围栏。格式：\n" +
            "{\"root\":\"中心主题\",\"items\":[{\"t\":\"一级主题\",\"c\":[{\"t\":\"二级主题\",\"c\":[]}]}]}"
        val raw = try {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(60_000) { ChatApi.complete(p, c, store.activeModel, system, text, maxTokens = 4096) }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            return JSONObject().put("error", "failed").put("message", "整理超时了，再试一次")
        } catch (e: Throwable) {
            return JSONObject().put("error", "failed").put("message", (e.message?.takeIf { it.isNotBlank() } ?: "模型没有回应") + " · FG " + p.id + " " + store.activeModel)
        }
        val o = parse(raw) ?: return JSONObject().put("error", "failed").put("message", "模型给的结果读不懂，再试一次")
        return o
    }

    /** 从模型输出里抠出 JSON，并按上限整理：层数、数量、字数。 */
    private fun parse(raw: String): JSONObject? {
        var s = raw.trim()
        if (s.startsWith("```")) s = s.removePrefix("```json").removePrefix("```").trim().removeSuffix("```").trim()
        val start = s.indexOf('{'); val end = s.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val o = runCatching { JSONObject(s.substring(start, end + 1)) }.getOrNull() ?: return null
        val root = o.optString("root").trim().take(20).ifEmpty { return null }
        var count = 0
        fun clean(arr: JSONArray?, depth: Int): JSONArray {
            val out = JSONArray()
            if (arr == null || depth > 4) return out
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                val t = it.optString("t").trim().trimEnd('。', '.', '，', ',').take(20)
                if (t.isEmpty() || count >= 60) continue
                count++
                out.put(JSONObject().put("t", t).put("c", clean(it.optJSONArray("c"), depth + 1)))
            }
            return out
        }
        val items = clean(o.optJSONArray("items"), 1)
        if (items.length() == 0) return null
        return JSONObject().put("api", 3).put("root", root).put("items", items)
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): android.database.Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: android.content.ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: android.content.ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0

    companion object {
        /** 羽的签名 SHA-256（羽那边给的）。 */
        const val YU_SHA256 = "57b66d03f6468058c784469c383e99e121af3b7f70d85902a65aa121ac6b08d9"
    }
}
