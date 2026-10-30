package com.fuguang.lueying

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 她常驻后台（第 354 版，原型 ToxhMQMGssuFx8MuxvjwZ9 第 4 版）：有约定时开着，一条都没有就自己退出。
 * 你从最近任务里划掉的只是界面，这个服务还在；到点闹钟在同一个进程里直接叫她想话（Pacts.fire → run），不用再把死掉的 App 拉起来。
 * 被系统收走会按 START_STICKY 自己回来；开机、更新后 PactBoot 再开一次。
 * 安卓规定常驻要挂一条通知：单独一类「她在后台等着」，最安静（不响、不震、不亮屏、无角标）；
 * 标题「陆嘉静 · 10:00」，正文是她记约定时自己写的那句（Pact.line）。不想看见可以在系统里把这一类关掉（「让她能按时找你」里有一步直达），服务照样在跑。
 */
class PactKeeper : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        PactRun.boot(this)
        if (Pacts.all.isEmpty()) { stopSelf(); return START_NOT_STICKY }
        val ok = runCatching {
            val n = note(this)
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(ID, n)
        }.isSuccess
        if (!ok) { stopSelf(); return START_NOT_STICKY }
        live = this
        return START_STICKY
    }

    override fun onDestroy() {
        if (live === this) live = null
        scope.cancel()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    companion object {
        const val CH = "pact-keep"
        private const val ID = 4290
        @Volatile private var live: PactKeeper? = null

        private fun channel(ctx: Context) {
            ctx.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CH, "她在后台等着", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "有约定时她常驻后台，到点来找你。关掉这一类只是看不见，她照样在。"
                    setShowBadge(false)
                },
            )
        }

        /** 这一类通知被你在系统里关掉了没有。 */
        fun hidden(ctx: Context): Boolean = ctx.getSystemService(NotificationManager::class.java)?.getNotificationChannel(CH)?.importance == NotificationManager.IMPORTANCE_NONE

        /** 常驻那一条：下一条要到点的约定。 */
        private fun note(ctx: Context): android.app.Notification {
            channel(ctx)
            val p = Pacts.all.filter { it.next > 0 }.minByOrNull { it.next } ?: Pacts.all.firstOrNull()
            val her = p?.let { Memory.get(it.who) }
            val name = her?.name?.ifBlank { null } ?: "她"
            val title = name + (p?.let { " · " + whenOf(it) } ?: "")
            val text = p?.line?.ifBlank { null } ?: p?.let { "${Memory.userCall}，${Pacts.rule(it)}我来找你。" } ?: "我在。"
            return NotificationCompat.Builder(ctx, CH).setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setOngoing(true).setSilent(true).setShowWhen(false).setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setContentIntent(PendingIntent.getActivity(ctx, 4290, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE))
                .build()
        }

        /** 「10:00」「明早 08:00」「11月2日 09:00」「3 天没聊」。 */
        private fun whenOf(p: Pact): String {
            if (p.idle > 0) return "${p.idle} 天没聊"
            if (p.next <= 0) return Pacts.rule(p)
            val t = LocalDateTime.ofInstant(Instant.ofEpochMilli(p.next), ZoneId.systemDefault())
            val hm = "%02d:%02d".format(t.hour, t.minute)
            val today = LocalDate.now()
            return when (t.toLocalDate()) {
                today -> hm
                today.plusDays(1) -> (if (t.hour < 12) "明早 " else "明天 ") + hm
                else -> "${t.monthValue}月${t.dayOfMonth}日 $hm"
            }
        }

        /** 约定变了：有就开着（已开着就换通知上的字），没有就关掉。 */
        fun sync(ctx: Context) {
            val k = live
            if (Pacts.all.isEmpty()) { k?.stopSelf(); return }
            if (k != null) runCatching { NotificationManagerCompat.from(ctx).notify(ID, note(ctx)) }
            else runCatching { ctx.startForegroundService(Intent(ctx, PactKeeper::class.java)) }
        }

        /** 到点：常驻服务开着就在它里面想话，返回 true；没开着返回 false。 */
        fun run(id: String): Boolean {
            val k = live ?: return false
            k.scope.launch { runCatching { Pacts.speak(k, id) } }
            return true
        }
    }
}
