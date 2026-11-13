package com.fuguang.lueying

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MotionTimesTest {
    @Test fun plansSmallRangesWithoutInventingDuplicateMilliseconds() {
        assertEquals(listOf(100L, 101L, 102L), MotionTimes.plan(1000, 100, 102, 12))
        assertEquals(listOf(0L, 250L, 500L), MotionTimes.plan(1000, 0, 500, 3))
        assertEquals(listOf(100L), MotionTimes.plan(1000, 100, 100, 8))
    }

    @Test fun rejectsOutOfRangeRatherThanSilentlyChangingSampling() {
        for (args in listOf(listOf(1000L, -1L, 200L), listOf(1000L, 0L, 1000L), listOf(0L, 0L, 0L))) {
            try { MotionTimes.plan(args[0], args[1], args[2], 8); fail("invalid range accepted") }
            catch (_: IllegalArgumentException) { }
        }
        try { MotionTimes.plan(1000, 0, 500, 13); fail("over budget") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun parsesMeasuredPtsIncludingScientificNotation() {
        assertEquals(listOf(0L, 41_666L, 5L), MotionTimes.pts("""
            [Parsed_showinfo] n:   0 pts: 0 pts_time:0 duration:1
            [Parsed_showinfo] n:   1 pts: 512 pts_time:0.0416667 duration:1
            [Parsed_showinfo] n:   2 pts: 1 pts_time:5e-06 duration:1
        """.trimIndent()))
    }

    @Test fun realDecoderKeepsSourceFramesAndReportsMissedTail() {
        val dir = Files.createTempDirectory("motion-pts").toFile()
        try {
            fun command(vararg args: String): String {
                val p = ProcessBuilder(listOf("ffmpeg", "-hide_banner", "-nostdin", "-y") + args).redirectErrorStream(true).start()
                val output = p.inputStream.bufferedReader().readText()
                assertEquals(output, 0, p.waitFor())
                return output
            }
            val video = File(dir, "reference.mp4")
            command("-loglevel", "error", "-f", "lavfi", "-i", "testsrc2=size=96x64:rate=24:duration=1", "-c:v", "libx264", video.path)
            // Two targets less than one source-frame apart must not create an interpolated or duplicated frame.
            val times = listOf(0L, 1L, 10L, 500L, 999L)
            val log = command("-loglevel", "info", "-i", video.path, "-an", "-vf",
                "setpts=PTS-STARTPTS,select='${MotionTimes.select(times)}',showinfo", "-vsync", "0", File(dir, "f-%03d.jpg").path)
            val pts = MotionTimes.pts(log)
            assertEquals(listOf(0L, 41_666L, 500_000L), pts)
            assertEquals(3, dir.listFiles()!!.count { it.extension == "jpg" })
            assertTrue(pts.none { it >= 999_000 })
        } finally { dir.deleteRecursively() }
    }
}
