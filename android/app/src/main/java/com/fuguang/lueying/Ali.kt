package com.fuguang.lueying

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 阿里百炼 Paraformer 实时识别（paraformer-realtime-v2，WebSocket duplex）：边说边出字，中文和方言最准，带标点。
 * 一句话一个任务：开口时连上、边说边送 16k PCM、说完 finish-task 等 task-finished。断句仍由 Talk（Silero + 两票合一）定。
 * 协议照官方文档：run-task → task-started → 二进制音频 → result-generated（sentence_end=true 是定稿）→ finish-task → task-finished。
 * 按「有人声的时长」计费，静音不收钱。Key 是百炼控制台的 API Key（sk-…）。
 */
object Ali {
    private const val URL = "wss://dashscope.aliyuncs.com/api-ws/v1/inference"
    private val http by lazy { OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).pingInterval(15, TimeUnit.SECONDS).build() }

    /** [onText] 每次出字回调「到目前为止整句」（定稿 + 正在认的）；在 OkHttp 线程上调 */
    class Session(key: String, private val onText: (String) -> Unit) {
        private val id = UUID.randomUUID().toString().replace("-", "")
        private val lock = Any()
        private val pending = ArrayList<ByteArray>()
        private var started = false
        private var ending = false
        private val fixed = StringBuilder()
        private var cur = ""
        @Volatile var error: String? = null; private set
        private val done = CountDownLatch(1)
        private val ws: WebSocket

        init {
            val req = Request.Builder().url(URL).header("Authorization", "Bearer $key").build()
            ws = http.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(w: WebSocket, r: Response) { w.send(runTask()) }
                override fun onMessage(w: WebSocket, text: String) { handle(text) }
                override fun onMessage(w: WebSocket, bytes: ByteString) {}
                override fun onFailure(w: WebSocket, t: Throwable, r: Response?) {
                    if (done.count > 0L) error = when (r?.code) { 401, 403 -> "百炼 Key 不对"; else -> "阿里识别连不上" }
                    done.countDown()
                }
                override fun onClosed(w: WebSocket, code: Int, reason: String) { done.countDown() }
            })
        }

        private fun runTask() = JSONObject()
            .put("header", JSONObject().put("action", "run-task").put("task_id", id).put("streaming", "duplex"))
            .put("payload", JSONObject().put("task_group", "audio").put("task", "asr").put("function", "recognition")
                .put("model", "paraformer-realtime-v2")
                .put("parameters", JSONObject().put("format", "pcm").put("sample_rate", 16000)
                    .put("language_hints", JSONArray().put("zh").put("en"))
                    .put("max_sentence_silence", 2000))       // 句子边界我们自己定，服务端别切太碎
                .put("input", JSONObject())).toString()

        private fun finishTask() = JSONObject()
            .put("header", JSONObject().put("action", "finish-task").put("task_id", id).put("streaming", "duplex"))
            .put("payload", JSONObject().put("input", JSONObject())).toString()

        private fun handle(text: String) {
            val o = runCatching { JSONObject(text) }.getOrNull() ?: return
            val h = o.optJSONObject("header") ?: return
            when (h.optString("event")) {
                "task-started" -> synchronized(lock) {
                    started = true
                    pending.forEach { ws.send(it.toByteString()) }; pending.clear()
                    if (ending) ws.send(finishTask())
                }
                "result-generated" -> {
                    val s = o.optJSONObject("payload")?.optJSONObject("output")?.optJSONObject("sentence") ?: return
                    if (s.optBoolean("heartbeat")) return
                    val t = s.optString("text")
                    val all = synchronized(lock) {
                        if (s.optBoolean("sentence_end")) { fixed.append(t); cur = "" } else cur = t
                        fixed.toString() + cur
                    }
                    onText(all)
                }
                "task-finished" -> { done.countDown(); ws.close(1000, null) }
                "task-failed" -> {
                    val code = h.optString("error_code")
                    error = when {
                        code.contains("Arrearage", true) -> "百炼账户欠费"
                        code.contains("InvalidApiKey", true) || code.contains("Unauthorized", true) -> "百炼 Key 不对"
                        else -> "阿里识别出错（${code.ifBlank { h.optString("error_message").take(30) }}）"
                    }
                    done.countDown(); ws.close(1000, null)
                }
            }
        }

        /** 送一帧 16 位 PCM（小端）。任务还没开始就先攒着 */
        fun send(pcm: ShortArray) {
            val b = ByteArray(pcm.size * 2)
            for (i in pcm.indices) { val v = pcm[i].toInt(); b[2 * i] = v.toByte(); b[2 * i + 1] = (v shr 8).toByte() }
            synchronized(lock) { if (started) ws.send(b.toByteString()) else if (!ending) pending += b }
        }

        /** 说完了：发 finish-task（还没开始就等开始后发） */
        fun end() = synchronized(lock) { if (ending) return; ending = true; if (started) ws.send(finishTask()) }

        /** 等定稿（阻塞，IO 线程调）。失败 / 超时返回已认出的部分，[error] 里有原因 */
        fun await(ms: Long): String {
            if (!done.await(ms, TimeUnit.MILLISECONDS)) { error = error ?: "阿里识别超时"; ws.cancel() }
            return synchronized(lock) { fixed.toString() + cur }.trim()
        }

        fun cancel() { ws.cancel(); done.countDown() }
    }
}
