package com.fuguang.lueying

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.compose.runtime.mutableStateListOf
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 约定（第 352 版，原型 2zfSHtSMKQBreEMyNQDznu 第 5 版）：她主动找你，不做设置页，是她记着的事。
 * 你说「每天早上 8 点给我发早报」，她用 set_pact 记成一条约定；到点手机在后台叫醒她（PactAlarm → PactRun 前台服务），
 * 她看着约定、记忆和最近的聊天想好一句话，接在这位助理最近那段聊天后面，发一条通知。改、停都用嘴说（set_pact 带 id / drop_pact）。
 * 三种：每天 / 每周几（days，1 = 周一）/ 某一天一次（date）；还有「多久没聊就来问问」（idle 天数，只在 10:00–21:30 找你）。
 * 没叫醒（手机把 App 关了、过了两小时才醒）不补发，记成 missed，下次聊天时她提一句。
 */
data class Pact(
    val id: String, val who: String, val title: String, val what: String,
    val time: String = "", val days: List<Int> = emptyList(), val date: String = "", val idle: Int = 0,
    val next: Long = 0L, val missed: Long = 0L,
    /** 常驻通知里那句：她记约定时自己写的，昵称开头、她的口吻（第 354 版）。 */
    val line: String = "",
)

object Pacts {
    const val SET = "set_pact"
    const val DROP = "drop_pact"
    const val PREFIX = "pact:"
    private const val KEY = "pacts"
    private const val LATE = 2 * 3600_000L

    val all = mutableStateListOf<Pact>()
    private var prefs: Prefs? = null
    private var app: Context? = null
    private var loaded = false

