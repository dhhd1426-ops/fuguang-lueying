package com.fuguang.lueying

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the actual private display, Chromium compositor and PixelCopy, without a model/API key. */
@RunWith(AndroidJUnit4::class)
class MotionPreviewTest {
    @Test fun packagedVideoDecoderReturnsRealFramesWithTimeLabels() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        Attach.init(context)
        YtDlp.init(context, Prefs(context))
        val ref = "motiontest/reference.mp4"
        val source = Attach.file(ref)
        source.parentFile!!.mkdirs()
        source.writeBytes(android.util.Base64.decode(instrumentation.context.assets.open("motion-reference.b64")
            .bufferedReader().use { it.readText() }, android.util.Base64.DEFAULT))
        val session = MotionTools.Session(listOf(ref), true)
        val out = session.run(MotionTools.FRAMES, org.json.JSONObject().put("source", ref)
            .put("times_ms", org.json.JSONArray(listOf(0, 1, 10, 500))).put("max_edge", 320))
        assertFalse(out.text, out.failed)
        assertEquals(out.text, 3, out.images.size)
        assertTrue(out.images.all { it.caption.contains("参考") && it.caption.contains("s") })
        assertTrue(out.text.contains("actual_pts_us"))
        val refused = session.run(MotionTools.FRAMES, org.json.JSONObject().put("source", "../private.mp4"))
        assertTrue(refused.failed)
    }

    @Test fun oldToolImagesAreArchivedWithoutDroppingToolIdsOrUserAttachments() {
        val messages = org.json.JSONArray()
        val originalPhoto = org.json.JSONArray().put(org.json.JSONObject().put("type", "image"))
        messages.put(org.json.JSONObject().put("role", "user").put("content", originalPhoto))
        repeat(5) { i ->
            val blocks = org.json.JSONArray().put(org.json.JSONObject().put("type", "text").put("text", "time=$i"))
                .put(org.json.JSONObject().put("type", "image"))
            messages.put(org.json.JSONObject().put("role", "user").put("content", org.json.JSONArray().put(
                org.json.JSONObject().put("type", "tool_result").put("tool_use_id", "call-$i").put("content", blocks))))
        }
        ToolImages.trim(messages, 3)
        assertEquals(1, originalPhoto.length())
        repeat(5) { i ->
            val tool = messages.getJSONObject(i + 1).getJSONArray("content").getJSONObject(0)
            assertEquals("call-$i", tool.getString("tool_use_id"))
            val blocks = tool.getJSONArray("content")
            assertEquals("time=$i", blocks.getJSONObject(0).getString("text"))
            assertEquals(i >= 2, (0 until blocks.length()).any { blocks.getJSONObject(it).optString("type") == "image" })
        }
    }

    private val page = """<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>html,body{margin:0;background:#fff}#box{width:40px;height:40px;background:#f00}</style>
        <div id="box"></div><script>
        window.__motion={durationMs:1000,ready:Promise.resolve(),seek(ms){
            document.getElementById('box').style.transform='translateX('+(ms/10)+'px)';
        }};</script>"""

    @Test fun exactTimesRenderDifferentPixelsAndSeekingBackIsRepeatable() = runBlocking {
        MotionPreview.init(InstrumentationRegistry.getInstrumentation().targetContext)
        val shots = MotionPreview.render(page, listOf(0, 500, 1000), 320, 240)
        try {
            assertEquals(3, shots.size)
            assertTrue(Color.red(shots[0].bitmap.getPixel(10, 10)) > 200)
            assertTrue(Color.green(shots[0].bitmap.getPixel(10, 10)) < 50)
            assertTrue(Color.green(shots[1].bitmap.getPixel(10, 10)) > 200)
            assertTrue(Color.green(shots[1].bitmap.getPixel(60, 10)) < 50)
            assertTrue(Color.green(shots[2].bitmap.getPixel(110, 10)) < 50)
        } finally { shots.forEach { it.bitmap.recycle() } }
    }

    @Test fun uncontrolledCanvasCannotPassAsInspectedAnimation() = runBlocking {
        MotionPreview.init(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            MotionPreview.render("<canvas width='100' height='100'></canvas>", listOf(0), 320, 240)
            fail("canvas without time adapter accepted")
        } catch (e: IllegalStateException) { assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("seek")) }
    }

    @Test fun nonDeterministicSeekIsRejectedAndResourcesCanBeReused() = runBlocking {
        MotionPreview.init(InstrumentationRegistry.getInstrumentation().targetContext)
        val bad = page.replace("window.__motion=", "let count=0;window.__motion=")
            .replace("ms/10", "(++count)*20")
        try {
            MotionPreview.render(bad, listOf(0, 500), 320, 240)
            fail("non-repeatable rendering accepted")
        } catch (e: IllegalStateException) { assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("重复")) }
        val shots = MotionPreview.render(page, listOf(0), 320, 240)
        shots.forEach { it.bitmap.recycle() }
    }
}
