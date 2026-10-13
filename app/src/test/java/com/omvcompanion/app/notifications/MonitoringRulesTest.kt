package com.omvcompanion.app.notifications

import com.omvcompanion.app.data.repo.*
import org.junit.Assert.*
import org.junit.Test

class MonitoringRulesTest {
    @Test fun unreadableDriveDoesNotGenerateFalseRecovery() {
        val previous = setOf("smart:/dev/a")
        val current = retainedAlertKeys(previous, emptySet(), setOf("smart:/dev/a"))
        assertTrue(transitions(previous, current).recovered.isEmpty())
        assertEquals(previous, transitions(previous, retainedAlertKeys(previous, emptySet(), emptySet())).recovered)
    }

    @Test fun transitionsFireOnceAndReportRecovery() {
        assertEquals(setOf("disk"), transitions(emptySet(), setOf("disk")).fired)
        assertTrue(transitions(setOf("disk"), setOf("disk")).fired.isEmpty())
        assertEquals(setOf("disk"), transitions(setOf("disk"), emptySet()).recovered)
    }
    @Test fun ignoredOrUnreadableTemperaturesAreNotFailures() {
        val devices = listOf(SmartData("/dev/a", "A", 55, "GOOD"), SmartData("/dev/b", "B", 90, null, true), SmartData("/dev/c", "C", null, "GOOD"))
        assertEquals(listOf("temperature:/dev/a"), temperatureAlerts(devices, 55).map { it.key })
    }
    @Test fun volumeThresholdIsUsedWhenGlobalOverrideIsDisabled() {
        val volume = FileSystem("a", "/dev/a", "Data", "/srv/a", "ext4", 100, 87, 87, 85)
        assertEquals(1, storageAlerts(listOf(volume), 0).size)
        assertEquals(0, storageAlerts(listOf(volume), 90).size)
    }
}
