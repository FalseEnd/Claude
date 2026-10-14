package com.perppilot.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.perppilot.app.ui.screens.BacktestScreen
import com.perppilot.app.ui.screens.BotScreen
import com.perppilot.app.ui.screens.DetailScreen
import com.perppilot.app.ui.screens.EventsScreen
import com.perppilot.app.ui.screens.MarketScreen
import com.perppilot.app.ui.screens.SettingsScreen
import com.perppilot.app.ui.theme.PerpPilotTheme
import com.perppilot.app.work.MonitorService

private data class Dest(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Dest("market", "行情", Icons.Filled.Home),
    Dest("bot", "機器人", Icons.Filled.PlayArrow),
    Dest("events", "事件合約", Icons.Filled.Star),
    Dest("backtest", "回測", Icons.Filled.DateRange),
    Dest("settings", "設定", Icons.Filled.Settings),
)

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        // Resume the real-time service if the user had it on (e.g. after a reboot or app update).
        runCatching { MonitorService.sync(this) }
        val graph = graph
        setContent {
            val settings by graph.settings.settings.collectAsStateWithLifecycle()
            PerpPilotTheme(redUp = settings.redUp) {
                val nav = rememberNavController()
                val entry by nav.currentBackStackEntryAsState()
                val route = entry?.destination?.route
                Scaffold(
                    bottomBar = {
                        if (route != "detail/{instId}") {
                            NavigationBar {
                                tabs.forEach { d ->
                                    NavigationBarItem(
                                        selected = route == d.route,
                                        onClick = {
                                            nav.navigate(d.route) {
                                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        },
                                        icon = { Icon(d.icon, d.label) },
                                        label = { Text(d.label) },
                                    )
                                }
                            }
                        }
                    },
                ) { pad ->
                    NavHost(nav, startDestination = "market", modifier = Modifier.padding(pad)) {
                        composable("market") { MarketScreen(graph) { inst -> nav.navigate("detail/$inst") } }
                        composable("detail/{instId}") { e -> DetailScreen(graph, e.arguments?.getString("instId").orEmpty()) { nav.popBackStack() } }
                        composable("bot") { BotScreen(graph) { nav.navigate("settings") } }
                        composable("events") { EventsScreen(graph) }
                        composable("backtest") { BacktestScreen(graph) }
                        composable("settings") { SettingsScreen(graph) }
                    }
                }
            }
        }
    }
}
