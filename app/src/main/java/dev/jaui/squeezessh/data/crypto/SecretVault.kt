package dev.jaui.squeezessh.data.crypto

/**
 * Verwahrt Geheimnisse (Passwoerter, Private Keys, Key-Passphrasen, AWS-Secrets).
 *
 * Der Klartext verlaesst diese Schnittstelle nur ueber [reveal] und wird nie in der
 * Datenbank abgelegt — dort steht ausschliesslich das Ergebnis von [seal].
 *
 * Als Interface modelliert, damit Tests ohne Android Keystore auskommen.
 */
interface SecretVault {

    /** Verschluesselt [plaintext]; `null` bleibt `null`. */
    fun seal(plaintext: String?): String?

    /**
     * Entschluesselt einen von [seal] erzeugten Wert.
     *
     * @throws SecretVaultException wenn der Wert nicht entschluesselt werden kann, etwa
     *   weil der Schluessel nach einem Wiederherstellen auf einem anderen Geraet fehlt.
     */
    fun reveal(ciphertext: String?): String?
}

class SecretVaultException(message: String, cause: Throwable? = null) : Exception(message, cause)
