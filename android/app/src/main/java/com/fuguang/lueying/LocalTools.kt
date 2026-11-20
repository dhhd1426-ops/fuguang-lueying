package com.fuguang.lueying

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.TimeZone
import kotlin.coroutines.resume

/**
 * 本地工具（照 Kelivo features/home/services/local_tools_service.dart、calculator_service.dart）：
 * 时间信息、剪切板、文字转语音、计算器、当前位置、天气。名字和参数照 Kelivo 原样。
 * 天气：Kelivo 用苹果 WeatherKit（只有 iOS 有），这里换成 Open-Meteo（免费、不用 Key）。
 *
 * 也管助理设置「本地工具」那一页的分组：每组一个开关（AsstExt.toolsOff 里的就不带）。
 */
object LocalTools {
    const val TIME = "get_time_info"
    const val CLIPBOARD = "clipboard_tool"
    const val TTS = "text_to_speech"
    const val CALC = "calculate"
    const val LOCATION = "get_current_location"
    const val WEATHER = "get_weather"
    val names = setOf(TIME, CLIPBOARD, TTS, CALC, LOCATION, WEATHER)

    /** 本地工具页的一组：key、图标名、标题、说明、是不是这次新加的。 */
    class Group(val key: String, val icon: String, val title: String, val desc: String, val isNew: Boolean = false)

    val GROUPS = listOf(
        Group("search", "globe", "上网搜索", "需要最新消息时上网查，查到的整理好再回答。"),
        Group("works", "sparkle", "做作品", "在后台做网页、幻灯片、长篇排版，做好变成作品卡。"),
        Group("file", "doc", "发文件", "把内容写成 PDF、Word 发给你。"),
        Group("pic", "pic", "发图片", "把网上或你发来的图片发给你，可存到相册。"),
        Group("clock", "clock", "闹钟和日历", "定闹钟，查、加、改、删日程。"),
        Group("pact", "pact", "约定", "按约定的时间主动来找你，比如早报。"),
        Group("material", "book", "素材", "翻看你存下的素材。"),
        Group("video", "film", "视频解析", "看懂你发来的视频链接。"),
        Group("motion", "film", "动效检查", "做动画作品时逐帧检查。"),
        Group("pan", "cloud", "网盘", "读写网盘里的文件。"),
        Group("web", "hand", "浏览器控制", "在 App 里打开网站替你点、填，登录和付款由你来。"),
        Group("ask", "ask", "询问用户", "允许助手提出简短问题，并在你回答后继续生成。"),
        Group(TIME, "clock", "时间信息", "读取设备日期、星期、时间、时区、UTC 偏移和时间戳。", true),
        Group(CLIPBOARD, "clip", "剪切板", "在明确需要时读取或写入设备剪切板中的纯文本。", true),
        Group(TTS, "speak", "文字转语音", "允许助手使用已配置的语音播放朗读文本。", true),
        Group(CALC, "calc", "计算器", "计算数学表达式，支持加减乘除幂运算 sqrt sin cos 等。", true),
        Group(LOCATION, "pin", "当前位置", "读取本设备的一次性位置，需要授予定位权限。", true),
        Group(WEATHER, "sun", "天气", "获取当前位置或指定地点的天气，数据来自 Open-Meteo。", true),
    )
    /** 新加的 6 样：老助理默认关着，和 Kelivo 一样要你自己打开。 */
    val NEW_DEFAULT_OFF = GROUPS.filter { it.isNew }.map { it.key }

    fun on(x: AsstExt, key: String) = key !in x.toolsOff

    private var app: Context? = null
    fun init(ctx: Context) { app = ctx.applicationContext }

    private fun obj(vararg p: Pair<String, JSONObject>) = JSONObject().apply { p.forEach { (k, v) -> put(k, v) } }
    private fun str(d: String) = JSONObject().put("type", "string").put("description", d)
    private fun num(d: String) = JSONObject().put("type", "number").put("description", d)
    private fun schema(props: JSONObject = JSONObject(), required: List<String> = emptyList()) =
        JSONObject().put("type", "object").put("properties", props).apply { if (required.isNotEmpty()) put("required", JSONArray(required)) }

