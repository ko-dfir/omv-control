package com.omvcompanion.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.omvcompanion.app.ui.dashboard.DashboardScreen
import com.omvcompanion.app.ui.servers.ServerEditScreen
import com.omvcompanion.app.ui.servers.ServerListScreen
import com.omvcompanion.app.ui.servers.LoginScreen
import kotlinx.serialization.Serializable

@Serializable
object LoginRoute

@Serializable
object ServerListRoute

@Serializable
data class ServerEditRoute(val serverId: String? = null)

@Serializable
data class DashboardRoute(val serverId: String)

@Composable
fun AppNavHost(nav: androidx.navigation.NavHostController) {
    NavHost(navController = nav, startDestination = LoginRoute) {
        composable<LoginRoute> {
            LoginScreen(onConnected = { id ->
                nav.navigate(DashboardRoute(id)) { popUpTo(LoginRoute) { inclusive = true } }
            })
        }
        composable<ServerListRoute> {
            ServerListScreen(
                onAdd = { nav.navigate(ServerEditRoute()) },
                onEdit = { id -> nav.navigate(ServerEditRoute(id)) },
                onOpen = { id -> nav.navigate(DashboardRoute(id)) { popUpTo(ServerListRoute) { inclusive = true } } },
            )
        }
        composable<ServerEditRoute> { entry ->
            ServerEditScreen(
                serverId = entry.toRoute<ServerEditRoute>().serverId,
                onDone = { nav.popBackStack() },
            )
        }
        composable<DashboardRoute> { entry ->
            val serverId = entry.toRoute<DashboardRoute>().serverId
            DashboardScreen(
                serverId = serverId,
                onBack = { nav.navigate(LoginRoute) { popUpTo<DashboardRoute> { inclusive = true } } },
                onEditServer = { nav.navigate(ServerEditRoute(serverId)) },
            )
        }
    }
}
