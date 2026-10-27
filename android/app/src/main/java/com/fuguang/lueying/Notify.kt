package com.fuguang.lueying

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import java.util.Calendar

/**
 * 她回好了、你不在 App 里：发一张「对话」通知（原型 HGCiQBSgqgCQ4gDwxuyJyM 第 3 版）。
 * 图标是她的头像、标题是她的名字，展开看全文，下面「回复 / 已读」；锁屏只写「回复了你」；
 * 深夜 23 点到 7 点不响。她回答期间有一张安静的「正在回答…」（ReplyLive.kt 的前台服务，让回答在后台跑完）。
 * 作品在后台写好、没回成，也用同一种卡。
 */
object Notify {
    const val CH_REPLY = "reply"
    const val CH_WORK = "work"
    const val CH_LIVE = "reply-live"
    const val LIVE_ID = 4201
    private const val ID = 4202
    const val KEY_TEXT = "text"

    private var app: Context? = null
    /** App 在前台（看得见）时不发通知。MainActivity 的 onStart / onStop 改它。 */
    var foreground by mutableStateOf(true)
    /** 点了通知：要打开的对话、要不要重试。ChatPage 看到就打开，再清掉。 */
    var openConv by mutableStateOf<String?>(null)
    var retry by mutableStateOf(false)
    /** 在通知里回了一句：ChatPage 活着时由它接手发出去。 */
    var onReply: ((conv: String, text: String) -> Unit)? = null

