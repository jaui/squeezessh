package dev.jaui.squeezessh.pluginapi

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking

/**
 * Der ContentProvider der Plugin-API — bewusst nur eine Hülle.
 *
 * Er tut vier Dinge: die Abhängigkeiten beschaffen, die Rechte des Aufrufers prüfen, den
 * aufrufenden Paketnamen feststellen und an [PluginApiGateway] weitergeben. Die Abbildung
 * selbst steht dort, weil sie sich so ohne Binder und ohne Hilt-Aufbau prüfen lässt.
 *
 * Die `path-permission`-Einträge im Manifest reichen als Schranke nicht: Android vergleicht
 * dort den Pfad wörtlich und lässt durch, was auf keine Regel passt, der `UriMatcher`
 * überspringt dagegen leere Segmente. `//connections` kam so ohne jede Permission an die
 * Verbindungen. Deshalb prüft der Provider hier selbst, welche Permission das gematchte
 * Modell verlangt ([PluginApiGateway.requiredPermission]) — das Manifest bleibt die erste,
 * aber nicht mehr die einzige Schranke.
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

    /** Ohne Leserecht gibt es auch keinen Typ — aber keine Exception, die Android stört. */
    override fun getType(uri: Uri): String? =
        if (mayAccess(uri, write = false)) gateway().mimeTypeOf(uri) else null

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        enforceAccess(uri, write = false)
        return runBlocking { gateway().query(uri, caller()) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        enforceAccess(uri, write = true)
        return runBlocking {
            gateway().insert(uri, values, caller())?.also { notifyChange(uri) }
        }
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        enforceAccess(uri, write = true)
        return runBlocking {
            gateway().update(uri, values, caller()).also { if (it > 0) notifyChange(uri) }
        }
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        enforceAccess(uri, write = true)
        return runBlocking {
            gateway().delete(uri, caller()).also { if (it > 0) notifyChange(uri) }
        }
    }

    private fun enforceAccess(uri: Uri, write: Boolean) {
        if (!mayAccess(uri, write)) {
            throw SecurityException(
                "${caller()} darf $uri nicht ${if (write) "schreiben" else "lesen"}",
            )
        }
    }

    /**
     * Die eigene App braucht keine Permission — Android lässt sie ebenfalls ohne Prüfung
     * durch, und `checkCallingPermission` lehnt Aufrufe außerhalb von Binder ohnehin ab.
     * Einen Pfad außerhalb des Vertrags bekommt aber auch sie nicht.
     */
    private fun mayAccess(uri: Uri, write: Boolean): Boolean {
        val permission = gateway().requiredPermission(uri, write) ?: return false
        if (Binder.getCallingUid() == Process.myUid()) return true
        val context = context ?: return false
        return context.checkCallingPermission(permission) == PackageManager.PERMISSION_GRANTED
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
