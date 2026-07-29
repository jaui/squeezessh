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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jaui.squeezessh.data.db.entity.IdentityEntity
import dev.jaui.squeezessh.data.repository.IdentityRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdentityListScreen(
    onEditIdentity: (Long) -> Unit,
    onNewIdentity: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: IdentityListViewModel = hiltViewModel(),
) {
    val identities by viewModel.identities.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var pendingDelete by remember { mutableStateOf<IdentityEntity?>(null) }
    var usageCount by remember { mutableStateOf(0) }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Identitäten") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onNewIdentity) {
                Icon(Icons.Default.Add, contentDescription = "Neue Identität")
            }
        },
    ) { insets ->
        if (identities.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(insets).padding(24.dp)) {
                Text(
                    "Eine Identität bündelt Benutzername, Passwort und Schlüssel und lässt " +
                        "sich von mehreren Verbindungen nutzen.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(insets),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 88.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(identities, key = { it.id }) { identity ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onEditIdentity(identity.id) },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                                Text(
                                    identity.displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    buildList {
                                        add(identity.username)
                                        if (identity.hasPassword) add("Passwort")
                                        if (identity.hasPrivateKey) add("Schlüssel")
                                    }.joinToString("  ·  "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        usageCount = viewModel.usageCount(identity.id)
                                        pendingDelete = identity
                                    }
                                },
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Löschen")
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { identity ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("„${identity.displayName}“ löschen?") },
            text = {
                Text(
                    if (usageCount > 0) {
                        "Diese Identität wird von $usageCount " +
                            "${if (usageCount == 1) "Verbindung" else "Verbindungen"} genutzt. " +
                            "Nach dem Löschen fehlen dort die Zugangsdaten."
                    } else {
                        "Die Identität wird samt hinterlegtem Passwort und Schlüssel entfernt."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(identity)
                        pendingDelete = null
                    },
                ) { Text("Löschen") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Abbrechen") }
            },
        )
    }
}

@HiltViewModel
class IdentityListViewModel @Inject constructor(
    private val repository: IdentityRepository,
) : ViewModel() {

    val identities: StateFlow<List<IdentityEntity>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun usageCount(identityId: Long): Int = repository.usageCount(identityId)

    fun delete(identity: IdentityEntity) {
        viewModelScope.launch { repository.delete(identity) }
    }
}
