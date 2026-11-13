package com.fuguang.lueying

import org.json.JSONArray
import org.json.JSONObject

/** Keep recent visual evidence in context; retain every tool ID, timestamp and textual observation. */
internal object ToolImages {
    private const val ARCHIVED = "（较早的工具帧图已归档，需要细看时重新调用视频工具；时间戳和工具记录仍保留。）"
    fun trim(messages: JSONArray, keep: Int) {
        if (keep <= 0) return
        val batches = mutableListOf<JSONArray>()
        for (i in 0 until messages.length()) {
            val message = messages.optJSONObject(i) ?: continue
            val content = message.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val block = content.optJSONObject(j) ?: continue
                if (block.optString("type") == "tool_result") block.optJSONArray("content")?.let { parts ->
                    if (hasImages(parts)) batches += parts
                }
            }
            // OpenAI-compatible APIs receive tool pictures in a separate user message.
            if (message.optString("role") == "user" && content.optJSONObject(0)?.optString("text")
                    ?.startsWith("（这些图片来自刚才的工具结果") == true && hasImages(content)) batches += content
        }
        for (parts in batches.dropLast(keep)) {
            for (i in parts.length() - 1 downTo 0) {
                val type = parts.optJSONObject(i)?.optString("type")
                if (type == "image" || type == "image_url") parts.remove(i)
            }
            parts.put(JSONObject().put("type", "text").put("text", ARCHIVED))
        }
    }

    private fun hasImages(parts: JSONArray): Boolean = (0 until parts.length()).any {
        parts.optJSONObject(it)?.optString("type") in setOf("image", "image_url")
    }
}
