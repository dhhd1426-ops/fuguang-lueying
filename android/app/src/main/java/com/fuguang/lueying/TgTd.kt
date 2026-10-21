package com.fuguang.lueying

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.TdApi
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 登录后：用 TDLib 看一条消息里有哪些视频、把它下下来。
 * 认链接用 getMessageLinkInfo（公开、私有 t.me/c/…、话题、评论都认）；一组相册按 media_album_id 把同组的都列出来。
 * 频道开了「禁止保存」也照样能下：那只是让客户端藏起保存按钮，你看得到这条，账号就能从服务器取这个文件。
 */
internal object TgTd {
    class Found(val title: String, val who: String, val vids: List<TgVid>, val thumb: ByteArray?, val locked: Boolean)

    suspend fun resolve(l: TgLink): Found {
        val info = try { Td.call(TdApi.GetMessageLinkInfo(l.url)) } catch (e: TgErr) { throw TgFail("这条链接认不出来", "TG · 链接 ${e.msg.take(30)}") }
        val m = info.message ?: throw if (l.private) TgFail("你还不是这个频道的成员，进了频道再下", "TG · 不是成员")
            else TgFail("找不到这条，可能被删了", "TG · 没这条")
        val chat = runCatching { Td.call(TdApi.GetChat(info.chatId)) }.getOrNull()
        val group = if (m.mediaAlbumId != 0L && info.forAlbum && !l.single) album(m) else listOf(m)
        val caption = group.firstNotNullOfOrNull { text(it.content).takeIf { t -> t.isNotBlank() } }.orEmpty()
        val vids = group.mapIndexedNotNull { i, x -> vid(x, i, group.size, caption, l) }
        val thumb = group.firstNotNullOfOrNull { mini(it.content) }
        return Found(caption.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(60), chat?.title.orEmpty(), vids, thumb, group.any { !it.canBeSaved })
    }

    /** 同一组相册：往前往后各看 10 条，挑 media_album_id 一样的，按先后排。 */
    private suspend fun album(m: TdApi.Message): List<TdApi.Message> {
        val h = runCatching { Td.call(TdApi.GetChatHistory(m.chatId, m.id, -10, 21, false)) }.getOrNull() ?: return listOf(m)
        return (h.messages.filter { it.mediaAlbumId == m.mediaAlbumId } + m).distinctBy { it.id }.sortedBy { it.id }
    }

    private fun vid(m: TdApi.Message, i: Int, n: Int, caption: String, l: TgLink): TgVid? {
        val (f, secs, fname) = when (val c = m.content) {
            is TdApi.MessageVideo -> Triple(c.video.video, c.video.duration, c.video.fileName)
            is TdApi.MessageAnimation -> Triple(c.animation.animation, c.animation.duration, c.animation.fileName)
            is TdApi.MessageDocument -> if (c.document.mimeType.startsWith("video/")) Triple(c.document.document, 0, c.document.fileName) else return null
            is TdApi.MessageVideoNote -> Triple(c.videoNote.video, c.videoNote.duration, "")
            else -> return null
        }
        val name = fname.takeIf { it.isNotBlank() && '.' in it } ?: TgWeb.name(caption, l, i, n)
        return TgVid("t${m.id}", name, secs, if (f.size > 0) f.size else f.expectedSize, null, f.id, m.id)
    }

    private fun text(c: TdApi.MessageContent): String = when (c) {
        is TdApi.MessageVideo -> c.caption.text; is TdApi.MessageAnimation -> c.caption.text; is TdApi.MessageDocument -> c.caption.text
        is TdApi.MessageText -> c.text.text; else -> ""
    }

    private fun mini(c: TdApi.MessageContent): ByteArray? = when (c) {
        is TdApi.MessageVideo -> c.video.minithumbnail?.data; is TdApi.MessageAnimation -> c.animation.minithumbnail?.data
        is TdApi.MessageDocument -> c.document.minithumbnail?.data; else -> null
    }

    /**
     * 下载列表里的一个（kind = "tg"，url = 消息链接，variant = 第几个）：重新认一次链接拿文件（重开 App 后 file id 会变），
     * 交给 TDLib 下（断点续传它自己记着），进度写回 [d]。下好返回 TDLib 缓存里的文件；搬走后调 [drop] 删掉缓存。
     */
    suspend fun fetch(d: Dl, url: String, index: Int): Pair<File, Int> {
        if (Td.up() != "ready") throw TgFail("要先登录 Telegram", "TG · 没登录")
        val l = TgLink.find(url) ?: throw TgFail("链接不对", "TG · 链接")
        val v = resolve(l).vids.getOrNull(index) ?: throw TgFail("这条里找不到这个视频了", "TG · 没有第 ${index + 1} 个")
        var t0 = System.currentTimeMillis(); var b0 = -1L
        val f = suspendCancellableCoroutine<TdApi.File> { k ->
            var seen = false
            fun step(f: TdApi.File) {
                val got = f.local.downloadedSize
                d.total = if (f.size > 0) f.size else f.expectedSize; d.done = got
                if (d.total > 0) d.frac = (got.toFloat() / d.total).coerceIn(0f, .99f)
                val t = System.currentTimeMillis()
                if (b0 < 0) { b0 = got; t0 = t } else if (t - t0 >= 1000) { d.speed = (got - b0) * 1000 / (t - t0); b0 = got; t0 = t }
                if (f.local.isDownloadingActive) seen = true
                if (f.local.isDownloadingCompleted) { Td.files.remove(f.id); if (k.isActive) k.resume(f) }
                else if (seen && !f.local.isDownloadingActive && k.isActive) { Td.files.remove(f.id); k.resumeWithException(TgFail("Telegram 那边断了，点继续接着下", "TG · 下载停了")) }
            }
            Td.files[v.fileId] = ::step
            // 暂停：停下 TDLib 这一个（下过的它留着，继续时接着下）
            k.invokeOnCancellation { Td.files.remove(v.fileId); TdKick.stop(v.fileId) }
            TdKick.start(v.fileId) { r -> if (r is Throwable && k.isActive) { Td.files.remove(v.fileId); k.resumeWithException(r) } else if (r is TdApi.File) step(r) }
        }
        d.speed = 0
        return File(f.local.path) to f.id
    }

    suspend fun drop(fileId: Int) { runCatching { Td.call(TdApi.DeleteFile(fileId)) } }
}

/** 发 downloadFile / cancelDownloadFile（优先级最高，不等它下完就回）。单独一个作用域，免得在回调里挂起。 */
internal object TdKick {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun start(id: Int, on: (Any) -> Unit) {
        scope.launch {
            try { on(Td.call(TdApi.DownloadFile(id, 32, 0, 0, false))) } catch (e: TgErr) { on(TgFail("Telegram 不给下：" + e.msg.take(30), "TG · ${e.code}")) }
        }
    }
    fun stop(id: Int) { scope.launch { runCatching { Td.call(TdApi.CancelDownloadFile(id, false)) } } }
}

class TgFail(val why: String, val code: String) : Exception(why)
