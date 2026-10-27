package com.fuguang.lueying

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

/**
 * 她手边的闹钟和日历（原型 CDpQRxvKNs1HdRh6VLCZtx）。
 * 闹钟：直接定进系统时钟（不跳界面）；安卓不让读、删时钟里已有的闹钟，只看得到下一个要响的。
 * 日历：查、加、改、删系统日历里的日程，提醒交给系统日历；加的时候她可以写一句到点想对你说的话，到点用她的通知发出来。
 * 每次在聊天里落一张卡（ClockCards.kt），卡的数据放在消息的 clips 里：alarm:{…} / cal:{…}。
 */
object ClockTools {
    const val ALARM = "set_alarm"
    const val NEXT = "next_alarm"
    const val OFF = "cancel_alarm"
    const val LIST = "calendar_list"
    const val ADD = "calendar_add"
    const val EDIT = "calendar_edit"
    const val DEL = "calendar_delete"
    val names = setOf(ALARM, NEXT, OFF, LIST, ADD, EDIT, DEL)
    const val P_ALARM = "alarm:"
    const val P_CAL = "cal:"

    private var app: Context? = null
    fun init(ctx: Context) { app = ctx.applicationContext }

    private fun obj(vararg p: Pair<String, JSONObject>, req: List<String> = emptyList()) =
        JSONObject().put("type", "object").put("properties", JSONObject().apply { p.forEach { put(it.first, it.second) } }).put("required", JSONArray(req))
    private fun str(d: String) = JSONObject().put("type", "string").put("description", d)
    private fun int(d: String) = JSONObject().put("type", "integer").put("description", d)

    val specs = listOf(
        ToolSpec(ALARM, "在他手机的系统时钟里定一个闹钟（直接定好，不跳界面）。",
            obj("time" to str("几点，24 小时制，如 07:00"), "date" to str("哪天响，如 2026-10-28；只响一次时照你算出的日子写上"), "label" to str("闹钟上写的字，几个字，可不写"),
                "days" to JSONObject().put("type", "array").put("items", JSONObject().put("type", "integer")).put("description", "每周重复哪几天：1=周一 … 7=周日；只响一次就不写"), req = listOf("time"))),
        ToolSpec(NEXT, "看他手机上下一个要响的闹钟是几点（只看得到这一个）。", obj()),
        ToolSpec(OFF, "关掉他手机时钟里的一个闹钟：按几点找，或按闹钟上的字找。",
            obj("time" to str("要关的那个是几点，如 09:00"), "label" to str("或者按闹钟上的字找"))),
        ToolSpec(LIST, "看他手机日历里一段时间的日程。",
            obj("from" to str("从哪天起，如 2026-10-27"), "to" to str("到哪天（含这天），如 2026-11-02"), req = listOf("from", "to"))),
        ToolSpec(ADD, "往他手机日历里加一件日程，到点由系统日历提醒他。",
            obj("title" to str("写什么，简短"), "start" to str("开始，当地时间，如 2026-10-29 15:00；全天就只写日期"),
                "end" to str("结束，可不写（默认一小时）"), "all_day" to JSONObject().put("type", "boolean").put("description", "是不是全天"),
                "location" to str("在哪儿，可不写"), "note" to str("备注，可不写"),
                "remind" to int("提前几分钟提醒，0 是准点；不要提醒写 -1；不写默认 15"),
                "say" to str("到提醒那一刻你想对他说的一句话（你记得的、和这件事有关的小事），可不写"), req = listOf("title", "start"))),
        ToolSpec(EDIT, "改他日历里的一件日程（id 从 calendar_list 或 calendar_add 来），只写要改的。只在他明说要改时用。",
            obj("id" to int("日程 id"), "title" to str("新标题"), "start" to str("新开始时间"), "end" to str("新结束时间"),
                "location" to str("新地点"), "remind" to int("提前几分钟提醒，-1 不提醒"), req = listOf("id"))),
        ToolSpec(DEL, "删掉他日历里的一件日程。只在他明说要删时用。", obj("id" to int("日程 id"), req = listOf("id"))),
    )

    fun system(call: String): String =
        "你能帮${call}在手机上定闹钟，也能看、加、改、删他手机日历里的日程，到点系统会提醒他。" +
            "闹钟你只看得到下一个要响的那个，看不到全部；他要关哪个，用 $OFF 按几点或闹钟上的字关掉。" +
            "系统时钟只认几点、不认哪天：只响一次的闹钟总在最近一次的那个点响。定之前先照现在的时间算清他要的是哪天，写进 date；不是最近那次的话定不了，照工具结果如实说。" +
            "说到具体时间就照他当地的时间算，「明早」「周三下午」这种换成具体日期再用。改、删日程只在他明说时做，删之前没把握是哪件就先问。" +
            "每次聊天里都会落一张卡，时间地点卡上都有，回复里不用再一条条念，说一两句要紧的就好。权限没给就如实说，告诉他在设置里给 Vignette 打开日历权限。"

