package dev.jaui.squeezessh.ui.sftp

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jaui.squeezessh.sftp.SftpEntry
import dev.jaui.squeezessh.sftp.SftpTransfer
import dev.jaui.squeezessh.sftp.TransferDirection
import dev.jaui.squeezessh.sftp.TransferState
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SftpBrowserScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SftpBrowserViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val transfers by viewModel.activeTransfers.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingDownload by remember { mutableStateOf<SftpEntry?>(null) }
    var newDirectoryName by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { viewModel.open() }

    val pickDownloadTarget = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val entry = pendingDownload
        pendingDownload = null
        if (uri != null && entry != null) viewModel.download(entry, uri)
    }

    val pickUpload = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        // Name und Groesse kommen vom Dokumentanbieter, nicht aus dem Pfad — der Uri
        // muss keinen tragen.
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            val name = if (nameIndex >= 0) cursor.getString(nameIndex) else "upload.bin"
            val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L
            viewModel.upload(uri, name, size)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Dateien", style = MaterialTheme.typography.titleMedium)
                        Text(
                            state.path.ifEmpty { "…" },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = viewModel::navigateUp) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = "Nach oben")
                    }
                },
                actions = {
                    TextButton(onClick = { newDirectoryName = "" }) { Text("Ordner") }
                    IconButton(onClick = { pickUpload.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Default.Upload, contentDescription = "Hochladen")
                    }
                    TextButton(onClick = onBack) { Text("Zurück") }
                },
            )
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

            if (transfers.isNotEmpty()) {
                TransferList(
                    transfers = transfers,
                    onPause = viewModel::pauseTransfer,
                    onResume = viewModel::resumeTransfer,
                    onCancel = viewModel::cancelTransfer,
                    onClearFinished = viewModel::clearFinishedTransfers,
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp,
                    vertical = 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(state.entries, key = { it.path }) { entry ->
                    EntryRow(
                        entry = entry,
                        onOpen = {
                            if (entry.isDirectory) {
                                viewModel.navigateTo(entry.path)
                            } else {
                                pendingDownload = entry
                                pickDownloadTarget.launch(entry.name)
                            }
                        },
                        onDelete = { viewModel.delete(entry) },
                    )
                }
            }
        }
    }

    newDirectoryName?.let { current ->
        AlertDialog(
            onDismissRequest = { newDirectoryName = null },
            title = { Text("Neuer Ordner") },
            text = {
                OutlinedTextField(
                    value = current,
                    onValueChange = { newDirectoryName = it },
                    singleLine = true,
                    label = { Text("Name") },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = current.isNotBlank(),
                    onClick = {
                        viewModel.createDirectory(current)
                        newDirectoryName = null
                    },
                ) { Text("Anlegen") }
            },
            dismissButton = {
                TextButton(onClick = { newDirectoryName = null }) { Text("Abbrechen") }
            },
        )
    }
}

@Composable
private fun EntryRow(entry: SftpEntry, onOpen: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (entry.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
            contentDescription = null,
            tint = if (entry.isDirectory) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Column(Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.bodyLarge)
            if (!entry.isDirectory) {
                Text(
                    formatBytes(entry.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!entry.isDirectory) {
            Icon(
                Icons.Default.Download,
                contentDescription = "Herunterladen",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Löschen")
        }
    }
}

@Composable
private fun TransferList(
    transfers: List<SftpTransfer>,
    onPause: (Long) -> Unit,
    onResume: (Long) -> Unit,
    onCancel: (Long) -> Unit,
    onClearFinished: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            transfers.forEach { transfer ->
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${if (transfer.direction == TransferDirection.DOWNLOAD) "↓" else "↑"} " +
                                transfer.name,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        when (transfer.state) {
                            TransferState.RUNNING ->
                                TextButton(onClick = { onPause(transfer.id) }) { Text("Pause") }
                            TransferState.PAUSED ->
                                TextButton(onClick = { onResume(transfer.id) }) { Text("Weiter") }
                            else -> Unit
                        }
                        if (!transfer.isFinished) {
                            TextButton(onClick = { onCancel(transfer.id) }) { Text("Abbrechen") }
                        }
                    }
                    Text(
                        buildString {
                            append(formatBytes(transfer.transferredBytes))
                            if (transfer.totalBytes > 0) {
                                append(" / ").append(formatBytes(transfer.totalBytes))
                            }
                            when (transfer.state) {
                                TransferState.DONE -> append("  ·  fertig")
                                TransferState.PAUSED -> append("  ·  angehalten")
                                TransferState.CANCELLED -> append("  ·  abgebrochen")
                                TransferState.FAILED -> append("  ·  ${transfer.error}")
                                TransferState.RUNNING -> Unit
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (transfer.state == TransferState.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    transfer.progress?.takeIf { !transfer.isFinished }?.let { progress ->
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            if (transfers.any { it.isFinished }) {
                TextButton(onClick = onClearFinished) { Text("Erledigte ausblenden") }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.GERMANY, "%.1f KiB", bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 ->
        String.format(Locale.GERMANY, "%.1f MiB", bytes / (1024.0 * 1024))
    else -> String.format(Locale.GERMANY, "%.2f GiB", bytes / (1024.0 * 1024 * 1024))
}
