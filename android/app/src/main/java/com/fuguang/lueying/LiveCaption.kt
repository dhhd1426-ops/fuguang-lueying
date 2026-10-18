package com.fuguang.lueying

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 同声翻译（内置浏览器底栏「译」）：
 * 听 → 网页里的声音（安卓 10 起能录 App 自己放出来的声音，要点一次系统的「开始录制」）或麦克风；
 * 认字 → sherpa-onnx 在手机上流式认（中英双语小模型，int8 约 50 MB，第一次用时下载，不上传录音）；
 * 翻 → 一句认完交给现在聊天用的模型，带前几句当上下文；字幕像电影字幕一样浮在下面（LiveCaptionUi.kt）。
 */
object Live {
    var on by mutableStateOf(false)
    var src by mutableStateOf("page")          // page 网页的声音 · mic 麦克风
    var to by mutableStateOf("中文")
    var showO by mutableStateOf(true)
    var band by mutableStateOf(false)
    var fz by mutableIntStateOf(1)              // 字号 0 / 1 / 2
    var folded by mutableStateOf(false)
    /** 当前这一句：原文（边认边出）、翻译（认完一句再出）。seq 每换一句加一，字幕换句的动画看它。 */
    var orig by mutableStateOf("")
    var trans by mutableStateOf("")
    var seq by mutableIntStateOf(0)
    var status by mutableStateOf("")
    var prep by mutableFloatStateOf(-1f)        // 下载模型进度：-1 不在下
    var error by mutableStateOf("")
    var store: AssistantStore? = null
    private var prefs: Prefs? = null
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var transJob: Job? = null
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val ctx = ArrayDeque<Pair<String, String>>()

    fun init(p: Prefs) {
        prefs = p
        p.string("live")?.split('|')?.let { a -> a.getOrNull(0)?.let { if (it.isNotEmpty()) src = it }; a.getOrNull(1)?.let { if (it.isNotEmpty()) to = it }; a.getOrNull(2)?.let { showO = it != "0" }; a.getOrNull(3)?.let { band = it == "1" }; a.getOrNull(4)?.toIntOrNull()?.let { fz = it } }
    }
    fun save() { prefs?.putString("live", listOf(src, to, if (showO) "1" else "0", if (band) "1" else "0", "$fz").joinToString("|")) }

    fun start(app: Context, resultCode: Int = 0, data: Intent? = null) {
        error = ""; orig = ""; trans = ""; folded = false; on = true; ctx.clear()
        status = if (src == "page") "放视频就开始…" else "对着手机说话…"
        val i = Intent(app, LiveService::class.java).putExtra("src", src).putExtra("code", resultCode)
        if (data != null) i.putExtra("data", data)
        runCatching { androidx.core.content.ContextCompat.startForegroundService(app, i) }.onFailure { fail("开不了：" + (it.message ?: "")) }
        if (transJob?.isActive != true) transJob = scope.launch { for (s in queue) translate(s) }
    }

    fun stop(app: Context?) { on = false; app?.let { runCatching { it.stopService(Intent(it, LiveService::class.java)) } }; orig = ""; trans = ""; status = "" }
    fun fail(msg: String) { error = msg; status = msg }

    /** 认出一句完整的话：先换上原文，翻译排队。 */
    internal fun sentence(s: String) {
        val t = s.trim().ifEmpty { return }
        seq++; orig = t; trans = ""
        queue.trySend(t)
    }
    internal fun partial(s: String) { if (s.isNotBlank()) { if (trans.isNotEmpty()) { seq++; trans = "" }; orig = s.trim() } }

    private fun isTarget(s: String): Boolean {
        val cjk = s.count { it.code in 0x4E00..0x9FFF }; val latin = s.count { it in 'a'..'z' || it in 'A'..'Z' }
        return when (to) { "中文" -> cjk > latin; "English" -> latin > cjk * 2 && cjk == 0; else -> false }
    }

    private suspend fun translate(s: String) {
        if (!on) return
        if (isTarget(s)) { if (orig == s) trans = s; return }   // 本来就是要的语言：原样
        val st = store ?: return
        val p = provider(st.activeProvider); val c = st.config(st.activeProvider)
        if (!c.configured || st.activeModel.isBlank()) { fail("还没设置好模型，翻不了"); return }
        val before = ctx.joinToString("\n") { "${it.first} → ${it.second}" }
        val sys = "你是同声传译。把用户给的一句话翻成${to}，口语、自然、简短，像在旁边小声讲给朋友听；人名、术语和前文保持一致。只输出译文，不加引号、不解释。" +
            (if (before.isNotEmpty()) "\n前面几句（原文 → 译文）：\n$before" else "")
        val out = runCatching { ChatApi.complete(p, c, st.activeModel, sys, s, 400) }.getOrElse { if (on) status = "翻译没连上，再说一句试试"; return }
            .trim().trim('"', '“', '”').lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        if (out.isEmpty()) return
        ctx.addLast(s to out); while (ctx.size > 4) ctx.removeFirst()
        // 还是这一句：一个字一个字写出来；已经换句了就不追了
        if (orig != s || !on) return
        withContext(Dispatchers.Main) {
            var i = 0
            while (i <= out.length && orig == s && on) { trans = out.substring(0, i); i += 2; delay(45) }
            if (orig == s) trans = out
        }
    }
}

