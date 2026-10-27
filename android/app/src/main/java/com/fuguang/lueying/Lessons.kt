package com.fuguang.lueying

import org.json.JSONObject

/**
 * 她记的（第 296 版）：你纠正她一次，她以后不再犯。
 *
 * 做事出了岔子（你说「没有卡啊」、她说做好了却没动手、照片没看清、上网查失败、工具用到顶、你点了重来），
 * 这一轮结束后请帮手看一遍，她给自己记一句（kind = lesson 的 Mem）。下次要做同类的事，这句放进她读到的东西里。
 * 算不算数她说了不算：想起了这条、同样的岔子没再出，才记一次「用上」；又出了，记一次「没帮上」，并把那条改得更具体。
 * 用上 3 次成习惯（哪个型号都带着）；没帮上 2 次就不再想起；30 天没想起过的也不再想起。
 * 只管她怎么做事，不管她是什么性子，那是「你的偏好」和「她自己的样子」的事。
 */
object Lessons {
    const val KIND = "lesson"
    /** 时间线上「想起上次」那一步的名字。 */
    const val STEP = "recall_lesson"

    /* 岔子 */
    const val CLAIM = "claim"
    const val WORK = "work"
    const val PHOTO = "photo"
    const val WEB = "web"
    const val CAP = "cap"
    const val CORRECT = "correct"
    const val REDO = "redo"

    /** 一个岔子对应她什么时候该想起这条：做作品 / 看照片 / 上网查 / 说话。 */
    fun scene(slip: String): String = when (slip) { CLAIM, WORK, CAP -> "work"; PHOTO -> "photo"; WEB -> "web"; else -> "talk" }

    private fun describe(slip: String): String = when (slip) {
        CLAIM -> "你说作品做好了 / 写好了，可这一轮没有动手做，卡没出来"
        WORK -> "动手做作品没做成"
        PHOTO -> "他发的照片没看清"
        WEB -> "上网查没查到、出错了"
        CAP -> "一轮里用工具用到了上限，事没做完"
        CORRECT -> "他纠正了你（上一句说的不对、不是他要的）"
        else -> "他让你重新回答了一遍"
    }

    private val correction = Regex("不对|不是这样|不是这个|不是这么|没有(啊|吧|呀|呢|卡|图|做|写|出来|啊)|怎么没|你没|我说的是|我是说|说错了|不要(这样|这个|这么)|别(这样|再|这么)|重新(做|写|来)|又(来|是|错)|听我说|不是让你|我要的是|搞错了")

    /** 这句是不是在纠正她：接在她的回复后面、不长、有纠正的字眼。 */
    fun isCorrection(text: String, prev: ChatMsg?): Boolean = prev?.role == "assistant" && text.length <= 80 && correction.containsMatchIn(text)

    /** 一句话该在什么时候想起（你自己写的那条用）。 */
    fun guessScene(s: String): String = when {
        Regex("卡|作品|画板|写好|做好|做成|排版|网页").containsMatchIn(s) -> "work"
        Regex("照片|图片|截图|看图|图里").containsMatchIn(s) -> "photo"
        Regex("上网|搜|查到|查一下|网上|价格|最新").containsMatchIn(s) -> "web"
        else -> "talk"
    }

    /** 这一轮大概要做什么：从最后一句和带的东西猜。 */
    private fun scenesOf(last: ChatMsg?): Set<String> {
        if (last == null) return emptySet()
        val out = mutableSetOf<String>()
        val t = last.text
        if (Regex("卡片|做成|做一张|做个|画一|写一|写成|改一下|改成|排版|网页|做张|做一个|帮我做|帮我写").containsMatchIn(t)) out += "work"
        if (last.files.any { Attach.isImage(it) } || Regex("照片|图里|这张图|截图").containsMatchIn(t)) out += "photo"
        if (Regex("现在|最近|最新|今天|价格|多少钱|新闻|查一下|查查|搜一下|上网").containsMatchIn(t)) out += "web"
        return out
    }

    private fun grams(s: String) = s.windowed(2).filter { g -> g.all { it.isLetterOrDigit() } }.toSet()

