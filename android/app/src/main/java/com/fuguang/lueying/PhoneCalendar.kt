package com.fuguang.lueying

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.TimeZone

/** 系统日历里的一件事。all 是全天；color 是那个日历的颜色。 */
data class CalEvent(val id: Long, val title: String, val begin: Long, val end: Long, val all: Boolean, val loc: String, val color: Int, val remind: Int = -1)

/**
 * 读写手机系统日历（CalendarContract）：查一段时间的日程、加、改、删。提醒交给系统日历，到点它来响。
 * 时间都按手机当地时间；全天的照日历的规矩存成 UTC 零点。
 */
object PhoneCalendar {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** 「2026-10-29 15:00」「2026-10-29T15:00」「2026-10-29」都认；只有日期时 second = true。 */
    fun parse(s: String): Pair<LocalDateTime, Boolean>? {
        val t = s.trim().replace('T', ' ').replace('/', '-')
        runCatching { return LocalDateTime.parse(t, DateTimeFormatter.ofPattern("yyyy-M-d H:mm")) to false }
        runCatching { return LocalDateTime.parse(t, DateTimeFormatter.ofPattern("yyyy-M-d H:mm:ss")) to false }
        runCatching { return LocalDate.parse(t, DateTimeFormatter.ofPattern("yyyy-M-d")).atStartOfDay() to true }
        return null
    }

    fun millis(t: LocalDateTime): Long = t.atZone(zone).toInstant().toEpochMilli()

