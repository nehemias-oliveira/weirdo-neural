package com.weirdo.neural.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.weirdo.neural.screen.chat.ChatScreen
import com.weirdo.neural.screen.settings.SettingsScreen
import com.weirdo.neural.screen.terminal.TerminalScreen

private data class NavItem(val route: Route, val label: String, val icon: ImageVector)

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val items = listOf(
        NavItem(Route.Chat, "Chat", Icons.Filled.Chat),
        NavItem(Route.Terminal, "Terminal", Icons.Filled.Terminal),
        NavItem(Route.Settings, "Settings", Icons.Filled.Settings),
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                val backStack by navController.currentBackStackEntryAsState()
                val current = backStack?.destination
                items.forEach { item ->
                    val selected = current?.hierarchy?.any { it.route == item.route.path } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(item.route.path) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Route.Chat.path,
            modifier = Modifier.padding(padding),
        ) {
            composable(Route.Chat.path) { ChatScreen() }
            composable(Route.Terminal.path) { TerminalScreen() }
            composable(Route.Settings.path) { SettingsScreen() }
        }
    }
}
