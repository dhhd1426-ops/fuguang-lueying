package com.fuguang.lueying

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.json.JSONObject

/**
 * 聊天里的闹钟卡、日程卡（原型 CDpQRxvKNs1HdRh6VLCZtx）。色块、不描边；落下时轻轻放大浮出。
 * 点闹钟卡打开时钟，点日程卡打开系统日历到那一天。
 */
private val Warm: Color get() = Skin.accent
private val bell = Ic("M6 16V11a6 6 0 0 1 12 0v5l1.5 2h-15ZM10 21h4")

/** clips 里是不是闹钟 / 日程卡。 */
fun isClockCard(l: String) = l.startsWith(ClockTools.P_ALARM) || l.startsWith(ClockTools.P_CAL)

@Composable
fun ClockCard(l: String) {
    val j = remember(l) { runCatching { JSONObject(l.substringAfter(':')) }.getOrNull() } ?: return
    val ctx = LocalContext.current
    val k = remember(l) { Animatable(.94f) }
    LaunchedEffect(l) { k.animateTo(1f, tween(420, easing = CubicBezierEasing(.2f, .9f, .25f, 1f))) }
    val m = Modifier.graphicsLayer { scaleX = k.value; scaleY = k.value; alpha = ((k.value - .94f) / .06f).coerceIn(0f, 1f); transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f) }
    if (l.startsWith(ClockTools.P_ALARM)) AlarmCard(j, m) { open(ctx, Intent(AlarmClock.ACTION_SHOW_ALARMS)) }
    else CalCard(j, m) { at -> open(ctx, Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build(), at))) }
}

private fun open(ctx: Context, i: Intent) { runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }

@Composable
private fun AlarmCard(j: JSONObject, m: Modifier, onOpen: () -> Unit) {
    val off = j.optBoolean("off")
    PressSurface(onOpen, m.widthIn(max = 300.dp), shape = RoundedCornerShape(18.dp), fill = veil(.06f), pressScale = .98f, label = "在时钟里看") {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f, fill = false)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Txt(j.optString("t"), if (off) Page.muted else Page.ink, 30.sp, maxLines = 1)
                    Spacer(Modifier.width(6.dp))
                    Txt(j.optString("w"), Page.muted, 13.sp, Modifier.padding(bottom = 5.dp), maxLines = 1)
                }
                j.optString("l").takeIf { it.isNotBlank() }?.let { Txt(it, Page.muted, 12.5.sp, Modifier.padding(top = 2.dp), maxLines = 1) }
                Txt(if (off) "关掉了 · 在时钟里看 ›" else "在时钟里看 ›", Warm, 12.sp, Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.width(20.dp))
            // 开着的开关：只是样子，开关在时钟里
            Box(Modifier.size(40.dp, 24.dp).background(if (off) veil(.14f) else Warm, RoundedCornerShape(12.dp)), contentAlignment = if (off) Alignment.CenterStart else Alignment.CenterEnd) {
                Box(Modifier.padding(horizontal = 3.dp).size(18.dp).background(if (off) Page.muted else Color(0xFF2A1D14), CircleShape))
            }
        }
    }
}

private fun local(ms: Long) = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())
private fun hm(t: LocalDateTime) = "%d:%02d".format(t.hour, t.minute)

@Composable
private fun CalCard(j: JSONObject, m: Modifier, onOpen: (Long) -> Unit) {
    val kind = j.optString("k")
    val evs = remember(j) { j.optJSONArray("ev")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty() }
    val first = evs.firstOrNull()?.optLong("b") ?: java.time.LocalDate.parse(j.optString("from").ifEmpty { java.time.LocalDate.now().toString() }).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    PressSurface({ onOpen(first) }, m.widthIn(max = 310.dp), shape = RoundedCornerShape(18.dp), fill = veil(.06f), pressScale = .985f, label = "在日历里看") {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            if (kind == "list") ListBody(j, evs) else evs.firstOrNull()?.let { OneEvent(it, kind == "del") }
            Txt(if (kind == "del") "已从日历删掉" else "在日历里看 ›", if (kind == "del") Page.muted else Warm, 12.sp, Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun OneEvent(e: JSONObject, gone: Boolean) {
    val b = local(e.optLong("b")); val en = local(e.optLong("e"))
    Row(Modifier.graphicsLayer { alpha = if (gone) .55f else 1f }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(46.dp).background(veil(.06f), RoundedCornerShape(12.dp)).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Txt("${b.dayOfMonth}", Page.ink, 19.sp, maxLines = 1)
            Txt(ClockTools.dayWord(b.toLocalDate()).substringAfter(' ').ifEmpty { "" }, Page.muted, 11.sp, maxLines = 1)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Txt(e.optString("t"), Page.ink, 14.5.sp, maxLines = 2)
            val where = e.optString("l").takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
            Txt((if (e.optBoolean("a")) "全天" else "${hm(b)}–${hm(en)}") + where, Page.muted, 12.5.sp, Modifier.padding(top = 2.dp), maxLines = 1)
            val r = e.optInt("r", -1)
            if (r >= 0 && !gone) Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                SvgIcon(bell, Warm, 12.dp, stroke = 1.6f)
                Spacer(Modifier.width(4.dp))
                Txt(if (r == 0) "准点提醒" else if (r % 60 == 0) "提前 ${r / 60} 小时提醒" else "提前 $r 分钟提醒", Warm, 12.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ListBody(j: JSONObject, evs: List<JSONObject>) {
    val from = runCatching { java.time.LocalDate.parse(j.optString("from")) }.getOrNull() ?: return
    val to = runCatching { java.time.LocalDate.parse(j.optString("to")) }.getOrNull() ?: from
    val byDay = evs.groupBy { local(it.optLong("b")).toLocalDate() }
    // 一周以内的空日子也列出来写「空着」；更长就只列有事的那几天
    val days = if (to.toEpochDay() - from.toEpochDay() <= 7) generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList() else byDay.keys.sorted()
    if (evs.isEmpty() && days.size > 7) { Txt("这段时间日历上什么都没有", Page.muted, 13.sp); return }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        days.forEachIndexed { i, d ->
            if (i > 0) Box(Modifier.padding(vertical = 4.dp).height(1.dp).widthIn(min = 200.dp).background(veil(.06f)))
            Txt(ClockTools.dayWord(d).let { w -> if (w.startsWith("今天") || w.startsWith("明天") || w.startsWith("后天")) w + " ${d.monthValue}月${d.dayOfMonth}日" else w }, Page.muted, 11.5.sp, Modifier.padding(top = 4.dp, bottom = 2.dp), maxLines = 1)
            val list = byDay[d].orEmpty()
            if (list.isEmpty()) Txt("空着", Page.muted, 12.5.sp, Modifier.padding(vertical = 3.dp))
            list.forEach { e ->
                Row(Modifier.height(IntrinsicSize.Min).padding(vertical = 5.dp)) {
                    val c = e.optInt("c"); Box(Modifier.width(3.dp).fillMaxHeight().background(if (c != 0) Color(c) else Warm, RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Txt(e.optString("t"), Page.ink, 14.sp, maxLines = 1)
                        val b = local(e.optLong("b")); val en = local(e.optLong("e"))
                        Txt((if (e.optBoolean("a")) "全天" else "${hm(b)}–${hm(en)}") + (e.optString("l").takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), Page.muted, 12.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}
