package dev.jaui.squeezessh.ssh

import android.util.Base64
import dev.jaui.squeezessh.data.db.entity.KnownHostEntity
import dev.jaui.squeezessh.data.model.HostKeyType
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.MessageDigest
import java.security.PublicKey

/**
 * Prueft Host-Keys gegen die bereits bekannten.
 *
 * Arbeitet ausschliesslich auf einer vorher geladenen Liste und fragt weder Datenbank noch
 * Nutzer: SSHJ ruft [verify] mitten im Schluesselaustausch auf, und alles, was dort
 * wartet, laeuft in das Transport-Timeout. Ist der Schluessel nicht bekannt, wird die
 * Verbindung abgelehnt und der Fall ueber [pendingPrompt] nach oben gereicht, wo in Ruhe
 * gefragt werden kann.
 *
 * Bei einer Verbindung durch einen Tunnel meldet SSHJ als Host und Port das lokale
 * Tunnelende. [logicalHost] und [logicalPort] kommen deshalb von aussen; die Argumente des
 * Callbacks werden bewusst ignoriert.
 */
class KnownHostVerifier(
    private val known: List<KnownHostEntity>,
    private val logicalHost: String,
    private val logicalPort: Int,
) : HostKeyVerifier {

    /** Gesetzt, wenn [verify] eine Entscheidung braucht. */
    @Volatile var pendingPrompt: HostKeyPrompt? = null
        private set

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val keyType = HostKeyType.fromWireName(KeyType.fromKey(key).toString())
        val encoded = key.encodeToBase64()
        val entry = known.firstOrNull { it.keyType == keyType }

        if (entry != null && entry.publicKey == encoded) return true

        pendingPrompt = HostKeyPrompt(
            host = logicalHost,
            port = logicalPort,
            keyType = keyType,
            fingerprint = key.fingerprint(),
            publicKey = encoded,
            previousFingerprint = entry?.fingerprint,
        )
        return false
    }

    /**
     * Teilt SSHJ mit, welche Verfahren wir fuer diesen Server schon kennen.
     *
     * Ohne das handelt der Server womoeglich ein anderes aus als beim letzten Mal, und der
     * Nutzer bekaeme faelschlich eine Erstkontakt-Abfrage statt einer stillen Bestaetigung.
     */
    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
        known.map { it.keyType.wireName }

    private fun PublicKey.encodeToBase64(): String =
        Base64.encodeToString(wireBytes(), Base64.NO_WRAP)

    /** SHA-256-Fingerprint in der Schreibweise, die auch OpenSSH anzeigt. */
    private fun PublicKey.fingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(wireBytes())
        return "SHA256:" + Base64.encodeToString(digest, Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun PublicKey.wireBytes(): ByteArray {
        val buffer = Buffer.PlainBuffer()
        KeyType.fromKey(this).putPubKeyIntoBuffer(this, buffer)
        return buffer.compactData
    }
}