    /** 这一轮想起哪几条：习惯最多 3 条，再按要做的事和字面挑最多 2 条。关着、或这家型号没记过的，什么都不带。 */
    fun pick(p: Persona, history: List<ChatMsg>, pv: String): List<Mem> {
        if (!p.lessonsOn) return emptyList()
        val now = System.currentTimeMillis()
        val fresh = p.items.filter { it.kind == KIND && it.failed < 2 && (it.habit || it.by == "you" || now - maxOf(it.at, it.usedAt) < 30L * 86_400_000) }
            .filter { it.habit || it.by == "you" || it.pv.isEmpty() || it.pv == pv }
        if (fresh.isEmpty()) return emptyList()
        val habits = fresh.filter { it.habit }.sortedByDescending { it.usedAt }.take(3)
        val rest = fresh - habits.toSet()
        val last = history.lastOrNull { it.role == "user" }
        val scenes = scenesOf(last)
        val g = grams(history.takeLast(4).joinToString(" ") { it.text })
        val scored = rest.map { m -> m to ((if (m.scene in scenes) 4 else 0) + grams(m.text + m.detail).count { it in g }) }
            .filter { it.second >= 2 }.sortedWith(compareByDescending<Pair<Mem, Int>> { it.second }.thenByDescending { it.first.used }.thenByDescending { it.first.at })
        return habits + scored.take(2).map { it.first }
    }

    /** 放在最后一句前的旁白：想起的几条，加上「他在纠正你」时怎么接。 */
    fun aside(shown: List<Mem>, slips: Set<String>): String {
        val sb = StringBuilder()
        if (shown.isNotEmpty()) sb.append("\n（做事上你记得：").append(shown.joinToString("；") { Memory.ownWords(it.text).trimEnd('。') }).append("。）")
        if (CORRECT in slips) sb.append("\n（${Memory.userCall}刚才在纠正你：先认下来，把事做对；最后用一句话说你记住了什么，不用再三道歉。）")
        return sb.toString()
    }

    /** 这一轮结束：想起过的那几条，同样的岔子没再出就算用上，又出了就算没帮上。说话类的等下一句再定（settleTalk）。 */
    private var pendingTalk: List<String> = emptyList()

    fun settle(p: Persona, shown: List<Mem>, slips: Set<String>, happened: Set<String>) {
        if (shown.isEmpty()) return
        val bad = slips.map { scene(it) }.toSet()
        val now = System.currentTimeMillis()
        var changed = false
        pendingTalk = shown.filter { it.scene == "talk" }.map { it.id }
        shown.filter { it.scene != "talk" }.forEach { m ->
            val i = p.items.indexOf(m).takeIf { it >= 0 } ?: return@forEach
            when {
                m.scene in bad -> { p.items[i] = m.copy(failed = m.failed + 1, usedAt = now); changed = true }
                m.scene in happened -> { p.items[i] = m.copy(used = m.used + 1, usedAt = now); changed = true }
            }
        }
        if (changed) Memory.save()
    }

    /** 下一句来了：上一轮想起的说话类经验，这句不是纠正就算用上；是纠正、而且说的是同一件事才算没帮上。 */
    fun settleTalk(p: Persona, text: String, correcting: Boolean) {
        val ids = pendingTalk; pendingTalk = emptyList()
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis()
        val g = grams(text)
        var changed = false
        ids.forEach { id ->
            val i = p.items.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return@forEach
            val m = p.items[i]
            p.items[i] = when {
                !correcting -> m.copy(used = m.used + 1, usedAt = now)
                grams(m.text + m.detail).count { it in g } >= 2 -> m.copy(failed = m.failed + 1, usedAt = now)
                else -> return@forEach
            }
            changed = true
        }
        if (changed) Memory.save()
    }

    /** 经验本里那条的状态。 */
    fun state(m: Mem): String = when {
        m.by == "you" -> if (m.used > 0) "用上 ${m.used} 次" else "你写的"
        m.failed >= 2 -> "两次没帮上，不再想起"
        m.habit -> "成了习惯 · 用上 ${m.used} 次"
        m.used > 0 -> "用上 ${m.used} 次" + (if (m.failed > 0) " · 没帮上 ${m.failed} 次" else "")
        m.failed > 0 -> "改过一次"
        else -> "刚记的"
    }