    fun list(ctx: Context, from: Long, to: Long): List<CalEvent> {
        val uri = Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, from); ContentUris.appendId(it, to) }.build()
        val proj = arrayOf(Instances.EVENT_ID, Instances.TITLE, Instances.BEGIN, Instances.END, Instances.ALL_DAY, Instances.EVENT_LOCATION, Instances.DISPLAY_COLOR)
        val out = ArrayList<CalEvent>()
        ctx.contentResolver.query(uri, proj, null, null, Instances.BEGIN + " ASC")?.use { c ->
            while (c.moveToNext() && out.size < 80) {
                val all = c.getInt(4) == 1
                // 全天的存的是 UTC 零点：换回当地那一天
                val b = c.getLong(2); val e = c.getLong(3)
                out += CalEvent(c.getLong(0), c.getString(1).orEmpty().ifBlank { "（没有标题）" }, if (all) utcDay(b) else b, if (all) utcDay(e) else e, all, c.getString(5).orEmpty(), c.getInt(6))
            }
        }
        return out
    }

    private fun utcDay(ms: Long): Long = millis(java.time.Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay())

    /** 能往里写的那本日历：优先主日历，否则第一本看得见、能写的。 */
    private fun target(ctx: Context): Long? {
        val proj = arrayOf(Calendars._ID, Calendars.IS_PRIMARY, Calendars.CALENDAR_ACCESS_LEVEL, Calendars.VISIBLE)
        var first: Long? = null
        ctx.contentResolver.query(Calendars.CONTENT_URI, proj, Calendars.VISIBLE + "=1 AND " + Calendars.CALENDAR_ACCESS_LEVEL + ">=" + Calendars.CAL_ACCESS_CONTRIBUTOR, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(1) == 1) return c.getLong(0)
                if (first == null) first = c.getLong(0)
            }
        }
        return first
    }

    private fun times(v: ContentValues, start: LocalDateTime, end: LocalDateTime?, all: Boolean) {
        if (all) {
            val d = start.toLocalDate(); val e = (end?.toLocalDate()?.takeIf { it.isAfter(d) } ?: d.plusDays(1))
            v.put(Events.DTSTART, d.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
            v.put(Events.DTEND, e.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
            v.put(Events.EVENT_TIMEZONE, "UTC"); v.put(Events.ALL_DAY, 1)
        } else {
            v.put(Events.DTSTART, millis(start))
            v.put(Events.DTEND, millis(end?.takeIf { it.isAfter(start) } ?: start.plusHours(1)))
            v.put(Events.EVENT_TIMEZONE, TimeZone.getDefault().id); v.put(Events.ALL_DAY, 0)
        }
    }

    private fun remind(ctx: Context, id: Long, minutes: Int) {
        ctx.contentResolver.delete(Reminders.CONTENT_URI, Reminders.EVENT_ID + "=?", arrayOf(id.toString()))
        if (minutes < 0) return
        ctx.contentResolver.insert(Reminders.CONTENT_URI, ContentValues().apply {
            put(Reminders.EVENT_ID, id); put(Reminders.MINUTES, minutes); put(Reminders.METHOD, Reminders.METHOD_ALERT)
        })
    }

    /** 加一件；返回新的 id，没有能写的日历时抛错。 */
    fun add(ctx: Context, title: String, start: LocalDateTime, end: LocalDateTime?, all: Boolean, loc: String, note: String, remindMin: Int): Long {
        val cal = target(ctx) ?: error("手机上没有能写的日历：先打开系统日历 App 建一个本地日历或登一个账号")
        val v = ContentValues().apply {
            put(Events.CALENDAR_ID, cal); put(Events.TITLE, title)
            if (loc.isNotBlank()) put(Events.EVENT_LOCATION, loc)
            if (note.isNotBlank()) put(Events.DESCRIPTION, note)
        }
        times(v, start, end, all)
        val id = ctx.contentResolver.insert(Events.CONTENT_URI, v)?.let { ContentUris.parseId(it) } ?: error("日历没让写进去")
        remind(ctx, id, remindMin)
        return id
    }

    fun get(ctx: Context, id: Long): CalEvent? {
        val proj = arrayOf(Events._ID, Events.TITLE, Events.DTSTART, Events.DTEND, Events.ALL_DAY, Events.EVENT_LOCATION, Events.DISPLAY_COLOR)
        ctx.contentResolver.query(ContentUris.withAppendedId(Events.CONTENT_URI, id), proj, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            val all = c.getInt(4) == 1
            val b = c.getLong(2); val e = if (c.isNull(3)) b + 3_600_000 else c.getLong(3)
            return CalEvent(c.getLong(0), c.getString(1).orEmpty(), if (all) utcDay(b) else b, if (all) utcDay(e) else e, all, c.getString(5).orEmpty(), c.getInt(6), reminderOf(ctx, id))
        }
        return null
    }

    private fun reminderOf(ctx: Context, id: Long): Int =
        ctx.contentResolver.query(Reminders.CONTENT_URI, arrayOf(Reminders.MINUTES), Reminders.EVENT_ID + "=?", arrayOf(id.toString()), Reminders.MINUTES + " ASC")
            ?.use { c -> if (c.moveToFirst()) c.getInt(0) else -1 } ?: -1

    /** 改一件：只改给了的那几样。 */
    fun edit(ctx: Context, id: Long, title: String?, start: LocalDateTime?, end: LocalDateTime?, all: Boolean?, loc: String?, note: String?, remindMin: Int?): Boolean {
        val old = get(ctx, id) ?: return false
        val v = ContentValues()
        title?.let { v.put(Events.TITLE, it) }
        loc?.let { v.put(Events.EVENT_LOCATION, it) }
        note?.let { v.put(Events.DESCRIPTION, it) }
        if (start != null || end != null || all != null) {
            val s = start ?: LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(old.begin), zone)
            val dur = old.end - old.begin
            val e = end ?: if (start != null) s.plusNanos(dur * 1_000_000) else LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(old.end), zone)
            times(v, s, e, all ?: old.all)
        }
        if (v.size() > 0) ctx.contentResolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, id), v, null, null)
        remindMin?.let { remind(ctx, id, it) }
        return true
    }

    fun delete(ctx: Context, id: Long): Boolean = ctx.contentResolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, id), null, null) > 0
}
