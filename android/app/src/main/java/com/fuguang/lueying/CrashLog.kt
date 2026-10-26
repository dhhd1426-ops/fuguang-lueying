package com.fuguang.lueying

import android.content.Context
import java.io.File

/**
 * 闪退时把原因记下来（files/crash.txt，只留最近 5 次）：以后再闪退，不用猜，照着那一行修。
 * 不改界面，只记；记完照常交给系统处理。
 */
object CrashLog {
    private var armed = false

    fun arm(ctx: Context) {
        if (armed) return
        armed = true
        val file = File(ctx.applicationContext.filesDir, "crash.txt")
        val ver = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode }.getOrDefault(0L)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date())
                val one = "== $now · 第 $ver 版 · 线程 ${t.name}\n" + e.stackTraceToString().take(6000) + "\n"
                val old = if (file.exists()) file.readText().split(Regex("(?m)^(?=== )")).filter { it.isNotBlank() }.takeLast(4) else emptyList()
                file.writeText((old + one).joinToString(""))
            }
            prev?.uncaughtException(t, e)
        }
    }
}
