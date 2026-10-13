package com.omvcompanion.app.data.repo

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ServerSettingsReadTest {
    @Test fun `missing settings are empty but corrupted settings fail closed`() {
        assertTrue(decodeServerProfiles(Json, null).isEmpty())
        listOf("", "{broken", "null", "{}").forEach {
            try { decodeServerProfiles(Json, it); fail("Corruption became empty servers") }
            catch (e: IllegalStateException) { assertTrue(e.message!!.contains("preserved")) }
        }
    }
}
