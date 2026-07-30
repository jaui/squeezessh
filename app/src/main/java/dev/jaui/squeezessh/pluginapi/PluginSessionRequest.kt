package dev.jaui.squeezessh.pluginapi

import android.net.Uri

/**
 * Erkennt die Form `ssh://<uuid>`, mit der ein Plugin eine gespeicherte Verbindung meint.
 *
 * Nötig, weil dasselbe Schema zwei Bedeutungen trägt: `ssh://root@host:22` ist ein
 * Verbindungsziel für den Schnellzugriff, `ssh://41b4f013-…` dagegen ein Verweis auf eine
 * bereits gespeicherte Verbindung. Auseinander hält sie nur die Form — deshalb wird hier
 * streng auf eine UUID geprüft und nicht bloß auf „enthält keine Punkte".
 */
object PluginSessionRequest {

    private val UUID_PATTERN = Regex(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
    )

    /** @return die UUID, wenn [uri] eine gespeicherte Verbindung meint, sonst `null`. */
    fun publicIdOf(uri: Uri?): String? {
        if (uri == null || !uri.scheme.equals("ssh", ignoreCase = true)) return null
        // `ssh://<uuid>` landet je nach Schreibweise in host oder in schemeSpecificPart.
        val candidate = uri.host
            ?: uri.schemeSpecificPart?.trimStart('/')?.substringBefore('/')
            ?: return null
        return candidate.lowercase().takeIf { UUID_PATTERN.matches(it) }
    }

    fun isPluginRequest(uri: Uri?): Boolean = publicIdOf(uri) != null
}
