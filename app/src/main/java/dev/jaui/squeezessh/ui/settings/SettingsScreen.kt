package dev.jaui.squeezessh.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jaui.squeezessh.data.settings.AppSettings
import dev.jaui.squeezessh.data.settings.AutoLockDelay
import dev.jaui.squeezessh.data.settings.BellMode
import dev.jaui.squeezessh.data.settings.HorizontalSwipeMode
import dev.jaui.squeezessh.data.settings.KeyboardPosition
import dev.jaui.squeezessh.data.settings.ThemeMode
import dev.jaui.squeezessh.security.AppLock
import dev.jaui.squeezessh.terminal.TerminalTheme
import dev.jaui.squeezessh.terminal.TerminalView
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenSnippets: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pinInput by remember { mutableStateOf<String?>(null) }
    var confirmClearHosts by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen") },
                actions = { TextButton(onClick = onBack) { Text("Zurück") } },
            )
        },
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.message?.let { message ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(message, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = viewModel::dismissMessage) { Text("Verstanden") }
                    }
                }
            }

            Section("Allgemein") {
                ChipChoice(
                    label = "Erscheinungsbild",
                    options = listOf(
                        ThemeMode.SYSTEM to "System",
                        ThemeMode.LIGHT to "hell",
                        ThemeMode.DARK to "dunkel",
                    ),
                    selected = state.themeMode,
                    onSelect = viewModel::setThemeMode,
                )
                TextButton(onClick = onOpenSnippets) { Text("Snippets verwalten") }
            }

            Section("Terminal") {
                ChipChoice(
                    label = "Farbschema",
                    options = TerminalTheme.all.map { it.id to it.name },
                    selected = state.terminal.themeId,
                    onSelect = viewModel::setTerminalTheme,
                )

                SliderRow(
                    label = "Schriftgröße",
                    value = state.terminal.fontSizePx,
                    range = TerminalView.MIN_TEXT_SIZE_PX..TerminalView.MAX_TEXT_SIZE_PX,
                    suffix = "px",
                    onChange = viewModel::setTerminalFontSize,
                )

                SliderRow(
                    label = "Rand",
                    value = state.terminal.marginDp,
                    range = 0..AppSettings.MAX_MARGIN_DP,
                    suffix = "dp",
                    onChange = viewModel::setTerminalMargin,
                )

                ChipChoice(
                    label = "Terminal-Typ (TERM)",
                    options = AppSettings.TERMINAL_TYPES.map { it to it },
                    selected = state.terminal.terminalType,
                    onSelect = viewModel::setTerminalType,
                )

                SwitchRow(
                    label = "UTF-8",
                    description = "Ausschalten nur für Server, die kein UTF-8 sprechen.",
                    checked = state.terminal.utf8,
                    onChange = viewModel::setTerminalUtf8,
                )

                ChipChoice(
                    label = "Signalton",
                    options = listOf(
                        BellMode.NONE to "aus",
                        BellMode.VIBRATE to "vibrieren",
                        BellMode.SOUND to "Ton",
                    ),
                    selected = state.terminal.bellMode,
                    onSelect = viewModel::setBellMode,
                )

                ChipChoice(
                    label = "Zusatztasten",
                    options = listOf(
                        KeyboardPosition.BOTTOM to "unten",
                        KeyboardPosition.TOP to "oben",
                    ),
                    selected = state.terminal.keyboardPosition,
                    onSelect = viewModel::setKeyboardPosition,
                )

                ChipChoice(
                    label = "Waagerechter Wisch",
                    options = listOf(
                        HorizontalSwipeMode.NONE to "nichts",
                        HorizontalSwipeMode.SWITCH_SESSION to "Sitzung wechseln",
                        HorizontalSwipeMode.ARROW_KEYS to "Pfeiltasten",
                    ),
                    selected = state.terminal.horizontalSwipeMode,
                    onSelect = viewModel::setHorizontalSwipeMode,
                )
            }

            Section("Sicherheit") {
                SwitchRow(
                    label = "App-Sperre",
                    description = if (state.appLockEnabled) {
                        "PIN ist gesetzt. Der PIN selbst wird nicht gespeichert, nur ein " +
                            "Nachweis, aus dem er sich nicht zurückrechnen lässt."
                    } else {
                        "Verlangt beim Öffnen einen PIN oder die Biometrie des Geräts."
                    },
                    checked = state.appLockEnabled,
                    onChange = { enabled ->
                        if (enabled) pinInput = "" else viewModel.disableAppLock()
                    },
                )

                if (state.appLockEnabled) {
                    SwitchRow(
                        label = "Biometrie zuerst anbieten",
                        description = "Der PIN bleibt als Rückfallebene immer verfügbar.",
                        checked = state.biometricPreferred,
                        onChange = viewModel::setBiometricPreferred,
                    )
                    ChipChoice(
                        label = "Automatisch sperren",
                        options = AutoLockDelay.entries.map { it to it.label },
                        selected = state.autoLockDelay,
                        onSelect = viewModel::setAutoLockDelay,
                    )
                    TextButton(onClick = { pinInput = "" }) { Text("PIN ändern") }
                }

                SwitchRow(
                    label = "Agent-Weiterleitung",
                    description = "Erlaubt Programmen auf dem Server, die eigenen Schlüssel " +
                        "für weitere Sprünge zu benutzen. Nur bei Servern einschalten, " +
                        "denen man vertraut.",
                    checked = state.sshAgentEnabled,
                    onChange = viewModel::setSshAgentEnabled,
                )

                TextButton(onClick = { confirmClearHosts = true }) {
                    Text("Bekannte Host-Keys löschen")
                }
            }

            Section("Mosh") {
                Text(
                    "Mosh wird noch nicht ausgeführt — der Befehl wird nur mitgeführt.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = state.moshServerCommand,
                    onValueChange = viewModel::setMoshServerCommand,
                    label = { Text("mosh-server-Befehl") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    pinInput?.let { current ->
        AlertDialog(
            onDismissRequest = { pinInput = null },
            title = { Text("PIN festlegen") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Mindestens ${AppLock.MIN_PIN_LENGTH} Stellen.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = current,
                        onValueChange = { pinInput = it.filter(Char::isDigit) },
                        label = { Text("PIN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword,
                        ),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = current.length >= AppLock.MIN_PIN_LENGTH,
                    onClick = {
                        viewModel.setPin(current)
                        pinInput = null
                    },
                ) { Text("Festlegen") }
            },
            dismissButton = {
                TextButton(onClick = { pinInput = null }) { Text("Abbrechen") }
            },
        )
    }

    if (confirmClearHosts) {
        AlertDialog(
            onDismissRequest = { confirmClearHosts = false },
            title = { Text("Alle Host-Keys löschen?") },
            text = {
                Text(
                    "Danach fragt die App bei jedem Server erneut nach dem Fingerprint. " +
                        "Ein zwischenzeitlich ausgetauschter Schlüssel fällt dann nicht " +
                        "mehr auf — das ist der Preis.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearKnownHosts()
                        confirmClearHosts = false
                    },
                ) { Text("Löschen") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearHosts = false }) { Text("Abbrechen") }
            },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipChoice(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            options.forEach { (value, text) ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    label = { Text(text) },
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Int,
    range: IntRange,
    suffix: String,
    onChange: (Int) -> Unit,
) {
    Column {
        Text("$label: $value $suffix", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}
