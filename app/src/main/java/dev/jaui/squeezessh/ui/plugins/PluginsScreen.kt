package dev.jaui.squeezessh.ui.plugins

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jaui.squeezessh.data.db.entity.PluginLogEntity
import dev.jaui.squeezessh.pluginapi.PluginAuditLog
import dev.jaui.squeezessh.pluginapi.PluginContract
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

/**
 * Zeigt, welche Erweiterungen es gibt, was sie dürfen und was sie getan haben.
 *
 * Die Rechte selbst werden hier nicht vergeben — das macht Android beim Installieren. Was
 * hier fehlt und bewusst fehlt, ist ein Schalter zum Entziehen: Android bietet dafür die
 * Systemeinstellungen, und ein zweiter Schalter daneben, der etwas anderes bedeutet, wäre
 * eine Falle.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PluginsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PluginsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Erweiterungen") },
                actions = { TextButton(onClick = onBack) { Text("Zurück") } },
            )
        },
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Erweiterungen können Verbindungen lesen und in deinem Namen Sitzungen " +
                        "aufbauen. Welche Rechte eine hat, entscheidest du beim Installieren; " +
                        "entziehen lassen sie sich in den Android-Einstellungen unter Apps.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.plugins.isEmpty()) {
                item {
                    Text(
                        "Keine Erweiterung installiert, die diese App anspricht.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                items(state.plugins, key = { it.packageName }) { plugin ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(plugin.label, style = MaterialTheme.typography.titleMedium)
                            Text(
                                plugin.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (plugin.grantedPermissions.isEmpty()) {
                                Text(
                                    "Keine Rechte erteilt.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            } else {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    plugin.grantedPermissions.forEach { permission ->
                                        AssistChip(onClick = {}, label = { Text(permission) })
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 16.dp),
                ) {
                    Text("Protokoll", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Jeder Zugriff einer Erweiterung, auch ein abgewiesener.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.log.isNotEmpty()) {
                        TextButton(onClick = viewModel::clearLog) { Text("Protokoll leeren") }
                    }
                }
            }

            if (state.log.isEmpty()) {
                item {
                    Text(
                        "Noch nichts geschehen.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                items(state.log, key = { it.id }) { entry ->
                    LogRow(entry)
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: PluginLogEntity) {
    Column(Modifier.fillMaxWidth()) {
        Text(entry.message, style = MaterialTheme.typography.bodyMedium)
        Text(
            "${entry.packageName} · ${formatTime(entry.createdAt)}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))

@HiltViewModel
class PluginsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auditLog: PluginAuditLog,
) : ViewModel() {

    val state: StateFlow<PluginsState> = combine(
        auditLog.observeRecent(),
        auditLog.observePackages().map { seen -> installedPlugins(seen) },
    ) { log, plugins ->
        PluginsState(plugins = plugins, log = log)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PluginsState())

    fun clearLog() {
        viewModelScope.launch { auditLog.clear() }
    }

    /**
     * Sammelt die Pakete, die eines der Plugin-Rechte angemeldet haben, und die, die schon
     * einmal zugegriffen haben.
     *
     * Beides ist nötig: ein Paket, das die Rechte nur deklariert, hat vielleicht noch nichts
     * getan — und eines, das im Protokoll steht, könnte inzwischen deinstalliert sein.
     */
    private fun installedPlugins(seenPackages: List<String>): List<PluginInfo> {
        val packageManager = context.packageManager
        val relevant = PERMISSIONS.map { "${PluginContract.AUTHORITY}.permission.$it" }.toSet()

        @Suppress("DEPRECATION")
        val installed = packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            .asSequence()
            .filter { info -> info.requestedPermissions?.any { it in relevant } == true }
            .filter { it.packageName != context.packageName }
            .map { info ->
                val granted = info.requestedPermissions
                    ?.filterIndexed { index, permission ->
                        permission in relevant &&
                            info.requestedPermissionsFlags
                                ?.getOrNull(index)
                                ?.and(android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                    }
                    ?.map { it.substringAfterLast('.') }
                    .orEmpty()
                PluginInfo(
                    packageName = info.packageName,
                    label = runCatching {
                        packageManager.getApplicationLabel(info.applicationInfo!!).toString()
                    }.getOrDefault(info.packageName),
                    grantedPermissions = granted,
                )
            }
            .toList()

        val known = installed.map { it.packageName }.toSet()
        val vanished = seenPackages
            .filterNot { it in known || it == "(unbekannt)" }
            .map { PluginInfo(it, "$it (nicht mehr installiert)", emptyList()) }

        return (installed + vanished).sortedBy { it.label.lowercase() }
    }

    private companion object {
        val PERMISSIONS = listOf(
            "READ_CONNECTIONS",
            "WRITE_CONNECTIONS",
            "READ_CONNECTION_GROUPS",
            "WRITE_CONNECTION_GROUPS",
            "READ_PORT_FORWARDS",
            "WRITE_PORT_FORWARDS",
            "READ_SNIPPETS",
            "WRITE_SNIPPETS",
            "READ_PLUGIN_LOG",
            "OPEN_SESSIONS",
            "OPEN_SFTP_SESSIONS",
        )
    }
}

data class PluginsState(
    val plugins: List<PluginInfo> = emptyList(),
    val log: List<PluginLogEntity> = emptyList(),
)

data class PluginInfo(
    val packageName: String,
    val label: String,
    val grantedPermissions: List<String>,
)

private fun IntArray.getOrNull(index: Int): Int? = if (index in indices) this[index] else null
