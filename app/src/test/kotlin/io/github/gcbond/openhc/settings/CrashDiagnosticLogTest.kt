package io.github.gcbond.openhc.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CrashDiagnosticLogTest {
    @Test
    fun stackHasTypesAndFramesWithoutExceptionMessages() {
        val cause = NoSuchMethodError("private-message-654321").apply {
            stackTrace = Array(18) { StackTraceElement("example.LegacySocket", "close$it", "LegacySocket.kt", it + 1) }
        }
        val trace = diagnosticStack(IllegalStateException("private-connection-pin", cause))
        assertTrue(trace.contains("java.lang.IllegalStateException"))
        assertTrue(trace.contains("Caused by: java.lang.NoSuchMethodError"))
        assertTrue(trace.contains("LegacySocket.close0"))
        assertFalse(trace.contains("LegacySocket.close12"))
        assertFalse(trace.contains("private-"))
    }

    @Test
    fun recordingDoesNotPreventTheSystemHandlerEvenWhenWritingFails() {
        var records = 0
        var forwarded = 0
        val thread = Thread.currentThread()
        val failure = IllegalStateException()
        val original = Thread.UncaughtExceptionHandler { reportedThread, reportedError ->
            assertSame(thread, reportedThread)
            assertSame(failure, reportedError)
            forwarded++
        }
        val handler = RecordingCrashHandler(original) { _, _ ->
            records++
            error("disk failure")
        }
        handler.uncaughtException(thread, failure)
        handler.uncaughtException(thread, failure)
        assertEquals(1, records)
        assertEquals(2, forwarded)
    }
}