    private fun listing(ms: List<Mem>) = ms.mapIndexed { i, m -> "l$i [${m.scene}] " + m.text + (if (m.detail.isNotBlank()) "（" + m.detail + "）" else "") }.joinToString("\n").ifBlank { "（还没有）" }

    /**
     * 出了岔子的这一轮结束后：请帮手看一遍，写 0 或 1 条。说的是同一件事的旧条目就改写它、从头算。
     * 返回记下 / 改写的那条，没记返回 null。失败就算了，不打扰。
     */
    suspend fun learn(p: Persona, store: AssistantStore, window: List<ChatMsg>, slips: Set<String>, pv: String, conv: String, convTitle: String): Mem? {
        if (!p.lessonsOn || slips.isEmpty()) return null
        val (hid, hmodel) = AttachLook.helper(store) ?: (store.activeProvider to store.activeModel)
        val hc = store.config(hid); if (!hc.configured) return null
        val who = Memory.userCall
        val known = p.items.filter { it.kind == KIND && it.by != "you" }.take(40)
        val talk = window.takeLast(8).joinToString("\n") { m ->
            (if (m.role == "user") "$who：" else "你：") + m.text.take(500) +
                m.steps.filter { it.name != STEP }.takeIf { it.isNotEmpty() }?.let { "\n（这一轮你：" + it.joinToString("、") { s -> s.title } + "）" }.orEmpty()
        }.take(6000)
        val sys = "你是${p.name}，刚才做事出了岔子，在给自己记一句以后怎么做。只输出 JSON，不要别的文字。"
        val ask = """
刚才这一段：
$talk

出的岔子（App 看到的）：
${slips.joinToString("\n") { "- " + describe(it) }}

你已经记着的做事经验（l 编号是 id）：
${listing(known)}

给自己写 0 或 1 条以后做事要记住的。只写怎么做事（先做什么再说什么、看什么、查什么），不写你该是什么性子，也不写$who 的情况和喜好。
拿不准是不是你的问题就不写，返回空数组。
- text：一句话，18 字以内，你自己的口吻，像「先把卡做出来，再说做好了」。
- why：哪天、因为什么，30 字以内，称$who 为“他”（今天是 ${java.text.SimpleDateFormat("M月d日", java.util.Locale.CHINA).format(java.util.Date())}）。
- scene：这条什么时候想起：work（做作品、改作品时）/ photo（看照片时）/ web（上网查时）/ talk（平常说话时）。
- 已有说同一件事的，就改写那条（op 写 update、带 id），不新开。
输出：{"ops":[{"op":"add","text":"…","why":"…","scene":"work"}]} 或 {"ops":[{"op":"update","id":"l2","text":"…","why":"…","scene":"work"}]} 或 {"ops":[]}
""".trim()
        val raw = runCatching { ChatApi.complete(provider(hid), hc, hmodel, sys, ask, maxTokens = 400) }.getOrNull() ?: return null
        val json = raw.substringAfter('{', "").substringBeforeLast('}', "").ifEmpty { return null }
        val o = runCatching { JSONObject("{$json}").optJSONArray("ops")?.optJSONObject(0) }.getOrNull() ?: return null
        val t = o.optString("text").trim().trimEnd('。').take(40)
        val why = o.optString("why").trim().take(80)
        val sc = o.optString("scene").trim().let { if (it in setOf("work", "photo", "web", "talk")) it else scene(slips.first()) }
        if (t.isEmpty() || Reflect.sensitive(t) || Reflect.sensitive(why)) return null
        val now = System.currentTimeMillis()
        val old = o.optString("id").removePrefix("l").toIntOrNull()?.let { known.getOrNull(it) }
        val mine = if (CORRECT in slips || REDO in slips) "" else pv
        val m = if (old != null && o.optString("op") == "update") {
            val i = p.items.indexOf(old).takeIf { it >= 0 } ?: return null
            old.copy(text = t, detail = why.ifEmpty { old.detail }, at = now, scene = sc, used = 0, failed = old.failed, usedAt = now, diary = "").also { p.items[i] = it }
        } else {
            if (p.items.any { it.kind == KIND && it.text == t }) return null
            Mem("$now-l", t, why, now, conv, convTitle, KIND, "her", pv = mine, scene = sc, usedAt = now).also { p.items.add(0, it) }
        }
        Memory.save()
        Diary.later(p, m)
        return m
    }
}
