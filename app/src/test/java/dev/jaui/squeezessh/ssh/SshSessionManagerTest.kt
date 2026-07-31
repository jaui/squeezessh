package dev.jaui.squeezessh.ssh

import com.google.common.truth.Truth.assertThat
import dev.jaui.squeezessh.data.DatabaseTestCase
import dev.jaui.squeezessh.data.db.entity.ConnectionEntity
import dev.jaui.squeezessh.data.repository.ConnectionRepository
import dev.jaui.squeezessh.data.repository.IdentityRepository
import dev.jaui.squeezessh.data.repository.KnownHostRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream

/**
 * Prueft die Verdrahtung zwischen Datenbank und SSH-Schicht: aus gespeicherten
 * Verbindungen und Identitaeten muss eine funktionierende Sitzung werden — inklusive
 * aufgeloester Sprung-Kette und entschluesselter Zugangsdaten.
 */
@RunWith(RobolectricTestRunner::class)
class SshSessionManagerTest : DatabaseTestCase() {

    private lateinit var connections: ConnectionRepository
    private lateinit var identities: IdentityRepository
    private lateinit var manager: SshSessionManager

    private val acceptAll: suspend (HostKeyPrompt) -> Boolean = { true }

    @Before
    fun setUp() {
        assumeTrue(
            "sshd-Testumgebung laeuft nicht — tools\\test-sshd\\up.ps1 ausfuehren",
            TestSshd.isRunning,
        )
        connections = ConnectionRepository(db.connectionDao(), time)
        identities = IdentityRepository(db.identityDao(), vault, time)
        manager = SshSessionManager(
            connector = SshConnector(KnownHostRepository(db.knownHostDao(), time), Dispatchers.IO),
            connections = connections,
            identities = identities,
            io = Dispatchers.IO,
        )
    }

    private suspend fun storeTarget(): Long {
        val identityId = identities.create(
            nickname = "pwuser",
            username = TestSshd.PASSWORD_USER,
            password = TestSshd.PASSWORD,
        )
        val connectionId = connections.create(
            ConnectionEntity(
                nickname = "Testziel",
                address = TestSshd.HOST,
                port = TestSshd.TARGET_PORT,
                createdAt = 0,
                updatedAt = 0,
            ),
        )
        connections.setIdentities(connectionId, listOf(identityId))
        return connectionId
    }

    @Test
    fun `oeffnet eine Sitzung aus gespeicherten Zugangsdaten`() = runTest {
        val connectionId = storeTarget()

        val session = manager.open(connectionId, onHostKey = acceptAll)

        try {
            assertThat(session.isAlive).isTrue()
            assertThat(session.title).isEqualTo("Testziel")
            assertThat(manager.openSessions.value).containsKey(session.id)
        } finally {
            manager.close(session.id)
        }
        assertThat(manager.openSessions.value).isEmpty()
    }

    /** Das Passwort liegt verschluesselt in der Datenbank — hier zeigt sich, ob es ankommt. */
    @Test
    fun `entschluesselt das Passwort auf dem Weg zur Verbindung`() = runTest {
        val connectionId = storeTarget()

        val session = manager.open(connectionId, onHostKey = acceptAll)

        try {
            session.shell.input.write("whoami\n".toByteArray())
            session.shell.input.flush()
            val output = withTimeout(15_000) { session.readUntil(TestSshd.PASSWORD_USER) }
            assertThat(output).contains(TestSshd.PASSWORD_USER)
        } finally {
            manager.close(session.id)
        }
    }

    /**
     * Eine Identitaet ohne Geheimnis entsteht regulaer aus `nutzer@host`. Das Passwort muss
     * dann beim ersten Anmelden erfragt werden — und danach gespeichert sein, sonst fragt
     * die App bei jedem Verbinden erneut.
     */
    @Test
    fun `fragt fehlende Zugangsdaten ab und merkt sie sich`() = runTest {
        val identityId = identities.create(nickname = null, username = TestSshd.PASSWORD_USER)
        val connectionId = connections.create(
            ConnectionEntity(
                nickname = "Ohne Passwort",
                address = TestSshd.HOST,
                port = TestSshd.TARGET_PORT,
                createdAt = 0,
                updatedAt = 0,
            ),
        )
        connections.setIdentities(connectionId, listOf(identityId))

        var asked: CredentialPrompt? = null
        val session = manager.open(
            connectionId,
            onHostKey = acceptAll,
            onCredentials = { prompt ->
                asked = prompt
                CredentialAnswer(TestSshd.PASSWORD, remember = true)
            },
        )

        try {
            assertThat(asked?.username).isEqualTo(TestSshd.PASSWORD_USER)
            assertThat(asked?.host).isEqualTo(TestSshd.HOST)
            assertThat(asked?.port).isEqualTo(TestSshd.TARGET_PORT)
            assertThat(session.isAlive).isTrue()
        } finally {
            manager.close(session.id)
        }

        val stored = identities.findById(identityId)!!
        assertThat(identities.revealPassword(stored)).isEqualTo(TestSshd.PASSWORD)
    }

    /** Ohne „merken" darf nichts in der Datenbank landen — die Eingabe galt nur einmal. */
    @Test
    fun `behaelt abgefragte Zugangsdaten ohne merken nicht`() = runTest {
        val identityId = identities.create(nickname = null, username = TestSshd.PASSWORD_USER)
        val connectionId = connections.create(
            ConnectionEntity(
                nickname = "Ohne Passwort",
                address = TestSshd.HOST,
                port = TestSshd.TARGET_PORT,
                createdAt = 0,
                updatedAt = 0,
            ),
        )
        connections.setIdentities(connectionId, listOf(identityId))

        val session = manager.open(
            connectionId,
            onHostKey = acceptAll,
            onCredentials = { CredentialAnswer(TestSshd.PASSWORD, remember = false) },
        )
        manager.close(session.id)

        assertThat(identities.revealPassword(identities.findById(identityId)!!)).isNull()
    }

