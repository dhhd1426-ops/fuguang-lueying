package com.fuguang.lueying

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume

/**
 * 语音：把她的回答读出来（TTS）。照云朵天气（yunduo-native）的语音模块做：
 * Volink / 硅基流动 / OpenAI / 自定义四家，OpenAI 兼容的 /audio/speech；Volink 走它自己的 /tts/speech，
 * 几种请求写法挨个试，记住能用的那种。长回答按句切段，读这一段时下一段已经在合成。
 * 鲸鱼娘的嘴按读出来的音量开合（先把音频解成 PCM 算每 50ms 的响度），停顿时闭上。
 * 和 Voice.kt（她说话的句群层次）无关。
 */
object Speech {
    class Prov(val id: String, val name: String, val base: String, val models: List<Pair<String, String>>, val note: String)

    val PROVS = listOf(
        Prov("volink", "Volink", "https://api.volink.org/v1", listOf(
            "minimax/speech-02-turbo" to "MiniMax · 中英粤",
            "bytedance/openspeech-tts-v3" to "字节跳动 · 7 种语言",
            "cosyvoice/CosyVoice2-0.5B" to "CosyVoice2 · 中英日韩",
            "sensetime/sensenova-tts-v1" to "商汤 · 中英",
        ), "一站式：字节 Open Speech、MiniMax、CosyVoice、商汤四个模型。音色用你在 Volink 控制台「我的语音」里建的（混合、设计、克隆），点「获取音色」读出来。"),
        Prov("siliconflow", "硅基流动", "https://api.siliconflow.cn/v1", listOf(
            "FunAudioLLM/CosyVoice2-0.5B" to "CosyVoice2 · 8 个预置音色",
        ), "国内直连，CosyVoice2 有 8 个预置音色，也能用自己上传的克隆音色。"),
        Prov("openai", "OpenAI", "https://api.openai.com/v1", listOf(
            "gpt-4o-mini-tts" to "OpenAI · 多语种", "tts-1" to "OpenAI · 多语种", "tts-1-hd" to "OpenAI · 多语种",
        ), "国内网络一般连不上，可以把地址换成中转。"),
        Prov("custom", "自定义", "", emptyList(), "任何兼容 OpenAI /audio/speech 的服务，包括自建的 CosyVoice、GPT-SoVITS 中转。"),
    )

    class Voice(val id: String, val name: String, val desc: String = "")

    class Conf(base: String, model: String) {
        var base by mutableStateOf(base)
        var key by mutableStateOf("")
        var path by mutableStateOf("/audio/speech")
        var model by mutableStateOf(model)
        var voice by mutableStateOf("")
        var voices by mutableStateOf(listOf<Voice>())
        /** 上次合成成功的请求写法；Key、地址、模型一改就清掉重试。 */
        var form = ""
    }

    private val confs = PROVS.associate { it.id to Conf(it.base, it.models.firstOrNull()?.first.orEmpty()) }
    var pid by mutableStateOf("volink")
    var auto by mutableStateOf(false)
    var btn by mutableStateOf(true)
    var lips by mutableStateOf(true)
    var speed by mutableFloatStateOf(1f)
    var sample by mutableStateOf("你好呀，我是 {asst}。今天也要记得照顾好自己。")

    val conf: Conf get() = confs[pid] ?: confs.getValue("volink")
    val prov: Prov get() = PROVS.firstOrNull { it.id == pid } ?: PROVS[0]
    val ready: Boolean get() = conf.key.isNotBlank() && conf.base.isNotBlank() && conf.model.isNotBlank()

    /** 正在出声的那一条：msg:<时间> / v:<音色> / sample / test；空 = 没在读。 */
    var playing by mutableStateOf("")
    /** 已经点了、还在合成（没出声）。 */
    var loading by mutableStateOf("")
    var since by mutableLongStateOf(0L)
    /** 最近一次合成试了哪些写法（出错时给你截图）。 */
    var trace by mutableStateOf(listOf<String>())