    fun init(ctx: Context, p: Prefs) {
        if (loaded) return
        app = ctx.applicationContext; prefs = p
        runCatching {
            val a = JSONArray(p.string(KEY) ?: "[]")
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val ds = o.optJSONArray("d")
                all += Pact(
                    o.getString("id"), o.optString("w"), o.optString("t"), o.optString("x"), o.optString("h"),
                    if (ds == null) emptyList() else List(ds.length()) { ds.optInt(it) }, o.optString("dt"), o.optInt("i"), o.optLong("n"), o.optLong("m"), o.optString("l"),
                )
            }
        }
        loaded = true
    }

    private fun save() {
        val a = JSONArray()
        all.forEach { p ->
            a.put(JSONObject().put("id", p.id).put("w", p.who).put("t", p.title).put("x", p.what).put("h", p.time).put("d", JSONArray(p.days))
                .put("dt", p.date).put("i", p.idle).put("n", p.next).put("m", p.missed).put("l", p.line))
        }
        prefs?.putString(KEY, a.toString())
        app?.let { PactKeeper.sync(it) }
    }

    fun of(who: String) = all.filter { it.who == who }
    fun get(id: String) = all.firstOrNull { it.id == id }
    private fun put(p: Pact) { val i = all.indexOfFirst { it.id == p.id }; if (i >= 0) all[i] = p else all.add(p); save() }

    /* ---------- 时间 ---------- */

    private val zone get() = ZoneId.systemDefault()
    private fun hm(s: String): LocalTime? = Regex("""^(\d{1,2})[:：](\d{2})$""").find(s.trim())?.let { m ->
        val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt(); if (h in 0..23 && mi in 0..59) LocalTime.of(h, mi) else null
    }
    private fun ms(t: LocalDateTime) = t.atZone(zone).toInstant().toEpochMilli()
    private fun at(ms: Long) = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone)

    /** 从 from 之后的下一次；0 = 不会再有了（一次性的已经过了）。 */
    fun nextOf(p: Pact, from: Long = System.currentTimeMillis()): Long {
        if (p.idle > 0) {
            val last = Memory.get(p.who)?.lastAt?.takeIf { it > 0 } ?: from
            var t = at(maxOf(last + p.idle * 86_400_000L, from + 60_000L))
            // 只在白天找你：早于 10 点挪到 10:30，晚于 21:30 挪到第二天 10:30
            if (t.toLocalTime().isBefore(LocalTime.of(10, 0))) t = t.toLocalDate().atTime(10, 30)
            else if (!t.toLocalTime().isBefore(LocalTime.of(21, 30))) t = t.toLocalDate().plusDays(1).atTime(10, 30)
            return ms(t)
        }
        val time = hm(p.time) ?: return 0L
        if (p.date.isNotBlank()) {
            val d = runCatching { LocalDate.parse(p.date.trim()) }.getOrNull() ?: return 0L
            val t = ms(d.atTime(time)); return if (t > from) t else 0L
        }
        var d = at(from).toLocalDate()
        repeat(9) {
            val t = d.atTime(time)
            if (ms(t) > from && (p.days.isEmpty() || d.dayOfWeek.value in p.days)) return ms(t)
            d = d.plusDays(1)
        }
        return 0L
    }

    private val wk = listOf("", "一", "二", "三", "四", "五", "六", "日")
    /** 卡片上那一行：「每天 08:00」「工作日 08:00」「3 天没聊」「11月2日 09:00」。 */
    fun rule(p: Pact): String = when {
        p.idle > 0 -> "${p.idle} 天没聊"
        p.date.isNotBlank() -> runCatching { LocalDate.parse(p.date).let { "${it.monthValue}月${it.dayOfMonth}日 " } }.getOrDefault("") + p.time
        p.days.isEmpty() || p.days.toSet() == (1..7).toSet() -> "每天 ${p.time}"
        p.days.toSet() == (1..5).toSet() -> "工作日 ${p.time}"
        p.days.toSet() == setOf(6, 7) -> "周末 ${p.time}"
        else -> "每周" + p.days.sorted().joinToString("、") { wk[it.coerceIn(1, 7)] } + " ${p.time}"
    }

    /** 「下次：今天 08:00」「明天早上 08:00」「11月2日 08:00」。 */
    fun nextWord(p: Pact): String {
        if (p.next <= 0) return "不会再响了"
        val t = at(p.next); val today = LocalDate.now(zone)
        val hhmm = "%02d:%02d".format(t.hour, t.minute)
        val part = when (t.hour) { in 5..10 -> "早上 "; in 11..12 -> "中午 "; in 13..17 -> "下午 "; in 18..23 -> "晚上 "; else -> "凌晨 " }
        return "下次：" + when (t.toLocalDate()) {
            today -> "今天 $hhmm"
            today.plusDays(1) -> "明天$part$hhmm"
            today.plusDays(2) -> "后天$part$hhmm"
            else -> "${t.monthValue}月${t.dayOfMonth}日 $hhmm"
        }
    }

    /* ---------- 给模型 ---------- */

    val specs get() = listOf(
        ToolSpec(
            SET,
            "和对方定一条约定：到点手机会把你叫醒，你主动去找他。他说「每天早上 8 点给我发早报」「晚上 11 点半提醒我睡觉」「三天没找你你就来问问我」这类话时用它记下。" +
                "改已有的约定就带上它的 id 再调一次（整条覆盖）。只记他说了的，不要自己提议一堆约定。要她到点来说话、提醒的用它；要手机响铃的才用 set_alarm。",
            JSONObject().put("type", "object").put(
                "properties",
                JSONObject()
                    .put("id", JSONObject().put("type", "string").put("description", "改已有的约定时填它的 id；新约定不填"))
                    .put("title", JSONObject().put("type", "string").put("description", "几个字，比如：早报、提醒睡觉、问问近况"))
                    .put("what", JSONObject().put("type", "string").put("description", "到点你要做什么，写给到时候的自己看，具体：要说哪些内容、什么语气、要不要查天气或日程"))
                    .put("time", JSONObject().put("type", "string").put("description", "几点，24 小时制 HH:mm，比如 08:00、23:30"))
                    .put("days", JSONObject().put("type", "array").put("items", JSONObject().put("type", "integer")).put("description", "每周哪几天，1 = 周一 … 7 = 周日；不填就是每天"))
                    .put("date", JSONObject().put("type", "string").put("description", "只提醒一次的（几分钟后、明天下午三点）一定填哪天，YYYY-MM-DD，按现在的时间算；不填就是每天"))
                    .put("line", JSONObject().put("type", "string").put("description", "挂在他通知栏里的一句话，等到点前一直显示：用你平时叫他的昵称开头，你自己的口吻和性格，带上什么时候、做什么，一两句，有感情、自然，不肉麻、不说「约定」"))
                    .put("idle_days", JSONObject().put("type", "integer").put("description", "「多久没聊就来找他」的天数；填了就不看 time / days")),
            ).put("required", JSONArray(listOf("title", "what"))),
        ),
        ToolSpec(
            DROP,
            "取消一条约定（他说不用了、别发了）。",
            JSONObject().put("type", "object").put("properties", JSONObject().put("id", JSONObject().put("type", "string")))
                .put("required", JSONArray(listOf("id"))),
        ),
    )

    fun system(who: Persona, call: String): String {
        val mine = of(who.id)
        return "$call 要你定时主动找他（每天几点发早报、晚上提醒睡觉、几天没聊就来问问近况），用 $SET 记成约定；到点你会被叫醒，自己去找他。要改带 id 再用 $SET，不要了用 $DROP。" +
            (if (mine.isEmpty()) "" else "你们现在的约定：" + mine.joinToString("；") { "[${it.id}] ${rule(it)} · ${it.title}：${it.what.take(60)}" } + "。") +
            mine.filter { it.missed > 0 }.joinToString("") { "你答应的「${it.title}」在 ${at(it.missed).let { t -> "${t.monthValue}月${t.dayOfMonth}日 %02d:%02d".format(t.hour, t.minute) }} 没去成（手机没把你叫醒）。这次见到他简单提一句就好，别反复道歉。" }
    }

    /** 记忆本里长按取消。 */
    fun drop(ctx: Context, id: String) { val p = get(id) ?: return; all.remove(p); save(); cancel(ctx, id) }

    /** 这一轮聊完：没去成的那句已经说过了。 */
    fun clearMissed(who: String) {
        var ch = false
        all.forEachIndexed { i, p -> if (p.who == who && p.missed > 0) { all[i] = p.copy(missed = 0); ch = true } }
        if (ch) save()
    }

    /** 跑工具；约定卡的引用交给 onCard（"pact:<id>|<名字>"）。 */
    fun run(n: String, a: JSONObject, who: Persona, onCard: (String) -> Unit): ToolOut {
        val ctx = app ?: return ToolOut("约定没地方存。")
        if (n == DROP) {
            val id = a.optString("id").trim()
            val p = get(id) ?: all.firstOrNull { it.who == who.id && it.title == id } ?: return ToolOut("没有这条约定。")
            all.remove(p); save(); cancel(ctx, p.id)
            onCard("$PREFIX${p.id}|${p.title}")
            return ToolOut("取消了「${p.title}」，以后到点不会再叫你。")
        }
        val title = a.optString("title").trim().ifEmpty { "约定" }.take(12)
        val what = a.optString("what").trim().ifEmpty { return ToolOut("what 是空的：写清到点要做什么再记一次。") }
        val idle = a.optInt("idle_days", 0).coerceIn(0, 60)
        val time = a.optString("time").trim()
        if (idle == 0 && hm(time) == null) return ToolOut("time 要写成 HH:mm（比如 08:00）；要是「几天没聊就来找」，填 idle_days。")
        val days = a.optJSONArray("days")?.let { d -> List(d.length()) { d.optInt(it) }.filter { it in 1..7 }.distinct().sorted() }.orEmpty()
        val old = a.optString("id").trim().takeIf { it.isNotEmpty() }?.let { get(it) }
        var p = Pact(old?.id ?: (System.currentTimeMillis().toString(36) + (10..99).random()), who.id, title, what.take(600),
            if (idle > 0) "" else hm(time)!!.let { "%02d:%02d".format(it.hour, it.minute) }, if (idle > 0) emptyList() else days,
            if (idle > 0) "" else a.optString("date").trim(), idle, line = a.optString("line").trim().take(80))
        p = p.copy(next = nextOf(p))
        if (p.next == 0L) return ToolOut("这个时间已经过了，换一个以后的时间再记。")
        put(p); schedule(ctx, p)
        onCard("$PREFIX${p.id}|${p.title}")
        return ToolOut(
            (if (old != null) "改好了" else "记好了") + "：${rule(p)} · ${p.title}，${nextWord(p)}。约定卡在你这条回复里，" +
                "不用把时间再念一遍，像答应他一样说一句就好。" + (if (Keep.needs(ctx).isNotEmpty()) "卡下面有几步让手机别把你关掉，请他点一下（就这一次）。" else ""),
        )
    }

    /* ---------- 闹钟 ---------- */

    private fun pi(ctx: Context, id: String) = PendingIntent.getBroadcast(
        ctx, ("pact$id").hashCode(), Intent(ctx, PactAlarm::class.java).putExtra("id", id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun schedule(ctx: Context, p: Pact) {
        if (p.next <= 0) return
        runCatching {
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, p.next, pi(ctx, p.id))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, p.next, pi(ctx, p.id))
        }
    }

    fun cancel(ctx: Context, id: String) { runCatching { ctx.getSystemService(AlarmManager::class.java)?.cancel(pi(ctx, id)) } }

    /** 打开 App、开机、更新后：闹钟全部重新定一遍；刚过点没多久的马上补上，过太久的记成没去成。 */
    fun catchUp(ctx: Context, direct: Boolean = true) {
        val now = System.currentTimeMillis()
        all.toList().forEach { p ->
            if (p.next in 1..now) {
                // 开机时不能直接开前台服务（安卓 15 起）：定一个半分钟后的闹钟去叫
                if (now - p.next < LATE) { if (direct) fire(ctx, p.id) else schedule(ctx, p.copy(next = now + 30_000L)); return@forEach }
                val n = p.copy(missed = if (p.idle > 0) p.missed else p.next, next = nextOf(p, now))
                if (n.next == 0L && n.date.isNotBlank()) { all.remove(p); save(); return@forEach }
                put(n); schedule(ctx, n)
            } else schedule(ctx, p)
        }
    }

    /** 到点了：常驻服务在跑就在它里面想，否则开前台服务；都开不了就先发一句保底的。 */
    fun fire(ctx: Context, id: String) {
        if (PactKeeper.run(id)) return
        runCatching { ctx.startForegroundService(Intent(ctx, PactRun::class.java).putExtra("id", id)) }
            .onFailure { android.util.Log.w("Pacts", "约定没叫醒：" + it.message); get(id)?.let { p -> fallback(ctx, p) } }
    }

    /** 保底的一句：「小屿，喝水的时间到了。」 */
    fun fallback(ctx: Context, p: Pact) {
        val her = Memory.get(p.who) ?: return
        Notify.init(ctx)
        Notify.herNote(ctx, her.id, her.name, ("pact" + p.id).hashCode().toLong(), p.title, "${Memory.userCall}，${p.title}的时间到了。点开我再跟你细说。")
    }

    /** 前台服务里做：看约定、记忆、最近的聊天，想好一句话，接在最近那段聊天后面，发通知；再定下一次。 */
    suspend fun speak(ctx: Context, id: String) {
        val p0 = get(id) ?: return
        val now = System.currentTimeMillis()
        val persona = Memory.get(p0.who)
        // 下一次先定好：这次想不出来也不影响以后
        fun moveOn(missed: Long = 0L) {
            val cur = get(id) ?: return
            val n = cur.copy(next = nextOf(cur, now + 60_000L), missed = if (missed > 0) missed else cur.missed)
            if (n.next == 0L) { all.remove(cur); save(); cancel(ctx, id) } else { put(n); schedule(ctx, n) }
        }
        if (persona == null) { all.removeAll { it.id == id }; save(); return }
        if (p0.idle > 0 && persona.lastAt > 0 && persona.lastAt + p0.idle * 86_400_000L > now) { moveOn(); return }   // 这几天聊过了
        if (p0.next > 0 && now - p0.next > LATE) { moveOn(missed = if (p0.idle > 0) 0L else p0.next); return }
        moveOn()
        val prefs = prefs ?: Prefs(ctx)
        val store = AssistantStore(prefs)
        val pv = provider(store.activeProvider); val c = store.config(pv.id)
        if (!c.configured || store.activeModel.isBlank()) return
        val history = Chats.current?.history ?: ConvStore(prefs)
        val conv = history.of(persona.id).maxByOrNull { it.updated }
        val past = conv?.msgs?.takeLast(16).orEmpty().filter { it.text.isNotBlank() }
        val t = at(now)
        val hhmm = "%02d:%02d".format(t.hour, t.minute)
        val call = Memory.userCall
        val note = "（现在是 ${t.monthValue}月${t.dayOfMonth}日 星期${wk[t.dayOfWeek.value]} $hhmm。$call 没有发消息，是你按你们的约定「${p0.title}」主动来找他。" +
            "约定的内容：${p0.what}\n" +
            (if (p0.idle > 0) "你们已经 ${p0.idle} 天以上没聊了，翻翻记忆里他最近的事，具体地问一句，不说空话。\n" else "") +
            "直接写你要发给他的话，像发消息一样，短一点、自然一点；不要提「约定」「系统」「被叫醒」。要查天气、看日程就用手边的工具。）"
        val tools = buildList {
            if (WebSearch.usable) add(WebSearch.spec)
            ClockTools.specs.firstOrNull { it.name == ClockTools.LIST }?.let { add(it) }
        }
        var text = ""
        runCatching {
            withTimeout(150_000) {
                ChatApi.stream(
                    pv, c, store.activeModel, Prompt.system(persona, store.activeModel), past + ChatMsg("user", note),
                    store.effort()?.let { e -> if (e == Effort.OFF) e else Effort.spec(store.activeProvider, store.activeModel).levels.first { it.value != Effort.OFF }.value },
                    onThink = { },
                    tools = if (tools.isEmpty()) null else Tools(
                        tools,
                        run = { n, a -> if (n == WebSearch.TOOL) WebSearch.run(a.optString("query")) else if (n == ClockTools.LIST) ClockTools.run(n, a, persona.id) { } else ToolOut("这里用不了。") },
                        onUse = { },
                    ),
                ) { text += it }
            }
        }.onFailure { android.util.Log.w("Pacts", "约定「${p0.title}」没想出来：" + it.message) }
        text = ToolText.clean(text).trim()
        if (text.isEmpty()) {
            // 保底：这次想不出话（没网、模型出错），先用她的名义说一句；下次聊天她再接着说
            fallback(ctx, p0)
            get(id)?.let { put(it.copy(missed = now)) }
            return
        }
        val m = ChatMsg("assistant", text, pid = pv.id, pact = p0.title)
        kotlinx.coroutines.withContext(Dispatchers.Main) {
            val cs = Chats.current
            val cid = if (cs != null && cs.convId != null && cs.convId == conv?.id && !cs.busy) cs.arrive(m)
            else history.append(conv?.id, m, persona.id, p0.title)
            val seen = cs != null && Notify.foreground && cs.convId == cid && cs.onScreen
            Notify.replied(cid, history, null, Notify.preview(m), seen)
        }
    }
}

