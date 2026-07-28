package dev.jaui.squeezessh.data

import com.google.common.truth.Truth.assertThat
import dev.jaui.squeezessh.data.model.HostKeyType
import dev.jaui.squeezessh.data.repository.HostKeyVerdict
import dev.jaui.squeezessh.data.repository.KnownHostRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KnownHostRepositoryTest : DatabaseTestCase() {

    private lateinit var knownHosts: KnownHostRepository

    @Before
    fun createRepository() {
        knownHosts = KnownHostRepository(db.knownHostDao(), time)
    }

    @Test
    fun `unbekannter Host verlangt eine Entscheidung`() = runTest {
        val verdict = knownHosts.verify("web.example.com", 22, HostKeyType.SSH_ED25519, "AAAA")

        assertThat(verdict).isEqualTo(HostKeyVerdict.Unknown)
    }

    @Test
    fun `bekannter und unveraenderter Host wird akzeptiert`() = runTest {
        knownHosts.trust("web.example.com", 22, HostKeyType.SSH_ED25519, "AAAA", "SHA256:abc")

        val verdict = knownHosts.verify("web.example.com", 22, HostKeyType.SSH_ED25519, "AAAA")

        assertThat(verdict).isEqualTo(HostKeyVerdict.Trusted)
    }

    @Test
    fun `geaenderter Host-Key wird als Aenderung gemeldet, nicht als unbekannt`() = runTest {
        knownHosts.trust("web.example.com", 22, HostKeyType.SSH_ED25519, "AAAA", "SHA256:abc")

        val verdict = knownHosts.verify("web.example.com", 22, HostKeyType.SSH_ED25519, "BBBB")

        assertThat(verdict).isInstanceOf(HostKeyVerdict.Changed::class.java)
        assertThat((verdict as HostKeyVerdict.Changed).known.publicKey).isEqualTo("AAAA")
        assertThat(verdict.known.fingerprint).isEqualTo("SHA256:abc")
    }

    @Test
    fun `derselbe Rechner auf verschiedenen Ports sind verschiedene Eintraege`() = runTest {
        knownHosts.trust("host.example.com", 22, HostKeyType.SSH_ED25519, "AAAA", "SHA256:a")
        knownHosts.trust("host.example.com", 2222, HostKeyType.SSH_ED25519, "BBBB", "SHA256:b")

        assertThat(knownHosts.verify("host.example.com", 22, HostKeyType.SSH_ED25519, "AAAA"))
            .isEqualTo(HostKeyVerdict.Trusted)
        assertThat(knownHosts.verify("host.example.com", 2222, HostKeyType.SSH_ED25519, "BBBB"))
            .isEqualTo(HostKeyVerdict.Trusted)
        assertThat(knownHosts.observeAll().first()).hasSize(2)
    }

    @Test
    fun `ein Host darf mehrere Key-Typen anbieten`() = runTest {
        knownHosts.trust("host.example.com", 22, HostKeyType.SSH_ED25519, "AAAA", "SHA256:a")
        knownHosts.trust("host.example.com", 22, HostKeyType.SSH_RSA, "BBBB", "SHA256:b")

        assertThat(knownHosts.findFor("host.example.com", 22)).hasSize(2)
    }

    @Test
    fun `erneutes trust aktualisiert statt zu duplizieren`() = runTest {
        knownHosts.trust("host.example.com", 22, HostKeyType.SSH_ED25519, "AAAA", "SHA256:a")
        knownHosts.trust("host.example.com", 22, HostKeyType.SSH_ED25519, "CCCC", "SHA256:c")

        val hosts = knownHosts.findFor("host.example.com", 22)
        assertThat(hosts).hasSize(1)
        assertThat(hosts.single().publicKey).isEqualTo("CCCC")
        assertThat(hosts.single().fingerprint).isEqualTo("SHA256:c")
    }

    @Test
    fun `verify aktualisiert lastSeenAt aber nicht firstSeenAt`() = runTest {
        knownHosts.trust("host.example.com", 22, HostKeyType.SSH_ED25519, "AAAA", "SHA256:a")
        val initial = knownHosts.findFor("host.example.com", 22).single()

        knownHosts.verify("host.example.com", 22, HostKeyType.SSH_ED25519, "AAAA")

        val touched = knownHosts.findFor("host.example.com", 22).single()
        assertThat(touched.firstSeenAt).isEqualTo(initial.firstSeenAt)
        assertThat(touched.lastSeenAt).isGreaterThan(initial.lastSeenAt)
    }

    @Test
    fun `forgetAll leert die Known Hosts`() = runTest {
        knownHosts.trust("a.example.com", 22, HostKeyType.SSH_ED25519, "AAAA", "SHA256:a")
        knownHosts.trust("b.example.com", 22, HostKeyType.SSH_RSA, "BBBB", "SHA256:b")

        knownHosts.forgetAll()

        assertThat(knownHosts.observeAll().first()).isEmpty()
    }
}
