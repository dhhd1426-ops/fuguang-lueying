package com.fuguang.lueying

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import java.io.File

/**
 * 放不了时说清楚为什么，再给一件能马上做的事（原型 https://claude.ai/artifact/YMgwXwAyY3e6PgAhHGRinE）。
 * mux 文件没合好 → 修一下（Remux.repair）；codec 这台手机解不了 → 用别的播放器；gone 文件不在了 → 从下载里移除；
 * net 还没下好、网上读不了 → 等下好。灰色小字是原因码，截图就能对上是哪一种。
 */
internal class Fail(val kind: String, val msg: String, val why: String)

internal object DlFail {
    fun of(ctx: Context, d: Dl, e: PlaybackException, fmt: Format?): Fail {
        val code = e.errorCode; val name = e.errorCodeName
        if (d.state != 3 && d.media.kind == "magnet") return when (code) {
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> Fail("net", "还没连上传这个视频的人，等一会儿再点", name)
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> Fail("net", "这一段一直没人传过来，等下多一点再看", name)
            else -> if (code in 4001..4005) codec(ctx, d, fmt, name) else Fail("net", "这一个现在读不了，下好就能看", name)
        }
        if (d.state != 3) return when (code) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> Fail("net", "网连不上，下好就能看", name)
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> Fail("net", "网站不让直接读，下好就能看", name)
            else -> if (code in 4001..4005) codec(ctx, d, fmt, name) else Fail("net", "这一个现在读不了，下好就能看", name)
        }
        val uri = if (d.out.startsWith("content://")) Uri.parse(d.out) else null
        val gone = if (uri != null) Downloads.missing(uri) else !File(d.out).exists()
        if (gone || code == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND) return Fail("gone", "文件不在了，可能在相册里删掉了", name)
        if (code in 4001..4005) return codec(ctx, d, fmt, name)
        // 本地文件别的错（盒子坏了、读到文件外面去了）：多半是合成时写坏的，重新装一遍能好
        return Fail("mux", "这个文件没合好，修一下就能看", name)
    }

    /** 解码器的错：手机真没有能解这种画面的，就是解不了；有的话就是文件里的数据坏了，当没合好。 */
    private fun codec(ctx: Context, d: Dl, fmt: Format?, name: String): Fail {
        val f = probe(ctx, d)
        val desc = listOfNotNull(
            (fmt?.sampleMimeType ?: f?.getString(MediaFormat.KEY_MIME))?.let { codecName(it) },
            (fmt?.takeIf { it.width > 0 }?.let { "${it.width}×${it.height}" }) ?: f?.takeIf { it.containsKey(MediaFormat.KEY_WIDTH) }?.let { "${it.getInteger(MediaFormat.KEY_WIDTH)}×${it.getInteger(MediaFormat.KEY_HEIGHT)}" },
        ).joinToString(" · ")
        val why = if (desc.isEmpty()) name else "$desc · $name"
        val can = f?.let { runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(it.also { x -> x.setString(MediaFormat.KEY_FRAME_RATE, null) }) != null }.getOrDefault(true) } ?: false
        return if (d.state == 3 && can) Fail("mux", "这个文件没合好，修一下就能看", why) else Fail("codec", "这台手机解不了这种画面", why)
    }

    private fun probe(ctx: Context, d: Dl): MediaFormat? = runCatching {
        if (d.state != 3) return null
        val e = MediaExtractor()
        try {
            if (d.out.startsWith("content://")) e.setDataSource(ctx, Uri.parse(d.out), null) else e.setDataSource(d.out)
            (0 until e.trackCount).map { e.getTrackFormat(it) }.firstOrNull { it.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/") }
        } finally { e.release() }
    }.getOrNull()

    private fun codecName(m: String) = when (m) {
        MimeTypes.VIDEO_H265 -> "HEVC"; MimeTypes.VIDEO_H264 -> "H.264"; MimeTypes.VIDEO_AV1 -> "AV1"
        MimeTypes.VIDEO_VP9 -> "VP9"; MimeTypes.VIDEO_DOLBY_VISION -> "杜比视界"; else -> m.substringAfter('/').uppercase()
    }

    /** 交给系统，让他挑一个播放器（MX、VLC 这类软解的多半能放）。 */
    fun external(ctx: Context, d: Dl) {
        val u = Downloads.uri(d) ?: return
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(u, if (d.audioOnly) "audio/*" else "video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(Intent.createChooser(i, "用哪个播放器打开").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: ActivityNotFoundException) {}
    }
}

/** 画面中间那一块：一句话 + 灰色原因 + 能做的事；修的时候换成进度。 */
@Composable
internal fun FailNote(d: Dl, f: Fail) {
    val ctx = LocalContext.current
    val a = remember(f) { mutableFloatStateOf(0f) }
    LaunchedEffect(f) { a.floatValue = 1f }
    val k by animateFloatAsState(a.floatValue, tween(320, easing = Curves.decel), label = "fail")
    Column(
        Modifier.fillMaxSize().padding(horizontal = 18.dp).graphicsLayer { alpha = k; translationY = (1f - k) * 18f },
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Txt(f.msg, Page.ink, 14.sp, align = TextAlign.Center, maxLines = 2)
        Txt(f.why, Page.muted, 11.sp, Modifier.padding(top = 5.dp), align = TextAlign.Center, maxLines = 2)
        if (DlPlay.fixing) {
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                LoadRing(FpWarm, 16.dp); Txt("正在重新合成，不用重新下", Page.ink, 12.5.sp, Modifier.padding(start = 8.dp))
            }
            return@Column
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (f.kind) {
                "mux" -> { FailBtn("修一下", true) { DlPlay.repair(ctx, d) }; FailBtn("用别的播放器", false) { DlFail.external(ctx, d) } }
                "codec" -> if (d.state == 3) FailBtn("用别的播放器", true) { DlFail.external(ctx, d) } else FailBtn("好", true) { DlPlay.close() }
                "stall" -> {
                    FailBtn("从头再放", true) { DlPlay.open(ctx, d, fresh = true) }
                    if (d.state == 3) FailBtn("修一下", false) { DlPlay.repair(ctx, d) }
                    if (d.state == 3) FailBtn("别的播放器", false) { DlFail.external(ctx, d) }
                }
                "gone" -> FailBtn("从下载里移除", true) { Downloads.delete(d) }
                else -> FailBtn("好", true) { DlPlay.close() }
            }
        }
    }
}

@Composable
private fun FailBtn(text: String, main: Boolean, onClick: () -> Unit) {
    PressSurface(onClick, Modifier.height(34.dp), shape = RoundedCornerShape(17.dp), fill = if (main) FpWarm else FpGlass, pressedFill = if (main) FpWarm.copy(alpha = .8f) else FpWarm.copy(alpha = .22f), pressScale = .94f, label = text) {
        Box(Modifier.align(Alignment.Center).padding(horizontal = 14.dp)) { Txt(text, if (main) Color(0xFF2A1D14) else Page.ink, 13.sp, maxLines = 1) }
    }
}
