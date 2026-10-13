package com.omvcompanion.app.ui.dashboard

import com.omvcompanion.app.data.repo.SmartData
import org.junit.Assert.*
import org.junit.Test

class SmartAcknowledgementsTest {
    private fun smart(status: String?, unavailable: Boolean = false) = SmartData("/dev/sda", "SSD", null, status, unavailable)
    @Test fun aDifferentFailureIsNotAcknowledged() {
        assertNotEquals(smartWarningSignature(smart("BAD_SECTOR")), smartWarningSignature(smart("FAILED")))
    }
    @Test fun unreadableOrMissingDataDoesNotEraseAcknowledgement() {
        val entries = setOf(smartWarningSignature(smart("BAD_SECTOR")))
        assertEquals(entries, retainedAcknowledgements(entries, listOf(smart(null, true))))
        assertEquals(entries, retainedAcknowledgements(entries, emptyList()))
    }
    @Test fun confirmedRecoveryClearsAcknowledgementForFutureFailures() {
        val entries = setOf(smartWarningSignature(smart("BAD_SECTOR")))
        assertTrue(retainedAcknowledgements(entries, listOf(smart("GOOD"))).isEmpty())
    }
    @Test fun changedFailureClearsPreviousAcknowledgement() {
        assertTrue(retainedAcknowledgements(setOf(smartWarningSignature(smart("BAD_SECTOR"))), listOf(smart("FAILED"))).isEmpty())
    }
}
