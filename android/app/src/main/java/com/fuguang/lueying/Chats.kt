package com.fuguang.lueying

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 手上这段对话，和在后台接着回答的那几段（照 Claude App）：她在回答时你切到别的对话或开新对话，
 * 这一轮不停，留在后台写完、存回原来那段；侧栏那段旁边亮小橙点。切回去就接着看她写。
 */
object Chats {
    var current by mutableStateOf<ChatState?>(null)
        private set
    /** 在后台回答的：对话 id → 那一段的状态。 */
    val running = mutableStateMapOf<String, ChatState>()

    fun init(history: ConvStore): ChatState = current ?: ChatState(history).also { current = it }

    /** 这段对话她是不是正在回答（手上这段或后台的）。 */
    fun isRunning(id: String): Boolean = running.containsKey(id) || current?.let { it.busy && it.convId == id } == true

    /** 打开一段对话：手上这段她还在写，就留在后台写完。 */
    fun open(c: Conv) {
        val cur = current ?: return
        if (cur.convId == c.id) return
        running.remove(c.id)?.let { back -> park(cur); back.onScreen = true; current = back; return }
        if (cur.busy) { park(cur); current = ChatState(cur.history).also { it.open(c) } } else cur.open(c)
    }

    /** 开新对话：手上这段她还在写，就留在后台写完。 */
    fun fresh() {
        val cur = current ?: return
        if (cur.busy) { park(cur); current = ChatState(cur.history) } else cur.reset()
    }

    private fun park(s: ChatState) {
        val id = s.convId
        if (!s.busy || id == null) return
        s.onScreen = false
        running[id] = s
    }

    /** 后台那段写完了：从后台名单里拿掉（它已经存回自己的对话）。 */
    fun finished(s: ChatState) {
        val id = s.convId ?: return
        if (running[id] === s) running.remove(id)
    }
}
