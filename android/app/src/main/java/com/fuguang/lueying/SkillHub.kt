package com.fuguang.lueying

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** GitHub 上找到的一个技能：repo 是 owner/name，path 是 SKILL.md 所在的文件夹（仓库根就是空的）。 */
data class SkillCand(val repo: String, val path: String, val name: String, val desc: String, val stars: String) {
    val id: String get() = (path.substringAfterLast('/').ifEmpty { repo.substringAfterLast('/') }).lowercase().replace(Regex("[^a-z0-9._-]"), "-").take(60)
}

/** 下载回来的：SKILL.md 的 name / description、要存的文字文件（相对路径 → 内容）、有没有脚本。 */
class SkillFiles(val name: String, val desc: String, val files: Map<String, String>, val script: Boolean)

/**
 * 去 GitHub 找技能、下载技能。不用登录：查找走公开的 API（一小时 60 次），文件走 raw.githubusercontent.com，打不开再走 API。
 * 找的办法：按词搜仓库（加上 anthropics/skills 这类常用的），列出每个仓库里带 SKILL.md 的文件夹，按词挑最像的，读它的说明头。
 */
object SkillHub {
    /** 常去翻的几个仓库：官方的放第一个。 */
    private val known = listOf("anthropics/skills")
    private val textExt = setOf("md", "txt", "json", "yaml", "yml", "csv", "html", "css", "xml", "svg", "toml", "tsv")
    private val scriptExt = setOf("py", "js", "ts", "sh", "rb", "mjs", "cjs", "ps1", "bat")

    /** 「owner/repo」「github.com/owner/repo/tree/main/a/b」「…/blob/main/a/b/SKILL.md」都认。 */
    fun cand(repo: String, path: String = ""): SkillCand {
        val (r, p) = split(repo)
        return SkillCand(r, (path.ifBlank { p }).trim('/').removeSuffix("SKILL.md").trim('/'), "", "", "")
    }

    private fun split(s: String): Pair<String, String> {
        val t = s.trim().removePrefix("https://").removePrefix("http://").removePrefix("www.").removePrefix("github.com/").trim('/')
        val parts = t.split('/')
        if (parts.size < 2) return t to ""
        val repo = parts[0] + "/" + parts[1].removeSuffix(".git")
        val rest = if (parts.size > 4 && (parts[2] == "tree" || parts[2] == "blob")) parts.drop(4).joinToString("/") else ""
        return repo to rest
    }

    fun looksLikeLink(s: String) = s.contains("github.com/") || Regex("^[\\w.-]+/[\\w.-]+(/.*)?$").matches(s.trim())

    /** 一个链接：是某个技能就只给它，是整个仓库就列出里面的技能。 */
    suspend fun fromLink(link: String): List<SkillCand> = withContext(Dispatchers.IO) {
        val (repo, p) = split(link)
        val dirs = skillDirs(repo).filter { p.isEmpty() || it == p || it.startsWith("$p/") }
        val stars = runCatching { stars(api("repos/$repo").optInt("stargazers_count")) }.getOrDefault("")
        dirs.take(12).map { head(SkillCand(repo, it, "", "", stars)) }
    }

    suspend fun find(query: String): List<SkillCand> = withContext(Dispatchers.IO) {
        val q = query.trim().ifEmpty { return@withContext emptyList() }
        val words = q.lowercase().split(Regex("[\\s,，、/_-]+")).filter { it.length > 1 }
        val repos = LinkedHashMap<String, String>()
        known.forEach { repos[it] = "" }
        runCatching {
            val r = api("search/repositories?q=" + URLEncoder.encode("$q skill", "UTF-8") + "&sort=stars&per_page=5").optJSONArray("items")
            if (r != null) for (i in 0 until r.length()) r.getJSONObject(i).let { repos[it.getString("full_name")] = stars(it.optInt("stargazers_count")) }
        }
        val scored = mutableListOf<Pair<Int, SkillCand>>()
        repos.entries.take(5).forEach { (repo, st) ->
            val dirs = runCatching { skillDirs(repo) }.getOrDefault(emptyList())
            dirs.forEach { d ->
                val hay = (repo + "/" + d).lowercase()
                val hit = words.count { it in hay }
                val score = hit * 10 + (if (repo in known) 0 else 2) + (if (dirs.size <= 3) 3 else 0)
                if (hit > 0 || (repo !in known && dirs.size <= 3)) scored += score to SkillCand(repo, d, "", "", st)
            }
        }
        val known0 = runCatching { stars(api("repos/${known.first()}").optInt("stargazers_count")) }.getOrDefault("")
        scored.sortedByDescending { it.first }.take(6).map { (_, c) -> head(if (c.repo in known && c.stars.isEmpty()) c.copy(stars = known0) else c) }
    }

