package com.fuguang.lueying

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer
import java.util.concurrent.Executors

/**
 * 说完没说完：Smart Turn v3.2（pipecat-ai，BSD-2；Whisper-tiny 编码器 + 分类头，int8 8.7MB，随包 assets/turn/）。
 * 只听声音（语调、拖音、停顿的样子），不看字；和文字规则（Talk.patience）合起来定还要不要等。
 * 自己一条线程算，不卡录音线程；模型起不来就回 null，Talk 退回只看文字。
 */
object Turn {
    private const val ASSET = "turn/smart-turn-v3.2-cpu.onnx"
    private val exec = Executors.newSingleThreadExecutor { Thread(it, "turn").apply { isDaemon = true; priority = Thread.NORM_PRIORITY + 1 } }
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var broken = false

    /** 先把模型装上（进语音页时调），第一次判断就不用等加载 */
    fun warm(ctx: Context) { val app = ctx.applicationContext; exec.execute { load(app) } }

    private fun load(ctx: Context): OrtSession? {
        session?.let { return it }
        if (broken) return null
        return runCatching {
            val e = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val bytes = ctx.assets.open(ASSET).use { it.readBytes() }
            env = e
            e.createSession(bytes, opts).also { session = it }
        }.onFailure { broken = true; Log.w("Turn", "Smart Turn 起不来，只看文字断句", it) }.getOrNull()
    }

    /** 判断这段话（16k、[-1,1]，取最后 8 秒）说完的概率 0..1；在 turn 线程回调，模型不可用给 null */
    fun judge(ctx: Context, pcm: FloatArray, done: (Float?) -> Unit) {
        val app = ctx.applicationContext
        exec.execute {
            val s = load(app); val e = env
            if (s == null || e == null) { done(null); return@execute }
            val p = runCatching {
                val f = TurnMel.features(pcm)
                OnnxTensor.createTensor(e, FloatBuffer.wrap(f), longArrayOf(1, TurnMel.MELS.toLong(), TurnMel.FRAMES.toLong())).use { t ->
                    s.run(mapOf("input_features" to t)).use { r ->
                        @Suppress("UNCHECKED_CAST")
                        (r[0].value as Array<FloatArray>)[0][0]
                    }
                }
            }.onFailure { Log.w("Turn", "判断失败", it) }.getOrNull()
            done(p)
        }
    }
}
