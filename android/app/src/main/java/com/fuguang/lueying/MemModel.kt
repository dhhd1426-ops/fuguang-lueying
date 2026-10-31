package com.fuguang.lueying

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

/**
 * 记忆模型（第 345 版，「我的 → 助理 → 记忆 → ☰ → 记忆模型」）：记下、每天整理、按周月年收拢，都交给这一个模型。
 * 没选就沿用原来的：能看图的帮手（AttachLook.helper），再没有就用正在聊天的模型。
 * 「记忆系统提示词」是它记下和整理时读的写法，用户能改、能恢复默认。
 */
object MemModel {
    private const val KEY = "mem-model"
    private var prefs: Prefs? = null
    private var loaded = false

    /** 选的供应商和型号；空 = 跟着默认（帮手 → 聊天模型）。 */
    var pid by mutableStateOf("")
    var model by mutableStateOf("")
    /** 启用思考：模型支持时按它的默认档想。 */
    var think by mutableStateOf(false)
    /** 用户改过的写法；空 = 默认。 */
    var prompt by mutableStateOf("")

    val DEFAULT = """
你是她的记忆。看完这段聊天，对照她现在的目录，决定：新开一条、更新某一条（可以改标题，也可以改正文），还是什么都不动。大多数时候什么都不动。

每条记忆有标题和正文。标题一句话，说清这条是关于什么的，以后她只看标题就能判断要不要翻。正文用她的第一人称，他用名字或「他」；内容只写真实发生过、他亲口说过的，不添不猜；段落层次分明，先说是什么，再说什么时候、怎么来的，有结论就落在结论上。时间写具体，年月日加时段。

贴一个类别：关于你、你喜欢的、我学会的、关于我（她自己）、聊过的、答应你的。前四类是人和规矩，不按时间收拢，有变化就原地改；后两类是事，按天收进时间里。每句话都得照着做的那种（比如她是谁），标成常驻。

寒暄、一次性的问题、一起编故事时的话不记；证件号、卡号、密码、验证码、住址、病情永远不记。他亲手写下的，原话不改。
""".trim()

    val text: String get() = prompt.trim().ifEmpty { DEFAULT }
    val custom: Boolean get() = prompt.isNotBlank() && prompt.trim() != DEFAULT

    fun init(p: Prefs) {
        prefs = p
        if (loaded) return
        loaded = true
        runCatching {
            val o = JSONObject(p.string(KEY) ?: return@runCatching)
            pid = o.optString("pid"); model = o.optString("model"); think = o.optBoolean("think"); prompt = o.optString("prompt")
        }
    }

    fun save() {
        prefs?.putString(KEY, JSONObject().put("pid", pid).put("model", model).put("think", think).put("prompt", prompt).toString())
    }

    fun use(id: String, m: String) { pid = id; model = m; save() }

    /** 这会儿真正用哪个：选的配好了就用它，不然帮手，再不然聊天模型。 */
    fun pick(store: AssistantStore): Pair<String, String> {
        if (pid.isNotEmpty() && model.isNotBlank() && store.config(pid).configured) return pid to model
        return AttachLook.helper(store) ?: (store.activeProvider to store.activeModel)
    }

    /** 记忆页上显示的那一行：选的，或者「跟着默认」以及默认现在是谁。 */
    fun label(store: AssistantStore): String {
        val (id, m) = pick(store)
        val name = store.config(id).name
        return if (pid.isNotEmpty() && model.isNotBlank() && store.config(pid).configured) "$name / $m" else "跟着默认 · $name / $m"
    }

    /** 上一次没问成的原因（聊天摘要页里给用户看，第 363 版）。 */
    @Volatile var lastError = ""

    /** 问一次记忆模型；没配好、出错都返回 null，不打扰用户（原因记在 [lastError]）。 */
    suspend fun ask(system: String, user: String, maxTokens: Int = 2000): String? {
        val store = AttachLook.store ?: return null.also { lastError = "App 还没准备好，等一下再试" }
        val (id, m) = pick(store)
        val c = store.config(id)
        if (!c.configured || m.isBlank()) return null.also { lastError = "记忆模型没设置好（${c.name}）" }
        val first = runCatching { ChatApi.complete(provider(id), c, m, system, user, maxTokens, think) }
        // 开了思考被拒：不想再试一次
        val second = if (first.isFailure && think) runCatching { ChatApi.complete(provider(id), c, m, system, user, maxTokens) } else null
        val out = (second ?: first)
        out.exceptionOrNull()?.let { e -> lastError = why(e, c.name, m) }
        return out.getOrNull()
    }

    /** 出错原因翻成人话（第 365 版：402 余额不足、401 Key 不对……）。 */
    fun why(e: Throwable, name: String, m: String): String {
        if (e is java.net.SocketTimeoutException) return "$name / $m 没回应（超时）"
        if (e is java.net.UnknownHostException || e is java.net.ConnectException) return "连不上 $name，看看网络"
        val msg = e.message ?: e.javaClass.simpleName
        val code = Regex("（(\\d{3})）").find(msg)?.groupValues?.get(1)?.toIntOrNull()
        return when (code) {
            402 -> "$name 余额不足，去充值或换个模型"
            401, 403 -> "$name 的 Key 不对或没权限"
            404 -> "$name 没有 $m 这个模型"
            429 -> "$name 请求太频繁，等一会儿再试"
            400 -> "$name / $m 不接受这个请求（400）"
            in 500..599 -> "$name 服务器出错（$code），等一会儿再试"
            else -> msg.replace("记忆整理失败", "$name / $m 出错").take(60)
        }
    }

    /** 不会自己好的错：接着试也一样。 */
    fun fatal(why: String) = "余额不足" in why || "Key 不对" in why || "没设置好" in why || "没有" in why && "模型" in why
}
