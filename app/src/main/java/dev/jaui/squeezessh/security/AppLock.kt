package dev.jaui.squeezessh.security

import android.util.Base64
import dev.jaui.squeezessh.data.crypto.SecretVault
import dev.jaui.squeezessh.data.crypto.SecretVaultException
import dev.jaui.squeezessh.data.settings.AppSettings
import dev.jaui.squeezessh.data.settings.AutoLockDelay
import dev.jaui.squeezessh.util.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Verriegelt die App.
 *
 * Der PIN wird **nicht** gespeichert, auch nicht verschluesselt. Abgelegt wird ein
 * Nachweis: `salt` plus PBKDF2-Ableitung, und das Ganze zusaetzlich mit dem
 * Geraeteschluessel versiegelt. Beim Pruefen wird neu abgeleitet und verglichen.
 *
 * Das ist bewusst strenger als bei JuiceSSH: dort lag der PIN verschluesselt vor, aber mit
 * einem Schluessel, der aus einer Konstanten im Programmcode und dem Kontonamen bestand —
 * wer die Datei hatte, hatte den PIN (siehe docs/juicessh-datenformat.md). Ein Nachweis
 * laesst sich dagegen nicht zurueckrechnen.
 */
@Singleton
class AppLock @Inject constructor(
    private val settings: AppSettings,
    private val vault: SecretVault,
    private val time: TimeSource,
) {

    private val _state = MutableStateFlow(LockState.UNKNOWN)
    val state: StateFlow<LockState> = _state.asStateFlow()

    /** Wann die App zuletzt in den Hintergrund ging; `null`, solange sie im Vordergrund ist. */
    private var backgroundedAt: Long? = null

    private val random = SecureRandom()

    /** Beim Start: verriegelt, wenn eingeschaltet. */
    suspend fun initialize() {
        _state.value = if (isEnabled()) LockState.LOCKED else LockState.UNLOCKED
    }

    suspend fun isEnabled(): Boolean =
        settings.appLockEnabled.first() && settings.pinProof.first() != null

    fun onEnteredBackground() {
        if (_state.value == LockState.UNLOCKED) backgroundedAt = time.nowMillis()
    }

    /**
     * Prueft beim Zurueckkehren, ob die Wartezeit abgelaufen ist.
     *
     * Die Zeit wird beim Verlassen genommen und hier verglichen, statt einen Zeitgeber
     * laufen zu lassen: ein Prozess im Hintergrund darf nicht darauf angewiesen sein,
     * ueberhaupt noch Rechenzeit zu bekommen.
     */
    suspend fun onEnteredForeground() {
        if (!isEnabled()) {
            _state.value = LockState.UNLOCKED
            return
        }
        val left = backgroundedAt ?: return
        backgroundedAt = null

        val delay = settings.autoLockDelay.first()
        if (delay == AutoLockDelay.NEVER) return

        val elapsedSeconds = (time.nowMillis() - left) / 1000
        if (elapsedSeconds >= delay.seconds) _state.value = LockState.LOCKED
    }

    fun lockNow() {
        _state.value = LockState.LOCKED
    }

    fun unlock() {
        _state.value = LockState.UNLOCKED
        backgroundedAt = null
    }

    /**
     * Legt einen neuen PIN an.
     *
     * @throws AppLockException wenn der PIN zu kurz ist — vier Stellen sind das Minimum,
     *   das ueberhaupt einen Unterschied macht.
     */
    suspend fun setPin(pin: String) {
        if (pin.length < MIN_PIN_LENGTH) {
            throw AppLockException("Der PIN braucht mindestens $MIN_PIN_LENGTH Stellen.")
        }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val proof = "${salt.toBase64()}:${derive(pin, salt).toBase64()}"
        settings.setAppLock(enabled = true, proof = vault.seal(proof))
    }

    suspend fun disable() = settings.setAppLock(enabled = false, proof = null)

    /**
     * Prueft einen eingegebenen PIN.
     *
     * Der Vergleich laeuft in konstanter Zeit. Ein PIN hat vier bis sechs Stellen; ohne
     * das liesse sich aus der Antwortzeit ablesen, wie viele Stellen schon stimmen.
     */
    suspend fun verifyPin(pin: String): Boolean {
        val sealed = settings.pinProof.first() ?: return false
        val proof = try {
            vault.reveal(sealed)
        } catch (e: SecretVaultException) {
            return false
        } ?: return false

        val parts = proof.split(':')
        if (parts.size != 2) return false

        val salt = try {
            Base64.decode(parts[0], Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            return false
        }
        val expected = parts[1]
        return MessageDigest.isEqual(
            derive(pin, salt).toBase64().toByteArray(),
            expected.toByteArray(),
        )
    }

    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_BITS)
        return SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(spec).encoded
    }

    private fun ByteArray.toBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    companion object {
        const val MIN_PIN_LENGTH = 4

        private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val SALT_BYTES = 16
        private const val KEY_BITS = 256

        /**
         * 120 000 Runden. Der PIN hat wenig Entropie, deshalb muss das Ableiten teuer
         * genug sein, um ein Durchprobieren zu bremsen — aber schnell genug, dass das
         * Entriegeln nicht spuerbar haengt.
         */
        private const val ITERATIONS = 120_000
    }
}

enum class LockState {
    /** Noch nicht entschieden — die Einstellungen sind noch nicht gelesen. */
    UNKNOWN,
    LOCKED,
    UNLOCKED,
}

class AppLockException(message: String) : Exception(message)
