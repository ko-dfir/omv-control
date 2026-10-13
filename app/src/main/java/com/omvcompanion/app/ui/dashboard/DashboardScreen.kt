package com.omvcompanion.app.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PendingActions
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import com.omvcompanion.app.ui.AppPreferencesDialog
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Brightness6
import com.omvcompanion.app.ui.theme.ThemeChooser
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.DirectionsBoat
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omvcompanion.app.data.repo.SystemInfo
import com.omvcompanion.app.ui.appViewModel
import com.omvcompanion.app.ui.formatBytes
import com.omvcompanion.app.ui.formatUptime
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import com.omvcompanion.app.ui.components.ControlPanel
import com.omvcompanion.app.ui.components.PageHeading
import androidx.compose.material.icons.filled.Lan
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.IconButtonDefaults

import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.AssistChip
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Folder
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(serverId: String, onBack: () -> Unit, onEditServer: () -> Unit) {
    val startupUnlock = com.omvcompanion.app.security.LocalUnlockSignIn.current
    androidx.compose.runtime.LaunchedEffect(startupUnlock.pending) { if (startupUnlock.pending) startupUnlock.consume() }
    val vm = appViewModel(key = "dashboard-$serverId") {
        DashboardViewModel(serverId, it.serverRepository, it.sessionManager, it.systemRepository)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val pendingDestination by com.omvcompanion.app.ui.PendingNavigation.destination.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    var currentTab by rememberSaveable(serverId) { mutableStateOf(0) }
    var navigationQuery by remember(serverId) { mutableStateOf("") }
    var navigationSection by remember(serverId) { mutableStateOf<String?>(null) }
    var searchOpen by remember { mutableStateOf(false) }
    fun choose(index: Int) { currentTab = index; navigationQuery = ""; navigationSection = null }
    androidx.compose.runtime.LaunchedEffect(pendingDestination, serverId) {
        pendingDestination?.let { if (it.serverId == null || it.serverId == serverId) { currentTab = it.tab; com.omvcompanion.app.ui.PendingNavigation.destination.value = null } }
    }
    var toolsOpen by remember { mutableStateOf(false) }
    var powerAction by remember { mutableStateOf<String?>(null) }
    val destinations = listOf("Overview", "Storage", "Services", "Updates", "Docker", "SSH", "Network", "Administration", "Performance", "Files")
    val destinationIcons = listOf(Icons.Filled.Home, Icons.Filled.Storage, Icons.Filled.Settings, Icons.Filled.SystemUpdate, Icons.Filled.ViewInAr, Icons.Filled.Terminal, Icons.Filled.Lan, Icons.Filled.ManageAccounts, Icons.Filled.QueryStats, Icons.Filled.Folder)
    if (searchOpen) NavigationSearchDialog(serverId, destinations, { searchOpen = false }) { hit -> currentTab = hit.tab; navigationQuery = hit.query; navigationSection = hit.section }

    LifecycleStartEffect(vm) {
        vm.start()
        onStopOrDispose { vm.stop() }
    }

    val dashboardActivity = androidx.activity.compose.LocalActivity.current
    androidx.activity.compose.BackHandler {
        when {
            searchOpen -> searchOpen = false
            toolsOpen -> toolsOpen = false
            menuOpen -> menuOpen = false
            currentTab != 0 -> choose(0)
            else -> dashboardActivity?.moveTaskToBack(true)
        }
    }
    var preferencesOpen by remember { mutableStateOf(false) }
    if (preferencesOpen) AppPreferencesDialog { preferencesOpen = false }
    var appearanceOpen by remember { mutableStateOf(false) }
    if (appearanceOpen) ThemeChooser { appearanceOpen = false }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val expanded = maxWidth >= 600.dp
    val railWidth = if (maxWidth >= 900.dp) 204.dp else 166.dp
    Row(Modifier.fillMaxSize()) {
    if (expanded) {
        Column(Modifier.width(railWidth).fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)).padding(horizontal = 10.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Surface(color = androidx.compose.ui.graphics.Color(0xFF161F26), shape = RoundedCornerShape(12.dp)) {
                androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.omvcompanion.app.R.drawable.ic_brand), "OMV Control", Modifier.padding(6.dp).size(28.dp))
            }
            Text("OMV CONTROL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            OutlinedButton({ searchOpen = true }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp)) { Icon(Icons.Filled.Search, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Search") }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            listOf(0, 8, 1, 9, 2, 3, 4, 5, 6, 7).forEach { index -> val label = destinations[index]
                Surface(color = if (currentTab == index) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().semantics { selected = currentTab == index }.clickable { choose(index) }) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(destinationIcons[index], null, Modifier.size(20.dp), tint = if (currentTab == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            }
            HorizontalDivider()
            TextButton(onClick = { preferencesOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("App preferences", style = MaterialTheme.typography.labelMedium) }
            Row {
                IconButton(onClick = { appearanceOpen = true }) { Icon(Icons.Filled.Brightness6, "Appearance", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface) }
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.Logout, "Change server", Modifier.size(20.dp)) }
            }
        }
    }
    Scaffold(
        modifier = Modifier.weight(1f),
        topBar = {
            if (currentTab != 0) {
            TopAppBar(
                title = { Text(destinations[currentTab], style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { choose(0) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to overview") }
                },
                actions = {
                    AssistChip(onClick = onBack, label = { Text(state.serverName.ifBlank { "Server" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Text("●", color = MaterialTheme.colorScheme.primary) }, trailingIcon = { Icon(Icons.Filled.ExpandMore, null, Modifier.size(18.dp)) }, modifier = Modifier.widthIn(max = 170.dp))
                    if (currentTab == 0) {
                        IconButton(onClick = vm::refresh) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Search server") }, onClick = { menuOpen = false; searchOpen = true })
                            DropdownMenuItem(text = { Text("App preferences") }, onClick = { menuOpen = false; preferencesOpen = true })
                            DropdownMenuItem(text = { Text("Appearance") }, onClick = { menuOpen = false; appearanceOpen = true })
                            DropdownMenuItem(
                                text = { Text("Change server") },
                                leadingIcon = { Icon(Icons.Outlined.Dns, null) },
                                onClick = { menuOpen = false; onBack() },
                            )
                            DropdownMenuItem(
                                text = { Text("Edit server") },
                                leadingIcon = { Icon(Icons.Filled.Edit, null) },
                                onClick = { menuOpen = false; onEditServer() },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Reboot") },
                                leadingIcon = { Icon(Icons.Filled.RestartAlt, null) },
                                onClick = { menuOpen = false; powerAction = "Reboot" },
                            )
                            DropdownMenuItem(
                                text = { Text("Shutdown") },
                                leadingIcon = { Icon(Icons.Filled.Power, null) },
                                onClick = { menuOpen = false; powerAction = "Shut down" },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Log out") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, null) },
                                onClick = { menuOpen = false; vm.logout(onBack) },
                            )
                        }
                    }
                },
            )
            }
        },
        bottomBar = {
            if (!expanded) Surface(color = MaterialTheme.colorScheme.background) {
                Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0, 1, 4, 9, -1).forEach { index ->
                        val active = if (index == -1) currentTab in listOf(8, 2, 3, 5, 6, 7) else currentTab == index
                        Box(Modifier.weight(1f)) {
                            Surface(color = if (active) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                                contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth().semantics { selected = active }.clickable { if (index == -1) toolsOpen = true else choose(index) }) {
                                Column(Modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Icon(if (index == -1) Icons.Filled.MoreVert else destinationIcons[index], null, Modifier.size(22.dp))
                                    Text(if (index == -1) "More" else if (index == 0) "Home" else destinations[index], style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                }
                            }
                            if (index == -1) DropdownMenu(expanded = toolsOpen, onDismissRequest = { toolsOpen = false }) {
                                listOf(8, 2, 3, 5, 6, 7).forEach { target -> DropdownMenuItem(text = { Text(destinations[target]) }, leadingIcon = { Icon(destinationIcons[target], null) }, onClick = { choose(target); toolsOpen = false }) }
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text("Search server") }, onClick = { toolsOpen = false; searchOpen = true })
                                DropdownMenuItem(text = { Text("Appearance") }, onClick = { toolsOpen = false; appearanceOpen = true })
                                DropdownMenuItem(text = { Text("App preferences") }, onClick = { toolsOpen = false; preferencesOpen = true })
                                DropdownMenuItem(text = { Text("Change server") }, onClick = { toolsOpen = false; onBack() })
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        val pagePadding = if (serverId == "demo") PaddingValues(bottom = padding.calculateBottomPadding()) else padding
        Column(Modifier.fillMaxSize().padding(top = if (serverId == "demo") padding.calculateTopPadding() else 0.dp)) {
        if (serverId == "demo") Text("DEMO · Sample data. Server actions are disabled.", Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(10.dp), style = MaterialTheme.typography.labelMedium)
        when (currentTab) {
            1 -> StorageScreen(serverId, pagePadding, navigationQuery, navigationSection ?: "Drives") { uuid -> navigationSection = uuid; navigationQuery = ""; currentTab = 9 }
            2 -> ServicesScreen(serverId, pagePadding, navigationQuery)
            3 -> SoftwareScreen(serverId, pagePadding)
            4 -> DockerWorkspace(serverId, pagePadding, navigationQuery) { choose(5) }
            5 -> SshScreen(serverId, pagePadding)
            6 -> SettingsScreen(serverId, pagePadding)
            7 -> ManagementScreen(serverId, pagePadding, navigationSection ?: "Users", initialSearch = navigationQuery)
            8 -> PerformanceScreen(serverId, pagePadding)
            9 -> FileBrowserScreen(serverId, pagePadding, { choose(5) }, navigationSection)
            else -> {
            val info = state.info
            when {
                state.loading && info == null -> Box(Modifier.fillMaxSize().padding(pagePadding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                info == null -> ErrorState(
                    message = state.error ?: "Unknown error",
                    modifier = Modifier.fillMaxSize().padding(pagePadding),
                    onRetry = vm::refresh,
                    onEdit = onEditServer,
                )
                else -> PullToRefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = vm::refresh,
                    modifier = Modifier.fillMaxSize().padding(top = pagePadding.calculateTopPadding(), bottom = pagePadding.calculateBottomPadding()),
                ) {
                    ControlRoomOverview(serverId, info, state, navigate = { choose(it) }, onChangeServer = onBack, onPreferences = { preferencesOpen = true }, onReviewHealth = { choose(1); navigationSection = "SMART" })
                }
            }
            }
        }
    }
    }
    }
    }
    powerAction?.let { action ->
        AlertDialog(
            onDismissRequest = { powerAction = null },
            title = { Text("$action ${state.serverName.ifBlank { "server" }}?") },
            text = { Text(if (action == "Reboot") "Connections will be interrupted while the server restarts." else "The server will go offline. You will need to power it on again to reconnect.") },
            confirmButton = { TextButton(onClick = {
                powerAction = null
                if (action == "Reboot") vm.rebootSystem() else vm.shutdownSystem()
            }) { Text(action) } },
            dismissButton = { TextButton(onClick = { powerAction = null }) { Text("Cancel") } },
        )
    }

}

@Composable
private fun ErrorState(message: String, modifier: Modifier, onRetry: () -> Unit, onEdit: () -> Unit) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.CloudOff, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
        Text("Cannot reach the server", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onEdit) { Text("Edit server") }
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}
