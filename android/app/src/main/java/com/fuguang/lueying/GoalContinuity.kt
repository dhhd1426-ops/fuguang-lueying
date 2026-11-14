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
    enum class Status { ACTIVE, PAUSED, COMPLETED, CANCELLED }
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
    private fun entries(array: JSONArray?): List<String> =
        if (array == null) emptyList() else (0 until array.length()).map { array.optString(it) }

    fun snapshot(id: String): Snapshot = synchronized(lock) {
        val s = read(id)
        Snapshot(
            id, s.optString("goal").takeIf { it.isNotBlank() },
            entries(s.optJSONArray("criteria")),
            s.optString("step").takeIf { it.isNotBlank() },
            runCatching { Status.valueOf(s.optString("status", "ACTIVE")) }.getOrDefault(Status.ACTIVE),
            s.optString("branch").takeIf { it.isNotBlank() },
            s.optString("resume").takeIf { it.isNotBlank() },
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
                .remove("branch").remove("resume")
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
                    s.put("step", s.optString("resume")).remove("branch").remove("resume")
                }
            }
            Intent.SWITCH -> {
                require(!text.isNullOrBlank()) { "Switch needs an explicit new goal" }
                val previous = s.optString("goal")
                if (previous.isNotBlank()) s.put("previous_goal", previous)
                s.put("goal", text.take(2000)).put("criteria", JSONArray())
                    .put("status", Status.ACTIVE.name)
                    .remove("step").remove("branch").remove("resume")
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
        s.put("status", Status.COMPLETED.name).put("completion_evidence", evidence.take(2000))
        persist(id, s)
        event(id, "completed")
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
}
