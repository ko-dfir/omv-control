package com.omvcompanion.app.ui.dashboard

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

internal val dashboardCards = linkedMapOf("cpu" to "CPU utilization", "memory" to "Memory usage", "gpu" to "GPU monitoring", "containers" to "Running containers", "storage" to "Storage at a glance", "activity" to "Server activity", "hardware" to "Hardware details")
internal fun dashboardOrder(context: Context): List<String> {
    val order = context.getSharedPreferences("dashboard_layout", 0).getString("order", "").orEmpty().split(',').flatMap { if (it == "metrics") listOf("cpu", "memory", "containers") else listOf(it) }.filter { it in dashboardCards }.distinct()
    val result = (order + dashboardCards.keys.filter { it !in order }).toMutableList()
    if ("gpu" !in order) { result.remove("gpu"); result.add((result.indexOf("memory") + 1).coerceAtLeast(0), "gpu") }
    return result
}
internal fun dashboardHidden(context: Context): Set<String> = context.getSharedPreferences("dashboard_layout", 0).getStringSet("hidden", emptySet()).orEmpty().toSet()
@Composable internal fun DashboardLayoutDialog(close: () -> Unit, changed: () -> Unit) {
    val context = LocalContext.current
    var order by remember { mutableStateOf(dashboardOrder(context)) }
    var hidden by remember { mutableStateOf(dashboardHidden(context)) }
    fun persist() { context.getSharedPreferences("dashboard_layout", 0).edit().putString("order", order.joinToString(",")).putStringSet("hidden", hidden).apply(); changed() }
    fun move(index: Int, offset: Int) { val list = order.toMutableList(); val card = list.removeAt(index); list.add(index + offset, card); order = list; persist() }
    AlertDialog(onDismissRequest = close, title = { Text("Customize overview") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Choose which cards appear and their order. Your server name stays at the top.", style = MaterialTheme.typography.bodySmall)
            order.forEachIndexed { i, id -> Row(Modifier.fillMaxWidth()) {
                Checkbox(id !in hidden, { show -> hidden = if (show) hidden - id else hidden + id; persist() })
                Text(dashboardCards.getValue(id), Modifier.weight(1f))
                TextButton({ move(i, -1) }, enabled = i > 0) { Text("↑") }
                TextButton({ move(i, 1) }, enabled = i < order.lastIndex) { Text("↓") }
            } }
            TextButton({ order = dashboardCards.keys.toList(); hidden = emptySet(); persist() }) { Text("Restore defaults") }
        }
    }, confirmButton = { TextButton(close) { Text("Done") } })
}
