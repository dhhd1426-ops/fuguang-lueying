package com.fuguang.lueying

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 语音对话（原型 HqPFuGXT73T5RuKRexxyLd 第 6 版）：输入框空着点杏色球进来。
 * 录音 16k 单声道（通话音源 + 回声消除）→ Silero VAD 判断人声 → 本地流式识别（sherpa-onnx，边说边出字幕，停 0.8 秒断句）；
 * 选了 SenseVoice / Whisper 时整句再送云端（失败退回本地那句）；手机自带识别走 SpeechRecognizer（不能插话，坏了自动换本地）。
 * 她说话时你开口（人声 + 够响 + 连着 0.25 秒）就停朗读、停生成。
 * 回复照常进对话，朗读走 Speech.begin（SpeechHooks 在语音模式下强制边写边读）。
 */
object Talk {
    const val RULE = "\n\n【语音对话】对方正在和你语音聊天，你说的话会被直接读出来：用口语、短句，一次两三句；不要列表、标题、链接、代码、表情符号和括号里的动作描写。"
    val SRCS = listOf("local" to "本地识别", "sense" to "SenseVoice", "whisper" to "Whisper", "system" to "手机识别")

    var want by mutableStateOf(false)          // 点了球，等权限
    var open by mutableStateOf(false)          // 语音页开着
    var shown by mutableFloatStateOf(0f)       // 进场进度 0..1（铺开动画）
    var from by mutableStateOf(Offset.Unspecified)
    var muted by mutableStateOf(false)
    var hearing by mutableStateOf(false)       // 正在说（断句中）
    var transcribing by mutableStateOf(false)
    var level by mutableFloatStateOf(0f)       // 麦克风音量 0..1
    var note by mutableStateOf("")
    var src by mutableStateOf("local")
    /** 你这句话的实时字幕（边说边出）；发出去后清空 */
    var partial by mutableStateOf("")
    /** 刚发出去的那句和发出时刻：字幕上浮淡出用 */
    var sent by mutableStateOf("")
    var sentAt by mutableLongStateOf(0L)
    /** 麦克风频谱 45 段 0..1（录音线程写，画面读） */
    val bands = FloatArray(45)

    var onText: ((String) -> Unit)? = null
    var onBarge: (() -> Unit)? = null

