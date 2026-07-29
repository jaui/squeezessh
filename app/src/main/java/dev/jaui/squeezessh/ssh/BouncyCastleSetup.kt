package dev.jaui.squeezessh.ssh

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * Ersetzt Androids mitgelieferten BouncyCastle-Provider durch den vollstaendigen.
 *
 * Android registriert unter dem Namen `BC` eine stark abgespeckte Variante. Ihr fehlen
 * unter anderem X25519 und Ed25519, also genau das, was moderne SSH-Server zur
 * Schluesselaushandlung anbieten. SSHJ fragt Algorithmen namentlich bei `BC` an und
 * scheitert dann mit `no such algorithm: X25519 for provider BC` — und zwar erst beim
 * Verbindungsaufbau, nicht beim Start.
 *
 * Der eingebaute Provider muss deshalb entfernt und der mitgelieferte an erster Stelle
 * eingesetzt werden; einfach zusaetzlich registrieren geht nicht, weil der Name `BC`
 * bereits belegt waere.
 *
 * Muss laufen, bevor die erste SSH-Verbindung aufgebaut wird.
 */
object BouncyCastleSetup {

    @Volatile private var done = false

    @Synchronized
    fun install() {
        if (done) return
        val existing = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
        if (existing == null || existing.javaClass != BouncyCastleProvider::class.java) {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
        done = true
    }
}