    private fun tzHint(): String {
        val tz = TimeZone.getDefault()
        val off = tz.getOffset(System.currentTimeMillis()) / 60000
        return "The device timezone is ${tz.id} (UTC${if (off < 0) "-" else "+"}%02d:%02d).".format(kotlin.math.abs(off) / 60, kotlin.math.abs(off) % 60)
    }

    fun specs(x: AsstExt): List<ToolSpec> = buildList {
        if (on(x, TIME)) add(ToolSpec(TIME, "Get the current local date and time info from the device. Returns year, month, day, weekday, ISO date and time strings, timezone, UTC offset, and timestamp.", schema()))
        if (on(x, CLIPBOARD)) add(ToolSpec(CLIPBOARD, "Read or write plain text from the device clipboard. Use action: read or write. For write, provide text. Do NOT write to the clipboard unless the user has explicitly requested it.",
            schema(obj("action" to JSONObject().put("type", "string").put("enum", JSONArray(listOf("read", "write"))).put("description", "Operation to perform: read or write"),
                "text" to str("Text to write to the clipboard. Required for write.")), listOf("action"))))
        if (on(x, TTS)) add(ToolSpec(TTS, "Speak text aloud to the user using the configured text-to-speech playback. Use this when the user asks you to read something aloud, or when audio output is appropriate. The tool returns after playback has been requested; audio may continue in the background. Provide natural, readable text without markdown formatting.",
            schema(obj("text" to str("The text to speak aloud.")), listOf("text"))))
        if (on(x, CALC)) add(ToolSpec(CALC, "Evaluate a mathematical expression. Decimal/integer arithmetic, division, integer powers, factorial, modulo and rounding use exact rational arithmetic. " +
            "Repeating decimal outputs and calculations using pi, e, roots, non-integer powers, or transcendental functions are marked approximate and rounded to 15 significant digits. " +
            "Supports: + - * / ^ % !, sin() cos() tan() (radians), sqrt() ln() abs() floor() ceil() sgn(), log(base, value), constants pi e. Example: \"5!\", \"sin(pi/4)\", \"log(2, 8)\", \"floor(3.7)\"",
            schema(obj("expression" to str("A mathematical expression in standard notation, e.g. \"(15 + 3) * 2\", \"2^10\", \"sqrt(144)\"")), listOf("expression"))))
        if (on(x, LOCATION)) add(ToolSpec(LOCATION, "Get the user's current location from the device (one-shot, When In Use). Returns latitude, longitude, accuracy in meters, timestamp, and optional city/region/country from reverse geocoding. Do not request this unless the user asked for their location or it is needed for weather. Requires the Location permission; if it is not granted, an error is returned.", schema()))
        if (on(x, WEATHER)) add(ToolSpec(WEATHER, "Get current weather, hourly forecast, and daily forecast from Open-Meteo. Omit coordinates to use the current device location; or pass latitude and longitude to query a specific place. " +
            "Returns temperature, apparent temperature, precipitation chance, and forecasts. Always mention that weather data is from Open-Meteo when presenting results. ${tzHint()} Requires Location permission when coordinates are omitted.",
            schema(obj("latitude" to num("Latitude in decimal degrees. Required together with longitude when querying a specific place."),
                "longitude" to num("Longitude in decimal degrees. Required together with latitude when querying a specific place.")))))
    }

    fun liveTitle(n: String) = when (n) { TIME -> "正在看时间"; CLIPBOARD -> "正在用剪切板"; TTS -> "正在朗读"; CALC -> "正在算"; LOCATION -> "正在定位"; else -> "正在查天气" }
    fun doneTitle(n: String) = when (n) { TIME -> "看了时间"; CLIPBOARD -> "用了剪切板"; TTS -> "读了出来"; CALC -> "算好了"; LOCATION -> "看了位置"; else -> "查了天气" }