    private lateinit var app: Context
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile private var running = false
    private var thread: Thread? = null
    private var rec: SpeechRecognizer? = null
    private var recBusy = false
    private var sysFails = 0

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        // v2：默认本地识别（以前存的「手机识别」在很多国产机上不能用，一律换过来，你想用再切回去）
        src = app.getSharedPreferences("voice", Context.MODE_PRIVATE).getString("src2", "local") ?: "local"
    }

    fun cycleSrc() {
        val i = SRCS.indexOfFirst { it.first == src }
        useSrc(SRCS[(i + 1) % SRCS.size].first)
        say("识别改用 ${label()}")
    }

    private fun useSrc(s: String) {
        src = s
        app.getSharedPreferences("voice", Context.MODE_PRIVATE).edit().putString("src2", src).apply()
        if (open) { stopMic(); startMic() }
    }

    fun label() = SRCS.firstOrNull { it.first == src }?.second ?: "本地识别"
    /** 识别来源旁边的小字：本地 = 离线，云端 = 云 */
    fun tag() = when (src) { "local" -> "离线"; "sense", "whisper" -> "云"; else -> "" }

    fun start() {
        open = true; muted = false; note = ""; partial = ""; sent = ""
        if (!Speech.ready) say("朗读还没配 Key，先用手机自带的声音")
        startMic()
    }

    fun close() {
        open = false; want = false
        stopMic()
        Speech.stopMessages()
        partial = ""
    }

    fun toggleMute() {
        muted = !muted
        partial = ""
        if (muted) { hearing = false; stopRecognizer() } else if (src == "system") listenSystem()
    }

    fun say(t: String) { note = t; main.launch { delay(2800); if (note == t) note = "" } }

    /** 她在说 / 在等她：给系统识别用，她说完再开始听 */
    fun busy(chatBusy: Boolean) = chatBusy || Speech.playing.isNotEmpty() || Speech.loading.isNotEmpty() || transcribing

    private fun startMic() {
        if (src == "system") {
            if (SpeechRecognizer.isRecognitionAvailable(app)) { sysFails = 0; listenSystem(); return }
            say("这台手机没有语音识别服务，已改用本地识别"); src = "local"
        }
        if (src != "local" && cloud() == null) say("${label()} 的 Key 没填（我的 › 语音），先用本地识别")
        startRecorder()
        ensureModel()
    }

    /** 本地模型没下就下（云端识别时也下：字幕和兜底都靠它） */
    private fun ensureModel() {
        if (Ear.ready(app) || Ear.progress >= 0f) return
        main.launch {
            try { Ear.fetch(app); if (open) say("识别装好了，不联网也能听") }
            catch (e: Exception) { if (open) say(e.message ?: "识别模型下载失败") }
        }
    }

    private fun stopMic() {
        running = false; thread = null
        stopRecognizer()
        hearing = false; level = 0f; bands.fill(0f)
    }

    /** 发出一句：字幕上浮淡出，交给对话 */
    private fun deliver(text: String) {
        partial = ""; sent = text; sentAt = System.currentTimeMillis()
        onText?.invoke(text)
    }

    /* ---------------- 手机自带识别 ---------------- */

    /** 系统识别：空闲（不在说、不在等她）时开一轮；结束或出错后由 [tick] 再开 */
    fun tick(chatBusy: Boolean) {
        if (!open || muted || src != "system" || recBusy || busy(chatBusy)) return
        listenSystem()
    }

    private fun stopRecognizer() { rec?.let { runCatching { it.cancel(); it.destroy() } }; rec = null; recBusy = false }

    private fun listenSystem() {
        if (recBusy || muted) return
        val r = rec ?: runCatching { SpeechRecognizer.createSpeechRecognizer(app) }.getOrNull()?.also { rec = it }
        if (r == null) { sysBroken("手机识别打不开"); return }
        recBusy = true
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { sysFails = 0 }
            override fun onBeginningOfSpeech() { hearing = true }
            override fun onRmsChanged(db: Float) { level = ((db + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() { hearing = false }
            override fun onError(e: Int) {
                recBusy = false; hearing = false; level = 0f
                when (e) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {}   // 没说话，正常，下一轮接着听
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> say("没有麦克风权限")
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> sysBroken("手机识别要联网")
                    else -> sysBroken("手机识别出错（$e）")
                }
            }
            override fun onResults(b: Bundle?) {
                recBusy = false; hearing = false; level = 0f
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                if (t.isNotEmpty()) deliver(t) else { partial = "" }
            }
            override fun onPartialResults(b: Bundle?) {
                b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { partial = it }
            }
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
        runCatching { r.startListening(i) }.onFailure { recBusy = false; sysBroken("手机识别打不开") }
    }

    /** 手机识别连着坏两次：说清楚，换本地识别（这一次不改你存的选择以外的东西） */
    private fun sysBroken(why: String) {
        if (++sysFails < 2) return
        stopRecognizer()
        say("$why，已改用本地识别")
        main.launch { useSrc("local") }
    }

    /* ---------------- 自己录音：Silero VAD + 本地流式识别（+ 可选云端识别） ---------------- */

    private const val RATE = 16000
    private const val FRAME = 512                 // 32ms，正好一窗 Silero
    private val FILLER = Regex("^[嗯啊哦呃额唔哈]$")

    /** 选了云端且有 Key：(地址, Key, 模型)；否则 null（走本地） */
    private fun cloud(): Triple<String, String, String>? {
        val sense = Triple(Speech.baseOf("siliconflow").ifBlank { "https://api.siliconflow.cn/v1" }, Speech.keyOf("siliconflow"), "FunAudioLLM/SenseVoiceSmall")
        val whisper = Triple(Speech.baseOf("openai").ifBlank { "https://api.openai.com/v1" }, Speech.keyOf("openai"), "whisper-1")
        return when (src) {
            "sense" -> sense.takeIf { it.second.isNotBlank() }
            "whisper" -> whisper.takeIf { it.second.isNotBlank() }
            else -> null
        }
    }

    /** 本地模型的字：英文小写、中文之间不留空格 */
    private fun tidy(t: String) = t.trim().lowercase().replace(Regex("(?<=[\\u4e00-\\u9fff])\\s+(?=[\\u4e00-\\u9fff])"), "")

    @SuppressLint("MissingPermission")
    private fun startRecorder() {
        if (running) return
        running = true
        val t = Thread {
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val ar = runCatching {
                AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE * 2))
            }.getOrNull()
            if (ar == null || ar.state != AudioRecord.STATE_INITIALIZED) { main.launch { say("麦克风打不开（可能被别的应用占着）") }; running = false; return@Thread }
            val aec = if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(ar.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
            val ns = if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(ar.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
            val vad = runCatching { Ear.vad(app) }.getOrNull()
            var asr: OnlineRecognizer? = null
            var stream: OnlineStream? = null
            fun load() {
                if (asr != null || !Ear.ready(app)) return
                asr = runCatching { Ear.recognizer(app) }.onFailure { main.launch { say("本地识别启动失败") } }.getOrNull()
                stream = asr?.createStream()
            }
            load()
            val buf = ShortArray(FRAME)
            val f = FloatArray(FRAME)
            val pre = ArrayDeque<ShortArray>()          // 开口前 320ms
            val utt = ArrayList<ShortArray>()
            var floor = .01
            var inUtt = false
            var barge = 0; var loud = 0; var cut = false
            var lastText = ""
            var herWas = false; var herEnd = 0L
            var n0 = 0
            fun feed(s: ShortArray) {
                val a = asr ?: return; val st = stream ?: return
                val x = FloatArray(s.size) { s[it] / 32768f }
                st.acceptWaveform(x, RATE)
                while (a.isReady(st)) a.decode(st)
            }
            fun resetAsr() { val a = asr; val st = stream; if (a != null && st != null) a.reset(st); lastText = "" }
            /** 起句。[now] = 马上打断她（她出声时你插话、或没有本地识别）；否则等识别出字再打断，免得一点杂音就把她在想的回复掐掉 */
            fun begin(pushPre: Boolean, now: Boolean) {
                inUtt = true; utt.clear(); cut = now
                if (pushPre) { utt.addAll(pre); pre.forEach { feed(it) } }
                pre.clear()
                main.launch { hearing = true; if (now && busy(false)) onBarge?.invoke() }
            }
            fun finish(text: String) {
                inUtt = false; loud = 0
                val pcm = utt.toList(); utt.clear()
                val dur = pcm.size * FRAME / RATE.toDouble()
                val c = cloud()
                main.launch {
                    hearing = false
                    when {
                        dur < .35 && text.isBlank() -> { partial = "" }
                        c != null && (text.isNotBlank() || asr == null) -> transcribe(pcm, c, text)
                        text.isNotBlank() && !FILLER.matches(text) -> deliver(text)
                        text.isBlank() && asr == null -> { partial = ""; say(if (Ear.progress >= 0f) "识别模型还在下载，等一下" else "本地识别没装好") }
                        else -> { partial = "" }
                    }
                }
            }
            try {
                ar.startRecording()
                while (running) {
                    val n = ar.read(buf, 0, FRAME)
                    if (n <= 0) continue
                    var acc = 0.0
                    for (i in 0 until n) { val s = buf[i] / 32768.0; acc += s * s }
                    val rms = sqrt(acc / n)
                    val lv = ((20 * log10(rms + 1e-6) + 52) / 40).toFloat().coerceIn(0f, 1f)
                    if (muted) {
                        level = 0f; bands.fill(0f)
                        if (inUtt) { inUtt = false; utt.clear(); main.launch { hearing = false; partial = "" } }
                        resetAsr(); vad?.reset(); pre.clear(); continue
                    }
                    level = lv
                    spectrum(buf, n, lv)
                    if (++n0 % 30 == 0) load()                       // 模型刚下完就接上
                    val frame = buf.copyOf(n)
                    // Silero 判断有没有人声；装不上就退回音量门槛
                    val voice = if (vad != null) {
                        for (i in 0 until n) f[i] = buf[i] / 32768f
                        vad.acceptWaveform(if (n == FRAME) f else f.copyOf(n))
                        while (!vad.empty()) vad.pop()
                        vad.isSpeechDetected()
                    } else rms > maxOf(floor * 3, .014)
                    if (!inUtt) floor = floor * .97 + minOf(rms, floor * 2) * .03
                    val her = Speech.playing.isNotEmpty()
                    if (herWas && !her) herEnd = System.currentTimeMillis()
                    herWas = her

                    if (her && !inUtt) {
                        // 她在出声：不喂识别（免得把她自己的声音当成你说的）；你得是人声、够响、连着 0.25 秒才算插话
                        pre.addLast(frame); if (pre.size > 10) pre.removeFirst()
                        barge = if (voice && rms > maxOf(floor * 5, .045)) barge + 1 else 0
                        if (barge >= 8) { barge = 0; resetAsr(); begin(true, true) }
                        continue
                    }
                    barge = 0
                    if (!inUtt) {
                        pre.addLast(frame); if (pre.size > 10) pre.removeFirst()
                        // 她刚说完的 0.6 秒里不起句（回声尾巴）
                        val calm = System.currentTimeMillis() - herEnd > 600
                        loud = if (voice && calm) loud + 1 else 0
                        if (asr != null) feed(frame)
                        if (loud >= (if (vad != null) 1 else 3)) begin(false, asr == null)
                    } else {
                        utt += frame
                        if (asr != null) feed(frame)
                    }

                    val a = asr; val st = stream
                    if (a != null && st != null) {
                        val txt = tidy(a.getResult(st).text)
                        if (txt != lastText) {
                            lastText = txt
                            if (inUtt) main.launch { partial = txt }
                            if (inUtt && !cut && txt.isNotBlank()) { cut = true; main.launch { if (busy(false)) onBarge?.invoke() } }
                        }
                        if (a.isEndpoint(st)) {
                            val fin = lastText
                            a.reset(st); lastText = ""
                            if (inUtt) finish(fin)
                        }
                    } else if (inUtt) {
                        // 没有本地模型：按 VAD 断句（停 0.8 秒）
                        val dur = utt.size * FRAME / RATE.toDouble()
                        if (!voice || dur > 20) finish("")
                    }
                }
            } finally {
                runCatching { ar.stop() }; ar.release(); aec?.release(); ns?.release()
                runCatching { stream?.release() }; runCatching { asr?.release() }; runCatching { vad?.release() }
                level = 0f
            }
        }
        thread = t; t.start()
    }

    // 512 点 FFT → 100–7500Hz 按对数分 45 段
    private val re = DoubleArray(FRAME); private val im = DoubleArray(FRAME)
    private fun spectrum(s: ShortArray, n: Int, lv: Float) {
        for (i in 0 until FRAME) { val w = .5 - .5 * cos(2 * Math.PI * i / (FRAME - 1)); re[i] = if (i < n) s[i] / 32768.0 * w else 0.0; im[i] = 0.0 }
        fft(re, im)
        val lo = log10(100.0); val hi = log10(7500.0)
        for (b in 0 until 45) {
            val f0 = Math.pow(10.0, lo + (hi - lo) * b / 45); val f1 = Math.pow(10.0, lo + (hi - lo) * (b + 1) / 45)
            val k0 = (f0 * FRAME / RATE).toInt().coerceIn(1, FRAME / 2 - 1); val k1 = (f1 * FRAME / RATE).toInt().coerceIn(k0, FRAME / 2 - 1)
            var m = 0.0; for (k in k0..k1) m = maxOf(m, hypot(re[k], im[k]))
            val db = 20 * log10(m / (FRAME / 4) + 1e-7)
            bands[b] = (((db + 62) / 46).toFloat().coerceIn(0f, 1f) * minOf(1f, lv * 2.2f))
        }
    }

    private fun fft(x: DoubleArray, y: DoubleArray) {
        val n = x.size; var j = 0
        for (i in 1 until n) { var bit = n shr 1; while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }; j = j xor bit
            if (i < j) { var t = x[i]; x[i] = x[j]; x[j] = t; t = y[i]; y[i] = y[j]; y[j] = t } }
        var len = 2
        while (len <= n) {
            val a = -2 * Math.PI / len; val wr = cos(a); val wi = sin(a)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = x[i + k]; val ui = y[i + k]
                    val vr = x[i + k + len / 2] * cr - y[i + k + len / 2] * ci; val vi = x[i + k + len / 2] * ci + y[i + k + len / 2] * cr
                    x[i + k] = ur + vr; y[i + k] = ui + vi; x[i + k + len / 2] = ur - vr; y[i + k + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /* ---------------- 识别 ---------------- */

    /** 云端识别整句；失败时有本地那句就用本地的，并说一声 */
    private suspend fun transcribe(pcm: List<ShortArray>, c: Triple<String, String, String>, local: String) {
        transcribing = true
        try {
            val text = withContext(Dispatchers.IO) { asr(wav(pcm), c) }
            when {
                text.isNotBlank() -> deliver(text)
                local.isNotBlank() -> deliver(local)
                else -> { partial = ""; say("没听清，再说一遍？") }
            }
        } catch (e: Exception) {
            if (local.isNotBlank()) { deliver(local); say((e.message ?: "云端识别失败") + "，这句用的本地识别") }
            else { partial = ""; say(e.message ?: "识别失败") }
        } finally { transcribing = false }
    }

    private fun wav(pcm: List<ShortArray>): ByteArray {
        val n = pcm.sumOf { it.size }
        val o = ByteArrayOutputStream(44 + n * 2)
        fun i32(v: Int) { o.write(v and 255); o.write(v shr 8 and 255); o.write(v shr 16 and 255); o.write(v shr 24 and 255) }
        fun i16(v: Int) { o.write(v and 255); o.write(v shr 8 and 255) }
        o.write("RIFF".toByteArray()); i32(36 + n * 2); o.write("WAVEfmt ".toByteArray()); i32(16); i16(1); i16(1); i32(RATE); i32(RATE * 2); i16(2); i16(16)
        o.write("data".toByteArray()); i32(n * 2)
        pcm.forEach { f -> f.forEach { i16(it.toInt()) } }
        return o.toByteArray()
    }

    /** SenseVoice（硅基流动）/ Whisper（OpenAI）：都是 OpenAI 兼容的 /audio/transcriptions；Key 用「我的 › 语音」里那两家的 */
    private fun asr(wav: ByteArray, c: Triple<String, String, String>): String {
        val (base, key, model) = c
        val b = "----fg" + System.nanoTime()
        val body = ByteArrayOutputStream()
        fun part(name: String, v: String) = body.write("--$b\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$v\r\n".toByteArray())
        part("model", model)
        body.write("--$b\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
        body.write(wav); body.write("\r\n--$b--\r\n".toByteArray())
        val conn = URL(base.trimEnd('/') + "/audio/transcriptions").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"; conn.doOutput = true; conn.connectTimeout = 15000; conn.readTimeout = 30000
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$b")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw Exception(when (code) { 401, 403 -> "识别 Key 不对"; 402 -> "识别账户余额不足"; 429 -> "识别请求太频繁"; else -> "云端识别失败（$code）" })
            return JSONObject(raw).optString("text").replace(Regex("<\\|[^|]*\\|>"), "").trim()
        } finally { conn.disconnect() }
    }
}
