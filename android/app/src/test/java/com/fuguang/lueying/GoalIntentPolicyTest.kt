package com.fuguang.lueying

import org.junit.Assert.*
import org.junit.Test

class GoalIntentPolicyTest {
    @Test fun continuePreservesMainTask() {
        assertEquals(GoalIntentPolicy.Hint.CONTINUE, GoalIntentPolicy.hint("继续跟进"))
        assertEquals(GoalIntentPolicy.Hint.CONTINUE, GoalIntentPolicy.hint("继续"))
    }

    @Test fun temporaryResearchIsNotGoalSwitch() {
        assertEquals(GoalIntentPolicy.Hint.EXPLORE, GoalIntentPolicy.hint("看看 GitHub 有没有类似方案"))
        assertFalse(GoalIntentPolicy.authorizes("switch", "看看 Claude 有没有类似方案", "看看 Claude"))
    }

    @Test fun hypotheticalNeverAuthorizesSwitchOrCancellation() {
        assertEquals(GoalIntentPolicy.Hint.UNSURE, GoalIntentPolicy.hint("如果我说不做了会怎样？"))
        assertFalse(GoalIntentPolicy.authorizes("cancel", "如果我说不做了会怎样？", "不做了"))
        assertFalse(GoalIntentPolicy.authorizes("switch", "假如改做绘画模块会怎样", "改做绘画模块"))
    }

    @Test fun explicitGoalChangeRequiresCurrentUserQuote() {
        val message = "后台任务先不做了，改做绘画模块"
        assertEquals(GoalIntentPolicy.Hint.CANCEL, GoalIntentPolicy.hint(message))
        assertFalse(GoalIntentPolicy.authorizes("switch", message, "改做绘画模块"))
        assertTrue(GoalIntentPolicy.authorizes("cancel", message, "先不做了"))
        assertTrue(GoalIntentPolicy.authorizes("switch", "换个目标，改做绘画模块", "改做绘画模块"))
        assertFalse(GoalIntentPolicy.authorizes("switch", "换个目标，改做绘画模块", "不存在的用户原话"))
    }

    @Test fun differentChatsDoNotImplyASwitch() {
        assertEquals(GoalIntentPolicy.Hint.UNSURE, GoalIntentPolicy.hint("你现在进行到哪里了？"))
        assertFalse(GoalIntentPolicy.authorizes("cancel", "取消的话会怎样", "取消的话"))
    }
}
