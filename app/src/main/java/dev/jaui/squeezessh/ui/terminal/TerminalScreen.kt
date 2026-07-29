package dev.jaui.squeezessh.ui.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jaui.squeezessh.terminal.TerminalBridge
import dev.jaui.squeezessh.terminal.TerminalTheme
import dev.jaui.squeezessh.terminal.TerminalView

@Composable
fun TerminalScreen(
    connectionId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TerminalViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var terminalView by remember { mutableStateOf<TerminalView?>(null) }
    var controlActive by remember { mutableStateOf(false) }
    var altActive by remember { mutableStateOf(false) }

    LaunchedEffect(connectionId) { viewModel.connect(connectionId) }

    // Ohne imePadding schiebt sich die Software-Tastatur ueber die Zusatztasten — und
    // ausgerechnet die braucht man, weil dort Ctrl, Esc und die Pfeile sitzen.
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .imePadding(),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (state.phase) {
                TerminalPhase.Connecting, TerminalPhase.Idle -> ConnectingIndicator(state.statusLine)

                TerminalPhase.Failed -> ConnectionFailed(state.error, onBack)

                TerminalPhase.Connected, TerminalPhase.Disconnected -> {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { viewContext ->
                            TerminalView(viewContext).also { view ->
                                terminalView = view
                                attachBridge(view, viewModel, context)
                                view.applyTheme(TerminalTheme.Dark)
                                view.showSoftKeyboard()
                            }
                        },
                    )
                    if (state.phase == TerminalPhase.Disconnected) {
                        DisconnectedBanner(state.statusLine, onBack)
                    }
                }
            }
        }

        if (state.phase == TerminalPhase.Connected) {
            ExtraKeysRow(
                controlActive = controlActive,
                altActive = altActive,
                onControlToggle = { controlActive = terminalView?.toggleControl() ?: false },
                onAltToggle = { altActive = terminalView?.toggleAlt() ?: false },
                onKey = { keyCode ->
                    terminalView?.sendKeyCode(keyCode)
                    controlActive = false
                    altActive = false
                },
                onText = { text ->
                    terminalView?.sendText(text)
                    controlActive = false
                    altActive = false
                },
            )
        }
    }

    state.hostKeyRequest?.let { request ->
        HostKeyDialog(
            request = request,
            onAnswer = viewModel::answerHostKey,
        )
    }

    DisposableEffect(Unit) {
        onDispose { terminalView?.detach() }
    }
}

/**
 * Haengt die View an die Sitzung.
 *
 * Der Bridge wird erst hier erzeugt, weil er die Groesse braucht — und die steht erst
 * fest, wenn die View gemessen wurde. Bis dahin gelten 80×24, was der Server nach dem
 * ersten `updateSize` sofort korrigiert bekommt.
 */
private fun attachBridge(view: TerminalView, viewModel: TerminalViewModel, context: Context) {
    val bridge = viewModel.attachTerminal { session ->
        TerminalBridge(
            shell = session.shell,
            columns = 80,
            rows = 24,
            cellWidthPixels = 0,
            cellHeightPixels = 0,
            listener = object : TerminalBridge.Listener {
                override fun onScreenUpdated() = view.onScreenUpdated()

                override fun onTitleChanged(title: String?) = Unit

                override fun onBell() {
                    view.performHapticFeedback(
                        android.view.HapticFeedbackConstants.LONG_PRESS,
                    )
                }

                override fun onCopyToClipboard(text: String) {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("Terminal", text))
                }

                override fun onPasteRequested() {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard?.primaryClip?.getItemAt(0)?.text?.toString()?.let(view::sendText)
                }

                override fun onFinished(reason: String?) = viewModel.onSessionFinished(reason)
            },
        )
    } ?: return
    view.attach(bridge)
}

