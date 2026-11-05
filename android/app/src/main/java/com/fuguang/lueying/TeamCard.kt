package com.fuguang.lueying

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * 分身团队的进度卡（第 398 版，原型 https://claude.ai/artifact/DQqyv4AAxQkVig2FrNCJs1 第 3 版）：
 * 照后台写作品那张（56 小方块弹跳 + 名字 + 一行状态），右边「停下 / 接着做」，下面 α β γ 一行一句：
 * 在写的是杏色小点呼吸，写完换成勾。做好了这张卡原地换成作品卡（WorkFlow.kt）。
 */

private val okIc = Ic("M5 12.5l4.5 4.5L19 7.5")
private val pauseIc = Ic("M9 7v10M15 7v10")
private val failIc = Ic("M12 7.5v5.5M12 16.2v.3", circles = listOf(floatArrayOf(12f, 12f, 8.5f)))

private fun stateLine(r: Team.Run): String {
    val done = r.parts.count { it.status == "done" }
    val left = r.parts.size - done
    return when (r.stage) {
        "merge" -> "她在整合"
        "stop" -> "停在这里 · 写好的都留着"
        "fail" -> "没做成：" + r.fail.orEmpty()
        "done" -> "做好了"
        else -> if (done == 0) "${r.parts.size} 个分身在写" else if (left == 0) "都写好了" else "写好 $done 段，还有 $left 段"
    }
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

@Composable
internal fun TeamCard(key: String) {
    val r = Team.runs[key] ?: return
    val running = r.stage == "write" || r.stage == "merge"
    val canGo = r.stage == "stop" || r.stage == "fail"
    Column(Modifier.padding(top = 10.dp, bottom = 12.dp).widthIn(max = 440.dp).fillMaxWidth().background(veil(.055f), RoundedCornerShape(16.dp)).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(11.dp)).background(Skin.accent.copy(alpha = if (r.stage == "fail") 0f else .13f)), contentAlignment = Alignment.Center) {
                when {
                    running -> ToyTile(Skin.accent, Modifier.size(56.dp))
                    r.stage == "fail" -> Box(Modifier.size(56.dp).background(Skin.danger.copy(alpha = .12f)), contentAlignment = Alignment.Center) { SvgIcon(failIc, Skin.danger, 24.dp, stroke = 1.5f) }
                    else -> SvgIcon(pauseIc, Skin.accent, 22.dp, stroke = 2.2f)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Txt(r.title, Page.ink, 16.sp, family = Serif, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                SwapText(stateLine(r), if (r.stage == "fail") Skin.danger else Page.muted, 12.5.sp)
            }
            Spacer(Modifier.width(8.dp))
            if (running || canGo) {
                PressSurface(
                    { if (running) Team.stop(key) else Team.resume(key) }, Modifier.height(36.dp),
                    shape = RoundedCornerShape(12.dp), fill = if (canGo) Skin.accent else veil(.08f), pressedFill = if (canGo) Skin.accent.copy(alpha = .8f) else veil(.14f),
                    pressScale = .94f, label = if (running) "停下" else "接着做",
                ) {
                    Box(Modifier.padding(horizontal = 12.dp).align(Alignment.Center)) {
                        SwapText(if (running) "停下" else "接着做", if (canGo) Skin.onAccent else Page.ink, 12.5.sp)
                    }
                }
            }
        }
        // 做好了就收起来（卡片随后换成作品卡）；在写、停着、没做成都留着
        AnimatedVisibility(r.stage != "done", enter = expandVertically(tween(360, easing = Curves.decel)) + fadeIn(tween(260)), exit = shrinkVertically(tween(260, easing = Curves.accel)) + fadeOut(tween(180))) {
            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
                        Modifier.fillMaxWidth().heightIn(min = 36.dp).background(Color.Black.copy(alpha = .16f), shape).padding(horizontal = 10.dp, vertical = 7.dp),
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
