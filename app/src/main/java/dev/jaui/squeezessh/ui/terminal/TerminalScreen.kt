package dev.jaui.squeezessh.ui.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jaui.squeezessh.terminal.TerminalTabs
import dev.jaui.squeezessh.terminal.TerminalTheme
import dev.jaui.squeezessh.terminal.TerminalView

@Composable
fun TerminalScreen(
    connectionId: Long?,
    onBack: () -> Unit,
    onAddSession: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TerminalViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val tabs by viewModel.tabs.tabs.collectAsStateWithLifecycle()
    val activeTabId by viewModel.tabs.activeTabId.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var terminalView by remember { mutableStateOf<TerminalView?>(null) }
    var controlActive by remember { mutableStateOf(false) }
    var altActive by remember { mutableStateOf(false) }
    var selectedText by remember { mutableStateOf<String?>(null) }

    // Ein per Navigation mitgegebenes Ziel wird genau einmal geoeffnet — sonst entstuende
    // bei jeder Neuzusammensetzung eine weitere Sitzung.
    LaunchedEffect(connectionId) { connectionId?.let(viewModel::openTab) }

    val activeTab = tabs.firstOrNull { it.id == activeTabId }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .imePadding(),
    ) {
        // Die Tab-Leiste liegt bewusst **ueber** dem Terminal statt darueber im Column:
        // die eingebettete View wird als echte View nach dem Compose-Inhalt gezeichnet
        // und wuerde eine davor liegende Leiste sonst verdecken. Das Terminal bekommt
        // dafuer oben denselben Abstand, damit nichts unter der Leiste verschwindet.
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds(),
        ) {
            when {
                state.connecting -> ConnectingIndicator(state.statusLine)

                state.error != null && tabs.isEmpty() ->
                    ConnectionFailed(state.error, onBack, viewModel::dismissError)

                activeTab == null -> NoSessions(onBack)

                else -> {
                    TerminalSurface(
                        modifier = Modifier.padding(top = if (tabs.isEmpty()) 0.dp else TAB_STRIP_HEIGHT),
                        tab = activeTab,
                        onViewReady = { terminalView = it },
                        onSelectionChanged = { selectedText = it },
                        readClipboard = {
                            context.getSystemService(ClipboardManager::class.java)
                                ?.primaryClip?.getItemAt(0)?.text?.toString()
                        },
                        writeClipboard = { text ->
                            context.getSystemService(ClipboardManager::class.java)
                                ?.setPrimaryClip(ClipData.newPlainText("Terminal", text))
                        },
                    )

                    val alive by activeTab.isAlive.collectAsStateWithLifecycle()
                    if (!alive) {
                        val reason by activeTab.endedReason.collectAsStateWithLifecycle()
                        DisconnectedBanner(reason) { viewModel.closeTab(activeTab.id) }
                    }
                }
            }

            // Auch die Auswahlleiste liegt ueber dem Terminal statt darunter im Column:
            // wuerde sie Platz wegnehmen, aendert sich die Zeilenzahl, das Terminal
            // vermisst sich neu — und verwuerfe dabei genau die Auswahl, die sie anzeigt.
            selectedText?.let { text ->
                SelectionBar(
                    modifier = Modifier.align(Alignment.BottomStart),
                    characters = text.length,
                    onCopy = {
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("Terminal", text))
                        terminalView?.clearSelection()
                    },
                    onPaste = { terminalView?.sendText(text) },
                    onDismiss = { terminalView?.clearSelection() },
                )
            }

            if (tabs.isNotEmpty()) {
                TabStrip(
                    modifier = Modifier.align(Alignment.TopStart),
                    tabs = tabs,
                    activeTabId = activeTabId,
                    onSelect = viewModel::activateTab,
                    onClose = viewModel::closeTab,
                    onAdd = onAddSession,
                )
            }
        }

        if (activeTab != null) {
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
        HostKeyDialog(request = request, onAnswer = viewModel::answerHostKey)
    }
}

/**
 * Bindet die View an den aktiven Tab.
 *
 * Beim Wechsel wird dieselbe View auf einen anderen [dev.jaui.squeezessh.terminal.TerminalBridge]
 * umgehaengt. Der Bildschirminhalt kommt aus dessen Emulator und ist deshalb sofort da —
 * auch das, was ankam, waehrend der Tab im Hintergrund lag.
 */
