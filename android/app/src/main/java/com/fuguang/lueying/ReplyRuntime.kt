package com.fuguang.lueying

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.util.concurrent.Executors

/** Application-owned execution. The UI observes ChatState; leaving composition owns no tasks. */
internal object ReplyRuntime {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var app: Context? = null
    private val logs = Executors.newSingleThreadExecutor { r -> Thread(r, "reply-diagnostics").apply { isDaemon = true } }
    @Volatile private var blocked = false
    @Volatile private var serviceFailure = false
    private var network: ConnectivityManager.NetworkCallback? = null
    @Volatile private var networkOwner: Any? = null

    fun init(context: Context) {
        if (app != null) return
        app = context.applicationContext
    }

    fun serviceStarted() { serviceFailure = false; note("service_started") }
    fun serviceFailed(e: Throwable) { serviceFailure = true; note("service_failed ${e.javaClass.simpleName}") }

    fun failureHint(): String = when {
        blocked -> "系统限制了后台联网，请允许 Vignette 后台联网后继续"
        serviceFailure -> "后台保活未成功，请检查系统后台运行设置后继续"
        else -> ""
    }

    fun watchNetwork() {
        val ctx = app ?: return
        if (network != null) return
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
        val owner = Any()
        networkOwner = owner
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                if (networkOwner !== owner) return
                ReplyRuntime.blocked = blocked
                note("network_blocked=$blocked foreground=${Notify.foreground}")
            }
            override fun onLost(network: Network) { note("network_lost foreground=${Notify.foreground}") }
            override fun onAvailable(network: Network) { note("network_available") }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onSuccess { network = callback }
            .onFailure { note("network_watch_failed ${it.javaClass.simpleName}") }
        val pm = ctx.getSystemService(PowerManager::class.java)
        note("policy batteryExempt=${pm?.isIgnoringBatteryOptimizations(ctx.packageName)} dataSaver=${cm.restrictBackgroundStatus}")
    }

    fun unwatchNetwork() {
        val callback = network ?: return
        network = null
        networkOwner = null
        runCatching { app?.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback) }
        blocked = false
    }

    /** No prompts, credentials, model output or raw server errors in diagnostics. */
    fun note(event: String) {
        val ctx = app ?: return
        logs.execute {
            runCatching {
                val file = File(ctx.filesDir, "reply-runtime.log")
                if (file.length() > 128 * 1024) file.writeText(file.readText().takeLast(64 * 1024))
                file.appendText("${System.currentTimeMillis()} ${event.take(180)}\n")
            }
        }
    }
}
