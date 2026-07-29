package dev.jaui.squeezessh.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import dev.jaui.squeezessh.ui.home.HomeScreen
import dev.jaui.squeezessh.ui.importer.ImportScreen
import dev.jaui.squeezessh.ui.terminal.TerminalScreen
import dev.jaui.squeezessh.ui.theme.SqueezeSshTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SqueezeSshTheme { SqueezeSshApp() } }
    }
}

@Composable
private fun SqueezeSshApp() {
    val navController = rememberNavController()

    Scaffold(modifier = Modifier.fillMaxSize()) { insets ->
        NavHost(
            navController = navController,
            startDestination = Route.HOME,
            modifier = Modifier.padding(insets),
        ) {
            composable(Route.HOME) {
                HomeScreen(
                    onOpenConnection = { id ->
                        navController.navigate("${Route.TERMINAL}?${Route.ARG_CONNECTION_ID}=$id")
                    },
                    onOpenImport = { navController.navigate(Route.IMPORT) },
                )
            }

            composable(Route.IMPORT) { ImportScreen() }

            // Der Verbindungs-Parameter ist optional: das Terminal ist auch ohne neues
            // Ziel erreichbar, wenn bereits Sitzungen offen sind.
            composable(
                route = "${Route.TERMINAL}?${Route.ARG_CONNECTION_ID}={${Route.ARG_CONNECTION_ID}}",
                arguments = listOf(
                    navArgument(Route.ARG_CONNECTION_ID) {
                        type = NavType.LongType
                        defaultValue = NO_CONNECTION
                    },
                ),
            ) { entry ->
                val id = entry.arguments?.getLong(Route.ARG_CONNECTION_ID) ?: NO_CONNECTION
                TerminalScreen(
                    connectionId = id.takeIf { it != NO_CONNECTION },
                    onBack = { navController.popBackStack() },
                    onAddSession = { navController.navigate(Route.HOME) },
                )
            }
        }
    }
}

/** Kein Ziel mitgegeben — das Terminal zeigt dann nur die bereits offenen Sitzungen. */
private const val NO_CONNECTION = -1L

private object Route {
    const val HOME = "home"
    const val IMPORT = "import"
    const val TERMINAL = "terminal"
    const val ARG_CONNECTION_ID = "connectionId"
}
