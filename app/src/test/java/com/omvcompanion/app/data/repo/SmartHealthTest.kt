package com.omvcompanion.app.data.repo

import org.junit.Assert.*
import org.junit.Test

class SmartHealthTest {
    @Test fun unavailableReportsDoNotIndicateFailure() {
        listOf("", "Read Device Identity failed", "SMART support is: Unavailable", "SMART Health Status: UNKNOWN", "SMART overall-health self-assessment test result: PASSED").forEach {
            assertFalse(it, hasExplicitSmartFailure(it))
        }
    }
    @Test fun actualFailuresRemainWarnings() {
        listOf("SMART overall-health self-assessment test result: FAILED!", "SMART Health Status: LOGICAL UNIT FAILURE PREDICTION THRESHOLD EXCEEDED", "Critical Warning: 0x02").forEach {
            assertTrue(it, hasExplicitSmartFailure(it))
        }
        assertFalse(hasExplicitSmartFailure("Critical Warning: 0x00"))
    }
}
