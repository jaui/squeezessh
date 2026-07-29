package dev.jaui.squeezessh.data.importer

import android.util.Base64
import dev.jaui.squeezessh.data.model.BackspaceMode
import dev.jaui.squeezessh.data.model.ConnectionType
import dev.jaui.squeezessh.data.model.HostKeyType
import dev.jaui.squeezessh.data.model.PortForwardMode
import java.security.MessageDigest

/**
 * Uebersetzt JuiceSSHs Speicherformat in das eigene Modell.
 *
 * Die Ordinalzahlen wurden auf einem Emulator gegen die laufende App verifiziert, nicht
 * geraten — siehe docs/juicessh-datenformat.md.
 */
internal object JuiceSshMapping {

    fun connectionType(ordinal: Int?): ConnectionType = when (ordinal) {
        0 -> ConnectionType.SSH
        1 -> ConnectionType.MOSH
        2 -> ConnectionType.LOCAL
        3 -> ConnectionType.TELNET
        else -> ConnectionType.SSH
    }

    fun backspaceMode(ordinal: Int?): BackspaceMode = when (ordinal) {
        1 -> BackspaceMode.BACKSPACE // „Legacy (sends ^H)"
        else -> BackspaceMode.DELETE // „Default (sends DEL)"
    }

    fun portForwardMode(ordinal: Int?): PortForwardMode = when (ordinal) {
        1 -> PortForwardMode.REMOTE
        2 -> PortForwardMode.DYNAMIC // im Original MODE_SOCKS
        else -> PortForwardMode.LOCAL
    }

    /**
     * Zerlegt `host.hostname`.
     *
     * JuiceSSH speichert in der OpenSSH-Schreibweise `[host]:port`. Aeltere Eintraege
     * koennen auch nur den Hostnamen enthalten; dann gilt Port 22.
     */
    fun parseHostname(raw: String?): Pair<String, Int>? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null

        val bracketed = Regex("""^\[(.+)]:(\d+)$""").matchEntire(value)
        if (bracketed != null) {
            val (host, port) = bracketed.destructured
            return host to (port.toIntOrNull() ?: DEFAULT_PORT)
        }
        return value to DEFAULT_PORT
    }

    /**
     * Bestimmt den Host-Key-Typ aus dem Key selbst.
     *
     * Die Spalte `host.type` ist in der Praxis leer — JuiceSSH fuellt sie nicht. Der
     * Algorithmus steht aber im Key: SSH-Keys beginnen im Wire-Format mit einer
     * 4-Byte-Laenge und danach dem Algorithmusnamen, etwa `ssh-ed25519`.
     */
    fun hostKeyType(base64Key: String?, declaredType: String? = null): HostKeyType {
        declaredType?.takeIf { it.isNotBlank() }?.let { declared ->
            val fromDeclaration = HostKeyType.fromWireName(declared)
            if (fromDeclaration != HostKeyType.UNKNOWN) return fromDeclaration
        }
        val algorithm = readAlgorithmName(base64Key) ?: return HostKeyType.UNKNOWN
        return HostKeyType.fromWireName(algorithm)
    }

    private fun readAlgorithmName(base64Key: String?): String? {
        if (base64Key.isNullOrBlank()) return null
        val bytes = try {
            Base64.decode(base64Key.trim(), Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (bytes.size < 4) return null

        val length = ((bytes[0].toInt() and 0xFF) shl 24) or
            ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or
            (bytes[3].toInt() and 0xFF)
        // Ein Algorithmusname ist kurz; alles andere heisst, wir lesen keinen SSH-Key.
        if (length !in 1..64 || bytes.size < 4 + length) return null

        return String(bytes, 4, length, Charsets.US_ASCII)
    }

    /**
     * SHA-256-Fingerprint in OpenSSH-Schreibweise.
     *
     * JuiceSSH speichert einen eigenen Fingerprint mit; der wird uebernommen, wenn er
     * vorhanden ist. Fehlt er, wird er hier aus dem Key berechnet, damit im UI immer
     * etwas Vergleichbares steht.
     */
    fun fingerprint(storedFingerprint: String?, base64Key: String?): String {
        storedFingerprint?.takeIf { it.isNotBlank() }?.let { return it }
        if (base64Key.isNullOrBlank()) return ""
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(Base64.decode(base64Key.trim(), Base64.DEFAULT))
            "SHA256:" + Base64.encodeToString(digest, Base64.NO_WRAP or Base64.NO_PADDING)
        } catch (e: IllegalArgumentException) {
            ""
        }
    }

    private const val DEFAULT_PORT = 22
}
