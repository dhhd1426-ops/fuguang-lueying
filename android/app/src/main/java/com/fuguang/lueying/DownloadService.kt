package com.fuguang.lueying

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * 下载在后台走：有在下的就开着这个前台服务，通知栏一条进度（几个在下、合计百分比、速度）；都下完了收起，换一条「下好了」。
 */
class DownloadService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private val nm by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm.createNotificationChannel(NotificationChannel(CH, "下载", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, n)
        h.removeCallbacksAndMessages(null)
        h.post(tick)
        return START_NOT_STICKY
    }

    private val tick = object : Runnable {
        override fun run() {
            if (Downloads.running == 0) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                Downloads.list.firstOrNull()?.takeIf { it.state == 3 }?.let { d ->
                    nm.notify(ID + 1, Notification.Builder(this@DownloadService, CH).setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setContentTitle("下好了").setContentText(d.title).setContentIntent(tap()).setAutoCancel(true).build())
                }
                stopSelf(); return
            }
            nm.notify(ID, build())
            h.postDelayed(this, 1000)
        }
    }

    private fun tap(): PendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("open", "downloads"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun build(): Notification {
        val on = Downloads.list.filter { it.state in setOf(0, 1, 2, 6) }
        val p = if (on.isEmpty()) 0 else (on.sumOf { it.frac.toDouble() } / on.size * 100).toInt()
        val sp = on.sumOf { it.speed }
        val text = when {
            on.isEmpty() -> "准备下载"
            on.all { it.state == 6 } -> "等 Wi-Fi"
            on.any { it.state == 2 } && on.size == 1 -> "正在合成画面和声音"
            else -> (if (on.size == 1) on[0].title else "${on.size} 个在下") + " · $p%" + if (sp > 0) " · " + speed(sp) else ""
        }
        return Notification.Builder(this, CH).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在下载").setContentText(text).setProgress(100, p, on.isEmpty()).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(tap()).build()
    }

    override fun onDestroy() { h.removeCallbacksAndMessages(null); super.onDestroy() }

    companion object {
        private const val CH = "downloads"
        private const val ID = 3107
        fun speed(b: Long): String = if (b > 1 shl 20) "%.1f MB/s".format(b / 1048576.0) else "${b / 1024} KB/s"
    }
}
