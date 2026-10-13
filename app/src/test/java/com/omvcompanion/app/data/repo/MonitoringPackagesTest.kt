package com.omvcompanion.app.data.repo

import org.junit.Assert.*
import org.junit.Test

class MonitoringPackagesTest {
    @Test fun `optional tools are deduplicated`() {
        assertEquals(listOf("intel-gpu-tools", "lm-sensors"), validatedMonitoringPackages(listOf("intel-gpu-tools", "lm-sensors", "intel-gpu-tools")))
    }
    @Test fun `driver replacements and shell tokens cannot be installed as monitoring tools`() {
        listOf("nvidia-driver", "--allow-unauthenticated", "python3; reboot", "").forEach { name ->
            try { validatedMonitoringPackages(listOf(name)); fail("Accepted $name") } catch (_: IllegalArgumentException) { }
        }
    }
}
