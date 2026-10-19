package com.fuguang.lueying

import android.app.Activity
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import java.net.HttpURLConnection
import java.net.URL

/**
 * 复制就认出（方案二）：回到 App、窗口拿到焦点时看一眼剪贴板（安卓 10 起只有这时读得到），
 * 有抖音链接就在聊天页顶上滑下一条长条。同一段文字只提醒一次（记在 prefs `clip-seen`）。
 * 下载：视频交给 [Downloads]（通知栏、下载页里都看得到），解析不出时换 yt-dlp；图集一张张存进 相册 / 浮光掠影。
 */
object ClipLink {
    /** 0 正在认 · 1 认出了 · 2 认不出 · 3 在下 · 4 好了 · 5 没成 */
    var phase by mutableIntStateOf(0)
    var showing by mutableStateOf(false)
    var link by mutableStateOf("")
    var item by mutableStateOf<Douyin.Item?>(null)
    var cover by mutableStateOf<ImageBitmap?>(null)
    var frac by mutableFloatStateOf(0f)
    var note by mutableStateOf("")
    /** 每次没成加一，长条抖一下。 */
    var shake by mutableIntStateOf(0)
    /** 「交给她」：写进聊天输入框（聊天页挂上）。 */
    var give: ((String) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var prefs: Prefs? = null

    fun init(p: Prefs) { prefs = p }

    fun check(act: Activity) {
        val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val text = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(act)?.toString() }.getOrNull() ?: return
        val l = Douyin.link(text) ?: return
        val key = text.hashCode().toString()
        if (prefs?.string("clip-seen") == key) return
        if (showing && phase == 3) return          // 正在下的那条别被打断
        prefs?.putString("clip-seen", key)
        job?.cancel()
        link = l; item = null; cover = null; frac = 0f; note = ""; phase = 0; showing = true
        job = scope.launch {
            runCatching { Douyin.parse(l) }
                .onSuccess { it2 -> item = it2; phase = 1; it2.cover?.let { c -> cover = withContext(Dispatchers.IO) { picture(c) } } }
                .onFailure { phase = 2; note = it.message.orEmpty() }
        }
    }

    fun dismiss() { if (phase != 3) { job?.cancel(); showing = false } else showing = false }

    fun handOver() {
        val i = item
        give?.invoke(if (i != null) "这条抖音「${i.title}」$link\n" else "$link\n")
        showing = false
    }

    fun download(act: Activity?) {
        val i = item
        phase = 3; frac = 0f; note = ""
        job = scope.launch {
            try {
                when {
                    i != null && i.images.isNotEmpty() -> saveImages(act, i)
                    i?.video != null -> viaDownloads(act, i.title, Sniffer.Media("file", i.video, headers = Douyin.headers(), page = i.pageUrl, mime = "video/mp4", name = i.title + ".mp4"), "720p", null)
                        .takeIf { it } ?: viaDownloads(act, i.title, ytdlp(i.pageUrl), "最清楚", "0").also { if (!it) error(note.ifBlank { "没下成" }) }
                    else -> if (!viaDownloads(act, i?.title ?: "抖音视频", ytdlp(i?.pageUrl ?: link), "最清楚", "0")) error(note.ifBlank { "这条认不出，也没下成" })
                }
                frac = 1f; phase = 4
                delay(2600); if (phase == 4) showing = false
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { note = e.message?.take(40) ?: "没下成"; phase = 5; shake++ }
        }
    }

    private fun ytdlp(page: String) = Sniffer.Media("ytdlp", page, headers = Douyin.headers(), page = page)

    /** 交给下载列表，跟着它的进度走；成了返回 true。 */
    private suspend fun viaDownloads(act: Activity?, title: String, m: Sniffer.Media, quality: String, variant: String?): Boolean {
        Downloads.start(act, title, m, quality, variant, audioOnly = false, gallery = true, wifiOnly = false)
        val d = Downloads.list.firstOrNull() ?: return false
        while (true) {
            frac = d.frac
            when (d.state) { 3 -> return true; 4 -> { note = d.error; Downloads.delete(d); return false } }
            if (d !in Downloads.list) { note = "下载被删掉了"; return false }
            delay(150)
        }
    }

    /** 图集：原图一张张存进 相册 / 浮光掠影。 */
    private suspend fun saveImages(act: Activity?, i: Douyin.Item) = withContext(Dispatchers.IO) {
        val cr = (act ?: error("没拿到页面")).applicationContext.contentResolver
        i.images.forEachIndexed { n, u ->
            val bytes = fetch(u) ?: error("第 ${n + 1} 张没拿到")
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, i.title.replace(Regex("[\\\\/:*?\"<>|\\n]"), "_").take(40) + "-${n + 1}.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/浮光掠影")
            }
            val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: error("相册存不进去")
            cr.openOutputStream(uri)?.use { it.write(bytes) }
            frac = (n + 1f) / i.images.size
        }
    }

    private fun fetch(u: String): ByteArray? = runCatching {
        (URL(u).openConnection() as HttpURLConnection).run {
            connectTimeout = 12_000; readTimeout = 20_000
            Douyin.headers().forEach { (k, v) -> setRequestProperty(k, v) }
            try { inputStream.use { it.readBytes() } } finally { disconnect() }
        }
    }.getOrNull()

    private fun picture(u: String): ImageBitmap? = fetch(u)?.let { b ->
        val o = BitmapFactory.Options().apply { inSampleSize = 4 }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)?.asImageBitmap()
    }
}
