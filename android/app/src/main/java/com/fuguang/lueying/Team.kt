package com.fuguang.lueying

import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/*
 * 分身团队（第 398 版，原型 https://claude.ai/artifact/DQqyv4AAxQkVig2FrNCJs1 第 3 版；用户的《Amor 多分身协作机制设计方案》）：
 * 她（Amor）遇到大、能拆开的作品，用 spawn_team 叫 α β γ 在后台一起做（照 WorkJobs：后台跑，她接着跟他说话）。
 * - 协调员就是她自己（用户定的，不另设零）：派活、谁等谁、超时、轮数这些死规矩由这里的代码执行；分身问她的事、他中途改的要求由她定。
 * - 她随时能看全部：每个分身写了什么、发了什么、黑板上记了什么（team_look）；要改就 team_tell 告诉对应的分身，写完的会按新要求重写。
 * - 分身只能查资料、写内容（web_search + 团队工具），不能动任何会改东西的工具。
 * - 分身和她一样的记忆配置（第 400 版用户要的）：带她的记忆目录和「一直记着」那几条、能翻记忆（只读，记新事 / 忘掉只有她能），也看得到叫分身前最近几句聊天。
 * - 全部写完由她整合成一件作品（只调用一次 make_work），作品卡出现在她那条回复里。
 * - 进度在思考过程里（第 400 版，原型第 4 版，用户说的）：那条回复的思考行一直写最新一步，摘要里叫分身那一步挂 α β γ 和停下 / 接着做（TeamTrail.kt），没有进度卡。
 * - 护栏：最多 3 个分身、分身不能再叫分身、每个分身单次最多 12 分钟、同一家最多同时 2 路、每个分身最多发 6 条消息、整队 25 分钟到了就拿已有的整合。
 * - 摘要里能停下 / 接着做；App 被杀掉，下次打开是「停在这里」，点接着做从没写完的那几段重来。
 * 审查者和摘要里的分身节点下一版做。
 */
object Team {
    const val SPAWN = "spawn_team"
    const val LOOK = "team_look"
    const val TELL = "team_tell"
    const val STOP = "team_stop"
    const val PREFIX = "team:"

    private const val NOTE = "team_note"
    private const val ASK = "team_ask"
    private const val DONE = "team_done"

    private const val MEMBER_MS = 12 * 60_000L
    private const val TEAM_MS = 25 * 60_000L
    private const val MAX_MSGS = 6

    /** 一个分身那一块：status = wait / run / done / fail / stop；note = 摘要里那一行（在查什么）。 */
    data class Part(
        val who: String, val task: String, val after: List<String>,
        val status: String = "wait", val note: String = "", val content: String = "", val redo: String = "", val tries: Int = 0,
    )

    /** stage = write（分身在写）/ merge（她在整合）/ done / fail / stop。 */
    data class Run(
        val key: String, val title: String, val kind: String, val brief: String, val conv: String?, val persona: String,
        val parts: List<Part>, val board: List<String> = emptyList(), val log: List<String> = emptyList(),
        val stage: String = "write", val done: String? = null, val fail: String? = null, val at: Long = System.currentTimeMillis(),
        /** 叫分身前最近几句聊天（分身看得到上下文）。 */
        val talk: String = "",
    ) {
        fun part(who: String) = parts.firstOrNull { it.who == who }
    }

    val runs = mutableStateMapOf<String, Run>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val jobs = HashMap<String, MutableList<Job>>()
    private val inbox = HashMap<String, MutableList<String>>()
    private val sent = HashMap<String, Int>()
    private val chats = HashMap<String, ChatState>()
    private val locks = HashMap<String, Semaphore>()
    private var file: java.io.File? = null

    fun keyOf(ref: String) = ref.removePrefix(PREFIX).substringBefore('|')
    /** 刚叫的那一队：聊天页把它记进叫分身那一步（ToolStep.detail = "team:<key>"），思考过程靠它找到这一队。 */
    @Volatile var lastKey = ""
    fun isRef(ref: String) = ref.startsWith(PREFIX)

