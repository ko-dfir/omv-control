package com.omvcompanion.app.ui.dashboard

import com.omvcompanion.app.data.repo.Disk
import com.omvcompanion.app.data.repo.SmartData

/** OMV may identify the same drive by a kernel path or a persistent by-id path. */
internal fun matchingSmart(disk: Disk, readings: List<SmartData>): SmartData? {
    readings.singleOrNull { it.deviceFile == disk.deviceFile }?.let { return it }
    val serial = disk.serialNumber?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    // Never match by model: multiple drives commonly have the same model.
    val suffix = Regex("(?:^|_)" + Regex.escape(serial.replace(' ', '_')) + "(?:-\\d+:\\d+)?$")
    return readings.filter {
        it.deviceFile.startsWith("/dev/disk/by-id/") && suffix.containsMatchIn(it.deviceFile.substringAfterLast('/'))
    }.singleOrNull()
}