    suspend fun run(n: String, a: JSONObject): ToolOut {
        val ctx = app ?: return ToolOut("这会儿用不了", failed = true)
        return runCatching {
            when (n) {
                TIME -> ToolOut(timeInfo().toString())
                CLIPBOARD -> clipboard(ctx, a)
                TTS -> tts(ctx, a.optString("text"))
                CALC -> ToolOut(Calc.evaluate(a.optString("expression")).toString())
                LOCATION -> location(ctx)?.let { ToolOut(it.toString()) } ?: ToolOut("{\"error\":\"Location permission not granted or location unavailable\"}", failed = true)
                else -> weather(ctx, a)
            }
        }.getOrElse { ToolOut("{\"error\":${JSONObject.quote(it.message ?: it.javaClass.simpleName)}}", failed = true) }
    }

    private fun p2(n: Int) = n.toString().padStart(2, '0')

    private fun timeInfo(): JSONObject {
        val c = Calendar.getInstance()
        val off = TimeZone.getDefault().getOffset(c.timeInMillis) / 60000
        val wd = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")[c.get(Calendar.DAY_OF_WEEK) - 1]
        val wdIndex = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1 // 周一 = 1 … 周日 = 7（Dart weekday）
        val y = c.get(Calendar.YEAR); val mo = c.get(Calendar.MONTH) + 1; val d = c.get(Calendar.DAY_OF_MONTH)
        val date = "${y.toString().padStart(4, '0')}-${p2(mo)}-${p2(d)}"
        val time = "${p2(c.get(Calendar.HOUR_OF_DAY))}:${p2(c.get(Calendar.MINUTE))}:${p2(c.get(Calendar.SECOND))}"
        val sign = if (off < 0) "-" else "+"
        val utc = "$sign${p2(kotlin.math.abs(off) / 60)}:${p2(kotlin.math.abs(off) % 60)}"
        return JSONObject().put("year", y).put("month", mo).put("day", d).put("weekday", wd).put("weekday_en", wd).put("weekday_index", wdIndex)
            .put("date", date).put("time", time).put("datetime", "${date}T$time.${c.get(Calendar.MILLISECOND).toString().padStart(3, '0')}")
            .put("timezone", TimeZone.getDefault().getDisplayName(TimeZone.getDefault().inDaylightTime(c.time), TimeZone.SHORT))
            .put("utc_offset", utc).put("timestamp_ms", c.timeInMillis)
    }

