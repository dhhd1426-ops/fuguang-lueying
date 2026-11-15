package com.fuguang.lueying

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StepsTest {
    private fun list(vararg items: Pair<String, String>) = JSONObject().put("items",
        JSONArray().apply { items.forEach { (t, s) -> put(JSONObject().put("text", t).put("state", s)) } })

    @Test fun onlyOneThingAtATime() {
        val out = Steps.run("t1", list("改按钮" to Steps.DOING, "写说明" to Steps.DOING))
        assertTrue(out.failed)
        assertEquals("", Steps.note("t1"))
    }

    @Test fun emptyListAddsNothing() {
        assertEquals("", Steps.note("t2"))
        Steps.run("t2", list("改按钮" to Steps.DONE))
        assertEquals("", Steps.note("t2"))
    }

    @Test fun sideQuestionKeepsTheList() {
        Steps.run("t3", list("改按钮" to Steps.DOING, "写说明" to Steps.PENDING))
        Steps.run("t3", list("查一下别家怎么做" to Steps.DOING, "改按钮" to Steps.PENDING, "写说明" to Steps.PENDING))
        val note = Steps.note("t3")
        assertTrue(note.contains("▶ 查一下别家怎么做"))
        assertTrue(note.contains("☐ 改按钮"))
        assertFalse(note.contains("换掉的"))
    }

    @Test fun replacedListStaysForAFewTurns() {
        Steps.run("t4", list("改按钮" to Steps.DOING))
        Steps.run("t4", list("订票" to Steps.DOING))
        assertTrue(Steps.note("t4").contains("换掉的：改按钮"))
        repeat(6) { Steps.startTurn("t4") }
        assertFalse(Steps.note("t4").contains("换掉的"))
    }

    @Test fun nudgeOnlyOnceAndOnlyWhenSomethingIsInProgress() {
        assertNull(Steps.Nudge().beforeFinish("t5", false))
        Steps.run("t5", list("改按钮" to Steps.DOING))
        val n = Steps.Nudge()
        assertNull(n.beforeFinish("t5", true))
        assertNotNull(n.beforeFinish("t5", false))
        assertNull(n.beforeFinish("t5", false))
        val touched = Steps.Nudge().apply { touched = true }
        assertNull(touched.beforeFinish("t5", false))
    }
}
