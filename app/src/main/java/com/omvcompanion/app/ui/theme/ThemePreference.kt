package com.omvcompanion.app.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

data class ThemePreference(val mode: String, val setMode: (String) -> Unit)
val LocalThemePreference = staticCompositionLocalOf { ThemePreference("System") {} }

@Composable fun AppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("appearance", Context.MODE_PRIVATE) }
    var mode by remember { mutableStateOf(preferences.getString("theme", "System") ?: "System") }
    val preference = ThemePreference(mode) { next -> mode = next; preferences.edit().putString("theme", next).apply() }
    val dark = when(mode) { "Dark" -> true; "Light" -> false; else -> isSystemInDarkTheme() }
    val view = androidx.compose.ui.platform.LocalView.current
    val activity = androidx.activity.compose.LocalActivity.current
    SideEffect {
        activity?.window?.let { window ->
            androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalThemePreference provides preference) {
        OmvTheme(darkTheme = dark, content = content)
    }
}

@Composable fun ThemeChooser(onClose: () -> Unit) {
    val preference = LocalThemePreference.current
    AlertDialog(onDismissRequest = onClose, title = { Text("Appearance") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Choose the app theme. System follows your tablet setting.")
            listOf("System", "Light", "Dark").forEach { mode ->
                Row(Modifier.fillMaxWidth()) {
                    RadioButton(preference.mode == mode, { preference.setMode(mode) })
                    TextButton({ preference.setMode(mode) }) { Text(mode) }
                }
            }
        }
    }, confirmButton = { TextButton(onClose) { Text("Done") } })
}
