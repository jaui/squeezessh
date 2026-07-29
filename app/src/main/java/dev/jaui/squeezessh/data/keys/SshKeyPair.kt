package dev.jaui.squeezessh.data.keys

/**
 * Ein erzeugtes oder importiertes Schluesselpaar.
 *
 * @param privateKeyPem der private Teil in PEM-Form, so wie ihn auch OpenSSH schreibt.
 * @param publicKeyLine der oeffentliche Teil als eine Zeile fuer `~/.ssh/authorized_keys`.
 * @param fingerprint SHA-256-Fingerprint in OpenSSH-Schreibweise.
 */
data class SshKeyPair(
    val type: SshKeyType,
    val privateKeyPem: String,
    val publicKeyLine: String,
    val fingerprint: String,
)

enum class SshKeyType(val wireName: String, val label: String) {
    /**
     * Der Normalfall: kurz, schnell, von jedem OpenSSH seit 6.5 unterstuetzt.
     */
    ED25519("ssh-ed25519", "Ed25519"),

    /**
     * Nur noch fuer Server, die nichts anderes koennen. 3072 Bit ist das Minimum, das
     * heute noch als tragfaehig gilt.
     */
    RSA_3072("ssh-rsa", "RSA 3072"),

    RSA_4096("ssh-rsa", "RSA 4096"),
    ;

    val bits: Int
        get() = when (this) {
            ED25519 -> 256
            RSA_3072 -> 3072
            RSA_4096 -> 4096
        }
}

class SshKeyException(message: String, cause: Throwable? = null) : Exception(message, cause)
