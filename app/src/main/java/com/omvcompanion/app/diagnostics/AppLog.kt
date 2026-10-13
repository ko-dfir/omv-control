package com.omvcompanion.app.diagnostics

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun redactLog(text: String): String = text
    .replace(Regex("(?i)(password|token|authorization|cookie|secret)(\\s*[:=]\\s*)[^\\s,;]+"), "$1$2[redacted]")
    .replace(Regex("(?i)(https?://)[^/@\\s]+:[^/@\\s]+@"), "$1[redacted]@")

object AppLog {
    private lateinit var file: File
    fun init(context: Context) {
        file = File(context.filesDir, "logs/app.log"); file.parentFile?.mkdirs()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            write("Crash", error.javaClass.simpleName + "\n" + error.stackTrace.joinToString("\n"))
            previous?.uncaughtException(thread, error)
        }
    }
    @Synchronized fun write(tag: String, message: String) {
        if (!::file.isInitialized) return
        runCatching {
            if (file.length() > 256_000) file.writeText(file.readText().takeLast(100_000))
            file.appendText("${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())} [$tag] ${redactLog(message)}\n")
        }
    }
    @Synchronized fun read(): String = if (::file.isInitialized && file.exists()) file.readText().takeLast(100_000) else "No diagnostic events recorded."
    @Synchronized fun clear() { if (::file.isInitialized) file.writeText("") }
    fun export(context: Context): File = File(context.cacheDir, "diagnostics/omv-control-log.txt").also {
        it.parentFile?.mkdirs()
        it.writeText("OMV Control ${com.omvcompanion.app.BuildConfig.VERSION_NAME}\nAndroid ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n\n${read()}")
    }
}
