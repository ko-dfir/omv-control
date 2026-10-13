package com.omvcompanion.app.notifications

import com.omvcompanion.app.data.repo.FileSystem
import com.omvcompanion.app.data.repo.SmartData
import org.junit.Assert.*
import org.junit.Test

class AlertRulesTest {
    private fun volume(percent: Int, mounted: Boolean = true, size: Long = 1000) = FileSystem("id", "/dev/sda1", null, if (mounted) "/mnt/data" else null, "ext4", size, size * percent / 100, percent)
    @Test fun `capacity only warns at threshold for measurable mounted volumes`() {
        assertTrue(storageAlerts(listOf(volume(89))).isEmpty())
        assertEquals(1, storageAlerts(listOf(volume(90))).size)
        assertTrue(storageAlerts(listOf(volume(99, false), volume(100, size = 0))).isEmpty())
    }
    @Test fun `unreadable SMART is never a failed drive alert`() {
        val unreadable = SmartData("/dev/usb", "USB disk", null, "BAD_STATUS", true)
        val good = SmartData("/dev/sda", "SSD", 30, "PASSED")
        val failed = SmartData("/dev/sdb", "HDD", 30, "BAD_STATUS")
        assertEquals(listOf("smart:/dev/sdb"), smartAlerts(listOf(unreadable, good, failed)).map { it.key })
    }
    @Test fun `capacity alert identity does not change with usage to prevent notification storms`() {
        assertEquals(storageAlerts(listOf(volume(90))).single().key, storageAlerts(listOf(volume(99))).single().key)
    }
}
