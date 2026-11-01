package com.fuguang.lueying

import org.json.JSONObject

/**
 * 千问自带的联网搜索（原型 https://claude.ai/artifact/5ryWctBCsAJGzBfFf5nFyH 第 1 版）。
 * 选择模型的面板里，当前型号支持才长出「千问自带搜索」开关（按型号记，默认关）。
 * 开着：请求带 enable_search（模型自己判断要不要查）+ include_tool_usage（真查了 usage 里才有 x_tools），
 *   App 自己的 web_search 这一轮不给她，免得一个问题查两遍；真查过了，时间线上记一步「用千问自带搜索查过了」。
 * 这个接口不返回查了哪些网页，所以没有来源卡片。带上被拒（400 点名 search），ModelFit 记下，以后不再出开关。
 */
object QwenSearch {
    /** 百炼官方文档（2026-11 查）写了支持联网搜索的型号：Qwen3.5 到 3.8 全系、qwen-max / plus / flash、角色扮演。 */
    private val yes = Regex("^qwen(3\\.[5-9]|-max|-plus|-flash)")
    /** 不搜的：代码、翻译、识字、看图专用；Omni 在这个接口要另一种搜索策略，先不开。 */
    private val no = Regex("coder|-mt|ocr|-vl|omni|audio|tts|asr|embedding")

    fun supports(pid: String, model: String): Boolean {
        if (pid != "qwen") return false
        val m = model.trim().lowercase()
        return yes.containsMatchIn(m) && !no.containsMatchIn(m)
    }

    /** 面板里要不要出开关：支持、而且这个型号没被拒过。 */
    fun offered(pid: String, model: String) = supports(pid, model) && !ModelFit.drops(pid, model, "search")

    fun apply(body: JSONObject) {
        body.put("enable_search", true)
        body.put("include_tool_usage", true)
        // usage 在最后一块里才有，要它才知道真查了没有
        body.put("stream_options", JSONObject().put("include_usage", true))
    }

    /** 流式里带 usage 的那一块：有 x_tools 就是真查了。 */
    fun searched(chunk: JSONObject): Boolean {
        val u = chunk.optJSONObject("usage") ?: return false
        val x = u.opt("x_tools") ?: return false
        return when (x) {
            is JSONObject -> x.length() > 0
            is org.json.JSONArray -> x.length() > 0
            else -> x != JSONObject.NULL
        }
    }

    const val STEP = "qwen_search"

    /** 地域：Key 是哪个地域开的就选哪个，对不上官方直接报 Key 无效。 */
    val REGIONS = listOf(
        "北京" to "https://dashscope.aliyuncs.com/compatible-mode/v1",
        "新加坡" to "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
    )
}