    private suspend fun clipboard(ctx: Context, a: JSONObject): ToolOut = withContext(Dispatchers.Main) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        when (a.optString("action")) {
            "read" -> {
                val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                ToolOut(JSONObject().put("text", t).toString())
            }
            "write" -> {
                val t = a.optString("text")
                cm.setPrimaryClip(ClipData.newPlainText("text", t))
                ToolOut(JSONObject().put("success", true).put("length", t.length).toString())
            }
            else -> ToolOut("{\"error\":\"action must be read or write\"}", failed = true)
        }
    }

    private var tts: TextToSpeech? = null
    private suspend fun tts(ctx: Context, text: String): ToolOut {
        if (text.isBlank()) return ToolOut("{\"error\":\"text is empty\"}", failed = true)
        // 先用「我的 › 语音」里配好的（和 Kelivo 一样用已配置的语音）；没配就用系统自带的朗读
        if (Speech.ready) { withContext(Dispatchers.Main) { Speech.play("tool-tts", text) }; return ToolOut("{\"success\":true}") }
        val ok = withContext(Dispatchers.Main) {
            tts?.let { true } ?: withTimeoutOrNull(5000) {
                suspendCancellableCoroutine<Boolean> { k -> var t: TextToSpeech? = null; t = TextToSpeech(ctx) { s -> if (s == TextToSpeech.SUCCESS) { tts = t; k.resume(true) } else k.resume(false) } }
            } ?: false
        }
        if (!ok) return ToolOut("{\"error\":\"No text-to-speech engine available\"}", failed = true)
        withContext(Dispatchers.Main) { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tool-tts") }
        return ToolOut("{\"success\":true}")
    }

    private suspend fun location(ctx: Context): JSONObject? {
        if (!Perms.need(ctx, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION) &&
            !Perms.has(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER).filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        var loc: Location? = null
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            for (p in providers) {
                loc = withTimeoutOrNull(10_000) {
                    suspendCancellableCoroutine<Location?> { k ->
                        try { lm.getCurrentLocation(p, null, ctx.mainExecutor) { k.resume(it) } } catch (e: SecurityException) { k.resume(null) }
                    }
                }
                if (loc != null) break
            }
        }
        if (loc == null) loc = providers.mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (e: SecurityException) { null } }.maxByOrNull { it.time }
        val l = loc ?: return null
        val o = JSONObject().put("latitude", l.latitude).put("longitude", l.longitude).put("accuracy_m", l.accuracy.toDouble()).put("timestamp_ms", l.time)
        withContext(Dispatchers.IO) {
            runCatching {
                @Suppress("DEPRECATION")
                Geocoder(ctx).getFromLocation(l.latitude, l.longitude, 1)?.firstOrNull()?.let { ad ->
                    ad.locality?.let { o.put("city", it) }; ad.adminArea?.let { o.put("region", it) }; ad.countryName?.let { o.put("country", it) }
                }
            }
        }
        return o
    }

    private suspend fun weather(ctx: Context, a: JSONObject): ToolOut {
        var lat = a.optDouble("latitude", Double.NaN); var lon = a.optDouble("longitude", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) {
            val l = location(ctx) ?: return ToolOut("{\"error\":\"Location permission not granted or location unavailable; pass latitude and longitude\"}", failed = true)
            lat = l.getDouble("latitude"); lon = l.getDouble("longitude")
        }
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,wind_speed_10m" +
            "&hourly=temperature_2m,precipitation_probability,weather_code&forecast_hours=12" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&forecast_days=7&timezone=auto"
        val body = withContext(Dispatchers.IO) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            try { if (c.responseCode !in 200..299) throw IllegalStateException("Open-Meteo ${c.responseCode}"); c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
        }
        val o = JSONObject(body)
        return ToolOut(JSONObject().put("source", "Open-Meteo").put("latitude", lat).put("longitude", lon)
            .put("timezone", o.optString("timezone")).put("current", o.optJSONObject("current")).put("current_units", o.optJSONObject("current_units"))
            .put("hourly", o.optJSONObject("hourly")).put("daily", o.optJSONObject("daily"))
            .put("weather_code_note", "WMO codes: 0 clear, 1-3 partly cloudy, 45/48 fog, 51-57 drizzle, 61-67 rain, 71-77 snow, 80-82 showers, 95-99 thunderstorm").toString())
    }
}

/**
 * 计算器（照 Kelivo calculator_service.dart 的行为）：加减乘除、整数次幂、阶乘、取模、取整用精确有理数；
 * 用到 pi、e、开方、非整数次幂、三角 / 对数时按双精度算，标 approximate，保留 15 位有效数字。
 */