    private val zone get() = ZoneId.systemDefault()
    private val WEEK = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    fun dayWord(d: LocalDate): String {
        val today = LocalDate.now()
        val w = WEEK[d.dayOfWeek.value - 1]
        return when (d) { today -> "今天 $w"; today.plusDays(1) -> "明天 $w"; today.plusDays(2) -> "后天 $w"; else -> "${d.monthValue}月${d.dayOfMonth}日 $w" }
    }

    private val said = HashMap<String, String>()
    fun doneTitle(n: String, a: JSONObject): String = synchronized(said) { said[n + a.toString()] } ?: when (n) {
        ALARM -> "定了闹钟"; NEXT -> "看了下一个闹钟"; OFF -> "关了闹钟"; LIST -> "看了日历"; ADD -> "加了日程"; EDIT -> "改了日程"; else -> "删了日程"
    }
    fun liveTitle(n: String) = when (n) { ALARM -> "正在定闹钟"; NEXT -> "正在看闹钟"; OFF -> "正在关闹钟"; LIST -> "正在看日历"; ADD -> "正在加日程"; EDIT -> "正在改日程"; else -> "正在删日程" }
    private fun note(n: String, a: JSONObject, t: String) = synchronized(said) { said[n + a.toString()] = t }

    suspend fun run(n: String, a: JSONObject, pid: String, onCard: (String) -> Unit): ToolOut {
        val ctx = app ?: return ToolOut("这会儿用不了")
        return when (n) {
            ALARM -> alarm(ctx, a, onCard)
            NEXT -> next(ctx, a)
            OFF -> off(ctx, a, onCard)
            else -> {
                val write = n != LIST
                val perms = if (write) arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR) else arrayOf(Manifest.permission.READ_CALENDAR)
                if (!Perms.need(ctx, *perms)) return ToolOut("他没给日历权限（或这会儿 App 不在前台问不了）。如实说，告诉他在 设置 → 应用 → Vignette → 权限 里打开日历。")
                when (n) { LIST -> list(ctx, a, onCard); ADD -> add(ctx, a, pid, onCard); EDIT -> edit(ctx, a, onCard); else -> delete(ctx, a, onCard) }
            }
        }
    }

    private fun alarm(ctx: Context, a: JSONObject, onCard: (String) -> Unit): ToolOut {
        val m = Regex("(\\d{1,2})[:：点](\\d{0,2})").find(a.optString("time")) ?: return ToolOut("时间没看懂，写成 07:00 这样")
        val h = m.groupValues[1].toInt().coerceIn(0, 23); val mi = m.groupValues[2].ifEmpty { "0" }.toInt().coerceIn(0, 59)
        val label = a.optString("label").trim().take(30)
        val days = a.optJSONArray("days")?.let { arr -> (0 until arr.length()).map { arr.optInt(it) }.filter { it in 1..7 }.distinct().sorted() }.orEmpty()
        val now = LocalDateTime.now()
        val nearest = if (now.toLocalTime() < java.time.LocalTime.of(h, mi)) now.toLocalDate() else now.toLocalDate().plusDays(1)
        val want = PhoneCalendar.parse(a.optString("date"))?.first?.toLocalDate()
        // 只响一次、又不是最近那次：系统时钟做不到（它只认几点），先不定，如实告诉她
        if (days.isEmpty() && want != null && want != nearest)
            return ToolOut("没定：系统时钟只认几点、不认哪天，现在定 %d:%02d 会在${dayWord(nearest)}先响，定不到${dayWord(want)}。".format(h, mi) +
                "可以告诉他：等${dayWord(nearest)}这个点过了再说一声，你再定；或者用 $ADD 在日历里记一条，到点是通知提醒、不会像闹钟一样一直响。")
        val i = Intent(AlarmClock.ACTION_SET_ALARM).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, mi).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (label.isNotEmpty()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        // 安卓的星期：周日 = 1，周一 = 2 …
        if (days.isNotEmpty()) i.putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days.map { if (it == 7) 1 else it + 1 }))
        try { ctx.startActivity(i) } catch (e: Exception) {
            return ToolOut("没定成：" + (if (e is android.content.ActivityNotFoundException) "手机上没有认这个的时钟 App" else e.message ?: e.javaClass.simpleName).take(60) + "。如实告诉他，可以请他自己在时钟里定。")
        }
        val whenTxt = if (days.isNotEmpty()) (if (days.size == 7) "每天" else if (days == listOf(1, 2, 3, 4, 5)) "工作日" else "每" + days.joinToString("、") { WEEK[it - 1] })
            else dayWord(nearest)
        val hm = "%d:%02d".format(h, mi)
        onCard(P_ALARM + JSONObject().put("t", hm).put("w", whenTxt).put("l", label).put("at", System.currentTimeMillis()))
        note(ALARM, a, "定了闹钟 · $whenTxt $hm")
        return ToolOut("定好了：$whenTxt $hm" + (if (label.isNotEmpty()) "「$label」" else "") + "。聊天里有一张闹钟卡。")
    }

    /** 关闹钟：交给时钟按几点 / 按字找（有的时钟会弹一下确认）。 */
    private fun off(ctx: Context, a: JSONObject, onCard: (String) -> Unit): ToolOut {
        val m = Regex("(\\d{1,2})[:：点](\\d{0,2})").find(a.optString("time"))
        val label = a.optString("label").trim()
        if (m == null && label.isEmpty()) return ToolOut("要说是几点那个，或者闹钟上写的字")
        val i = Intent(AlarmClock.ACTION_DISMISS_ALARM).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        var hm = ""
        if (m != null) {
            val h = m.groupValues[1].toInt().coerceIn(0, 23); val mi = m.groupValues[2].ifEmpty { "0" }.toInt().coerceIn(0, 59)
            hm = "%d:%02d".format(h, mi)
            i.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME).putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, mi)
                .putExtra(AlarmClock.EXTRA_IS_PM, h >= 12)
        } else i.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_LABEL).putExtra(AlarmClock.EXTRA_MESSAGE, label)
        try { ctx.startActivity(i) } catch (e: Exception) {
            return ToolOut("没关成：" + (if (e is android.content.ActivityNotFoundException) "手机上的时钟不认这个" else e.message ?: e.javaClass.simpleName).take(60) + "。如实说，请他点闹钟卡在时钟里关。")
        }
        onCard(P_ALARM + JSONObject().put("t", hm.ifEmpty { label.take(8) }).put("w", if (hm.isNotEmpty() && label.isNotEmpty()) label else "").put("l", if (hm.isEmpty()) "" else label).put("off", true).put("at", System.currentTimeMillis()))
        note(OFF, a, "关了闹钟 · " + hm.ifEmpty { "「$label」" })
        return ToolOut("交给时钟关掉了 ${hm.ifEmpty { "「$label」" }} 那个。有的时钟会弹一下让他确认，或者有好几个对得上时让他挑，提一句就好。")
    }

    private fun next(ctx: Context, a: JSONObject): ToolOut {
        val t = (ctx.getSystemService(AlarmManager::class.java))?.nextAlarmClock?.triggerTime ?: return ToolOut("他手机上现在没有要响的闹钟。")
        val d = LocalDateTime.ofInstant(Instant.ofEpochMilli(t), zone)
        val s = dayWord(d.toLocalDate()) + " %d:%02d".format(d.hour, d.minute)
        note(NEXT, a, "下一个闹钟 · $s")
        return ToolOut("下一个要响的闹钟：$s。别的闹钟你看不到。")
    }

    private fun evJson(e: CalEvent) = JSONObject().put("id", e.id).put("t", e.title).put("b", e.begin).put("e", e.end).put("a", e.all).put("l", e.loc).put("c", e.color).put("r", e.remind)

    private fun evText(e: CalEvent): String {
        val b = LocalDateTime.ofInstant(Instant.ofEpochMilli(e.begin), zone); val en = LocalDateTime.ofInstant(Instant.ofEpochMilli(e.end), zone)
        val time = if (e.all) "全天" else "%d:%02d–%d:%02d".format(b.hour, b.minute, en.hour, en.minute)
        return "[id ${e.id}] ${dayWord(b.toLocalDate())} $time ${e.title}" + (if (e.loc.isNotBlank()) " @${e.loc}" else "")
    }

    private fun list(ctx: Context, a: JSONObject, onCard: (String) -> Unit): ToolOut {
        val f = PhoneCalendar.parse(a.optString("from"))?.first?.toLocalDate() ?: LocalDate.now()
        val t = (PhoneCalendar.parse(a.optString("to"))?.first?.toLocalDate() ?: f).let { if (it.isBefore(f)) f else it }.coerceAtMost(f.plusDays(62))
        val evs = PhoneCalendar.list(ctx, PhoneCalendar.millis(f.atStartOfDay()), PhoneCalendar.millis(t.plusDays(1).atStartOfDay()))
        onCard(P_CAL + JSONObject().put("k", "list").put("from", f.toString()).put("to", t.toString()).put("ev", JSONArray(evs.map(::evJson))).put("at", System.currentTimeMillis()))
        note(LIST, a, if (f == t) "看了日历 · ${dayWord(f)}" else "看了日历 · ${f.monthValue}月${f.dayOfMonth}日到${t.monthValue}月${t.dayOfMonth}日")
        return ToolOut(if (evs.isEmpty()) "这段时间日历上什么都没有。" else evs.joinToString("\n", "这段时间的日程（聊天里有一张卡按天列着）：\n") { evText(it) })
    }

    private fun add(ctx: Context, a: JSONObject, pid: String, onCard: (String) -> Unit): ToolOut {
        val title = a.optString("title").trim().ifEmpty { return ToolOut("要写个标题") }.take(60)
        val (start, dateOnly) = PhoneCalendar.parse(a.optString("start")) ?: return ToolOut("开始时间没看懂，写成 2026-10-29 15:00")
        val all = a.optBoolean("all_day", dateOnly)
        val end = PhoneCalendar.parse(a.optString("end"))?.first
        val remind = if (a.has("remind")) a.optInt("remind", 15) else 15
        val id = PhoneCalendar.add(ctx, title, start, end, all, a.optString("location").trim(), a.optString("note").trim(), remind)
        val e = PhoneCalendar.get(ctx, id) ?: CalEvent(id, title, PhoneCalendar.millis(start), PhoneCalendar.millis(end ?: start.plusHours(1)), all, a.optString("location"), 0, remind)
        a.optString("say").trim().takeIf { it.isNotEmpty() && remind >= 0 }?.let { HerNote.schedule(ctx, id, pid, title, it, (if (all) e.begin + 9 * 3_600_000 else e.begin) - remind * 60_000L) }
        onCard(P_CAL + JSONObject().put("k", "add").put("ev", JSONArray().put(evJson(e))).put("at", System.currentTimeMillis()))
        note(ADD, a, "加了日程「${title.take(14)}」")
        return ToolOut("加好了：" + evText(e) + (if (remind >= 0) "，提前 $remind 分钟提醒" else "，不提醒") + "。聊天里有一张日程卡。")
    }

    private fun edit(ctx: Context, a: JSONObject, onCard: (String) -> Unit): ToolOut {
        val id = a.optLong("id", -1)
        fun s(k: String) = if (a.has(k)) a.optString(k).trim() else null
        val st = s("start")?.let { PhoneCalendar.parse(it) }
        val ok = PhoneCalendar.edit(ctx, id, s("title"), st?.first, s("end")?.let { PhoneCalendar.parse(it)?.first }, st?.second, s("location"), null, if (a.has("remind")) a.optInt("remind") else null)
        if (!ok) return ToolOut("没找到 id $id 这件日程，先用 $LIST 看看")
        val e = PhoneCalendar.get(ctx, id) ?: return ToolOut("改好了")
        onCard(P_CAL + JSONObject().put("k", "edit").put("ev", JSONArray().put(evJson(e))).put("at", System.currentTimeMillis()))
        note(EDIT, a, "改了日程「${e.title.take(14)}」")
        return ToolOut("改好了：" + evText(e))
    }

    private fun delete(ctx: Context, a: JSONObject, onCard: (String) -> Unit): ToolOut {
        val id = a.optLong("id", -1)
        val e = PhoneCalendar.get(ctx, id) ?: return ToolOut("没找到 id $id 这件日程")
        if (!PhoneCalendar.delete(ctx, id)) return ToolOut("日历没让删")
        HerNote.cancel(ctx, id)
        onCard(P_CAL + JSONObject().put("k", "del").put("ev", JSONArray().put(evJson(e))).put("at", System.currentTimeMillis()))
        note(DEL, a, "删了日程「${e.title.take(14)}」")
        return ToolOut("删掉了：" + evText(e))
    }
}

