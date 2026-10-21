package com.fuguang.lueying

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * 自动开通被 Telegram 拦了（它故意拦脚本建应用）：
 * 「去网页里建」——把登好的 Cookie 交给内置浏览器，打开就是开通页，他填个名字点 Create application；这边每 1.5 秒读一次，读到就关浏览器接着登录。
 * 「粘贴已有的」——在电脑上建过的，把 api_id、api_hash 贴进来。
 */
@Composable
internal fun TgManualStep(onKeys: (Int, String) -> Unit) {
    var waiting by remember { mutableStateOf(false) }
    var paste by remember { mutableStateOf(false) }
    var id by remember { mutableStateOf("") }
    var hash by remember { mutableStateOf("") }
    LaunchedEffect(waiting) {
        if (!waiting) return@LaunchedEffect
        repeat(400) {
            delay(1500)
            TgApp.fromWeb()?.let { (i, h) -> InBrowser.close(); waiting = false; onKeys(i, h); return@LaunchedEffect }
            if (InBrowser.url == null) { TgApp.fromWeb()?.let { (i, h) -> onKeys(i, h) }; waiting = false; return@LaunchedEffect }
        }
        waiting = false
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Txt("Telegram 不让 App 自动建通行证，要你在官网点一下：已经替你登好了，打开后填个名字（别带 Telegram 字样），平台选 Android，点 Create application，建好会自己回来。",
            Page.muted, 12.sp, lineHeight = 18.sp)
        Wide(if (waiting) "建好会自己回来" else "去网页里建", !waiting) {
            TgApp.toWeb(); InBrowser.open("https://my.telegram.org/apps"); waiting = true
        }
        Wide(if (paste) "收起" else "我有 api_id，粘贴进来", true, main = false) { paste = !paste }
        AnimatedVisibility(paste, enter = expandVertically(tween(320, easing = Curves.decel), Alignment.Top) + fadeIn(tween(200, 60)),
            exit = shrinkVertically(tween(220, easing = Curves.accel), Alignment.Top) + fadeOut(tween(140))) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CardField(id, { id = it.filter { c -> c.isDigit() } }, "api_id（一串数字）", keyboard = KeyboardType.Number)
                CardField(hash, { hash = it.trim() }, "api_hash（32 位字母数字）")
                Wide("用这个", id.toIntOrNull() != null && hash.length >= 16) { onKeys(id.toInt(), hash) }
            }
        }
        Txt("网页里也只显示 ERROR 的话：换一个代理节点（和手机号同一个国家更好）或换手机流量，隔一会儿再试。",
            Page.muted.copy(alpha = .6f), 11.sp, lineHeight = 16.sp)
    }
}