private object Calc {
    private class V(val n: BigInteger, val d: BigInteger, val approx: Boolean = false, val dbl: Double = 0.0) {
        val value: Double get() = if (approx) dbl else BigDecimal(n).divide(BigDecimal(d), MathContext.DECIMAL64).toDouble()
        companion object {
            fun of(n: BigInteger, d: BigInteger): V {
                require(d.signum() != 0) { "Division by zero" }
                val g = n.gcd(d).let { if (it.signum() == 0) BigInteger.ONE else it }
                val s = if (d.signum() < 0) BigInteger.ONE.negate() else BigInteger.ONE
                return V(n.divide(g).multiply(s), d.divide(g).multiply(s))
            }
            fun approx(x: Double): V { require(!x.isNaN() && !x.isInfinite()) { "Result is not a finite number" }; return V(BigInteger.ZERO, BigInteger.ONE, true, x) }
        }
    }

    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun peek(): Char? { ws(); return s.getOrNull(i) }
        fun eat(c: Char): Boolean { if (peek() == c) { i++; return true }; return false }
    }

    private fun add(a: V, b: V) = if (a.approx || b.approx) V.approx(a.value + b.value) else V.of(a.n * b.d + b.n * a.d, a.d * b.d)
    private fun sub(a: V, b: V) = if (a.approx || b.approx) V.approx(a.value - b.value) else V.of(a.n * b.d - b.n * a.d, a.d * b.d)
    private fun mul(a: V, b: V) = if (a.approx || b.approx) V.approx(a.value * b.value) else V.of(a.n * b.n, a.d * b.d)
    private fun div(a: V, b: V): V { if (!b.approx && b.n.signum() == 0 || b.approx && b.dbl == 0.0) throw IllegalArgumentException("Division by zero"); return if (a.approx || b.approx) V.approx(a.value / b.value) else V.of(a.n * b.d, a.d * b.n) }
    private fun mod(a: V, b: V): V {
        if (a.approx || b.approx) return V.approx(a.value - b.value * kotlin.math.floor(a.value / b.value))
        val q = BigDecimal(a.n * b.d).divide(BigDecimal(a.d * b.n), 0, RoundingMode.FLOOR).toBigInteger()
        return sub(a, mul(b, V.of(q, BigInteger.ONE)))
    }
    private fun isInt(v: V) = !v.approx && v.d == BigInteger.ONE
    private fun pow(a: V, b: V): V {
        if (isInt(b) && b.n.abs() <= BigInteger.valueOf(10000)) {
            val e = b.n.toInt()
            if (a.approx) return V.approx(Math.pow(a.dbl, e.toDouble()))
            return if (e >= 0) V.of(a.n.pow(e), a.d.pow(e)) else V.of(a.d.pow(-e), a.n.pow(-e))
        }
        return V.approx(Math.pow(a.value, b.value))
    }
    private fun fact(a: V): V {
        require(isInt(a) && a.n.signum() >= 0 && a.n <= BigInteger.valueOf(1000)) { "Factorial needs a non-negative integer up to 1000" }
        var r = BigInteger.ONE; var k = 2; val m = a.n.toInt(); while (k <= m) { r *= BigInteger.valueOf(k.toLong()); k++ }
        return V.of(r, BigInteger.ONE)
    }

    private fun expr(p: P): V { var v = term(p); while (true) { v = when { p.eat('+') -> add(v, term(p)); p.eat('-') -> sub(v, term(p)); else -> return v } } }
    private fun term(p: P): V {
        var v = unary(p)
        while (true) {
            v = when {
                p.eat('*') || p.eat('×') -> mul(v, unary(p))
                p.eat('/') || p.eat('÷') -> div(v, unary(p))
                p.eat('%') -> mod(v, unary(p))
                else -> return v
            }
        }
    }
    private fun unary(p: P): V = when { p.eat('-') -> sub(V.of(BigInteger.ZERO, BigInteger.ONE), unary(p)); p.eat('+') -> unary(p); else -> power(p) }
    private fun power(p: P): V { val b = postfix(p); return if (p.eat('^')) pow(b, unary(p)) else b }
    private fun postfix(p: P): V { var v = atom(p); while (p.eat('!')) v = fact(v); return v }
    private fun atom(p: P): V {
        val c = p.peek() ?: throw IllegalArgumentException("Unexpected end of expression")
        if (p.eat('(')) { val v = expr(p); require(p.eat(')')) { "Missing )" }; return v }
        if (c.isDigit() || c == '.') {
            val st = p.i; while (p.i < p.s.length && (p.s[p.i].isDigit() || p.s[p.i] == '.')) p.i++
            if (p.i < p.s.length && (p.s[p.i] == 'e' || p.s[p.i] == 'E') && p.s.getOrNull(p.i + 1)?.let { it.isDigit() || it == '-' || it == '+' } == true) {
                p.i += 2; while (p.i < p.s.length && p.s[p.i].isDigit()) p.i++
            }
            val bd = BigDecimal(p.s.substring(st, p.i))
            return if (bd.scale() > 0) V.of(bd.unscaledValue(), BigInteger.TEN.pow(bd.scale())) else V.of(bd.toBigIntegerExact(), BigInteger.ONE)
        }
        if (c.isLetter() || c == 'π') {
            val st = p.i; while (p.i < p.s.length && (p.s[p.i].isLetter() || p.s[p.i] == 'π')) p.i++
            val name = p.s.substring(st, p.i).lowercase()
            if (name == "pi" || name == "π") return V.approx(Math.PI)
            if (name == "e") return V.approx(Math.E)
            require(p.eat('(')) { "Unknown identifier: $name" }
            val args = mutableListOf(expr(p)); while (p.eat(',')) args += expr(p)
            require(p.eat(')')) { "Missing )" }
            val x = args[0]
            return when (name) {
                "sin" -> V.approx(kotlin.math.sin(x.value)); "cos" -> V.approx(kotlin.math.cos(x.value)); "tan" -> V.approx(kotlin.math.tan(x.value))
                "sqrt" -> V.approx(kotlin.math.sqrt(x.value)); "ln" -> V.approx(kotlin.math.ln(x.value))
                "abs" -> if (x.approx) V.approx(kotlin.math.abs(x.dbl)) else V.of(x.n.abs(), x.d)
                "floor" -> if (x.approx) V.approx(kotlin.math.floor(x.dbl)) else V.of(BigDecimal(x.n).divide(BigDecimal(x.d), 0, RoundingMode.FLOOR).toBigInteger(), BigInteger.ONE)
                "ceil" -> if (x.approx) V.approx(kotlin.math.ceil(x.dbl)) else V.of(BigDecimal(x.n).divide(BigDecimal(x.d), 0, RoundingMode.CEILING).toBigInteger(), BigInteger.ONE)
                "sgn" -> V.of(BigInteger.valueOf(kotlin.math.sign(x.value).toLong()), BigInteger.ONE)
                "log" -> { require(args.size == 2) { "log needs (base, value)" }; V.approx(kotlin.math.ln(args[1].value) / kotlin.math.ln(x.value)) }
                else -> throw IllegalArgumentException("Unknown function: $name")
            }
        }
        throw IllegalArgumentException("Unexpected character '$c' at ${p.i}")
    }

    fun evaluate(expression: String): JSONObject {
        require(expression.length <= 4096 && Regex("[(^]").findAll(expression).count() <= 64) { "Expression exceeds calculator limits." }
        val p = P(expression)
        val v = expr(p)
        require(p.peek() == null) { "Unexpected character '${p.peek()}' at ${p.i}" }
        // 精确：分母只含 2 和 5 时是有限小数
        if (!v.approx) {
            var d = v.d; while (d.mod(BigInteger.valueOf(2)).signum() == 0) d /= BigInteger.valueOf(2); while (d.mod(BigInteger.valueOf(5)).signum() == 0) d /= BigInteger.valueOf(5)
            if (d == BigInteger.ONE) {
                val r = BigDecimal(v.n).divide(BigDecimal(v.d)).stripTrailingZeros()
                return JSONObject().put("result", r.toPlainString()).put("approximate", false)
            }
        }
        val r = BigDecimal(v.value).round(MathContext(15)).stripTrailingZeros()
        return JSONObject().put("result", r.toPlainString()).put("approximate", true)
            .put("note", if (v.approx) "Uses double-precision approximations for functions/constants. Rounded to 15 significant digits; numerical error may accumulate." else "Repeating decimal rounded to 15 significant digits.")
    }
}