@Composable
private fun TerminalSurface(
    modifier: Modifier = Modifier,
    tab: TerminalTabs.Tab,
    onViewReady: (TerminalView) -> Unit,
    onSelectionChanged: (String?) -> Unit,
    readClipboard: () -> String?,
    writeClipboard: (String) -> Unit,
) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { viewContext ->
            TerminalView(viewContext).also { view ->
                view.applyTheme(TerminalTheme.Dark)
                view.onSelectionChanged = onSelectionChanged
                onViewReady(view)
            }
        },
        update = { view ->
            if (view.bridge !== tab.bridge) {
                view.attach(tab.bridge)
                view.applyTheme(TerminalTheme.Dark)
                view.showSoftKeyboard()
            }
            // Nur der sichtbare Tab zeichnet neu. Die uebrigen sammeln weiter Ausgabe in
            // ihrem Emulator, ohne die Oberflaeche zu beschaeftigen.
            tab.onScreenUpdated = view::onScreenUpdated
            tab.onBell = {
                view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            }
            tab.onClipboardRead = readClipboard
            tab.onClipboardWrite = writeClipboard
        },
    )

    DisposableEffect(tab.id) {
        onDispose {
            tab.onScreenUpdated = null
            tab.onBell = null
            tab.onClipboardRead = null
            tab.onClipboardWrite = null
        }
    }
}

@Composable
private fun TabStrip(
    modifier: Modifier = Modifier,
    tabs: List<TerminalTabs.Tab>,
    activeTabId: Long?,
    onSelect: (Long) -> Unit,
    onClose: (Long) -> Unit,
    onAdd: () -> Unit,
) {
    // Bewusst kein Material3-Tab: das bezieht seine Farben aus dem Umfeld einer TabRow und
    // waere hier, auf schwarzem Terminal-Hintergrund, unsichtbar. Die Leiste bringt ihre
    // Farben deshalb selbst mit.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(TAB_STRIP_HEIGHT)
            .background(TAB_STRIP_BACKGROUND)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tabs.forEach { tab ->
            val title by tab.title.collectAsStateWithLifecycle()
            val alive by tab.isAlive.collectAsStateWithLifecycle()
            val active = tab.id == activeTabId

            Row(
                modifier = Modifier
                    .background(
                        color = if (active) TAB_ACTIVE_BACKGROUND else TAB_STRIP_BACKGROUND,
                        shape = MaterialTheme.shapes.small,
                    )
                    .clickable { onSelect(tab.id) }
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (alive) title else "$title · beendet",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) TAB_ACTIVE_TEXT else TAB_INACTIVE_TEXT,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                IconButton(onClick = { onClose(tab.id) }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Sitzung schließen",
                        tint = if (active) TAB_ACTIVE_TEXT else TAB_INACTIVE_TEXT,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        IconButton(onClick = onAdd) {
            Icon(
                Icons.Default.Add,
                contentDescription = "Weitere Sitzung",
                tint = TAB_ACTIVE_TEXT,
            )
        }
    }
}

private val TAB_STRIP_HEIGHT = 44.dp
private val TAB_STRIP_BACKGROUND = Color(0xFF263238)
private val TAB_ACTIVE_BACKGROUND = Color(0xFF37474F)
private val TAB_ACTIVE_TEXT = Color(0xFFFFFFFF)
private val TAB_INACTIVE_TEXT = Color(0xFFB0BEC5)

@Composable
private fun SelectionBar(
    modifier: Modifier = Modifier,
    characters: Int,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("$characters Zeichen", style = MaterialTheme.typography.labelMedium)
        TextButton(onClick = onCopy) {
            Icon(Icons.Default.ContentCopy, contentDescription = null, Modifier.size(16.dp))
            Text(" Kopieren")
        }
        // „Senden" schickt die Auswahl zurueck in die Shell — praktisch, um einen
        // ausgegebenen Pfad direkt weiterzuverwenden.
        TextButton(onClick = onPaste) { Text("Senden") }
        TextButton(onClick = onDismiss) { Text("Abbrechen") }
    }
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
private fun NoSessions(onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Keine offene Sitzung", color = Color.White)
        Button(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) {
            Text("Zu den Verbindungen")
        }
    }
}

@Composable
private fun ConnectionFailed(error: String?, onBack: () -> Unit, onDismiss: () -> Unit) {
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
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onDismiss) { Text("Verstanden") }
            Button(onClick = onBack) { Text("Zurück") }
        }
    }
}

@Composable
private fun DisconnectedBanner(reason: String?, onClose: () -> Unit) {
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
            Button(onClick = onClose, modifier = Modifier.padding(top = 16.dp)) {
                Text("Tab schließen")
            }
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
