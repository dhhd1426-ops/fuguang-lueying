package com.fuguang.lueying

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Apricot: Color get() = Skin.accent

/** 正在写 / 正在改的那件作品：at = 她动手时正文写到第几个字；chars = 参数已经来了多少字；title 从参数开头抠出来（改作品就是原来的名字）。 */
class WorkDraft(val at: Int, val name: String, val chars: Int, val title: String?) {
    val editing get() = name == Works.EDIT
    companion object {
        private val TITLE = Regex("\"title\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.){1,40})")
        private val ID = Regex("\"id\"\\s*:\\s*\"([^\"]{1,64})\"")
        fun title(name: String, head: String): String? =
            if (name == Works.EDIT) ID.find(head)?.groupValues?.get(1)?.let { Works.get(it)?.title }
            else TITLE.find(head)?.groupValues?.get(1)?.replace("\\\"", "\"")?.takeIf { it.isNotBlank() }
    }
}

/**
 * 她的一段回复：正文按作品卡的位置切开，卡插在她动手的那一刻写到的地方——
 * 先说话再做就是「话 → 卡 → 话」，先动手就是卡在最前面。旧记录没有位置，卡都在最后。
 * 写的时候：卡等正文写到那里才出来；正在做的先放一张「正在写」，做好了原地换成作品卡。
 */
@Composable
internal fun ReplyFlow(
    text: String, works: List<String>, at: List<Int>, draft: WorkDraft?, fadeTail: Boolean, onWork: (String) -> Unit,
    reply: @Composable (String, Boolean) -> Unit,
) {
    class Slot(val pos: Int, val id: String?, val draft: WorkDraft?)
    val slots = works.mapIndexed { k, id -> Slot(at.getOrElse(k) { Int.MAX_VALUE }, id, null) }.toMutableList()
    if (draft != null) slots += Slot(draft.at, null, draft)
    slots.sortBy { it.pos }
    val n = text.length
    val ready = slots.filter { it.pos == Int.MAX_VALUE || it.pos <= n }
    var from = 0
    Column(Modifier.fillMaxWidth()) {
        ready.forEach { s ->
            val cut = s.pos.coerceIn(from, n)
            val seg = text.substring(from, cut)
            if (seg.isNotBlank()) reply(seg.trim(), false)
            from = cut
            key(if (s.pos == Int.MAX_VALUE) "end-${s.id}" else "at-${s.pos}") { WorkSlot(s.id, s.draft, onWork) }
        }
        val rest = text.substring(from.coerceAtMost(n))
        if (rest.isNotBlank()) reply(rest.trimStart(), fadeTail)
    }
}

/** 一个卡位：正在写时是占位卡，好了原地淡成作品卡（同一个位置，不跳）。 */
@Composable
private fun WorkSlot(id: String?, draft: WorkDraft?, onWork: (String) -> Unit) {
    val rise = remember { Animatable(if (id != null && draft == null) 1f else 0f) }
    LaunchedEffect(Unit) { rise.animateTo(1f, tween(300, easing = Curves.decel)) }
    Box(Modifier.graphicsLayer { alpha = rise.value; translationY = (1f - rise.value) * 8.dp.toPx() }) {
        // 后台在写的（WorkJobs）：写着是占位卡，好了是作品卡，没写成是一张能点了重来的卡
        val job = id?.takeIf { it.startsWith(WorkJobs.PREFIX) }
        val st = job?.let { WorkJobs.state[WorkJobs.keyOf(it)] }
        val face: String? = when {
            job == null -> id
            st?.done != null -> st.done
            st == null || st.fail != null -> "fail"
            else -> null
        }
        AnimatedContent(face, transitionSpec = { fadeIn(tween(240, 40)) togetherWith fadeOut(tween(160)) }, label = "workSlot") { real ->
            when {
                real == "fail" && job != null -> FailCard(WorkJobs.titleOf(job), st?.fail, WorkJobs.canRetry(job)) { WorkJobs.retry(job) }
                real != null -> WorkCards(listOf(real), onWork)
                st != null -> DraftCard(WorkDraft(0, if (st.editing) Works.EDIT else Works.MAKE, st.chars, st.title))
                draft != null -> DraftCard(draft)
            }
        }
    }
}

/** 正在写的卡：和作品卡一样的块；图标上一道光扫过，名字没出来时是一根灰条，下面写着写到多少字。 */
@Composable
private fun DraftCard(d: WorkDraft) {
    val t = rememberInfiniteTransition(label = "draft")
    val sweep by t.animateFloat(-1f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "sweep")
    Column(Modifier.padding(top = 10.dp, bottom = 12.dp)) {
        Row(
            Modifier.widthIn(max = 440.dp).fillMaxWidth().background(rgba(255, 255, 255, .055f), RoundedCornerShape(16.dp)).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(11.dp)).background(Apricot.copy(alpha = .13f))
                    .drawWithContent {
                        drawContent()
                        val x = size.width * sweep
                        drawRect(Brush.linearGradient(listOf(Color.Transparent, Color(0x38FFECDC), Color.Transparent), Offset(x, 0f), Offset(x + size.width * .7f, size.height)))
                    },
                contentAlignment = Alignment.Center,
            ) { SvgIcon(WorkIcons.page, Apricot, 24.dp, stroke = 1.5f) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                AnimatedContent(d.title, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(140)) }, label = "draftTitle") { title ->
                    if (title == null) Box(Modifier.padding(vertical = 6.dp).width(110.dp).height(10.dp).background(Page.LINE.copy(alpha = .6f), RoundedCornerShape(5.dp)))
                    else Txt(title, Page.INK, 16.sp, family = Serif, maxLines = 1)
                }
                Spacer(Modifier.height(4.dp))
                val verb = if (d.editing) "正在改" else "正在写"
                Txt(if (d.chars < 200) "$verb…" else "$verb · 已写 %.1fk 字".format(d.chars / 1000f), Page.MUTED, 12.5.sp, maxLines = 1)
            }
        }
    }
}

/** 后台没写成：同样的块，说一句原因；这次打开 App 里交代的能点一下重来。 */
@Composable
private fun FailCard(title: String, why: String?, canRetry: Boolean, onRetry: () -> Unit) {
    Pressable(onRetry, Modifier.padding(top = 10.dp, bottom = 12.dp).widthIn(max = 440.dp).fillMaxWidth(), enabled = canRetry, label = "再写一次") { pressed ->
        Row(
            Modifier.fillMaxWidth().background(rgba(255, 255, 255, if (pressed) .085f else .055f), RoundedCornerShape(16.dp)).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(56.dp).background(Color(0x1FD79A94), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                SvgIcon(WorkIcons.page, Color(0xFFD79A94), 24.dp, stroke = 1.5f)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Txt(title.ifBlank { "作品" }, Page.INK, 16.sp, family = Serif, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                Txt(if (canRetry) "没写成" + (why?.let { "（$it）" } ?: "") + " · 点一下再写一次" else "没写完（App 中途关掉了），让她再做一次", Page.MUTED, 12.5.sp, maxLines = 2)
            }
        }
    }
}
