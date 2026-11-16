package com.fuguang.lueying

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 手机自带的语音合成（朗读没配 Key 时语音对话的兜底）：合成成 wav 交回去，
 * 和云端合成走同一条播放管线（嘴型、语音页的光、字幕都照常）。
 */
object SysVoice {
    private var tts: TextToSpeech? = null
    private var up: CompletableDeferred<Boolean>? = null
    private val waits = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(id: String?) {}
        override fun onDone(id: String?) { id?.let { waits.remove(it)?.complete(true) } }
        @Deprecated("Deprecated in Java")
        override fun onError(id: String?) { id?.let { waits.remove(it)?.complete(false) } }
        override fun onError(id: String?, code: Int) { id?.let { waits.remove(it)?.complete(false) } }
    }

    private suspend fun engine(ctx: Context): TextToSpeech {
        val d = up ?: CompletableDeferred<Boolean>().also { nd ->
            up = nd
            withContext(Dispatchers.Main) {
                tts = TextToSpeech(ctx.applicationContext) { s -> nd.complete(s == TextToSpeech.SUCCESS) }
            }
        }
        val ok = withTimeoutOrNull(8000) { d.await() } ?: false
        val t = tts
        if (!ok || t == null) {
            up = null; runCatching { tts?.shutdown() }; tts = null
            throw Speech.Err(-1, "手机没有可用的语音合成（设置 › 文字转语音）")
        }
        t.setOnUtteranceProgressListener(listener)
        runCatching { if (t.isLanguageAvailable(Locale.CHINA) >= TextToSpeech.LANG_AVAILABLE) t.language = Locale.CHINA }
        return t
    }

    suspend fun synth(ctx: Context, text: String): ByteArray {
        val t = engine(ctx)
        val f = File(ctx.cacheDir, "systts_${System.nanoTime()}.wav")
        val id = f.name
        val done = CompletableDeferred<Boolean>().also { waits[id] = it }
        try {
            if (t.synthesizeToFile(text, Bundle(), f, id) != TextToSpeech.SUCCESS) throw Speech.Err(-1, "手机语音合成失败")
            val ok = withTimeoutOrNull(20000) { done.await() } ?: false
            if (!ok || f.length() <= 44) throw Speech.Err(-1, "手机语音合成失败")
            return withContext(Dispatchers.IO) { f.readBytes() }
        } finally {
            waits.remove(id); f.delete()
        }
    }
}
