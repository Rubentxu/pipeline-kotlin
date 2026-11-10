package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The parts of the lease of a run's broker that need no socket: whether its path
 * fits, what a refused opening leaves behind, and what the sweep removes.
 *
 * ## The guarantee under trial
 *
 * That a root whose socket path the runtime would not bind is refused before
 * anything exists on disk; that an opening that fails leaves nothing and says
 * nothing of the machine; and that the sweep removes what an earlier process
 * left, leaves the root itself, and does not follow a link out of it.
 *
 * ## The capacity this serves
 *
 * A service that starts runs with a broker must know, when it starts, that the
 * brokers fit; and it must not accumulate directories of runs that are over.
 *
 * ## Why this instrument
 *
 * None of these facts needs a live socket, so they are tried on a temporary
 * directory. The rows that open a broker and talk to it are in the integration
 * lane, where a socket really binds.
 *
 * ## What this does not freeze
 *
 * The length of the random name of a lease directory beyond what the path
 * budget needs, and the order in which the sweep visits entries.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class RunBrokerLeaseTest {

    @Test
    fun `a root fits while the socket path stays within what the runtime binds and not one byte more`() {
        // root + "/" + 12-char name + "/" + "broker.sock" (11 bytes) = root + 25
        val fits = Path.of("/" + "a".repeat(80))
        val over = Path.of("/" + "a".repeat(81))

        assertEquals(81, fits.toString().length)
        assertTrue(RunBrokerLease.fitsBelow(fits), "106 bytes is the limit and it is accepted")
        assertFalse(RunBrokerLease.fitsBelow(over), "107 bytes is one more than the runtime binds")
    }

    @Test
    fun `the budget is counted in bytes and not in characters`() {
        // 81 characters, which would fit, and 82 bytes, which do not: the accent takes two.
        val multibyte = Path.of("/" + "a".repeat(79) + "é")

        assertEquals(81, multibyte.toString().length)
        assertEquals(82, multibyte.toString().toByteArray(Charsets.UTF_8).size)
        assertFalse(RunBrokerLease.fitsBelow(multibyte))
    }

    @Test
    fun `a root too deep is refused before anything exists and the message names no path`(@TempDir directory: Path) {
        val parent = directory.toAbsolutePath()
        val missing = parent.resolve("n".repeat(110))

        val failure = assertThrows<RunBrokerLeaseException> {
            RunBrokerLease.open(missing, emptySet(), provider = null)
        }

        assertFalse(Files.exists(missing, LinkOption.NOFOLLOW_LINKS), "not even the root was created")
        assertEquals("the socket path of a run broker is longer than a socket can be bound to", failure.message)
        assertFalse(failure.message.orEmpty().contains(parent.toString()))
    }

    @Test
    fun `a root that cannot be created is a refusal with a fixed message and leaves nothing`(@TempDir directory: Path) {
        val file = Files.writeString(directory.resolve("not-a-directory"), "x")
        val root = file.resolve("brokers")

        val failure = assertThrows<RunBrokerLeaseException> {
            RunBrokerLease.open(root, emptySet(), provider = null)
        }

        assertEquals("the directory of a run broker could not be created", failure.message)
        assertEquals(listOf(file.fileName.toString()), Files.list(directory).use { it.map { p -> p.fileName.toString() }.toList() })
        assertEquals("x", Files.readString(file), "what was in the way is not touched")
    }

    @Test
    fun `the factory asks for the grants of the client it was given and passes the absence of a client as it is`(
        @TempDir directory: Path,
    ) {
        val asked = mutableListOf<String?>()
        val factory = RunBrokerFactory(
            root = directory.toAbsolutePath().resolve("n".repeat(110)),
            provider = null,
            grantsOf = { digest ->
                asked += digest
                emptySet()
            },
        )

        assertThrows<RunBrokerLeaseException> { factory.open("digest-of-a-client") }
        assertThrows<RunBrokerLeaseException> { factory.open(null) }

        assertEquals(listOf<String?>("digest-of-a-client", null), asked)
    }

    @Test
    fun `the factory tells whether its root fits`(@TempDir directory: Path) {
        val short = RunBrokerFactory(directory.toAbsolutePath().resolve("b"), null) { emptySet() }
        val long = RunBrokerFactory(directory.toAbsolutePath().resolve("n".repeat(110)), null) { emptySet() }

        assertTrue(short.fits())
        assertFalse(long.fits())
    }

    @Test
    fun `the sweep removes every leftover below the root and keeps the root`(@TempDir directory: Path) {
        val root = Files.createDirectory(directory.resolve("brokers"))
        val first = Files.createDirectory(root.resolve("aaaaaaaaaaaa"))
        Files.writeString(first.resolve(RunBrokerLease.TOKEN_FILE_NAME), "token")
        Files.writeString(first.resolve(RunBrokerLease.SOCKET_FILE_NAME), "not really a socket")
        val second = Files.createDirectories(root.resolve("bbbbbbbbbbbb").resolve("deeper").resolve("still"))
        Files.writeString(second.resolve("file"), "x")
        Files.writeString(root.resolve("a-file-at-the-top"), "x")

        RunBrokerLease.sweep(root)

        assertTrue(Files.isDirectory(root), "the root belongs to the service and stays")
        assertEquals(emptyList<String>(), Files.list(root).use { it.map { p -> p.fileName.toString() }.toList() })
    }

    @Test
    fun `the sweep deletes a link and does not follow it out of the root`(@TempDir directory: Path) {
        val outside = Files.createDirectory(directory.resolve("outside"))
        val precious = Files.writeString(outside.resolve("precious"), "keep me")
        val root = Files.createDirectory(directory.resolve("brokers"))
        val link = Files.createSymbolicLink(root.resolve("cccccccccccc"), outside)

        RunBrokerLease.sweep(root)

        assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS), "the link is a leftover and goes")
        assertTrue(Files.isDirectory(outside), "what it pointed at is not a leftover")
        assertEquals("keep me", Files.readString(precious))
    }

    @Test
    fun `the sweep of a root that is not there or is not a directory does nothing`(@TempDir directory: Path) {
        val missing = directory.resolve("missing")
        val file = Files.writeString(directory.resolve("a-file"), "x")
        val outside = Files.createDirectory(directory.resolve("outside"))
        Files.writeString(outside.resolve("precious"), "keep me")
        val link = Files.createSymbolicLink(directory.resolve("link-to-a-directory"), outside)

        RunBrokerLease.sweep(missing)
        RunBrokerLease.sweep(file)
        RunBrokerLease.sweep(link)

        assertFalse(Files.exists(missing))
        assertEquals("x", Files.readString(file))
        assertTrue(Files.exists(outside.resolve("precious")), "a root that is a link is not swept through")
    }
}
