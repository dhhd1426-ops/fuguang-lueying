package com.fuguang.lueying

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Warm = css("#e9b48e")
private val FileIc = Ic("M7 3.5h7l4 4V20.5H7zM14 3.5v4h4")

/** 加号三颗球接到系统：0 照片（系统相册，不要权限）、1 拍照（系统相机）、2 文件（系统选文件）。选好的存进来排进输入框。 */
@Composable
fun rememberAttachPick(chat: ChatState): (Int) -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var shot by remember { mutableStateOf<java.io.File?>(null) }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) { us ->
        scope.launch { us.forEach { u -> Attach.save(ctx, u, asImage = true)?.let { chat.atts.add(it) } } }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = shot; shot = null
        if (f != null) scope.launch { if (ok) Attach.fromCamera(f)?.let { chat.atts.add(it) } else runCatching { f.delete() } }
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { us ->
        scope.launch { us.take(9).forEach { u -> Attach.save(ctx, u, asImage = false)?.let { chat.atts.add(it) } } }
    }
    return { k ->
        runCatching {
            when (k) {
                0 -> photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                1 -> { val (f, u) = Attach.cameraTarget(ctx); shot = f; camera.launch(u) }
                else -> files.launch(arrayOf("*/*"))
            }
        }
    }
}

/** 输入框里排着要发的照片、文件：一个个浮进来，× 去掉。她看不了图时下面一行说明。 */
@Composable
fun AttachTray(chat: ChatState, vision: Boolean, model: String) {
    androidx.compose.animation.AnimatedVisibility(
        chat.atts.isNotEmpty(),
        enter = androidx.compose.animation.expandVertically(tween(300, easing = Curves.decel)) + androidx.compose.animation.fadeIn(tween(240)),
        exit = androidx.compose.animation.shrinkVertically(tween(220, easing = Curves.accel)) + androidx.compose.animation.fadeOut(tween(160)),
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp, end = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                chat.atts.toList().forEach { r -> key(r) { TrayItem(r) { chat.atts.remove(r); Attach.drop(r) } } }
            }
            if (!vision && chat.atts.any(Attach::isImage)) Txt(
                "${model.ifBlank { "这个型号" }} 看不了图，换成 Claude、Gemini、GPT 或 Grok 她才看得见", Page.muted, 11.5.sp,
                Modifier.padding(start = 2.dp, top = 8.dp), maxLines = 2, lineHeight = 17.sp,
            )
        }
    }
}

@Composable
private fun TrayItem(ref: String, onRemove: () -> Unit) {
    val k = remember { Animatable(0f) }
    LaunchedEffect(Unit) { k.animateTo(1f, tween(320, easing = Curves.decel)) }
    Box(Modifier.graphicsLayer { alpha = k.value; val s = .6f + .4f * k.value; scaleX = s; scaleY = s; translationY = (1f - k.value) * 8.dp.toPx() }) {
        if (Attach.isImage(ref)) Thumb(ref, Modifier.size(58.dp).clip(RoundedCornerShape(14.dp)))
        else FileChip(ref, Modifier.widthIn(max = 180.dp).height(58.dp))
        Pressable(onRemove, Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp).size(24.dp), label = "去掉") { p ->
            Box(Modifier.size(22.dp).background(rgba(69, 71, 77, if (p) .75f else 1f), CircleShape), contentAlignment = Alignment.Center) {
                SvgIcon(Icons.close, Page.ink, 12.dp, stroke = 1.8f)
            }
        }
    }
}

@Composable
private fun FileChip(ref: String, modifier: Modifier) {
    Row(modifier.background(rgba(255, 255, 255, .07f), RoundedCornerShape(14.dp)).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        SvgIcon(FileIc, Warm, 18.dp, stroke = 1.5f)
        Spacer(Modifier.width(8.dp))
        Column {
            Txt(Attach.name(ref), Page.ink, 12.5.sp, maxLines = 1)
            Txt(Attach.kb(Attach.size(ref)), Page.muted, 10.5.sp, maxLines = 1)
        }
    }
}

/** 小图：按要的大小缩着读，免得大照片撑爆内存。 */
@Composable
private fun Thumb(ref: String, modifier: Modifier, px: Int = 360) {
    val img by produceState<ImageBitmap?>(null, ref) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val f = Attach.file(ref)
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, o)
                var s = 1
                while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= px) s *= 2
                BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(modifier.background(rgba(255, 255, 255, .06f))) {
        img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

/** 你发出去的那条消息上面：照片（一张大一点，几张两列）+ 文件。点照片、文件交给系统打开。 */
@Composable
fun SentFiles(files: List<String>, compact: Boolean) {
    if (files.isEmpty()) return
    val ctx = LocalContext.current
    fun open(ref: String) = runCatching {
        val u = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", Attach.file(ref))
        val mime = if (Attach.isImage(ref)) "image/*" else ctx.contentResolver.getType(u) ?: "*/*"
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    val imgs = files.filter(Attach::isImage)
    val docs = files.filterNot(Attach::isImage)
    val w: Dp = if (compact) 240.dp else 300.dp
    Column(Modifier.padding(bottom = 6.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (imgs.size == 1) Pressable({ open(imgs[0]) }, label = "看照片") { _ -> Thumb(imgs[0], Modifier.width(w * .8f).aspectRatio(1f).clip(RoundedCornerShape(16.dp)), 900) }
        else imgs.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { r -> Pressable({ open(r) }, label = "看照片") { _ -> Thumb(r, Modifier.size(w / 2 - 2.dp).clip(RoundedCornerShape(14.dp)), 600) } }
            }
        }
        docs.forEach { r -> Pressable({ open(r) }, label = "打开文件") { _ -> FileChip(r, Modifier.widthIn(max = w).height(54.dp)) } }
    }
}
