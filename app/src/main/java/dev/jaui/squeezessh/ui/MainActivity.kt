package dev.jaui.squeezessh.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import dev.jaui.squeezessh.data.settings.AppSettings
import dev.jaui.squeezessh.data.settings.ThemeMode
import dev.jaui.squeezessh.security.AppLock
import dev.jaui.squeezessh.security.LockState
import dev.jaui.squeezessh.ui.addressbook.AddressBookScreen
import dev.jaui.squeezessh.ui.addressbook.ConnectionEditorScreen
import dev.jaui.squeezessh.ui.addressbook.ConnectionEditorViewModel
import dev.jaui.squeezessh.ui.addressbook.IdentityEditorScreen
import dev.jaui.squeezessh.ui.addressbook.IdentityEditorViewModel
import dev.jaui.squeezessh.ui.addressbook.IdentityListScreen
import dev.jaui.squeezessh.ui.forward.PortForwardScreen
import dev.jaui.squeezessh.ui.importer.ImportScreen
import dev.jaui.squeezessh.ui.lock.LockScreen
import dev.jaui.squeezessh.ui.settings.SettingsScreen
import dev.jaui.squeezessh.ui.sftp.SftpBrowserScreen
import dev.jaui.squeezessh.ui.snippets.SnippetsScreen
import dev.jaui.squeezessh.ui.terminal.TerminalScreen
import dev.jaui.squeezessh.ui.theme.SqueezeSshTheme
import dev.jaui.squeezessh.widget.ConnectionsWidgetProvider
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Erbt von [FragmentActivity] und nicht von `ComponentActivity`: `BiometricPrompt` verlangt
 * eine FragmentActivity, um seinen Dialog einzuhaengen.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var appLock: AppLock

    @Inject lateinit var settings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch { appLock.initialize() }

        setContent {
            val themeMode by settings.themeMode.collectAsStateWithLifecycle(ThemeMode.SYSTEM)
            val lockState by appLock.state.collectAsStateWithLifecycle()

            SqueezeSshTheme(
                darkTheme = when (themeMode) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                },
            ) {
                // Solange nicht entschieden ist, wird nichts gezeigt — sonst blitzt der
                // Inhalt auf, bevor der Sperrbildschirm darueberlegt.
                when (lockState) {
                    LockState.UNKNOWN -> Unit
                    LockState.LOCKED -> LockScreen()
                    LockState.UNLOCKED -> SqueezeSshApp(intent)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        appLock.onEnteredBackground()
        // Beim Verlassen ist der guenstigste Zeitpunkt fuer eine Widget-Auffrischung:
        // was der Nutzer geaendert hat, ist gespeichert, und er sieht als Naechstes
        // ohnehin den Startbildschirm.
        ConnectionsWidgetProvider.requestUpdate(this)
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch { appLock.onEnteredForeground() }
    }

    /**
     * Ein `ssh://`-Link, waehrend die App schon laeuft. Ohne das bliebe der Bildschirm
     * einfach auf dem stehen, was gerade offen war.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }
}

@Composable
private fun SqueezeSshApp(intent: Intent?) {
    val navController = rememberNavController()
    val handledIntent = remember { mutableStateOf(false) }

    LaunchedEffect(intent) {
        if (handledIntent.value) return@LaunchedEffect
        handledIntent.value = true

        // Aus dem Widget kommt eine Verbindungs-ID, von aussen ein ssh://-Link. Die ID
        // fuehrt direkt ins Terminal, weil die Verbindung vollstaendig hinterlegt ist.
        val fromWidget = intent
            ?.getLongExtra(ConnectionsWidgetProvider.EXTRA_CONNECTION_ID, NO_CONNECTION)
            ?.takeIf { it != NO_CONNECTION }
        when {
            fromWidget != null -> navController.openTerminal(fromWidget)
            else -> intent?.data?.toString()?.let { navController.openQuickConnect(it) }
        }
    }

    NavHost(
        navController = navController,
        startDestination = Route.ADDRESS_BOOK,
        modifier = Modifier.fillMaxSize(),
    ) {
        composable(Route.ADDRESS_BOOK) {
            AddressBookScreen(
                onOpenConnection = { id -> navController.openTerminal(id) },
                onEditConnection = { id ->
                    navController.navigate("${Route.CONNECTION_EDITOR}?connectionId=$id")
                },
                onNewConnection = { navController.navigate(Route.CONNECTION_EDITOR) },
                onOpenIdentities = { navController.navigate(Route.IDENTITIES) },
                onOpenImport = { navController.navigate(Route.IMPORT) },
                onOpenSessions = { navController.openTerminal(null) },
                onOpenSettings = { navController.navigate(Route.SETTINGS) },
                onOpenSnippets = { navController.navigate(Route.SNIPPETS) },
            )
        }

        composable(Route.IMPORT) { ImportScreen() }

        composable(Route.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenSnippets = { navController.navigate(Route.SNIPPETS) },
            )
        }

        composable(Route.SNIPPETS) {
            SnippetsScreen(onBack = { navController.popBackStack() })
        }

        composable(Route.SFTP) {
            SftpBrowserScreen(onBack = { navController.popBackStack() })
        }

        composable(Route.FORWARDS) {
            PortForwardScreen(onBack = { navController.popBackStack() })
        }

        composable(Route.IDENTITIES) {
            IdentityListScreen(
                onEditIdentity = { id ->
                    navController.navigate("${Route.IDENTITY_EDITOR}?identityId=$id")
                },
                onNewIdentity = { navController.navigate(Route.IDENTITY_EDITOR) },
            )
        }

        composable(
            route = "${Route.CONNECTION_EDITOR}?connectionId={connectionId}&prefill={prefill}",
            arguments = listOf(
                navArgument("connectionId") {
                    type = NavType.LongType
                    defaultValue = ConnectionEditorViewModel.NEW
                },
                // Vorbelegung aus einem ssh://-Link.
                navArgument("prefill") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) {
            ConnectionEditorScreen(
                onDone = { navController.popBackStack() },
                onNewIdentity = { navController.navigate(Route.IDENTITY_EDITOR) },
            )
        }

        composable(
            route = "${Route.IDENTITY_EDITOR}?identityId={identityId}",
            arguments = listOf(
                navArgument("identityId") {
                    type = NavType.LongType
                    defaultValue = IdentityEditorViewModel.NEW
                },
            ),
        ) {
            IdentityEditorScreen(onDone = { navController.popBackStack() })
        }

        // Der Verbindungs-Parameter ist optional: das Terminal ist auch ohne neues Ziel
        // erreichbar, wenn bereits Sitzungen offen sind.
        composable(
            route = "${Route.TERMINAL}?connectionId={connectionId}",
            arguments = listOf(
                navArgument("connectionId") {
                    type = NavType.LongType
                    defaultValue = NO_CONNECTION
                },
            ),
        ) { entry ->
            val id = entry.arguments?.getLong("connectionId") ?: NO_CONNECTION
            TerminalScreen(
                connectionId = id.takeIf { it != NO_CONNECTION },
                onBack = { navController.popBackStack() },
                onAddSession = { navController.navigate(Route.ADDRESS_BOOK) },
                onOpenFiles = { navController.navigate(Route.SFTP) },
                onOpenForwards = { navController.navigate(Route.FORWARDS) },
            )
        }
    }
}

private fun NavHostController.openTerminal(connectionId: Long?) {
    navigate("${Route.TERMINAL}?connectionId=${connectionId ?: NO_CONNECTION}")
}

/**
 * Ein von aussen hereingereichtes Ziel landet im Editor, nicht direkt im Terminal:
 * ohne hinterlegte Identitaet fehlt der Benutzername, und der Verbindungsversuch
 * scheiterte sonst mit einem Authentifizierungsfehler statt mit einer Nachfrage.
 */
private fun NavHostController.openQuickConnect(uri: String) {
    navigate("${Route.CONNECTION_EDITOR}?prefill=${android.net.Uri.encode(uri)}")
}

/** Kein Ziel mitgegeben — das Terminal zeigt dann nur die bereits offenen Sitzungen. */
private const val NO_CONNECTION = -1L

private object Route {
    const val ADDRESS_BOOK = "addressbook"
    const val IMPORT = "import"
    const val IDENTITIES = "identities"
    const val CONNECTION_EDITOR = "connection"
    const val IDENTITY_EDITOR = "identity"
    const val TERMINAL = "terminal"
    const val SFTP = "sftp"
    const val FORWARDS = "forwards"
    const val SETTINGS = "settings"
    const val SNIPPETS = "snippets"
}
