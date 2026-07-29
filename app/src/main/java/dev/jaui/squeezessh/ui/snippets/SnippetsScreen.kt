package dev.jaui.squeezessh.ui.snippets

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jaui.squeezessh.data.db.entity.SnippetEntity
import dev.jaui.squeezessh.data.repository.SnippetRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Snippets sind gespeicherte Befehlszeilen.
 *
 * Sie waren in JuiceSSH eine Pro-Funktion; hier gibt es sie ohne Einschraenkung. Aus dem
 * Terminal heraus lassen sie sich in die laufende Sitzung schicken.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnippetsScreen(
    onBack: () -> Unit,
    /** Gesetzt, wenn der Bildschirm aus einem Terminal geoeffnet wurde. */
    onSend: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    viewModel: SnippetsViewModel = hiltViewModel(),
) {
    val snippets by viewModel.snippets.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<SnippetDraft?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(if (onSend == null) "Snippets" else "Snippet senden") },
                actions = { TextButton(onClick = onBack) { Text("Zurück") } },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = SnippetDraft() }) {
                Icon(Icons.Default.Add, contentDescription = "Neues Snippet")
            }
        },
    ) { insets ->
        if (snippets.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(insets).padding(24.dp)) {
                Text(
                    "Noch keine Snippets. Gut geeignet für alles, was man auf einer " +
                        "Software-Tastatur nicht gern tippt — etwa " +
                        "„docker compose logs -f --tail=100“.",
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
                items(snippets, key = { it.id }) { snippet ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable {
                            if (onSend != null) {
                                viewModel.recordUse(snippet)
                                onSend(snippet.content)
                                onBack()
                            } else {
                                editing = SnippetDraft(snippet.id, snippet.name, snippet.content)
                            }
                        },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                                Text(snippet.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    snippet.content.lineSequence().first().take(80),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { viewModel.delete(snippet) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Löschen")
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { draft ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (draft.id == 0L) "Neues Snippet" else "Snippet bearbeiten") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = draft.name,
                        onValueChange = { editing = draft.copy(name = it) },
                        label = { Text("Name") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = draft.content,
                        onValueChange = { editing = draft.copy(content = it) },
                        label = { Text("Befehl") },
                        minLines = 3,
                    )
                    Text(
                        "Wird beim Senden unverändert übertragen — ohne abschließenden " +
                            "Zeilenumbruch, damit man vorher noch etwas anhängen kann.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = draft.isValid,
                    onClick = {
                        viewModel.save(draft)
                        editing = null
                    },
                ) { Text("Speichern") }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("Abbrechen") }
            },
        )
    }
}

data class SnippetDraft(
    val id: Long = 0,
    val name: String = "",
    val content: String = "",
) {
    val isValid: Boolean get() = name.isNotBlank() && content.isNotBlank()
}

@HiltViewModel
class SnippetsViewModel @Inject constructor(
    private val repository: SnippetRepository,
) : ViewModel() {

    val snippets: StateFlow<List<SnippetEntity>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun save(draft: SnippetDraft) {
        viewModelScope.launch {
            if (draft.id == 0L) {
                repository.create(draft.name.trim(), draft.content)
            } else {
                repository.findById(draft.id)?.let { existing ->
                    repository.update(
                        existing.copy(name = draft.name.trim(), content = draft.content),
                    )
                }
            }
        }
    }

    fun delete(snippet: SnippetEntity) {
        viewModelScope.launch { repository.delete(snippet) }
    }

    fun recordUse(snippet: SnippetEntity) {
        viewModelScope.launch { repository.recordUse(snippet.id) }
    }
}
