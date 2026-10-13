package com.omvcompanion.app.data.repo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class NewControlsTest {
    @Test fun filePathsCannotLeaveSharedRoot() {
        assertTrue(insideSharedFolder("/srv/data", "/srv/data/photos"))
        assertFalse(insideSharedFolder("/srv/data", "/srv/database"))
        listOf("../etc", "photos/../../etc", "/etc", "a/./b", "a\\b").forEach { assertTrue(runCatching { relativeFilePath(it) }.isFailure) }
        assertEquals("Photos/2026", relativeFilePath("Photos/2026"))
    }
    @Test fun fileNamesAreLiteralAndCannotBecomeGlobDeletes() {
        assertEquals("/srv/data/a\\*b\\?.txt", literalSftpPath("/srv/data/a*b?.txt"))
        listOf("..", ".", "a/b", "a\nname", "").forEach { assertTrue(runCatching { safeFileName(it) }.isFailure) }
        assertEquals("Family photos.png", safeFileName("Family photos.png"))
    }
    @Test fun historyIsBoundedAndDoesNotInventFutureObservations() {
        val now = 10 * 86_400_000L
        assertEquals(listOf(now - 1000, now), retainedSamples(listOf(PerformanceSample(0), PerformanceSample(now), PerformanceSample(now + 1000), PerformanceSample(now - 1000)), now).map { it.time })
        assertEquals(20_200, retainedSamples((0..21_000).map { PerformanceSample(now - it) }, now).size)
    }
    @Test fun absentMetricsStayAbsent() {
        assertNull(performanceValue(PerformanceSample(1), "Temperature"))
        assertNull(performanceValue(PerformanceSample(1, cpu = Double.NaN), "CPU"))
        assertEquals(0.0, performanceValue(PerformanceSample(1, cpu = 0.0), "CPU")!!, 0.0)
    }
    @Test fun throughputNeedsTwoSamplesAndDoesNotTurnCounterResetIntoTraffic() {
        val first = PerformanceCounters(100.0, 1000.0, 2000.0, 3000.0, 4000.0)
        assertTrue(throughput(null, first).all { it == null })
        val later = first.copy(uptime = 102.0, received = 1000 + 2 * 1_048_576.0)
        assertEquals(1.0, throughput(first, later)[0]!!, 0.0)
        assertTrue(throughput(first, first.copy(uptime = 1.0)).all { it == null })
        assertTrue(throughput(first, first.copy(uptime = 500.0)).all { it == null })
        assertNull(throughput(first, first.copy(uptime = 101.0, received = 0.0))[0])
    }
    @Test fun containerDraftPreservesEqualsInSecretsAndAnonymousVolumeNames() {
        val parsed = draftFromInspection(Json.parseToJsonElement("""{"Config":{"Env":["TOKEN=a=b=c"],"Labels":{}},"HostConfig":{"NetworkMode":"bridge","RestartPolicy":{"Name":"on-failure","MaximumRetryCount":5},"PortBindings":{"80/tcp":[{"HostPort":"8080","HostIp":"127.0.0.1"}]}},"Mounts":[{"Type":"volume","Name":"anonymous-volume-id","Destination":"/data","RW":false}],"State":{"Status":"running"}}""").jsonObject)
        assertEquals("a=b=c", parsed.draft.environment.single().value)
        assertEquals("anonymous-volume-id", parsed.draft.mounts.single().source)
        assertTrue(parsed.draft.mounts.single().readOnly)
        assertEquals("127.0.0.1", parsed.draft.ports.single().hostAddress)
        assertEquals(5, parsed.draft.retries)
    }
    private fun valid() = ContainerDraft(listOf(EnvironmentEntry("TZ", "Europe/Bucharest")), listOf(PortMapping("80", "8080")), listOf(ContainerMount("/srv/data", "/data")), "unless-stopped", 0)
    @Test fun duplicateEnvironmentAndMountsAreRejected() {
        assertTrue(runCatching { validateContainerDraft(valid().copy(environment = listOf(EnvironmentEntry("TZ", "a"), EnvironmentEntry("TZ", "b")))) }.isFailure)
        assertTrue(runCatching { validateContainerDraft(valid().copy(mounts = listOf(ContainerMount("/a", "/data"), ContainerMount("/b", "/data")))) }.isFailure)
    }
    @Test fun invalidPortAndRelativeBindAreRejected() {
        assertTrue(runCatching { validateContainerDraft(valid().copy(ports = listOf(PortMapping("70000", "8080")))) }.isFailure)
        assertTrue(runCatching { validateContainerDraft(valid().copy(mounts = listOf(ContainerMount("relative", "/data")))) }.isFailure)
        validateContainerDraft(valid().copy(ports = listOf(PortMapping("80", ""))))
    }
    @Test fun conflictingHostBindingsAreRejected() {
        assertTrue(runCatching { validateContainerDraft(valid().copy(ports = listOf(PortMapping("80", "8080"), PortMapping("81", "8080")))) }.isFailure)
    }
}
