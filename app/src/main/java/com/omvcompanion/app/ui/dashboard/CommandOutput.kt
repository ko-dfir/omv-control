package com.omvcompanion.app.ui.dashboard

import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay

/** Polls only available bytes, so cancellation never waits for a newline or EOF. */
internal suspend fun streamCommandOutput(input: InputStream, closed: () -> Boolean,
    exitStatus: () -> Int, disconnect: () -> Unit, line: (String) -> Unit): Int {
    val pending = ByteArrayOutputStream()
    val bytes = ByteArray(4096)
    try {
        while (true) {
            currentCoroutineContext().ensureActive()
            val available = input.available()
            if (available > 0) {
                val count = input.read(bytes, 0, minOf(bytes.size, available))
                for (i in 0 until count.coerceAtLeast(0)) {
                    if (bytes[i] == 10.toByte()) {
                        line(pending.toString("UTF-8").removeSuffix("\r")); pending.reset()
                    } else pending.write(bytes[i].toInt())
                }
            } else if (closed()) break else delay(50)
        }
        if (pending.size() > 0) line(pending.toString("UTF-8"))
        return exitStatus()
    } finally { disconnect() }
}