    fun init(ctx: Context) {
        val c = ctx.applicationContext; app = c
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CH_REPLY, "她的回复", NotificationManager.IMPORTANCE_HIGH).apply { description = "她回好了、没回成时告诉你" })
        nm.createNotificationChannel(NotificationChannel(CH_WORK, "作品写好了", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_LIVE, "她在回答", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
    }

    fun take(intent: Intent?) {
        intent ?: return
        intent.getStringExtra("conv")?.let { c -> openConv = c; retry = intent.getBooleanExtra("retry", false); cancel(c) }
    }

    private fun quiet(): Boolean { val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY); return h >= 23 || h < 7 }

    /** 这段对话属于谁（不在了就当前那位）。 */
    private fun herOf(conv: String?, history: ConvStore?): Persona =
        history?.get(conv)?.who?.let { Memory.get(it) } ?: Memory.active

    fun nameOf(p: Persona) = p.name.ifBlank { "她" }

    private fun person(ctx: Context, p: Persona): Person =
        Person.Builder().setName(nameOf(p)).setKey("her-" + p.id).setIcon(IconCompat.createWithBitmap(face(p))).build()

    /** 头像：有图用图（圆），没图首字圆（杏到墨蓝的渐变）。 */
    private fun face(p: Persona): Bitmap = face(p.id, nameOf(p))

    private fun face(id: String, name: String): Bitmap {
        val s = 192
        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val img = runCatching { Avatars.get(id)?.asAndroidBitmap() }.getOrNull()
        if (img != null) {
            paint.shader = android.graphics.BitmapShader(Bitmap.createScaledBitmap(img, s, s, true), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            cv.drawCircle(s / 2f, s / 2f, s / 2f, paint)
        } else {
            paint.shader = LinearGradient(0f, 0f, s.toFloat(), s.toFloat(), 0xFFC79A86.toInt(), 0xFF5A4660.toInt(), Shader.TileMode.CLAMP)
            cv.drawCircle(s / 2f, s / 2f, s / 2f, paint)
            val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFF4EC.toInt(); textSize = s * .42f; textAlign = Paint.Align.CENTER }
            cv.drawText(name.take(1), s / 2f, s / 2f - (t.descent() + t.ascent()) / 2, t)
        }
        return out
    }

    /** 长期的「对话」快捷方式：系统据此把通知放进「对话」区，可设为优先对话、气泡。 */
    private fun shortcut(ctx: Context, p: Persona, her: Person): String {
        val id = "her-" + p.id
        runCatching {
            ShortcutManagerCompat.pushDynamicShortcut(
                ctx, ShortcutInfoCompat.Builder(ctx, id).setShortLabel(nameOf(p)).setLongLived(true).setPerson(her)
                    .setIcon(her.icon ?: IconCompat.createWithResource(ctx, R.mipmap.ic_launcher))
                    .setIntent(Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)).build(),
            )
        }
        return id
    }

    private fun openIntent(ctx: Context, conv: String, retry: Boolean = false): PendingIntent =
        PendingIntent.getActivity(
            ctx, (conv + retry).hashCode(),
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("conv", conv).putExtra("retry", retry),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun action(ctx: Context, conv: String, what: String): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, (conv + what).hashCode(),
            Intent(ctx, ReplyReceiver::class.java).setAction(what).putExtra("conv", conv),
            // 回复要让系统把打的字放进来，所以是 MUTABLE
            (if (what == ReplyReceiver.REPLY) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE) or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun base(ctx: Context, ch: String, conv: String, p: Persona, her: Person) =
        NotificationCompat.Builder(ctx, ch)
            .setSmallIcon(R.drawable.ic_notify)
            .setShortcutId(shortcut(ctx, p, her))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openIntent(ctx, conv))
            .setAutoCancel(true)
            .setSilent(quiet())
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            // 锁屏上只写「X 回复了你」
            .setPublicVersion(
                NotificationCompat.Builder(ctx, ch).setSmallIcon(R.drawable.ic_notify).setContentTitle(nameOf(p)).setContentText("回复了你").build(),
            )

    private fun style(ctx: Context, her: Person, mine: String?, line: String): NotificationCompat.MessagingStyle {
        val me = Person.Builder().setName(Memory.userName.ifBlank { "我" }).setKey("me").build()
        val now = System.currentTimeMillis()
        // 不用 apply：MessagingStyle 自己有个同名方法
        val st = NotificationCompat.MessagingStyle(me)
        if (!mine.isNullOrBlank()) st.addMessage(mine.take(400), now - 1, me)
        st.addMessage(line, now, her)
        return st
    }

    private fun post(ctx: Context, conv: String, n: android.app.Notification) {
        runCatching { NotificationManagerCompat.from(ctx).notify(conv, ID, n) }
    }

    /** 她回好了。 */
    fun replied(conv: String?, history: ConvStore?, mine: String?, reply: String) {
        val ctx = app ?: return
        if (foreground || conv == null || reply.isBlank()) return
        val p = herOf(conv, history); val her = person(ctx, p)
        val input = RemoteInput.Builder(KEY_TEXT).setLabel("回信给 ${nameOf(p)}…").build()
        val n = base(ctx, CH_REPLY, conv, p, her)
            .setStyle(style(ctx, her, mine, reply))
            .addAction(NotificationCompat.Action.Builder(0, "回复", action(ctx, conv, ReplyReceiver.REPLY)).addRemoteInput(input).setAllowGeneratedReplies(false)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY).setShowsUserInterface(false).build())
            .addAction(NotificationCompat.Action.Builder(0, "已读", action(ctx, conv, ReplyReceiver.READ))
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ).setShowsUserInterface(false).build())
            .build()
        post(ctx, conv, n)
    }

    /** 没回成：说清楚原因，下面「重试」。 */
    fun failed(conv: String?, history: ConvStore?, why: String) {
        val ctx = app ?: return
        if (foreground || conv == null) return
        val p = herOf(conv, history); val her = person(ctx, p)
        val n = base(ctx, CH_REPLY, conv, p, her)
            .setContentTitle("${nameOf(p)} · 没回成").setContentText(why.take(120)).setLargeIcon(face(p))
            .addAction(0, "重试", openIntent(ctx, conv, retry = true))
            .addAction(0, "打开", openIntent(ctx, conv))
            .build()
        post(ctx, conv, n)
    }

    /** 后台写的作品好了。 */
    fun work(conv: String?, history: ConvStore?, title: String) {
        val ctx = app ?: return
        if (foreground || conv == null) return
        val p = herOf(conv, history); val her = person(ctx, p)
        val n = base(ctx, CH_WORK, conv, p, her).setStyle(style(ctx, her, null, "做好了作品「$title」，点开看看。")).build()
        runCatching { NotificationManagerCompat.from(ctx).notify(conv, ID + 1, n) }
    }

    /** 她正在回答：安静的一张，不弹不响。 */
    fun liveNote(ctx: Context, conv: String?, history: ConvStore?, mine: String?): android.app.Notification {
        val p = herOf(conv, history)
        val b = NotificationCompat.Builder(ctx, CH_LIVE).setSmallIcon(R.drawable.ic_notify).setOngoing(true).setSilent(true).setOnlyAlertOnce(true)
            .setContentTitle(nameOf(p)).setContentText("正在回答…").setLargeIcon(face(p))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        if (!mine.isNullOrBlank()) b.setSubText("你：" + mine.take(40))
        if (conv != null) b.setContentIntent(openIntent(ctx, conv))
        return b.build()
    }

    fun startLive(conv: String?, history: ConvStore?, mine: String?) {
        val ctx = app ?: return
        ReplyLive.conv = conv; ReplyLive.history = history; ReplyLive.mine = mine
        val ok = runCatching { ctx.startForegroundService(Intent(ctx, ReplyLive::class.java)) }.isSuccess
        // 后台起不了前台服务（比如在通知里回的）：退一步，只发一张普通的「正在回答…」
        if (!ok) runCatching { NotificationManagerCompat.from(ctx).notify(LIVE_ID, liveNote(ctx, conv, history, mine)) }
    }

    fun stopLive() {
        val ctx = app ?: return
        runCatching { ctx.stopService(Intent(ctx, ReplyLive::class.java)) }
        runCatching { NotificationManagerCompat.from(ctx).cancel(LIVE_ID) }
    }

    /** App 不在时在通知里回的话：存起来，下次打开放回那段对话的输入框。 */
    fun saveDraft(ctx: Context, conv: String, text: String) {
        ctx.getSharedPreferences("fuguang", Context.MODE_PRIVATE).edit().putString("notify-draft", conv + "\n" + text).apply()
    }

    fun takeDraft(): Pair<String, String>? {
        val sp = app?.getSharedPreferences("fuguang", Context.MODE_PRIVATE) ?: return null
        val v = sp.getString("notify-draft", null) ?: return null
        sp.edit().remove("notify-draft").apply()
        return v.substringBefore('\n') to v.substringAfter('\n')
    }

    fun cancel(conv: String) {
        val ctx = app ?: return
        runCatching { NotificationManagerCompat.from(ctx).cancel(conv, ID); NotificationManagerCompat.from(ctx).cancel(conv, ID + 1) }
    }

    /** 日程到点：她加日程时写的那句话（系统日历那条照常响）。 */
    fun herNote(ctx: Context, pid: String, name0: String, id: Long, title: String, text: String) {
        val name = name0.ifBlank { "她" }
        val her = Person.Builder().setName(name).setKey("her-$pid").setIcon(IconCompat.createWithBitmap(face(pid, name))).build()
        val me = Person.Builder().setName("我").setKey("me").build()
        val st = NotificationCompat.MessagingStyle(me)
        st.addMessage(text, System.currentTimeMillis(), her)
        val n = NotificationCompat.Builder(ctx, CH_REPLY).setSmallIcon(R.drawable.ic_notify)
            .setShortcutId("her-$pid").setCategory(NotificationCompat.CATEGORY_REMINDER).setStyle(st).setSubText(title).setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(ctx, ("note$id").hashCode(), Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationCompat.Builder(ctx, CH_REPLY).setSmallIcon(R.drawable.ic_notify).setContentTitle(name).setContentText("提醒你一件事").build())
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify("note$id", ID + 2, n) }
    }

    /** 通知里只放她说的话：去掉工具标记、格式符号，空了就说做了什么。 */
    fun preview(m: ChatMsg): String {
        var s = ToolText.clean(m.text)
        s = s.replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")
            .replace(Regex("(?m)^\\s{0,3}(#{1,6}|>|[-*+]|\\d+\\.)\\s+"), "")
            .replace(Regex("[*_`~]{1,3}"), "")
            .replace(Regex("\n{3,}"), "\n\n").trim()
        if (s.isEmpty() && m.works.isNotEmpty()) {
            val t = m.works.firstNotNullOfOrNull { w -> Works.get(w)?.title ?: WorkJobs.titleOf(w).takeIf { it.isNotEmpty() } }
            s = if (t != null) "在做作品「$t」" else "做了一件作品"
        }
        return s.take(1200)
    }
}
