package dev.jaui.squeezessh.ssh

import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Zugang zur sshd-Testumgebung aus `tools/test-sshd`.
 *
 * Die Tests, die davon abhaengen, werden **uebersprungen** statt zu scheitern, wenn die
 * Container nicht laufen. Ein rotes Testergebnis soll bedeuten, dass der Code kaputt ist,
 * nicht dass Docker gerade nicht gestartet wurde.
 *
 * Starten mit `tools\test-sshd\up.ps1`.
 */
internal object TestSshd {

    const val HOST = "127.0.0.1"
    const val TARGET_PORT = 2222
    const val BASTION_PORT = 2223

    /** Hostname, unter dem der Bastion das Ziel im Docker-Netz erreicht. */
    const val TARGET_INTERNAL_HOST = "target"
    const val TARGET_INTERNAL_PORT = 22

    const val PASSWORD_USER = "pwuser"
    const val PASSWORD = "testpass123"
    const val KEY_USER = "keyuser"
    const val LOCKED_KEY_USER = "lockuser"
    const val KEY_PASSPHRASE = "keypass123"

    val isRunning: Boolean by lazy { canReach(TARGET_PORT) && canReach(BASTION_PORT) }

    val privateKey: String by lazy { keyFile("id_ed25519").readText() }
    val publicKey: String by lazy { keyFile("id_ed25519.pub").readText() }
    val lockedPrivateKey: String by lazy { keyFile("id_ed25519_locked").readText() }
    val rsaPrivateKey: String by lazy { keyFile("id_rsa").readText() }

    private fun canReach(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(HOST, port), 1_500) }
        true
    } catch (e: Exception) {
        false
    }

    /**
     * Findet `tools/test-sshd/keys` unabhaengig davon, ob Gradle aus dem Projekt- oder
     * aus dem Modulverzeichnis startet.
     */
    private fun keyFile(name: String): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            val candidate = File(directory, "tools/test-sshd/keys/$name")
            if (candidate.isFile) return candidate
            directory = directory.parentFile
        }
        error("Testschluessel $name nicht gefunden — tools\\test-sshd\\up.ps1 ausfuehren")
    }
}
