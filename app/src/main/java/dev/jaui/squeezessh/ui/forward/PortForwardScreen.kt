package dev.jaui.squeezessh.ui.forward

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jaui.squeezessh.data.db.entity.PortForwardEntity
import dev.jaui.squeezessh.data.model.PortForwardMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortForwardScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PortForwardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var editing by remember { mutableStateOf<PortForwardDraft?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Weiterleitungen") },
                actions = { TextButton(onClick = onBack) { Text("Zurück") } },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = PortForwardDraft() }) {
                Icon(Icons.Default.Add, contentDescription = "Neue Weiterleitung")
            }
        },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            state.error?.let { error ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(error, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = viewModel::dismissError) { Text("Verstanden") }
                    }
                }
            }

            if (!state.hasSession) {
                Text(
                    "Weiterleitungen brauchen eine offene Sitzung. Erst verbinden, dann " +
                        "hier einschalten.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 88.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.forwards, key = { it.definition.id }) { item ->
                    ForwardCard(
                        item = item,
                        canToggle = state.hasSession,
                        onToggle = { viewModel.toggle(item.definition) },
                        onDelete = { viewModel.delete(item.definition) },
                        onOpenInBrowser = {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("http://127.0.0.1:${item.definition.localPort}"),
                                    ),
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    editing?.let { draft ->
        ForwardEditorDialog(
            draft = draft,
            onChange = { editing = it },
            onSave = {
                viewModel.save(draft)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun ForwardCard(
    item: PortForwardItem,
    canToggle: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onOpenInBrowser: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        item.definition.name ?: item.definition.mode.name.lowercase(),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        item.endpoint,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = item.isRunning, onCheckedChange = { onToggle() }, enabled = canToggle)
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Löschen")
                }
            }
            if (item.isRunning && item.definition.openInBrowser) {
                TextButton(onClick = onOpenInBrowser) { Text("Im Browser öffnen") }
            }
            if (item.isRunning && item.definition.useAsProxy) {
                Text(
                    "Als Proxy eintragen: SOCKS5 auf 127.0.0.1:${item.definition.localPort}. " +
                        "Android hat keine systemweite Proxy-Einstellung für Apps — der Wert " +
                        "muss in der jeweiligen App hinterlegt werden.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ForwardEditorDialog(
    draft: PortForwardDraft,
    onChange: (PortForwardDraft) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Weiterleitung") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { onChange(draft.copy(name = it)) },
                    label = { Text("Name (optional)") },
                    singleLine = true,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PortForwardMode.entries.forEach { mode ->
                        FilterChip(
                            selected = draft.mode == mode,
                            onClick = { onChange(draft.copy(mode = mode)) },
                            label = { Text(mode.name.lowercase()) },
                        )
                    }
                }

                Text(
                    when (draft.mode) {
                        PortForwardMode.LOCAL ->
                            "Lokaler Port zeigt auf ein Ziel hinter dem Server."
                        PortForwardMode.REMOTE ->
                            "Port auf dem Server zeigt auf ein Ziel in deinem Netz."
                        PortForwardMode.DYNAMIC ->
                            "SOCKS5-Proxy auf dem lokalen Port; das Ziel bestimmt jede " +
                                "Verbindung selbst."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = draft.localPort,
                        onValueChange = { onChange(draft.copy(localPort = it.filter(Char::isDigit))) },
                        label = { Text("Lokaler Port") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                        ),
                        modifier = Modifier.width(150.dp),
                    )
                    if (draft.mode != PortForwardMode.DYNAMIC) {
                        OutlinedTextField(
                            value = draft.remotePort,
                            onValueChange = {
                                onChange(draft.copy(remotePort = it.filter(Char::isDigit)))
                            },
                            label = { Text("Zielport") },
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                            ),
                            modifier = Modifier.width(150.dp),
                        )
                    }
                }

                if (draft.mode != PortForwardMode.DYNAMIC) {
                    OutlinedTextField(
                        value = draft.targetHost,
                        onValueChange = { onChange(draft.copy(targetHost = it)) },
                        label = { Text("Zielhost") },
                        placeholder = { Text("127.0.0.1") },
                        singleLine = true,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = draft.openInBrowser,
                        onCheckedChange = { onChange(draft.copy(openInBrowser = it)) },
                    )
                    Text("  Schaltfläche „Im Browser öffnen“ anzeigen")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = draft.useAsProxy,
                        onCheckedChange = { onChange(draft.copy(useAsProxy = it)) },
                    )
                    Text("  Als Proxy kennzeichnen")
                }
            }
        },
        confirmButton = {
            TextButton(enabled = draft.isValid, onClick = onSave) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

data class PortForwardDraft(
    val id: Long = 0,
    val name: String = "",
    val mode: PortForwardMode = PortForwardMode.LOCAL,
    val localPort: String = "",
    val remotePort: String = "",
    val targetHost: String = "",
    val openInBrowser: Boolean = false,
    val useAsProxy: Boolean = false,
) {
    val isValid: Boolean
        get() {
            val local = localPort.toIntOrNull() ?: return false
            if (local !in 1..65535) return false
            if (mode == PortForwardMode.DYNAMIC) return true
            return (remotePort.toIntOrNull() ?: 0) in 1..65535 && targetHost.isNotBlank()
        }

    fun toEntity(connectionId: Long) = PortForwardEntity(
        id = id,
        name = name.takeIf { it.isNotBlank() },
        connectionId = connectionId,
        mode = mode,
        localPort = localPort.toIntOrNull() ?: 0,
        targetHost = targetHost.takeIf { it.isNotBlank() },
        remotePort = remotePort.toIntOrNull(),
        openInBrowser = openInBrowser,
        useAsProxy = useAsProxy,
        createdAt = 0,
        updatedAt = 0,
    )
}
