package com.fuguang.lueying

/**
 * Conservative surface-intent hints. Not a language-model replacement:
 * unclear messages stay neutral and never authorize a destructive change.
 */
internal object GoalIntentPolicy {
    enum class Hint { CONTINUE, EXPLORE, SWITCH, CANCEL, PAUSE, UNSURE }

    private val hypothetical = listOf("如果", "假如", "假设", "举例", "例如", "会怎样", "怎么办", "what if", "suppose")
    private val cancel = listOf("取消任务", "取消这个任务", "不做了", "停止这个任务", "放弃这个任务", "cancel this task", "stop this task")
    private val switch = listOf("换个目标", "切换目标", "改做", "先不做", "换成", "改成新的任务", "switch goal", "instead work on")
    private val pause = listOf("暂停这个任务", "先暂停", "暂时停下", "pause this task")
    private val explore = listOf("看看", "查一下", "搜一下", "对比", "研究一下", "有没有类似", "compare", "research")
    private val continueWords = setOf("继续", "继续跟进", "接着做", "继续执行", "continue", "go on")

    fun hint(input: String): Hint {
        val message = input.trim()
        if (message.isEmpty()) return Hint.UNSURE
        if (hypothetical.any { message.contains(it, ignoreCase = true) }) return Hint.UNSURE
        if (continueWords.contains(message.lowercase())) return Hint.CONTINUE
        if (cancel.any { message.contains(it, ignoreCase = true) }) return Hint.CANCEL
        if (switch.any { message.contains(it, ignoreCase = true) }) return Hint.SWITCH
        if (pause.any { message.contains(it, ignoreCase = true) }) return Hint.PAUSE
        if (explore.any { message.contains(it, ignoreCase = true) }) return Hint.EXPLORE
        return Hint.UNSURE
    }

    /**
     * Never treat a hint by itself as authorization. A model tool call must
     * also cite an exact substring of the latest user message.
     */
    fun authorizes(action: String, message: String, quote: String): Boolean {
        if (quote.length < 3 || !message.contains(quote)) return false
        return when (action) {
            "switch" -> hint(message) == Hint.SWITCH
            "cancel" -> hint(message) == Hint.CANCEL
            else -> false
        }
    }

    fun modelNote(message: String): String = when (hint(message)) {
        Hint.CONTINUE -> "本轮用户要求继续：优先检查账本中未完成的主线步骤，核实状态后推进。"
        Hint.EXPLORE -> "本轮可能属于临时探索：保留主线，得出有用结论后回归；不要仅凭关键词开启新主线。"
        Hint.SWITCH -> "本轮可能要求切换目标：先核实用户完整语义；只有明确授权才能更换主线。"
        Hint.CANCEL -> "本轮可能要求取消：核实取消对象与真实意图，不要误取消其他任务。"
        Hint.PAUSE -> "本轮可能要求暂停：尊重用户当前操作指令，保留恢复点。"
        Hint.UNSURE -> "本轮意图尚未确定：根据上下文理解，不要擅自覆盖主线。"
    }
}
