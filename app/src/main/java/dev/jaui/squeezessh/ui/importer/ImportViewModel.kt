package dev.jaui.squeezessh.ui.importer

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jaui.squeezessh.data.importer.DuplicateStrategy
import dev.jaui.squeezessh.data.importer.ImportPreview
import dev.jaui.squeezessh.data.importer.ImportReport
import dev.jaui.squeezessh.data.importer.JuiceSshBackup
import dev.jaui.squeezessh.data.importer.JuiceSshFormatException
import dev.jaui.squeezessh.data.importer.JuiceSshImporter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

@HiltViewModel
class ImportViewModel @Inject constructor(
    application: Application,
    private val importer: JuiceSshImporter,
    private val io: CoroutineDispatcher,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    /**
     * Uebernimmt die vom Nutzer gewaehlte Datenbank.
     *
     * Der Inhalt wird in den App-Cache kopiert: SQLite braucht einen echten Dateipfad,
     * ein `content://`-Uri genuegt nicht.
     */
    fun onDatabasePicked(uri: Uri) = copyThenPreview(uri, isDatabase = true)

    fun onPreferencesPicked(uri: Uri) = copyThenPreview(uri, isDatabase = false)

    private fun copyThenPreview(uri: Uri, isDatabase: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val file = withContext(io) {
                    copyToCache(uri, if (isDatabase) "juicessh-import.db" else "juicessh-prefs.xml")
                }
                val name = withContext(io) { displayName(uri) }
                _state.update {
                    if (isDatabase) {
                        it.copy(databaseFile = file, databaseName = name)
                    } else {
                        it.copy(preferencesFile = file, preferencesName = name)
                    }
                }
                refreshPreview()
            } catch (e: IOException) {
                _state.update { it.copy(busy = false, error = "Datei ließ sich nicht lesen: ${e.message}") }
            }
        }
    }

    private suspend fun refreshPreview() {
        val database = _state.value.databaseFile
        if (database == null) {
            _state.update { it.copy(busy = false) }
            return
        }
        try {
            val preview = importer.preview(
                JuiceSshBackup(database, _state.value.preferencesFile),
            )
            _state.update { it.copy(busy = false, preview = preview, error = null) }
        } catch (e: JuiceSshFormatException) {
            _state.update {
                it.copy(
                    busy = false,
                    preview = null,
                    databaseFile = null,
                    databaseName = null,
                    error = "Das ist keine JuiceSSH-Datenbank. Erwartet wird die Datei aus " +
                        "/data/data/com.sonelli.juicessh/databases/ — sie trägt keinen " +
                        "sprechenden Namen, sondern eine Zeichenkette wie „19a093afcee…“.",
                )
            }
        }
    }

    fun startImport(strategy: DuplicateStrategy = DuplicateStrategy.SKIP) {
        val database = _state.value.databaseFile ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val report = importer.import(
                    JuiceSshBackup(database, _state.value.preferencesFile),
                    strategy,
                )
                _state.update { it.copy(busy = false, report = report) }
            } catch (e: JuiceSshFormatException) {
                _state.update { it.copy(busy = false, error = e.message) }
            } finally {
                // Die Kopien enthalten Zugangsdaten und haben nach dem Import nichts
                // mehr im Cache verloren.
                withContext(io) {
                    _state.value.databaseFile?.delete()
                    _state.value.preferencesFile?.delete()
                }
            }
        }
    }

    fun reset() {
        viewModelScope.launch {
            withContext(io) {
                _state.value.databaseFile?.delete()
                _state.value.preferencesFile?.delete()
            }
            _state.value = ImportUiState()
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    private fun copyToCache(uri: Uri, filename: String): File {
        val context = getApplication<Application>()
        val target = File(context.cacheDir, filename)
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Kein Zugriff auf die gewählte Datei")
        stream.use { input -> target.outputStream().use(input::copyTo) }
        return target
    }

    private fun displayName(uri: Uri): String? {
        val context = getApplication<Application>()
        return context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: uri.lastPathSegment
    }
}

data class ImportUiState(
    val databaseFile: File? = null,
    val databaseName: String? = null,
    val preferencesFile: File? = null,
    val preferencesName: String? = null,
    val preview: ImportPreview? = null,
    val report: ImportReport? = null,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val canImport: Boolean get() = databaseFile != null && preview != null && !busy && report == null
}
