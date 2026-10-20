package com.fuguang.lueying

import android.app.Activity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 文件树上的一个点。kids 是 null 表示还没打开过这个文件夹。 */
class PanNode(val f: PanFile, val depth: Int) {
    var kids by mutableStateOf<List<PanNode>?>(null)
    var open by mutableStateOf(false)
    var loading by mutableStateOf(false)
    /** 文件：勾没勾。文件夹：还没打开过时它自己的勾；打开后看孩子。 */
    var on by mutableStateOf(true)
}

/**
 * 一个网盘分享。stage：0 正在看里面 · 1 列出来了 · 2 打不开。save：0 没下 · 1 在下 · 2 下好了 · 3 有没下成的。
 * 长条、聊天卡片、她的工具读写同一份（照抖音 VideoStore 的规矩：唯一事实来源）。
 */
class PanItem(val share: PanShare) {
    val link get() = share.link
    var stage by mutableIntStateOf(0)
    var title by mutableStateOf("")
    var err by mutableStateOf<PanError?>(null)
    val nodes = mutableStateListOf<PanNode>()
    var save by mutableIntStateOf(0)
    var step by mutableStateOf("")
    var frac by mutableFloatStateOf(0f)
    var why by mutableStateOf<PanError?>(null)
    var got by mutableIntStateOf(0)
    var gotBytes by mutableStateOf(0L)
    var doneTick by mutableIntStateOf(0)
    /** 这次下的几个在下载列表里的 id，「在下载里看」用。 */
    val dls = mutableStateListOf<String>()
    /** 在等他去内置浏览器登录。 */
    var waitingLogin by mutableStateOf(false)
    var job: Job? = null
}

object PanStore {
    val items = mutableStateMapOf<String, PanItem>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val opening = HashMap<String, Deferred<Unit>>()
    private var prefs: Prefs? = null

    /** 提取码记在 prefs `pan-codes`（链接 → 提取码），重开 App 后卡片自己能再打开。 */
    fun init(p: Prefs) { prefs = p }
    private fun codes() = runCatching { org.json.JSONObject(prefs?.string("pan-codes") ?: "{}") }.getOrDefault(org.json.JSONObject())
    private fun keep(s: PanShare) {
        if (s.code.isEmpty()) return
        val o = codes(); if (o.optString(s.link) == s.code) return
        o.put(s.link, s.code)
        if (o.length() > 200) o.remove(o.keys().next())
        prefs?.putString("pan-codes", o.toString())
    }

    fun item(link: String?): PanItem? = link?.let { items[it] }
    fun has(link: String) = items.containsKey(link)

    /** 文字里有网盘分享就收下（第一次见就去打开），返回那一个；没有返回 null。 */
    fun offer(text: String): PanItem? {
        val s = Pan.link(text) ?: return null
        val it = items.getOrPut(s.link) { PanItem(s) }
        if (s.code.isNotEmpty() && it.share.code.isEmpty()) it.share.code = s.code
        if (it.share.code.isEmpty()) it.share.code = codes().optString(s.link)
        if (it.stage != 1 && opening[s.link]?.isActive != true) open(it)
        return it
    }

    fun open(it: PanItem): Deferred<Unit> = opening[it.link]?.takeIf { d -> d.isActive } ?: scope.async {
        it.stage = 0; it.err = null
        try {
            val r = it.share.disk.api.open(it.share)
            it.title = r.title.ifBlank { r.files.firstOrNull()?.name ?: "这个分享" }
            it.nodes.clear(); it.nodes.addAll(r.files.map { f -> PanNode(f, 0) })
            it.nodes.firstOrNull { n -> n.f.dir }?.let { n -> if (r.files.size <= 3) expand(it, n) }
            it.stage = 1; keep(it.share)
        } catch (e: PanError) { it.err = e; it.stage = 2 }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { it.err = PanError(PanError.Kind.OTHER, "没打开，换个时间再试", e.javaClass.simpleName); it.stage = 2 }
        Unit
    }.also { d -> opening[it.link] = d }

    /** 换提取码再打开。 */
    fun retry(it: PanItem, code: String? = null) { if (code != null) it.share.code = code.trim(); open(it) }

    fun expand(it: PanItem, n: PanNode) {
        if (!n.f.dir) return
        if (n.kids != null) { n.open = !n.open; return }
        n.loading = true; n.open = true
        scope.launch {
            try {
                n.kids = it.share.disk.api.list(it.share, n.f).map { f -> PanNode(f, n.depth + 1).also { k -> k.on = n.on } }
            } catch (e: PanError) { n.open = false; it.why = e }
            catch (e: Exception) { n.open = false }
            n.loading = false
        }
    }

