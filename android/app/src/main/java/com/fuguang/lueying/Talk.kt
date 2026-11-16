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
import kotlinx.coroutines.sync.withLock
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

    /** 发出一句：(这句话, 是不是接着上一句说的——她还没开口你就接着说了，该拼成一句重问) */
    var onText: ((String, Boolean) -> Unit)? = null
    var onBarge: (() -> Unit)? = null

    private lateinit var app: Context
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile private var running = false
    private var thread: Thread? = null
    private var rec: SpeechRecognizer? = null
    private var recBusy = false
    private var sysFails = 0
    /** 上一句发出后她出过声没有（续句判断用） */
    @Volatile private var spoke = false
    /** 上一句（有字的）收句时刻：续句按它算，不按「发出时刻」——云端识别要一两秒，那时还没发出 */
    @Volatile private var lastEnd = 0L
    /** 手机识别这一句是不是续句（开口时定） */
    private var sysCont = false
    /** 云端识别排队：两截先后送出，结果按先后发，续句才拼得上 */
    private val order = kotlinx.coroutines.sync.Mutex()

    /** 断句过程（长按名字的浮窗里看）：最近 10 件事 */
    val trace = androidx.compose.runtime.mutableStateListOf<String>()
    private val t0 = System.currentTimeMillis()
    fun log(s: String) { val t = (System.currentTimeMillis() - t0) / 100 / 10.0; main.launch { trace.add("%.1f  %s".format(t, s)); while (trace.size > 10) trace.removeAt(0) } }

    /** 上一句的声音（含开口前）：浮窗里点「回放」听识别到底收到了什么 */
    @Volatile private var lastPcm: List<ShortArray> = emptyList()
    fun replay() {
        val pcm = lastPcm; if (pcm.isEmpty()) return
        main.launch(Dispatchers.IO) {
            val n = pcm.sumOf { it.size }
            val all = ShortArray(n); var o = 0; pcm.forEach { it.copyInto(all, o); o += it.size }
            runCatching {
                val t = android.media.AudioTrack.Builder()
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(n * 2).setTransferMode(android.media.AudioTrack.MODE_STATIC).build()
                t.write(all, 0, n); t.play()
                delay(n * 1000L / RATE + 300); t.release()
            }
        }
    }
    /** 你说话的慢：中途停顿常被续上就慢慢加大等待（1.0..1.6，存着） */
    @Volatile private var slow = 1.0

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        // v2：默认本地识别（以前存的「手机识别」在很多国产机上不能用，一律换过来，你想用再切回去）
        val sp = app.getSharedPreferences("voice", Context.MODE_PRIVATE)
        src = sp.getString("src2", "local") ?: "local"
        slow = sp.getFloat("slow", 1f).toDouble().coerceIn(1.0, 1.6)
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
        if (::app.isInitialized) app.getSharedPreferences("voice", Context.MODE_PRIVATE).edit().putFloat("slow", slow.toFloat()).apply()
    }

    /** 发出一句：字幕上浮淡出，交给对话 */
    private fun deliver(text: String, c: Boolean) {
        partial = ""
        // 「停 / 别说了」：插话那一下已经让她停了，这句不用再发给她
        if (STOP.matches(text)) { sent = ""; log("只让她停：「$text」"); if (text.startsWith("等")) say("好，等你"); return }
        log("发出「$text」" + if (c) " ·续上一句" else "")
        if (c) slow = minOf(1.6, slow + .15)
        sent = text; sentAt = System.currentTimeMillis(); spoke = false
        onText?.invoke(text, c)
    }

    /* ---------------- 手机自带识别 ---------------- */

    /** 系统识别：空闲（不在说、不在等她）时开一轮；结束或出错后由 [tick] 再开 */
    fun tick(chatBusy: Boolean) {
        if (Speech.playing.isNotEmpty()) spoke = true
        if (!open || muted || src != "system" || recBusy || busy(chatBusy)) return
        listenSystem()
    }

    private fun stopRecognizer() {
        rec?.let { runCatching { it.cancel(); it.destroy() } }; rec = null; recBusy = false
        sysJob?.cancel(); sysJob = null; sysHeld = ""
    }

    /** 这个识别器是刚新建的（新建的也报错才算真坏） */
    private var sysFresh = false
    /** 手机自带的「端侧识别」用不了（没语言包等）：以后只用普通的 */
    private var noOnDevice = false
    /** 手机识别也攥一下：一段像没说完就先留着、马上接着听，接上了拼成一句 */
    private var sysHeld = ""
    private var sysJob: kotlinx.coroutines.Job? = null

    private fun sysCommit() { sysJob?.cancel(); sysJob = null; val h = sysHeld; sysHeld = ""; val c = sysCont; sysCont = false; if (h.isNotEmpty()) deliver(h, c) else partial = "" }

    private fun listenSystem() {
        if (recBusy || muted) return
        sysFresh = rec == null
        // Android 13+ 有端侧识别就用（不联网、不走别家服务，国产机上更稳）；用不了退回普通的
        val r = rec ?: runCatching {
            if (!noOnDevice && android.os.Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(app))
                SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
            else SpeechRecognizer.createSpeechRecognizer(app)
        }.getOrNull()?.also { rec = it }
        if (r == null) { sysBroken("手机识别打不开"); return }
        recBusy = true
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { sysFails = 0 }
            override fun onBeginningOfSpeech() {
                hearing = true
                if (sysHeld.isEmpty()) sysCont = sentAt > 0 && !spoke && System.currentTimeMillis() - sentAt < 2500
            }
            override fun onRmsChanged(db: Float) { level = ((db + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() { hearing = false }
            override fun onError(e: Int) {
                recBusy = false; hearing = false; level = 0f
                when (e) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> if (sysHeld.isNotEmpty()) sysCommit()   // 没接着说：攥着的发出去
                    // 5 客户端 / 8 忙 / 11 断开：多半是识别器状态坏了（国产机常见），扔掉重建再来一次；新建的也报才算坏
                    SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_RECOGNIZER_BUSY, 11 -> {
                        val held = sysHeld; val job = sysJob
                        rec?.let { runCatching { it.destroy() } }; rec = null
                        if (sysFresh) { sysJob = null; sysHeld = ""; job?.cancel(); if (held.isNotEmpty()) deliver(held, sysCont); sysCont = false; sysBroken("手机识别出错（$e）") }
                        else main.launch { delay(300); if (open && !muted && src == "system") listenSystem() }
                    }
                    // 12 / 13：端侧识别没这门语言或没下语言包，换普通的
                    12, 13 -> { rec?.let { runCatching { it.destroy() } }; rec = null
                        if (!noOnDevice) { noOnDevice = true; main.launch { delay(200); if (open && !muted && src == "system") listenSystem() } } else sysBroken("手机识别不支持中文") }
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> say("没有麦克风权限")
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> sysBroken("手机识别要联网")
                    else -> sysBroken("手机识别出错（$e）")
                }
            }
            override fun onResults(b: Bundle?) {
                recBusy = false; hearing = false; level = 0f
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                if (t.isEmpty()) { if (sysHeld.isNotEmpty()) sysCommit() else partial = ""; return }
                // 手机识别拿不到声音，只有文字那一票
                val all = join(sysHeld, t)
                val w = (patience(all) * slow).toLong()
                sysJob?.cancel()
                if (w == 0L) { sysHeld = all; sysCommit(); return }
                sysHeld = all; partial = all
                sysJob = main.launch { delay(w + 300); sysCommit() }
                listenSystem()                       // 马上接着听
            }
            override fun onPartialResults(b: Bundle?) {
                b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    if (sysHeld.isNotEmpty() && sysJob != null) { sysJob?.cancel(); sysJob = null; slow = minOf(1.6, slow + .1) }   // 接上了
                    partial = join(sysHeld, it)
                }
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
    private val FILLER = Regex("^[嗯啊哦呃额唔哈]+$")
    /** 只为让她停下的话：不发给她 */
    private val STOP = Regex("^(停|停停|停一下|停下|别说了|不要说了|不用说了|闭嘴|安静|好了好了|等一下|等等|等会儿)$")

    /** 像说完了：语气词收尾、问句、应答 */
    private val CLOSED = Regex("(吗|呢|吧|了|呀|嘛|啦|怎么样|多少|几点|哪里|好的|好|行|对|是的|不是|没有|可以|谢谢|再见|拜拜)$|(?i)\\b(please|thanks|ok|okay|yes|no)$")
    /** 像没说完：时间词、连词、介词、「我想 / 帮我」这类开头 */
    private val OPEN = Regex("(今天|明天|后天|昨天|现在|刚才|待会|那个|这个|就是|然后|还有|而且|但是|可是|所以|因为|如果|我想|我要|想问|帮我|给我|请你|一下|一个|的|和|跟|在|把|被|从|往|给|是|有|还|再|也|都|又|呃|嗯|[0-9零一二三四五六七八九十百千万])$|(?i)\\b(and|or|but|so|the|a|to|of|for|with|my|i|is|what)$")

    /** 两截拼一句：两边都是英文字母/数字就隔个空格，中文直接接 */
    fun join(a: String, b: String): String =
        if (a.isNotEmpty() && b.isNotEmpty() && a.last().isLetterOrDigit() && a.last().code < 128 && b.first().isLetterOrDigit() && b.first().code < 128) "$a $b" else a + b

    /** utt 最后 [sec] 秒，转成 [-1,1] */
    private fun lastSeconds(utt: List<ShortArray>, sec: Int): FloatArray {
        val want = RATE * sec
        var n = 0; var i = utt.size
        while (i > 0 && n < want) { i--; n += utt[i].size }
        val out = FloatArray(n); var o = 0
        for (j in i until utt.size) for (v in utt[j]) out[o++] = v / 32768f
        return out
    }

    /**
     * 两票合一：端点（停 0.5 秒）之后还要再等多久。[text] = 文字那票（patience：0 像说完 / 350 看不出 / 1100 像没说完），[p] = 声音那票（说完的概率；null = 模型不可用）。
     * 照 Pipecat：声音说没完就多等，最长兜底约 2 秒（再乘你说话的慢）。
     */
    private fun fuse(text: Long, p: Float?): Long = when {
        p == null -> text + 300                    // 没有声音那票：和以前一样（0.8 秒起）
        text == 0L -> if (p >= .25f) 0 else 500    // 字像说完了：除非声音很肯定没完
        text >= 1100L -> if (p >= .9f) 700 else 1400  // 短句声音再像说完也至少多等 0.7 秒（「好久……不见」）
        p >= .6f -> 0
        p >= .3f -> 500
        else -> 1200
    }

    /** 文字那一票：说完了不等；太短或没说完多等 1.1 秒；其余 0.35 秒 */
    private fun patience(t: String): Long = when {
        CLOSED.containsMatchIn(t) -> 0L
        t.length <= 3 || OPEN.containsMatchIn(t) -> 1100L
        else -> 350L
    }

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
                AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE * 2 * 4))   // 4 秒缓冲：第二遍识别占着线程时不丢声音
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
            val pre = ArrayDeque<ShortArray>()          // 开口前 640ms（Silero 要 0.25 秒才认人声，开头一两个字在这里）
            val utt = ArrayList<ShortArray>()
            var floor = .01
            var inUtt = false
            var barge = 0; var loud = 0; var cut = false
            var lastText = ""
            var herWas = false; var herEnd = 0L
            var n0 = 0
            // 端点后先「攥着」这句：没说完的样子就多等一会儿，接着说就拼成一句（他山之石：LiveKit / Pipecat 的 turn detection）
            var held = ""; var holdUntil = 0L; var voiceWas = false
            // 声音那一票：Smart Turn 在自己线程算，算完放进 verdict（序号对不上的作废——中途又开口了）
            var seq = 0; var textWait = 0L
            var segs = 0                                  // 这句被端点切成了几截（≥2 才要第二遍）
            var uttCont = false                           // 这句是不是上一句的后半截（起句时定，跟着这句走，不用全局的）
            var rise = 0                                  // 攥着时音量连着高了几帧（Silero 有 0.8 秒拖尾，接着说时它不会「重新开口」）
            val verdict = java.util.concurrent.atomic.AtomicReference<Pair<Int, Float?>?>(null)
            Turn.warm(app)
            fun feed(s: ShortArray) {
                val a = asr ?: return; val st = stream ?: return
                val x = FloatArray(s.size) { s[it] / 32768f }
                st.acceptWaveform(x, RATE)
                while (a.isReady(st)) a.decode(st)
            }
            fun resetAsr() { val a = asr; val st = stream; if (a != null && st != null) a.reset(st); lastText = "" }
            /** 起句。[now] = 马上打断她（她出声时你插话、或没有本地识别）；否则等识别出字再打断，免得一点杂音就把她在想的回复掐掉 */
            fun begin(pushPre: Boolean, now: Boolean) {
                inUtt = true; utt.clear(); cut = now; held = ""; holdUntil = 0L; seq++; segs = 0
                // 上一句刚收（不论发没发出去）、她还没开口，你 2.5 秒内又说：多半是一句话被切成了两截
                uttCont = lastEnd > 0 && !spoke && System.currentTimeMillis() - lastEnd < 2500
                log("起句 预录${pre.size * 32}ms" + (if (now) " ·插话" else "") + (if (uttCont) " ·像续句" else ""))
                // 每句从干净的识别流开始，先喂开口前那段（照 sherpa VAD+ASR 示例 / Pipecat pre_speech）；空闲时不喂识别，免得它自己断句重置把开头吞掉
                resetAsr()
                if (pushPre) { utt.addAll(pre); pre.forEach { feed(it) } }
                pre.clear()
                main.launch { hearing = true; if (now && busy(false)) onBarge?.invoke() }
            }
            fun finish(text0: String) {
                inUtt = false; loud = 0; held = ""; holdUntil = 0L; seq++
                val pcm = utt.toList(); utt.clear()
                val dur = pcm.size * FRAME / RATE.toDouble()
                // 第二遍：这句中途被切过（识别流重置过），接缝处的字可能残缺 → 整句声音（含开口前）用新流从头再认一次（sherpa two-pass 的思路，同一个模型不用另下）。
                // 没切过的不必：实时那条流本来就是从开口前一路认到底的，再认一遍只是多等
                val text = if (segs >= 2 && text0.isNotBlank() && dur <= 15) asr?.let { tidy(Ear.redo(it, pcm, RATE)) }?.takeIf { it.isNotBlank() } ?: text0 else text0
                if (segs >= 2) log("第二遍「$text」")
                val k = uttCont; uttCont = false
                if (text.isNotBlank()) { lastEnd = System.currentTimeMillis(); spoke = false }
                lastPcm = pcm
                log("收句「$text0」" + "%.1f秒".format(dur))
                val c = cloud()
                main.launch {
                    hearing = false
                    when {
                        dur < .35 && text.isBlank() -> { partial = "" }
                        c != null && (text.isNotBlank() || asr == null) -> transcribe(pcm, c, text, k)
                        text.isNotBlank() && !FILLER.matches(text) -> deliver(text, k)
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
                        if (inUtt) { inUtt = false; utt.clear(); held = ""; holdUntil = 0L; main.launch { hearing = false; partial = "" } }
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
                    if (her) spoke = true

                    if (her && !inUtt) {
                        // 她在出声：不喂识别（免得把她自己的声音当成你说的）；你得是人声、够响、连着 0.25 秒才算插话
                        pre.addLast(frame); while (pre.size > 10) pre.removeFirst()   // 插话只留 320ms，多了会混进她的声音
                        barge = if (voice && rms > maxOf(floor * 5, .045)) barge + 1 else 0
                        if (barge >= 8) { barge = 0; resetAsr(); begin(true, true) }
                        continue
                    }
                    barge = 0
                    if (!inUtt) {
                        // 她刚说完的 0.6 秒里不起句（回声尾巴），开口前那段也不留她的回声
                        val calm = System.currentTimeMillis() - herEnd > 600
                        if (calm) { pre.addLast(frame); if (pre.size > 20) pre.removeFirst() } else pre.clear()
                        loud = if (voice && calm) loud + 1 else 0
                        if (loud >= (if (vad != null) 1 else 3)) begin(true, asr == null)
                    } else {
                        utt += frame
                        if (asr != null) feed(frame)
                    }

                    val a = asr; val st = stream
                    if (a != null && st != null) {
                        val now = System.currentTimeMillis()
                        // 攥着时又开口：先续 0.6 秒等识别出字；出了字就算接着说
                        if (holdUntil > 0 && voice && !voiceWas) holdUntil = maxOf(holdUntil, now + 600)
                        voiceWas = voice
                        rise = if (inUtt && rms > maxOf(floor * 3, .02)) rise + 1 else 0
                        if (holdUntil > 0 && rise == 3) { holdUntil = maxOf(holdUntil, now + 900); log("又有声音，多等") }
                        verdict.getAndSet(null)?.let { (sq, p) ->
                            if (sq == seq && inUtt && holdUntil > 0) {
                                val w = (fuse(textWait, p) * slow).toLong()
                                holdUntil = if (w == 0L) 1L else now + w
                                log("声音票 " + (p?.let { "%.2f".format(it) } ?: "无") + " 文字票 $textWait → 再等 ${w}ms")
                            }
                        }
                        val txt = tidy(a.getResult(st).text)
                        if (txt != lastText) {
                            lastText = txt
                            if (txt.isNotBlank() && holdUntil > 0) { holdUntil = 0L; seq++; slow = minOf(1.6, slow + .1); log("接着说了") }
                            if (inUtt) { val show = join(held, txt); main.launch { partial = show } }
                            if (inUtt && !cut && txt.isNotBlank()) { cut = true; main.launch { if (busy(false)) onBarge?.invoke() } }
                        }
                        if (a.isEndpoint(st)) {
                            val fin = lastText
                            a.reset(st); lastText = ""
                            when {
                                !inUtt -> {}
                                fin.isBlank() -> finish(held)              // 这段没出字：有攥着的就发，没有就丢
                                else -> {
                                    held = join(held, fin); segs++
                                    log("停顿「$fin」")
                                    // 文字一票先记下；声音一票回来前最多等 1.5 秒
                                    textWait = patience(held)
                                    val my = ++seq
                                    holdUntil = now + 1500
                                    Turn.judge(app, lastSeconds(utt, 8)) { p -> verdict.set(my to p) }
                                }
                            }
                        } else if (inUtt && holdUntil in 1..now) { slow = maxOf(1.0, slow - .02); finish(held) }
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
    private suspend fun transcribe(pcm: List<ShortArray>, c: Triple<String, String, String>, local: String, k: Boolean) = order.withLock {
        transcribing = true
        try {
            val text = withContext(Dispatchers.IO) { asr(wav(pcm), c) }
            log("云端「$text」")
            when {
                text.isNotBlank() -> deliver(text, k)
                local.isNotBlank() -> deliver(local, k)
                else -> { partial = ""; say("没听清，再说一遍？") }
            }
        } catch (e: Exception) {
            if (local.isNotBlank()) { deliver(local, k); say((e.message ?: "云端识别失败") + "，这句用的本地识别") }
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