    /** Wer die Abfrage abbricht, bekommt keine Verbindung — und keine halbe. */
    @Test
    fun `bricht ab wenn die Abfrage abgelehnt wird`() = runTest {
        val identityId = identities.create(nickname = null, username = TestSshd.PASSWORD_USER)
        val connectionId = connections.create(
            ConnectionEntity(
                nickname = "Ohne Passwort",
                address = TestSshd.HOST,
                port = TestSshd.TARGET_PORT,
                createdAt = 0,
                updatedAt = 0,
            ),
        )
        connections.setIdentities(connectionId, listOf(identityId))

        val failure = runCatching {
            manager.open(connectionId, onHostKey = acceptAll, onCredentials = { null })
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SshException::class.java)
        assertThat((failure as SshException).error)
            .isInstanceOf(SshError.AuthenticationFailed::class.java)
        assertThat(manager.openSessions.value).isEmpty()
    }

    /** Sind Zugangsdaten hinterlegt, darf niemand gefragt werden. */
    @Test
    fun `fragt nicht wenn Zugangsdaten hinterlegt sind`() = runTest {
        val connectionId = storeTarget()

        var asked = false
        val session = manager.open(
            connectionId,
            onHostKey = acceptAll,
            onCredentials = { asked = true; null },
        )
        manager.close(session.id)

        assertThat(asked).isFalse()
    }

    @Test
    fun `zaehlt die Nutzung der Verbindung hoch`() = runTest {
        val connectionId = storeTarget()

        val session = manager.open(connectionId, onHostKey = acceptAll)
        manager.close(session.id)

        assertThat(connections.findById(connectionId)!!.useCount).isEqualTo(1)
        assertThat(connections.observeFrequentlyUsed().first().map { it.id })
            .containsExactly(connectionId)
    }

    /**
     * Der Sprung-Host steht in der Datenbank als `viaConnectionId`. Hier muss aus zwei
     * gespeicherten Verbindungen eine Kette werden — das Ziel ist von aussen nicht
     * erreichbar, ein Erfolg beweist also den Sprung.
     */
    @Test
    fun `loest eine gespeicherte Sprung-Kette auf`() = runTest {
        val bastionIdentity = identities.create(
            nickname = "keyuser",
            username = TestSshd.KEY_USER,
            privateKey = TestSshd.privateKey,
        )
        val bastionId = connections.create(
            ConnectionEntity(
                nickname = "Bastion",
                address = TestSshd.HOST,
                port = TestSshd.BASTION_PORT,
                createdAt = 0,
                updatedAt = 0,
            ),
        )
        connections.setIdentities(bastionId, listOf(bastionIdentity))

        val targetIdentity = identities.create(
            nickname = "pwuser",
            username = TestSshd.PASSWORD_USER,
            password = TestSshd.PASSWORD,
        )
        val targetId = connections.create(
            ConnectionEntity(
                nickname = "Hinter dem Bastion",
                address = TestSshd.TARGET_INTERNAL_HOST,
                port = TestSshd.TARGET_INTERNAL_PORT,
                viaConnectionId = bastionId,
                createdAt = 0,
                updatedAt = 0,
            ),
        )
        connections.setIdentities(targetId, listOf(targetIdentity))

        val session = manager.open(targetId, onHostKey = acceptAll)

        try {
            assertThat(session.isAlive).isTrue()
            session.shell.input.write("whoami\n".toByteArray())
            session.shell.input.flush()
            val output = withTimeout(20_000) { session.readUntil(TestSshd.PASSWORD_USER) }
            assertThat(output).contains(TestSshd.PASSWORD_USER)
        } finally {
            manager.close(session.id)
        }
    }

    @Test
    fun `Verbindung ohne Identitaet scheitert an der Anmeldung`() = runTest {
        val connectionId = connections.create(
            ConnectionEntity(
                nickname = "Ohne Zugangsdaten",
                address = TestSshd.HOST,
                port = TestSshd.TARGET_PORT,
                createdAt = 0,
                updatedAt = 0,
            ),
        )

        val error = runCatching { manager.open(connectionId, onHostKey = acceptAll) }
            .exceptionOrNull()

        assertThat((error as SshException).error)
            .isInstanceOf(SshError.AuthenticationFailed::class.java)
        assertThat(manager.openSessions.value).isEmpty()
    }

    @Test
    fun `closeAll schliesst alle offenen Sitzungen`() = runTest {
        val connectionId = storeTarget()
        manager.open(connectionId, onHostKey = acceptAll)
        manager.open(connectionId, onHostKey = acceptAll)
        assertThat(manager.openSessions.value).hasSize(2)

        manager.closeAll()

        assertThat(manager.openSessions.value).isEmpty()
    }

    @Test
    fun `pruneDead raeumt weggebrochene Sitzungen weg`() = runTest {
        val connectionId = storeTarget()
        val session = manager.open(connectionId, onHostKey = acceptAll)

        // Von aussen schliessen, ohne den Manager zu informieren — so verhaelt sich ein
        // Server, der die Verbindung abbricht.
        session.close()

        assertThat(manager.pruneDead()).containsExactly(session.id)
        assertThat(manager.openSessions.value).isEmpty()
    }

    private fun SshSession.readUntil(marker: String): String {
        val collected = ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        while (true) {
            val read = shell.output.read(chunk)
            if (read <= 0) break
            collected.write(chunk, 0, read)
            val text = collected.toString(Charsets.UTF_8.name())
            if (text.contains(marker)) return text
        }
        return collected.toString(Charsets.UTF_8.name())
    }
}
