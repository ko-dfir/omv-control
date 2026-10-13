package com.omvcompanion.app.data.repo

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ManagementRulesTest {
    @Test fun editingCronRetainsIntervalFlagsAndConvertsScheduleArrays() {
        val original = Json.parseToJsonElement("""{"uuid":"123","execution":"exactly","username":"root","command":"echo ok","minute":"5,10","hour":"*","everynminute":true,"sendemail":false}""") as JsonObject
        val result = cronParams(original, mapOf("comment" to "Updated"), true, false)
        assertEquals(JsonArray(listOf(JsonPrimitive("5"), JsonPrimitive("10"))), result["minute"])
        assertEquals(JsonPrimitive(true), result["everynminute"])
        assertEquals(JsonPrimitive("123"), result["uuid"])
    }
    @Test fun rejectsOutOfRangeCronFieldsBeforeSaving() {
        try { cronParams(JsonObject(emptyMap()), mapOf("command" to "echo ok", "hour" to "24"), true, false); fail("Expected invalid hour") }
        catch (e: IllegalArgumentException) { assertTrue(e.message.orEmpty().contains("hour")) }
    }
    @Test fun unavailableRegistryChecksAreNotImageUpdates() {
        fun image(status: String) = buildJsonObject { put("status", status) }
        assertTrue(imageUpdateAvailable(image("AVAILABLE")))
        for (status in listOf("CURRENT", "FAILED", "TIMEOUT", "NA")) assertFalse(imageUpdateAvailable(image(status)))
    }
}
