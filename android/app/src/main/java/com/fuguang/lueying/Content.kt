package com.fuguang.lueying

import android.content.Context
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
    fun load(context: Context): Luoying {
        val raw = context.assets.open("luoying.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
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
        val messages = json.optJSONArray("messages")?.let { arr ->
            (0 until arr.length()).map { i ->
                val m = arr.getJSONObject(i)
                Message(m.getString("role"), m.getString("text"))
            }
        } ?: emptyList()
        return Luoying(chapters, messages)
    }

    /** 与网站「留言」六封完全一致。 */
    val letters = listOf(
        Letter("不管什么都要尽可能的给到沉浸式，不被打扰", "——林妙妙"),
        Letter("不管什么都要去尝试一下，", "——任沐瞳"),
        Letter("多去看看，不要被眼前的小事磨躁了心", "——王嫣然"),
        Letter("肖宇，你个大笨蛋！", "——母莙慧"),
        Letter(
            "如果爱情是一场考试，那么简薇就是在答了满分后被人撒了墨。想再要一张卷子却没法开口:李小允答得很快且没有答错一道题，但是在快要答宪时，卷子却被设有卷子的乐瑶撕了:而那个满眼都是昭阳的乐瑶,她甚至不需要试卷,连题都能默写下来,但是在她满眼欢欣的等着昭阳给她发卷时。昭阳却打心里觉得她会作弊,没让她进考场,她只能蹲在考场外，在地上写下了满分答案。米彩答卷很慢，很细心，最后在考试结束的铃声响起前交了那张满分答卷。",
            "——匿名",
        ),
        Letter(
            "人在精神上受了伤是无法补回来的，就像《人间失 格》里说的，即使我此刻是快乐的，只要一想到那些瞬间，心情就会立马暗下来！再多的光都照不进去。",
            "——独白",
        ),
    )

    val groups = listOf("写作方法", "场景练习", "连续故事")
}
