package com.omvcompanion.app.ui.dashboard
import org.junit.Assert.*
import org.junit.Test
class ReadableDetailsTest {
    @Test fun portsDecodeDockerAndComposeFormats() {
        assertTrue(readableContainerField("""[{"PrivatePort":80,"PublicPort":8080,"Type":"tcp"}]""", true).contains("8080"))
        assertTrue(readableContainerField("""{"443/tcp":null}""", true).contains("not published"))
        assertFalse(readableContainerField("<a href='http://nas'>0.0.0.0:8080->80/tcp</a>", true).contains("<a"))
    }
    @Test fun inspectionKeepsSecretsOutAndExplainsLimits() {
        val summary = readableInspection("""[{"Name":"/web","Config":{"Image":"nginx","Env":["SECRET=password"]},"HostConfig":{"Memory":0,"NanoCpus":0,"RestartPolicy":{"Name":"unless-stopped"}},"NetworkSettings":{"Ports":{"80/tcp":[{"HostPort":"8080","HostIp":"0.0.0.0"}]}},"Mounts":[]}]""").joinToString()
        assertTrue(summary.contains("No fixed limit")); assertTrue(summary.contains("8080")); assertFalse(summary.contains("password"))
    }
    @Test fun memorySkipsEmptySlotsAndReportsConfiguredSpeed() {
        val result = readableMemory("Memory Device\n Size: No Module Installed\nMemory Device\n Size: 16 GB\n Type: DDR4\n Speed: 3200 MT/s\n Configured Memory Speed: 2666 MT/s")
        assertFalse(result.contains("No Module")); assertTrue(result.contains("2666 MT/s"))
    }
}
