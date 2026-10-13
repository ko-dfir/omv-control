package com.omvcompanion.app.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun DockerWorkspace(serverId: String, padding: PaddingValues, initialSearch: String = "", onSsh: () -> Unit = {}) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().padding(padding)) {
        LaunchedEffect(initialSearch) { if (initialSearch.isNotEmpty()) tab = 0 }
        if (tab == 0) DockerScreen(serverId, PaddingValues(0.dp), initialSearch, onSsh, tabs = {
            com.omvcompanion.app.ui.components.ExpressiveTabs(listOf("Containers", "Stacks", "Images"), tab, { tab = it }, Modifier.padding(top = 12.dp))
        }) else {
            com.omvcompanion.app.ui.components.ExpressiveTabs(listOf("Containers", "Stacks", "Images"), tab, { tab = it }, Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            ManagementScreen(serverId, PaddingValues(0.dp), if (tab == 1) "Stacks" else "Images", true, showTabs = false)
        }
    }
}
