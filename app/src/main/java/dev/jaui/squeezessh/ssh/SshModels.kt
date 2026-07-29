package dev.jaui.squeezessh.ssh

import dev.jaui.squeezessh.data.model.HostKeyType

/**
 * Ein aufgeloester Sprung der Verbindungskette.
 *
 * Bewusst ohne Verweise auf die Datenbank: der SSH-Layer bekommt fertige Werte und muss
 * nichts nachladen. Das haelt ihn testbar und verhindert, dass Geheimnisse laenger als
 * noetig im Speicher herumliegen.
 */
data class SshHop(
    val host: String,
    val port: Int,
    val username: String,
    val credentials: List<SshCredential>,
)

/** Ein Anmeldeverfahren. Mehrere pro Sprung sind erlaubt und werden der Reihe nach probiert. */
sealed interface SshCredential {

    data class Password(val password: String) : SshCredential

    /**
     * @param privateKey Schluessel im PEM- oder openssh-key-v1-Format.
     * @param passphrase `null` wenn der Schluessel unverschluesselt ist.
     */
    data class PrivateKey(
        val privateKey: String,
        val passphrase: String? = null,
        val publicKey: String? = null,
    ) : SshCredential

    /**
     * Server fragt frei formulierte Prompts ab. Wird oft nur als anderer Weg zum
     * Passwort benutzt, kann aber auch ein zweiter Faktor sein — deshalb liegt die
     * Beantwortung beim Aufrufer.
     */
    data class KeyboardInteractive(
        val respond: (prompts: List<String>) -> List<String>,
    ) : SshCredential
}

/**
 * Was der Nutzer entscheiden muss, wenn ein Host-Key nicht bereits vertraut ist.
 *
 * Der Unterschied zwischen [isChange] `false` und `true` ist der zwischen einer
 * Formalitaet und einem Alarm: beim Erstkontakt gibt es nichts zu vergleichen, ein
 * geaenderter Key kann dagegen ein Angreifer in der Leitung sein.
 */
data class HostKeyPrompt(
    val host: String,
    val port: Int,
    val keyType: HostKeyType,
    val fingerprint: String,
    /** Der Schluessel selbst, damit er nach der Zustimmung gespeichert werden kann. */
    val publicKey: String,
    val previousFingerprint: String? = null,
) {
    val isChange: Boolean get() = previousFingerprint != null
}

/** Zustand einer Verbindung, wie ihn die Oberflaeche anzeigt. */
sealed interface SshConnectionState {
    data object Idle : SshConnectionState
    data class Connecting(val step: String) : SshConnectionState
    data object Connected : SshConnectionState
    data class Disconnected(val reason: String?) : SshConnectionState
    data class Failed(val error: SshError) : SshConnectionState
}

/**
 * Fehler in der Form, in der sie dem Nutzer etwas sagen.
 *
 * SSHJ wirft ueber ein Dutzend Ausnahmetypen, deren Meldungen fuer Endnutzer wenig
 * hergeben. Hier wird auf die Faelle reduziert, bei denen sich unterschiedlich handeln
 * laesst.
 */
sealed interface SshError {
    val detail: String?

    /** Host nicht erreichbar, Verbindung abgelehnt, Zeitueberschreitung. */
    data class Unreachable(override val detail: String?) : SshError

    /** Alle hinterlegten Anmeldeverfahren abgelehnt. */
    data class AuthenticationFailed(override val detail: String?) : SshError

    /**
     * Der Host-Key ist unbekannt oder hat sich geaendert — der Verbindungsaufbau wurde
     * deshalb abgebrochen und wartet auf eine Entscheidung.
     *
     * Bewusst ein Abbruch und keine Rueckfrage mitten im Handshake: waehrend der Nutzer
     * einen Fingerprint prueft, laeuft sonst das Transport-Timeout ab und die Verbindung
     * stirbt genau dann, wenn jemand sorgfaeltig ist.
     */
    data class HostKeyUnverified(
        val prompt: HostKeyPrompt,
        override val detail: String? = null,
    ) : SshError

    /** Nutzer hat den Host-Key nicht bestaetigt. */
    data class HostKeyRejected(override val detail: String?) : SshError

    /** Privater Schluessel unlesbar oder falsche Passphrase. */
    data class KeyUnusable(override val detail: String?) : SshError

    /** Verbindung stand und ist weggebrochen. */
    data class ConnectionLost(override val detail: String?) : SshError

    data class Unexpected(override val detail: String?) : SshError
}

class SshException(val error: SshError, cause: Throwable? = null) :
    Exception(error.detail ?: error::class.simpleName, cause)
