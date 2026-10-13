package com.omvcompanion.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.omvcompanion.app.AppContainer
import com.omvcompanion.app.OmvApp
import java.util.Locale

/** Creates a ViewModel scoped to the current nav destination, with access to the [AppContainer]. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(
    key: String? = null,
    crossinline create: (AppContainer) -> VM,
): VM {
    val container = (LocalContext.current.applicationContext as OmvApp).container
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}

fun formatBytes(bytes: Long?): String {
    if (bytes == null) return "–"
    val units = listOf("B", "KiB", "MiB", "GiB", "TiB", "PiB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "$bytes B" else String.format(Locale.getDefault(), "%.1f %s", value, units[unit])
}

fun formatUptime(seconds: Long): String {
    val days = seconds / 86_400
    val hours = (seconds % 86_400) / 3_600
    val minutes = (seconds % 3_600) / 60
    return buildString {
        if (days > 0) append("${days}d ")
        if (days > 0 || hours > 0) append("${hours}h ")
        append("${minutes}m")
    }
}

sealed class UiState<out T> {
    object Loading : UiState<Nothing>()
    data class Success<T>(val data: T) : UiState<T>()
    data class Error(val message: String) : UiState<Nothing>()
}
