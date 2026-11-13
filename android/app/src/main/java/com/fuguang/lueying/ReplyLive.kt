package com.fuguang.lueying

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput

/**
 * Protects application-owned chat and artwork tasks while the UI is away.
 * Foreground priority reduces reclaim risk; it does not guarantee process survival.
 * 通知栏里一张安静的「X 正在回答…」；安卓 12 起前 10 秒不显示，回得快的根本看不见。回完就关。
 */
class ReplyLive : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val renew = object : Runnable {
        override fun run() {
            if (!Notify.hasLiveTasks) return
            val lock = wakeLock ?: return
            // A finite lease also expires if cleanup is unexpectedly skipped.
            runCatching { lock.acquire(20 * 60_000L) }
                .onFailure { ReplyRuntime.note("wake_lock_failed ${it.javaClass.simpleName}") }
            handler.postDelayed(this, 10 * 60_000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Notify.hasLiveTasks) { stopSelf(startId); return START_NOT_STICKY }
        val n = Notify.liveNote(this, conv, history, mine)
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) startForeground(Notify.LIVE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(Notify.LIVE_ID, n)
        }.onSuccess {
            ReplyRuntime.serviceStarted()
            ReplyRuntime.watchNetwork()
            if (wakeLock == null) {
                val pm = getSystemService(PowerManager::class.java) ?: return@onSuccess
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vignette:reply").apply { setReferenceCounted(false) }
                runCatching { renew.run() }.onFailure { ReplyRuntime.note("wake_lock_failed ${it.javaClass.simpleName}") }
            }
        }.onFailure { ReplyRuntime.serviceFailed(it); stopSelf(startId) }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        ReplyRuntime.serviceFailed(IllegalStateException("foreground service timeout"))
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(renew)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        ReplyRuntime.unwatchNetwork()
        ReplyRuntime.note("service_destroyed tasks=${Notify.hasLiveTasks}")
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    companion object {
        var conv: String? = null
        var history: ConvStore? = null
        var mine: String? = null
    }
}

/** 通知上的「回复」「已读」。 */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Notify.init(ctx)
        val conv = intent.getStringExtra("conv") ?: return
        when (intent.action) {
            READ -> Notify.cancel(conv)
            REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notify.KEY_TEXT)?.toString()?.trim().orEmpty()
                Notify.cancel(conv)
                if (text.isEmpty()) return
                val go = Notify.onReply
                if (go != null) go(conv, text)
                else {
                    // App 已经被系统收走了：这句留在输入框里，下次打开接着发
                    Notify.saveDraft(ctx, conv, text)
                    runCatching {
                        NotificationManagerCompat.from(ctx).notify(conv, 4203,
                            androidx.core.app.NotificationCompat.Builder(ctx, Notify.CH_REPLY).setSmallIcon(R.drawable.ic_notify)
                                .setContentTitle("这句还没发出去").setContentText("打开 Vignette，它在输入框里等你").setSilent(true).setAutoCancel(true)
                                .setContentIntent(android.app.PendingIntent.getActivity(ctx, conv.hashCode(),
                                    Intent(ctx, MainActivity::class.java).putExtra("conv", conv), android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT))
                                .build())
                    }
                }
            }
        }
    }

    companion object {
        const val REPLY = "com.fuguang.lueying.REPLY"
        const val READ = "com.fuguang.lueying.READ"
    }
}
