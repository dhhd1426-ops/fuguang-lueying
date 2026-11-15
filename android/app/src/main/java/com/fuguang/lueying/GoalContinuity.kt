package com.fuguang.lueying

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable per-conversation goal ledger. This is a fact store, NOT a replacement
 * for the model's planning. The model must explicitly classify user intent;
 * casual questions must never silently replace the primary goal.
 */
internal object GoalContinuity {
    enum class Intent { CONTINUE, REFINE, EXPLORE, PAUSE, SWITCH, CANCEL }
    enum class Status { ACTIVE, PAUSED, NEEDS_VERIFICATION, COMPLETED, CANCELLED }
    data class Snapshot(
        val conversationId: String,
        val goal: String?,
        val successCriteria: List<String>,
        val step: String?,
        val status: Status,
        val pendingBranch: String?,
        val resumeStep: String?,
        val revision: Long
    )
    data class Route(val intent: Intent, val preserveMainGoal: Boolean, val nextStep: String?)

    internal interface Store {
        fun get(key: String): String?
        fun put(key: String, value: String): Boolean
    }
    private lateinit var store: Store
    private val lock = Any()
    fun init(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences("goal_continuity_v1", Context.MODE_PRIVATE)
        initStore(object : Store {
            override fun get(key: String) = prefs.getString(key, null)
            override fun put(key: String, value: String) = prefs.edit().putString(key, value).commit()
        })
    }
    internal fun initStore(storage: Store) { synchronized(lock) { store = storage } }
    private fun key(id: String) = "chat_" + id
    private fun read(id: String): JSONObject {
        val raw = store.get(key(id))
        return if (raw == null) JSONObject() else JSONObject(raw) // Fail closed: never overwrite a corrupt ledger.
    }
    private fun persist(id: String, state: JSONObject) {
        state.put("version", 1)
        state.put("revision", state.optLong("revision", 0) + 1)
        // commit is synchronous: checkpoint is on disk before the UI reports success.
        check(store.put(key(id), state.toString())) { "Goal checkpoint could not be saved" }
    }
    private fun value(s: JSONObject, key: String): String? =
        if (s.isNull(key)) null else s.optString(key).takeIf { it.isNotBlank() }
    private fun entries(array: JSONArray?): List<String> =
        if (array == null) emptyList() else (0 until array.length()).map { array.optString(it) }

