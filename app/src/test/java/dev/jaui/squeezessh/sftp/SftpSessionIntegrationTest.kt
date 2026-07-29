package dev.jaui.squeezessh.sftp

import com.google.common.truth.Truth.assertThat
import dev.jaui.squeezessh.data.DatabaseTestCase
import dev.jaui.squeezessh.data.repository.KnownHostRepository
import dev.jaui.squeezessh.ssh.SshConnection
import dev.jaui.squeezessh.ssh.SshConnector
import dev.jaui.squeezessh.ssh.SshCredential
import dev.jaui.squeezessh.ssh.SshHop
import dev.jaui.squeezessh.ssh.TestSshd
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Prueft SFTP gegen den echten sshd aus `tools/test-sshd`. */
@RunWith(RobolectricTestRunner::class)
class SftpSessionIntegrationTest : DatabaseTestCase() {

    private lateinit var connector: SshConnector

    @Before
    fun setUp() {
        assumeTrue(
            "sshd-Testumgebung laeuft nicht — tools\\test-sshd\\up.ps1 ausfuehren",
            TestSshd.isRunning,
        )
        connector = SshConnector(KnownHostRepository(db.knownHostDao(), time), Dispatchers.IO)
    }

    private suspend fun connect(): SshConnection = connector.connect(
        listOf(
            SshHop(
                TestSshd.HOST,
                TestSshd.TARGET_PORT,
                TestSshd.PASSWORD_USER,
                listOf(SshCredential.Password(TestSshd.PASSWORD)),
            ),
        ),
        onHostKey = { true },
    )

