package com.fuguang.lueying

import org.junit.Assert.*
import org.junit.Test

class GoalTurnGuardTest {
    @Test fun branchNudgeIsBoundedAndDoesNotOverridePauseOrUserInput() {
        val guard = GoalTurnGuard()
        assertNull(guard.beforeFinish(false, true, false))
        assertNull(guard.beforeFinish(true, true, true))
        assertNull(guard.beforeFinish(true, false, false))
        assertNotNull(guard.beforeFinish(true, true, false))
        assertNull(guard.beforeFinish(true, true, false))
    }
    @Test fun bookkeepingLoopGetsOneReminderAndRealActionResetsCount() {
        val guard = GoalTurnGuard()
        repeat(3) { assertNull(guard.afterTool(true)) }
        assertNull(guard.afterTool(false))
        repeat(3) { assertNull(guard.afterTool(true)) }
        assertNotNull(guard.afterTool(true))
        repeat(10) { assertNull(guard.afterTool(true)) }
    }
    @Test fun finishingBranchStillRequiresMainlineAction() {
        val guard = GoalTurnGuard()
        guard.afterTool(true, resumed = true)
        assertNotNull(guard.beforeFinish(true, false, false))
        val working = GoalTurnGuard()
        working.afterTool(true, resumed = true)
        working.afterTool(false)
        assertNull(working.beforeFinish(true, false, false))
    }
}
