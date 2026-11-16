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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
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
 * 录音 16k 单声道（通话音源 + 回声消除），音量 + 静音 0.8 秒断句，整段送识别（SenseVoice / Whisper）；
 * 手机自带识别走 SpeechRecognizer（不能插话）。她说话时你一开口（过线 0.25 秒）就停朗读、停生成。
 * 回复照常进对话，朗读走 Speech.begin（SpeechHooks 在语音模式下强制边写边读）。
 */
object Talk {
    const val RULE = "\n\n【语音对话】对方正在和你语音聊天，你说的话会被直接读出来：用口语、短句，一次两三句；不要列表、标题、链接、代码、表情符号和括号里的动作描写。"
    val SRCS = listOf("sense" to "SenseVoice", "system" to "手机识别", "whisper" to "Whisper")

    var want by mutableStateOf(false)          // 点了球，等权限
    var open by mutableStateOf(false)          // 语音页开着
    var shown by mutableFloatStateOf(0f)       // 进场进度 0..1（铺开动画）
    var from by mutableStateOf(Offset.Unspecified)
    var muted by mutableStateOf(false)
    var hearing by mutableStateOf(false)       // 正在说（断句中）
    var transcribing by mutableStateOf(false)
    var level by mutableFloatStateOf(0f)       // 麦克风音量 0..1
    var note by mutableStateOf("")
    var src by mutableStateOf("sense")
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

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        src = app.getSharedPreferences("voice", Context.MODE_PRIVATE).getString("src", "sense") ?: "sense"
    }

    fun cycleSrc() {
        val i = SRCS.indexOfFirst { it.first == src }
        src = SRCS[(i + 1) % SRCS.size].first
        app.getSharedPreferences("voice", Context.MODE_PRIVATE).edit().putString("src", src).apply()
        if (open) { stopMic(); startMic() }
    }

    fun label() = SRCS.firstOrNull { it.first == src }?.second ?: "SenseVoice"

    fun start() {
        open = true; muted = false; note = ""
        if (!Speech.ready) say("朗读还没配 Key（我的 › 语音），她的回复只出字")
        startMic()
    }

    fun close() {
        open = false; want = false
        stopMic()
        Speech.stopMessages()
    }

    fun toggleMute() {
        muted = !muted
        if (muted) { hearing = false; stopRecognizer() } else if (useSystem()) listenSystem()
    }

    fun say(t: String) { note = t; main.launch { delay(2600); if (note == t) note = "" } }

    /** 她在说 / 在等她：给系统识别用，她说完再开始听 */
    fun busy(chatBusy: Boolean) = chatBusy || Speech.playing.isNotEmpty() || Speech.loading.isNotEmpty() || transcribing

    private fun useSystem() = src == "system" && SpeechRecognizer.isRecognitionAvailable(app)

    private fun startMic() {
        if (src == "system" && !SpeechRecognizer.isRecognitionAvailable(app)) say("手机没装语音识别，改用 SenseVoice")
        if (useSystem()) listenSystem() else startRecorder()
    }

    private fun stopMic() {
        running = false; thread = null
        stopRecognizer()
        hearing = false; level = 0f; bands.fill(0f)
    }

    /* ---------------- 手机自带识别 ---------------- */

    /** 系统识别：空闲（不在说、不在等她）时开一轮；结束或出错后由 [tick] 再开 */
    fun tick(chatBusy: Boolean) {
        if (!open || muted || !useSystem() || recBusy || busy(chatBusy)) return
        listenSystem()
    }

    private fun stopRecognizer() { rec?.let { runCatching { it.cancel(); it.destroy() } }; rec = null; recBusy = false }

    private fun listenSystem() {
        if (recBusy || muted) return
        val r = rec ?: SpeechRecognizer.createSpeechRecognizer(app).also { rec = it }
        recBusy = true
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() { hearing = true }
            override fun onRmsChanged(db: Float) { level = ((db + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() { hearing = false }
            override fun onError(e: Int) { recBusy = false; hearing = false; level = 0f
                if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) say("没有麦克风权限") }
            override fun onResults(b: Bundle?) {
                recBusy = false; hearing = false; level = 0f
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                if (t.isNotEmpty()) onText?.invoke(t)
            }
            override fun onPartialResults(b: Bundle?) {}
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
        runCatching { r.startListening(i) }.onFailure { recBusy = false }
    }

    /* ---------------- 自己录音 + 断句 ---------------- */

    private const val RATE = 16000
    private const val FRAME = 512                 // 32ms

    @SuppressLint("MissingPermission")
    private fun startRecorder() {
        if (running) return
        running = true
        val t = Thread {
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val ar = runCatching {
                AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAME * 8))
            }.getOrNull()
            if (ar == null || ar.state != AudioRecord.STATE_INITIALIZED) { main.launch { say("麦克风打不开") }; running = false; return@Thread }
            val aec = if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(ar.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
            val ns = if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(ar.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
            val buf = ShortArray(FRAME)
            val pre = ArrayDeque<ShortArray>()          // 开口前 300ms
            val utt = ArrayList<ShortArray>()
            var floor = .01
            var loud = 0; var quiet = 0; var inSpeech = false
            try {
                ar.startRecording()
                while (running) {
                    val n = ar.read(buf, 0, FRAME)
                    if (n <= 0) continue
                    var acc = 0.0
                    for (i in 0 until n) { val s = buf[i] / 32768.0; acc += s * s }
                    val rms = sqrt(acc / n)
                    val lv = ((20 * log10(rms + 1e-6) + 52) / 40).toFloat().coerceIn(0f, 1f)
                    if (muted) { level = 0f; bands.fill(0f); inSpeech = false; utt.clear(); continue }
                    level = lv
                    spectrum(buf, n, lv)
                    if (!inSpeech) floor = floor * .97 + minOf(rms, floor * 2) * .03
                    val speaking = Speech.playing.isNotEmpty()
                    // 她在出声时门槛抬高（回声消除不干净也不会自己打断自己）；要连着 0.25 秒
                    val thr = if (speaking) maxOf(floor * 5, .045) else maxOf(floor * 3, .014)
                    val need = if (speaking) 8 else 3
                    val frame = buf.copyOf(n)
                    if (!inSpeech) {
                        pre.addLast(frame); if (pre.size > 10) pre.removeFirst()
                        loud = if (rms > thr) loud + 1 else 0
                        if (loud >= need) {
                            inSpeech = true; quiet = 0; utt.clear(); utt.addAll(pre); pre.clear()
                            main.launch { hearing = true; if (busy(false)) onBarge?.invoke() }
                        }
                    } else {
                        utt += frame
                        quiet = if (rms < thr * .6) quiet + 1 else 0
                        val dur = utt.size * FRAME / RATE.toDouble()
                        if (quiet >= 25 || dur > 30) {           // 静音 0.8 秒算说完
                            inSpeech = false; loud = 0
                            val pcm = utt.toList(); utt.clear()
                            main.launch { hearing = false }
                            if (dur - quiet * FRAME / RATE.toDouble() > .35) main.launch { transcribe(pcm) }
                        }
                    }
                }
            } finally {
                runCatching { ar.stop() }; ar.release(); aec?.release(); ns?.release()
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

    private suspend fun transcribe(pcm: List<ShortArray>) {
        transcribing = true
        try {
            val text = withContext(Dispatchers.IO) { asr(wav(pcm)) }
            if (text.isNotBlank()) onText?.invoke(text) else say("没听清，再说一遍？")
        } catch (e: Exception) {
            say(e.message ?: "识别失败")
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
    private fun asr(wav: ByteArray): String {
        val sense = Triple(Speech.baseOf("siliconflow").ifBlank { "https://api.siliconflow.cn/v1" }, Speech.keyOf("siliconflow"), "FunAudioLLM/SenseVoiceSmall")
        val whisper = Triple(Speech.baseOf("openai").ifBlank { "https://api.openai.com/v1" }, Speech.keyOf("openai"), "whisper-1")
        val (base, key, model) = when {
            src == "whisper" && whisper.second.isNotBlank() -> whisper
            sense.second.isNotBlank() -> sense
            whisper.second.isNotBlank() -> whisper
            else -> throw Exception("识别要硅基流动或 OpenAI 的 Key（我的 › 语音里填）")
        }
        val b = "----fg" + System.nanoTime()
        val body = ByteArrayOutputStream()
        fun part(name: String, v: String) = body.write("--$b\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$v\r\n".toByteArray())
        part("model", model)
        body.write("--$b\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
        body.write(wav); body.write("\r\n--$b--\r\n".toByteArray())
        val c = URL(base.trimEnd('/') + "/audio/transcriptions").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15000; c.readTimeout = 30000
            c.setRequestProperty("Authorization", "Bearer $key")
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$b")
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val raw = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw Exception(when (code) { 401, 403 -> "识别 Key 不对"; 402 -> "识别账户余额不足"; 429 -> "识别请求太频繁"; else -> "识别失败（$code）" })
            return JSONObject(raw).optString("text").replace(Regex("<\\|[^|]*\\|>"), "").trim()
        } finally { c.disconnect() }
    }
}
