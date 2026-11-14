package com.fuguang.lueying

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GoalContinuityTest {
    private fun fresh(): String {
        GoalContinuity.init(InstrumentationRegistry.getInstrumentation().targetContext)
        return "test-" + UUID.randomUUID().toString()
    }

    @Test fun separateChatsKeepIndependentGoalsAcrossReinitialization() {
        val a = fresh()
        val b = fresh()
        GoalContinuity.setGoal(a, "修复后台恢复", listOf("通过实际恢复测试"), "检查生命周期")
        GoalContinuity.setGoal(b, "设计绘画工作流", listOf("完成自检返工"), "制定方案")
        GoalContinuity.init(InstrumentationRegistry.getInstrumentation().targetContext)
        assertEquals("修复后台恢复", GoalContinuity.snapshot(a).goal)
        assertEquals("设计绘画工作流", GoalContinuity.snapshot(b).goal)
        assertEquals("检查生命周期", GoalContinuity.snapshot(a).step)
    }

    @Test fun branchResumesOriginalStepWithoutReplacingGoal() {
        val id = fresh()
        GoalContinuity.setGoal(id, "修复后台恢复", emptyList(), "编写状态恢复")
        GoalContinuity.route(id, GoalContinuity.Intent.EXPLORE, "参考其他客户端")
        assertEquals("参考其他客户端", GoalContinuity.snapshot(id).pendingBranch)
        GoalContinuity.route(id, GoalContinuity.Intent.CONTINUE)
        assertNull(GoalContinuity.snapshot(id).pendingBranch)
        assertEquals("编写状态恢复", GoalContinuity.snapshot(id).step)
        assertEquals("修复后台恢复", GoalContinuity.snapshot(id).goal)
    }

    @Test fun goalSwitchRequiresQuotedLatestUserInstruction() {
        val id = fresh()
        GoalContinuity.setGoal(id, "旧目标", emptyList())
        val denied = GoalContinuity.runTool(id,
            JSONObject().put("action", "switch").put("text", "新目标")
                .put("user_quote", "去做新任务"), "看看有没有更好的实现方案")
        assertTrue(denied.text.contains("失败"))
        assertEquals("旧目标", GoalContinuity.snapshot(id).goal)
        GoalContinuity.runTool(id,
            JSONObject().put("action", "switch").put("text", "新目标")
                .put("user_quote", "旧目标先暂停，改做新目标"), "旧目标先暂停，改做新目标")
        assertEquals("新目标", GoalContinuity.snapshot(id).goal)
    }

    @Test fun completionClaimDoesNotPretendVerificationPassed() {
        val id = fresh()
        GoalContinuity.setGoal(id, "验证修复", emptyList())
        GoalContinuity.runTool(id, JSONObject().put("action", "complete").put("text", "模型声称已编译"), "继续")
        assertEquals(GoalContinuity.Status.NEEDS_VERIFICATION, GoalContinuity.snapshot(id).status)
        assertFalse(GoalContinuity.snapshot(id).status == GoalContinuity.Status.COMPLETED)
    }
}
