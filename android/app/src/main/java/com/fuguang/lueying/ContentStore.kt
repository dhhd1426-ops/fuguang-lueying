package com.fuguang.lueying

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 掠影与留言的内容来源，按顺序取用：
 * 1. 网上最新的（公开仓库 fuguanglueying，经 jsDelivr CDN）
 * 2. 手机里上次下载成功的那份
 * 3. APK 自带的那份
 * 网上那份格式不对时不会覆盖手机里的，所以内容不会变空。
 */
@Stable
class ContentStore(private val context: Context, private val prefs: Prefs) {

    var luoying by mutableStateOf(load(LUOYING, Content::parseLuoying))
        private set
    var letters by mutableStateOf(load(LETTERS, Content::parseLetters))
        private set

    /** 有没看过的新章节 / 新留言时为 true，用来点亮导航上的小红点。 */
    var newLuoying by mutableStateOf(false)
        private set
    var newLetters by mutableStateOf(false)
        private set

    init {
        // 第一次打开时，把现有内容记为已看过，不亮红点
        if (prefs.string(SEEN_CHAPTERS) == null) markLuoyingSeen()
        if (prefs.string(SEEN_LETTERS) == null) markLettersSeen()
        updateDots()
    }

    fun markLuoyingSeen() {
        prefs.putString(SEEN_CHAPTERS, luoying.chapters.joinToString("\n") { it.id })
        updateDots()
    }

    fun markLettersSeen() {
        prefs.putString(SEEN_LETTERS, letters.joinToString("\n") { letterKey(it) })
        updateDots()
    }

    private fun updateDots() {
        val seenChapters = prefs.string(SEEN_CHAPTERS).orEmpty().split("\n").toSet()
        newLuoying = luoying.chapters.any { it.id !in seenChapters }
        val seenLetters = prefs.string(SEEN_LETTERS).orEmpty().split("\n").toSet()
        newLetters = letters.any { letterKey(it) !in seenLetters }
    }

    private fun letterKey(l: Letter) = (l.author + "|" + l.quote).hashCode().toString()

    /** 到网上检查一次；内容没变时服务器只回一个“未修改”，几乎不耗流量。 */
    suspend fun refresh() {
        fetch(LUOYING)?.let { (raw, etag) ->
            runCatching { Content.parseLuoying(raw) }.onSuccess {
                save(LUOYING, raw, etag)
                luoying = it
            }
        }
        fetch(LETTERS)?.let { (raw, etag) ->
            runCatching { Content.parseLetters(raw) }.onSuccess {
                save(LETTERS, raw, etag)
                letters = it
            }
        }
        updateDots()
    }

    private fun <T> load(name: String, parse: (String) -> T): T {
        val cached = cacheFile(name)
        if (cached.exists()) {
            runCatching { return parse(cached.readText()) }
            cached.delete()
            prefs.putString(etagKey(name), null)
        }
        val bundled = context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
        return parse(bundled)
    }

    /** 只有解析成功的内容才落盘并记下 ETag。 */
    private fun save(name: String, raw: String, etag: String?) {
        val f = cacheFile(name)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "$name.tmp")
        tmp.writeText(raw)
        if (tmp.renameTo(f)) prefs.putString(etagKey(name), etag)
    }

    private fun cacheFile(name: String) = File(context.filesDir, "content/$name")
    private fun etagKey(name: String) = "content-etag-$name"

    /** 依次尝试各个 CDN 节点；返回新内容和它的 ETag，没有变化或都连不上时返回 null。 */
    private suspend fun fetch(name: String): Pair<String, String?>? = withContext(Dispatchers.IO) {
        for (host in MIRRORS) {
            val conn = runCatching { URL("$host/content/$name").openConnection() as HttpURLConnection }.getOrNull() ?: continue
            try {
                conn.connectTimeout = 6_000
                conn.readTimeout = 20_000
                conn.useCaches = false
                val etag = prefs.string(etagKey(name))
                if (etag != null && cacheFile(name).exists()) conn.setRequestProperty("If-None-Match", etag)
                when (conn.responseCode) {
                    HttpURLConnection.HTTP_NOT_MODIFIED -> return@withContext null
                    HttpURLConnection.HTTP_OK -> {
                        val raw = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        return@withContext raw to conn.getHeaderField("ETag")
                    }
                    else -> Unit
                }
            } catch (_: Exception) {
                // 换下一个节点
            } finally {
                conn.disconnect()
            }
        }
        null
    }

    companion object {
        const val LUOYING = "luoying.json"
        const val LETTERS = "letters.json"
        private const val SEEN_CHAPTERS = "seen-chapters"
        private const val SEEN_LETTERS = "seen-letters"
        private const val REPO = "gh/dhhd1426-ops/fuguanglueying@main"

        /** 同一份内容的几个 jsDelivr 节点，国内哪个通用哪个。 */
        val MIRRORS = listOf(
            "https://cdn.jsdelivr.net/$REPO",
            "https://fastly.jsdelivr.net/$REPO",
            "https://gcore.jsdelivr.net/$REPO",
        )
    }
}
