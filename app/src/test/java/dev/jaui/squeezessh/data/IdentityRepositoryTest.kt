package dev.jaui.squeezessh.data

import com.google.common.truth.Truth.assertThat
import dev.jaui.squeezessh.data.repository.IdentityRepository
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IdentityRepositoryTest : DatabaseTestCase() {

    private lateinit var identities: IdentityRepository

    @Before
    fun createRepository() {
        identities = IdentityRepository(db.identityDao(), vault, time)
    }

    @Test
    fun `Geheimnisse liegen niemals im Klartext in der Datenbank`() = runTest {
        val id = identities.create(
            nickname = "root",
            username = "root",
            password = "hunter2",
            privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n",
            privateKeyPassword = "passphrase",
        )

        val stored = identities.findById(id)!!
        assertThat(stored.passwordCipher).isNotEqualTo("hunter2")
        assertThat(stored.passwordCipher).startsWith(FakeSecretVault.PREFIX)
        assertThat(stored.privateKeyCipher).startsWith(FakeSecretVault.PREFIX)
        assertThat(stored.privateKeyPasswordCipher).startsWith(FakeSecretVault.PREFIX)

        assertThat(identities.revealPassword(stored)).isEqualTo("hunter2")
        assertThat(identities.revealPrivateKey(stored))
            .isEqualTo("-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n")
        assertThat(identities.revealPrivateKeyPassword(stored)).isEqualTo("passphrase")
    }

    @Test
    fun `Identitaet ohne Geheimnisse hat keine Chiffren`() = runTest {
        val id = identities.create(nickname = "agent", username = "deploy")

        val stored = identities.findById(id)!!
        assertThat(stored.passwordCipher).isNull()
        assertThat(stored.privateKeyCipher).isNull()
        assertThat(stored.hasPassword).isFalse()
        assertThat(stored.hasPrivateKey).isFalse()
        assertThat(identities.revealPassword(stored)).isNull()
    }

    @Test
    fun `update laesst Geheimnisse unangetastet`() = runTest {
        val id = identities.create(username = "root", nickname = "root", password = "hunter2")
        val original = identities.findById(id)!!

        identities.update(original.copy(nickname = "root-produktion"))

        val updated = identities.findById(id)!!
        assertThat(updated.nickname).isEqualTo("root-produktion")
        assertThat(updated.passwordCipher).isEqualTo(original.passwordCipher)
    }

    @Test
    fun `setPassword ueberschreibt und kann loeschen`() = runTest {
        val id = identities.create(username = "root", nickname = "root", password = "alt")

        identities.setPassword(id, "neu")
        assertThat(identities.revealPassword(identities.findById(id)!!)).isEqualTo("neu")

        identities.setPassword(id, null)
        val cleared = identities.findById(id)!!
        assertThat(cleared.passwordCipher).isNull()
        assertThat(cleared.passwordUpdatedAt).isNull()
    }

    @Test
    fun `setPrivateKey haelt den oeffentlichen Teil fest`() = runTest {
        val id = identities.create(username = "deploy", nickname = "deploy")

        identities.setPrivateKey(
            identityId = id,
            privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nxyz\n",
            privateKeyPassword = "geheim",
            publicKey = "ssh-ed25519 AAAAC3Nza... deploy@host",
        )

        val stored = identities.findById(id)!!
        assertThat(stored.publicKey).isEqualTo("ssh-ed25519 AAAAC3Nza... deploy@host")
        assertThat(stored.keyUpdatedAt).isNotNull()
        assertThat(stored.hasPrivateKey).isTrue()
    }

    @Test
    fun `displayName faellt auf den Benutzernamen zurueck`() = runTest {
        val named = identities.findById(identities.create(nickname = "Prod", username = "root"))!!
        val unnamed = identities.findById(identities.create(nickname = null, username = "deploy"))!!

        assertThat(named.displayName).isEqualTo("Prod")
        assertThat(unnamed.displayName).isEqualTo("deploy")
    }
}
