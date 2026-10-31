package com.fuguang.lueying

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

/**
 * 摘要模型（第 365 版，原型 https://claude.ai/artifact/N1rGviN3HUFX2VdB5yj8qx 第 3 版，聊天摘要页右上 ☰）：
 * - 自动写摘要：默认关。关着时聊完不自动写、翻旧聊天时也不在后台补；摘要页里「补写」「重试」照样能用。
 * - 摘要模型：空 = 跟着记忆模型（MemModel）。
 * - 摘要提示词：写摘要时读的写法，能改、能恢复默认。
 */
object GistModel {
    private const val KEY = "gist-model"
    private var prefs: Prefs? = null
    private var loaded = false

    var auto by mutableStateOf(false)
    var pid by mutableStateOf("")
    var model by mutableStateOf("")
    var prompt by mutableStateOf("")

    val DEFAULT = """
你在给她和他的一段聊天写摘要。摘要只给她自己看：以后他问起以前聊过的事，她先看摘要找到是哪段、聊过什么。
- 只写聊天里真的说过、发生过的，不添不猜，不评价，不写感受。
- 细节写全：人名、地名、店名、品牌、型号、颜色、尺码、价格、数量、日期时间；谁推荐了什么、为什么；他选了什么、定下了什么；答应了还没办的事。以后会被问到的，宁可多写。
- 按话题分段：每个话题先单独一行写「【话题（年月日）】」，下面一段段写细节；时间都写具体日期，不写「今天」「上周」。还没办完的事写在那个话题最后，单独一行，以「还没办完：」开头。
- 原来摘要里的内容都留着；被后来的话改掉的，写成最新的，并注一句原来是什么。
- 寒暄不写。不超过 900 字。
""".trim()

    val text: String get() = prompt.trim().ifEmpty { DEFAULT }
    val custom: Boolean get() = prompt.isNotBlank() && prompt.trim() != DEFAULT

    fun init(p: Prefs) {
        prefs = p
        if (loaded) return
        loaded = true
        runCatching {
            val o = JSONObject(p.string(KEY) ?: return@runCatching)
            auto = o.optBoolean("auto"); pid = o.optString("pid"); model = o.optString("model"); prompt = o.optString("prompt")
        }
    }

    fun save() {
        prefs?.putString(KEY, JSONObject().put("auto", auto).put("pid", pid).put("model", model).put("prompt", prompt).toString())
    }

    fun use(id: String, m: String) { pid = id; model = m; save() }

    private fun own(store: AssistantStore) = pid.isNotEmpty() && model.isNotBlank() && store.config(pid).configured

    /** 菜单里那行小字：选的，或者「跟着记忆模型」。 */
    fun label(store: AssistantStore?): String =
        if (store != null && own(store)) "${store.config(pid).name} / $model" else "跟着记忆模型"

    /** 问一次摘要模型：自己选了就用自己的，不然交给记忆模型（失败原因都记在 MemModel.lastError）。 */
    suspend fun ask(system: String, user: String, maxTokens: Int): String? {
        val store = AttachLook.store ?: return null.also { MemModel.lastError = "App 还没准备好，等一下再试" }
        if (!own(store)) return MemModel.ask(system, user, maxTokens)
        val c = store.config(pid)
        val r = runCatching { ChatApi.complete(provider(pid), c, model, system, user, maxTokens) }
        r.exceptionOrNull()?.let { MemModel.lastError = MemModel.why(it, c.name, model) }
        return r.getOrNull()
    }
}
