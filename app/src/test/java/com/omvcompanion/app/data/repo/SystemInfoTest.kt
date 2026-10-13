package com.omvcompanion.app.data.repo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemInfoTest {

    private fun parse(s: String) = SystemInfo.parse(Json.parseToJsonElement(s) as JsonObject)

    @Test
    fun `parses OMV 7 style payload`() {
        val info = parse(
            """{"hostname":"nas","version":"7.4.5-1 (Sandworm)","kernel":"Linux 6.1.0","cpuModelName":"Intel N100",
               "cpuUsage":12.5,"memTotal":"8000000000","memAvailable":"6000000000",
               "loadAverage":{"1min":0.5,"5min":0.25,"15min":0.1},"uptime":93784.2,
               "configDirty":true,"rebootRequired":false,"pkgUpdatesAvailable":true,"availablePkgUpdates":3}""",
        )
        assertEquals("nas", info.hostname)
        assertEquals(12.5, info.cpuUsagePercent!!, 0.001)
        assertEquals(2_000_000_000L, info.memUsed)
        assertEquals(0.25, info.load5!!, 0.001)
        assertEquals(93784L, info.uptimeSeconds)
        assertTrue(info.configDirty)
        assertEquals(3, info.updatesAvailable)
    }

    @Test
    fun `tolerates legacy string fields`() {
        val info = parse("""{"hostname":"old","loadAverage":"0.10, 0.20, 0.30","uptime":"1 day 2 hours","pkgUpdatesAvailable":0}""")
        assertEquals(0.3, info.load15!!, 0.001)
        assertEquals("1 day 2 hours", info.uptimeText)
        assertEquals(0, info.updatesAvailable)
    }
}
