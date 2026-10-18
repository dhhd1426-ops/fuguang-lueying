package com.fuguang.lueying

import android.graphics.BitmapFactory
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.max
import kotlin.math.roundToInt

private const val VIDEO_W = 3300f
private const val VIDEO_H = 1532f

@Composable
fun rememberPoster(): ImageBitmap? {
    val context = LocalContext.current
    return remember {
        runCatching {
            context.assets.open("poster.jpg").use { BitmapFactory.decodeStream(it).asImageBitmap() }
        }.getOrNull()
    }
}

/**
 * 网站的 .background：静帧铺底，视频 object-fit: cover，object-position: 62% center。
 * [playing] 为 false 时暂停在当前帧；视频出错时只显示静帧并回调 [onFailed]。
 */
@Composable
fun SceneBackground(playing: Boolean, positionX: Float, onFailed: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val poster = rememberPoster()
    var ready by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri("asset:///scene.mp4"))
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            prepare()
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { ready = true }
            override fun onPlayerError(error: PlaybackException) { failed = true; onFailed() }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(playing, failed) { player.playWhenReady = playing && !failed }

    BoxWithConstraints(modifier.fillMaxSize().clipToBounds().background(Page.ground)) {
        val scale = max(maxWidth.value / VIDEO_W, maxHeight.value / VIDEO_H)
        val w = (VIDEO_W * scale).dp
        val h = (VIDEO_H * scale).dp
        val x = (maxWidth - w) * positionX
        val y = (maxHeight - h) / 2
        if (poster != null) {
            Placed(x, y, w, h) {
                Image(poster, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            }
        }
        if (!failed) {
            Placed(x, y, w, h) {
                AndroidView(
                    factory = { ctx -> TextureView(ctx).also { player.setVideoTextureView(it) } },
                    modifier = Modifier.fillMaxSize().alpha(if (ready) 1f else 0f),
                )
            }
        }
    }
}

/** 把子元素按给定尺寸放在给定位置（可超出父容器，再由父容器裁切）。 */
@Composable
private fun Placed(x: Dp, y: Dp, w: Dp, h: Dp, content: @Composable () -> Unit) {
    Layout(content = content) { measurables, constraints ->
        val wp = w.roundToPx()
        val hp = h.roundToPx()
        val placeables = measurables.map { it.measure(Constraints.fixed(wp, hp)) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEach { it.place(x.toPx().roundToInt(), y.toPx().roundToInt()) }
        }
    }
}

/** 网站的 .shade：左侧与底部渐暗，再叠一层可调的整体暗度。 */
@Composable
fun Shade(dim: Float, compact: Boolean, zen: Boolean) {
    val a by androidx.compose.animation.core.animateFloatAsState(if (zen) .35f else 1f, androidx.compose.animation.core.tween(if (zen) 1100 else 260), label = "shadeZen")
    Box(
        Modifier
            .fillMaxSize()
            .alpha(a)
            .background(
                if (compact) Brush.horizontalGradient(listOf(css("#11182066"), Color.Transparent))
                else Brush.horizontalGradient(0f to rgba(15, 21, 29, .38f), .65f to Color.Transparent),
            )
            .background(
                if (compact) Brush.verticalGradient(.35f to Color.Transparent, 1f to css("#111820aa"))
                else Brush.verticalGradient(.6f to Color.Transparent, 1f to rgba(12, 18, 26, .5f)),
            )
            .background(Color.Black.copy(alpha = dim)),
    )
}
