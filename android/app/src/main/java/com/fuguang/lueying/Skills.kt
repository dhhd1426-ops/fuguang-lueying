package com.fuguang.lueying

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 一个技能（照 Claude 的 Agent Skills：GitHub 上一个文件夹，里面一份 SKILL.md + 参考文件）。
 * title：显示的名字（她起的中文名，没有就用 SKILL.md 的 name）；desc：SKILL.md 的 description；when：她写的「什么时候用」。
 */
data class Skill(
    val id: String, val title: String, val desc: String, val whenUse: String, val repo: String, val path: String,
    val by: String, val at: Long, val used: Int = 0, val on: Boolean = true, val files: Int = 1, val script: Boolean = false,
) {
    val shown: String get() = whenUse.ifBlank { desc }
}

/** 她要学一个技能时，在聊天里问你一声（方案二：先问你）。answer 是你点的结果。 */
class SkillAsk(val cand: SkillCand, val title: String, val why: String) {
    val answer = CompletableDeferred<Boolean>()
    /** 装完了：null 是装好了，否则是没装上的原因。卡片等它来画 ✓ 或写原因。 */
    val installed = CompletableDeferred<String?>()
}

/**
 * 技能（「我的 → 功能 → 技能」）。每位助理各自一份，说明文件存在 files/skills/<助理>/<技能>/，跟着备份走；索引存 prefs `skills`。
 * 平时她只看到每个技能的名字和一句「什么时候用」，要用时 open_skill 翻开整份说明（照 Claude 的渐进披露）。
 * 她自己找（find_skill）、自己学（learn_skill）：学之前在聊天里问你，你点「装上」才装。手机上跑不了脚本，只读文字。
 */
object Skills {
    const val OPEN = "open_skill"
    const val FIND = "find_skill"
    const val LEARN = "learn_skill"
    private const val KEY = "skills"

    var on by mutableStateOf(true)
    var self by mutableStateOf(true)
    /** 助理 id → 她的技能，新装的在前。 */
    var lists by mutableStateOf(mapOf<String, List<Skill>>())
    /** 正等你点头的那一个（聊天里显示「装上 / 先不用」）。 */
    var asking by mutableStateOf<SkillAsk?>(null)
    private var prefs: Prefs? = null
    private lateinit var root: File

    fun init(ctx: Context, p: Prefs) {
        prefs = p
        root = File(ctx.applicationContext.filesDir, "skills").apply { mkdirs() }
        runCatching {
            val o = JSONObject(p.string(KEY) ?: return@runCatching)
            on = o.optBoolean("on", true); self = o.optBoolean("self", true)
            val by = o.optJSONObject("by") ?: return@runCatching
            lists = by.keys().asSequence().associateWith { k -> by.getJSONArray(k).let { a -> List(a.length()) { read(a.getJSONObject(it)) } } }
        }
    }

    private fun read(o: JSONObject) = Skill(
        o.getString("id"), o.optString("title"), o.optString("desc"), o.optString("when"), o.optString("repo"), o.optString("path"),
        o.optString("by", "you"), o.optLong("at"), o.optInt("used"), o.optBoolean("on", true), o.optInt("files", 1), o.optBoolean("script"),
    )

    fun save() {
        val by = JSONObject()
        lists.forEach { (k, l) ->
            by.put(k, JSONArray(l.map { s ->
                JSONObject().put("id", s.id).put("title", s.title).put("desc", s.desc).put("when", s.whenUse).put("repo", s.repo).put("path", s.path)
                    .put("by", s.by).put("at", s.at).put("used", s.used).put("on", s.on).put("files", s.files).put("script", s.script)
            }))
        }
        prefs?.putString(KEY, JSONObject().put("on", on).put("self", self).put("by", by).toString())
    }

    fun of(pid: String = Memory.active.id): List<Skill> = lists[pid].orEmpty()
    fun get(id: String, pid: String = Memory.active.id) = of(pid).firstOrNull { it.id == id }
    private fun put(pid: String, l: List<Skill>) { lists = lists + (pid to l); save() }
    fun update(s: Skill, pid: String = Memory.active.id) = put(pid, of(pid).map { if (it.id == s.id) s else it })
    fun has(c: SkillCand, pid: String = Memory.active.id) = of(pid).any { it.repo == c.repo && it.path == c.path }

