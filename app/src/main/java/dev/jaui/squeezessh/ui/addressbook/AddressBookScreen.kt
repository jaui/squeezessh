package dev.jaui.squeezessh.ui.addressbook

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jaui.squeezessh.data.db.entity.ConnectionEntity
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddressBookScreen(
    onOpenConnection: (Long) -> Unit,
    onEditConnection: (Long) -> Unit,
    onNewConnection: () -> Unit,
    onOpenIdentities: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenSessions: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AddressBookViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var pendingDelete by remember { mutableStateOf<ConnectionEntity?>(null) }
    var dependents by remember { mutableStateOf<List<ConnectionEntity>>(emptyList()) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Verbindungen") },
                actions = {
                    TextButton(onClick = onOpenSessions) { Text("Sitzungen") }
                    TextButton(onClick = onOpenIdentities) { Text("Identitäten") }
                    TextButton(onClick = onOpenImport) { Text("Import") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNewConnection) {
                Icon(Icons.Default.Add, contentDescription = "Neue Verbindung")
            }
        },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            QuickConnectField(
                query = state.query,
                onQueryChanged = viewModel::onQueryChanged,
                onSubmit = { input ->
                    scope.launch {
                        viewModel.createFromQuickConnect(input)?.let { id ->
                            viewModel.onQueryChanged("")
                            onEditConnection(id)
                        }
                    }
                },
            )

            if (state.isEmpty) {
                EmptyHint(hasQuery = state.query.isNotBlank())
            } else {
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = 88.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.frequentlyUsed.isNotEmpty()) {
                        item { SectionHeader("Häufig genutzt", icon = Icons.Default.Star) }
                        items(state.frequentlyUsed, key = { "freq-${it.id}" }) { connection ->
                            ConnectionRow(
                                connection = connection,
                                onOpen = { onOpenConnection(connection.id) },
                                onEdit = { onEditConnection(connection.id) },
                                onDelete = null,
                            )
                        }
                    }

                    state.sections.forEach { section ->
                        item(key = "group-${section.group.id}") {
                            SectionHeader(section.group.name)
                        }
                        items(section.connections, key = { it.id }) { connection ->
                            ConnectionRow(
                                connection = connection,
                                onOpen = { onOpenConnection(connection.id) },
                                onEdit = { onEditConnection(connection.id) },
                                onDelete = {
                                    scope.launch {
                                        dependents = viewModel.dependentsOf(connection.id)
                                        pendingDelete = connection
                                    }
                                },
                            )
                        }
                    }

                    if (state.ungrouped.isNotEmpty()) {
                        if (state.sections.isNotEmpty()) {
                            item { SectionHeader("Ohne Gruppe") }
                        }
                        items(state.ungrouped, key = { it.id }) { connection ->
                            ConnectionRow(
                                connection = connection,
                                onOpen = { onOpenConnection(connection.id) },
                                onEdit = { onEditConnection(connection.id) },
                                onDelete = {
                                    scope.launch {
                                        dependents = viewModel.dependentsOf(connection.id)
                                        pendingDelete = connection
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { connection ->
        DeleteDialog(
            connection = connection,
            dependents = dependents,
            onConfirm = {
                viewModel.delete(connection)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/**
 * Sucht und legt an — beides im selben Feld.
 *
 * Getippter Text filtert die Liste; steht darin ein vollstaendiges Ziel wie
 * `root@host:2222`, laesst es sich mit der Eingabetaste direkt als Verbindung anlegen.
 * Zwei getrennte Felder waeren fuer denselben Zweck einer zu viel.
 */
@Composable
private fun QuickConnectField(
    query: String,
    onQueryChanged: (String) -> Unit,
    onSubmit: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChanged,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        singleLine = true,
        label = { Text("Suchen oder verbinden") },
        placeholder = { Text("root@host:2222") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChanged("") }) {
                    Icon(Icons.Default.Close, contentDescription = "Leeren")
                }
            }
        },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            imeAction = ImeAction.Go,
            autoCorrectEnabled = false,
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
            onGo = { onSubmit(query) },
        ),
    )
}

@Composable
private fun SectionHeader(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    Row(
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        icon?.let {
            Icon(
                it,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 2.dp),
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun ConnectionRow(
    connection: ConnectionEntity,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                Text(connection.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    buildString {
                        append(connection.type.name.lowercase()).append("://")
                        append(connection.address).append(':').append(connection.port)
                        if (connection.useCount > 0) append("  ·  ${connection.useCount}×")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Bearbeiten")
            }
            onDelete?.let {
                IconButton(onClick = it) {
                    Icon(Icons.Default.Delete, contentDescription = "Löschen")
                }
            }
        }
    }
}

@Composable
private fun EmptyHint(hasQuery: Boolean) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text(
            if (hasQuery) {
                "Kein Treffer. Eine vollständige Adresse wie root@host:2222 lässt sich mit " +
                    "der Eingabetaste direkt anlegen."
            } else {
                "Noch keine Verbindungen. Über „Import“ lässt sich eine bestehende " +
                    "JuiceSSH-Datenbank übernehmen, oder unten rechts eine neue anlegen."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * Warnt beim Loeschen, wenn andere Verbindungen ueber diese springen — die verlieren
 * dabei ihren Weg nach innen.
 */
@Composable
private fun DeleteDialog(
    connection: ConnectionEntity,
    dependents: List<ConnectionEntity>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("„${connection.displayName}“ löschen?") },
        text = {
            Column {
                Text("Die Verbindung wird entfernt. Identitäten bleiben erhalten.")
                if (dependents.isNotEmpty()) {
                    Text(
                        "\nAchtung: ${dependents.joinToString { it.displayName }} " +
                            "${if (dependents.size == 1) "springt" else "springen"} über diese " +
                            "Verbindung und verliert dabei den Sprung-Host.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Löschen") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
