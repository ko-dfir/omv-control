package com.omvcompanion.app.ui.dashboard

import java.io.ByteArrayInputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommandOutputTest {
    @Test fun `cancelling silent follow command closes channel`() = runTest {
        var disconnected = false
        val job = launch { streamCommandOutput(ByteArrayInputStream(byteArrayOf()), { false }, { -1 }, { disconnected = true }, {}) }
        runCurrent(); job.cancelAndJoin()
        assertTrue(disconnected)
    }
    @Test fun `waits for channel close after output and returns real exit code`() = runTest {
        var closed = false; var disconnected = false
        val lines = mutableListOf<String>()
        val result = async { streamCommandOutput(ByteArrayInputStream("first\r\n温度\nlast".toByteArray()), { closed }, { if (closed) 7 else -1 }, { disconnected = true }, lines::add) }
        runCurrent(); assertFalse(result.isCompleted)
        closed = true; advanceUntilIdle()
        assertEquals(7, result.await())
        assertEquals(listOf("first", "温度", "last"), lines)
        assertTrue(disconnected)
    }
}
