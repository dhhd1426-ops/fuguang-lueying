package com.fuguang.lueying

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope

/**
 * 聊天页和通知接上：点了通知打开那段对话（要重试就重试）；在通知里回的话由这里发出去；
 * 回到 App 看着这段对话时把它的通知收掉；第一次发消息时问一次能不能发通知（安卓 13 起要问）。
 */
@Composable
fun NotifyHooks(chat: ChatState, store: AssistantStore, scope: CoroutineScope) {
    val ctx = LocalContext.current
    PermsHost()
    DisposableEffect(chat, store, scope) {
        Notify.onReply = { conv, text ->
            when {
                // 她正忙着这段：话排在末尾，做完一步就收到
                chat.busy && chat.convId == conv -> chat.queue.add(text)
                // 她在忙别的：先放进那段对话的输入框，你回来再发
                chat.busy -> Notify.saveDraft(ctx, conv, text)
                else -> {
                    if (chat.convId != conv) chat.history.get(conv)?.let { chat.open(it) }
                    chat.draft = text
                    chat.send(scope, store)
                }
            }
        }
        onDispose { Notify.onReply = null }
    }
    // 点了通知：打开那段对话；点的是「重试」就再问一次
    LaunchedEffect(Notify.openConv) {
        val id = Notify.openConv ?: return@LaunchedEffect
        val retry = Notify.retry
        Notify.openConv = null; Notify.retry = false
        if (chat.busy && chat.convId != id) return@LaunchedEffect
        if (chat.convId != id) chat.history.get(id)?.let { chat.open(it) }
        if (retry) chat.retry(scope, store)
    }
    // 上次在通知里回了、App 却不在：放回那段对话的输入框
    LaunchedEffect(Unit) {
        val (conv, text) = Notify.takeDraft() ?: return@LaunchedEffect
        if (chat.busy) return@LaunchedEffect
        if (chat.convId != conv) chat.history.get(conv)?.let { chat.open(it) }
        chat.draft = text
    }
    // 看着这段对话：它的通知收掉
    LaunchedEffect(Notify.foreground, chat.convId) {
        if (Notify.foreground) chat.convId?.let { Notify.cancel(it) }
    }
    // 第一次发消息时问一次通知权限；不允许就不再问
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(chat.sent) {
        if (chat.sent == 0 || Build.VERSION.SDK_INT < 33) return@LaunchedEffect
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return@LaunchedEffect
        val sp = ctx.getSharedPreferences("fuguang", Context.MODE_PRIVATE)
        if (sp.getBoolean("notify-asked", false)) return@LaunchedEffect
        sp.edit().putBoolean("notify-asked", true).apply()
        ask.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