    /** 移除：文件夹也删掉。撤销时用 restore 放回原位（文件先挪到 .trash）。 */
    fun remove(s: Skill, pid: String = Memory.active.id): Int {
        val i = of(pid).indexOfFirst { it.id == s.id }
        val trash = File(root, ".trash/$pid/${s.id}"); trash.deleteRecursively(); trash.parentFile?.mkdirs()
        dir(pid, s.id).renameTo(trash)
        put(pid, of(pid).filter { it.id != s.id })
        return i
    }
    fun restore(s: Skill, at: Int, pid: String = Memory.active.id) {
        File(root, ".trash/$pid/${s.id}").renameTo(dir(pid, s.id))
        val l = of(pid).toMutableList(); l.add(at.coerceIn(0, l.size), s); put(pid, l)
    }

    private fun dir(pid: String, id: String) = File(root, "$pid/$id")

    /** 装上：下载、存好，放进她的技能里。 */
    suspend fun install(c: SkillCand, by: String, title: String = "", whenUse: String = "", pid: String = Memory.active.id): Skill {
        get(c.id, pid)?.let { return it }
        val got = SkillHub.fetch(c)
        val d = dir(pid, c.id); d.deleteRecursively(); d.mkdirs()
        got.files.forEach { (name, text) -> File(d, name).apply { parentFile?.mkdirs() }.writeText(text) }
        val s = Skill(c.id, title.trim().ifBlank { got.name.ifBlank { c.id } }, got.desc, whenUse.trim(), c.repo, c.path, by, System.currentTimeMillis(),
            files = got.files.size, script = got.script)
        put(pid, listOf(s) + of(pid).filter { it.id != c.id })
        return s
    }

    /** 检查更新：重新下载说明，不动名字、开关、次数。 */
    suspend fun refresh(s: Skill, pid: String = Memory.active.id): Boolean {
        val got = SkillHub.fetch(SkillCand(s.repo, s.path, s.title, s.desc, ""))
        val d = dir(pid, s.id)
        val old = File(d, "SKILL.md").takeIf { it.exists() }?.readText()
        d.deleteRecursively(); d.mkdirs()
        got.files.forEach { (name, text) -> File(d, name).apply { parentFile?.mkdirs() }.writeText(text) }
        update(s.copy(desc = got.desc, files = got.files.size, script = got.script), pid)
        return old != got.files["SKILL.md"]
    }

    /** 整份说明（详情页「她翻开看到的」也用它）。 */
    fun body(s: Skill, pid: String = Memory.active.id): String = File(dir(pid, s.id), "SKILL.md").takeIf { it.exists() }?.readText().orEmpty()
    fun fileNames(s: Skill, pid: String = Memory.active.id): List<String> =
        dir(pid, s.id).walkTopDown().filter { it.isFile }.map { it.relativeTo(dir(pid, s.id)).path }.filter { it != "SKILL.md" }.sorted().toList()

    // ———— 她用的 ————

    private fun obj(vararg p: Pair<String, String>, req: List<String>) = JSONObject().put("type", "object")
        .put("properties", JSONObject().apply { p.forEach { (k, d) -> put(k, JSONObject().put("type", "string").put("description", d)) } })
        .put("required", JSONArray(req))

    fun specs(): List<ToolSpec> {
        if (!on) return emptyList()
        val l = mutableListOf<ToolSpec>()
        if (of().any { it.on }) l += ToolSpec(OPEN, "翻开你会的一样本事，看它怎么做。用得上时先翻开再动手。file 空着是整份说明，也可以翻它附带的文件。",
            obj("name" to "本事的名字（列表里的那个）", "file" to "附带文件的路径，可以空着", req = listOf("name")))
        if (self) {
            l += ToolSpec(FIND, "去 GitHub 上找别人写好的本事。用英文短词搜，比如 poster、meeting notes、pdf。",
                obj("query" to "要找的本事，英文短词", req = listOf("query")))
            l += ToolSpec(LEARN, "学一样在 GitHub 上找到的本事。学之前会先问一声，点头了才学会。",
                obj("repo" to "find_skill 给的 repo", "path" to "find_skill 给的 path", "title" to "给它起个中文名，四五个字", "why" to "一句话：为什么要学它、什么时候用", req = listOf("repo", "path", "title", "why")))
        }
        return l
    }

