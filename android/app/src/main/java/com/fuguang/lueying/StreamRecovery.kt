package com.fuguang.lueying

/** A socket EOF is not a model completion. Kept Android-free for protocol regression tests. */
internal class StreamBoundary {
    private var ended = false
    private var limit = false

    fun finish(reason: String?) {
        if (reason.isNullOrBlank()) return
        ended = true
        limit = reason == "length" || reason == "max_tokens"
    }

    fun stop() { ended = true }

    fun verify() {
        if (limit) throw IllegalStateException("输出达到模型长度上限，已保留内容，可以继续")
        if (!ended) throw StreamTransportException("连接提前结束，已保留收到的内容")
    }
}

internal class StreamTransportException(message: String) : IllegalStateException(message)

internal object StreamRecovery {
    // Reissue only an unobserved MODEL request. Never replay a completed tool or partial output.
    fun retry(attempt: Int, received: Boolean) = !received && attempt < 2
    fun delayMillis(attempt: Int, jitter: Double): Long {
        val cap = 500L shl attempt.coerceIn(0, 4)
        return (cap * (.5 + jitter.coerceIn(0.0, 1.0) * .5)).toLong()
    }
}

/** Identity-based leases: duplicate releases and one finished task cannot stop other work. */
internal class LiveTasks {
    private val ids = mutableSetOf<String>()
    fun add(id: String) { ids.add(id) }
    fun remove(id: String): Boolean = ids.remove(id)
    val active: Boolean get() = ids.isNotEmpty()
    val size: Int get() = ids.size
}
