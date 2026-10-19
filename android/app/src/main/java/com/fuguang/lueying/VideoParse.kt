package com.fuguang.lueying

import org.json.JSONArray
import org.json.JSONObject

/**
 * 她手边的两样：parse_video（看）、save_video（做）。都读写 [VideoStore]：
 * 解析完聊天里落一张卡（她这条回复上；这条对话里已经有了就不重复落），她存的时候卡上走同一套进度。
 * 工具结果里写明卡片现在什么状态（ui），她照实说，不凭空描述界面。
 */
object VideoParse {
    const val TOOL = "parse_video"
    const val SAVE = "save_video"

    val specs = listOf(
        ToolSpec(
            TOOL,
            "解析一条抖音：分享文字或链接都行。拿到完整文案、作者、时长；聊天里会落一张卡片。同一条只解析一次。",
            JSONObject().put("type", "object").put(
                "properties", JSONObject().put("text", JSONObject().put("type", "string").put("description", "抖音的分享文字或链接，原样贴进来")),
            ).put("required", JSONArray().put("text")),
        ),
        ToolSpec(
            SAVE,
            "把一条抖音存到对方手机相册（浮光掠影文件夹），存完才返回。pick：video 视频（图集是全部原图）、audio 只要声音、cover 封面。",
            JSONObject().put("type", "object").put(
                "properties", JSONObject()
                    .put("text", JSONObject().put("type", "string").put("description", "抖音的分享文字或链接"))
                    .put("pick", JSONObject().put("type", "string").put("enum", JSONArray().put("video").put("audio").put("cover")).put("description", "存哪样，不写就是 video")),
            ).put("required", JSONArray().put("text")),
        ),
    )

    /** 她读到的那段：只用「你…」。 */
    fun system(call: String): String =
        "你手边有解析视频的本事。$call 发来抖音的分享文字或链接，用 $TOOL 解析，拿到完整文案、作者、时长；解析完聊天里会有一张卡，封面、标题、作者都在卡上，回复里不用再把标题作者时长文案逻一遍。抖音链接不用 ${Materials.OPEN}。" +
            "$call 让你存、让你把视频发他、只要声音，就用 $SAVE 存到相册，存完再回话：说存在相册的浮光掠影里、卡上能点开。他也可以自己点卡上的「存到相册」。" +
            "$call 只发了一条链接没说要干什么，那就是让你看看这是什么：说两句这条讲什么，问一句要不要存。不要自己决定去做作品、找素材。" +
            "视频的画面和里面说的话你看不到，只有文案里写的才算数，别猜视频里讲了什么；封面和原图可以用 ${Materials.LOOK} 看一眼（地址在 materials 里）。" +
            "一条链接只解析一次；$call 说「没有」「重新发」，先看工具结果里的 ui，照实说卡在哪、存没存、存在哪，不要再解析一遍。"

    private val said = HashMap<String, String>()
    fun doneTitle(n: String, a: JSONObject): String = synchronized(said) { said[n + a.optString("text").trim()] } ?: if (n == SAVE) "存了这条抖音" else "解析了抖音"

    /** onCard：这条对话里还没有这条的卡，就挂到她这条回复上。 */
    suspend fun run(n: String, a: JSONObject, onCard: (String) -> Unit): ToolOut {
        val text = a.optString("text").trim()
        val c = VideoStore.offer(text) ?: return ToolOut("这段文字里没找到抖音链接。现在只认抖音（v.douyin.com、douyin.com、iesdouyin.com）。")
        onCard(c.link)
        VideoStore.ready(c)
        if (n == SAVE) {
            val pick = when (a.optString("pick")) { "audio" -> 1; "cover" -> 2; else -> 0 }
            val r = VideoStore.saveAndWait(c, null, pick)
            synchronized(said) { said[n + text] = if (c.save == 2) "存好了「${c.title.take(16)}」" else "没存成「${c.title.take(16)}」" }
            return ToolOut(JSONObject().put("result", r).put("ui", ui(c)).toString())
        }
        val i = c.item ?: return ToolOut(JSONObject().put("result", "没解析出来：${c.why}").put("ui", "聊天里有一张卡，写着认不出；卡上的「试试存」会换个办法下载").toString())
        synchronized(said) { said[n + text] = "解析了抖音「${i.title.take(16)}」" + if (i.images.isNotEmpty()) "，${i.images.size} 张原图" else "" }
        return result(c, i)
    }

    fun ui(c: Clip): String = when (c.save) {
        2 -> "已经存在相册的浮光掠影里，卡上点「打开」能看"
        1 -> "卡上正在存，${(c.frac * 100).toInt()}%"
        3 -> "卡上显示没存成：${c.saveWhy}"
        else -> "卡片在聊天里，还没存"
    }

    fun result(c: Clip, i: Douyin.Item): ToolOut {
        val page = i.pageUrl
        listOfNotNull(i.cover, i.video, i.music).plus(i.images).forEach { Materials.mark(it, page) }
        val o = JSONObject().put("platform", "抖音").put("kind", if (i.images.isNotEmpty()) "图集" else "视频")
            .put("title", i.title).put("text", i.desc).put("author", i.author)
        if (i.seconds > 0) o.put("duration", "%d:%02d".format(i.seconds / 60, i.seconds % 60))
        if (i.images.isNotEmpty()) o.put("images", i.images.size)
        o.put("materials", JSONArray(listOfNotNull(i.cover) + i.images.take(9)))
        o.put("ui", ui(c))
        return ToolOut(o.toString())
    }
}