@Composable
private fun ConnectingIndicator(status: String?) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        status?.let {
            Text(
                it,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@Composable
private fun ConnectionFailed(error: String?, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            error ?: "Verbindung fehlgeschlagen",
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) { Text("Zurück") }
    }
}

@Composable
private fun DisconnectedBanner(reason: String?, onBack: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xAA000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                reason ?: "Verbindung beendet",
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) { Text("Zurück") }
        }
    }
}

/**
 * Die Zusatztasten, die auf einer Software-Tastatur fehlen.
 *
 * Ctrl und Alt sind „klebrig": ein Antippen gilt fuer genau den naechsten Anschlag. Ohne
 * das waere Strg-C auf einem Touchscreen nicht tippbar.
 */
@Composable
private fun ExtraKeysRow(
    controlActive: Boolean,
    altActive: Boolean,
    onControlToggle: () -> Unit,
    onAltToggle: () -> Unit,
    onKey: (Int) -> Unit,
    onText: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = controlActive,
            onClick = onControlToggle,
            label = { Text("CTRL", fontFamily = FontFamily.Monospace) },
        )
        FilterChip(
            selected = altActive,
            onClick = onAltToggle,
            label = { Text("ALT", fontFamily = FontFamily.Monospace) },
        )
        KeyChip("ESC") { onKey(KeyEvent.KEYCODE_ESCAPE) }
        KeyChip("TAB") { onKey(KeyEvent.KEYCODE_TAB) }
        KeyChip("←") { onKey(KeyEvent.KEYCODE_DPAD_LEFT) }
        KeyChip("↓") { onKey(KeyEvent.KEYCODE_DPAD_DOWN) }
        KeyChip("↑") { onKey(KeyEvent.KEYCODE_DPAD_UP) }
        KeyChip("→") { onKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
        KeyChip("HOME") { onKey(KeyEvent.KEYCODE_MOVE_HOME) }
        KeyChip("END") { onKey(KeyEvent.KEYCODE_MOVE_END) }
        KeyChip("PGUP") { onKey(KeyEvent.KEYCODE_PAGE_UP) }
        KeyChip("PGDN") { onKey(KeyEvent.KEYCODE_PAGE_DOWN) }
        KeyChip("/") { onText("/") }
        KeyChip("|") { onText("|") }
        KeyChip("-") { onText("-") }
    }
}

@Composable
private fun KeyChip(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(label, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Fragt nach, wenn ein Host-Key unbekannt ist oder sich geaendert hat.
 *
 * Der Unterschied zwischen beiden Faellen wird deutlich gemacht: beim Erstkontakt gibt es
 * nichts zu vergleichen, ein geaenderter Schluessel ist dagegen ein moeglicher Angriff.
 */
@Composable
private fun HostKeyDialog(request: HostKeyRequest, onAnswer: (Boolean) -> Unit) {
    val prompt = request.prompt
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        title = {
            Text(if (prompt.isChange) "Host-Key hat sich geändert" else "Unbekannter Server")
        },
        text = {
            Column {
                if (prompt.isChange) {
                    Text(
                        "Der Server ${prompt.host}:${prompt.port} meldet sich mit einem " +
                            "anderen Schlüssel als beim letzten Mal. Das passiert nach einer " +
                            "Neuinstallation — kann aber auch bedeuten, dass jemand die " +
                            "Verbindung umleitet.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "\nBisher: ${prompt.previousFingerprint}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        "Jetzt:  ${prompt.fingerprint}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                } else {
                    Text(
                        "${prompt.host}:${prompt.port} ist noch nicht bekannt. Prüfe den " +
                            "Fingerprint gegen den, den der Server anzeigt.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "\n${prompt.keyType.wireName}\n${prompt.fingerprint}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAnswer(true) }) {
                Text(if (prompt.isChange) "Trotzdem verbinden" else "Vertrauen")
            }
        },
        dismissButton = {
            TextButton(onClick = { onAnswer(false) }) { Text("Abbrechen") }
        },
    )
}
