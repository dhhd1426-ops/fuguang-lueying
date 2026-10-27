package com.fuguang.lueying

import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 记忆页里她写的那段话（Mem.diary）：每记下一件事，请帮手照 Voice.helper 把它写成她自己的一小段。
 * 旧的那些在打开记忆页时一条条补写，写好就存下，不重复写。聊天时她读到的仍是短句（省字）。
 * 他亲手写的：原话不改，只在前面加一句时间。
 */
object Diary {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    /** 正在写的那几条（记忆页上闪一下）。 */
    val writing = mutableStateListOf<String>()
    private val queue = LinkedHashSet<Pair<String, String>>()
    private var running = false

    /** 新记下一条：排进去写。 */
    fun later(p: Persona, m: Mem) {
        if (m.diary.isNotBlank() || m.isSelf) return
        queue += p.id to m.id; pump()
    }

    /** 打开记忆页：还没写过的都排进去。 */
    fun backfill(p: Persona) {
        p.items.filter { it.diary.isBlank() && !it.isSelf }.forEach { queue += p.id to it.id }
        pump()
    }

    private fun pump() {
        if (running) return
        running = true
        scope.launch {
            try {
                while (queue.isNotEmpty()) {
                    val next = queue.first(); queue.remove(next)
                    val p = Memory.get(next.first) ?: continue
                    val m = p.items.firstOrNull { it.id == next.second } ?: continue
                    if (m.diary.isNotBlank()) continue
                    writing += m.id
                    val d = runCatching { write(p, m) }.getOrNull()
                    writing -= m.id
                    // 写不出来（没配 Key、网络断了）：这次先停，下次打开再补
                    if (d.isNullOrBlank()) { queue.clear(); break }
                    val i = p.items.indexOfFirst { it.id == m.id }
                    if (i >= 0) { p.items[i] = p.items[i].copy(diary = d); Memory.save() }
                }
            } finally { running = false }
        }
    }

    /** 记下的时间：2026年10月25日中午。 */
    fun stamp(at: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = at }
        val part = when (c.get(Calendar.HOUR_OF_DAY)) { in 0..4 -> "凌晨"; in 5..7 -> "清晨"; in 8..10 -> "上午"; in 11..13 -> "中午"; in 14..16 -> "下午"; in 17..18 -> "傍晚"; in 19..22 -> "晚上"; else -> "深夜" }
        return SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(Date(at)) + part
    }

    private suspend fun write(p: Persona, m: Mem): String? {
        // 他亲手写的：原话不改，前面加一句时间
        if (m.by == "you") return SimpleDateFormat("M月d日", Locale.CHINA).format(Date(m.at)) + "，他亲手写下：" + m.text.trimEnd('。') + "。"
        val store = AttachLook.store ?: return null
        val (hid, hmodel) = AttachLook.helper(store) ?: (store.activeProvider to store.activeModel)
        val hc = store.config(hid); if (!hc.configured || hmodel.isBlank()) return null
        val what = when (m.kind) {
            Lessons.KIND -> "她做事出过的岔子，她记下以后怎么做"
            "promise" -> "她答应他的事"
            "talk" -> if (m.moment) "她想留着的一刻" else "他们一起定下、还没做完的事"
            "about" -> "他跟她说起的他自己"
            "pref" -> "他喜欢她怎么和他相处"
            else -> "她对他的了解"
        }
        val ask = buildString {
            append("这件事是：").append(what).append("\n")
            append("记下的时间：").append(stamp(m.at)).append("\n")
            append("她当时记的一句：").append(Memory.ownWords(m.text)).append("\n")
            if (m.detail.isNotBlank()) append("为什么、什么时候照着做：").append(Memory.ownWords(m.detail)).append("\n")
            if (m.convTitle.isNotBlank()) append("那段对话的题目：「").append(m.convTitle).append("」\n")
            append("她叫").append(p.name).append("，他是").append(Memory.userCall).append("。只写这一件事，不添没发生过的细节。")
        }
        val raw = ChatApi.complete(provider(hid), hc, hmodel, Voice.helper(), ask, maxTokens = 3000)
        val json = raw.substringAfter('{', "").substringBeforeLast('}', "").ifEmpty { return null }
        val t = runCatching { JSONObject("{$json}").optString("text") }.getOrNull()?.trim().orEmpty()
        if (t.isEmpty() || Reflect.sensitive(t)) return null
        return t.take(220)
    }
}
