package com.fuguang.lueying

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import com.fuguang.lueying.PanQuark.call
import com.fuguang.lueying.PanQuark.fail
import com.fuguang.lueying.PanQuark.link
import com.fuguang.lueying.PanQuark.site
import com.fuguang.lueying.PanQuark.unescape
import com.fuguang.lueying.PanQuark.withToken

/**
 * 夸克 / UC 的「借道」：存进他网盘根目录的「浮光临时」→ 拿地址 → 删掉那份。
 * 接口照 OpenList drivers/quark_uc（file/sort、file、file/delete、file/download）
 * 和 netdisk-fast-download QkTool（sharepage/save、task、save_as.save_as_top_fids、search_exit）。
 */
internal object PanQuarkSave {
    const val TMP = "浮光临时"

    /** 建「浮光临时」时上锁：一批文件同时借道，别建出两个同名文件夹。 */
    private val lock = Mutex()

    suspend fun borrow(s: PanShare, f: PanFile): Direct {
        val st = site(s)
        val tmp = tmpDir(s)
        val taskId = withToken(s) { stoken ->
            val b = JSONObject()
                .put("fid_list", JSONArray().put(f.id))
                .put("fid_token_list", JSONArray().put(f.extra["token"].orEmpty()))
                .put("to_pdir_fid", tmp)
                .put("pwd_id", s.id).put("stoken", stoken)
                .put("pdir_fid", "0") // 照 QkTool：子文件夹里的文件也传 "0"，靠 fid + fid_token 认文件
                .put("scene", "link").put("pdir_save_all", false)
            call(s, "share/sharepage/save", body = b).optJSONObject("data")?.optString("task_id").orEmpty()
        }
        if (taskId.isEmpty()) throw PanError(PanError.Kind.OTHER, "存进你网盘没成功，过会儿再试", "${st.tag} · save")

        // 轮询转存任务：status 0 等着 · 1 在做 · 2 好了 · 3 失败。最多等 30 秒
        var done: JSONObject? = null
        for (i in 0 until 30) {
            val d = call(s, "task", arrayOf("task_id" to taskId, "retry_index" to i)).optJSONObject("data") ?: JSONObject()
            val status = d.optInt("status", -1)
            if (status == 2) { done = d; break }
            if (status == 3) throw fail(st, d.optInt("err_code", 3), d.optString("message").ifEmpty { "转存失败" })
            delay(1000)
        }
        val saveAs = done?.optJSONObject("save_as")
            ?: throw PanError(PanError.Kind.OTHER, "${st.name}存得太慢了，过会儿再试", "${st.tag} · task")
        val fid = saveAs.optJSONArray("save_as_top_fids")?.optString(0).orEmpty()
        if (fid.isEmpty()) throw PanError(PanError.Kind.OTHER, "存进去了但找不到那份文件，过会儿再试", "${st.tag} · save_as")
        // search_exit：他网盘里本来就有这份，夸克直接复用了他自己的文件——这份不是我们存的，不能删
        val ours = !saveAs.optBoolean("search_exit", false)
        try {
            return link(s, call(s, "file/download", body = JSONObject().put("fids", JSONArray().put(fid))), f)
        } finally {
            if (ours) withContext(NonCancellable) {
                try { remove(s, listOf(fid)) } catch (_: PanError) { } // 删不掉留给 cleanup
            }
        }
    }

    /** 找或建根目录下的「浮光临时」，fid 记在 s.state["tmp"]。 */
    private suspend fun tmpDir(s: PanShare): String = lock.withLock {
        s.state["tmp"]?.let { return@withLock it }
        val st = site(s)
        val fid = findTmp(s) ?: run {
            val b = JSONObject().put("pdir_fid", "0").put("file_name", TMP).put("dir_path", "").put("dir_init_lock", false)
            val made = try {
                call(s, "file", body = b).optJSONObject("data")?.optString("fid").orEmpty()
            } catch (e: PanError) {
                if (e.kind != PanError.Kind.OTHER) throw e
                "" // 同名冲突之类：再找一遍
            }
            made.ifEmpty {
                delay(1000)
                findTmp(s) ?: throw PanError(PanError.Kind.OTHER, "在你网盘里建「$TMP」没成功，过会儿再试", "${st.tag} · mkdir")
            }
        }
        s.state["tmp"] = fid
        fid
    }

    suspend fun findTmp(s: PanShare): String? =
        mine(s, "0", stopAt = TMP).firstOrNull { !it.optBoolean("file", true) && unescape(it.optString("file_name")) == TMP }
            ?.optString("fid")?.takeIf { it.isNotEmpty() }

    /** 列他自己网盘里一个文件夹（file/sort，一页 100 个）。stopAt：翻到同名的就不往下翻了。 */
    suspend fun mine(s: PanShare, pdir: String, stopAt: String? = null): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        var page = 1
        while (true) {
            val j = call(s, "file/sort", arrayOf(
                "pdir_fid" to pdir, "_page" to page, "_size" to 100, "_fetch_total" to 1,
                "fetch_all_file" to 1, "fetch_risk_file_name" to 1, "_sort" to "file_type:asc,file_name:asc",
            ))
            val list = j.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
            for (i in 0 until list.length()) list.optJSONObject(i)?.let { out += it }
            if (stopAt != null && out.any { unescape(it.optString("file_name")) == stopAt }) break
            val total = j.optJSONObject("metadata")?.optInt("_total", 0) ?: 0
            if (list.length() == 0 || page * 100 >= total || page >= 50) break
            page++
        }
        return out
    }

    /** 删他网盘里的文件（进他的回收站）。action_type 照 OpenList 用 1。 */
    suspend fun remove(s: PanShare, fids: List<String>) {
        val arr = JSONArray().also { a -> fids.forEach { a.put(it) } }
        call(s, "file/delete", body = JSONObject().put("action_type", 1).put("filelist", arr).put("exclude_fids", JSONArray()))
    }
}