    fun init(p: Prefs) {
        if (file != null) return
        val f = p.file("teams.json"); file = f
        runCatching {
            val arr = JSONArray(f.takeIf { it.exists() }?.readText() ?: return@runCatching)
            for (i in 0 until arr.length()) read(arr.getJSONObject(i))?.let { r ->
                // 上次没做完就被关掉了：停在这里，等他点接着做
                runs[r.key] = if (r.stage == "write" || r.stage == "merge") r.copy(stage = "stop", parts = r.parts.map { if (it.status == "run") it.copy(status = "stop") else it }) else r
            }
        }
    }

    private fun read(o: JSONObject): Run? = runCatching {
        val ps = o.getJSONArray("p")
        Run(
            o.getString("k"), o.optString("t"), o.optString("kd"), o.optString("b"), o.optString("c").ifEmpty { null }, o.optString("w"),
            List(ps.length()) { i ->
                val x = ps.getJSONObject(i)
                Part(x.optString("w"), x.optString("t"), x.optJSONArray("a")?.let { a -> List(a.length()) { a.optString(it) } } ?: emptyList(),
                    x.optString("s", "wait"), x.optString("n"), x.optString("x"), x.optString("r"), x.optInt("tr"))
            },
            o.optJSONArray("bd")?.let { a -> List(a.length()) { a.optString(it) } } ?: emptyList(),
            o.optJSONArray("lg")?.let { a -> List(a.length()) { a.optString(it) } } ?: emptyList(),
            o.optString("s", "stop"), o.optString("d").ifEmpty { null }, o.optString("f").ifEmpty { null }, o.optLong("at"), o.optString("tk"),
        )
    }.getOrNull()

    private fun json(r: Run): JSONObject = JSONObject().put("k", r.key).put("t", r.title).put("kd", r.kind).put("b", r.brief).put("c", r.conv.orEmpty()).put("w", r.persona)
        .put("p", JSONArray(r.parts.map { JSONObject().put("w", it.who).put("t", it.task).put("a", JSONArray(it.after)).put("s", it.status).put("n", it.note).put("x", it.content).put("r", it.redo).put("tr", it.tries) }))
        .put("bd", JSONArray(r.board)).put("lg", JSONArray(r.log)).put("s", r.stage).put("d", r.done.orEmpty()).put("f", r.fail.orEmpty()).put("at", r.at).put("tk", r.talk)

    private fun persist() {
        val f = file ?: return
        val snap = runs.values.sortedByDescending { it.at }.take(30)
        ConvStore.Saver.put(f) { JSONArray(snap.map { json(it) }).toString() }
    }

    private fun put(r: Run) { runs[r.key] = r; persist() }
    private fun edit(key: String, f: (Run) -> Run) { runs[key]?.let { put(f(it)) } }
    private fun editPart(key: String, who: String, f: (Part) -> Part) = edit(key) { r -> r.copy(parts = r.parts.map { if (it.who == who) f(it) else it }) }
    private fun log(key: String, line: String) = edit(key) { it.copy(log = (it.log + line).takeLast(60)) }

    /* ---------- 她用的工具 ---------- */

