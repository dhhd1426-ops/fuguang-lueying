package com.fuguang.lueying

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Apricot: Color get() = Skin.accent

/** 作品卡的底：实底 + 1dp 细线（Skin.workFill / workLine），正在写、没做成、做好了三张卡共用。 */
internal fun Modifier.workCard(pressed: Boolean = false): Modifier {
    val shape = RoundedCornerShape(16.dp)
    return this.background(if (pressed) Skin.workPress else Skin.workFill, shape).border(1.dp, Skin.workLine, shape)
}

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
    chat: ChatState, store: AssistantStore,
    reply: @Composable (String, Boolean) -> Unit,
) {
    class Slot(val pos: Int, val id: String?, val draft: WorkDraft?)
    val slots = works.mapIndexedNotNull { k, id ->
        ArchivedTeamWorks.resolve(id)?.let { Slot(at.getOrElse(k) { Int.MAX_VALUE }, it, null) }
    }.toMutableList()
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
            key(if (s.pos == Int.MAX_VALUE) "end-${s.id}" else "at-${s.pos}") { WorkSlot(s.id, s.draft, onWork, chat, store) }
        }
        val rest = text.substring(from.coerceAtMost(n))
        if (rest.isNotBlank()) reply(rest.trimStart(), fadeTail)
    }
}

/** 一个卡位：正在写时是占位卡，好了原地淡成作品卡（同一个位置，不跳）。 */
@Composable
private fun WorkSlot(id: String?, draft: WorkDraft?, onWork: (String) -> Unit, chat: ChatState, store: AssistantStore) {
    val rise = remember { Animatable(if (id != null && draft == null) 1f else 0f) }
    LaunchedEffect(Unit) { rise.animateTo(1f, tween(300, easing = Curves.decel)) }
    // 她直接发的文件（SentFiles）：文件卡，自己管打包动效
    if (id != null && id.startsWith(Pacts.PREFIX)) {
        Box(Modifier.graphicsLayer { alpha = rise.value; translationY = (1f - rise.value) * 8.dp.toPx() }) { PactCard(id) }
        return
    }
    if (id != null && SentFiles.isRef(id)) {
        Box(Modifier.graphicsLayer { alpha = rise.value; translationY = (1f - rise.value) * 8.dp.toPx() }) { SentFileCard(id) }
        return
    }
    Box(Modifier.graphicsLayer { alpha = rise.value; translationY = (1f - rise.value) * 8.dp.toPx() }) {
        // 后台在写的（WorkJobs）：写着是占位卡，好了是作品卡，没写成是一张能点了重来的卡
        val job = id?.takeIf { it.startsWith(WorkJobs.PREFIX) }
        val st = job?.let { WorkJobs.state[WorkJobs.keyOf(it)] }
        val face: String? = when {
            job == null -> id
            st?.done != null -> st.done
            else -> JOB
        }
        AnimatedContent(face, transitionSpec = { fadeIn(tween(240, 40)) togetherWith fadeOut(tween(160)) }, label = "workSlot") { real ->
            when {
                real == JOB && job != null -> JobCard(job, WorkJobs.state[WorkJobs.keyOf(job)], chat, store)
                real != null -> WorkCards(listOf(real), onWork)
                draft != null -> DraftCard(draft)
            }
        }
    }
}

private const val JOB = "\u0000job"

/**
 * 后台作品的卡（第 4xx 版，原型 Sq8GxFW7NS51trawrHYLyt）：一张卡三种样子，原地变，不换卡——
 * 在写：小方块弹跳 + 阶段字，右边「停下」；停下：小方块落成灰点，「停在第 N 稿」，右边「接着做」；
 * 没写成：红色图标 + 原因，能接着做的右边也是「接着做」。点卡片本身也能接着做。
 */
