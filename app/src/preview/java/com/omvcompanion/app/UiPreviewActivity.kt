package com.omvcompanion.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.omvcompanion.app.ui.dashboard.DashboardScreen
import com.omvcompanion.app.ui.theme.AppTheme
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/** Isolated sample-data entry point, compiled only into the separate preview APK. */
class UiPreviewActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        getSharedPreferences("appearance", MODE_PRIVATE).edit().putString("theme", intent.getStringExtra("theme") ?: "Dark").apply()
        com.omvcompanion.app.ui.PendingNavigation.destination.value = com.omvcompanion.app.ui.ServerDestination("demo", intent.getIntExtra("nav_target", 0).coerceIn(0, 9))
        setContent { AppTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, intent.getFloatExtra("font_scale", density.fontScale))) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Box(if (intent.getBooleanExtra("phone", false)) Modifier.width(390.dp).fillMaxHeight() else Modifier.fillMaxSize()) {
                        DashboardScreen("demo", {}, {})
                    }
                }
            }
        } }
    }
}
