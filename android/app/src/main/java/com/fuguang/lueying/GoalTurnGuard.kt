package com.fuguang.lueying

/** Per-request bounded intervention. Never shares counters across conversations. */
internal class GoalTurnGuard {
    private var finishNudged = false
    private var awaitingMainline = false
    private var idleTools = 0
    private var stallNudged = false

    fun afterTool(isBookkeeping: Boolean, resumed: Boolean = false): String? {
        if (resumed) awaitingMainline = true
        else if (!isBookkeeping) awaitingMainline = false
        idleTools = if (isBookkeeping) idleTools + 1 else 0
        if (idleTools < 4 || stallNudged) return null
        stallNudged = true
        return "连续多次只更新计划，尚无实际工具执行。信息足够就推进下一步；如有阻塞，请说明缺少什么，不要继续重复记账。"
    }

    fun beforeFinish(active: Boolean, branch: Boolean, userWaiting: Boolean): String? {
        if (!active || finishNudged || userWaiting || (!branch && !awaitingMainline)) return null
        finishNudged = true
        if (!branch) return "临时支线已结束，但尚未执行恢复后的主线步骤。请推进实际操作；若确实受阻就明确说明阻塞原因，不要只报告研究结论。这是应用提醒，不是用户新指令。"
        return "当前临时支线仍未闭合。请判断已有信息是否足够：足够则调用 manage_goal 的 finish_branch 记录结论并恢复主线，继续下一步；不足则有针对性地补查。受阻时如实说明，不要假装完成。这是应用提醒，不是用户新指令。"
    }
}
