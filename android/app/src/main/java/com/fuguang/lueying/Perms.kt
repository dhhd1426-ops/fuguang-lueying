package com.fuguang.lueying

import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 她用工具时临时要权限（比如日历）：弹系统那一问，等你点完再接着做。App 不在前台时不问，直接当没给。
 */
object Perms {
    private var launch: ((Array<String>) -> Unit)? = null
    private var waiting: CompletableDeferred<Boolean>? = null

    fun has(ctx: Context, vararg p: String) = p.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

    suspend fun need(ctx: Context, vararg p: String): Boolean {
        if (has(ctx, *p)) return true
        val go = launch ?: return false
        if (!Notify.foreground) return false
        val d = CompletableDeferred<Boolean>()
        waiting?.complete(false); waiting = d
        withContext(Dispatchers.Main) { go(arrayOf(*p)) }
        return d.await() && has(ctx, *p)
    }

    internal fun done(ok: Boolean) { waiting?.complete(ok); waiting = null }
    internal fun bind(f: ((Array<String>) -> Unit)?) { launch = f }
}

/** 挂在聊天页里：系统权限弹窗的入口。 */
@Composable
fun PermsHost() {
    val l = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> Perms.done(r.values.all { it }) }
    DisposableEffect(l) {
        Perms.bind { l.launch(it) }
        onDispose { Perms.bind(null); Perms.done(false) }
    }
}