/**
 * 认字模型：中英双语流式小模型（int8）。
 * 先走 jsDelivr（和掠影内容同一条路，国内能连；放在公开的内容仓库 asr-model 分支，encoder 切成三段），再试 hf-mirror、Hugging Face。
 * encoder 下完核对 SHA-256，不对就删了重下。
 */
object AsrModel {
    private const val DIR = "sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16"
    private val FILES = listOf("encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.int8.onnx", "joiner-epoch-99-avg-1.int8.onnx", "tokens.txt")
    private const val ENCODER_SHA = "db6f51551762e40e549166fe041ea3e45464370b595e9ad23f06478ec3794fbb"
    private const val GH = "gh/dhhd1426-ops/fuguanglueying@asr-model/"
    /** 一个地址 + encoder 是不是切成三段。 */
    private val SOURCES = listOf(
        "https://fastly.jsdelivr.net/$GH" to true,
        "https://cdn.jsdelivr.net/$GH" to true,
        "https://gcore.jsdelivr.net/$GH" to true,
        "https://hf-mirror.com/csukuangfj/$DIR/resolve/main/" to false,
        "https://huggingface.co/csukuangfj/$DIR/resolve/main/" to false,
    )

    fun dir(ctx: Context) = File(ctx.filesDir, "asr/$DIR")
    fun ready(ctx: Context) = FILES.all { File(dir(ctx), it).let { f -> f.exists() && f.length() > 1000 } }

    /** 下好四个文件；progress 0–1（按大小算：encoder 占大头）。 */
    suspend fun ensure(ctx: Context, progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val d = dir(ctx).apply { mkdirs() }
        val weight = mapOf(FILES[0] to .86f, FILES[1] to .07f, FILES[2] to .065f, FILES[3] to .005f)
        var base = 0f
        for (name in FILES) {
            val w = weight.getValue(name)
            val f = File(d, name)
            if (f.exists() && f.length() > 1000) { base += w; continue }
            var ok = false
            for ((src, parts) in SOURCES) {
                try {
                    if (name == FILES[0] && parts) {
                        val tmp = File(d, "$name.join"); tmp.delete()
                        for (k in 0..2) { val pf = File(d, "$name.p$k"); fetch("$src$name.part$k", pf) { p -> progress(base + w * (k + p) / 3) }; pf.inputStream().use { i -> java.io.FileOutputStream(tmp, true).use { o -> i.copyTo(o) } }; pf.delete() }
                        if (!tmp.renameTo(f)) error("存不下")
                    } else fetch(src + name, f) { p -> progress(base + w * p) }
                    if (name == FILES[0] && sha256(f) != ENCODER_SHA) { f.delete(); error("下坏了") }
                    ok = true; break
                } catch (e: Exception) { f.delete() }
            }
            if (!ok) throw IllegalStateException("认字模型下不下来（试了 ${SOURCES.size} 个地址），点「译」再试一次")
            base += w
        }
        progress(1f)
    }

    private fun sha256(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(1 shl 16); var n: Int; while (i.read(b).also { n = it } > 0) md.update(b, 0, n) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fetch(url: String, dst: File, p: (Float) -> Unit) {
        var u = url; var hops = 0
        while (true) {
            val c = (URL(u).openConnection() as HttpURLConnection).apply { connectTimeout = 12_000; readTimeout = 25_000; instanceFollowRedirects = false; setRequestProperty("User-Agent", "fuguang-lueying") }
            val code = c.responseCode
            if (code in 300..399 && hops++ < 6) { u = URL(URL(u), c.getHeaderField("Location")).toString(); c.disconnect(); continue }
            if (code != 200) { c.disconnect(); error("$code") }
            val len = c.contentLengthLong
            val tmp = File(dst.path + ".part")
            c.inputStream.use { ins -> tmp.outputStream().use { o ->
                val buf = ByteArray(1 shl 16); var n: Int; var got = 0L
                while (ins.read(buf).also { n = it } >= 0) { o.write(buf, 0, n); got += n; if (len > 0) p(got.toFloat() / len) }
            } }
            c.disconnect()
            if (len > 0 && tmp.length() != len) { tmp.delete(); error("没下完") }
            if (!tmp.renameTo(dst)) error("存不下")
            return
        }
    }

    fun recognizer(ctx: Context): OnlineRecognizer {
        val d = dir(ctx).path
        val cfg = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(encoder = "$d/${FILES[0]}", decoder = "$d/${FILES[1]}", joiner = "$d/${FILES[2]}"),
                tokens = "$d/${FILES[3]}", numThreads = 2, modelType = "zipformer",
            ),
            // 停顿 0.8 秒算一句；一句最长 12 秒就切
            endpointConfig = EndpointConfig(rule1 = EndpointRule(false, 2.0f, 0f), rule2 = EndpointRule(true, 0.8f, 0f), rule3 = EndpointRule(false, 0f, 12f)),
            enableEndpoint = true,
        )
        return OnlineRecognizer(null, cfg)
    }
}

