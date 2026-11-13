package com.fuguang.lueying

import org.junit.Assert.*
import org.junit.Test

class StreamRecoveryTest {
    @Test fun eofWithoutCompletionIsInterrupted() {
        assertThrows(StreamTransportException::class.java) { StreamBoundary().verify() }
    }

    @Test fun doneAndAnthropicMessageStopAreCompletion() {
        StreamBoundary().apply { stop(); verify() }
    }

    @Test fun compatibleServersMayCloseAfterFinishReason() {
        for (reason in listOf("stop", "tool_calls", "end_turn", "tool_use"))
            StreamBoundary().apply { finish(reason); verify() }
    }

    @Test fun emptyFinishReasonDoesNotHideDisconnect() {
        val boundary = StreamBoundary().apply { finish(null); finish("") }
        assertThrows(StreamTransportException::class.java) { boundary.verify() }
    }

    @Test fun doneDoesNotTurnTokenLimitIntoSuccess() {
        for (reason in listOf("length", "max_tokens")) {
            val boundary = StreamBoundary().apply { finish(reason); stop() }
            assertThrows(IllegalStateException::class.java) { boundary.verify() }
        }
    }

    @Test fun retryIsBoundedAndNeverReplaysVisibleOutput() {
        assertTrue(StreamRecovery.retry(0, false))
        assertTrue(StreamRecovery.retry(1, false))
        assertFalse(StreamRecovery.retry(2, false))
        assertFalse(StreamRecovery.retry(0, true))
        assertFalse(StreamRecovery.retry(1, true))
    }

    @Test fun backoffUsesBoundedJitter() {
        assertEquals(250L, StreamRecovery.delayMillis(0, 0.0))
        assertEquals(500L, StreamRecovery.delayMillis(0, 1.0))
        assertEquals(1_000L, StreamRecovery.delayMillis(1, 1.0))
    }

    @Test fun finishingChatDoesNotStopArtworkOrAnotherChat() {
        val tasks = LiveTasks()
        tasks.add("chat-a"); tasks.add("artwork"); tasks.add("chat-b")
        assertTrue(tasks.remove("chat-a"))
        assertTrue(tasks.active)
        assertEquals(2, tasks.size)
        assertFalse(tasks.remove("chat-a"))
        assertEquals(2, tasks.size)
        tasks.remove("artwork")
        assertTrue(tasks.active)
        tasks.remove("chat-b")
        assertFalse(tasks.active)
    }
}
