package com.fuguang.lueying

import android.content.Context
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/**
 * 听有没有人在说话：Silero VAD（sherpa-onnx，随 App 带在 assets/sherpa/silero_vad.onnx，640KB，一帧不到 1ms）。
 * 识别不在手机上做了（本地大模型太烫）：字由阿里实时 / 云端 SenseVoice / Whisper / 手机自带识别出。
 */
object Ear {
    /** 以前下的本地识别模型（zipformer 50MB、SenseVoice 237MB）删掉，腾地方 */
    fun purge(ctx: Context) { runCatching { File(ctx.filesDir, "asr").deleteRecursively() } }

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
