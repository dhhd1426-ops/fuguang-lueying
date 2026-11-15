package com.fuguang.lueying

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GoalLedgerTest {
    private val data = mutableMapOf<String, String>()
    private val storage = object : GoalContinuity.Store {
        override fun get(key: String) = data[key]
        override fun put(key: String, value: String): Boolean { data[key] = value; return true }
    }
    @Before fun setup() { GoalContinuity.initStore(storage) }
    private fun goal(id: String = "a") = GoalContinuity.setGoal(id, "发布修复", listOf("编译通过"), "修复代码")
    private fun receipt(id: String = "a", failed: Boolean = false): String {
        val token = GoalContinuity.beginTool(id, "build", JSONObject())
        GoalContinuity.finishTool(id, token, ToolOut("build result", failed = failed))
        return token
    }
    private fun rejected(block: () -> Unit) { assertTrue(runCatching(block).isFailure) }

    @Test fun branchConclusionRestoresCheckpointAcrossReload() {
        goal()
        GoalContinuity.route("a", GoalContinuity.Intent.EXPLORE, "比较恢复机制")
        GoalContinuity.updateStep("a", "阅读资料")
        GoalContinuity.initStore(storage)
        assertEquals("修复代码", GoalContinuity.snapshot("a").resumeStep)
        GoalContinuity.finishBranch("a", "保留当前调度机制，补充持久化")
        assertNull(GoalContinuity.snapshot("a").pendingBranch)
        assertEquals("修复代码", GoalContinuity.snapshot("a").step)
        assertTrue(GoalContinuity.evidenceContext("a").contains("补充持久化"))
    }
    @Test fun nestedBranchCannotOverwriteCheckpoint() {
        goal(); GoalContinuity.route("a", GoalContinuity.Intent.EXPLORE, "第一支线")
        rejected { GoalContinuity.route("a", GoalContinuity.Intent.EXPLORE, "第二支线") }
        assertEquals("第一支线", GoalContinuity.snapshot("a").pendingBranch)
    }
    @Test fun fabricatedAndFailedEvidenceCannotComplete() {
        goal()
        rejected { GoalContinuity.completed("a", "已完成") }
        rejected { GoalContinuity.completed("a", "已完成", listOf("invented")) }
        val failed = receipt(failed = true)
        rejected { GoalContinuity.completed("a", "已完成", listOf(failed)) }
        assertEquals(GoalContinuity.Status.ACTIVE, GoalContinuity.snapshot("a").status)
    }
    @Test fun receiptOnlyAllowsPendingVerificationNotVerifiedCompletion() {
        goal(); val evidence = receipt()
        GoalContinuity.completed("a", "实际编译返回，仍需验收", listOf(evidence))
        assertEquals(GoalContinuity.Status.NEEDS_VERIFICATION, GoalContinuity.snapshot("a").status)
    }
    @Test fun evidenceCannotCrossChatsOrGoalEpochs() {
        goal(); goal("b"); val evidence = receipt()
        rejected { GoalContinuity.completed("b", "已完成", listOf(evidence)) }
        GoalContinuity.route("a", GoalContinuity.Intent.SWITCH, "新目标")
        rejected { GoalContinuity.completed("a", "已完成", listOf(evidence)) }
        assertEquals("发布修复", GoalContinuity.snapshot("b").goal)
    }
    @Test fun interruptedOperationCannotReplayAfterReloadOrArgumentReordering() {
        goal()
        val args = JSONObject().put("path", "x").put("value", 1)
        val token = GoalContinuity.beginTool("a", "write", args)
        GoalContinuity.initStore(storage)
        rejected { GoalContinuity.beginTool("a", "write", JSONObject().put("value", 1).put("path", "x")) }
        assertTrue(GoalContinuity.evidenceContext("a").contains("UNKNOWN"))
        val denied = GoalContinuity.runTool("a", JSONObject().put("action", "confirm_retry").put("text", token).put("user_quote", "确认重试"), "继续")
        assertTrue(denied.failed)
        val accepted = GoalContinuity.runTool("a", JSONObject().put("action", "confirm_retry").put("text", token).put("user_quote", "确认重试"), "确认重试")
        assertFalse(accepted.failed)
        GoalContinuity.beginTool("a", "write", args)
    }
    @Test fun pausedOrCancelledWorkCannotAutoResumeFromBranch() {
        goal(); GoalContinuity.route("a", GoalContinuity.Intent.EXPLORE, "资料")
        GoalContinuity.route("a", GoalContinuity.Intent.PAUSE)
        rejected { GoalContinuity.finishBranch("a", "完成研究") }
        assertTrue(GoalContinuity.runTool("a", JSONObject().put("action", "resume"), "先暂停").failed)
        GoalContinuity.route("a", GoalContinuity.Intent.CANCEL)
        rejected { GoalContinuity.route("a", GoalContinuity.Intent.CONTINUE) }
        rejected { GoalContinuity.route("a", GoalContinuity.Intent.PAUSE) }
    }
    @Test fun corruptOrUnwritableLedgerFailsClosed() {
        data["chat_a"] = "{broken"
        rejected { goal() }
        assertEquals("{broken", data["chat_a"])
        GoalContinuity.initStore(object : GoalContinuity.Store {
            override fun get(key: String): String? = null
            override fun put(key: String, value: String) = false
        })
        rejected { goal() }
    }
    @Test fun readOnlyCallsCanRetryAndConcurrentReceiptsRemainDistinct() {
        goal()
        val first = GoalContinuity.beginTool("a", "read", JSONObject(), true)
        val second = GoalContinuity.beginTool("a", "read", JSONObject(), true)
        GoalContinuity.finishTool("a", first, ToolOut("first"))
        GoalContinuity.finishTool("a", second, ToolOut("second"))
        GoalContinuity.beginTool("a", "read", JSONObject(), true)
        GoalContinuity.startTurn("a")
        assertFalse(GoalContinuity.evidenceContext("a").contains("结果未知"))
    }
    @Test fun completionNeedsExplicitUserAcceptance() {
        goal(); val evidence = receipt()
        GoalContinuity.completed("a", "等待用户验收", listOf(evidence))
        val args = JSONObject().put("action", "confirm_complete").put("user_quote", "验收通过")
        assertTrue(GoalContinuity.runTool("a", args, "继续检查").failed)
        assertFalse(GoalContinuity.runTool("a", args, "验收通过").failed)
        assertEquals(GoalContinuity.Status.COMPLETED, GoalContinuity.snapshot("a").status)
    }
}