    /** 勾一个：文件夹连孩子一起。 */
    fun flip(n: PanNode) { val v = state(n) != 1; set(n, v) }
    private fun set(n: PanNode, v: Boolean) { n.on = v; n.kids?.forEach { set(it, v) } }

    /** 0 没勾 · 1 全勾 · 2 勾了一部分。 */
    fun state(n: PanNode): Int {
        val k = n.kids ?: return if (n.on) 1 else 0
        if (k.isEmpty()) return if (n.on) 1 else 0
        val s = k.map { state(it) }
        return if (s.all { it == 1 }) 1 else if (s.all { it == 0 }) 0 else 2
    }

    /** 已经知道的选中文件（没打开的文件夹算不进来）。 */
    fun picked(it: PanItem): List<PanFile> = it.nodes.flatMap { n -> leaves(n) }
    private fun leaves(n: PanNode): List<PanFile> = when {
        !n.f.dir -> if (n.on) listOf(n.f) else emptyList()
        n.kids != null -> n.kids!!.flatMap { k -> leaves(k) }
        else -> emptyList()
    }
    /** 勾着但没打开过的文件夹。 */
    fun pickedDirs(it: PanItem): List<PanFile> = it.nodes.flatMap { closedDirs(it) }
    private fun closedDirs(n: PanNode): List<PanFile> = when {
        !n.f.dir -> emptyList()
        n.kids == null -> if (n.on) listOf(n.f) else emptyList()
        else -> n.kids!!.flatMap { closedDirs(it) }
    }

    /** 摘要：「3 个 · 1.21 GB」「2 个文件 + 1 个文件夹」。 */
    fun summary(it: PanItem): String {
        val fs = picked(it); val ds = pickedDirs(it)
        if (fs.isEmpty() && ds.isEmpty()) return "一个都没选"
        val head = if (fs.isNotEmpty()) "${fs.size} 个 · ${Pan.size(fs.sumOf { f -> f.size.coerceAtLeast(0) })}" else ""
        return listOf(head, if (ds.isNotEmpty()) "${ds.size} 个文件夹" else "").filter { s -> s.isNotEmpty() }.joinToString(" + ")
    }

    /** 他登过这家没有（内置浏览器里的 Cookie）。阿里、123 的登录在网页存储里，看不出来，当作登过、真用时再说。 */
    fun loggedIn(d: Disk): Boolean {
        val c = Pan.cookie(d)
        return when (d) {
            Disk.BAIDU -> "BDUSS" in c
            Disk.P115 -> "UID=" in c
            Disk.QUARK, Disk.UC -> "__pus" in c || "__puus" in c
            else -> true
        }
    }
    fun needLogin(it: PanItem) = it.share.disk.login == 2 && !loggedIn(it.share.disk)

    /** 去内置浏览器登录；登好了（Cookie 出现）自己关掉浏览器回来。 */
    fun login(it: PanItem) {
        val u = it.share.disk.loginUrl ?: return
        it.waitingLogin = true; InBrowser.open(u)
        scope.launch {
            repeat(600) { _ ->
                delay(1000)
                if (InBrowser.url == null) { it.waitingLogin = false; if (it.stage == 2 && it.err?.kind == PanError.Kind.LOGIN) open(it); return@launch }
                if (it.share.disk.login == 2 && loggedIn(it.share.disk) && it.share.disk in setOf(Disk.BAIDU, Disk.P115, Disk.QUARK, Disk.UC)) {
                    delay(600); InBrowser.close(); it.waitingLogin = false
                    if (it.stage == 2) open(it)
                    return@launch
                }
            }
            it.waitingLogin = false
        }
    }

    /** 开始下选中的；她调工具时等这个 Job（最多 90 秒）。 */
    fun download(it: PanItem, act: Activity?): Job {
        it.job?.takeIf { j -> j.isActive }?.let { j -> return j }
        it.save = 1; it.frac = 0f; it.why = null; it.got = 0; it.gotBytes = 0; it.dls.clear()
        return scope.launch { PanRun.run(it, act) }.also { j -> it.job = j }
    }

    fun stop(it: PanItem) { it.job?.cancel(); it.save = 0; it.step = "" }
}
