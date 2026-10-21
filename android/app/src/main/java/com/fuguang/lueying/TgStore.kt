package com.fuguang.lueying

import android.app.Activity
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 一条里的一个视频。web：不登录就能下的地址（网页里拿的）；fileId：登录后 TDLib 里的文件。 */
class TgVid(val key: String, val name: String, val seconds: Int, val size: Long, val web: String?, val fileId: Int = 0, val msgId: Long = 0, val thumb: String? = null) {
    var on by mutableStateOf(true)
}

/**
 * 一条 Telegram 链接。stage：0 正在认 · 1 认出来了 · 2 认不出。save：0 没下 · 1 在下 · 2 下好了 · 3 没下成。
 * 长条、聊天卡片读写同一份（和网盘 PanStore 一个规矩）。
 */
class TgItem(val l: TgLink) {
    val link get() = l.url
    var stage by mutableIntStateOf(0)
    var title by mutableStateOf("")
    var who by mutableStateOf("")
    var cover by mutableStateOf<ImageBitmap?>(null)
    val vids = mutableStateListOf<TgVid>()
    /** 网页拿不到、要登录 Telegram 才能下。 */
    var needLogin by mutableStateOf(false)
    var why by mutableStateOf("")
    var code by mutableStateOf("")
    var save by mutableIntStateOf(0)
    var frac by mutableFloatStateOf(0f)
    var speed by mutableLongStateOf(0L)
    var doneTick by mutableIntStateOf(0)
    val dls = mutableStateListOf<String>()
    var job: Job? = null
}

object TgStore {
    val items = mutableStateMapOf<String, TgItem>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun item(link: String?): TgItem? = link?.let { items[it] }

    /** 文字里有 Telegram 消息链接就收下（第一次见就去认），返回那一条。 */
    fun offer(text: String): TgItem? {
        val l = TgLink.find(text) ?: return null
        items[l.url]?.let { return it }
        val it = TgItem(l); items[l.url] = it
        open(it)
        return it
    }

    /** 先看网页（公开频道、不用登录）；网页给不了的，登录过就用 TDLib 认，没登录就标「要登录」。 */
    fun open(it: TgItem) = scope.launch {
        it.stage = 0; it.why = ""; it.code = ""
        val web = if (it.l.private) null else runCatching { TgWeb.read(it.l) }.getOrNull()
        if (web != null && web.vids.isNotEmpty()) {
            it.title = web.title.ifBlank { web.who }; it.who = "@" + it.l.chan
            it.vids.clear(); it.vids += web.vids
            web.vids.firstNotNullOfOrNull { v -> v.thumb }?.let { u -> launch { it.cover = picture(u) } }
        }
        if (web != null && !web.need) { it.needLogin = false; it.stage = 1; return@launch }
        // 网页不行：登录过就用 TDLib
        if (Td.configured && Td.up() == "ready") { viaTd(it); return@launch }
        it.needLogin = true
        it.code = web?.why ?: if (it.l.private) "TG · 私有频道" else "TG · 网页打不开"
        it.stage = 1
        if (it.title.isBlank()) it.title = if (it.l.private) "私有频道里的一条" else "@" + it.l.chan + " 的一条"
    }

    private suspend fun viaTd(it: TgItem) {
        try {
            val f = TgTd.resolve(it.l)
            if (f.vids.isEmpty()) throw TgFail("这条里没有视频", "TG · 没有视频")
            it.title = f.title.ifBlank { f.who.ifBlank { it.title } }
            it.who = f.who.ifBlank { "@" + it.l.chan }
            // 网页已经给了地址的保留（走 CDN，更快），其余换成 TDLib 的
            val web = it.vids.toList()
            it.vids.clear()
            it.vids += f.vids.mapIndexed { i, v -> web.getOrNull(i)?.web?.let { u -> TgVid(v.key, v.name, v.seconds, v.size, u, v.fileId, v.msgId) } ?: v }
            f.thumb?.let { b -> if (it.cover == null) it.cover = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() } }
            it.needLogin = false; it.stage = 1
        } catch (e: TgFail) { it.why = e.why; it.code = e.code; it.stage = 2 }
        catch (e: Exception) { it.why = "没认出来，再试一次"; it.code = "TG · ${e.javaClass.simpleName}"; it.stage = 2 }
    }

    /** 登录好了：没认全的那几条重新认；长条上那条认好就接着下（好几个视频的展开勾选）。 */
    fun loggedIn(focus: TgItem?, act: Activity?) {
        items.values.filter { it.needLogin || it.stage == 2 }.forEach { x ->
            val j = open(x)
            if (x === focus) scope.launch {
                j.join()
                if (x.stage == 1 && !x.needLogin) { if (x.vids.size > 1) TgPill.open = "pick" else if (x.vids.size == 1) download(x, act) }
            }
        }
    }

    /** 存下选中的：网页地址走分块多路下（和网盘一样），其余交给 TDLib。都进下载列表，通知栏看得到。 */
    fun download(it: TgItem, act: Activity?): Job {
        it.job?.takeIf { j -> j.isActive }?.let { j -> return j }
        it.save = 1; it.frac = 0f; it.why = ""; it.dls.clear()
        return scope.launch {
            val picked = it.vids.withIndex().filter { (_, v) -> v.on }
            if (picked.isEmpty()) { it.why = "一个都没选"; it.save = 3; return@launch }
            val started = ArrayList<Dl>()
            for ((i, v) in picked) {
                val m = if (v.web != null) Sniffer.Media("pan", v.web, headers = mapOf("Referer" to "https://t.me/"), page = it.link, mime = "video/mp4", size = v.size, name = v.name)
                    else Sniffer.Media("tg", it.link, page = it.link, mime = Pan.mime(v.name), size = v.size, name = v.name)
                Downloads.start(act, v.name, m, "原文件", i.toString(), audioOnly = false, gallery = true, wifiOnly = false)
                Downloads.list.firstOrNull()?.let { d -> started += d; it.dls += d.id }
            }
            while (started.any { d -> d.state in setOf(0, 1, 2, 6) }) {
                val all = started.sumOf { d -> d.total.coerceAtLeast(0) }
                if (all > 0) it.frac = (started.sumOf { d -> if (d.state == 3) d.total else d.done }.toFloat() / all).coerceIn(0f, .99f)
                it.speed = started.sumOf { d -> d.speed }
                delay(400)
            }
            it.speed = 0
            val bad = started.filter { d -> d.state != 3 }
            if (bad.isEmpty()) { it.frac = 1f; it.save = 2; it.doneTick++ }
            else { it.why = (if (started.size > 1) "${bad.size} 个没下成：" else "") + bad.first().error.ifBlank { "没下完" }; it.save = 3 }
        }.also { j -> it.job = j }
    }

    fun stop(it: TgItem) { it.job?.cancel(); it.dls.mapNotNull { id -> Downloads.list.firstOrNull { d -> d.id == id } }.forEach { d -> Downloads.pause(d) }; it.save = 0 }

    private suspend fun picture(u: String): ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching { java.net.URL(u).openStream().use { s -> BitmapFactory.decodeStream(s)?.asImageBitmap() } }.getOrNull()
    }
}
