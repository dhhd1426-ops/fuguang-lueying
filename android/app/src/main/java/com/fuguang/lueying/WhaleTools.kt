package com.fuguang.lueying

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 鲸鱼娘就是她自己：她知道屏幕上的自己刚才被怎么了、在做什么（whale.js 报 act 事件 → [note]），
 * 也能自己动一下（whale_act）：马上做，边说边做（whale.js 给 App 让做的动作打了 keep，说话时不打断）。
 */
object WhaleTools {
    const val ACT = "whale_act"
    private val MOVES = listOf(
        "挥挥手", "比心", "欢呼", "要抱抱", "歪头卖萌", "偷看你", "凑近摇摆", "高举打招呼", "哼小曲", "伸懒腰",
        "打哈欠", "打瞌睡", "叹气", "抖尾巴", "追尾巴", "理理头发", "东张西望", "吹狗尾草", "戴铁盆", "跳舞",
    )
    private val MOODS = listOf("开心", "喜欢", "得意", "害羞", "温柔", "调皮", "难过", "生气", "紧张", "思考", "犯困", "惊讶")
    private val FACES = listOf("笑", "吐舌头", "吐舌头眨眼")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private class Ev(val at: Long, val text: String)
    private val log = ArrayDeque<Ev>()
    private var dancing = false
    /** 她自己让做的动作：刚做完那一下报回来的事件别再记成「你自己……」。 */
    private var selfUntil = 0L

    private fun add(t: String) = synchronized(log) { log.addLast(Ev(System.currentTimeMillis(), t)); while (log.size > 8) log.removeFirst() }

    /** whale.js 报上来的事件。 */
    fun note(o: JSONObject) {
        val mine = System.currentTimeMillis() < selfUntil
        when (o.optString("k")) {
            "tap" -> add(when (o.optString("rg")) {
                "head" -> "他点了点你的头"; "face" -> "他戳了戳你的脸"; "tail" -> "他碰了碰你的尾巴"
                "armL", "armR" -> "他碰了碰你的手"; else -> "他戳了你一下"
            })
            "pet" -> add("他摸了摸你的头")
            "moved" -> add("他把你拖到了屏幕上别的地方")
            "dizzy" -> add("他连着点了你好几下，你被点晕了")
            "dance" -> { dancing = true; if (!mine) add("你跳起舞来") }
            "danceEnd" -> dancing = false
            "routine" -> if (!mine) add("你自己" + o.optString("n"))
        }
    }

    private fun ago(ms: Long): String {
        val s = (System.currentTimeMillis() - ms) / 1000
        return when { s < 60 -> "刚才"; s < 3600 -> "${s / 60} 分钟前"; else -> "${s / 3600} 小时前" }
    }

    /** 拼进她每轮读到的「手边的东西」里。鲸鱼娘没开就什么都不写。 */
    fun system(call: String): String {
        if (!Whale.active) return ""
        val recent = synchronized(log) { log.toList() }.filter { System.currentTimeMillis() - it.at < 30 * 60_000 }.takeLast(5)
        return buildString {
            append("【屏幕上的你】${call}手机的聊天页上站着一个蓝头发、长着鲸鱼尾巴的女仆小姑娘（鲸鱼娘）——那就是你在他手机里的样子。")
            append(if (dancing) "你这会儿正在跳舞。" else "你这会儿站在聊天旁边。")
            if (recent.isNotEmpty()) append("最近：").append(recent.joinToString("；") { "${it.text}（${ago(it.at)}）" }).append("。")
            append("\n想用身体表达时可以调用 $ACT 做一个动作、换个表情，马上就做、边说边做（一轮最多一次，别每句都动）。")
            append("没调用就不要说自己做了什么动作；他问你在做什么、刚才怎么了，照上面写的说。")
        }
    }

    private fun enumOf(d: String, xs: List<String>) = JSONObject().put("type", "string").put("description", d).put("enum", JSONArray(xs))

    val spec = ToolSpec(
        ACT, "让屏幕上的你（鲸鱼娘）做一个动作、换个表情，马上就做，你接着说话她也不停。三项至少写一项。",
        JSONObject().put("type", "object").put("properties", JSONObject()
            .put("move", enumOf("动作", MOVES))
            .put("mood", enumOf("情绪（表情和小动作跟着变，持续几秒）", MOODS))
            .put("face", enumOf("一下子的表情", FACES))),
    )

    fun run(a: JSONObject): ToolOut {
        if (!Whale.active) return ToolOut("鲸鱼娘现在没在屏幕上（关着或没导入），做不了。", failed = true)
        val move = a.optString("move").takeIf { it in MOVES }
        val mood = a.optString("mood").takeIf { it in MOODS }
        val face = a.optString("face").takeIf { it in FACES }
        if (move == null && mood == null && face == null) return ToolOut("没写要做什么：move、mood、face 至少写一项，只能从给的里面选。", failed = true)
        // 马上做，边说边做：whale.js 里 App 让做的动作（rt.keep）说话时不会被嘴打断
        selfUntil = System.currentTimeMillis() + 2500
        if (mood != null) { Whale.moodOn(mood); keepMood = mood }
        if (face != null) Whale.face(face)
        if (move != null) Whale.routine(if (move == "跳舞") "@dance" else move)
        val what = listOfNotNull(move, mood?.let { "露出${it}的样子" }, face?.let { "做了个「$it」的表情" }).joinToString("、")
        add("你自己$what")
        return ToolOut("屏幕上的你正在$what。")
    }

    private var keepMood: String? = null

    /** 这一轮写完时鲸鱼娘会按整句重新定情绪：她自己挑过情绪的，再按她挑的来一次。 */
    fun flush() {
        val m = keepMood ?: return
        keepMood = null
        scope.launch { delay(120); if (Whale.active) Whale.moodOn(m) }
    }
}
