package com.omvcompanion.app.data.repo

/** BAD_STATUS alone is ambiguous: OMV also returns it when SMART probing fails. */
internal fun hasExplicitSmartFailure(report: String): Boolean {
    val health = Regex("(?im)^SMART (?:overall-health self-assessment test result|Health Status):\\s*(.+)$")
        .find(report)?.groupValues?.get(1)?.trim()
    val failed = health != null && health.isNotBlank() &&
        !health.equals("PASSED", true) && !health.equals("OK", true) &&
        !health.contains("UNKNOWN", true) && !health.contains("unavailable", true) &&
        !health.contains("unsupported", true) &&
        (health.contains("FAIL", true) || health.contains("EXCEEDED", true))
    val critical = Regex("(?im)^Critical Warning:\\s*0x([0-9a-f]+)")
        .find(report)?.groupValues?.get(1)?.toLongOrNull(16)
    return failed || (critical != null && critical > 0)
}
