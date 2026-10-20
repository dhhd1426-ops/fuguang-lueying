package com.fuguang.lueying

import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * 方案三的另一半：她手边的 open_pan（看里面有什么）、get_pan（替他下）。都读写 [PanStore]，
 * 聊天里那张卡和她用的是同一份：她勾了哪些，卡上就勾着哪些；她下的进度卡上看得到。
 */
object PanTools {
    const val OPEN = "open_pan"
    const val GET = "get_pan"

    val specs = listOf(
        ToolSpec(
            OPEN,
            "打开一个网盘分享链接（百度、115、夸克、UC、阿里、123、蓝奏、移动云盘），看里面有哪些文件夹和文件、多大。聊天里会落一张卡片，他能在卡上自己勾、自己下。",
            JSONObject().put("type", "object").put("properties", JSONObject()
                .put("text", JSONObject().put("type", "string").put("description", "分享链接，连同提取码那段文字原样贴进来"))
                .put("folder", JSONObject().put("type", "string").put("description", "要往里看的文件夹路径，如「第一章」；不写就是最外层")),
            ).put("required", JSONArray().put("text")),
        ),
        ToolSpec(
            GET,
            "把网盘分享里的文件直接下到他手机上，不用存进他的网盘。视频声音进相册「浮光掠影」，别的进 下载/浮光掠影。最多等 90 秒，没下完卡上接着走。",
            JSONObject().put("type", "object").put("properties", JSONObject()
                .put("text", JSONObject().put("type", "string").put("description", "分享链接（和提取码）"))
                .put("pick", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))
                    .put("description", "要下的文件或文件夹，写名字或路径（open_pan 给的 path）；不写就是全部")),
            ).put("required", JSONArray().put("text")),
        ),
    )

    /** 她读到的那段：只用「你…」。 */
    fun system(call: String): String =
        "你能打开网盘分享链接，直接把里面的东西下到${call}手机上，不用他先存进自己的网盘。$call 发来网盘链接，用 $OPEN 看里面有什么，聊天里会落一张卡，文件列在卡上，你不用在回复里一个个念，挑要紧的说两句就行。" +
            "他说要哪些、或者说「都下了」，用 $GET 下；他也可以自己在卡上勾、点下载。没说要干什么，就告诉他里面大概是什么、问要不要下。" +
            "结果里 need_login 为真，说一句要先在卡上点「去登录」登一次那家网盘；borrow 为真的几家会先临时放进他网盘里的「浮光临时」再删掉，提一句就好。" +
            "打不开时照结果里的原因说，提取码不对就问他要提取码。说现在卡上什么样，照工具结果里的 ui 说，不凭空描述。"

    private val said = HashMap<String, String>()
    fun doneTitle(n: String, a: JSONObject): String = synchronized(said) { said[n + a.optString("text").trim()] } ?: if (n == GET) "下了网盘里的文件" else "看了网盘分享"

    suspend fun run(n: String, a: JSONObject, onCard: (String) -> Unit): ToolOut {
        val text = a.optString("text").trim()
        val it = PanStore.offer(text) ?: return ToolOut("这段文字里没认出网盘分享链接。认得：百度网盘、115、夸克、UC、阿里云盘、123 云盘、蓝奏云、移动云盘。")
        onCard(it.link)
        PanStore.open(it).await()
        if (it.stage == 2) return ToolOut(JSONObject().put("result", "打不开：" + (it.err?.why ?: "")).put("reason", it.err?.code ?: "").put("ui", ui(it)).toString())
        if (n == OPEN) {
            val folder = a.optString("folder").trim().trim('/')
            val nodes = if (folder.isEmpty()) it.nodes.toList() else find(it, folder)?.let { d -> open(it, d); d.kids.orEmpty() } ?: return ToolOut("没找到文件夹「$folder」")
            synchronized(said) { said[n + text] = "看了${it.share.disk.label}「${it.title.take(14)}」" }
            return ToolOut(JSONObject().put("disk", it.share.disk.label).put("title", it.title)
                .put("files", JSONArray(nodes.take(80).map { d -> JSONObject().put("path", d.f.path).put("dir", d.f.dir).apply { if (!d.f.dir) put("size", Pan.size(d.f.size)) } }))
                .put("more", (nodes.size - 80).coerceAtLeast(0))
                .put("need_login", PanStore.needLogin(it)).put("borrow", it.share.disk.borrow).put("ui", ui(it)).toString())
        }
        // 下：照她给的名字勾上（不写就全部）
        val pick = a.optJSONArray("pick")?.let { arr -> (0 until arr.length()).map { i -> arr.optString(i).trim().trim('/') }.filter { s -> s.isNotEmpty() } }.orEmpty()
        if (pick.isNotEmpty()) {
            it.nodes.forEach { d -> mark(d, false) }
            val miss = pick.filter { p -> (find(it, p) ?: findDeep(it, p))?.let { d -> mark(d, true) } == null }
            if (miss.size == pick.size) return ToolOut("没找到这些：${miss.joinToString("、")}。先用 $OPEN 看看里面的名字。")
        } else it.nodes.forEach { d -> mark(d, true) }
        if (PanStore.needLogin(it)) return ToolOut(JSONObject().put("result", "${it.share.disk.label}要先登录一次，卡上有「去登录」").put("ui", ui(it)).toString())
        val job = PanStore.download(it, null)
        withTimeoutOrNull(90_000) { job.join() }
        synchronized(said) { said[n + text] = if (it.save == 2) "下好了「${it.title.take(14)}」" else if (it.save == 1) "开始下「${it.title.take(14)}」" else "没下成「${it.title.take(14)}」" }
        val r = when (it.save) {
            2 -> "下好了 ${it.got} 个，视频进了相册「浮光掠影」，别的在 下载/浮光掠影"
            1 -> "还在下，${(it.frac * 100).toInt()}%，卡上看得到进度"
            else -> "没下成：" + (it.why?.why ?: "")
        }
        return ToolOut(JSONObject().put("result", r).put("ui", ui(it)).toString())
    }

    fun ui(it: PanItem): String = when {
        it.stage == 2 -> "卡上写着打不开：${it.err?.why}"
        it.save == 2 -> "卡上画了勾，写着下好了，有「打开」「在下载里看」"
        it.save == 1 -> "卡上正在下，${(it.frac * 100).toInt()}%"
        it.save == 3 -> "卡上写着没下成：${it.why?.why}"
        PanStore.needLogin(it) -> "卡上有一行「去登录」，按钮写着先登录"
        else -> "卡片在聊天里，${PanStore.summary(it)}，还没下"
    }

    private fun mark(n: PanNode, v: Boolean): Unit { n.on = v; n.kids?.forEach { k -> mark(k, v) } }

    /** 按路径一层层找（只在已经打开过的里面找，没打开的文件夹顺路打开）。 */
    private suspend fun find(it: PanItem, path: String): PanNode? {
        var level: List<PanNode> = it.nodes
        var hit: PanNode? = null
        for (part in path.split('/').filter { s -> s.isNotEmpty() }) {
            hit = level.firstOrNull { d -> d.f.name == part } ?: level.firstOrNull { d -> d.f.name.contains(part) } ?: return null
            if (hit.f.dir) { open(it, hit); level = hit.kids.orEmpty() }
        }
        return hit
    }

    /** 只给了名字：在已经列出来的里面整棵树找。 */
    private fun findDeep(it: PanItem, name: String): PanNode? {
        fun go(ns: List<PanNode>): PanNode? { for (d in ns) { if (d.f.name == name || d.f.path == name) return d; d.kids?.let { k -> go(k) }?.let { r -> return r } }; return null }
        return go(it.nodes)
    }

    private suspend fun open(it: PanItem, d: PanNode) {
        if (!d.f.dir || d.kids != null) return
        d.kids = runCatching { it.share.disk.api.list(it.share, d.f) }.getOrNull()?.map { f -> PanNode(f, d.depth + 1).also { k -> k.on = d.on } }
    }
}
