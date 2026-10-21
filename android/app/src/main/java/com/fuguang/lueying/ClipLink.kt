package com.fuguang.lueying

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 复制就认出：回到 App 时看一眼剪贴板，有抖音链接就交给 [VideoStore] 并在顶上滑下长条。
 * 只管「复制了就提醒」：同一条链接只弹一次（prefs `clip-seen`）；结果、进度都在 VideoStore 里。
 * 你发给她、她自己解析，永远落卡，不经过这里的去重。
 */
object ClipLink {
    /** 长条上那条的链接（VideoStore 的 key）。 */
    var pill by mutableStateOf<String?>(null)
    var showing by mutableStateOf(false)
    /** 「让她看看」：直接发一条消息（聊天页挂上）。 */
    var ask: ((String) -> Unit)? = null
    private var prefs: Prefs? = null

    fun init(p: Prefs) { prefs = p }

    fun check(act: Activity) {
        val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val text = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(act)?.toString() }.getOrNull() ?: return
        val l = Douyin.link(text) ?: return (if (checkTg(text)) Unit else checkPan(text))
        val key = l.hashCode().toString()
        if (prefs?.string("clip-seen") == key) return
        VideoStore.clip(pill)?.let { if (showing && it.save == 1) return }   // 正在存的那条别被打断
        prefs?.putString("clip-seen", key)
        val c = VideoStore.offer(text) ?: return
        pill = c.link; showing = true
    }

    /** 网盘分享链接：同样只弹一次，交给 PanStore，顶上滑下网盘长条（PanPill）。 */
    private fun checkPan(text: String) {
        val l = Pan.link(text)?.link ?: return
        val key = "pan" + l.hashCode()
        if (prefs?.string("clip-seen") == key) return
        if (PanLink.showing && PanStore.item(PanLink.pill)?.save == 1) return
        prefs?.putString("clip-seen", key)
        val it = PanStore.offer(text) ?: return
        showing = false
        PanLink.show(it.link)
    }

    /** Telegram 消息链接：同样只弹一次，交给 TgStore，顶上滑下 Telegram 长条（TgPill）。认出来了返回 true。 */
    private fun checkTg(text: String): Boolean {
        val l = TgLink.find(text)?.url ?: return false
        val key = "tg" + l.hashCode()
        if (prefs?.string("clip-seen") == key) return true
        if (TgPill.showing && TgStore.item(TgPill.pill)?.save == 1) return true
        prefs?.putString("clip-seen", key)
        val it = TgStore.offer(text) ?: return true
        showing = false; PanLink.dismiss()
        TgPill.show(it.link)
        return true
    }

    fun dismiss() { showing = false }

    fun handOver() {
        val c = VideoStore.clip(pill) ?: return
        ask?.invoke("看看这条抖音：" + (c.item?.title?.let { "$it " } ?: "") + c.link)
        showing = false
    }
}
