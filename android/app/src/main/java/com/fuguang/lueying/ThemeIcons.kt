package com.fuguang.lueying

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** 一行说明 + 跟在后面的小链接（杏色）。 */
private class IconNote(val text: String, val home: Boolean = false, val again: Boolean = false)

/**
 * 「主题」下面的「图标」：三列格子，四个预设 + 自定义。选中的外面一圈杏色、右下角画出 ✓；
 * 下面一行说结果，换了的能「回桌面看看」，自定义的还能「换一张」。
 */
@Composable
internal fun IconSection() {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf(IconNote("桌面上是「${AppIcon.name()}」。")) }
    fun pinned(ok: Boolean) {
        note = if (ok) IconNote("桌面上加了你的图标。应用列表里还是原来那个。", home = true, again = true)
        else IconNote("这台手机的桌面不让 App 加图标，换一个预设吧。")
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { Avatars.load(ctx, uri)?.let { b -> IconCrop.open(b) { out -> pinned(AppIcon.pin(ctx, out)) } } }
    }
    fun choose() = pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    fun preset(id: String) {
        if (AppIcon.id == id) return
        val hadPin = AppIcon.pinned(ctx)
        AppIcon.use(ctx, id)
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        note = IconNote("桌面图标换成了「${AppIcon.name(id)}」，几秒内生效。" + if (hadPin) "你加的那个还在桌面上，不要了长按删掉。" else "", home = true)
    }

    Txt("图标", Page.muted.copy(alpha = .7f), 12.sp, Modifier.padding(start = 8.dp, top = 10.dp, bottom = 6.dp), spacing = 1.sp)
    val tiles = AppIcon.opts.map { it.id } + AppIcon.CUSTOM
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { id ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        IconTile(id, AppIcon.id == id) {
                            when {
                                id != AppIcon.CUSTOM -> preset(id)
                                !AppIcon.hasCustom() || AppIcon.id == AppIcon.CUSTOM -> choose()
                                else -> { pinned(AppIcon.pin(ctx)); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                            }
                        }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    AnimatedContent(
        note,
        transitionSpec = { (fadeIn(tween(220, 60)) + slideInVertically(tween(260, 60, Curves.decel)) { it / 4 }) togetherWith (fadeOut(tween(140)) + slideOutVertically(tween(160, easing = Curves.accel)) { -it / 4 }) },
        label = "iconNote",
    ) { n ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp)) {
            Txt(n.text, Page.muted.copy(alpha = .8f), 11.5.sp, lineHeight = 19.sp)
            if (n.home || n.again) Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (n.home) NoteLink("回桌面看看") { AppIcon.goHome(ctx) }
                if (n.again) NoteLink("换一张") { choose() }
            }
        }
    }
}

@Composable
private fun NoteLink(text: String, onClick: () -> Unit) {
    Pressable(onClick, label = text) { p -> Txt(text, SideWarm.copy(alpha = if (p) .6f else 1f), 12.sp, Modifier.padding(vertical = 6.dp)) }
}

/** 一格：58dp 圆润方块 + 名字。选中：外圈杏色、右下角小圆里画 ✓；按下缩一下。 */
@Composable
private fun IconTile(id: String, on: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val ver = AppIcon.customVer
    val face = remember(id, ver) { tileImage(ctx, id) }
    val ring by animateFloatAsState(if (on) 1f else 0f, tween(240, easing = Curves.standard), label = "ring")
    val badge by animateFloatAsState(if (on) 1f else 0f, if (on) spring(.5f, 500f) else tween(140, easing = Curves.accel), label = "badge")
    val name = if (id == AppIcon.CUSTOM) "自定义" else AppIcon.name(id)
    Pressable(onClick, label = name) { pressed ->
        val press by animateFloatAsState(if (pressed) .92f else 1f, if (pressed) tween(70) else spring(.5f, 600f), label = "press")
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(66.dp).graphicsLayer { scaleX = press; scaleY = press }, contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(66.dp).drawBehind {
                        if (ring > 0f) drawRoundRect(SideWarm.copy(alpha = ring), Offset(1.dp.toPx(), 1.dp.toPx()), Size(size.width - 2.dp.toPx(), size.height - 2.dp.toPx()), CornerRadius(size.width * .32f), style = Stroke(2.dp.toPx()))
                    },
                )
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(30)).background(veil(.08f)), contentAlignment = Alignment.Center) {
                    if (face != null) Image(face, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else SvgIcon(Ic("M12 5v14M5 12h14"), Page.muted, 22.dp, stroke = 1.7f)
                }
                if (badge > .01f) Box(
                    Modifier.align(Alignment.BottomEnd).offset(x = (-1).dp, y = (-1).dp).size(20.dp).graphicsLayer { scaleX = badge; scaleY = badge; alpha = badge.coerceIn(0f, 1f) }
                        .background(SideWarm, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { if (on) DrawnCheck(Skin.onAccent, 13.dp) }
            }
            Spacer(Modifier.height(5.dp))
            Txt(name, if (on) Page.ink else Page.muted, 12.sp, maxLines = 1)
        }
    }
}

/** 格子里的图：取前景中间 72/108（系统遮罩最多露出的那块），自定义取 custom.png 中间 2/3。 */
private fun tileImage(ctx: android.content.Context, id: String): ImageBitmap? = runCatching {
    val full: Bitmap = if (id == AppIcon.CUSTOM) BitmapFactory.decodeFile(AppIcon.customFile().path) ?: return null
    else BitmapFactory.decodeResource(ctx.resources, AppIcon.opts.first { it.id == id }.fg) ?: return null
    val i = full.width / 6
    Bitmap.createBitmap(full, i, i, full.width - 2 * i, full.height - 2 * i).asImageBitmap()
}.getOrNull()
