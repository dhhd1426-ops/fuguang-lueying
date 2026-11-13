package com.fuguang.lueying

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Persisted visual evidence is available to the user after the model call and after process recreation. */
internal object MotionEvidence {
    private var shown by mutableStateOf<String?>(null)
    private val marker = Regex("证据：(motion-[0-9a-f-]{36})")
    fun save(text: String, pictures: List<MotionTools.Picture>): String {
        val id = "motion-${UUID.randomUUID()}"
        val dir = File(VideoFrames.root, "evidence/$id").apply { mkdirs() }
        val rows = pictures.mapIndexed { i, p ->
            val name = "$i.${if (p.mime == "image/jpeg") "jpg" else "png"}"
            File(dir, name).writeBytes(p.bytes)
            JSONObject().put("file", name).put("caption", p.caption)
        }
        VideoFrames.atomic(File(dir, "evidence.json"), JSONObject().put("text", text).put("frames", JSONArray(rows)).toString())
        return id
    }

    @Composable
    fun Button(text: String) {
        val id = marker.find(text)?.groupValues?.get(1) ?: return
        Pressable({ shown = id }, label = "查看实际帧图") { pressed ->
            Txt("查看实际帧图与对比", Page.ink, 13.sp, Modifier.background(veil(if (pressed) .12f else .07f), RoundedCornerShape(8.dp)).padding(10.dp))
        }
    }

    @Composable
    fun Layer() {
        val id = shown ?: return
        val data by produceState<JSONObject?>(null, id) {
            value = withContext(Dispatchers.IO) { runCatching {
                JSONObject(File(VideoFrames.root, "evidence/$id/evidence.json").readText())
            }.getOrNull() }
        }
        val frames = data?.optJSONArray("frames")
        var index by remember(id) { mutableStateOf(0) }
        val row = frames?.optJSONObject(index)
        val bitmap by produceState<Bitmap?>(null, id, index, row?.optString("file")) {
            value = withContext(Dispatchers.IO) {
                row?.optString("file")?.takeIf { it.matches(Regex("\\d+\\.(png|jpg)")) }
                    ?.let { BitmapFactory.decodeFile(File(VideoFrames.root, "evidence/$id/$it").path) }
            }
        }
        val ownedBitmap = bitmap
        DisposableEffect(ownedBitmap) { onDispose { ownedBitmap?.recycle() } }
        Dialog(onDismissRequest = { shown = null }) {
            Column(Modifier.fillMaxWidth().background(Page.ground, RoundedCornerShape(20.dp)).padding(16.dp)
                .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Txt("实际画面", Page.ink, 17.sp)
                    Pressable({ shown = null }, label = "关闭帧图") { Txt("关闭", Page.muted, 14.sp) }
                }
                if (frames == null) Txt("正在读取画面…", Page.muted, 13.sp)
                else {
                    Txt(row?.optString("caption").orEmpty(), Page.ink, 13.sp, lineHeight = 20.sp)
                    bitmap?.let { Image(it.asImageBitmap(), row?.optString("caption"), Modifier.fillMaxWidth().heightIn(max = 520.dp), contentScale = ContentScale.Fit) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Pressable({ if (index > 0) index-- }, label = "上一张帧图") { Txt("上一张", if (index > 0) Page.ink else Page.muted, 14.sp) }
                        Txt("${index + 1} / ${frames.length()}", Page.muted, 13.sp)
                        Pressable({ if (index + 1 < frames.length()) index++ }, label = "下一张帧图") { Txt("下一张", if (index + 1 < frames.length()) Page.ink else Page.muted, 14.sp) }
                    }
                    Txt("抽样画面；不代表整段动画和交互全部通过。", Page.muted, 11.sp, lineHeight = 17.sp)
                }
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}
