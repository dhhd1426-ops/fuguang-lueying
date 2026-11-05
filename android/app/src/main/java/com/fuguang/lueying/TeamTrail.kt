package com.fuguang.lueying

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * 分身团队在思考过程里（第 400 版，原型 https://claude.ai/artifact/DQqyv4AAxQkVig2FrNCJs1 第 4 版，用户说进度要在思考过程里、不要小球卡片）：
 * - 聊天里：那条回复的思考行一直写最新一步（在写时流光），做完换时钟 +「整合成《…》」；
 * - 摘要里：叫分身那一步的标题跟着变，下面挂 α β γ 一行一句（在写杏色小点、写完勾），再下面「停下 / 接着做」。
 * 靠叫分身那一步的 ToolStep.detail = "team:<key>" 找到这一队（Chat.kt 收尾时记进去）。
 */

private val okIc = Ic("M5 12.5l4.5 4.5L19 7.5")

/** 这一步里记着的那一队；没有就是 null。 */
fun teamOf(steps: List<ToolStep>): Team.Run? =
    steps.lastOrNull { it.name == Team.SPAWN && it.detail.startsWith(Team.PREFIX) }?.let { Team.runs[Team.keyOf(it.detail)] }

fun teamKeyOf(step: ToolStep?): String? = step?.takeIf { it.name == Team.SPAWN && it.detail.startsWith(Team.PREFIX) }?.let { Team.keyOf(it.detail) }

fun teamBusy(r: Team.Run) = r.stage == "write" || r.stage == "merge"

/** 思考行那一句：最新一步。 */
fun teamLine(r: Team.Run): String {
    val done = r.parts.count { it.status == "done" }
    val left = r.parts.size - done
    return when (r.stage) {
        "merge" -> "整合成《${r.title.take(14)}》"
        "stop" -> "停在这里 · 写好的都留着"
        "fail" -> "没做成：" + r.fail.orEmpty()
        "done" -> "整合成《${r.title.take(14)}》"
        else -> if (done == 0) "${r.parts.size} 个分身在写" else if (left == 0) "都写好了" else "写好 $done 段，还有 $left 段"
    }
}

/** 摘要里那一步的标题。 */
fun teamTitle(r: Team.Run): String = when (r.stage) {
    "done" -> "${r.parts.size} 个分身写好了，整合成《${r.title.take(16)}》"
    "merge" -> "${r.parts.size} 个分身写好了，在整合"
    "fail" -> "叫分身写《${r.title.take(16)}》没做成"
    else -> "叫分身一起写《${r.title.take(16)}》"
}

private fun partLine(r: Team.Run, p: Team.Part): String {
    val head = p.task.lineSequence().firstOrNull().orEmpty().take(14)
    val tail = when (p.status) {
        "done" -> "写好了"
        "run" -> p.note.ifEmpty { "在写" }
        "fail" -> "没写成"
        "stop" -> "停着"
        else -> if (p.after.isNotEmpty() && p.after.any { a -> r.part(a)?.status != "done" }) "等 ${p.after.joinToString(" ")}" else "等开工"
    }
    return "$head · $tail"
}

/** 摘要里那一步下面：α β γ 一行一句，再下面停下 / 接着做。 */
@Composable
fun TeamParts(r: Team.Run) {
    val running = teamBusy(r)
    val canGo = r.stage == "stop" || r.stage == "fail"
    Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        r.parts.forEachIndexed { i, p ->
            val shape = when {
                r.parts.size == 1 -> RoundedCornerShape(12.dp)
                i == 0 -> RoundedCornerShape(12.dp, 12.dp, 4.dp, 4.dp)
                i == r.parts.lastIndex -> RoundedCornerShape(4.dp, 4.dp, 12.dp, 12.dp)
                else -> RoundedCornerShape(4.dp)
            }
            val waiting = p.status == "wait" || p.status == "stop"
            val a by animateFloatAsState(if (waiting) .55f else 1f, tween(300, easing = Curves.standard), label = "teamRow")
            Row(
                Modifier.fillMaxWidth().heightIn(min = 36.dp).background(veil(.06f), shape).padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Txt(p.who, Page.muted, 13.5.sp, Modifier.width(16.dp), family = Serif, maxLines = 1)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f).graphicsLayer { alpha = a }) { SwapText(partLine(r, p), Page.muted, 13.sp) }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
                    // 写全名：这里在 Box 里，外面还有 Row / Column，不要被它们的 AnimatedVisibility 拦走
                    androidx.compose.animation.AnimatedVisibility(p.status == "done", enter = scaleIn(tween(260, easing = Curves.decel), initialScale = .4f) + fadeIn(tween(200)), exit = scaleOut(tween(160)) + fadeOut(tween(140))) {
                        SvgIcon(okIc, Page.muted, 14.dp, stroke = 2f)
                    }
                    if (p.status == "run" && running) RunDot()
                }
            }
        }
        if (running || canGo) {
            Spacer(Modifier.height(6.dp))
            PressSurface(
                { if (running) Team.stop(r.key) else Team.resume(r.key) }, Modifier.height(36.dp),
                shape = RoundedCornerShape(12.dp), fill = if (canGo) Skin.accent else veil(.08f), pressedFill = if (canGo) Skin.accent.copy(alpha = .8f) else veil(.14f),
                pressScale = .94f, label = if (running) "停下" else "接着做",
            ) {
                Box(Modifier.padding(horizontal = 14.dp).align(Alignment.Center)) {
                    SwapText(if (running) "停下" else "接着做", if (canGo) Skin.onAccent else Page.ink, 12.5.sp)
                }
            }
        }
    }
}

/** 正在写：杏色小点呼吸。 */
@Composable
private fun RunDot() {
    val inf = rememberInfiniteTransition(label = "teamDot")
    val v = inf.animateFloat(1f, .3f, infiniteRepeatable(tween(550, easing = Curves.standard), RepeatMode.Reverse), label = "teamDotA")
    Box(Modifier.size(6.dp).graphicsLayer { alpha = v.value; val s = .7f + .3f * v.value; scaleX = s; scaleY = s }.background(Skin.accent, CircleShape))
}
