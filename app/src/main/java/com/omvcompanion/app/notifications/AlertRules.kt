package com.omvcompanion.app.notifications

import com.omvcompanion.app.data.repo.FileSystem
import com.omvcompanion.app.data.repo.SmartData

data class ServerAlert(val key: String, val title: String, val message: String)
internal fun storageAlerts(files: List<FileSystem>, threshold: Int = 90): List<ServerAlert> = files.filter { it.isMounted && it.sizeBytes > 0 && it.percentage >= (threshold.takeIf { t -> t > 0 } ?: it.warningThreshold) }.map {
    ServerAlert("capacity:${it.uuid.ifBlank { it.deviceFile }}", "Storage nearly full", "${it.deviceFile} is ${it.percentage}% full. Free space or expand the volume.")
}
internal fun smartAlerts(devices: List<SmartData>): List<ServerAlert> = devices.filter {
    !it.unavailable && it.overallStatus?.uppercase() in setOf("BAD_STATUS", "BAD_SECTOR", "FAILED", "FAIL", "FAILING", "BAD", "EXCEEDED")
}.map { ServerAlert("smart:${it.deviceFile}", "Drive health needs attention", "${it.model ?: it.deviceFile}: ${it.overallStatus?.replace('_', ' ')}. Review SMART details.") }

internal fun temperatureAlerts(devices: List<SmartData>, threshold: Int): List<ServerAlert> = devices.filter {
    !it.unavailable && (it.temperatureCelsius ?: Int.MIN_VALUE) >= threshold
}.map { ServerAlert("temperature:${it.deviceFile}", "Drive temperature high", "${it.model ?: it.deviceFile}: ${it.temperatureCelsius}°C. Check cooling and airflow.") }

internal data class AlertTransitions(val fired: Set<String>, val recovered: Set<String>)
internal fun transitions(previous: Set<String>, current: Set<String>) = AlertTransitions(current - previous, previous - current)

internal fun retainedAlertKeys(previous: Set<String>, current: Set<String>, unreadable: Set<String>): Set<String> = current + (previous intersect unreadable)
