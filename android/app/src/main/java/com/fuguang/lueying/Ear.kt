package com.fuguang.lueying

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
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
 * - 流式识别：中英双语 streaming zipformer（int8，约 47MB），第一次用从本仓库 Releases「asr-model-zh-en」下载，断点续传；
 * - 说没说话：Silero VAD（随 App 带在 assets/sherpa/silero_vad.onnx，640KB）。
 * 全在手机上算，不要 Key、不联网也能听。
 */
object Ear {
    private const val BASE = "https://github.com/dhhd1426-ops/fuguang-lueying/releases/download/asr-model-zh-en/"
    private val FILES = listOf(
        "encoder-epoch-99-avg-1.int8.onnx" to 42980793L,
        "decoder-epoch-99-avg-1.int8.onnx" to 3486740L,
        "joiner-epoch-99-avg-1.int8.onnx" to 3228485L,
        "tokens.txt" to 56317L,
    )
    val TOTAL = FILES.sumOf { it.second }

    /** 下载进度 0..1；-1 = 没在下 */
    var progress by mutableFloatStateOf(-1f)
        private set

    private val lock = Mutex()

    private fun dir(ctx: Context) = File(ctx.filesDir, "asr/zh-en")
    fun ready(ctx: Context) = FILES.all { (n, size) -> File(dir(ctx), n).length() == size }

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
                        try { pull(BASE + name, part, size) { progress = have() / TOTAL.toFloat() } }
                        catch (e: Exception) { if (++tries >= 3) throw e }
                    }
                    if (part.length() != size || !part.renameTo(done)) throw Exception("识别模型没下完整")
                }
            } catch (e: java.net.UnknownHostException) {
                throw Exception("没联网，识别模型下不了")
            } catch (e: java.io.IOException) {
                throw Exception("识别模型下载断了，再进来会接着下")
            } finally { progress = -1f }
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

    /**
     * 流式识别器。断句：说过话之后停 0.8 秒；一直不说话 2.4 秒清一次；一句最长 20 秒。
     * 建一次约 1 秒，在录音线程里建。
     */
    fun recognizer(ctx: Context): OnlineRecognizer {
        val d = dir(ctx)
        val cfg = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(d, FILES[0].first).absolutePath,
                    decoder = File(d, FILES[1].first).absolutePath,
                    joiner = File(d, FILES[2].first).absolutePath,
                ),
                tokens = File(d, FILES[3].first).absolutePath,
                numThreads = 2,
                provider = "cpu",
                modelType = "zipformer",
            ),
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 2.4f, 0f),
                rule2 = EndpointRule(true, 0.5f, 0f),   // 只是「可能说完了」，真断不断由 Talk 两票合一（文字 + Smart Turn）
                rule3 = EndpointRule(false, 0f, 20f),
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        )
        return OnlineRecognizer(null, cfg)
    }

    /** Silero VAD：一窗 512 点（32ms），判定 0.5；停 0.8 秒算一段说完 */
    fun vad(ctx: Context): Vad = Vad(
        ctx.assets,
        VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "sherpa/silero_vad.onnx",
                threshold = 0.5f,
                minSilenceDuration = 0.8f,
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
