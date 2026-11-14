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

    private lateinit var application: Context
    private val lock = Any()
    private val prefs get() = application.getSharedPreferences("goal_continuity_v1", Context.MODE_PRIVATE)

    fun init(context: Context) { synchronized(lock) { application = context.applicationContext } }
    private fun key(id: String) = "chat_" + id
    private fun read(id: String): JSONObject {
        val raw = prefs.getString(key(id), null)
        return runCatching { if (raw == null) JSONObject() else JSONObject(raw) }.getOrDefault(JSONObject())
    }
    private fun persist(id: String, state: JSONObject) {
        state.put("version", 1)
        state.put("revision", state.optLong("revision", 0) + 1)
        // commit is synchronous: checkpoint is on disk before the UI reports success.
        check(prefs.edit().putString(key(id), state.toString()).commit()) { "Goal checkpoint could not be saved" }
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
            if (!prefs.contains(key(id))) persist(id, JSONObject().put("status", Status.ACTIVE.name))
        }
    }
    fun setGoal(id: String, description: String, criteria: List<String>, step: String? = null) {
        require(id.isNotBlank() && description.isNotBlank())
        synchronized(lock) {
            val s = read(id)
            s.put("goal", description.trim()).put("criteria", JSONArray(criteria))
                .put("status", Status.ACTIVE.name)
                .put("step", step ?: JSONObject.NULL)
                
            s.remove("branch"); s.remove("resume")
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
                require(!text.isNullOrBlank()) { "Branch needs a purpose" }
                if (s.optString("branch").isBlank()) {
                    s.put("resume", s.optString("step")).put("branch", text.take(500))
                }
            }
            Intent.PAUSE -> s.put("status", Status.PAUSED.name)
            Intent.CANCEL -> s.put("status", Status.CANCELLED.name)
            Intent.CONTINUE -> {
                s.put("status", Status.ACTIVE.name)
                if (s.optString("branch").isNotBlank()) {
                    s.put("step", s.optString("resume"))
                    s.remove("branch"); s.remove("resume")
                }
            }
            Intent.SWITCH -> {
                require(!text.isNullOrBlank()) { "Switch needs an explicit new goal" }
                val previous = s.optString("goal")
                if (previous.isNotBlank()) s.put("previous_goal", previous)
                s.put("goal", text.take(2000)).put("criteria", JSONArray())
                    .put("status", Status.ACTIVE.name)
                    
                s.remove("step"); s.remove("branch"); s.remove("resume")
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
        s.put("step", step.take(500))
        persist(id, s)
        event(id, "step_updated")
    }
    fun completed(id: String, evidence: String) = synchronized(lock) {
        require(evidence.isNotBlank()) { "Completion requires verifiable evidence" }
        val s = read(id)
        s.put("status", Status.NEEDS_VERIFICATION.name).put("completion_claim", evidence.take(2000))
        persist(id, s)
        event(id, "completion_claimed")
    }
    /** Store event types only; no model output, credentials, or prompt text in logs. */
    private fun event(id: String, type: String) {
        val name = "events_" + id
        val list = runCatching { JSONArray(prefs.getString(name, "[]")) }.getOrDefault(JSONArray())
        list.put(JSONObject().put("at", System.currentTimeMillis()).put("type", type))
        val kept = JSONArray()
        for (i in maxOf(0, list.length() - 80) until list.length()) kept.put(list.get(i))
        check(prefs.edit().putString(name, kept.toString()).commit())
    }

    /** Context for the model. Facts are read from durable state, not chat summaries. */
    fun rule(id: String?): String {
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
            append("""
            
            # 防偏航原则
            - 用户提出关联问题、查资料、比较方案、质疑设计时，保留既定主线，把它当临时探索。得出结论后回归主线，不无限扩展支线。
            - 用户说「继续」时，优先恢复上一次尚未完成的主线步骤；不凭空声称已执行。
            - 用户明确要求暂停、取消或改为另一项任务时，服从最新指令；不要强迫用户回到旧任务。
            - 修改方案不等于替换目标。行动先检查是否真正推进验收条件；信息足够就执行，不要无止境分析。
            - 对长期任务用 manage_goal 维护目标和步骤。只有用户明确改变主线才允许 switch；完成须有实际验证证据。
            - 当前账本只是计划与记录，不能证明工具已执行、测试通过或成果已交付。
            """.trimIndent())
        }
    }

    val spec get() = ToolSpec(
        "manage_goal",
        "管理当前对话的长期主线任务。开始长任务时 set_goal，执行进度更新 step，临时调研 branch，回到主线 resume，明确暂停 pause、取消 cancel，明确换目标 switch。普通闲聊不必调用。完成需提交实际验证依据，账本会标记为待验收，不能自行宣布已验证完成。",
        JSONObject().put("type", "object")
            .put("properties", JSONObject()
                .put("action", JSONObject().put("type", "string").put("enum",
                    JSONArray(listOf("set_goal", "step", "branch", "resume", "pause", "cancel", "switch", "complete", "status"))))
                .put("text", JSONObject().put("type", "string").put("description", "目标、步骤、支线问题或已核实的完成证据"))
                .put("criteria", JSONObject().put("type", "array").put("items",
                    JSONObject().put("type", "string")))
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
                require(quote.length >= 3 && latestUser.contains(quote)) {
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
                "resume" -> route(id, Intent.CONTINUE)
                "pause" -> route(id, Intent.PAUSE)
                "cancel" -> route(id, Intent.CANCEL)
                "switch" -> route(id, Intent.SWITCH, body)
                "complete" -> completed(id, body)
                "status" -> Unit
                else -> error("未知目标动作")
            }
            val s = snapshot(id)
            ToolOut("目标账本已核实：主线=" + (s.goal ?: "未设置") +
                "；状态=" + s.status.name + "；步骤=" + (s.step ?: "未设置") +
                "；临时分支=" + (s.pendingBranch ?: "无") + "；版本=" + s.revision +
                "。这仅确认任务账本更新，不代表执行、编译或交付已经完成。")
        }.getOrElse { ToolOut("目标账本更新失败：" + (it.message ?: it.javaClass.simpleName).take(160)) }
    }

}
