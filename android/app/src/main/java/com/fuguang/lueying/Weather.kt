package com.fuguang.lueying

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime

/** 一小时：几点、天气码、是不是白天、气温。 */
data class WxHour(val hour: Int, val code: Int, val day: Boolean, val temp: Int)

/** 一天：天气码、最低最高、降水概率、风、紫外线、日出日落、湿度、空气，和 7–21 点每两小时的气温。 */
data class WxDay(
    val date: LocalDate, val code: Int, val lo: Int, val hi: Int, val rain: Int,
    val windKmh: Double, val windDir: Int, val uv: Double, val sunrise: String, val sunset: String,
    val hum: Int, val aqi: Int?, val hours: List<WxHour>,
)

data class WxNow(val temp: Int, val feels: Int, val code: Int, val day: Boolean, val hum: Int, val aqi: Int?)

data class Forecast(val at: Long, val now: WxNow, val next: List<WxHour>, val days: List<WxDay>)

data class WxPlace(val name: String, val lat: Double, val lon: Double, val area: String = "")

/**
 * 天气：Open-Meteo（不要 key）。地点默认丰台区，卡片里可以改城市（存在 prefs）。
 * 上次取到的原文存着，打开 App 先显示它，再在后台刷新；半小时内不重复取。
 */
object Weather {
    private lateinit var prefs: Prefs
    var place by mutableStateOf(WxPlace("丰台区", 39.858, 116.287))
        private set
    var data by mutableStateOf<Forecast?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set

    fun init(p: Prefs) {
        prefs = p
        p.string("wx-place")?.split("|")?.takeIf { it.size >= 3 }?.let { s ->
            runCatching { place = WxPlace(s[0], s[1].toDouble(), s[2].toDouble(), s.getOrElse(3) { "" }) }
        }
        val raw = p.string("wx-raw")
        if (raw != null) runCatching { data = parse(raw, p.string("wx-air"), p.string("wx-at")?.toLongOrNull() ?: 0L) }
    }

    suspend fun refresh(force: Boolean = false) {
        val d = data
        if (loading || (!force && d != null && System.currentTimeMillis() - d.at < 30 * 60_000)) return
        loading = true
        try {
            val q = "latitude=${place.lat}&longitude=${place.lon}&timezone=auto"
            val raw = get(
                "https://api.open-meteo.com/v1/forecast?$q&forecast_days=7" +
                    "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,is_day" +
                    "&hourly=temperature_2m,weather_code,is_day" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,wind_speed_10m_max," +
                    "wind_direction_10m_dominant,uv_index_max,sunrise,sunset,relative_humidity_2m_mean",
            )
            val air = runCatching { get("https://air-quality-api.open-meteo.com/v1/air-quality?$q&forecast_days=7&current=us_aqi&hourly=us_aqi") }.getOrNull()
            val now = System.currentTimeMillis()
            data = parse(raw, air, now)
            failed = false
            prefs.putString("wx-raw", raw); prefs.putString("wx-air", air); prefs.putString("wx-at", now.toString())
        } catch (_: Exception) {
            failed = true
        } finally {
            loading = false
        }
    }

    /** 按名字找城市（中文名也行）。 */
    suspend fun search(name: String): List<WxPlace> = runCatching {
        val o = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=6&language=zh&name=" + URLEncoder.encode(name.trim(), "UTF-8")))
        val a = o.optJSONArray("results") ?: return@runCatching emptyList()
        (0 until a.length()).map { i ->
            val r = a.getJSONObject(i)
            WxPlace(r.getString("name"), r.getDouble("latitude"), r.getDouble("longitude"), listOf(r.optString("admin1"), r.optString("country")).filter { it.isNotBlank() }.joinToString(" · "))
        }
    }.getOrDefault(emptyList())

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main)

    /** 换地方：马上记下，刷新放在自己的协程里，卡片收起也不会被打断。 */
    fun use(p: WxPlace) {
        place = p
        prefs.putString("wx-place", "${p.name}|${p.lat}|${p.lon}|${p.area}")
        scope.launch { refresh(force = true) }
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000; c.readTimeout = 15_000
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun parse(raw: String, air: String?, at: Long): Forecast {
        val o = JSONObject(raw)
        val cur = o.getJSONObject("current")
        val h = o.getJSONObject("hourly")
        val ht = h.getJSONArray("time"); val htemp = h.getJSONArray("temperature_2m"); val hcode = h.getJSONArray("weather_code"); val hday = h.getJSONArray("is_day")
        val hours = (0 until ht.length()).map { i ->
            LocalDateTime.parse(ht.getString(i)) to WxHour(LocalDateTime.parse(ht.getString(i)).hour, hcode.optInt(i), hday.optInt(i, 1) == 1, Math.round(htemp.optDouble(i)).toInt())
        }
        // 空气：按小时的 us_aqi，一天取最大
        val aqiAt = HashMap<LocalDateTime, Int>()
        var aqiNow: Int? = null
        if (air != null) runCatching {
            val a = JSONObject(air)
            aqiNow = a.optJSONObject("current")?.let { if (it.isNull("us_aqi")) null else it.optInt("us_aqi") }
            val ah = a.getJSONObject("hourly"); val t = ah.getJSONArray("time"); val v = ah.getJSONArray("us_aqi")
            for (i in 0 until t.length()) if (!v.isNull(i)) aqiAt[LocalDateTime.parse(t.getString(i))] = v.getInt(i)
        }
        val curTime = LocalDateTime.parse(cur.getString("time"))
        val start = hours.indexOfFirst { it.first.hour == curTime.hour && it.first.toLocalDate() == curTime.toLocalDate() }.coerceAtLeast(0)
        val d = o.getJSONObject("daily")
        val dt = d.getJSONArray("time")
        val days = (0 until dt.length()).map { i ->
            val date = LocalDate.parse(dt.getString(i))
            val mine = hours.filter { it.first.toLocalDate() == date }
            WxDay(
                date = date,
                code = d.getJSONArray("weather_code").optInt(i),
                lo = Math.round(d.getJSONArray("temperature_2m_min").optDouble(i)).toInt(),
                hi = Math.round(d.getJSONArray("temperature_2m_max").optDouble(i)).toInt(),
                rain = d.optJSONArray("precipitation_probability_max")?.optInt(i) ?: 0,
                windKmh = d.getJSONArray("wind_speed_10m_max").optDouble(i),
                windDir = d.getJSONArray("wind_direction_10m_dominant").optInt(i),
                uv = d.optJSONArray("uv_index_max")?.optDouble(i) ?: 0.0,
                sunrise = d.getJSONArray("sunrise").optString(i).takeLast(5),
                sunset = d.getJSONArray("sunset").optString(i).takeLast(5),
                hum = d.optJSONArray("relative_humidity_2m_mean")?.optInt(i) ?: 0,
                aqi = aqiAt.filterKeys { it.toLocalDate() == date }.values.maxOrNull(),
                hours = mine.filter { it.first.hour in 7..21 && it.first.hour % 2 == 1 }.map { it.second },
            )
        }
        return Forecast(
            at = at,
            now = WxNow(
                Math.round(cur.getDouble("temperature_2m")).toInt(), Math.round(cur.getDouble("apparent_temperature")).toInt(),
                cur.optInt("weather_code"), cur.optInt("is_day", 1) == 1, cur.optInt("relative_humidity_2m"), aqiNow,
            ),
            next = hours.drop(start).take(13).map { it.second },
            days = days,
        )
    }
}
