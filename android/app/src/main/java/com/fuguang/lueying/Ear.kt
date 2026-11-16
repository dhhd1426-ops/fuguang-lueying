package com.fuguang.lueying

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * 本地听写（sherpa-onnx，k2-fsa，Apache-2.0）：
 * - 识别：SenseVoice Small（阿里 FunAudioLLM，int8 2025-09-09，约 237MB；中英日韩粤，带标点、数字转阿拉伯）。
 *   不是流式模型：照 sherpa 官方 SimulateStreamingAsr，说话时每隔一会儿把这句从头认一遍出字幕，说完再认一遍定稿。
 *   第一次用下载：本仓库 Releases「asr-model-sensevoice」，连不上退到 hf-mirror（同一份，sha256 一致），断点续传。
 * - 说没说话：Silero VAD（随 App 带在 assets/sherpa/silero_vad.onnx，640KB）。
 * 全在手机上算，不要 Key、不联网也能听。
 */
object Ear {
    private val BASES = listOf(
        "https://github.com/dhhd1426-ops/fuguang-lueying/releases/download/asr-model-sensevoice/",
        "https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09/resolve/main/",
    )
    private val FILES = listOf(
        "model.int8.onnx" to 237115547L,
        "tokens.txt" to 315894L,
    )
    val TOTAL = FILES.sumOf { it.second }

    /** 下载进度 0..1；-1 = 没在下 */
    var progress by mutableFloatStateOf(-1f)
        private set

    private val lock = Mutex()

    private fun dir(ctx: Context) = File(ctx.filesDir, "asr/sensevoice")
    fun ready(ctx: Context) = FILES.all { (n, size) -> File(dir(ctx), n).length() == size }

    /** 以前的流式小模型（zipformer，50MB）不用了：新模型装好后删掉 */
    fun dropOld(ctx: Context) { runCatching { File(ctx.filesDir, "asr/zh-en").deleteRecursively() } }

    /** 缺哪个下哪个（.part 续传，下完改名）。失败抛异常，消息给人看 */
    suspend fun fetch(ctx: Context) = lock.withLock {
        if (ready(ctx)) return@withLock
        withContext(Dispatchers.IO) {
            val d = dir(ctx).apply { mkdirs() }
            fun have() = FILES.sumOf { (n, size) -> File(d, n).takeIf { it.length() == size }?.length() ?: File(d, "$n.part").length().coerceAtMost(size) }
            progress = have() / TOTAL.toFloat()
            try {
                for ((name, size) in FILES) {
                    val done = File(d, name)
                    if (done.length() == size) continue
                    val part = File(d, "$name.part")
                    if (part.length() > size) part.delete()
                    var tries = 0
                    while (part.length() < size) {
                        // GitHub 连不上（大陆常见）就换 hf-mirror；两边同一份文件，.part 接着续
                        try { pull(BASES[tries % BASES.size] + name, part, size) { progress = have() / TOTAL.toFloat() } }
                        catch (e: Exception) { if (++tries >= 4) throw e }
                    }
                    if (part.length() != size || !part.renameTo(done)) throw Exception("识别模型没下完整")
                }
            } catch (e: java.net.UnknownHostException) {
                throw Exception("没联网，识别模型下不了")
            } catch (e: java.io.IOException) {
                throw Exception("识别模型下载断了，再进来会接着下")
            } finally { progress = -1f }
            dropOld(ctx)
        }
    }

    private fun pull(url: String, part: File, size: Long, tick: () -> Unit) {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000; c.readTimeout = 30000; c.instanceFollowRedirects = true
            val from = part.length()
            if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
            val code = c.responseCode
            if (code != 200 && code != 206) throw java.io.IOException("HTTP $code")
            RandomAccessFile(part, "rw").use { out ->
                out.seek(if (code == 206) from else 0L)
                if (code == 200) out.setLength(0)
                c.inputStream.use { inp ->
                    val buf = ByteArray(64 * 1024); var n = 0
                    var last = 0L
                    while (inp.read(buf).also { n = it } > 0) {
                        out.write(buf, 0, n)
                        val now = System.currentTimeMillis()
                        if (now - last > 120) { last = now; tick() }
                    }
                }
            }
            if (part.length() < size) throw java.io.IOException("short")
        } finally { c.disconnect() }
    }

    /** 识别器：建一次约 1–2 秒（读 237MB），在识别线程里建；同一个识别器只在一条线程上用 */
    fun recognizer(ctx: Context): OfflineRecognizer {
        val d = dir(ctx)
        val cfg = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = File(d, FILES[0].first).absolutePath,
                    language = "",                        // 自动：中英混说也行
                    useInverseTextNormalization = true,   // 标点 + 「三点」→「3点」
                ),
                tokens = File(d, FILES[1].first).absolutePath,
                numThreads = 2,
                provider = "cpu",
            ),
        )
        return OfflineRecognizer(null, cfg)
    }

    /** 认一段（16k，[-1,1]）。末尾补 0.3 秒静音，最后一个字不吃 */
    fun decode(r: OfflineRecognizer, pcm: FloatArray, rate: Int): String {
        val st = r.createStream()
        try {
            st.acceptWaveform(pcm, rate)
            st.acceptWaveform(FloatArray(rate * 3 / 10), rate)
            r.decode(st)
            return r.getResult(st).text
        } finally { st.release() }
    }

    /** Silero VAD：一窗 512 点（32ms），判定 0.5；静 0.5 秒算「可能说完」，真断不断由 Talk 两票合一 */
    fun vad(ctx: Context): Vad = Vad(
        ctx.assets,
        VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "sherpa/silero_vad.onnx",
                threshold = 0.5f,
                minSilenceDuration = 0.5f,
                minSpeechDuration = 0.25f,
                windowSize = 512,
                maxSpeechDuration = 20f,
            ),
            sampleRate = 16000,
            numThreads = 1,
            provider = "cpu",
        ),
    )
}