    /** 她读到的（只用「你…」）。 */
    fun system(): String {
        val n = Memory.userCall
        val mine = of().filter { it.on }
        return buildString {
            if (mine.isNotEmpty()) {
                append("你会这些本事，用得上时先翻开（$OPEN）看怎么做，再照着做：")
                mine.forEach { append("\n- ").append(it.title).append("：").append(it.shown.take(200)) }
            }
            if (self) {
                if (isNotEmpty()) append("\n")
                append("你也能去 GitHub 上找别人写好的本事来学（$FIND，再 $LEARN）。只在手头这件事真用得上、你会的里又没有时才去找；")
                append("挑说明清楚、星多、来路正的，学之前会问${n}一声，他点头了才学会，没点头就用你自己的办法做。脚本你用不了，只学文字说明。")
            }
        }
    }

    suspend fun run(name: String, a: JSONObject): ToolOut = when (name) {
        OPEN -> {
            val q = a.optString("name").trim()
            val s = of().firstOrNull { it.on && (it.title == q || it.id == q) } ?: of().firstOrNull { it.on && (q in it.title || it.id in q) }
            if (s == null) ToolOut("你会的里没有「$q」。")
            else {
                update(s.copy(used = s.used + 1))
                val f = a.optString("file").trim().removePrefix("/")
                val text = if (f.isEmpty() || f == "SKILL.md") body(s) else File(dir(Memory.active.id, s.id), f).takeIf { it.exists() && it.canonicalPath.startsWith(dir(Memory.active.id, s.id).canonicalPath) }?.readText()
                    ?: return ToolOut("「${s.title}」里没有 $f。有这些：" + fileNames(s).joinToString("、").ifEmpty { "（没有附带文件）" })
                val extra = fileNames(s).takeIf { f.isEmpty() && it.isNotEmpty() }?.let { "\n\n（附带文件：" + it.take(30).joinToString("、") + "）" }.orEmpty()
                ToolOut(text.take(30_000) + extra)
            }
        }
        FIND -> {
            val hits = SkillHub.find(a.optString("query"))
            if (hits.isEmpty()) ToolOut("没找到合适的。可以换个英文词，或者用你自己的办法做。")
            else ToolOut(JSONObject().put("found", JSONArray(hits.map { c ->
                JSONObject().put("repo", c.repo).put("path", c.path).put("name", c.name).put("about", c.desc.take(300)).put("stars", c.stars).put("learned", has(c))
            })).toString())
        }
        LEARN -> {
            val c = SkillHub.cand(a.optString("repo"), a.optString("path"))
            val title = a.optString("title").trim().take(12); val why = a.optString("why").trim().take(80)
            if (has(c)) ToolOut("你已经会「${of().first { it.repo == c.repo && it.path == c.path }.title}」了，直接翻开用。")
            else {
                val ask = SkillAsk(runCatching { SkillHub.described(c) }.getOrDefault(c), title.ifEmpty { c.id }, why)
                asking = ask
                try {
                    if (!ask.answer.await()) ToolOut("${Memory.userCall}说先不用。这次用你自己的办法做，别再提要学它。")
                    else {
                        val s = try { install(c, "her", title, why) } catch (e: Exception) { ask.installed.complete(e.message ?: "没装上"); delay(1600); throw e }
                        ask.installed.complete(null)
                        delay(1300)   // 让卡片画完 ✓、迸完光点再收
                        ToolOut("学会了「${s.title}」。用 $OPEN 翻开看怎么做，再接着做。" + if (s.script) "（它带的脚本你用不了，只照文字做。）" else "")
                    }
                } finally { if (asking === ask) asking = null }
            }
        }
        else -> ToolOut("没有这个本事。")
    }
}
