package com.omvcompanion.app.ui.dashboard

import com.omvcompanion.app.data.repo.Disk
import com.omvcompanion.app.data.repo.SmartData
import org.junit.Assert.*
import org.junit.Test

class DriveHealthTest {
    private fun disk(serial: String? = "SERIAL123") = Disk("/dev/sdb", "Same model", null, serial, 100, false)
    private fun reading(path: String, unavailable: Boolean = false) = SmartData(path, "Same model", null, if (unavailable) null else "GOOD", unavailable)
    @Test fun directPathWins() {
        val exact = reading("/dev/sdb")
        assertEquals(exact, matchingSmart(disk(), listOf(exact, reading("/dev/disk/by-id/ata-Model_SERIAL123"))))
    }
    @Test fun persistentAtaPathMatchesSerial() {
        val smart = reading("/dev/disk/by-id/ata-Model_SERIAL123")
        assertEquals(smart, matchingSmart(disk(), listOf(smart)))
    }
    @Test fun usbPathRetainsUnavailableState() {
        val smart = reading("/dev/disk/by-id/usb-Model_SERIAL123-0:0", true)
        assertTrue(matchingSmart(disk(), listOf(smart))!!.unavailable)
    }
    @Test fun serialSubstringCannotMatchAnotherDrive() {
        assertNull(matchingSmart(disk("123"), listOf(reading("/dev/disk/by-id/ata-Model_SERIAL123"))))
    }
    @Test fun duplicateSerialCannotAssignHealth() {
        assertNull(matchingSmart(disk(), listOf(reading("/dev/disk/by-id/ata-Model_SERIAL123"), reading("/dev/disk/by-id/usb-Model_SERIAL123-0:0"))))
    }
    @Test fun sameModelWithoutSerialCannotAssignHealth() {
        assertNull(matchingSmart(disk(null), listOf(reading("/dev/disk/by-id/ata-Model_SERIAL123"))))
    }
}
