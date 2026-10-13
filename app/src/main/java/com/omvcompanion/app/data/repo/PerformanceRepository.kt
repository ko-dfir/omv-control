package com.omvcompanion.app.data.repo

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import com.omvcompanion.app.data.rpc.dbl

@Serializable
data class PerformanceSample(val time: Long, val cpu: Double? = null, val memory: Double? = null,
    val temperature: Double? = null, val load: Double? = null, val received: Double? = null,
    val sent: Double? = null, val diskRead: Double? = null, val diskWritten: Double? = null)

data class PerformanceCounters(val uptime: Double, val received: Double?, val sent: Double?, val diskRead: Double?, val diskWritten: Double?)
internal fun throughput(previous: PerformanceCounters?, current: PerformanceCounters): List<Double?> {
    val elapsed = current.uptime - (previous?.uptime ?: current.uptime)
    if (previous == null || elapsed <= 0 || elapsed > 300) return List(4) { null }
    fun rate(currentValue: Double?, previousValue: Double?): Double? {
        if (currentValue == null || previousValue == null || currentValue < previousValue) return null
        return (currentValue - previousValue) / elapsed / 1_048_576
    }
    return listOf(rate(current.received, previous.received), rate(current.sent, previous.sent), rate(current.diskRead, previous.diskRead), rate(current.diskWritten, previous.diskWritten))
}

internal fun retainedSamples(samples: List<PerformanceSample>, now: Long): List<PerformanceSample> =
    samples.filter { it.time >= now - 7 * 86_400_000L && it.time <= now }.sortedBy { it.time }.takeLast(20_200)

internal fun performanceValue(sample: PerformanceSample, metric: String): Double? = when (metric) {
    "CPU" -> sample.cpu; "RAM" -> sample.memory; "Temperature" -> sample.temperature; "Network received" -> sample.received
    "Network sent" -> sample.sent; "Disk read" -> sample.diskRead; "Disk written" -> sample.diskWritten; else -> sample.load
}?.takeIf { it.isFinite() }

/** Per-server local history; only successfully observed values are recorded. */
class PerformanceRepository(context: Context, private val servers: ServerRepository? = null, private val ssh: SshRunner? = null) {
    private val directory = File(context.filesDir, "performance").apply { mkdirs() }
    private val lock = Mutex()
    private val flows = mutableMapOf<String, MutableStateFlow<List<PerformanceSample>>>()
    private val serializer = ListSerializer(PerformanceSample.serializer())
    private val counters = mutableMapOf<String, PerformanceCounters>()
    private val sampler = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sampling = java.util.concurrent.ConcurrentHashMap<String, Job>()
    /** Never hold a dashboard request open for SSH or disk writes. Skip overlapping samples. */
    fun recordLater(id: String, info: SystemInfo) {
        sampling.compute(id) { _, current ->
            if (current != null && !current.isCompleted) current else sampler.launch(start = CoroutineStart.LAZY) {
                try { record(id, info) } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* History failures cannot interrupt server controls. */ }
            }
        }?.start()
    }
    private fun file(id: String): AtomicFile {
        val name = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
        return AtomicFile(File(directory, "$name.json"))
    }
    suspend fun observe(id: String): StateFlow<List<PerformanceSample>> = withContext(Dispatchers.IO) { lock.withLock {
        flows.getOrPut(id) {
            val samples = if (id == "demo") demoSamples() else runCatching {
                file(id).openRead().use { Json.decodeFromString(serializer, it.reader().readText()) }
            }.getOrDefault(emptyList())
            MutableStateFlow(retainedSamples(samples, System.currentTimeMillis()))
        }
    } }
    suspend fun record(id: String, info: SystemInfo) {
        if (id == "demo") return
        observe(id)
        withContext(Dispatchers.IO) {
          val due = lock.withLock { System.currentTimeMillis() - (flows.getValue(id).value.lastOrNull()?.time ?: 0) >= 30_000 }
          if (!due) return@withContext
          val observed = readCounters(id)
          lock.withLock {
            val flow = flows.getValue(id)
            val now = System.currentTimeMillis()
            if (now - (flow.value.lastOrNull()?.time ?: 0) < 30_000) return@withLock
            val rates = observed?.let { throughput(counters[id], it).also { counters[id] = observed } } ?: List(4) { null }
            val sample = PerformanceSample(now, info.cpuUsagePercent?.coerceIn(0.0, 100.0),
                info.memUsedFraction?.times(100)?.toDouble(), info.cpuTemp, info.load1, rates[0], rates[1], rates[2], rates[3])
            val samples = retainedSamples(flow.value + sample, now)
            val target = file(id)
            val stream = target.startWrite()
            try { stream.write(Json.encodeToString(serializer, samples).toByteArray()); target.finishWrite(stream); flow.value = samples }
            catch (e: Exception) { target.failWrite(stream); throw e }
          }
        }
    }
    suspend fun clear(id: String) = withContext(Dispatchers.IO) { observe(id); lock.withLock {
        if (id != "demo") file(id).delete()
        counters.remove(id)
        flows.getValue(id).value = if (id == "demo") demoSamples() else emptyList()
    } }
    private fun demoSamples(): List<PerformanceSample> {
        val now = System.currentTimeMillis()
        return (0..576).map { i -> PerformanceSample(now - (576 - i) * 300_000L,
            18 + 11 * sin(i / 9.0) + 5 * sin(i / 2.0), 32 + 7 * sin(i / 35.0),
            40 + 5 * sin(i / 9.0), 0.5 + 0.3 * sin(i / 9.0), 4 + 3 * sin(i / 9.0), 1 + .8 * sin(i / 13.0), 15 + 10 * sin(i / 9.0), 7 + 6 * sin(i / 9.0)) }
    }
    private suspend fun readCounters(id: String): PerformanceCounters? {
        val profile = try { servers?.get(id) } catch (e: CancellationException) { throw e } catch (_: Exception) { return null } ?: return null
        if (profile.sshFingerprint == null || profile.sshUsername == null || ssh == null) return null
        return try {
            withTimeout(15_000) {
                val script = """
import json,os
rx=tx=read=written=0
interfaces=disks=0
for line in open('/proc/net/dev'):
 if ':' not in line: continue
 name,data=line.split(':',1); name=name.strip(); fields=data.split()
 if os.path.exists('/sys/class/net/'+name+'/device'):
  rx+=int(fields[0]); tx+=int(fields[8]); interfaces+=1
for line in open('/proc/diskstats'):
 f=line.split()
 if os.path.exists('/sys/block/'+f[2]+'/device'):
  read+=int(f[5])*512; written+=int(f[9])*512; disks+=1
print(json.dumps({'uptime':float(open('/proc/uptime').read().split()[0]),'received':rx if interfaces else None,'sent':tx if interfaces else None,'read':read if disks else None,'written':written if disks else None}))
""".trimIndent()
                val code = java.util.Base64.getEncoder().encodeToString(script.toByteArray())
                val result = ssh.execute(id, "python3 -c \"import base64;exec(base64.b64decode('$code'))\"", timeoutMs = 8_000)
                if (result.exitCode != 0) return@withTimeout null
                val o = Json.parseToJsonElement(result.output).jsonObject
                PerformanceCounters(o.dbl("uptime") ?: return@withTimeout null, o.dbl("received"), o.dbl("sent"), o.dbl("read"), o.dbl("written"))
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) { null }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
    }
}
