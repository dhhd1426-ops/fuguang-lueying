package com.fuguang.lueying

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 她找到一个技能、想学之前问你（方案二）：思考那一行下面长出一张小卡片，
 * 「我在 GitHub 上找到一个…装上吗？」+ 技能是谁写的、几颗星 +「装上 / 先不用」。
 * 装上：转圈 → 画 ✓ → 迸光点，然后整张收起；先不用：直接收起，她用自己的办法接着做。
 */
@Composable
internal fun SkillAskHost(compact: Boolean) {
    val cur = Skills.asking
    var last by remember { mutableStateOf<SkillAsk?>(null) }
    if (cur != null) last = cur
    AnimatedVisibility(
        cur != null,
        enter = fadeIn(tween(220)) + scaleIn(spring(dampingRatio = .82f, stiffness = 420f), initialScale = .94f, transformOrigin = TransformOrigin(0f, 0f)) +
            expandVertically(spring(dampingRatio = .9f, stiffness = 420f), expandFrom = Alignment.Top),
        exit = fadeOut(tween(160)) + shrinkVertically(tween(220, easing = Curves.accel), shrinkTowards = Alignment.Top),
    ) { last?.let { AskCard(it, compact) } }
}

@Composable
private fun AskCard(ask: SkillAsk, compact: Boolean) {
    val c = ask.cand
    var said by remember(ask) { mutableStateOf(false) }
    Column(Modifier.padding(top = 8.dp, bottom = 4.dp).widthIn(max = 520.dp).fillMaxWidth()) {
        Txt(
            "我在 GitHub 上找到一个「${ask.title}」的本事，想学了来做。" + (if (ask.why.isNotBlank()) ask.why.trimEnd('。') + "。" else "") + "装上吗？",
            Page.ink.copy(alpha = .9f), if (compact) 15.sp else 16.sp, lineHeight = if (compact) 26.sp else 28.sp,
        )
        Row(
            Modifier.padding(top = 10.dp).fillMaxWidth().background(skillWarm.copy(alpha = .08f), RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SkillTile(c.id, ask.title, 32.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Txt(c.name.ifBlank { c.id }, Page.ink, 13.5.sp, maxLines = 1)
                Txt(c.repo + (if (c.stars.isNotEmpty()) " · ★ ${c.stars}" else ""), Page.muted, 11.sp, Modifier.padding(top = 2.dp), maxLines = 1)
            }
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            GetButton("装上", false, {
                said = true
                ask.answer.complete(true)
                ask.installed.await()?.let { error(it) }
            }, { }, wide = true)
            AnimatedVisibility(!said, exit = fadeOut(tween(160))) {
                CardButton(0, BtnTone.Quiet, "先不用", { said = true; ask.answer.complete(false) }, Modifier.widthIn(min = 92.dp)) { _, ink ->
                    Txt("先不用", ink, 13.5.sp, Modifier.padding(horizontal = 14.dp))
                }
            }
        }
    }
}
