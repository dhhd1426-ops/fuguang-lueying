package com.fuguang.lueying

import org.json.JSONObject

data class Chapter(
    val id: String,
    val title: String,
    val group: String,
    val text: String,
    val characters: Int,
)

data class Message(val role: String, val text: String)

data class Luoying(val chapters: List<Chapter>, val messages: List<Message>)

data class Letter(val quote: String, val author: String)

object Content {
    fun parseLuoying(raw: String): Luoying {
        val json = JSONObject(raw)
        val chapters = json.getJSONArray("chapters").let { arr ->
            (0 until arr.length()).map { i ->
                val c = arr.getJSONObject(i)
                Chapter(
                    id = c.getString("id"),
                    title = c.getString("title"),
                    group = c.getString("group"),
                    text = c.getString("text"),
                    characters = c.optInt("characters", c.getString("text").length),
                )
            }
        }
        require(chapters.isNotEmpty()) { "掠影没有章节" }
        val messages = json.optJSONArray("messages")?.let { arr ->
            (0 until arr.length()).map { i ->
                val m = arr.getJSONObject(i)
                Message(m.getString("role"), m.getString("text"))
            }
        } ?: emptyList()
        return Luoying(chapters, messages)
    }

    fun parseLetters(raw: String): List<Letter> {
        val arr = JSONObject(raw).getJSONArray("letters")
        val list = (0 until arr.length()).map { i ->
            val l = arr.getJSONObject(i)
            Letter(l.getString("quote"), l.getString("author"))
        }
        require(list.isNotEmpty()) { "没有留言" }
        return list
    }

    val groups = listOf("写作方法", "场景练习", "连续故事")
}
