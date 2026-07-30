package dev.jaui.squeezessh.pluginapi

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking

/**
 * Der ContentProvider der Plugin-API — bewusst nur eine Hülle.
 *
 * Er tut drei Dinge: die Abhängigkeiten beschaffen, den aufrufenden Paketnamen feststellen
 * und an [PluginApiGateway] weitergeben. Die Abbildung selbst steht dort, weil sie sich so
 * ohne Binder und ohne Hilt-Aufbau prüfen lässt.
 *
 * Wer worauf zugreifen darf, entscheidet Android anhand der `path-permission`-Einträge im
 * Manifest — hier steht keine eigene Rechteprüfung, die damit auseinanderlaufen könnte.
 *
 * [runBlocking] ist hier richtig: Android ruft `query` auf einem Binder-Thread auf und
 * erwartet den Cursor als Rückgabewert.
 */
class PluginProvider : ContentProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun gateway(): PluginApiGateway
    }

    private var cached: PluginApiGateway? = null

    /**
     * Ein Provider wird vor der Application erzeugt, wenn ein Plugin ihn als Erstes
     * anspricht. Die Abhängigkeiten werden deshalb erst beim ersten Zugriff geholt.
     */
    override fun onCreate(): Boolean = true

    private fun gateway(): PluginApiGateway = cached ?: EntryPointAccessors.fromApplication(
        requireNotNull(context) { "Provider ohne Context" }.applicationContext,
        Dependencies::class.java,
    ).gateway().also { cached = it }

    override fun getType(uri: Uri): String? = gateway().mimeTypeOf(uri)

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = runBlocking { gateway().query(uri, caller()) }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = runBlocking {
        gateway().insert(uri, values, caller())?.also { notifyChange(uri) }
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = runBlocking {
        gateway().update(uri, values, caller()).also { if (it > 0) notifyChange(uri) }
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        runBlocking {
            gateway().delete(uri, caller()).also { if (it > 0) notifyChange(uri) }
        }

    private fun notifyChange(uri: Uri) {
        context?.contentResolver?.notifyChange(uri, null)
    }

    /**
     * `callingPackage` ist `null`, wenn der Aufruf nicht über Binder kommt — etwa aus der
     * eigenen App. Für den Prüfpfad ist ein Platzhalter besser als kein Eintrag.
     */
    private fun caller(): String = callingPackage ?: "(unbekannt)"
}
