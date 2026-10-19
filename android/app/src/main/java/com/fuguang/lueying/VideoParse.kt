package com.fuguang.lueying

import org.json.JSONArray
import org.json.JSONObject

/**
 * 她手边的「解析视频」：抖音的分享文字或链接 → 完整文案、作者、时长、封面、去掉水印的视频（图集是原图）、配乐。
 * 解析见 [Douyin]；解析的同时聊天页顶上滑出长条（[ClipLink]），要存就点那里。
 */
object VideoParse {
    const val TOOL = "parse_video"

    val spec = ToolSpec(
        TOOL,
        "解析一条抖音：分享文字或链接都行。拿到完整文案、作者、时长、封面、去掉水印的视频地址（图集是每张原图）、配乐。",
        JSONObject().put("type", "object").put(
            "properties",
            JSONObject().put("text", JSONObject().put("type", "string").put("description", "抖音的分享文字或链接，原样贴进来")),
        ).put("required", JSONArray().put("text")),
    )

    /** 她读到的那段：只用「你…」。 */
    fun system(call: String): String =
        "你手边有一样解析视频的本事（$TOOL）：$call 发来抖音的分享文字或链接，就用它解析，拿到完整的文案、作者、时长、封面和去掉水印的视频，图集是每张原图。" +
            "抖音的链接不用 ${Materials.OPEN} 去开，那样只看得到标题。" +
            "视频的画面和里面说的话你看不到，只有文案里写的才算数，别猜视频里讲了什么；封面和原图可以用 ${Materials.LOOK} 看一眼。" +
            "解析的同时，聊天页顶上会滑下一条，$call 点「下载」就存进相册；他想存时告诉他点那里。做作品要用这条视频或图，把拿到的地址原样写进作品就行。"

    private val said = HashMap<String, String>()
    fun doneTitle(a: JSONObject): String = synchronized(said) { said[a.optString("text").trim()] } ?: "解析了抖音"

    suspend fun run(a: JSONObject): ToolOut {
        val text = a.optString("text").trim()
        val l = Douyin.link(text) ?: return ToolOut("这段文字里没找到抖音链接。现在只认抖音（v.douyin.com、douyin.com、iesdouyin.com）。")
        val i = runCatching { Douyin.parse(l) }.getOrElse { return ToolOut("没解析出来：${it.message}。可以让对方在内置浏览器里打开这条，再点「让她看看这页」。") }
        ClipLink.offer(text)
        synchronized(said) { said[text] = "解析了抖音「${i.title.take(16)}」" + if (i.images.isNotEmpty()) "，${i.images.size} 张原图" else "" }
        return result(i)
    }

    fun result(i: Douyin.Item): ToolOut {
        val page = i.pageUrl
        listOfNotNull(i.cover, i.video, i.music).plus(i.images).forEach { Materials.mark(it, page) }
        val o = JSONObject().put("platform", "抖音").put("title", i.title).put("text", i.desc).put("author", i.author).put("url", page)
        if (i.seconds > 0) o.put("duration", "%d:%02d".format(i.seconds / 60, i.seconds % 60))
        i.cover?.let { o.put("cover", it) }
        i.video?.let { o.put("video", it) }
        if (i.images.isNotEmpty()) o.put("images", JSONArray(i.images))
        i.music?.let { o.put("music", it) }
        return ToolOut(o.toString())
    }
}