@Composable
private fun JobCard(job: String, st: WorkJobs.State?, chat: ChatState, store: AssistantStore) {
    val key = WorkJobs.keyOf(job)
    val record = remember(job, st == null) { if (st == null) ArtworkJob.loadOrder(key) else null }
    val running = st != null && st.fail == null && st.done == null
    val stopped = !running && (st?.stopped == true || (st == null && record != null))
    val rev = st?.revision?.takeIf { it > 0 } ?: record?.optInt("revision", 0) ?: 0
    val canRetry = !running && WorkJobs.canRetry(job, record)
    val title = st?.title ?: WorkJobs.titleOf(job)
    val line = when {
        running -> st!!.stage ?: if (st.chars < 200) (if (st.editing) "正在改…" else "正在写…") else "${if (st.editing) "正在改" else "正在写"} · 已写 %.1fk 字".format(st.chars / 1000f)
        stopped -> if (rev > 0) "停在第 $rev 稿" else if (st?.preview != null || record?.optJSONObject("args")?.optBoolean("artwork") == true) "停在这里" else "还没出稿"
        canRetry -> "没写成" + (st?.fail?.let { "（$it）" } ?: "")
        else -> "没写完（App 中途关掉了），让她再做一次"
    }
    val mode = when { running -> 0; stopped -> 1; else -> 2 }
    Column(Modifier.padding(top = 10.dp, bottom = 12.dp)) {
        Pressable({ if (canRetry) WorkJobs.retry(job, record, chat, store) }, Modifier.widthIn(max = 440.dp).fillMaxWidth(), label = title) { pressed ->
            Row(
                Modifier.fillMaxWidth().workCard(pressed && canRetry).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnimatedContent(mode, transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(180)) }, label = "jobTile") { m ->
                    when (m) {
                        0 -> Box(Modifier.size(56.dp).clip(RoundedCornerShape(11.dp)).background(Skin.accent.copy(alpha = .14f))) {
                            ToyTile(Apricot, Modifier.size(56.dp))
                        }
                        1 -> Box(Modifier.size(56.dp).background(veil(.06f), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(12.dp).background(Page.muted.copy(alpha = .55f), CircleShape))
                        }
                        else -> Box(Modifier.size(56.dp).background(Skin.danger.copy(alpha = .12f), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                            SvgIcon(WorkIcons.page, Skin.danger, 24.dp, stroke = 1.5f)
                        }
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Txt(title.ifBlank { "作品" }, Page.ink, 16.sp, family = Serif, maxLines = 1)
                    Spacer(Modifier.height(4.dp))
                    AnimatedContent(line, transitionSpec = {
                        (fadeIn(tween(240)) + slideInVertically(tween(320, easing = Curves.decel)) { it / 2 }) togetherWith
                            (fadeOut(tween(160)) + slideOutVertically(tween(240)) { -it / 2 })
                    }, label = "jobLine") { t ->
                        Txt(t, Page.muted, 12.5.sp, maxLines = 2)
                    }
                }
                if (running || canRetry) {
                    Spacer(Modifier.width(8.dp))
                    JobAction(go = !running) { if (running) WorkJobs.stop(job) else WorkJobs.retry(job, record, chat, store) }
                }
            }
        }
        val file = st?.preview ?: if (running) null else remember(job) { ArtworkJob.latestPreview(key) }
        if (st?.stage != null || !running) ArtworkProgress(file)
    }
}

/** 卡片右边的小胶囊：「■ 停下」↔「▶ 接着做」，底色跟着变。 */
@Composable
private fun JobAction(go: Boolean, onClick: () -> Unit) {
    val fill by animateColorAsState(if (go) Skin.accent.copy(alpha = .18f) else veil(.07f), tween(260), label = "jobActFill")
    val ink by animateColorAsState(if (go) Skin.workIcon else Page.ink, tween(260), label = "jobActInk")
    Pressable(onClick, label = if (go) "接着做" else "停下") { pressed ->
        Row(
            Modifier.graphicsLayer { val k = if (pressed) .97f else 1f; scaleX = k; scaleY = k }
                .background(fill, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (go) SvgIcon(JobIcons.play, ink, 12.dp, stroke = 2.2f)
            else Box(Modifier.size(9.dp).background(ink, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(6.dp))
            Txt(if (go) "接着做" else "停下", ink, 13.sp, maxLines = 1)
        }
    }
}

private object JobIcons {
    val play = Ic("M8 5.5 18.5 12 8 18.5Z")
}

@Composable
internal fun ArtworkProgress(file: java.io.File?) {
    if (file == null) return
    Column(Modifier.widthIn(max = 440.dp).fillMaxWidth().padding(bottom = 10.dp)) {
        val preview by produceState<ImageBitmap?>(null, file) {
            value = withContext(Dispatchers.IO) { file?.let { android.graphics.BitmapFactory.decodeFile(it.path)?.asImageBitmap() } }
        }
        var expanded by remember(file) { mutableStateOf(false) }
        preview?.let { image ->
            Pressable({ expanded = true }, label = "查看画稿") {
                Image(image, "当前画稿", Modifier.fillMaxWidth().height(168.dp))
            }
            if (expanded) Dialog(onDismissRequest = { expanded = false }) {
                Image(image, "当前画稿", Modifier.fillMaxWidth())
            }
        }
    }
}

/** 正在写的卡（她这一轮里直接 make_work / edit_work）：和作品卡一样的块；左边小方块变形弹跳，下面写到多少字。 */
@Composable
private fun DraftCard(d: WorkDraft, stage: String? = null) {
    Column(Modifier.padding(top = 10.dp, bottom = 12.dp)) {
        Row(
            Modifier.widthIn(max = 440.dp).fillMaxWidth().workCard().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(11.dp)).background(Skin.accent.copy(alpha = .14f))) {
                ToyTile(Apricot, Modifier.size(56.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                AnimatedContent(d.title, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(140)) }, label = "draftTitle") { title ->
                    if (title == null) Box(Modifier.padding(vertical = 6.dp).width(110.dp).height(10.dp).background(Page.line.copy(alpha = .6f), RoundedCornerShape(5.dp)))
                    else Txt(title, Page.ink, 16.sp, family = Serif, maxLines = 1)
                }
                Spacer(Modifier.height(4.dp))
                val verb = if (d.editing) "正在改" else "正在写"
                Txt(stage ?: if (d.chars < 200) "$verb…" else "$verb · 已写 %.1fk 字".format(d.chars / 1000f), Page.muted, 12.5.sp, maxLines = 1)
            }
        }
    }
}