    val wave = Ic("M4 10v4M8 7v10M12 4v16M16 8v8M20 11v2")
    val speaker = Ic("M4 9.5h3l4.5-4v13L7 14.5H4zM15.5 9a4 4 0 0 1 0 6M18 6.5a7.5 7.5 0 0 1 0 11")
    val plug = Ic("M9 3v5M15 3v5M6 8h12v3a6 6 0 0 1-12 0zM12 17v4")

    private const val KEY = "vignette-speech-v1"
    private lateinit var app: Context
    private lateinit var prefs: Prefs
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var player: MediaPlayer? = null
    private val cache = LinkedHashMap<String, ByteArray>()

    fun init(ctx: Context, p: Prefs) {
        app = ctx.applicationContext; prefs = p
        runCatching {
            val o = JSONObject(p.string(KEY) ?: return)
            pid = o.optString("pid", pid).takeIf { id -> PROVS.any { it.id == id } } ?: "volink"
            auto = o.optBoolean("auto", auto); btn = o.optBoolean("btn", btn); lips = o.optBoolean("lips", lips)
            speed = o.optDouble("speed", 1.0).toFloat().coerceIn(.5f, 2f)
            sample = o.optString("sample", sample)
            val cs = o.optJSONObject("conf") ?: JSONObject()
            confs.forEach { (id, c) ->
                val j = cs.optJSONObject(id) ?: return@forEach
                c.base = j.optString("base", c.base); c.key = j.optString("key"); c.path = j.optString("path", c.path)
                c.model = j.optString("model", c.model); c.voice = j.optString("voice"); c.form = j.optString("form")
                val vs = j.optJSONArray("voices") ?: JSONArray()
                c.voices = (0 until vs.length()).mapNotNull { i -> vs.optJSONObject(i)?.let { Voice(it.optString("id"), it.optString("name"), it.optString("desc")) } }.filter { it.id.isNotEmpty() }
            }
        }
    }

