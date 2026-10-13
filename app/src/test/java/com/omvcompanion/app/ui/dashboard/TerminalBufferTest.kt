package com.omvcompanion.app.ui.dashboard

import org.junit.Assert.*
import org.junit.Test

class TerminalBufferTest {
    @Test fun preservesEscapeSequencesAcrossNetworkReads() {
        val buffer = TerminalBuffer(20, 4)
        buffer.feed("Hello\u001b[")
        assertEquals("New", buffer.feed("2J\u001b[HNew"))
    }
    @Test fun shellCarriageReturnsUpdateExistingLine() {
        val buffer = TerminalBuffer(20, 4)
        assertEquals("done", buffer.feed("loading\rdone\u001b[K"))
    }
    @Test fun boundsScrollingOutputAndConsumesColorCodes() {
        val buffer = TerminalBuffer(20, 2)
        val text = buffer.feed("old\r\none\r\n\u001b[31mtwo\u001b[0m")
        assertEquals("one\ntwo", text)
    }
}
