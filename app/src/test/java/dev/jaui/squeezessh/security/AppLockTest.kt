package dev.jaui.squeezessh.security

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import dev.jaui.squeezessh.data.FakeSecretVault
import dev.jaui.squeezessh.data.settings.AppSettings
import dev.jaui.squeezessh.data.settings.AutoLockDelay
import dev.jaui.squeezessh.util.TimeSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class AppLockTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var settings: AppSettings
    private lateinit var lock: AppLock
    private var now = 1_000_000L

    @Before
    fun setUp() {
        // Frischer DataStore pro Test: sonst schwappt ein gesetzter PIN in den naechsten.
        val file = File(temp.newFolder(), "settings.preferences_pb")
        settings = AppSettings(PreferenceDataStoreFactory.create { file })
        lock = AppLock(settings, FakeSecretVault(), TimeSource { now })
    }

    @Test
    fun `ohne eingeschalteten Lock ist die App offen`() = runTest {
        lock.initialize()

        assertThat(lock.state.value).isEqualTo(LockState.UNLOCKED)
        assertThat(lock.isEnabled()).isFalse()
    }

    @Test
    fun `nach dem Setzen eines PIN ist der Lock aktiv`() = runTest {
        lock.setPin("1234")

        assertThat(lock.isEnabled()).isTrue()
        lock.initialize()
        assertThat(lock.state.value).isEqualTo(LockState.LOCKED)
    }

    @Test
    fun `richtiger PIN wird angenommen, falscher nicht`() = runTest {
        lock.setPin("246810")

        assertThat(lock.verifyPin("246810")).isTrue()
        assertThat(lock.verifyPin("246811")).isFalse()
        assertThat(lock.verifyPin("")).isFalse()
        assertThat(lock.verifyPin("2468100")).isFalse()
    }

    /** Der Nachweis darf den PIN nicht enthalten — sonst waere er kein Nachweis. */
    @Test
    fun `der gespeicherte Nachweis enthaelt den PIN nicht`() = runTest {
        lock.setPin("135790")

        val stored = settings.pinProofValue()
        assertThat(stored).isNotNull()
        assertThat(stored).doesNotContain("135790")
    }

    /** Zwei gleiche PINs muessen verschiedene Nachweise ergeben, sonst waere das Salz nutzlos. */
    @Test
    fun `gleicher PIN ergibt unterschiedliche Nachweise`() = runTest {
        lock.setPin("1234")
        val first = settings.pinProofValue()

        lock.setPin("1234")
        val second = settings.pinProofValue()

        assertThat(first).isNotEqualTo(second)
        assertThat(lock.verifyPin("1234")).isTrue()
    }

    @Test
    fun `zu kurzer PIN wird abgelehnt`() = runTest {
        val error = assertThrows(AppLockException::class.java) {
            kotlinx.coroutines.runBlocking { lock.setPin("123") }
        }

        assertThat(error).hasMessageThat().contains("4")
        assertThat(lock.isEnabled()).isFalse()
    }

    @Test
    fun `abschalten entfernt den Nachweis`() = runTest {
        lock.setPin("1234")
        lock.disable()

        assertThat(lock.isEnabled()).isFalse()
        assertThat(settings.pinProofValue()).isNull()
        assertThat(lock.verifyPin("1234")).isFalse()
    }

    // --- Automatisches Verriegeln ------------------------------------------------------

    @Test
    fun `nach Ablauf der Wartezeit verriegelt die App`() = runTest {
        lock.setPin("1234")
        settings.setAutoLockDelay(AutoLockDelay.AFTER_30_SECONDS)
        lock.unlock()

        lock.onEnteredBackground()
        now += 31_000
        lock.onEnteredForeground()

        assertThat(lock.state.value).isEqualTo(LockState.LOCKED)
    }

    @Test
    fun `kurze Abwesenheit verriegelt nicht`() = runTest {
        lock.setPin("1234")
        settings.setAutoLockDelay(AutoLockDelay.AFTER_30_SECONDS)
        lock.unlock()

        lock.onEnteredBackground()
        now += 5_000
        lock.onEnteredForeground()

        assertThat(lock.state.value).isEqualTo(LockState.UNLOCKED)
    }

    @Test
    fun `sofort bedeutet auch bei kuerzester Abwesenheit verriegeln`() = runTest {
        lock.setPin("1234")
        settings.setAutoLockDelay(AutoLockDelay.IMMEDIATELY)
        lock.unlock()

        lock.onEnteredBackground()
        lock.onEnteredForeground()

        assertThat(lock.state.value).isEqualTo(LockState.LOCKED)
    }

    @Test
    fun `nie bedeutet nie verriegeln`() = runTest {
        lock.setPin("1234")
        settings.setAutoLockDelay(AutoLockDelay.NEVER)
        lock.unlock()

        lock.onEnteredBackground()
        now += 10 * 24 * 3600 * 1000L
        lock.onEnteredForeground()

        assertThat(lock.state.value).isEqualTo(LockState.UNLOCKED)
    }

    @Test
    fun `von Hand verriegeln wirkt sofort`() = runTest {
        lock.setPin("1234")
        lock.unlock()

        lock.lockNow()

        assertThat(lock.state.value).isEqualTo(LockState.LOCKED)
    }

    /**
     * Ein zweites Zurueckkehren ohne zwischenzeitliches Verlassen darf nicht erneut
     * verriegeln — sonst sperrt sich die App im Betrieb selbst aus.
     */
    @Test
    fun `zweites Zurueckkehren ohne Verlassen verriegelt nicht erneut`() = runTest {
        lock.setPin("1234")
        settings.setAutoLockDelay(AutoLockDelay.AFTER_30_SECONDS)
        lock.unlock()

        lock.onEnteredBackground()
        now += 5_000
        lock.onEnteredForeground()
        now += 60_000
        lock.onEnteredForeground()

        assertThat(lock.state.value).isEqualTo(LockState.UNLOCKED)
    }

    private suspend fun AppSettings.pinProofValue(): String? = pinProof.first()
}
