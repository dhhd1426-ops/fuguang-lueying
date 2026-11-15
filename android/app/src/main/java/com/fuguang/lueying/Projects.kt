package com.fuguang.lueying

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 项目里的一份资料。[ref] 是 Attach 目录里的文件；[text] 是读出来的字（图片、读不出字的为空）。 */
data class ProjFile(val id: String, val name: String, val ref: String, val image: Boolean, val text: String) {
    /** 卡片角上的小标：MD / PDF / 图片…… */
    val tag: String get() = if (image) "图片" else ref.substringAfterLast('.', "").uppercase().take(4).ifEmpty { "文本" }
}

/** 一个项目：名字、自定义指令、资料。哪些对话属于它记在 [Projects.links]。不分助理。 */
data class Project(val id: String, val name: String, val ins: String, val files: List<ProjFile>, val edited: Long)

/**
 * 项目（第 468 版，原型 https://claude.ai/artifact/Hppwum2KbaCx5JxB3gk2Qu）。
 * 存在 files/projects.json；对话归属另记一张表，不改 Conv 的存法。
 * 项目里的对话：自定义指令和文字资料拼在系统提示词最后（每轮一样，能缓存），总共不超过 [LIMIT] 字。图片先只存着，不发给她。
 */
object Projects {
    const val LIMIT = 30_000
    val all = mutableStateListOf<Project>()
    /** 对话 id → 项目 id。 */
    val links = mutableStateMapOf<String, String>()

    /** 界面：项目页开着没有、看的是哪个（null = 列表）。 */
    var open by mutableStateOf(false)
    var showing by mutableStateOf<String?>(null)
    /** 项目里点「新建会话」：聊天页收起侧栏（ChatPage 跟着它）。 */
    var chatTick by mutableIntStateOf(0)

