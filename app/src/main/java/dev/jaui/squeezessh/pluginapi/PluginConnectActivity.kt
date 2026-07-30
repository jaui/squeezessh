package dev.jaui.squeezessh.pluginapi

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.jaui.squeezessh.ssh.HostKeyPrompt
import dev.jaui.squeezessh.ui.theme.SqueezeSshTheme

/**
 * Nimmt `ssh://<uuid>` von einem Plugin entgegen, baut die Sitzung auf und gibt das Paar aus
 * Nummer und Schlüssel zurück.
 *
 * So sieht der Vertrag von JuiceSSH aus: das Plugin ruft
 * `startActivityForResult(ACTION_VIEW, ssh://<uuid>)` mit dem Extra `background=true` und
 * bekommt `session_id` und `session_key` im Ergebnis. Der Umweg über eine Activity hat einen
 * Grund — beim Verbinden können Rückfragen nötig sein, etwa ein unbekannter Host-Key, und
 * die brauchen ein Fenster.
 *
 * Erbt von [FragmentActivity] wie [dev.jaui.squeezessh.ui.MainActivity], damit später auch
 * eine biometrische Rückfrage möglich ist.
 */
@AndroidEntryPoint
class PluginConnectActivity : FragmentActivity() {

    private val viewModel: PluginConnectViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val publicId = intent?.data?.let { PluginSessionRequest.publicIdOf(it) }
        // Der Aufrufer kommt als Extra, weil diese Activity von MainActivity gestartet
        // wird und `callingActivity` deshalb auf die eigene App zeigt. Vertrauenswuerdig
        // ist das, weil die Activity nicht exportiert ist — setzen kann das Extra nur die
        // eigene App, und die hat den Namen zuvor vom Binder erfragt.
        val caller = intent.getStringExtra(EXTRA_CALLER)
        if (publicId == null || caller == null) {
            // Ohne bekannten Aufrufer gäbe es niemanden, dem die Sitzung gehört — und ohne
            // Eigentümer könnte jedes Plugin sie später ansprechen.
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        viewModel.connect(publicId, caller, intent.getBooleanExtra(EXTRA_SFTP, false))

        setContent {
            SqueezeSshTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                Screen(
                    state = state,
                    onHostKeyAnswer = viewModel::answerHostKey,
                    onDismissError = { finishWith(null) },
                )
                when (val finished = state.result) {
                    null -> Unit
                    else -> finishWith(finished)
                }
            }
        }
    }

    private fun finishWith(handle: PluginSessionRegistry.Handle?) {
        if (handle == null) {
            setResult(Activity.RESULT_CANCELED)
        } else {
            setResult(
                Activity.RESULT_OK,
                Intent()
                    .putExtra(EXTRA_SESSION_ID, handle.sessionId)
                    .putExtra(EXTRA_SESSION_KEY, handle.sessionKey),
            )
        }
        finish()
    }

    companion object {
        /** Namen aus JuiceSSHs Vertrag; ein Plugin liest genau diese Extras aus. */
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_SESSION_KEY = "session_key"
        const val EXTRA_BACKGROUND = "background"
        const val EXTRA_SFTP = "sftp"

        /** Eigene Zutat: der Paketname des Plugins, von MainActivity ermittelt. */
        const val EXTRA_CALLER = "dev.jaui.squeezessh.plugin.caller"
    }
}

@Composable
private fun Screen(
    state: PluginConnectState,
    onHostKeyAnswer: (Boolean) -> Unit,
    onDismissError: () -> Unit,
) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Text(
                state.status ?: "Verbinde…",
                style = MaterialTheme.typography.bodyMedium,
            )
            state.callerLabel?.let {
                Text(
                    "angefordert von $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    state.hostKeyRequest?.let { request ->
        HostKeyDialog(request.prompt, onHostKeyAnswer)
    }

    state.error?.let { message ->
        AlertDialog(
            onDismissRequest = onDismissError,
            title = { Text("Verbindung fehlgeschlagen") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = onDismissError) { Text("Schließen") } },
        )
    }
}

@Composable
private fun HostKeyDialog(prompt: HostKeyPrompt, onAnswer: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        title = { Text(if (prompt.isChange) "Host-Key hat sich geändert" else "Unbekannter Host") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (prompt.isChange) {
                        "Der Server ${prompt.host} meldet sich mit einem anderen Schlüssel als " +
                            "zuvor. Das kann ein neu aufgesetzter Server sein — oder jemand " +
                            "dazwischen."
                    } else {
                        "${prompt.host} ist noch nicht bekannt. Vergleiche den Fingerprint mit " +
                            "dem, den der Server nennt."
                    },
                )
                Text(
                    prompt.fingerprint,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text("Vertrauen") } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text("Abbrechen") } },
    )
}
