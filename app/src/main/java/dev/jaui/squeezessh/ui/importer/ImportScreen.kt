package dev.jaui.squeezessh.ui.importer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jaui.squeezessh.data.importer.ImportPreview
import dev.jaui.squeezessh.data.importer.ImportReport

@Composable
fun ImportScreen(
    modifier: Modifier = Modifier,
    viewModel: ImportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val pickDatabase = rememberLauncherForActivityResult(
        // Kein sinnvoller MIME-Typ: die Datei hat weder Endung noch Typ.
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::onDatabasePicked) }

    val pickPreferences = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::onPreferencesPicked) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Aus JuiceSSH übernehmen", style = MaterialTheme.typography.headlineSmall)

        Text(
            "JuiceSSH wird nicht mehr entwickelt und bietet in der kostenlosen Version " +
                "keinen Export. Die Daten liegen aber als zwei Dateien auf dem alten Gerät " +
                "und lassen sich direkt einlesen.",
            style = MaterialTheme.typography.bodyMedium,
        )

        HowToCard()

        FilePickerRow(
            label = "Datenbank",
            hint = "Pflicht — Datei aus databases/, Name wie „19a093afcee…“",
            picked = state.databaseName,
            onPick = { pickDatabase.launch(arrayOf("*/*")) },
        )

        FilePickerRow(
            label = "Preferences",
            hint = "Für Passwörter und Schlüssel — com.sonelli.juicessh_preferences.xml",
            picked = state.preferencesName,
            onPick = { pickPreferences.launch(arrayOf("text/xml", "application/xml", "*/*")) },
        )

        if (state.busy) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        state.error?.let { message ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = viewModel::dismissError) { Text("Verstanden") }
                }
            }
        }

        state.report?.let { report -> ReportCard(report, onReset = viewModel::reset) }

        if (state.report == null) {
            state.preview?.let { preview ->
                PreviewCard(preview)
                Button(
                    onClick = { viewModel.startImport() },
                    enabled = state.canImport && !preview.isEmpty,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("${preview.total} Einträge übernehmen")
                }
            }
        }
    }
}

@Composable
private fun HowToCard() {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("So kommst du an die Dateien", style = MaterialTheme.typography.titleSmall)
            Text(
                "Beide liegen im privaten Speicher von JuiceSSH und sind nur mit Root " +
                    "oder über einen Emulator erreichbar:",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "adb root\n" +
                    "adb pull /data/data/com.sonelli.juicessh/databases/\n" +
                    "adb pull /data/data/com.sonelli.juicessh/shared_prefs/" +
                    "com.sonelli.juicessh_preferences.xml",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            Text(
                "Ohne die Preferences-Datei kommen Verbindungen, Gruppen und Namen mit, " +
                    "Passwörter und Schlüssel aber nicht — die müssen dann neu hinterlegt werden.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun FilePickerRow(
    label: String,
    hint: String,
    picked: String?,
    onPick: () -> Unit,
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                OutlinedButton(onClick = onPick) { Text(if (picked == null) "Wählen" else "Ändern") }
            }
            Text(
                picked ?: hint,
                style = MaterialTheme.typography.bodySmall,
                color = if (picked == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
    }
}

@Composable
private fun PreviewCard(preview: ImportPreview) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Gefunden", style = MaterialTheme.typography.titleSmall)
            preview.accountName?.let { Text("Konto: $it", style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(4.dp))
            CountRow("Verbindungen", preview.connections)
            CountRow("Gruppen", preview.groups)
            CountRow("Identitäten", preview.identities)
            CountRow("Snippets", preview.snippets)
            CountRow("Port-Forwards", preview.portForwards)
            CountRow("Known Hosts", preview.knownHosts)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                if (preview.secretsReadable) {
                    "Zugangsdaten sind lesbar und werden beim Import in den " +
                        "Android-Keystore überführt."
                } else {
                    "Zugangsdaten sind nicht lesbar — es fehlt die Preferences-Datei. " +
                        "${preview.identitiesWithSecrets} Identitäten kommen ohne Passwort an."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (preview.secretsReadable) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
    }
}

@Composable
private fun ReportCard(report: ImportReport, onReset: () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Import abgeschlossen", style = MaterialTheme.typography.titleSmall)
            CountRow("Verbindungen", report.importedConnections)
            CountRow("Gruppen", report.importedGroups)
            CountRow("Identitäten", report.importedIdentities)
            CountRow("Snippets", report.importedSnippets)
            CountRow("Port-Forwards", report.importedPortForwards)
            CountRow("Known Hosts", report.importedKnownHosts)
            if (report.skippedDuplicates > 0) {
                CountRow("Übersprungen (schon vorhanden)", report.skippedDuplicates)
            }
            if (report.warnings.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                report.warnings.forEach { warning ->
                    Text(
                        "• $warning",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            TextButton(onClick = onReset) { Text("Weiteres Backup einlesen") }
        }
    }
}

@Composable
private fun CountRow(label: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text("$count", style = MaterialTheme.typography.bodyMedium)
    }
}
