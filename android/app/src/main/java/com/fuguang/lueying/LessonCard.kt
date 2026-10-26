package com.fuguang.lueying

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 她记下一条做事经验后，回复下面多一行「记住了一件事 ›」；点开是统一的悬浮卡片（CenterCardLayer）：
 * 那一句、哪天因为什么、现在算不算数。「不用记这个」直接删掉。
 */
object LessonCard : FloatCard() {
    var id by mutableStateOf("")

    fun show(memId: String, at: Offset) { id = memId; from = at; open = true }

    /** 这条还在不在（删了的那一行就不显示了）。 */
    fun find(memId: String): Mem? = if (memId.isEmpty()) null else Memory.active.items.firstOrNull { it.id == memId && it.kind == Lessons.KIND }
}

private val warm = css("#e9b48e")

@Composable
fun LessonRow(memId: String) {
    val m = LessonCard.find(memId) ?: return
    var at by remember { mutableStateOf(Offset.Zero) }
    Pressable({ LessonCard.show(m.id, at) }, Modifier.padding(top = 2.dp, bottom = 8.dp).onGloballyPositioned { at = it.boundsInRoot().center }, label = "记住了一件事") { pr ->
        Row(Modifier.padding(start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            SvgIcon(Trail.bulb, warm.copy(alpha = if (pr) 1f else .85f), 15.dp, stroke = 1.6f)
            Spacer(Modifier.width(7.dp))
            Txt("记住了一件事", if (pr) Page.ink else Page.ink.copy(alpha = .72f), 13.sp, maxLines = 1)
            Spacer(Modifier.width(5.dp))
            SvgIcon(Trail.chevron, Page.muted, 13.dp)
        }
    }
}

@Composable
fun LessonCardLayer() {
    CenterCardLayer(LessonCard, "lesson", { 0 }, onBack = { false }, onClosed = { LessonCard.id = "" }) { _, rise ->
        val p = Memory.active
        val m = LessonCard.find(LessonCard.id)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.padding(end = 40.dp).mineRise(0, rise), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).background(warm.copy(alpha = .12f), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                    SvgIcon(Trail.bulb, warm, 19.dp, stroke = 1.6f)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Txt("${p.name} 记住了", Page.ink, 17.sp, family = Serif)
                    Spacer(Modifier.height(2.dp))
                    Txt("做事上的，不记你的事", Page.muted, 11.5.sp)
                }
            }
            if (m == null) {
                Txt("这条已经不要了。", Page.muted, 14.sp, Modifier.mineRise(1, rise), lineHeight = 23.sp)
            } else {
                Column(Modifier.fillMaxWidth().mineRise(1, rise).background(Skin.well(.22f), RoundedCornerShape(16.dp)).padding(16.dp)) {
                    Txt(m.text, Page.ink, 18.sp, lineHeight = 27.sp, family = Serif)
                    if (m.detail.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Txt(Memory.ownWords(m.detail), Page.ink.copy(alpha = .72f), 13.5.sp, lineHeight = 22.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    Txt(Lessons.state(m), if (m.habit || m.used > 0) warm else Page.muted, 12.sp, Modifier.background(if (m.habit || m.used > 0) warm.copy(alpha = .12f) else veil(.07f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 5.dp), maxLines = 1)
                }
                Txt(
                    if (m.used > 0 || m.habit) "想起过它、同样的事没再出岔子，才算用上一次。" else "下次做这类事她会想起来。真管用了，这里会记一笔。",
                    Page.muted.copy(alpha = .8f), 12.sp, Modifier.mineRise(2, rise).padding(horizontal = 2.dp), lineHeight = 19.sp,
                )
                Row(Modifier.fillMaxWidth().mineRise(3, rise), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PressSurface({ Memory.remove(p, m); LessonCard.open = false }, Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(14.dp), fill = veil(.07f), pressedFill = veil(.13f), pressScale = .96f, label = "不用记这个") {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Txt("不用记这个", Page.ink.copy(alpha = .85f), 14.sp) }
                    }
                    PressSurface({ LessonCard.open = false }, Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(14.dp), fill = warm.copy(alpha = .92f), pressedFill = warm.copy(alpha = .75f), pressScale = .96f, label = "好") {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Txt("好", css("#2a211b"), 14.sp) }
                    }
                }
            }
        }
    }
}