    fun snapshot(id: String): Snapshot = synchronized(lock) {
        val s = read(id)
        Snapshot(
            id, value(s, "goal"),
            entries(s.optJSONArray("criteria")),
            value(s, "step"),
            runCatching { Status.valueOf(s.optString("status", "ACTIVE")) }.getOrDefault(Status.ACTIVE),
            value(s, "branch"),
            value(s, "resume"),
            s.optLong("revision")
        )
    }
    /** Starting a chat doesn't invent a goal or replace another chat's goal. */
    fun touch(id: String?) {
        if (id.isNullOrBlank()) return
        synchronized(lock) {
            if (store.get(key(id)) == null) persist(id, JSONObject().put("status", Status.ACTIVE.name))
        }
    }
    fun setGoal(id: String, description: String, criteria: List<String>, step: String? = null) {
        require(id.isNotBlank() && description.isNotBlank())
        synchronized(lock) {
            val s = read(id)
            resetEvidence(s)
            s.put("goal", description.trim()).put("criteria", JSONArray(criteria))
                .put("status", Status.ACTIVE.name)
                .put("step", step ?: JSONObject.NULL)
                
            s.remove("branch"); s.remove("resume"); s.remove("branch_step")
            persist(id, s)
            event(id, "goal_set")
        }
    }
    /** Only an explicit user switch can replace a primary goal. */
    fun route(id: String, intent: Intent, text: String? = null): Route = synchronized(lock) {
        require(id.isNotBlank())
        val s = read(id)
        when (intent) {
            Intent.EXPLORE -> {
                requireActive(s)
                require(!text.isNullOrBlank()) { "Branch needs a purpose" }
                require(value(s, "branch") == null || value(s, "branch") == text.take(500)) { "请先结束当前支线，避免覆盖恢复点" }
                if (value(s, "branch") == null) {
                    s.put("resume", value(s, "step") ?: JSONObject.NULL).put("branch", text.take(500))
                }
            }
            Intent.PAUSE -> {
                require(s.optString("status") !in listOf("CANCELLED", "COMPLETED")) { "已结束的目标不能通过暂停重新激活" }
                s.put("status", Status.PAUSED.name)
            }
            Intent.CANCEL -> s.put("status", Status.CANCELLED.name)
            Intent.CONTINUE -> {
                require(s.optString("status") !in listOf("CANCELLED", "COMPLETED")) { "已取消或完成的主线不能自动恢复；请明确切换目标" }
                s.put("status", Status.ACTIVE.name)
                if (value(s, "branch") != null) {
                    s.put("step", value(s, "resume") ?: JSONObject.NULL)
                    s.remove("branch"); s.remove("resume"); s.remove("branch_step")
                }
            }
            Intent.SWITCH -> {
                require(!text.isNullOrBlank()) { "Switch needs an explicit new goal" }
                val previous = value(s, "goal").orEmpty()
                if (previous.isNotBlank()) s.put("previous_goal", previous)
                resetEvidence(s)
                s.put("goal", text.take(2000)).put("criteria", JSONArray())
                    .put("status", Status.ACTIVE.name)
                    
                s.remove("step"); s.remove("branch"); s.remove("resume"); s.remove("branch_step")
            }
            Intent.REFINE -> Unit
        }
        persist(id, s)
        event(id, "route_" + intent.name.lowercase())
        Route(intent, intent != Intent.SWITCH && intent != Intent.CANCEL,
            s.optString("step").takeIf { it.isNotBlank() })
    }
    fun updateStep(id: String, step: String) = synchronized(lock) {
        require(step.isNotBlank())
        val s = read(id)
        requireActive(s)
        s.put(if (value(s, "branch") == null) "step" else "branch_step", step.take(500))
        persist(id, s)
        event(id, "step_updated")
    }
    private fun requireActive(s: JSONObject) {
        require(value(s, "goal") != null && s.optString("status") == Status.ACTIVE.name) { "主线未处于可执行状态" }
    }
    private fun resetEvidence(s: JSONObject) {
        s.put("epoch", java.util.UUID.randomUUID().toString())
        s.remove("receipts"); s.remove("completion_claim"); s.remove("completion_refs")
        s.remove("branch_step"); s.remove("branch_result")
        // Pending operations survive a goal switch: their external effects may still exist.
    }
    private fun digest(text: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun canonical(v: Any?): String = when (v) {
        is JSONObject -> v.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(v.get(it)) }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canonical(v.get(it)) }
        is String -> JSONObject.quote(v)
        else -> v.toString()
    }
    fun startTurn(id: String) = synchronized(lock) {
        val s = read(id)
        val pending = s.optJSONObject("pending_tools") ?: return@synchronized
        val staleReads = pending.keys().asSequence().filter { pending.getJSONObject(it).optBoolean("read_only") }.toList()
        if (staleReads.isNotEmpty()) {
            staleReads.forEach { pending.remove(it) }
            persist(id, s)
        }
    }
    /** Written BEFORE dispatch. Unknown outcomes remain pending after cancellation/restart. */
    fun beginTool(id: String, name: String, args: JSONObject, readOnly: Boolean = false): String = synchronized(lock) {
        val s = read(id)
        val fingerprint = digest(name + canonical(args))
        val pending = s.optJSONObject("pending_tools") ?: JSONObject()
        require(readOnly || !pending.has(fingerprint)) { "相同工具上次结果尚未确认，禁止自动重放。请先查询实际状态；确认后由用户决定是否重试。" }
        val token = java.util.UUID.randomUUID().toString()
        pending.put(if (readOnly) token else fingerprint, JSONObject().put("id", token).put("tool", name)
            .put("epoch", s.optString("epoch")).put("read_only", readOnly).put("at", System.currentTimeMillis()))
        s.put("pending_tools", pending)
        persist(id, s)
        token
    }
    /** Only the runtime can create receipts. A returned result is NOT proof of business success. */
    fun finishTool(id: String, token: String, out: ToolOut): String = synchronized(lock) {
        val s = read(id)
        val pending = s.optJSONObject("pending_tools") ?: error("Missing operation checkpoint")
        val key = pending.keys().asSequence().firstOrNull { pending.getJSONObject(it).optString("id") == token }
            ?: error("Unknown operation checkpoint")
        val receipt = pending.getJSONObject(key)
        receipt.put("outcome", if (out.failed) "FAILED" else "RETURNED")
            .put("result_sha256", digest(out.text)).put("finished_at", System.currentTimeMillis())
        pending.remove(key)
        val old = s.optJSONArray("receipts") ?: JSONArray()
        val kept = JSONArray()
        for (i in maxOf(0, old.length() - 39) until old.length()) kept.put(old.get(i))
        kept.put(receipt); s.put("receipts", kept)
        persist(id, s)
        token
    }
    fun finishBranch(id: String, conclusion: String) = synchronized(lock) {
        require(conclusion.isNotBlank()) { "结束支线需要记录结论或阻塞原因" }
        val s = read(id)
        requireActive(s)
        require(value(s, "branch") != null) { "没有活动支线" }
        s.put("branch_result", conclusion.take(1000))
        s.put("step", value(s, "resume") ?: JSONObject.NULL)
        s.remove("branch"); s.remove("resume"); s.remove("branch_step")
        persist(id, s)
    }
    fun completed(id: String, evidence: String, refs: List<String> = emptyList()) = synchronized(lock) {
        require(evidence.isNotBlank()) { "Completion requires verifiable evidence" }
        val s = read(id)
        requireActive(s)
        require(value(s, "branch") == null) { "请先结束支线并回到主线验收" }
        val receipts = s.optJSONArray("receipts") ?: JSONArray()
        require(refs.isNotEmpty() && refs.all { ref ->
            (0 until receipts.length()).any { i -> receipts.getJSONObject(i).let {
                it.optString("id") == ref && it.optString("epoch") == s.optString("epoch") && it.optString("outcome") == "RETURNED"
            } }
        }) { "必须引用当前目标的真实工具回执 ID；模型文字、失败回执或旧目标证据不算验证" }
        require(s.optJSONObject("pending_tools")?.let { pending -> pending.keys().asSequence().none { !pending.getJSONObject(it).optBoolean("read_only") } } ?: true) { "仍有执行结果未知的操作，不能提交完成验收" }
        s.put("status", Status.NEEDS_VERIFICATION.name).put("completion_claim", evidence.take(2000))
            .put("completion_refs", JSONArray(refs))
        persist(id, s)
        event(id, "completion_claimed")
    }

    fun evidenceContext(id: String): String = synchronized(lock) {
        val s = read(id)
        val receipts = s.optJSONArray("receipts") ?: JSONArray()
        val pending = s.optJSONObject("pending_tools") ?: JSONObject()
        buildString {
            append("工具回执（RETURNED 仅表示返回，必须结合原始结果核验成功条件）：")
            for (i in maxOf(0, receipts.length() - 8) until receipts.length()) {
                val r = receipts.getJSONObject(i)
                if (r.optString("epoch") == s.optString("epoch")) append("\n").append(r.optString("id")).append(" ").append(r.optString("tool")).append(" ").append(r.optString("outcome"))
            }
            pending.keys().asSequence().filter { !pending.getJSONObject(it).optBoolean("read_only") }.forEach { key ->
                val operation = pending.getJSONObject(key)
                append("\nUNKNOWN ").append(operation.optString("id")).append(" ").append(operation.optString("tool"))
            }
            val unknown = pending.keys().asSequence().count { !pending.getJSONObject(it).optBoolean("read_only") }
            if (unknown > 0) append("\n有 ").append(unknown).append(" 个操作结果未知：先查询外部状态，勿重复执行。")
            value(s, "branch_result")?.let { append("\n最近支线结论：").append(it) }
        }
    }
    /** Store event types only; no model output, credentials, or prompt text in logs. */
    private fun event(id: String, type: String) {
        val name = "events_" + id
        val list = runCatching { JSONArray(store.get(name) ?: "[]") }.getOrDefault(JSONArray())
        list.put(JSONObject().put("at", System.currentTimeMillis()).put("type", type))
        val kept = JSONArray()
        for (i in maxOf(0, list.length() - 80) until list.length()) kept.put(list.get(i))
        check(store.put(name, kept.toString()))
    }

    /** Context for the model. Facts are read from durable state, not chat summaries. */
    fun rule(id: String?, latestUser: String = ""): String {
        if (id.isNullOrBlank()) return ""
        val s = snapshot(id)
        return buildString {
            append("\n\n# 当前对话的工作目标（本地持久化任务账本）")
            if (s.goal == null) append("\n尚未建立明确主线。普通聊天无需创建任务。")
            else {
                append("\n主线：").append(s.goal)
                if (s.successCriteria.isNotEmpty()) append("\n验收：").append(s.successCriteria.joinToString("；"))
                if (s.step != null) append("\n正在进行：").append(s.step)
                append("\n状态：").append(s.status.name)
                if (s.pendingBranch != null) append("\n当前探索支线：").append(s.pendingBranch)
                if (s.resumeStep != null) append("\n支线结束后返回：").append(s.resumeStep)
            }
            append("\n").append(evidenceContext(id))
            if (latestUser.isNotBlank()) append("\n本轮意图提醒：").append(GoalIntentPolicy.modelNote(latestUser))
            append("""
            
            # 防偏航原则
            - 用户提出关联问题、查资料、比较方案、质疑设计时，保留既定主线，把它当临时探索。得出结论后回归主线，不无限扩展支线。
            - 用户说「继续」时，优先恢复上一次尚未完成的主线步骤；不凭空声称已执行。
            - 用户明确要求暂停、取消或改为另一项任务时，服从最新指令；不要强迫用户回到旧任务。
            - 修改方案不等于替换目标。行动先检查是否真正推进验收条件；信息足够就执行，不要无止境分析。
            - 对长期任务用 manage_goal 维护目标和步骤。只有用户明确改变主线才允许 switch；完成须有实际验证证据。
            - 支线得到结论后调用 finish_branch，工具会恢复主线步骤；随后继续实际执行，不要结束在研究报告。每次模型调用独立思考，是否继续研究由新证据和验收目标决定。
            - 长时间只有状态更新或调研而没有推进时，选择可执行的下一步；确实受阻就记录阻塞原因，禁止伪造行动。
            - 当前账本只是计划与记录，不能证明工具已执行、测试通过或成果已交付。
            """.trimIndent())
        }
    }

    val spec get() = ToolSpec(
        "manage_goal",
        "管理当前对话的长期主线任务。开始长任务时 set_goal，执行进度更新 step，临时调研 branch，支线结束 finish_branch（text 为结论），回到主线 resume，明确暂停 pause、取消 cancel，明确换目标 switch。普通闲聊不必调用。完成需提交实际验证依据，账本会标记为待验收，不能自行宣布已验证完成。confirm_complete 仅在用户明确回复验收通过后标记完成。confirm_retry 仅在用户明确回复确认重试后解除 text 指定的未知操作 ID。",
        JSONObject().put("type", "object")
            .put("properties", JSONObject()
                .put("action", JSONObject().put("type", "string").put("enum",
                    JSONArray(listOf("set_goal", "step", "branch", "finish_branch", "resume", "pause", "cancel", "switch", "complete", "confirm_complete", "confirm_retry", "status"))))
                .put("text", JSONObject().put("type", "string").put("description", "目标、步骤、支线问题或已核实的完成证据"))
                .put("criteria", JSONObject().put("type", "array").put("items",
                    JSONObject().put("type", "string")))
                .put("evidence_ids", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "complete 必须引用当前目标真实工具回执 ID"))
                .put("user_quote", JSONObject().put("type", "string")
                    .put("description", "switch/cancel 时引用本轮用户原话，以便核对授权")))
            .put("required", JSONArray(listOf("action")))
    )

    fun runTool(id: String?, args: JSONObject, latestUser: String): ToolOut {
        if (id.isNullOrBlank()) return ToolOut("对话尚未建立，无法记录目标；请在本轮消息持久化后重试。")
        val action = args.optString("action")
        val body = args.optString("text").trim()
        return runCatching {
            if (action == "switch" || action == "cancel") {
                val quote = args.optString("user_quote").trim()
                require(quote.length >= 3 && latestUser.contains(quote) && GoalIntentPolicy.authorizes(action, latestUser, quote)) {
                    "更换或取消主线必须来自用户本轮明确指令，不得根据模型推断自行决定。"
                }
            }
            when (action) {
                "set_goal" -> {
                    require(body.isNotBlank())
                    require(snapshot(id).goal == null) { "主线已存在；请用 step/refine，明确切换才用 switch" }
                    val arr = args.optJSONArray("criteria")
                    setGoal(id, body, entries(arr), body.take(120))
                }
                "step" -> updateStep(id, body)
                "branch" -> route(id, Intent.EXPLORE, body)
                "finish_branch" -> finishBranch(id, body)
                "resume" -> {
                    val state = snapshot(id)
                    require(state.status == Status.ACTIVE || GoalIntentPolicy.hint(latestUser) == GoalIntentPolicy.Hint.CONTINUE) { "暂停后需要用户明确继续" }
                    route(id, Intent.CONTINUE)
                }
                "pause" -> route(id, Intent.PAUSE)
                "cancel" -> route(id, Intent.CANCEL)
                "switch" -> route(id, Intent.SWITCH, body)
                "complete" -> completed(id, body, entries(args.optJSONArray("evidence_ids")))
                "confirm_complete" -> {
                    require(latestUser.trim() == "验收通过" && args.optString("user_quote") == "验收通过") { "需要用户明确回复「验收通过」" }
                    synchronized(lock) {
                        val state = read(id)
                        require(state.optString("status") == Status.NEEDS_VERIFICATION.name) { "当前任务尚未提交验收" }
                        val pending = state.optJSONObject("pending_tools")
                        require(pending == null || pending.keys().asSequence().none { !pending.getJSONObject(it).optBoolean("read_only") }) { "验收后又有结果未知的操作，需先核实" }
                        state.put("status", Status.COMPLETED.name)
                        persist(id, state)
                    }
                }
                "confirm_retry" -> {
                    val quote = args.optString("user_quote").trim()
                    require(quote == "确认重试" && latestUser.trim() == quote) { "结果未知的操作必须由用户明确回复「确认重试」后才能解除重放保护" }
                    synchronized(lock) {
                        val state = read(id)
                        val pending = state.optJSONObject("pending_tools") ?: JSONObject()
                        val fingerprint = pending.keys().asSequence().firstOrNull { pending.getJSONObject(it).optString("id") == body }
                            ?: error("请提供待确认操作的 ID")
                        pending.remove(fingerprint)
                        persist(id, state)
                    }
                }
                "status" -> Unit
                else -> error("未知目标动作")
            }
            val s = snapshot(id)
            ToolOut("目标账本已核实：主线=" + (s.goal ?: "未设置") +
                "；状态=" + s.status.name + "；步骤=" + (s.step ?: "未设置") +
                "；临时分支=" + (s.pendingBranch ?: "无") + "；版本=" + s.revision +
                "。这仅确认任务账本更新，不代表执行、编译或交付已经完成。\n" + evidenceContext(id))
        }.getOrElse { ToolOut("目标账本更新失败：" + (it.message ?: it.javaClass.simpleName).take(160), failed = true) }
    }

}
