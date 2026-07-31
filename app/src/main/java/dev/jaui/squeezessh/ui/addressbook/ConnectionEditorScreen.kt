package dev.jaui.squeezessh.ui.addressbook

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jaui.squeezessh.data.model.BackspaceMode
import dev.jaui.squeezessh.data.model.ConnectionType
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionEditorScreen(
    onDone: () -> Unit,
    onNewIdentity: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ConnectionEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var newGroupName by remember { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNew) "Neue Verbindung" else "Verbindung bearbeiten") },
                actions = {
                    IconButton(
                        enabled = state.canSave,
                        onClick = { scope.launch { if (viewModel.save()) onDone() } },
                    ) {
                        Icon(Icons.Default.Check, contentDescription = "Speichern")
                    }
                },
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
            OutlinedTextField(
                value = state.nickname,
                onValueChange = viewModel::onNicknameChanged,
                label = { Text("Name (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.address,
                    onValueChange = viewModel::onAddressChanged,
                    label = { Text("Adresse") },
                    placeholder = { Text("host oder root@host:2222") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = state.port,
                    onValueChange = viewModel::onPortChanged,
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                    ),
                    modifier = Modifier.width(110.dp),
                )
            }

            // Zeigt vor dem Speichern, was aus der Eingabe gelesen wurde — das Adressfeld
            // darf auch die volle Form wie ssh://root@host:2222 enthalten.
            state.effectiveTarget?.let { target ->
                Text(
                    "Verbindet sich mit $target",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            } ?: Text(
                "Adresse noch unvollständig",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )

            state.addressUsername?.let { username ->
                Text(
                    if (state.addressUsernameKnown) {
                        "Benutzer „$username“ erkannt — die vorhandene Identität wird beim " +
                            "Speichern verknüpft."
                    } else {
                        "Benutzer „$username“ erkannt — wird beim Speichern als Identität " +
                            "angelegt. Nach dem Passwort fragt die App beim ersten Verbinden."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            ChoiceRow(
                label = "Typ",
                options = ConnectionType.entries.map { it to it.name.lowercase() },
                selected = state.type,
                onSelect = viewModel::onTypeChanged,
            )

            ChoiceRow(
                label = "Backspace",
                options = listOf(
                    BackspaceMode.DELETE to "DEL (Standard)",
                    BackspaceMode.BACKSPACE to "^H (alt)",
                ),
                selected = state.backspaceMode,
                onSelect = viewModel::onBackspaceModeChanged,
            )

            SectionCard("Identitäten") {
                if (state.availableIdentities.isEmpty()) {
                    Text(
                        "Noch keine Identität hinterlegt.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(
                        "Werden beim Verbinden von oben nach unten probiert.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    state.selectedIdentityIds.forEachIndexed { index, identityId ->
                        val identity = state.availableIdentities.firstOrNull { it.id == identityId }
                            ?: return@forEachIndexed
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${index + 1}. ${identity.displayName}",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = { viewModel.moveIdentity(identityId, up = true) },
                                enabled = index > 0,
                            ) { Icon(Icons.Default.ArrowUpward, contentDescription = "Nach oben") }
                            IconButton(
                                onClick = { viewModel.moveIdentity(identityId, up = false) },
                                enabled = index < state.selectedIdentityIds.lastIndex,
                            ) { Icon(Icons.Default.ArrowDownward, contentDescription = "Nach unten") }
                            IconButton(onClick = { viewModel.onIdentityToggled(identityId) }) {
                                Icon(Icons.Default.Close, contentDescription = "Entfernen")
                            }
                        }
                    }

                    val unselected = state.availableIdentities
                        .filterNot { it.id in state.selectedIdentityIds }
                    if (unselected.isNotEmpty()) {
                        WrappingChips {
                            unselected.forEach { identity ->
                                AssistChip(
                                    onClick = { viewModel.onIdentityToggled(identity.id) },
                                    label = { Text("+ ${identity.displayName}") },
                                )
                            }
                        }
                    }
                }
                TextButton(onClick = onNewIdentity) { Text("Identität anlegen") }
            }

            SectionCard("Gruppe") {
                WrappingChips {
                    FilterChip(
                        selected = state.groupId == null,
                        onClick = { viewModel.onGroupChanged(null) },
                        label = { Text("keine") },
                    )
                    state.availableGroups.forEach { group ->
                        FilterChip(
                            selected = state.groupId == group.id,
                            onClick = { viewModel.onGroupChanged(group.id) },
                            label = { Text(group.name) },
                        )
                    }
                    AssistChip(
                        onClick = { newGroupName = "" },
                        label = { Text("+ neu") },
                    )
                }
            }

            SectionCard("Verbinden über") {
                Text(
                    "Sprung über einen Bastion-Host. Die Kette wird von außen nach innen " +
                        "aufgebaut.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                WrappingChips {
                    FilterChip(
                        selected = state.viaConnectionId == null,
                        onClick = { viewModel.onJumpHostChanged(null) },
                        label = { Text("direkt") },
                    )
                    state.possibleJumpHosts.forEach { candidate ->
                        FilterChip(
                            selected = state.viaConnectionId == candidate.id,
                            onClick = { viewModel.onJumpHostChanged(candidate.id) },
                            label = { Text(candidate.displayName) },
                        )
                    }
                }
            }
        }
    }

    newGroupName?.let { current ->
        AlertDialog(
            onDismissRequest = { newGroupName = null },
            title = { Text("Neue Gruppe") },
            text = {
                OutlinedTextField(
                    value = current,
                    onValueChange = { newGroupName = it },
                    singleLine = true,
                    label = { Text("Name") },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = current.isNotBlank(),
                    onClick = {
                        viewModel.createGroup(current)
                        newGroupName = null
                    },
                ) { Text("Anlegen") }
            },
            dismissButton = {
                TextButton(onClick = { newGroupName = null }) { Text("Abbrechen") }
            },
        )
    }
}

@Composable
private fun <T> ChoiceRow(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    SectionCard(label) {
        WrappingChips {
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
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

/** Chips umbrechen statt seitlich zu verschwinden — Gruppennamen koennen lang sein. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WrappingChips(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}