    fun save() {
        val cs = JSONObject()
        confs.forEach { (id, c) ->
            cs.put(id, JSONObject().put("base", c.base).put("key", c.key).put("path", c.path).put("model", c.model)
                .put("voice", c.voice).put("form", c.form)
                .put("voices", JSONArray().apply { c.voices.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("desc", it.desc)) } }))
        }
        prefs.putString(KEY, JSONObject().put("pid", pid).put("auto", auto).put("btn", btn).put("lips", lips)
            .put("speed", speed.toDouble()).put("sample", sample).put("conf", cs).toString())
    }

    /* ---------- 音色 ---------- */

    /** 常见模型的预置音色（平台能读出来时以平台为准）。 */
    private fun presets(c: Conf): List<Voice> = when {
        c.base.contains("volink") -> emptyList()   // Volink 的音色都在账号里
        c.model.contains("CosyVoice2", true) -> listOf(
            "anna" to "沉稳女声", "bella" to "激情女声", "claire" to "温柔女声", "diana" to "欢快女声",
            "alex" to "沉稳男声", "benjamin" to "低沉男声", "charles" to "磁性男声", "david" to "欢快男声",
        ).map { Voice("${c.model}:${it.first}", it.first, it.second) }
        Regex("gpt-4o.*tts|^tts-1", RegexOption.IGNORE_CASE).containsMatchIn(c.model) -> listOf(
            "coral" to "女声", "nova" to "女声", "shimmer" to "女声", "alloy" to "中性", "echo" to "男声", "onyx" to "男声",
        ).map { Voice(it.first, it.first, it.second) }
        else -> emptyList()
    }

    fun voices(c: Conf = conf): List<Voice> = (c.voices + presets(c)).distinctBy { it.id }
    fun voiceName(c: Conf = conf): String = voices(c).firstOrNull { it.id == c.voice }?.name ?: c.voice.ifEmpty { "默认音色" }
    private fun defaultVoice(c: Conf): String {
        val v = presets(c)
        return (v.firstOrNull { it.desc.contains("温柔女声") } ?: v.firstOrNull { it.desc.contains("女") } ?: v.firstOrNull())?.id.orEmpty()
    }

    /* ---------- 请求 ---------- */

    class Err(val status: Int, msg: String) : Exception(msg)

    private fun join(base: String, path: String) = base.trim().trimEnd('/') + "/" + path.trim().trimStart('/')

    /** 几种写法：native-oa / native 是 Volink 原生 /tts/speech，openai 是 /audio/speech。 */
    private val VOLINK_FORMS = listOf("native-oa", "openai", "native", "native-full")

    private fun body(form: String, c: Conf, text: String, v: String): JSONObject {
        val sp = Math.round(speed * 10) / 10.0
        if (form == "native" || form == "native-full") {
            val vs = JSONObject()
            if (v.isNotEmpty()) vs.put("voice_id", v)
            if (sp != 1.0) vs.put("speed", sp)
            val b = JSONObject().put("model", c.model).put("text", text).put("voice_setting", vs)
            if (form == "native-full") b.put("language", "zh-CN").put("stream", false).put("audio_setting", JSONObject().put("format", "mp3").put("sample_rate", 24000))
            return b
        }
        val o = JSONObject().put("model", c.model).put("input", text)
        if (form == "openai") o.put("response_format", "mp3")
        if (sp != 1.0) o.put("speed", sp)
        if (v.isNotEmpty()) o.put("voice", v)
        return o
    }

    private fun pathOf(form: String, c: Conf) =
        if (form != "openai" && c.base.contains("volink")) "/tts/speech" else c.path.ifBlank { "/audio/speech" }

    private fun errText(code: Int, raw: String): String {
        val j = runCatching { JSONObject(raw) }.getOrNull()
        val detail = j?.let {
            it.optJSONObject("error")?.optString("message")?.takeIf { s -> s.isNotBlank() }
                ?: it.optString("error").takeIf { s -> s.isNotBlank() && !s.startsWith("{") }
                ?: it.optString("message").takeIf { s -> s.isNotBlank() }
                ?: it.optJSONObject("base_resp")?.optString("status_msg")
        } ?: raw.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
        val head = when (code) {
            401 -> "Key 不对或过期了"
            402 -> "余额不足"
            403 -> "没有权限"
            404 -> "地址或接口路径不对"
            429 -> "请求太多了，稍后再试"
            in 500..599 -> "服务那边出错了（$code）"
            else -> "出错了（$code）"
        }
        return if (detail.isNullOrBlank()) head else "$head：${detail.take(80)}"
    }

    private fun open(url: String, key: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 15000; readTimeout = 30000
            setRequestProperty("Authorization", "Bearer $key")
            if (method == "POST") { doOutput = true; setRequestProperty("Content-Type", "application/json") }
        }

    private fun <T> net(block: () -> T): T = try { block() }
        catch (e: Err) { throw e }
        catch (e: java.net.SocketTimeoutException) { throw Err(0, "太久没有响应，稍后再试") }
        catch (e: java.io.IOException) { throw Err(0, "连不上：${e.message ?: "网络错误"}") }

    private fun post(url: String, key: String, body: JSONObject): ByteArray = net {
        val cn = open(url, key, "POST")
        try {
            cn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = cn.responseCode
            val type = cn.contentType.orEmpty().lowercase()
            val bytes = (if (code in 200..299) cn.inputStream else cn.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
            if (code !in 200..299) throw Err(code, errText(code, String(bytes)))
            if (bytes.size > 200 && !type.contains("json") && !type.contains("text")) bytes else audioOf(String(bytes))
        } finally { cn.disconnect() }
    }

    private fun get(url: String, key: String): Pair<Int, ByteArray> = net {
        val cn = open(url, key, "GET")
        try {
            val code = cn.responseCode
            code to ((if (code in 200..299) cn.inputStream else cn.errorStream)?.use { it.readBytes() } ?: ByteArray(0))
        } finally { cn.disconnect() }
    }

    /** 有的服务回 JSON：音频是 base64 / 十六进制，或者给一个链接。 */
    private fun audioOf(raw: String): ByteArray {
        val j = runCatching { JSONObject(raw) }.getOrNull() ?: throw Err(422, "接口没有返回音频：${raw.take(80)}")
        var d: Any? = j.opt("data") ?: j.opt("result") ?: j.opt("output") ?: j
        if (d is JSONObject && d.opt("data") is JSONObject) d = d.opt("data")
        var a: Any? = (d as? JSONObject)?.let { it.opt("audio") ?: it.opt("b64_json") ?: it.opt("audio_base64") ?: it.opt("audio_data") } ?: (d as? String)
        if (a is JSONObject) a = a.opt("data") ?: a.opt("url")
        val url = (d as? JSONObject)?.let { it.optString("url").ifEmpty { it.optString("audio_url") }.ifEmpty { it.optString("audio_file") } }
            ?.takeIf { it.startsWith("http") } ?: (a as? String)?.takeIf { it.startsWith("http") }
        if (url != null) {
            val (code, b) = get(url, "")
            if (code in 200..299 && b.size > 200) return b
            throw Err(code, "音频链接下载失败")
        }
        val s = (a as? String)?.trim()
        if (s != null && s.length > 100) {
            return runCatching {
                if (s.length % 2 == 0 && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
                else Base64.decode(s.substringAfter("base64,"), Base64.DEFAULT)
            }.getOrElse { throw Err(422, "返回的音频格式看不懂") }
        }
        val em = j.optJSONObject("base_resp")?.optString("status_msg").orEmpty()
        throw Err(422, if (em.isNotBlank()) "合成失败（$em）" else errText(400, raw))
    }

    private fun fatal(e: Err) = e.status == 0 || e.status == 401 || e.status == 402 || e.status == 403 || e.status == 429

    /** 合成一段话。先用记住的写法；不行就挨个换（Key、余额、限流、断网直接报）。 */
    suspend fun synth(text: String, voice: String = conf.voice, c: Conf = conf): ByteArray = withContext(Dispatchers.IO) {
        if (c.key.isBlank()) throw Err(-1, "还没填 API Key")
        if (c.model.isBlank()) throw Err(-1, "还没选模型")
        val v = voice.ifBlank { defaultVoice(c) }
        val ck = "${c.base}|${c.model}|$v|$speed|$text"
        synchronized(cache) { cache[ck] }?.let { return@withContext it }
        val all = if (c.base.contains("volink")) VOLINK_FORMS else listOf("openai")
        val forms = if (c.form in all) listOf(c.form) + (all - c.form) else all
        val lines = mutableListOf<String>()
        var first: Err? = null
        for (f in forms) {
            val t0 = System.currentTimeMillis()
            fun took() = "%.1f 秒".format((System.currentTimeMillis() - t0) / 1000f)
            try {
                val b = post(join(c.base, pathOf(f, c)), c.key, body(f, c, text, v))
                lines += "$f → 成功（${took()}）"; trace = lines.toList()
                if (c.form != f) { c.form = f; withContext(Dispatchers.Main) { save() } }
                synchronized(cache) { cache[ck] = b; while (cache.size > 24) cache.remove(cache.keys.first()) }
                return@withContext b
            } catch (e: Err) {
                lines += "$f → ${if (e.status > 0) e.status.toString() else "网络"} ${e.message.orEmpty().take(70)}（${took()}）"
                if (first == null || (first.status >= 500 && e.status in 1..499)) first = e
                if (fatal(e)) break
            }
        }
        trace = lines.toList()
        throw first ?: Err(0, "合成失败")
    }

    /** 从平台读音色。返回（成功？，说明）。 */
    suspend fun fetchVoices(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val c = conf
        if (c.key.isBlank()) return@withContext false to "还没填 API Key"
        val b = c.base.trim().trimEnd('/')
        val mq = URLEncoder.encode(c.model, "UTF-8")
        for (u in listOf("$b/tts/voices?model=$mq", "$b/tts/voices", "$b/audio/voices?model=$mq", "$b/voices?model=$mq", "$b/audio/voice/list")) {
            val (code, raw) = try { get(u, c.key) } catch (e: Err) { if (e.status == 0) return@withContext false to (e.message ?: "连不上") else continue }
            val txt = String(raw)
            if (code == 401 || code == 403) return@withContext false to errText(code, txt)
            if (code !in 200..299) continue
            val all = parseVoices(txt)
            val mine = all.filter { it.second.isEmpty() || it.second == c.model }.map { it.first }
            val got = mine.ifEmpty { all.map { it.first } }
            if (got.isEmpty()) continue
            withContext(Dispatchers.Main) { c.voices = got; save() }
            return@withContext true to "从平台读到 ${got.size} 个音色" + if (mine.isNotEmpty() && mine.size < all.size) "（只显示 ${c.model} 能用的）" else ""
        }
        false to if (c.base.contains("volink")) "账号里还没有这个模型能用的音色，先到 Volink 控制台「我的语音」里建一个（混合、设计或克隆）。"
            else "平台没有提供音色列表，可以在下面直接填音色 ID。"
    }

    private val ID_FIELDS = listOf("voice_setting.voice_id", "voice_id", "voiceId", "voice_type", "speaker_id", "speaker", "provider_voice_id", "id", "voice", "code")

    private fun pick(o: JSONObject, path: String): String {
        var x: Any? = o
        for (k in path.split('.')) x = (x as? JSONObject)?.opt(k)
        return when (x) { is String -> x; is Number -> x.toString(); else -> "" }
    }

    /** （音色，它属于哪个模型） */
    private fun parseVoices(txt: String): List<Pair<Voice, String>> {
        val root: Any = runCatching { JSONArray(txt) }.getOrNull() ?: runCatching { JSONObject(txt) }.getOrNull() ?: return emptyList()
        var arr: Any? = if (root is JSONArray) root else (root as JSONObject).let { it.opt("voices") ?: it.opt("data") ?: it.opt("result") ?: it.opt("items") }
        if (arr is JSONObject) arr = arr.opt("voices") ?: arr.opt("list") ?: arr.opt("items")
        val a = arr as? JSONArray ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            when (val v = a.opt(i)) {
                is String -> Voice(v, v) to ""
                is JSONObject -> {
                    val id = ID_FIELDS.firstNotNullOfOrNull { f -> pick(v, f).takeIf { it.isNotEmpty() } } ?: return@mapNotNull null
                    val model = v.optString("model").ifEmpty { v.optString("model_id") }.ifEmpty { pick(v, "voice_setting.model") }
                    var name = listOf("name", "display_name", "customName", "title", "voice_name").firstNotNullOfOrNull { k -> v.optString(k).takeIf { it.isNotBlank() } } ?: id
                    var tag = ""
                    Regex("^(.+?)\\s*[（(]([^）)]{1,8})[）)]$").find(name)?.let { name = it.groupValues[1]; tag = it.groupValues[2] }
                    val kind = mapOf("mix" to "混合", "design" to "设计", "clone" to "克隆")[v.optString("type").ifEmpty { v.optString("kind") }.ifEmpty { v.optString("source") }].orEmpty()
                    val gender = when (v.optString("gender")) { "female" -> "女声"; "male" -> "男声"; else -> "" }
                    Voice(id, name, listOf(tag, kind, gender).filter { it.isNotEmpty() }.joinToString(" · ")) to model
                }
                else -> null
            }
        }
    }

    /* ---------- 朗读 ---------- */

    /** 读出来的字：去掉工具标记、代码、链接、Markdown 符号。 */
    fun clean(text: String): String = ToolText.clean(text)
        .replace(Regex("```[\\s\\S]*?```"), "（代码略）")
        .replace(Regex("!\\[[^\\]]*\\]\\([^)]*\\)"), "")
        .replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
        .replace(Regex("https?://\\S+"), "")
        .replace(Regex("[*_#>`|~]+"), "")
        .replace(Regex("[ \\t]+"), " ")
        .trim()

    /** 按句切段：一段不超过 220 字，句子太长再硬切。 */
    private fun chunks(text: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        Regex("[^。！？!?；;\\n]+[。！？!?；;\\n]*").findAll(text).map { it.value.trim() }.filter { it.isNotEmpty() }.forEach { s ->
            if (sb.isNotEmpty() && sb.length + s.length > 220) { out += sb.toString(); sb.clear() }
            if (s.length > 220) s.chunked(220).forEach { out += it } else sb.append(s)
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    fun toggle(key: String, text: String, voice: String = conf.voice, onResult: (String?) -> Unit = {}) {
        if (playing == key || loading == key) stop() else play(key, text, voice, onResult)
    }

    /**
     * 读一段话。[onResult] 只叫一次：开始出声时给 null，出错时给原因。
     * 读这一段时下一段已经在合成，段与段之间不停顿。
     */
    fun play(key: String, text: String, voice: String = conf.voice, onResult: (String?) -> Unit = {}) {
        stop()
        val parts = chunks(clean(text))
        if (parts.isEmpty()) return
        loading = key
        job = scope.launch {
            var told = false
            try {
                var next: Deferred<ByteArray>? = async { synth(parts[0], voice) }
                for (i in parts.indices) {
                    val bytes = next!!.await()
                    next = if (i + 1 < parts.size) async { synth(parts[i + 1], voice) } else null
                    if (playing != key) { playing = key; loading = ""; since = System.currentTimeMillis() }
                    if (!told) { told = true; onResult(null) }
                    playBytes(bytes, i)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!told) { told = true; onResult(e.message ?: "合成失败") }
            } finally {
                if (playing == key) playing = ""
                if (loading == key) loading = ""
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        player?.let { runCatching { it.stop() } }
        playing = ""; loading = ""
    }

    /** 新一轮开始时停掉还在读的回答；设置页的试听不管。 */
    fun stopMessages() { if (playing.startsWith("msg:") || loading.startsWith("msg:")) stop() }

    private suspend fun playBytes(b: ByteArray, i: Int) {
        val f = File(app.cacheDir, "tts_${i % 4}.audio")
        withContext(Dispatchers.IO) { f.writeBytes(b) }
        val env = if (lips && Whale.active) withContext(Dispatchers.Default) { envelope(f) } else null
        val mp = MediaPlayer()
        player = mp
        var mouth: Job? = null
        try {
            withContext(Dispatchers.IO) { mp.setDataSource(f.absolutePath); mp.prepare() }
            suspendCancellableCoroutine<Unit> { cont ->
                mp.setOnCompletionListener { if (cont.isActive) cont.resume(Unit) }
                mp.setOnErrorListener { _, _, _ -> if (cont.isActive) cont.resume(Unit); true }
                mp.start()
                // 嘴：每 120ms 看一眼这一刻的响度，有声音就张一下；停顿时不叫，whale.talk 自己会合上
                if (lips && Whale.active) mouth = scope.launch {
                    while (isActive) {
                        val ms = runCatching { mp.currentPosition }.getOrDefault(-1)
                        if (ms < 0) break
                        if (env == null || env.at(ms) > .18f) Whale.talk(1)
                        delay(120)
                    }
                }
            }
        } finally {
            mouth?.cancel()
            runCatching { mp.stop() }
            mp.release()
            if (player === mp) player = null
        }
    }

    /** 每 50ms 一格的响度（0..1）。解不出来就 null，嘴按「在放就动」处理。 */
    private class Env(val v: FloatArray) { fun at(ms: Int) = v.getOrElse(ms / 50) { 0f } }

    private fun envelope(f: File): Env? = runCatching {
        val ex = MediaExtractor()
        ex.setDataSource(f.absolutePath)
        val ti = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        ex.selectTrack(ti)
        val fmt = ex.getTrackFormat(ti)
        val win = (fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) * fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) / 20).coerceAtLeast(1)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        val out = ArrayList<Float>()
        try {
            codec.configure(fmt, null, null, 0); codec.start()
            val info = MediaCodec.BufferInfo()
            var acc = 0.0; var n = 0; var inDone = false; var outDone = false; var guard = 0
            while (!outDone && guard++ < 100_000) {
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(5000)
                    if (ii >= 0) {
                        val sz = ex.readSampleData(codec.getInputBuffer(ii)!!, 0)
                        if (sz < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { codec.queueInputBuffer(ii, 0, sz, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 5000)
                if (oi >= 0) {
                    val buf = codec.getOutputBuffer(oi)!!
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    val sb = buf.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    while (sb.hasRemaining()) {
                        val s = sb.get() / 32768.0
                        acc += s * s
                        if (++n >= win) { out += Math.sqrt(acc / n).toFloat(); acc = 0.0; n = 0 }
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
        } finally { runCatching { codec.stop() }; codec.release(); ex.release() }
        val mx = out.maxOrNull() ?: 0f
        if (mx <= 0f) null else Env(FloatArray(out.size) { out[it] / mx })
    }.getOrNull()
}
