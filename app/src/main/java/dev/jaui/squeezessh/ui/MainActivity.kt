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
                    onOpenConnection = { id -> navController.navigate("${Route.TERMINAL}/$id") },
                    onOpenImport = { navController.navigate(Route.IMPORT) },
                )
            }

            composable(Route.IMPORT) { ImportScreen() }

            composable(
                route = "${Route.TERMINAL}/{${Route.ARG_CONNECTION_ID}}",
                arguments = listOf(
                    navArgument(Route.ARG_CONNECTION_ID) { type = NavType.LongType },
                ),
            ) { entry ->
                TerminalScreen(
                    connectionId = entry.arguments?.getLong(Route.ARG_CONNECTION_ID) ?: 0L,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

private object Route {
    const val HOME = "home"
    const val IMPORT = "import"
    const val TERMINAL = "terminal"
    const val ARG_CONNECTION_ID = "connectionId"
}
