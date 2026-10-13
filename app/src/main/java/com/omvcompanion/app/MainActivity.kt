package com.omvcompanion.app

import android.os.Bundle
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.omvcompanion.app.ui.AppNavHost
import com.omvcompanion.app.ui.theme.AppTheme

class MainActivity : FragmentActivity() {
    private fun acceptNavigation(incoming: android.content.Intent?) {
        if (incoming?.hasExtra("nav_target") != true) return
        lifecycleScope.launch {
            try {
                val repo = (application as OmvApp).container.serverRepository
                com.omvcompanion.app.ui.PendingNavigation.accept(incoming, com.omvcompanion.app.security.AppPreferences(this@MainActivity).navigationToken, repo.servers.first().map { it.id }.toSet())
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { /* Unreadable profiles cannot authorize a destination. */ }
        }
    }
    override fun onResume() { super.onResume(); com.omvcompanion.app.widget.ServerWidget.render(this); com.omvcompanion.app.widget.ServerWidget.refresh(this) }
    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); setIntent(intent); acceptNavigation(intent) }
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        acceptNavigation(intent)
        if (com.omvcompanion.app.security.AppPreferences(this).let { it.biometricLock || it.hideContent }) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            AppTheme {
                val navigation = androidx.navigation.compose.rememberNavController()
                val screens = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
                androidx.compose.material3.Surface(androidx.compose.ui.Modifier.fillMaxSize(), color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
                    com.omvcompanion.app.security.AppLock {
                        screens.SaveableStateProvider("navigation") { AppNavHost(navigation) }
                    }
                }
            }
        }
    }
}
