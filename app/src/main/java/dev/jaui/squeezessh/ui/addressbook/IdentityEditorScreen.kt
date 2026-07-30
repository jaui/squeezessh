package dev.jaui.squeezessh.ui.addressbook

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jaui.squeezessh.data.keys.SshKeyType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun IdentityEditorScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: IdentityEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var confirmExport by remember { mutableStateOf(false) }

    val exportKey = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-pem-file"),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val pem = viewModel.exportablePrivateKey() ?: return@launch
            withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(pem.toByteArray()) }
                }
            }
        }
    }

    val importKey = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()
                        ?.use { it.readText() }
                }.getOrNull()
            }
            text?.let(viewModel::onPrivateKeyPasted)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNew) "Neue Identität" else "Identität bearbeiten") },
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

            OutlinedTextField(
                value = state.username,
                onValueChange = viewModel::onUsernameChanged,
                label = { Text("Benutzername") },
                singleLine = true,
                isError = state.username.isBlank(),
                modifier = Modifier.fillMaxWidth(),
            )

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Passwort", style = MaterialTheme.typography.titleSmall)
                    if (state.hasStoredPassword && !state.passwordTouched) {
                        // Gespeicherte Geheimnisse werden bewusst nicht zurueckgelesen,
                        // nur ihr Vorhandensein wird angezeigt.
                        Text(
                            "Ist hinterlegt und verschlüsselt gespeichert.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = viewModel::clearPassword) { Text("Ersetzen oder löschen") }
                    } else {
                        OutlinedTextField(
                            value = state.password,
                            onValueChange = viewModel::onPasswordChanged,
                            label = { Text("Passwort") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Privater Schlüssel", style = MaterialTheme.typography.titleSmall)

                    if (state.hasStoredKey && !state.keyTouched) {
                        Text(
                            "Ist hinterlegt und verschlüsselt gespeichert.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else if (state.privateKeyPem.isNotBlank()) {
                        Text(
                            "Schlüssel liegt bereit — wird beim Speichern in den " +
                                "Android-Keystore überführt.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        OutlinedTextField(
                            value = state.keyPassphrase,
                            onValueChange = viewModel::onKeyPassphraseChanged,
                            label = { Text("Passphrase des Schlüssels (falls vorhanden)") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    if (state.busy) {
                        CircularProgressIndicator()
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SshKeyType.entries.forEach { type ->
                                AssistChip(
                                    onClick = { viewModel.generateKey(type) },
                                    label = { Text("${type.label} erzeugen") },
                                )
                            }
                            AssistChip(
                                onClick = { importKey.launch(arrayOf("*/*")) },
                                label = { Text("Aus Datei") },
                            )
                            if (state.hasStoredKey || state.privateKeyPem.isNotBlank()) {
                                AssistChip(
                                    onClick = { confirmExport = true },
                                    label = { Text("Exportieren") },
                                )
                                AssistChip(
                                    onClick = viewModel::clearKey,
                                    label = { Text("Entfernen") },
                                )
                            }
                        }
                    }

                    state.generatedFingerprint?.let { fingerprint ->
                        Text(
                            fingerprint,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }

            state.publicKeyLine?.let { publicKey ->
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Öffentlicher Schlüssel", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Diese Zeile gehört auf den Server in ~/.ssh/authorized_keys.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            publicKey,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        TextButton(
                            onClick = {
                                context.getSystemService(ClipboardManager::class.java)
                                    ?.setPrimaryClip(
                                        ClipData.newPlainText("Public Key", publicKey),
                                    )
                            },
                        ) { Text("Kopieren") }
                    }
                }
            }

            state.error?.let { error ->
                Card(
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
        }
    }

    if (confirmExport) {
        AlertDialog(
            onDismissRequest = { confirmExport = false },
            title = { Text("Privaten Schlüssel exportieren?") },
            text = {
                Text(
                    "Die Datei enthält den Schlüssel im Klartext. Wer sie hat, kann sich " +
                        "als du anmelden — auch nach dem Löschen aus dieser App. Nur " +
                        "exportieren, wenn das Ziel genauso geschützt ist wie der Schlüssel " +
                        "selbst.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmExport = false
                        exportKey.launch("${state.username.ifBlank { "identity" }}.pem")
                    },
                ) { Text("Exportieren") }
            },
            dismissButton = {
                TextButton(onClick = { confirmExport = false }) { Text("Abbrechen") }
            },
        )
    }
}
