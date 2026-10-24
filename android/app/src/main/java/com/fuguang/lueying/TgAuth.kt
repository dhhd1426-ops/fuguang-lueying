package com.fuguang.lueying

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Telegram 授权弹窗：从「登录后下」那颗按钮长出统一的悬浮卡片（CenterCardLayer），授权好缩回按钮，
 * 长条上那条接着认、接着下。
 */
object TgAuth : FloatCard() {
    /** 从哪一条点进来的：授权好接着下它。 */
    var focus by mutableStateOf<TgItem?>(null)

    fun show(it: TgItem?) { focus = it; open = true }
}

@Composable
fun TgAuthLayer() {
    val act = LocalContext.current as? Activity
    CenterCardLayer(TgAuth, "auth", { 0 }, onBack = { false }, onClosed = { TgAuth.focus = null }) { _, rise ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.padding(end = 40.dp).mineRise(0, rise), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).background(ClipWarm.copy(alpha = .1f), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                    Txt("T", ClipWarm, 17.sp, family = Serif)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Txt("授权 Telegram", Page.ink, 17.sp, family = Serif)
                    Spacer(Modifier.height(2.dp))
                    Txt("只存在这台手机上", Page.muted, 11.5.sp)
                }
            }
            Box(Modifier.fillMaxWidth().mineRise(1, rise)) {
                TgLogin {
                    val f = TgAuth.focus
                    TgAuth.open = false
                    TgStore.loggedIn(f, act)
                }
            }
            Txt("Vignette 用 Telegram 官方的 TDLib 登录，只用来下你复制的链接：不发消息、不动你的聊天。在「我的 → 连接器 → Telegram」里能退出。",
                Page.muted.copy(alpha = .7f), 11.sp, Modifier.mineRise(2, rise), lineHeight = 17.sp)
        }
    }
}
