package com.srvmanager.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.srvmanager.app.ui.backup.BackupScreen
import com.srvmanager.app.ui.keys.KeysScreen
import com.srvmanager.app.ui.monitor.MonitorScreen
import com.srvmanager.app.ui.portbind.PortBindScreen
import com.srvmanager.app.ui.servers.KeySetupScreen
import com.srvmanager.app.ui.servers.ServerDetailScreen
import com.srvmanager.app.ui.servers.ServerEditScreen
import com.srvmanager.app.ui.servers.ServerListScreen
import com.srvmanager.app.ui.sftp.SftpScreen
import com.srvmanager.app.ui.terminal.TerminalScreen

private fun NavHostController.back() {
    if (previousBackStackEntry != null) popBackStack()
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "servers") {
        composable("servers") {
            ServerListScreen(
                onOpen = { nav.navigate("server/$it") },
                onAdd = { nav.navigate("edit") },
                onKeys = { nav.navigate("keys") },
                onBackup = { nav.navigate("backup") },
            )
        }
        composable(
            route = "edit?id={id}",
            arguments = listOf(navArgument("id") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            }),
        ) { entry ->
            ServerEditScreen(
                serverId = entry.arguments?.getString("id"),
                onDone = { nav.back() },
                onDeleted = { nav.popBackStack("servers", inclusive = false) },
            )
        }
        composable("server/{id}") { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            ServerDetailScreen(
                serverId = id,
                onBack = { nav.back() },
                onEdit = { nav.navigate("edit?id=$id") },
                onTerminal = { nav.navigate("server/$id/terminal") },
                onSftp = { nav.navigate("server/$id/sftp") },
                onMonitor = { nav.navigate("server/$id/monitor") },
                onPortBind = { nav.navigate("server/$id/portbind") },
                onKeySetup = { nav.navigate("server/$id/keysetup") },
            )
        }
        composable("server/{id}/terminal") { entry ->
            TerminalScreen(serverId = entry.arguments?.getString("id").orEmpty(), onBack = { nav.back() })
        }
        composable("server/{id}/sftp") { entry ->
            SftpScreen(serverId = entry.arguments?.getString("id").orEmpty(), onBack = { nav.back() })
        }
        composable("server/{id}/monitor") { entry ->
            MonitorScreen(serverId = entry.arguments?.getString("id").orEmpty(), onBack = { nav.back() })
        }
        composable("server/{id}/portbind") { entry ->
            PortBindScreen(serverId = entry.arguments?.getString("id").orEmpty(), onBack = { nav.back() })
        }
        composable("server/{id}/keysetup") { entry ->
            KeySetupScreen(serverId = entry.arguments?.getString("id").orEmpty(), onBack = { nav.back() })
        }
        composable("keys") { KeysScreen(onBack = { nav.back() }) }
        composable("backup") { BackupScreen(onBack = { nav.back() }) }
    }
}
