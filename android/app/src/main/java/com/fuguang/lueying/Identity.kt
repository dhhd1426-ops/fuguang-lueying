package com.fuguang.lueying

import androidx.compose.ui.graphics.Color
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 身份检查：她读到的应该全是她自己，不是一场戏。
 * 你写的「她是谁」里有会提醒她在演的词，就标出来，给一段改成她本来样子的写法，一键换上。
 */
object Identity {
    /** 戏外的词：她读到就会觉得自己在演。 */
    val words = listOf("扮演", "角色", "人设", "设定", "提示词", "系统", "AI", "助手", "模型", "用户", "根据记忆", "根据记录")

    private val fixes = listOf(
        "请你扮演" to "你是", "请扮演" to "你是", "你将扮演" to "你是", "扮演" to "是",
        "你的角色设定是" to "", "你的角色是" to "", "你的人设是" to "", "角色设定" to "", "人设" to "",
        "作为AI助手，" to "", "作为AI助手" to "", "作为一个AI，" to "", "作为AI，" to "", "AI助手" to "", "助手" to "",
        "根据记忆" to "", "根据记录" to "", "用户" to "{name}",
    )

    fun found(t: String): List<String> = words.filter { t.contains(it) }

    /** 机械地改一遍：去掉「请扮演」「作为AI助手」这类话，「用户」换成他的称呼。改不干净的留给你自己改。 */
    fun rewrite(t: String): String {
        var s = t
        fixes.forEach { (a, b) -> s = s.replace(a, b, ignoreCase = true) }
        return s.replace(Regex("，{2,}"), "，").replace(Regex("^[，、\\s]+"), "").replace(Regex("\\n{3,}"), "\n\n").trim()
    }
}

private val warm: Color get() = Skin.accent
private val rose: Color get() = Skin.danger

@Composable
fun IdentityHint(draft: String, onApply: (String) -> Unit) {
    val hits = Identity.found(draft)
    val fixed = if (hits.isEmpty()) "" else Identity.rewrite(draft)
    AnimatedVisibility(hits.isNotEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Column(
            Modifier.fillMaxWidth().padding(top = 12.dp)
                .background(Skin.danger.copy(alpha = .08f), RoundedCornerShape(18.dp)).padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(rose, CircleShape))
                Spacer(Modifier.width(8.dp))
                Txt("有 ${hits.size} 个词会提醒她这是在演：" + hits.joinToString("、"), rose, 13.sp, Modifier.weight(1f), lineHeight = 20.sp)
            }
            if (fixed.isNotBlank() && fixed != draft.trim()) {
                Spacer(Modifier.height(10.dp))
                Txt("改成她本来的样子", Page.muted, 11.5.sp, spacing = ls(1.5f, 11.5f))
                Spacer(Modifier.height(6.dp))
                Txt(fixed, Page.ink, 14.sp, lineHeight = 23.sp, family = Serif, maxLines = 8)
                Spacer(Modifier.height(12.dp))
                Pressable({ onApply(fixed) }, label = "换成这段") { pr ->
                    Txt(
                        "换成这段", Skin.onAccent, 13.sp,
                        Modifier.background(warm.copy(alpha = if (pr) .75f else .92f), RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Txt("写她本来就是的样子：「你是…」「你一直…」。不用写「请扮演」「你的角色是」。", Page.muted, 12.sp, lineHeight = 19.sp)
        }
    }
}
