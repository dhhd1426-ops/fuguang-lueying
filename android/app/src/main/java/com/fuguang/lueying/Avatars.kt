package com.fuguang.lueying

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 头像图片：每位助理一张（按 Persona.id），你一张（ME）。裁好的 256×256 存在 App 自己的文件夹里，不上传；
 * 跟着「备份」一起走。没有图片时还是首字圆。
 */
object Avatars {
    const val ME = "me"
    private var dir: File? = null
    private val cache = HashMap<String, ImageBitmap?>()
    /** 换了图就加一，用到头像的地方跟着重画。 */
    var version by mutableIntStateOf(0)
        private set

    fun init(ctx: Context) { dir = File(ctx.applicationContext.filesDir, "avatars").apply { mkdirs() } }

    private fun file(id: String) = dir?.let { File(it, "$id.jpg") }

    fun get(id: String): ImageBitmap? = cache.getOrPut(id) {
        file(id)?.takeIf { it.exists() }?.let { f -> runCatching { BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull() }
    }

    fun set(id: String, bmp: Bitmap) {
        file(id)?.outputStream()?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        cache[id] = bmp.asImageBitmap(); version++
    }

    fun clear(id: String) { file(id)?.delete(); cache[id] = null; version++ }

    /** 读相册里选的图：长边缩到 1600 以内，免得太占内存。 */
    suspend fun load(ctx: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        }.getOrNull()
    }
}

/** 一颗头像：有图用图，没图是首字圆（fill 是底色）。 */
@Composable
internal fun AvatarFace(id: String, letter: String, fill: Brush, size: Dp, modifier: Modifier = Modifier, ink: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color(0xFF2A211B)) {
    val v = Avatars.version
    val img = remember(id, v) { Avatars.get(id) }
    Box(modifier.size(size).clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
        if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Txt(letter.ifEmpty { "?" }, ink, (size.value * .46f).sp, family = Serif, maxLines = 1)
    }
}