    private suspend fun withSftp(block: (SftpSession, String) -> Unit) {
        connect().use { connection ->
            SftpSession.open(connection).use { sftp ->
                // Eigenes Verzeichnis pro Lauf, damit sich parallele Tests nicht stoeren.
                val base = sftp.canonicalize(".") + "/test-${System.nanoTime()}"
                sftp.makeDirectory(base)
                try {
                    block(sftp, base)
                } finally {
                    runCatching {
                        sftp.list(base).forEach { sftp.delete(it) }
                        sftp.delete(
                            SftpEntry(base, base, true, false, 0, 0, ""),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `Heimatverzeichnis laesst sich aufloesen und auflisten`() = runTest {
        connect().use { connection ->
            SftpSession.open(connection).use { sftp ->
                val home = sftp.canonicalize(".")

                assertThat(home).startsWith("/")
                assertThat(sftp.list(home)).isNotNull()
            }
        }
    }

    @Test
    fun `Hochladen und wieder Herunterladen liefert dieselben Bytes`() = runTest {
        withSftp { sftp, base ->
            val content = "Inhalt mit Umlauten: äöü ß\nund zweiter Zeile\n".toByteArray()
            val remote = "$base/probe.txt"

            val written = sftp.upload(ByteArrayInputStream(content), remote)
            assertThat(written).isEqualTo(content.size.toLong())

            val received = ByteArrayOutputStream()
            val read = sftp.download(remote, received)

            assertThat(read).isEqualTo(content.size.toLong())
            assertThat(received.toByteArray()).isEqualTo(content)
        }
    }

    @Test
    fun `Groesse und Auflistung stimmen nach dem Hochladen`() = runTest {
        withSftp { sftp, base ->
            val content = ByteArray(100_000) { (it % 251).toByte() }
            sftp.upload(ByteArrayInputStream(content), "$base/gross.bin")

            assertThat(sftp.sizeOf("$base/gross.bin")).isEqualTo(100_000)

            val entry = sftp.list(base).single { it.name == "gross.bin" }
            assertThat(entry.isDirectory).isFalse()
            assertThat(entry.sizeBytes).isEqualTo(100_000)
        }
    }

    /** Der Fortschritt muss waehrend der Uebertragung kommen, nicht erst am Ende. */
    @Test
    fun `Fortschritt wird waehrend der Uebertragung gemeldet`() = runTest {
        withSftp { sftp, base ->
            val content = ByteArray(300_000) { it.toByte() }
            val steps = mutableListOf<Long>()

            sftp.upload(
                ByteArrayInputStream(content),
                "$base/fortschritt.bin",
                onProgress = { steps += it },
            )

            assertThat(steps).isNotEmpty()
            assertThat(steps.last()).isEqualTo(300_000)
            // Bei 32-KiB-Bloecken muessen es mehrere Meldungen sein.
            assertThat(steps.size).isGreaterThan(1)
            assertThat(steps).isInOrder()
        }
    }

    @Test
    fun `Abbruch stoppt die Uebertragung vorzeitig`() = runTest {
        withSftp { sftp, base ->
            val content = ByteArray(1_000_000) { it.toByte() }
            val cancelled = AtomicBoolean(false)

            val written = sftp.upload(
                ByteArrayInputStream(content),
                "$base/abgebrochen.bin",
                onProgress = { transferred -> if (transferred > 100_000) cancelled.set(true) },
                shouldContinue = { !cancelled.get() },
            )

            assertThat(written).isLessThan(1_000_000)
            assertThat(written).isGreaterThan(0)
            // Was ankam, steht auch wirklich auf dem Server.
            assertThat(sftp.sizeOf("$base/abgebrochen.bin")).isEqualTo(written)
        }
    }

    /**
     * Der eigentliche Zweck des Offsets: eine abgebrochene Uebertragung fortsetzen und am
     * Ende dieselbe Datei haben wie bei einem Durchlauf am Stueck.
     */
    @Test
    fun `abgebrochene Uebertragung laesst sich fortsetzen`() = runTest {
        withSftp { sftp, base ->
            val content = ByteArray(200_000) { (it * 7 % 253).toByte() }
            val remote = "$base/fortgesetzt.bin"
            val stop = AtomicBoolean(false)

            val firstPart = sftp.upload(
                ByteArrayInputStream(content),
                remote,
                onProgress = { if (it >= 64_000) stop.set(true) },
                shouldContinue = { !stop.get() },
            )
            assertThat(firstPart).isLessThan(200_000)

            sftp.upload(
                ByteArrayInputStream(content.copyOfRange(firstPart.toInt(), content.size)),
                remote,
                startOffset = firstPart,
            )

            assertThat(sftp.sizeOf(remote)).isEqualTo(200_000)
            val received = ByteArrayOutputStream()
            sftp.download(remote, received)
            assertThat(received.toByteArray()).isEqualTo(content)
        }
    }

    @Test
    fun `Herunterladen ab einem Offset liefert nur den Rest`() = runTest {
        withSftp { sftp, base ->
            val content = "0123456789ABCDEF".toByteArray()
            sftp.upload(ByteArrayInputStream(content), "$base/teil.txt")

            val received = ByteArrayOutputStream()
            val read = sftp.download("$base/teil.txt", received, startOffset = 10)

            assertThat(read).isEqualTo(6)
            assertThat(received.toString(Charsets.UTF_8.name())).isEqualTo("ABCDEF")
        }
    }

    @Test
    fun `Verzeichnisse anlegen umbenennen und loeschen`() = runTest {
        withSftp { sftp, base ->
            sftp.makeDirectory("$base/alt")
            assertThat(sftp.list(base).map { it.name }).contains("alt")

            sftp.rename("$base/alt", "$base/neu")
            val entries = sftp.list(base)
            assertThat(entries.map { it.name }).contains("neu")
            assertThat(entries.map { it.name }).doesNotContain("alt")

            sftp.delete(entries.single { it.name == "neu" })
            assertThat(sftp.list(base).map { it.name }).doesNotContain("neu")
        }
    }

    @Test
    fun `Verzeichnisse stehen in der Auflistung vor Dateien`() = runTest {
        withSftp { sftp, base ->
            sftp.upload(ByteArrayInputStream("x".toByteArray()), "$base/aaa.txt")
            sftp.makeDirectory("$base/zzz")

            val entries = sftp.list(base)

            assertThat(entries.first().name).isEqualTo("zzz")
            assertThat(entries.first().isDirectory).isTrue()
        }
    }

    @Test
    fun `nicht vorhandene Datei meldet einen Fehler`() = runTest {
        withSftp { sftp, base ->
            val error = runCatching { sftp.sizeOf("$base/gibtsnicht") }.exceptionOrNull()

            assertThat(error).isInstanceOf(IOException::class.java)
        }
    }
}
