package com.fuguang.lueying

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 聊天里放抖音（原型 https://claude.ai/artifact/TZ2J8C7YdpLoM4AfVdseVT）：点卡片封面原地放，底下用的是下载页那套播放器（[DlPlay]）。
 * 每条抖音造一个临时的 [Dl]：存好了放相册里那份（本地），没存放无水印地址（在线，走同一套磁盘缓存），解析不出交给 yt-dlp 读流。
 * 同时只放一条；正在放的卡滑出屏幕，右下角缩成小窗接着放，点小窗回到那张卡，× 停掉。全屏用下载页那套。
 */
object ClipPlay {
    private val dls = HashMap<String, Dl>()
    /** 画面在右下角小窗里（卡片滑出屏幕了）。 */
    var mini by mutableStateOf(false)
    /** 点小窗：把正在放的卡滑回来（卡片挂上）。 */
    var bring: (() -> Unit)? = null

    private fun base(c: Clip) = "clip-" + c.link.hashCode().toUInt().toString(36)
    fun isMine(c: Clip): Boolean = DlPlay.id?.startsWith(base(c)) == true
    fun playing(): Dl? = DlPlay.id?.let { dls[it] }

    /** 这条能不能放（图集不能）。 */
    fun playable(c: Clip): Boolean = c.item?.images?.isNotEmpty() != true || (c.save == 2 && !c.outMime.startsWith("image"))

    private fun dl(c: Clip): Dl? {
        val i = c.item
        val local = c.save == 2 && c.out.isNotEmpty() && !c.outMime.startsWith("image")
        val id = base(c) + if (local) "l" else "n"
        dls[id]?.let { return it }
        val d = when {
            local -> Dl(id, c.title, Sniffer.Media("file", c.out, mime = c.outMime), "", null, c.outMime.startsWith("audio"), true, false).also { it.state = 3; it.out = c.out }
            i?.video != null -> Dl(id, c.title, Sniffer.Media("file", i.video, headers = Douyin.headers(), page = i.pageUrl, mime = "video/mp4"), "720p", null, false, false, false)
            i != null && i.images.isNotEmpty() -> return null
            else -> (i?.pageUrl ?: c.link).let { pg -> Dl(id, c.title, Sniffer.Media("ytdlp", pg, headers = Douyin.headers(), page = pg), "最清楚", "0", false, false, false) }
        }
        dls[id] = d
        return d
    }

    fun play(ctx: Context, c: Clip) {
        val d = dl(c) ?: return
        mini = false
        if (DlPlay.id != d.id) DlPlay.open(ctx, d)
    }
}

/** 盖在聊天页上：小窗 + 全屏。 */
@Composable
fun ClipPlayLayer() {
    val d = ClipPlay.playing()
    val pl = DlPlay.player
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        AnimatedVisibility(
            d != null && pl != null && ClipPlay.mini && !DlPlay.full && DlPlay.fail == null,
            enter = scaleIn(spring(.8f, 380f), .85f) + fadeIn(tween(200)),
            exit = scaleOut(tween(180, easing = Curves.accel), .85f) + fadeOut(tween(160)),
            modifier = Modifier.navigationBarsPadding().padding(end = 12.dp, bottom = 104.dp),
        ) {
            val player = DlPlay.player ?: return@AnimatedVisibility
            Pressable({ ClipPlay.bring?.invoke() }, label = "回到那张卡") { _ ->
                Box(Modifier.width(156.dp).aspectRatio(16 / 10f).shadow(14.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).background(Color.Black)) {
                    DlBareSurface(player)
                    var playing by remember(player) { mutableStateOf(player.playWhenReady) }
                    Pressable({ player.playWhenReady = !player.playWhenReady; playing = player.playWhenReady }, Modifier.align(Alignment.BottomStart).padding(4.dp), label = "播放暂停") { _ ->
                        Box(Modifier.size(26.dp).background(Color(0x99000000), CircleShape), contentAlignment = Alignment.Center) { PlayGlyph(playing, Modifier.size(12.dp)) }
                    }
                    Pressable({ DlPlay.close(); ClipPlay.mini = false }, Modifier.align(Alignment.TopEnd).padding(4.dp), label = "停掉") { _ ->
                        Box(Modifier.size(24.dp).background(Color(0x99000000), CircleShape), contentAlignment = Alignment.Center) { SvgIcon(Icons.close, Page.ink, 12.dp, stroke = 1.8f) }
                    }
                }
            }
        }
    }
    DlFull(d)
}