    val spec get() = ToolSpec(
        SPAWN,
        "叫分身 α β γ 在后台一起做一件大作品（幻灯片、长文档、要查很多资料的网页）。分身各写一块，写完你来整合，作品卡会出现在你这条回复里，进度他在思考过程里看得到；你交代完就接着跟他说话。" +
            "能不叫就不叫：聊天、短回答、小作品你自己做或用 ${WorkJobs.START}。brief 写清楚总要求和规范（给谁看、风格、口吻、数字怎么写、每块大概多长）；" +
            "parts 每个分身一块（最多 3 个），task 写清它负责什么，after 写它要等谁写完（比如预算要等前两块的价格）。",
        JSONObject().put("type", "object").put(
            "properties", JSONObject()
                .put("title", JSONObject().put("type", "string").put("description", "作品名，几个字"))
                .put("kind", JSONObject().put("type", "string").put("enum", JSONArray(listOf("page", "slides", "doc"))))
                .put("brief", JSONObject().put("type", "string").put("description", "总要求和规范，每个分身都会看到"))
                .put(
                    "parts", JSONObject().put("type", "array").put(
                        "items", JSONObject().put("type", "object").put(
                            "properties", JSONObject()
                                .put("who", JSONObject().put("type", "string").put("enum", JSONArray(Amor.NAMES)))
                                .put("task", JSONObject().put("type", "string").put("description", "这个分身负责的那一块，写清楚"))
                                .put("after", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "要等哪几个分身写完，比如 [\"α\",\"β\"]；不用等就空着")),
                        ).put("required", JSONArray(listOf("who", "task"))),
                    ),
                ),
        ).put("required", JSONArray(listOf("title", "kind", "brief", "parts"))),
    )

    val lookSpec = ToolSpec(
        LOOK, "看后台分身做到哪了：每个分身的进度和写好的内容、黑板上记的事、他们之间发的消息。他问做到哪了、或者你要核对分身写的内容时用。",
        JSONObject().put("type", "object").put("properties", JSONObject()),
    )
    val tellSpec = ToolSpec(
        TELL, "告诉后台的分身一件事（他中途改了要求、你裁决了分身问你的事）。to 写 α / β / γ，或「全体」。已经写完的分身会按这句重写。",
        JSONObject().put("type", "object").put(
            "properties", JSONObject()
                .put("to", JSONObject().put("type", "string").put("description", "α / β / γ / 全体"))
                .put("text", JSONObject().put("type", "string")),
        ).put("required", JSONArray(listOf("to", "text"))),
    )
    val stopSpec = ToolSpec(STOP, "让后台的分身停下（他说不要了、不做了）。写好的部分留着。", JSONObject().put("type", "object").put("properties", JSONObject()))

    /** 这一轮给她哪些团队工具：开着 Amor 才能叫；这段聊天里有团队才给看、说、停。 */
    fun specs(conv: String?): List<ToolSpec> {
        val mine = conv != null && runs.values.any { it.conv == conv }
        return (if (Amor.on) listOf(spec) else emptyList()) + (if (mine) listOf(lookSpec, tellSpec, stopSpec) else emptyList())
    }

    fun system(who: String, conv: String?): String = buildString {
        if (Amor.on) append("大、能拆开的作品（15 页以上的幻灯片、长文档、要查很多资料的）可以用 $SPAWN 叫分身 α β γ 在后台一起做，你来定规范、最后整合；能不叫就不叫。交代完接着跟${who}说话，别说做好了。")
        if (conv != null && runs.values.any { it.conv == conv }) append("${who}问分身做到哪了，用 $LOOK 看了再照实说；他改了要求，用 $TELL 告诉对应的分身；他说不要了用 $STOP。")
    }

    /** 每轮放在他最后一句旁边：这段聊天里正在做的团队到哪了。 */
    fun note(conv: String?): String {
        val r = runs.values.filter { it.conv == conv && (it.stage == "write" || it.stage == "merge") }.maxByOrNull { it.at } ?: return ""
        val ps = r.parts.joinToString("，") { it.who + when (it.status) { "done" -> "写好了"; "run" -> "在写"; "fail" -> "没写成"; "stop" -> "停着"; else -> "等着" } }
        return "（后台分身在做《${r.title}》：" + (if (r.stage == "merge") "都写好了，在整合" else ps) + "。要看细节用 $LOOK。）"
    }

    fun start(a: JSONObject, chat: ChatState, persona: String, onCard: (String) -> Unit): ToolOut {
        if (!Amor.on) return ToolOut("Amor 关着，叫不了分身。自己做，或者用 ${WorkJobs.START}。")
        val title = a.optString("title").trim().ifEmpty { "未命名" }.take(40)
        val brief = a.optString("brief").trim()
        if (brief.isEmpty()) return ToolOut("brief 是空的：把总要求和规范写清楚再叫一次。")
        val arr = a.optJSONArray("parts") ?: JSONArray()
        val parts = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { o ->
            val w = o.optString("who").trim(); val t = o.optString("task").trim()
            if (w !in Amor.NAMES || t.isEmpty()) null
            else Part(w, t.take(1500), o.optJSONArray("after")?.let { x -> List(x.length()) { x.optString(it).trim() } }.orEmpty().filter { it in Amor.NAMES && it != w })
        }.distinctBy { it.who }.take(3)
        if (parts.isEmpty()) return ToolOut("parts 是空的：每个分身（α / β / γ）写一块 task。")
        // 谁等谁不能绕成圈：有圈就都不等
        val names = parts.map { it.who }.toSet()
        val clean = parts.map { p -> p.copy(after = p.after.filter { it in names }) }
        val ok = acyclic(clean)
        val final = if (ok) clean else clean.map { it.copy(after = emptyList()) }
        val key = System.currentTimeMillis().toString(36) + (100..999).random()
        val talk = chat.messages.takeLast(10).filter { it.text.isNotBlank() }.joinToString("\n") { m -> (if (m.role == "user") Memory.userCall else "Amor") + "：" + ToolText.clean(m.text).take(600) }.takeLast(4000)
        put(Run(key, title, a.optString("kind").ifBlank { "doc" }, brief.take(6000), chat.convId, persona, final, talk = talk))
        lastKey = key
        chats[key] = chat
        onCard("$PREFIX$key|$title")
        go(key)
        return ToolOut("已经叫 ${final.joinToString(" ") { it.who }} 在后台做《$title》了，进度在你这条回复的思考过程里，做好了作品卡会出现在这里。接着跟${Memory.userCall}说话；别说做好了。")
    }

    private fun acyclic(ps: List<Part>): Boolean {
        val m = ps.associate { it.who to it.after }
        fun visit(n: String, path: Set<String>): Boolean = if (n in path) false else m[n].orEmpty().all { visit(it, path + n) }
        return ps.all { visit(it.who, emptySet()) }
    }

    fun look(conv: String?): ToolOut {
        val list = runs.values.filter { it.conv == conv }.sortedByDescending { it.at }.take(2)
        if (list.isEmpty()) return ToolOut("这段聊天里没有叫过分身。")
        return ToolOut(list.joinToString("\n\n——\n\n") { r ->
            buildString {
                append("《${r.title}》 ").append(stageZh(r)).append('\n')
                r.parts.forEach { p ->
                    append("\n【${p.who}】${p.task.take(80)} · ").append(statusZh(p)).append('\n')
                    if (p.content.isNotBlank()) append(p.content.take(3000)).append('\n')
                }
                if (r.board.isNotEmpty()) append("\n黑板：\n").append(r.board.takeLast(20).joinToString("\n")).append('\n')
                if (r.log.isNotEmpty()) append("\n他们之间的消息：\n").append(r.log.takeLast(20).joinToString("\n"))
            }
        }.take(16_000))
    }

    fun tell(conv: String?, a: JSONObject): ToolOut {
        val r = runs.values.filter { it.conv == conv && it.stage != "done" }.maxByOrNull { it.at } ?: return ToolOut("没有还在做的分身。")
        val text = a.optString("text").trim().ifEmpty { return ToolOut("text 是空的。") }
        val to = a.optString("to").trim()
        val targets = if (to in Amor.NAMES) listOf(to) else r.parts.map { it.who }
        targets.forEach { w ->
            val p = r.part(w) ?: return@forEach
            if (p.status == "done") editPart(r.key, w) { it.copy(status = "wait", redo = (it.redo + "\n" + text).trim()) }
            else inbox.getOrPut("${r.key}/$w") { mutableListOf() } += "Amor 说：$text"
        }
        log(r.key, "Amor → ${if (to in Amor.NAMES) to else "全体"}：$text")
        val cur = runs[r.key]
        if (cur != null && (cur.stage == "stop" || cur.stage == "fail" || cur.stage == "merge")) {
            jobs[r.key]?.forEach { it.cancel() }
            put(cur.copy(stage = "write", fail = null))
            go(r.key)
        }
        return ToolOut("告诉 ${targets.joinToString(" ")} 了；写完的会按这句重写。")
    }

    fun stopFromChat(conv: String?): ToolOut {
        val r = runs.values.filter { it.conv == conv && (it.stage == "write" || it.stage == "merge") }.maxByOrNull { it.at } ?: return ToolOut("没有还在做的分身。")
        stop(r.key)
        return ToolOut("分身都停下了，写好的留着。")
    }

    private fun stageZh(r: Run) = when (r.stage) { "merge" -> "都写好了，在整合"; "done" -> "做好了"; "fail" -> "没做成：" + r.fail.orEmpty(); "stop" -> "停着"; else -> "分身在写" }
    private fun statusZh(p: Part) = when (p.status) { "done" -> "写好了"; "run" -> "在写" + (if (p.note.isNotEmpty()) "（${p.note}）" else ""); "fail" -> "没写成"; "stop" -> "停着"; else -> if (p.after.isNotEmpty()) "等 ${p.after.joinToString(" ")}" else "等开工" }

    /* ---------- 摘要里的停下 / 接着做 ---------- */

    fun stop(key: String) {
        jobs.remove(key)?.forEach { it.cancel() }
        edit(key) { r -> r.copy(stage = "stop", parts = r.parts.map { if (it.status == "run") it.copy(status = "stop") else it }) }
    }

    fun resume(key: String) {
        val r = runs[key] ?: return
        if (r.stage == "write" || r.stage == "merge" || r.stage == "done") return
        put(r.copy(stage = "write", fail = null, at = r.at, parts = r.parts.map { if (it.status != "done") it.copy(status = "wait", tries = 0) else it }))
        go(key)
    }

    /* ---------- 调度（死规矩，代码执行） ---------- */

    private fun go(key: String) {
        val list = jobs.getOrPut(key) { mutableListOf() }
        list += scope.launch {
            val started = System.currentTimeMillis()
            while (true) {
                val r = runs[key] ?: return@launch
                if (r.stage != "write") return@launch
                val over = System.currentTimeMillis() - started > TEAM_MS
                if (over || r.parts.all { it.status == "done" || it.status == "fail" }) {
                    // 到点了还有在写的：停下，拿写好的整合
                    if (over) jobs[key]?.filter { it !== coroutineContext[Job] }?.forEach { it.cancel() }
                    if (r.parts.none { it.status == "done" }) { put(r.copy(stage = "fail", fail = "分身都没写出来")); return@launch }
                    merge(key)
                    return@launch
                }
                r.parts.filter { p -> p.status == "wait" && p.after.all { a -> r.part(a)?.status == "done" || r.part(a)?.status == "fail" } }.forEach { p ->
                    editPart(key, p.who) { it.copy(status = "run", note = "在开工", tries = it.tries + 1) }
                    list += scope.launch { member(key, p.who) }
                }
                delay(600)
            }
        }
    }

    private fun lock(pid: String) = locks.getOrPut(pid) { Semaphore(2) }

    private fun low(store: AssistantStore, pid: String, model: String): String? =
        store.effort(pid, model)?.let { e -> if (e == Effort.OFF) e else Effort.spec(pid, model).levels.firstOrNull { it.value != Effort.OFF }?.value ?: e }

    private val noteSpec = ToolSpec(NOTE, "把查到的关键事实记到黑板上（价格、日期、名字、数字），别的分身直接看，不用来回问。一条一件事。",
        JSONObject().put("type", "object").put("properties", JSONObject().put("text", JSONObject().put("type", "string"))).put("required", JSONArray(listOf("text"))))
    private val askSpec = ToolSpec(ASK, "问别的分身（α / β / γ）或 Amor 一件事。对方回了会出现在你后面的消息里，别干等，先写别的。要定主意的（两份数据冲突、要不要改规范）问 Amor。",
        JSONObject().put("type", "object").put("properties", JSONObject().put("to", JSONObject().put("type", "string")).put("text", JSONObject().put("type", "string"))).put("required", JSONArray(listOf("to", "text"))))
    private val doneSpec = ToolSpec(DONE, "交上你负责那一块的完整内容（不是摘要，是能直接放进作品里的正文，Markdown）。交了就结束。",
        JSONObject().put("type", "object").put("properties", JSONObject().put("content", JSONObject().put("type", "string"))).put("required", JSONArray(listOf("content"))))

    private fun memberSystem(r: Run, p: Part): String = buildString {
        val her = Memory.get(r.persona)
        append("你是分身 ${p.who}，和另外几个分身一起帮 Amor 做《${r.title}》（${kindZh(r.kind)}）。你只负责自己那一块，别人的部分不要写。\n")
        append("规矩：只查资料、写内容；查到的关键事实用 $NOTE 记到黑板；要别人的数据用 $ASK 问；写完用 $DONE 交上完整正文，交了就结束。不要跟谁寒暄，不要解释你在做什么。")
        Amor.role(p.who).prompt.trim().takeIf { it.isNotEmpty() }?.let { append("\n\n").append(it) }
        // 和她一样的记忆（只读）
        if (her != null && her.noteOn) MemTree.catalog(her).takeIf { it.isNotBlank() }?.let { cat ->
            append("\n\n你和 Amor 共用她记得的事，这里是目录（方括号里是编号）：\n").append(cat)
            append("\n\n「一直记着」那组的正文都在上面，照着做。做你这一块时用得上的，用 ${Memory.READ} 翻那条的正文；只能翻，不能记新的。")
        }
    }

    private fun kindZh(k: String) = when (k) { "slides" -> "幻灯片"; "page" -> "网页"; else -> "文档" }

    private fun memberOrder(r: Run, p: Part): String = buildString {
        append("总要求和规范（Amor 定的，照着做）：\n").append(r.brief).append("\n\n")
        if (r.talk.isNotBlank()) append("叫分身之前，${Memory.userCall}和 Amor 最近的几句聊天：\n").append(r.talk).append("\n\n")
        append("你负责：").append(p.task).append('\n')
        val others = r.parts.filter { it.who != p.who }
        if (others.isNotEmpty()) append("别的分身：").append(others.joinToString("；") { "${it.who} 写 ${it.task.take(60)}" }).append('\n')
        p.after.mapNotNull { r.part(it) }.filter { it.content.isNotBlank() }.forEach { d -> append("\n${d.who} 已经写好的（你要用到它）：\n").append(d.content.take(8000)).append('\n') }
        if (r.board.isNotEmpty()) append("\n黑板上已经记的：\n").append(r.board.takeLast(30).joinToString("\n")).append('\n')
        if (p.redo.isNotBlank()) {
            append("\n你上次交的：\n").append(p.content.take(8000)).append("\n\n这次要改：").append(p.redo).append('\n')
        }
        append("\n现在开始，写完调用 $DONE。")
    }

    private suspend fun member(key: String, who: String) {
        val store = AttachLook.store
        val r0 = runs[key]
        val p0 = r0?.part(who)
        if (store == null || r0 == null || p0 == null) { editPart(key, who) { it.copy(status = "fail", note = "App 还没准备好") }; return }
        val role = Amor.role(who)
        val pid = if (Amor.own(store, role)) role.pid else store.activeProvider
        val model = if (Amor.own(store, role)) role.model else store.activeModel
        val c = store.config(pid)
        var done: String? = null
        val text = StringBuilder()
        var err: String? = null
        val her = Memory.get(r0.persona)
        val tools = buildList {
            if (WebSearch.usable) add(WebSearch.spec)
            if (her != null && her.noteOn) Memory.toolSpecs.firstOrNull { it.name == Memory.READ }?.let { add(it) }
            add(noteSpec); add(askSpec); add(doneSpec)
        }
        try {
            lock(pid).withPermit {
                withTimeout(MEMBER_MS) {
                    ChatApi.stream(
                        provider(pid), c, model, memberSystem(r0, p0), listOf(ChatMsg("user", memberOrder(r0, p0))), low(store, pid, model),
                        onThink = { },
                        tools = Tools(
                            tools,
                            run = { n, a -> if (n == Memory.READ && her != null) Memory.runTool(her, n, a, r0.conv.orEmpty(), r0.title) else memberTool(key, who, n, a) { done = it } },
                            onUse = { n -> if (n == WebSearch.TOOL) editPart(key, who) { it.copy(note = "在查资料") } },
                            inbox = { inbox.remove("$key/$who")?.joinToString("\n") },
                            wrap = { "（团队里的消息：）\n$it\n\n（看完接着写。）" },
                        ),
                    ) { s ->
                        text.append(s)
                        if (done != null) throw CancellationException("交了")
                    }
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            err = "超时"
        } catch (e: CancellationException) {
            if (done == null) { if (runs[key]?.stage == "stop") return; err = "停下了" }
        } catch (e: Exception) {
            err = (e.message ?: e.javaClass.simpleName).take(60)
        }
        val content = (done ?: ToolText.clean(text.toString())).trim()
        if (content.isNotEmpty() && (done != null || err == null)) {
            editPart(key, who) { it.copy(status = "done", content = content.take(40_000), redo = "", note = "写好了") }
            return
        }
        // 没写成：自动再来一次；第二次还不行就算了，拿别人写好的整合
        val p = runs[key]?.part(who)
        val fatal = err?.let { e -> e.contains("402") || e.contains("401") || e.contains("403") } == true
        if (fatal) { stop(key); edit(key) { it.copy(stage = "fail", fail = MemModel.why(IllegalStateException(err), c.name, model)) }; return }
        if (p != null && p.tries < 2) editPart(key, who) { it.copy(status = "wait", note = "再试一次") }
        else editPart(key, who) { it.copy(status = "fail", note = err ?: "没写出来") }
    }

    private suspend fun memberTool(key: String, who: String, n: String, a: JSONObject, onDone: (String) -> Unit): ToolOut = when (n) {
        WebSearch.TOOL -> { editPart(key, who) { it.copy(note = "查了「${a.optString("query").take(12)}」") }; WebSearch.run(a.optString("query")) }
        NOTE -> {
            val t = a.optString("text").trim().take(300)
            if (t.isEmpty()) ToolOut("text 是空的。")
            else { edit(key) { it.copy(board = (it.board + "$who：$t").takeLast(60)) }; ToolOut("记下了。") }
        }
        ASK -> ask(key, who, a.optString("to").trim(), a.optString("text").trim())
        DONE -> {
            val c = a.optString("content").trim()
            if (c.length < 20) ToolOut("内容太短：把你负责那一块的完整正文交上来。") else { onDone(c); ToolOut("交上了，不用再说话。") }
        }
        else -> ToolOut("这里用不了这个。")
    }

    private suspend fun ask(key: String, who: String, to: String, text: String): ToolOut {
        if (text.isEmpty()) return ToolOut("text 是空的。")
        val k = "$key/$who"
        val n = (sent[k] ?: 0) + 1
        if (n > MAX_MSGS) return ToolOut("消息用完了：按自己的判断写完交上来，拿不准的地方写明。")
        sent[k] = n
        val r = runs[key] ?: return ToolOut("团队已经散了。")
        if (to in Amor.NAMES && to != who && r.part(to) != null) {
            inbox.getOrPut("$key/$to") { mutableListOf() } += "$who 问你：$text（用 $ASK 回 $who）"
            log(key, "$who → $to：$text")
            return ToolOut("发给 $to 了；回了会出现在你后面的消息里。先写别的部分。")
        }
        // 问 Amor：她用聊天的模型当场定一下，回到发问的分身那里
        log(key, "$who → Amor：$text")
        scope.launch {
            val store = AttachLook.store ?: return@launch
            val pv = provider(store.activeProvider); val c = store.config(pv.id)
            val sys = "你是 Amor，在后台协调分身做《${r.title}》。分身问你一件事，按总要求和黑板定下来，一两句话，直接说结论。"
            val user = "总要求：\n${r.brief.take(3000)}\n\n黑板：\n${r.board.takeLast(20).joinToString("\n")}\n\n$who 问：$text"
            val answer = runCatching { ChatApi.complete(pv, c, store.activeModel, sys, user, 400) }.getOrNull()?.trim().orEmpty()
            if (answer.isNotEmpty()) {
                inbox.getOrPut("$key/$who") { mutableListOf() } += "Amor 回你：$answer"
                log(key, "Amor → $who：$answer")
            }
        }
        return ToolOut("问 Amor 了；她回了会出现在你后面的消息里。先写别的部分。")
    }

    /* ---------- 她整合 ---------- */

    private suspend fun merge(key: String) {
        val r = runs[key] ?: return
        put(r.copy(stage = "merge"))
        val store = AttachLook.store ?: run { put(r.copy(stage = "fail", fail = "App 还没准备好")); return }
        val pv = provider(store.activeProvider); val c = store.config(pv.id)
        val make = Works.specs.first { it.name == Works.MAKE }
        var made: String? = null
        var err: String? = null
        val sys = "你是 Amor。后台的分身已经把各自那一块写好了，现在由你整合成一件完整的作品：统一口吻、补上段落之间的过渡、去掉重复、核对数字和前后说法（以黑板为准），写得不好的地方你来改。" +
            "只调用一次 ${Works.MAKE}，把整件作品一次写完；不要回复别的话。\n\n" + Craft.system()
        val user = buildString {
            append("作品名：《${r.title}》，种类：${r.kind}。\n\n总要求和规范：\n").append(r.brief).append("\n\n")
            r.parts.forEach { p ->
                append("【${p.who} · ${p.task.take(80)}】").append(if (p.status == "done") "\n" + p.content.take(20_000) else "（没写成，这一块你自己补上）").append("\n\n")
            }
            if (r.board.isNotEmpty()) append("黑板：\n").append(r.board.takeLast(40).joinToString("\n")).append("\n\n")
            append("现在就调用 ${Works.MAKE} 做出来。")
        }
        try {
            ChatApi.stream(
                pv, c, store.activeModel, sys, listOf(ChatMsg("user", user)), low(store, pv.id, store.activeModel), onThink = { },
                tools = Tools(
                    listOf(make),
                    run = { n, a ->
                        if (n != Works.MAKE) ToolOut("这里只用 ${Works.MAKE}。")
                        else {
                            a.put("title", r.title).put("kind", r.kind)
                            Works.run(Works.MAKE, a, r.persona) { id -> made = id }.let { out -> if (made != null) ToolOut("好了，不用再说话。") else out }
                        }
                    },
                    onUse = { },
                ),
            ) { if (made != null) throw CancellationException("做好了") }
        } catch (e: CancellationException) {
            if (made == null) { if (runs[key]?.stage == "stop") return; err = "停下了" }
        } catch (e: Exception) {
            err = (e.message ?: e.javaClass.simpleName).take(60)
        }
        val id = made
        val cur = runs[key] ?: return
        if (id != null) {
            put(cur.copy(stage = "done", done = id))
            val chat = chats[key] ?: Chats.current
            chat?.swapWork(cur.conv, "$PREFIX$key|${cur.title}", id)
            Notify.work(cur.conv, chat?.history, cur.title)
        } else put(cur.copy(stage = "fail", fail = err ?: "整合时没把作品写出来"))
    }
}