/** 前台服务：录声音、认字；通知栏一条「正在听写翻译」。 */
class LiveService : Service() {
    private var job: Job? = null
    private var projection: MediaProjection? = null
    private var rec: AudioRecord? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("live", "同声翻译", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "live").setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("同声翻译").setContentText("正在听，认字在手机上做").setOngoing(true).build()
        val page = intent?.getStringExtra("src") == "page"
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(9, n, if (page) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(9, n)
        } catch (e: Exception) { Live.fail("开不了：" + (e.message ?: "")); stopSelf(); return START_NOT_STICKY }
        job?.cancel()
        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION") val data: Intent? = intent?.getParcelableExtra("data")
        job = Live.scope.launch {
            try {
                if (!AsrModel.ready(this@LiveService)) {
                    Live.status = "第一次用，在下认字模型（约 50 MB）…"
                    AsrModel.ensure(this@LiveService) { p -> Live.prep = p; Live.status = "在下认字模型 ${(p * 100).toInt()}%" }
                    Live.prep = -1f
                }
                Live.status = if (page) "放视频就开始…" else "对着手机说话…"
                val r = AsrModel.recognizer(this@LiveService)
                val stream = r.createStream()
                val rate = 16000
                val size = maxOf(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), rate / 5 * 2)
                val ar = if (page) {
                    val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    val mp = mpm.getMediaProjection(code, data ?: error("没拿到录制许可"))?.also { projection = it } ?: error("没拿到录制许可")
                    mp.registerCallback(object : MediaProjection.Callback() { override fun onStop() { Live.stop(this@LiveService) } }, null)
                    val cap = AudioPlaybackCaptureConfiguration.Builder(mp)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME).addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
                    AudioRecord.Builder().setAudioPlaybackCaptureConfig(cap)
                        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                        .setBufferSizeInBytes(size * 2).build()
                } else AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size * 2)
                rec = ar
                if (ar.state != AudioRecord.STATE_INITIALIZED) error("录不了声音")
                ar.startRecording()
                val buf = ShortArray(rate / 10)
                val f = FloatArray(buf.size)
                var lastPartial = ""
                while (isActive && Live.on) {
                    val n = ar.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    for (i in 0 until n) f[i] = buf[i] / 32768f
                    stream.acceptWaveform(if (n == f.size) f else f.copyOf(n), rate)
                    while (r.isReady(stream)) r.decode(stream)
                    val text = r.getResult(stream).text.trim()
                    if (text != lastPartial && text.isNotEmpty()) { lastPartial = text; withContext(Dispatchers.Main) { Live.partial(pretty(text)) } }
                    if (r.isEndpoint(stream)) {
                        if (text.isNotEmpty()) withContext(Dispatchers.Main) { Live.sentence(pretty(text)) }
                        r.reset(stream); lastPartial = ""
                    }
                }
                stream.release(); r.release()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Live.prep = -1f
                withContext(Dispatchers.Main) { Live.fail(e.message?.take(40) ?: "出错了") }
            } finally {
                runCatching { rec?.stop() }; runCatching { rec?.release() }; rec = null
            }
        }
        return START_NOT_STICKY
    }

    /** 模型出来的英文是大写、没标点：改成正常句子的样子。 */
    private fun pretty(s: String): String {
        val t = s.replace(Regex("\\s+"), " ").trim()
        if (t.any { it.code in 0x4E00..0x9FFF } && t.none { it in 'A'..'Z' }) return t
        val lower = t.lowercase().replace(Regex("\\bi\\b"), "I").replace(Regex("\\bi'(m|ll|ve|d)\\b")) { "I'" + it.groupValues[1] }
        return lower.replaceFirstChar { it.uppercase() }
    }

    override fun onDestroy() {
        job?.cancel(); runCatching { rec?.stop() }; runCatching { rec?.release() }; runCatching { projection?.stop() }
        super.onDestroy()
    }
}
