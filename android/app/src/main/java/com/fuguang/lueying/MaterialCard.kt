package com.fuguang.lueying

import android.graphics.Bitmap
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import java.io.File

private val Decel = CubicBezierEasing(.05f, .7f, .1f, 1f)

/**
 * 作品卡左边：她用到的素材叠成一小摞（最多三张，最前面那张是第一件）。
 * 每张解出来就落定：从略小、透明长到原位，一张晚 40ms。
 */
@Composable
fun MaterialStack(faces: List<File>) {
    val shown = faces.take(3)
    // 从后往前画：最后一件在最左最底下
    val slots = listOf(Triple(0, 7, -8f), Triple(8, 4, -1f), Triple(17, 0, 7f)).takeLast(shown.size)
    Box(Modifier.size(width = 58.dp, height = 56.dp)) {
        shown.reversed().forEachIndexed { i, f ->
            val (x, y, r) = slots[i]
            val bmp by produceState<Bitmap?>(null, f) { value = MaterialStore.thumb(f, 160) }
            val t by animateFloatAsState(if (bmp != null) 1f else 0f, tween(240, delayMillis = i * 40, easing = Decel), label = "face")
            Box(
                Modifier.offset(x.dp, y.dp).size(38.dp, 49.dp)
                    .graphicsLayer { rotationZ = r; alpha = t; scaleX = .9f + .1f * t; scaleY = .9f + .1f * t }
                    .shadow(6.dp, RoundedCornerShape(8.dp))
                    .clip(RoundedCornerShape(8.dp))
                    .background(veil(.06f)),
            ) {
                bmp?.let { Image(it.asImageBitmap(), null, Modifier.size(38.dp, 49.dp), contentScale = ContentScale.Crop) }
            }
        }
    }
}
