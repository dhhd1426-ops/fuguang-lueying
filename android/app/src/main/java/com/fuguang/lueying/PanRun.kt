package com.fuguang.lueying

import android.app.Activity
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 真正去下：一个个拿地址（借道的几家在这一步存进「浮光临时」再删），交给下载列表（通知栏、下载页都看得到），
 * 最多两个一起下；进度合起来写回 [PanItem]。没下成的不拖垮别的，最后说清楚几个没成、为什么。
 */
internal object PanRun {
    private const val AT_ONCE = 2

    suspend fun run(it: PanItem, act: Activity?) {
        val s = it.share
        val api = s.disk.api
        val started = ArrayList<Pair<PanFile, Dl>>()
        val failed = ArrayList<Pair<PanFile, PanError>>()
        try {
            if (PanStore.needLogin(it)) throw PanError(PanError.Kind.LOGIN, "${s.disk.label}要先登录一次", "${s.disk.name} · 没登录")
            it.step = "看看要下哪些"
            val files = ArrayList(PanStore.picked(it))
            PanStore.pickedDirs(it).forEach { d -> files += deep(s, d) }
            if (files.isEmpty()) throw PanError(PanError.Kind.OTHER, "一个都没选", "EMPTY")
            val total = files.sumOf { f -> f.size.coerceAtLeast(0) }
            for ((i, f) in files.withIndex()) {
                while (started.count { p -> active(p.second) } >= AT_ONCE) { progress(it, started, total); delay(500) }
                it.step = (if (s.disk.borrow) "借道" else "拿地址") + if (files.size > 1) " ${i + 1}/${files.size}" else ""
                val d = try { api.direct(s, f) } catch (e: PanError) {
                    if (e.kind == PanError.Kind.LOGIN) throw e
                    failed += f to e; continue
                }
                val name = f.name.replace(Regex("[\\\\/:*?\"<>|\\n]"), "_")
                Downloads.start(act, name, Sniffer.Media("pan", d.url, headers = d.headers, page = s.link, mime = Pan.mime(name), size = if (d.size > 0) d.size else f.size, name = name, alts = d.alts),
                    "原文件", null, audioOnly = false, gallery = true, wifiOnly = false)
                val dl = Downloads.list.firstOrNull() ?: continue
                it.dls += dl.id; started += f to dl
                it.step = "下载中"
            }
            while (started.any { p -> active(p.second) }) { progress(it, started, total); delay(400) }
            progress(it, started, total)
            started.filter { p -> p.second.state == 4 }.forEach { p -> failed += p.first to PanError(PanError.Kind.NET, p.second.error.ifBlank { "没下完" }, "DL · ${p.second.error.take(30)}") }
            it.got = started.count { p -> p.second.state == 3 }
            it.gotBytes = started.filter { p -> p.second.state == 3 }.sumOf { p -> p.second.total.coerceAtLeast(0) }
            if (failed.isEmpty()) { it.frac = 1f; it.save = 2; it.doneTick++ }
            else {
                val (f, e) = failed.first()
                it.why = PanError(e.kind, (if (files.size > 1) "${failed.size} 个没下成。「${f.name.take(14)}」：" else "") + e.why, e.code)
                it.save = 3
            }
        } catch (e: PanError) { it.why = e; it.save = 3 }
        catch (e: kotlinx.coroutines.CancellationException) { started.forEach { p -> if (active(p.second)) Downloads.pause(p.second) }; throw e }
        catch (e: Exception) { it.why = PanError(PanError.Kind.OTHER, "没下成，再试一次", e.javaClass.simpleName); it.save = 3 }
        finally {
            it.step = ""
            // 借道存进去的，下完或放弃都清掉
            withContext(NonCancellable) { runCatching { api.cleanup(s) } }
        }
    }

    private fun active(d: Dl) = d.state in setOf(0, 1, 2, 6)

    private fun progress(it: PanItem, started: List<Pair<PanFile, Dl>>, total: Long) {
        val done = started.sumOf { p -> if (p.second.state == 3) p.first.size.coerceAtLeast(p.second.total) else p.second.done }
        val all = if (total > 0) total else started.sumOf { p -> p.second.total.coerceAtLeast(0) }
        if (all > 0) it.frac = (done.toFloat() / all).coerceIn(0f, .99f)
        it.speed = started.filter { p -> active(p.second) }.sumOf { p -> p.second.speed }
    }

    /** 没打开过的文件夹：一层层列出来（最多 500 个文件，免得一下子下一整个网盘）。 */
    private suspend fun deep(s: PanShare, dir: PanFile, out: MutableList<PanFile> = ArrayList()): List<PanFile> {
        for (f in s.disk.api.list(s, dir)) {
            if (out.size >= 500) break
            if (f.dir) deep(s, f, out) else out += f
        }
        return out
    }
}
