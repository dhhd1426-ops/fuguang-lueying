package com.fuguang.lueying

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput

/**
 * 她回答期间开着的前台服务：你切走了她也能把这轮回完（安卓不会把 App 停掉）。
 * 通知栏里一张安静的「X 正在回答…」；安卓 12 起前 10 秒不显示，回得快的根本看不见。回完就关。
 */
class ReplyLive : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Notify.liveNote(this, conv, history, mine)
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) startForeground(Notify.LIVE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(Notify.LIVE_ID, n)
        }.onFailure { stopSelf() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
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