    /** 下载一个技能：只要文字文件，脚本记一笔不下，最多 40 个文件、400 KB。 */
    suspend fun fetch(c: SkillCand): SkillFiles = withContext(Dispatchers.IO) {
        val pre = if (c.path.isEmpty()) "" else c.path + "/"
        val all = tree(c.repo).filter { it.first.startsWith(pre) }
        if (all.none { it.first == pre + "SKILL.md" }) error("这里没有 SKILL.md")
        var script = false; var total = 0
        val files = LinkedHashMap<String, String>()
        all.sortedBy { if (it.first.endsWith("SKILL.md")) 0 else 1 }.forEach { (p, size) ->
            val ext = p.substringAfterLast('.', "").lowercase()
            if (ext in scriptExt) { script = true; return@forEach }
            if (ext !in textExt || files.size >= 40 || total + size > 400_000) return@forEach
            files[p.removePrefix(pre)] = raw(c.repo, p); total += size
        }
        val (n, d) = front(files["SKILL.md"].orEmpty())
        SkillFiles(n, d, files, script)
    }

    /** 问你之前补上星数和说明头。 */
    suspend fun described(c: SkillCand): SkillCand = withContext(Dispatchers.IO) {
        val st = runCatching { stars(api("repos/${c.repo}").optInt("stargazers_count")) }.getOrDefault("")
        head(c.copy(stars = st))
    }

    /** 读 SKILL.md 开头的 name / description。 */
    private fun head(c: SkillCand): SkillCand = runCatching {
        val (n, d) = front(raw(c.repo, (if (c.path.isEmpty()) "" else c.path + "/") + "SKILL.md").take(4000))
        c.copy(name = n.ifBlank { c.id }, desc = d)
    }.getOrDefault(c.copy(name = c.id))

    private fun front(md: String): Pair<String, String> {
        val m = Regex("^---\\s*\\n(.*?)\\n---", RegexOption.DOT_MATCHES_ALL).find(md.trimStart()) ?: return "" to ""
        val lines = m.groupValues[1].lines()
        fun field(k: String): String {
            val i = lines.indexOfFirst { it.startsWith("$k:") }.takeIf { it >= 0 } ?: return ""
            var v = lines[i].substringAfter(':').trim()
            if (v == ">" || v == "|" || v.isEmpty()) v = lines.drop(i + 1).takeWhile { it.startsWith(" ") || it.isBlank() }.joinToString(" ") { it.trim() }
            return v.trim().trim('"', '\'')
        }
        return field("name") to field("description")
    }

    private fun skillDirs(repo: String): List<String> =
        tree(repo).map { it.first }.filter { it == "SKILL.md" || it.endsWith("/SKILL.md") }.map { it.removeSuffix("SKILL.md").trim('/') }
            .filter { d -> d.split('/').none { it.startsWith(".") } }.sortedBy { it.count { ch -> ch == '/' } }.take(200)

    private val trees = HashMap<String, List<Pair<String, Int>>>()
    private fun tree(repo: String): List<Pair<String, Int>> = trees.getOrPut(repo) {
        val a = api("repos/$repo/git/trees/HEAD?recursive=1").optJSONArray("tree") ?: return@getOrPut emptyList()
        (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("type") == "blob" }.map { it.getString("path") to it.optInt("size") }
    }

    private fun stars(n: Int) = if (n >= 10000) "%.1f 万".format(n / 10000f).replace(".0 万", " 万") else "$n"

    private fun open(u: String, accept: String): HttpURLConnection = (URL(u).openConnection() as HttpURLConnection).apply {
        connectTimeout = 12_000; readTimeout = 15_000
        setRequestProperty("User-Agent", "fuguang-lueying"); setRequestProperty("Accept", accept)
    }

    private fun text(conn: HttpURLConnection): String = try {
        val code = conn.responseCode
        if (code !in 200..299) error(
            when (code) {
                403, 429 -> "GitHub 一小时只让查 60 次，过一会儿再试"
                404 -> "GitHub 上没有这个仓库或文件"
                else -> "GitHub 出错了（$code）"
            },
        )
        conn.inputStream.bufferedReader().use { it.readText() }
    } finally { conn.disconnect() }

    private fun api(path: String): JSONObject = JSONObject(text(open("https://api.github.com/$path", "application/vnd.github+json")))

    /** 先走 raw（不算次数），打不开再走 API。 */
    private fun raw(repo: String, path: String): String {
        val p = path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return runCatching { text(open("https://raw.githubusercontent.com/$repo/HEAD/$p", "text/plain")) }
            .getOrElse { text(open("https://api.github.com/repos/$repo/contents/$p", "application/vnd.github.raw")) }
    }
}
