package com.fuguang.lueying

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** 一言里的一句：你自己写的。 */
data class YLine(val id: String, val t: String)

/**
 * 一言（手记里你自己写的句子）和桌面小组件「每日一言」。
 * 存在 prefs `fuguang` 里（跟着备份走）：`yiyan` 句子、`yiyan-cur` 桌面上那句、`yiyan-next` 下次换的时刻、`yiyan-side` 小组件显示的是哪一面。
 * 每 2 小时随机换一句（不和上一句重复）；刚写的一句马上放上去；改了桌面上那句跟着改，删了就换一句。
 */
object Yiyan {
    const val EVERY = 2 * 60 * 60 * 1000L
    const val TICK = "com.fuguang.lueying.YIYAN_TICK"
    private const val EMPTY = "在手记里写下第一句"

    val items = mutableStateListOf<YLine>()
    var cur by mutableStateOf<String?>(null)
        private set
    private var loaded = false
    private lateinit var app: Context

    private fun sp(c: Context) = c.getSharedPreferences("fuguang", Context.MODE_PRIVATE)

    /** 只读一次（换主题时 Activity 重建，init 会再走一遍）。 */
    fun init(ctx: Context) {
        app = ctx.applicationContext
        if (loaded) return
        loaded = true
        val sp = sp(app)
        val seen = HashSet<String>()
        runCatching {
            val a = JSONArray(sp.getString("yiyan", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val l = YLine(o.getString("i"), o.getString("t"))
                if (seen.add(l.id)) items += l
            }
        }
        cur = sp.getString("yiyan-cur", null)?.takeIf { id -> items.any { it.id == id } }
        if (cur == null && items.isNotEmpty()) cur = items.first().id
    }

    fun text(): String? = items.firstOrNull { it.id == cur }?.t

    private fun save() {
        val a = JSONArray()
        items.forEach { a.put(JSONObject().put("i", it.id).put("t", it.t)) }
        sp(app).edit().putString("yiyan", a.toString()).putString("yiyan-cur", cur).apply()
    }

    private fun newId(): String {
        var n = System.currentTimeMillis()
        while (items.any { it.id == n.toString(36) }) n++
        return n.toString(36)
    }

    /** 写一句：放在最上面，桌面马上换成它。 */
    fun add(t: String) {
        val v = t.trim().ifEmpty { return }
        val l = YLine(newId(), v)
        items.add(0, l)
        cur = l.id
        save()
        show(true, again = true)
    }

    fun edit(id: String, t: String) {
        val i = items.indexOfFirst { it.id == id }
        val v = t.trim()
        if (i < 0 || v.isEmpty() || items[i].t == v) return
        items[i] = items[i].copy(t = v)
        save()
        if (id == cur) show(false)
    }

    /** 删掉一句，返回它原来的位置和它是不是桌面上那句（撤销用）。 */
    fun remove(id: String): Triple<YLine, Int, Boolean>? {
        val i = items.indexOfFirst { it.id == id }
        if (i < 0) return null
        val l = items.removeAt(i)
        val was = id == cur
        if (was) pick()
        save()
        if (was) show(true)
        return Triple(l, i, was)
    }

    fun restore(l: YLine, i: Int, was: Boolean) {
        if (items.any { it.id == l.id }) return
        items.add(i.coerceIn(0, items.size), l)
        if (was) cur = l.id
        save()
        if (was) show(true)
    }

    /** 从 txt 导入：一行一句，已经有的跳过，最多 500 句。返回加了几句。 */
    fun importText(text: String): Int {
        val have = items.map { it.t }.toHashSet()
        val add = text.lines().map { it.trim() }.filter { it.isNotEmpty() && have.add(it) }.take(500)
        if (add.isEmpty()) return 0
        var n = System.currentTimeMillis()
        val ls = add.map { YLine((n++).toString(36) + "i", it) }
        items.addAll(0, ls)
        if (cur == null) cur = ls.first().id
        save()
        show(true)
        return ls.size
    }

    private fun pick() {
        if (items.isEmpty()) { cur = null; return }
        val pool = items.filter { it.id != cur }.ifEmpty { items.toList() }
        cur = pool.random().id
    }

    /** 到点（闹钟或系统的定时更新）：过了 2 小时就换一句。留 10 分钟余量，免得系统早到一点就白等一轮。 */
    fun tick(ctx: Context) {
        init(ctx)
        val next = sp(app).getLong("yiyan-next", 0L)
        if (System.currentTimeMillis() >= next - 10 * 60_000L) {
            pick(); save(); show(true, again = true)
        } else render()
    }

    private fun ids(): IntArray =
        AppWidgetManager.getInstance(app).getAppWidgetIds(ComponentName(app, YiyanWidget::class.java))

    /** 换到这一句。[animate]：翻到另一面（淡出上移 → 淡入）；否则原地改字。[again]：从现在起重新数 2 小时。 */
    private fun show(animate: Boolean, again: Boolean = false) {
        val sp = sp(app)
        if (again) sp.edit().putLong("yiyan-next", System.currentTimeMillis() + EVERY).apply()
        schedule()
        val ids = ids()
        if (ids.isEmpty()) return
        val m = AppWidgetManager.getInstance(app)
        var side = sp.getInt("yiyan-side", 0)
        val rv = RemoteViews(app.packageName, R.layout.widget_yiyan)
        if (animate) {
            side = 1 - side
            sp.edit().putInt("yiyan-side", side).apply()
            face(rv, if (side == 0) R.id.yiyan_a else R.id.yiyan_b)
            rv.showNext(R.id.yiyan_flip)
        } else face(rv, if (side == 0) R.id.yiyan_a else R.id.yiyan_b)
        m.partiallyUpdateAppWidget(ids, rv)
    }

    /** 整张重画（摆上桌面、改大小、系统要求时）：两面都写同一句，停在当前那一面。 */
    fun render() {
        schedule()
        val ids = ids()
        if (ids.isEmpty()) return
        val rv = RemoteViews(app.packageName, R.layout.widget_yiyan)
        face(rv, R.id.yiyan_a); face(rv, R.id.yiyan_b)
        rv.setDisplayedChild(R.id.yiyan_flip, sp(app).getInt("yiyan-side", 0))
        AppWidgetManager.getInstance(app).updateAppWidget(ids, rv)
    }

    private fun face(rv: RemoteViews, id: Int) {
        val t = text()
        rv.setTextViewText(id, t ?: EMPTY)
        rv.setTextColor(id, if (t == null) 0x99F6F4F0.toInt() else 0xFFF6F4F0.toInt())
    }

    /** 下次换句的闹钟：不唤醒手机，亮屏时到点就换。桌面上没有小组件就不排。 */
    private fun schedule() {
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            app, 7301, Intent(app, YiyanWidget::class.java).setAction(TICK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (ids().isEmpty()) { am.cancel(pi); return }
        var at = sp(app).getLong("yiyan-next", 0L)
        if (at <= System.currentTimeMillis()) {
            at = System.currentTimeMillis() + EVERY
            sp(app).edit().putLong("yiyan-next", at).apply()
        }
        am.set(AlarmManager.RTC, at, pi)
    }
}

/** 桌面小组件「每日一言」：透明背景，只有一句话；可以拉伸，字号自动；点了不跳转。 */
class YiyanWidget : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Yiyan.TICK) Yiyan.tick(context) else super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        Yiyan.tick(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: android.os.Bundle) {
        Yiyan.init(context)
        Yiyan.render()
    }

    override fun onEnabled(context: Context) {
        Yiyan.init(context)
        Yiyan.render()
    }
}
