package dev.jaui.squeezessh.sftp

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fuehrt und steuert laufende Dateiuebertragungen.
 *
 * Uebertragungen laufen ausserhalb der Oberflaeche weiter, deshalb liegen sie hier und
 * nicht in einem ViewModel. Solange welche laufen, haelt [SftpTransferService] den Prozess
 * am Leben.
 */
@Singleton
class SftpTransferManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val io: CoroutineDispatcher,
) {

    private val scope = CoroutineScope(SupervisorJob() + io)
    private val nextId = AtomicLong(1)
    private val jobs = mutableMapOf<Long, Job>()

    private val _transfers = MutableStateFlow<List<SftpTransfer>>(emptyList())
    val transfers: StateFlow<List<SftpTransfer>> = _transfers.asStateFlow()

    val hasActiveTransfers: Boolean
        get() = _transfers.value.any { it.state == TransferState.RUNNING }

    fun download(session: SftpSession, remotePath: String, name: String, destination: Uri, size: Long): Long {
        val transfer = newTransfer(name, TransferDirection.DOWNLOAD, size)
        start(transfer.id) { current ->
            // Anhaengen statt Ueberschreiben, damit ein fortgesetzter Download nicht von
            // vorn beginnt. Nicht jeder Dokumentanbieter kann das — dann bricht es hier
            // sichtbar ab statt still die Datei zu zerstoeren.
            val mode = if (current.transferredBytes > 0) "wa" else "w"
            val output = context.contentResolver.openOutputStream(destination, mode)
                ?: throw IOException("Ziel ließ sich nicht zum Schreiben öffnen")
            output.use {
                session.download(
                    remotePath = remotePath,
                    destination = it,
                    startOffset = current.transferredBytes,
                    onProgress = { transferred -> updateProgress(current.id, transferred) },
                    shouldContinue = { shouldContinue(current.id) },
                )
            }
        }
        return transfer.id
    }

    fun upload(session: SftpSession, source: Uri, remotePath: String, name: String, size: Long): Long {
        val transfer = newTransfer(name, TransferDirection.UPLOAD, size)
        start(transfer.id) { current ->
            val input = context.contentResolver.openInputStream(source)
                ?: throw IOException("Quelle ließ sich nicht zum Lesen öffnen")
            input.use { stream ->
                // Beim Fortsetzen den bereits uebertragenen Teil ueberspringen.
                if (current.transferredBytes > 0) stream.skip(current.transferredBytes)
                session.upload(
                    source = stream,
                    remotePath = remotePath,
                    startOffset = current.transferredBytes,
                    onProgress = { transferred -> updateProgress(current.id, transferred) },
                    shouldContinue = { shouldContinue(current.id) },
                )
            }
        }
        return transfer.id
    }

    fun pause(id: Long) = setState(id, TransferState.PAUSED)

    fun resume(id: Long) {
        val transfer = _transfers.value.firstOrNull { it.id == id } ?: return
        if (transfer.state != TransferState.PAUSED) return
        setState(id, TransferState.RUNNING)
        transfer.restart?.invoke()
    }

    fun cancel(id: Long) {
        setState(id, TransferState.CANCELLED)
        jobs.remove(id)?.cancel()
    }

    /** Entfernt abgeschlossene Eintraege aus der Liste. */
    fun clearFinished() = _transfers.update { list ->
        list.filter { it.state == TransferState.RUNNING || it.state == TransferState.PAUSED }
    }

    private fun newTransfer(name: String, direction: TransferDirection, size: Long): SftpTransfer {
        val transfer = SftpTransfer(
            id = nextId.getAndIncrement(),
            name = name,
            direction = direction,
            totalBytes = size,
        )
        _transfers.update { it + transfer }
        return transfer
    }

    private fun start(id: Long, body: suspend (SftpTransfer) -> Unit) {
        val restart: () -> Unit = { launchWorker(id, body) }
        _transfers.update { list ->
            list.map { if (it.id == id) it.copy(restart = restart) else it }
        }
        launchWorker(id, body)
    }

    private fun launchWorker(id: Long, body: suspend (SftpTransfer) -> Unit) {
        jobs[id] = scope.launch {
            val current = _transfers.value.firstOrNull { it.id == id } ?: return@launch
            try {
                body(current)
                // Nur als fertig markieren, wenn nicht zwischendurch angehalten wurde.
                if (_transfers.value.firstOrNull { it.id == id }?.state == TransferState.RUNNING) {
                    setState(id, TransferState.DONE)
                }
            } catch (e: IOException) {
                setFailed(id, e.message ?: "Übertragung fehlgeschlagen")
            }
        }
    }

    private fun shouldContinue(id: Long): Boolean =
        _transfers.value.firstOrNull { it.id == id }?.state == TransferState.RUNNING

    private fun updateProgress(id: Long, transferred: Long) = _transfers.update { list ->
        list.map { if (it.id == id) it.copy(transferredBytes = transferred) else it }
    }

    private fun setState(id: Long, state: TransferState) = _transfers.update { list ->
        list.map { if (it.id == id) it.copy(state = state) else it }
    }

    private fun setFailed(id: Long, message: String) = _transfers.update { list ->
        list.map {
            if (it.id == id) it.copy(state = TransferState.FAILED, error = message) else it
        }
    }
}

data class SftpTransfer(
    val id: Long,
    val name: String,
    val direction: TransferDirection,
    val totalBytes: Long,
    val transferredBytes: Long = 0,
    val state: TransferState = TransferState.RUNNING,
    val error: String? = null,
    /** Setzt eine angehaltene Uebertragung an der letzten Stelle fort. */
    val restart: (() -> Unit)? = null,
) {
    /** `null`, wenn die Gesamtgroesse unbekannt ist — dann gibt es keinen sinnvollen Balken. */
    val progress: Float?
        get() = if (totalBytes > 0) (transferredBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null

    val isFinished: Boolean
        get() = state == TransferState.DONE ||
            state == TransferState.FAILED ||
            state == TransferState.CANCELLED
}

enum class TransferDirection { DOWNLOAD, UPLOAD }

enum class TransferState { RUNNING, PAUSED, DONE, FAILED, CANCELLED }
