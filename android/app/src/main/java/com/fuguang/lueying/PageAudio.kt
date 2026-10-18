package com.fuguang.lueying

import android.content.Context
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 从网页里直接接视频的声音（戴耳机也行，不用录制许可）：
 * 安卓录「系统内部声音」不会录到 App 自己放的声音，内置浏览器正是 App 自己，所以那条路录不到。
 * 这里往网页里塞一段 Web Audio：视频的声音分一路出来，降到 16k，一段段（约 0.1 秒）交给 App 认字；原来那路照样放出来。
 * 跨域又没开 CORS 的视频（少数直接放 mp4 的网站）浏览器只给静音——10 秒全静音就退回录音 / 麦克风。
 */
object PageAudio {
    private val pcm = Channel<FloatArray>(64)
    private var job: Job? = null
    private var webRef: java.lang.ref.WeakReference<WebView>? = null

    /** 网页那边调这个，把一段声音递过来。 */
    class Bridge {
        @JavascriptInterface
        fun pcm(b64: String) {
            val raw = Base64.decode(b64, Base64.NO_WRAP)
            val f = FloatArray(raw.size / 2)
            for (i in f.indices) f[i] = ((raw[2 * i].toInt() and 0xff) or (raw[2 * i + 1].toInt() shl 8)).toShort() / 32768f
            pcm.trySend(f)
        }
    }

    /** 塞进网页的那段：找到正在放的视频，接一路声音出来。返回 ok / novideo / 出错原因。 */
    private const val TAP = """(function(){try{
if(window.__fgTap){window.__fgTap.on=true;if(window.__fgTap.ac.state!=='running')window.__fgTap.ac.resume();return 'ok'}
var vs=[].slice.call(document.querySelectorAll('video,audio'));if(!vs.length)return 'novideo';
var v=vs.filter(function(x){return !x.paused})[0]||vs[0];
var AC=window.AudioContext||window.webkitAudioContext;var ac=new AC();
var src=ac.createMediaElementSource(v);src.connect(ac.destination);
var sp=ac.createScriptProcessor(4096,1,1);var g=ac.createGain();g.gain.value=0;src.connect(sp);sp.connect(g);g.connect(ac.destination);
var ratio=ac.sampleRate/16000,acc=[],pos=0;var T={ac:ac,src:src,sp:sp,v:v,on:true};
sp.onaudioprocess=function(e){if(!T.on)return;var d=e.inputBuffer.getChannelData(0);
 for(;pos<d.length;pos+=ratio){var s=d[Math.floor(pos)];acc.push(s<-1?-32768:s>1?32767:Math.round(s*32767))}pos-=d.length;
 if(acc.length>=1600){var b=new Uint8Array(acc.length*2);for(var i=0;i<acc.length;i++){var x=acc[i]&0xffff;b[2*i]=x&255;b[2*i+1]=x>>8}acc=[];
  var s2='';for(var j=0;j<b.length;j+=8192)s2+=String.fromCharCode.apply(null,b.subarray(j,j+8192));FGLive.pcm(btoa(s2))}};
ac.resume();window.__fgTap=T;return 'ok'}catch(x){return 'err:'+x.message}})()"""

    private const val UNTAP = "(function(){if(window.__fgTap)window.__fgTap.on=false})()"

    /** 接上网页的声音开始认字。拿不到（没视频、出错）返回原因，接上了返回 null。 */
    suspend fun start(ctx: Context, web: WebView): String? {
        val r = withContext(Dispatchers.Main) {
            kotlinx.coroutines.suspendCancellableCoroutine<String> { c -> web.evaluateJavascript(TAP) { s -> if (c.isActive) c.resume(s?.trim('"').orEmpty()) { } } }
        }
        if (r != "ok") return if (r == "novideo") "这一页没找到视频" else "接不上网页的声音"
        webRef = java.lang.ref.WeakReference(web)
        while (pcm.tryReceive().isSuccess) { }
        job?.cancel()
        job = Live.scope.launch {
            try {
                if (!AsrModel.ready(ctx)) {
                    Live.status = "第一次用，在下认字模型（约 50 MB）…"
                    AsrModel.ensure(ctx) { p -> Live.prep = p; Live.status = "在下认字模型 ${(p * 100).toInt()}%" }
                    Live.prep = -1f
                }
                Live.status = "放视频就开始…"
                val rec = AsrModel.recognizer(ctx)
                val stream = rec.createStream()
                var last = ""; var quietSince = System.currentTimeMillis(); var gotAny = false
                while (isActive && Live.on) {
                    val f = withTimeoutOrNull(1000) { pcm.receive() }
                    if (f == null) { if (!gotAny && System.currentTimeMillis() - quietSince > 10_000) quiet(); continue }
                    gotAny = true
                    var sum = 0.0; for (x in f) sum += x * x
                    val rms = kotlin.math.sqrt(sum / f.size).toFloat()
                    Live.level = (rms * 8).coerceIn(0f, 1f)
                    if (rms > .003f) { quietSince = System.currentTimeMillis(); if (Live.silent) Live.silent = false }
                    else if (System.currentTimeMillis() - quietSince > 10_000) quiet()
                    stream.acceptWaveform(f, 16000)
                    while (rec.isReady(stream)) rec.decode(stream)
                    val text = rec.getResult(stream).text.trim()
                    if (text.isNotEmpty() && text != last) { last = text; withContext(Dispatchers.Main) { Live.partial(Asr.pretty(text)) } }
                    if (rec.isEndpoint(stream)) {
                        if (text.isNotEmpty()) withContext(Dispatchers.Main) { Live.sentence(Asr.pretty(text)) }
                        rec.reset(stream); last = ""
                    }
                }
                stream.release(); rec.release()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                Live.prep = -1f; withContext(Dispatchers.Main) { Live.fail(e.message?.take(40) ?: "出错了") }
            }
        }
        return null
    }

    private fun quiet() {
        if (Live.silent) return
        Live.silent = true
        if (Live.orig.isEmpty()) Live.status = "视频在放吗？这一页的声音接不出来（有的网站不让）"
    }

    fun stop() { job?.cancel(); job = null; webRef?.get()?.let { w -> w.post { w.evaluateJavascript(UNTAP, null) } }; webRef = null }
}

/** 认字结果整理成正常句子的样子（模型出来的英文是大写、没标点）。 */
object Asr {
    fun pretty(s: String): String {
        val t = s.replace(Regex("\\s+"), " ").trim()
        if (t.any { it.code in 0x4E00..0x9FFF } && t.none { it in 'A'..'Z' }) return t
        val lower = t.lowercase().replace(Regex("\\bi\\b"), "I").replace(Regex("\\bi'(m|ll|ve|d)\\b")) { "I'" + it.groupValues[1] }
        return lower.replaceFirstChar { it.uppercase() }
    }
}
