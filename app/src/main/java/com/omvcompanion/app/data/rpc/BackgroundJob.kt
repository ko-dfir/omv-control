package com.omvcompanion.app.data.rpc

import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

/** Reads OMV background output until completion; errors propagate instead of reporting success early. */
suspend fun OmvRpcClient.awaitJob(result: JsonElement, onOutput: (String) -> Unit = {}) {
    val filename = (result as? JsonPrimitive)?.contentOrNull
        ?: throw OmvConnectionException("The server did not return a background task identifier")
    require(filename.isNotBlank()) { "Empty background task identifier" }
    var pos = 0L
    while (true) {
        val response = call("Exec", "getOutput", buildJsonObject {
            put("filename", filename); put("pos", pos); put("length", 16384)
        }) as? JsonObject ?: throw OmvConnectionException("Invalid background task response")
        response.str("output")?.takeIf { it.isNotEmpty() }?.let(onOutput)
        pos = response.lng("pos") ?: throw OmvConnectionException("Missing background output position")
        val running = response.bool("running") ?: throw OmvConnectionException("Missing background task state")
        val pending = response.bool("pendingOutput") == true
        if (!running && !pending) return
        if (!pending) delay(1000)
    }
}