    private var file: File? = null

    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.filesDir, "projects.json").also { file = it }
        runCatching {
            if (!f.exists()) return@runCatching
            val o = JSONObject(f.readText())
            val ps = o.optJSONArray("p") ?: JSONArray()
            for (i in 0 until ps.length()) {
                val p = ps.getJSONObject(i)
                val fs = p.optJSONArray("f") ?: JSONArray()
                all += Project(
                    p.getString("id"), p.optString("n"), p.optString("i"),
                    List(fs.length()) { k -> fs.getJSONObject(k).let { x -> ProjFile(x.optString("id"), x.optString("n"), x.optString("r"), x.optBoolean("img"), x.optString("x")) } },
                    p.optLong("e"),
                )
            }
            o.optJSONObject("l")?.let { l -> l.keys().forEach { k -> links[k] = l.optString(k) } }
        }
    }

    private fun persist() {
        val f = file ?: return
        val ps = JSONArray()
        all.forEach { p ->
            val fs = JSONArray()
            p.files.forEach { x -> fs.put(JSONObject().put("id", x.id).put("n", x.name).put("r", x.ref).put("img", x.image).put("x", x.text)) }
            ps.put(JSONObject().put("id", p.id).put("n", p.name).put("i", p.ins).put("e", p.edited).put("f", fs))
        }
        val l = JSONObject(); links.forEach { (k, v) -> l.put(k, v) }
        val text = JSONObject().put("p", ps).put("l", l).toString()
        ConvStore.Saver.put(f) { text }
    }

    fun get(id: String?): Project? = all.firstOrNull { it.id == id }
    /** 这段对话属于哪个项目。 */
    fun of(convId: String?): String? = convId?.let { links[it] }?.takeIf { id -> all.any { it.id == id } }
    fun convs(id: String, history: ConvStore): List<Conv> = history.all.filter { links[it.id] == id }.sortedByDescending { it.updated }

    private fun update(id: String, f: (Project) -> Project) {
        val i = all.indexOfFirst { it.id == id }
        if (i < 0) return
        all[i] = f(all[i]).copy(edited = System.currentTimeMillis())
        // 改过的排到最前
        if (i > 0) all.add(0, all.removeAt(i))
        persist()
    }

    fun create(name: String): String {
        val id = "p" + System.currentTimeMillis()
        all.add(0, Project(id, name.trim().take(40), "", emptyList(), System.currentTimeMillis()))
        persist()
        return id
    }
    fun rename(id: String, name: String) = update(id) { it.copy(name = name.trim().take(40).ifEmpty { it.name }) }
    fun setIns(id: String, ins: String) = update(id) { it.copy(ins = ins.trim()) }
    fun delete(id: String) {
        get(id)?.files?.forEach { Attach.drop(it.ref) }
        all.removeAll { it.id == id }
        links.keys.filter { links[it] == id }.forEach { links.remove(it) }
        if (showing == id) showing = null
        persist()
    }
    fun link(convId: String, projectId: String) { if (links[convId] != projectId) { links[convId] = projectId; update(projectId) { it } } }

    /** 已经占了多少字（指令 + 文字资料）。 */
    fun used(p: Project): Int = p.ins.length + p.files.sumOf { it.text.length }
    fun room(p: Project): Int = (LIMIT - used(p)).coerceAtLeast(0)

    /** 从系统选的文件 / 照片 / 相机加进来；超出容量的字截掉。返回 false = 存不下来。 */
    suspend fun add(ctx: Context, id: String, uri: Uri, image: Boolean): Boolean {
        val ref = Attach.save(ctx, uri, asImage = image) ?: return false
        return addRef(id, ref)
    }
    suspend fun addRef(id: String, ref: String): Boolean {
        val p = get(id) ?: return false
        val img = Attach.isImage(ref)
        val text = if (img) "" else withContext(Dispatchers.IO) { AttachRead.text(Attach.file(ref)).orEmpty() }.take(room(p))
        val name = ref.substringAfterLast('/').substringBeforeLast('.').take(40)
        update(id) { it.copy(files = listOf(ProjFile("f" + System.nanoTime(), name, ref, img, text)) + it.files) }
        return true
    }
    /** 新建文档：存成 .md。 */
    fun addDoc(id: String, title: String, body: String) {
        val p = get(id) ?: return
        val name = title.trim().ifEmpty { firstSentence(body, 16) }.ifEmpty { "未命名" }.take(40)
        // 引用是「目录/文件名」：Attach.drop 会删掉整个目录，所以每份一个目录
        val ref = "proj" + System.currentTimeMillis().toString(36) + "/" + name.replace(Regex("[/\\\\:*?\"<>|]"), "_") + ".md"
        runCatching { Attach.file(ref).apply { parentFile?.mkdirs() }.writeText(body) }
        update(id) { it.copy(files = listOf(ProjFile("f" + System.nanoTime(), name, ref, false, body.take(room(p)))) + it.files) }
    }
    fun renameFile(id: String, fileId: String, name: String) = update(id) { p -> p.copy(files = p.files.map { if (it.id == fileId) it.copy(name = name.trim().take(40).ifEmpty { it.name }) else it }) }
    fun deleteFile(id: String, fileId: String) {
        get(id)?.files?.firstOrNull { it.id == fileId }?.let { Attach.drop(it.ref) }
        update(id) { p -> p.copy(files = p.files.filterNot { it.id == fileId }) }
    }

    /** 拼在系统提示词最后。只用「你」的口吻，不出现戏外的词（见 Prompt.kt 第一原则）。 */
    fun system(id: String?): String {
        val p = get(id) ?: return ""
        val docs = p.files.filter { it.text.isNotBlank() }
        if (p.ins.isBlank() && docs.isEmpty()) return ""
        val who = Memory.userCall
        return buildString {
            append("\n\n你们这段聊天在「").append(p.name).append("」里。")
            if (p.ins.isNotBlank()) append("\n在「").append(p.name).append("」里，").append(who).append("交代过：\n").append(p.ins)
            if (docs.isNotEmpty()) {
                append("\n\n「").append(p.name).append("」里放着这些资料，用得上就照着用：")
                docs.forEach { append("\n\n《").append(it.name).append("》\n").append(it.text) }
            }
        }
    }
}