/** 到点：交给前台服务。 */
class PactAlarm : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        PactRun.boot(ctx)
        intent.getStringExtra("id")?.let { Pacts.fire(ctx, it) }
    }
}

/** 开机、App 更新后：约定的闹钟重新定一遍。 */
class PactBoot : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        PactRun.boot(ctx)
        Pacts.catchUp(ctx, direct = false)
        PactKeeper.sync(ctx)
    }
}

/** 她想话的那几十秒开着的前台服务：系统不会半路把她掐断。想完就关。 */
class PactRun : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var jobs = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        boot(this)
        val p = intent?.getStringExtra("id")?.let { Pacts.get(it) }
        val name = p?.let { Memory.get(it.who)?.name?.ifBlank { null } } ?: "她"
        val n = NotificationCompat.Builder(this, Notify.CH_LIVE).setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("$name 在想跟你说什么").setSilent(true).setOngoing(true).build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) startForeground(Notify.LIVE_ID + 7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(Notify.LIVE_ID + 7, n)
        }.onFailure { stopSelf(); return START_NOT_STICKY }
        if (p == null) { if (jobs == 0) stopSelf(); return START_NOT_STICKY }
        jobs++
        scope.launch {
            runCatching { Pacts.speak(this@PactRun, p.id) }
            jobs--
            if (jobs <= 0) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    companion object {
        /** App 没开着、进程是被闹钟拉起来的：把要用到的东西读进来（开着时都已经读过了，不再读）。 */
        /** 开着的 App 已经都读过了（MainActivity 里设）。 */
        @Volatile var ready = false
        fun boot(ctx: Context) {
            if (ready) return
            ready = true
            val prefs = Prefs(ctx)
            Notify.init(ctx); ClockTools.init(ctx); Works.init(ctx); SentFiles.init(ctx); MaterialStore.init(ctx); Avatars.init(ctx); Attach.init(ctx)
            Memory.init(prefs); MemModel.init(prefs); GistModel.init(prefs); ModelFit.init(prefs); WebSearch.init(prefs)
            Pacts.init(ctx, prefs)
        }
    }
}