/** 加日程时她写的那句话：到提醒那一刻用她的通知发出来（系统日历那条照常响）。手机重启后这句会丢，系统日历的提醒不受影响。 */
object HerNote {
    private fun pi(ctx: Context, id: Long, pid: String = "", title: String = "", text: String = "") = PendingIntent.getBroadcast(
        ctx, ("note$id").hashCode(),
        Intent(ctx, HerNoteReceiver::class.java).putExtra("id", id).putExtra("pid", pid).putExtra("name", Memory.get(pid)?.name.orEmpty()).putExtra("title", title).putExtra("text", text),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun schedule(ctx: Context, id: Long, pid: String, title: String, text: String, at: Long) {
        if (at <= System.currentTimeMillis()) return
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val p = pi(ctx, id, pid, title, text)
        runCatching {
            if (android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        }
    }

    fun cancel(ctx: Context, id: Long) { runCatching { ctx.getSystemService(AlarmManager::class.java)?.cancel(pi(ctx, id)) } }
}

class HerNoteReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Notify.init(ctx)
        Notify.herNote(ctx, intent.getStringExtra("pid").orEmpty(), intent.getStringExtra("name").orEmpty(), intent.getLongExtra("id", 0), intent.getStringExtra("title").orEmpty(), intent.getStringExtra("text").orEmpty())
    }
}
