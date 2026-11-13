package com.fuguang.lueying

import java.util.Locale

/** All tool times use milliseconds; decoded PTS is retained separately. Never infer time from frame index. */
internal object MotionTimes {
    fun plan(duration: Long, start: Long, end: Long, count: Int): List<Long> {
        require(duration > 0) { "视频时长不可用" }
        require(start >= 0 && end >= start && end < duration) { "区间必须在视频时长内，结束时间小于 duration_ms" }
        require(count in 1..12) { "每次取 1–12 帧；需要更密时缩小区间后继续查看" }
        if (start == end || count == 1) return listOf(start)
        return List(count) { start + ((end - start).toDouble() * it / (count - 1)).toLong() }.distinct()
    }

    fun points(duration: Long, times: List<Long>): List<Long> {
        require(times.size in 1..12) { "每次指定 1–12 个时刻" }
        require(times.all { it >= 0 && it < duration }) { "时间必须在视频范围内" }
        return times.distinct().sorted()
    }

    /** Select the first existing source frame at or after each target. Multiple targets may resolve to one frame. */
    fun select(times: List<Long>): String = times.joinToString("+") { ms ->
        val t = String.format(Locale.US, "%.6f", ms / 1000.0)
        "gte(t,$t)*if(isnan(prev_pts),1,lt(prev_pts*TB,$t))"
    }

    fun pts(log: String): List<Long> = Regex("\\bn:\\s*\\d+.*?\\bpts_time:([0-9.eE+\\-]+)")
        .findAll(log).map { (it.groupValues[1].toDouble() * 1_000_000).toLong() }.toList()

    fun label(us: Long): String = String.format(Locale.US, "%.3f s", us / 1_000_000.0)
}
