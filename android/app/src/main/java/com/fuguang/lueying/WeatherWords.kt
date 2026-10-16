package com.fuguang.lueying

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import java.time.LocalDate

/* 天气码（WMO）→ 中文、图标；空气、风、日子的说法；一天的一句话。 */

enum class WxKind { Sun, Moon, Part, NightPart, Cloud, Rain, Snow }

fun wxKind(code: Int, day: Boolean = true): WxKind = when (code) {
    0, 1 -> if (day) WxKind.Sun else WxKind.Moon
    2 -> if (day) WxKind.Part else WxKind.NightPart
    3, 45, 48 -> WxKind.Cloud
    71, 73, 75, 77, 85, 86 -> WxKind.Snow
    else -> if (code >= 51) WxKind.Rain else WxKind.Cloud
}

fun wxText(code: Int): String = when (code) {
    0 -> "晴"; 1 -> "大致晴"; 2 -> "多云"; 3 -> "阴"
    45, 48 -> "雾"
    51, 53, 55, 56, 57 -> "毛毛雨"
    61 -> "小雨"; 63 -> "中雨"; 65 -> "大雨"; 66, 67 -> "冻雨"
    71 -> "小雪"; 73 -> "中雪"; 75 -> "大雪"; 77 -> "雪粒"
    80 -> "阵雨"; 81 -> "中阵雨"; 82 -> "强阵雨"; 85, 86 -> "阵雪"
    95 -> "雷阵雨"; 96, 99 -> "雷阵雨伴冰雹"
    else -> "—"
}

fun aqiWord(v: Int): String = when {
    v <= 50 -> "优"; v <= 100 -> "良"; v <= 150 -> "轻度"; v <= 200 -> "中度"; v <= 300 -> "重度"; else -> "严重"
}

private val beaufort = doubleArrayOf(1.0, 6.0, 12.0, 20.0, 29.0, 39.0, 50.0, 62.0, 75.0, 89.0, 103.0, 118.0)
fun windLevel(kmh: Double): Int = beaufort.indexOfFirst { kmh < it }.let { if (it < 0) 12 else it }
fun windDir(deg: Int): String = listOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")[((deg + 22.5) / 45).toInt() % 8] + "风"
fun uvWord(uv: Double): String = when { uv < 3 -> "弱"; uv < 6 -> "中等"; uv < 8 -> "强"; else -> "很强" }

private const val WEEK = "一二三四五六日"
fun weekName(d: LocalDate) = "周" + WEEK[d.dayOfWeek.value - 1]
fun dayName(d: LocalDate, today: LocalDate = LocalDate.now()) = when (d) {
    today -> "今天"; today.plusDays(1) -> "明天"; else -> weekName(d)
}

/** 那一天的一句话：天气、最暖的时候、温差、带伞、空气。 */
fun daySay(d: WxDay): String {
    val parts = mutableListOf<String>()
    val warm = d.hours.maxByOrNull { it.temp }
    parts += wxText(d.code) + if (warm != null) "，${if (warm.hour < 12) "上午" else if (warm.hour < 18) "下午" else "晚上"}${warm.hour % 12}点前后最暖 ${d.hi}°" else "，最高 ${d.hi}°"
    if (d.hi - d.lo >= 10) parts += "早晚只有 ${d.lo}°，出门带件外套"
    if (d.rain >= 50) parts += "下雨的可能有 ${d.rain}%，记得带伞"
    if (windLevel(d.windKmh) >= 5) parts += "风大，体感会比温度低"
    if ((d.aqi ?: 0) > 150) parts += "空气不好，少在外面待太久"
    return parts.joinToString("，") + "。"
}

/* ---------- 线条图标：太阳杏色、雨蓝灰、其余米白 ---------- */

private val sunC = Color(0xFFE9B48E)
private val paleC = Color(0xFFEFE3CF)
private val rainC = Color(0xFF9FB6CF)
private val sunFull = Ic("M12 3v1.6M12 19.4V21M3 12h1.6M19.4 12H21M5.6 5.6l1.1 1.1M17.3 17.3l1.1 1.1M5.6 18.4l1.1-1.1M17.3 6.7l1.1-1.1", circles = listOf(floatArrayOf(12f, 12f, 4f)))
private val moon = Ic("M19 14.5A7.5 7.5 0 0 1 9.5 5a7.5 7.5 0 1 0 9.5 9.5Z")
private val cloud = Ic("M7 18h10.5a3.5 3.5 0 0 0 .3-7 5 5 0 0 0-9.6-1.2A3.8 3.8 0 0 0 7 18Z")
private val smallSun = Ic("M8.5 3v1M3 8.5h1M4.6 4.6l.7.7M12.4 4.6l-.7.7", circles = listOf(floatArrayOf(8.5f, 8.5f, 3f)))
private val lowCloud = Ic("M9 19h8.5a3 3 0 0 0 .3-6 4.3 4.3 0 0 0-8.3-1A3.3 3.3 0 0 0 9 19Z")
private val smallMoon = Ic("M12 8.6A4.6 4.6 0 0 1 6.4 3a4.6 4.6 0 1 0 5.6 5.6Z")
private val rainCloud = Ic("M7 15h10.5a3.5 3.5 0 0 0 .3-7 5 5 0 0 0-9.6-1.2A3.8 3.8 0 0 0 7 15Z")
private val drops = Ic("M9 18l-1 2.5M13 18l-1 2.5M17 18l-1 2.5")
private val flakes = Ic("M9 18.5v2M8 19.5h2M13 18.5v2M12 19.5h2M17 18.5v2M16 19.5h2")

@Composable
fun WxIcon(kind: WxKind, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size)) {
        when (kind) {
            WxKind.Sun -> SvgIcon(sunFull, sunC, size, 1.5f)
            WxKind.Moon -> SvgIcon(moon, paleC, size, 1.5f)
            WxKind.Cloud -> SvgIcon(cloud, paleC, size, 1.5f)
            WxKind.Part -> { SvgIcon(smallSun, sunC, size, 1.5f); SvgIcon(lowCloud, paleC, size, 1.5f) }
            WxKind.NightPart -> { SvgIcon(smallMoon, paleC, size, 1.5f); SvgIcon(lowCloud, paleC, size, 1.5f) }
            WxKind.Rain -> { SvgIcon(rainCloud, paleC, size, 1.5f); SvgIcon(drops, rainC, size, 1.5f) }
            WxKind.Snow -> { SvgIcon(rainCloud, paleC, size, 1.5f); SvgIcon(flakes, paleC, size, 1.4f) }
        }
    }
}
