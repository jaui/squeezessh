package dev.jaui.squeezessh.ssh

import android.util.Base64
import dev.jaui.squeezessh.data.model.HostKeyType
import dev.jaui.squeezessh.data.repository.HostKeyVerdict
import dev.jaui.squeezessh.data.repository.KnownHostRepository
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.MessageDigest
import java.security.PublicKey

/**
 * Prueft Host-Keys gegen die eigenen Known Hosts und fragt sonst den Nutzer.
 *
 * SSHJ ruft [verify] auf seinem Transport-Thread auf und erwartet synchron ein Ergebnis.
 * Die Nutzerentscheidung ist aber asynchron, deshalb wird hier ueberbrueckt. Genau
 * deswegen darf [decide] **nicht** auf denselben Thread zurueckwechseln, auf dem der
 * Verbindungsaufbau laeuft.
 *
 * Wichtig fuer Sprung-Ketten: bei einer Verbindung durch einen Tunnel meldet SSHJ als
 * Host und Port das lokale Ende des Tunnels, nicht den tatsaechlichen Server. Deshalb
 * kommen [logicalHost] und [logicalPort] von aussen und die Argumente des Callbacks
 * werden bewusst ignoriert.
 */
class KnownHostVerifier(
    private val knownHosts: KnownHostRepository,
    private val logicalHost: String,
    private val logicalPort: Int,
    private val decide: suspend (HostKeyPrompt) -> Boolean,
) : HostKeyVerifier {

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean = runBlocking {
        val keyType = key.toHostKeyType()
        val encoded = key.toBase64()

        when (val verdict = knownHosts.verify(logicalHost, logicalPort, keyType, encoded)) {
            HostKeyVerdict.Trusted -> true

            HostKeyVerdict.Unknown -> confirmAndRemember(
                prompt = HostKeyPrompt(
                    host = logicalHost,
                    port = logicalPort,
                    keyType = keyType,
                    fingerprint = fingerprintOf(key),
                ),
                keyType = keyType,
                encoded = encoded,
                fingerprint = fingerprintOf(key),
            )

            is HostKeyVerdict.Changed -> confirmAndRemember(
                prompt = HostKeyPrompt(
                    host = logicalHost,
                    port = logicalPort,
                    keyType = keyType,
                    fingerprint = fingerprintOf(key),
                    previousFingerprint = verdict.known.fingerprint,
                ),
                keyType = keyType,
                encoded = encoded,
                fingerprint = fingerprintOf(key),
            )
        }
    }

    private suspend fun confirmAndRemember(
        prompt: HostKeyPrompt,
        keyType: HostKeyType,
        encoded: String,
        fingerprint: String,
    ): Boolean {
        if (!decide(prompt)) return false
        knownHosts.trust(logicalHost, logicalPort, keyType, encoded, fingerprint)
        return true
    }

    /**
     * Teilt SSHJ mit, welche Host-Key-Verfahren wir fuer diesen Server schon kennen.
     *
     * Ohne das koennte der Server ein anderes Verfahren aushandeln als beim letzten Mal
     * und der Nutzer bekaeme faelschlich eine Erstkontakt-Abfrage.
     */
    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = runBlocking {
        knownHosts.findFor(logicalHost, logicalPort).map { it.keyType.wireName }
    }

    private fun PublicKey.toBase64(): String {
        val buffer = Buffer.PlainBuffer()
        KeyType.fromKey(this).putPubKeyIntoBuffer(this, buffer)
        return Base64.encodeToString(buffer.compactData, Base64.NO_WRAP)
    }

    private fun PublicKey.toHostKeyType(): HostKeyType =
        HostKeyType.fromWireName(KeyType.fromKey(this).toString())

    /** SHA-256-Fingerprint in der Schreibweise, die auch OpenSSH anzeigt. */
    private fun fingerprintOf(key: PublicKey): String {
        val buffer = Buffer.PlainBuffer()
        KeyType.fromKey(key).putPubKeyIntoBuffer(key, buffer)
        val digest = MessageDigest.getInstance("SHA-256").digest(buffer.compactData)
        return "SHA256:" + Base64.encodeToString(digest, Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
