package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.credentials.spi.CredentialProvider
import dev.rubentxu.pipeline.v2.credentials.api.SecretStoreTamperException
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.credentials.Credential
import dev.rubentxu.pipeline.v2.domain.credentials.SecretText
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The broker of a run, opened as a service opens it, and spoken to as a run
 * speaks to it.
 *
 * ## The guarantee under trial
 *
 * That a lease is born private and holds two files and no secret; that a run
 * which reads its directory receives what the store holds for what was granted
 * and nothing else; that a run cannot use the grants or the session of another;
 * and that when the lease is over the directory is gone and the token opens
 * nothing.
 *
 * ## The capacity this serves
 *
 * A step that enters `withCredentials` receives the secret on arriving there,
 * from a broker that exists for its run alone and whose answers are the
 * operator's grants, not the run's wishes.
 *
 * ## Why this is an INTEGRATION test
 *
 * The facts are about a directory, its modes and a socket that really binds and
 * really accepts: what the kernel lets another process of the same user see, and
 * what a closed socket does to a client that still holds its path.
 *
 * ## What this does not freeze
 *
 * The random name of a lease directory beyond its length, and the processes of
 * the same user, which this lane does not claim to separate.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class RunBrokerLeaseIntegrationTest {

    @TempDir
    lateinit var directory: Path

    private val leases = CopyOnWriteArrayList<RunBrokerLease>()

    private fun root(): Path = directory.resolve("b")

    private fun open(
        grants: Set<CredentialGrant>,
        store: CredentialProvider?,
        root: Path = root(),
    ): RunBrokerLease = RunBrokerLease.open(root, grants, store).also { leases += it }

    @AfterEach
    fun closeLeases() {
        leases.forEach { it.close() }
    }

    @Test
    fun `a lease is born private with the token and the socket and nothing else`() {
        val lease = open(setOf(grant("deploy-key")), StoreDouble(mapOf("deploy-key" to SECRET)))

        assertEquals(root(), lease.directory.parent)
        assertEquals(12, lease.directory.fileName.toString().length)
        assertEquals("rwx------", modeOf(root()), "the root is created private")
        assertEquals("rwx------", modeOf(lease.directory))
        assertEquals("rw-------", modeOf(lease.directory.resolve(RunBrokerLease.TOKEN_FILE_NAME)))
        assertEquals(
            setOf(RunBrokerLease.SOCKET_FILE_NAME, RunBrokerLease.TOKEN_FILE_NAME),
            names(lease.directory),
        )
        assertTrue(Files.exists(lease.directory.resolve(RunBrokerLease.SOCKET_FILE_NAME), LinkOption.NOFOLLOW_LINKS))
        assertEquals(43, Files.readString(lease.directory.resolve(RunBrokerLease.TOKEN_FILE_NAME)).length)
    }

    @Test
    fun `two leases are two directories and two sessions`() {
        val first = open(emptySet(), null)
        val second = open(emptySet(), null)

        assertTrue(first.directory != second.directory)
        assertTrue(
            Files.readString(first.directory.resolve(RunBrokerLease.TOKEN_FILE_NAME)) !=
                Files.readString(second.directory.resolve(RunBrokerLease.TOKEN_FILE_NAME)),
        )
    }

    @Test
    fun `a lease opens when its socket path is exactly the longest the runtime binds and not one byte more`() {
        // root + "/" + 12-character name + "/" + "broker.sock" (11 bytes) is root + 25 bytes.
        val limit = RunBrokerLease.MAX_SOCKET_PATH_BYTES
        val base = directory.toAbsolutePath().toString().toByteArray(Charsets.UTF_8).size
        val padding = limit - 25 - base - 1
        require(padding > 0) { "the temporary directory is too deep to build the boundary" }
        val exact = directory.toAbsolutePath().resolve("r".repeat(padding))
        val over = directory.toAbsolutePath().resolve("r".repeat(padding + 1))

        val lease = open(emptySet(), null, exact)

        assertEquals(
            limit,
            lease.directory.resolve(RunBrokerLease.SOCKET_FILE_NAME).toString().toByteArray(Charsets.UTF_8).size,
            "the socket path of this lease is exactly the longest the runtime binds",
        )
        assertTrue(Files.exists(lease.directory.resolve(RunBrokerLease.SOCKET_FILE_NAME), LinkOption.NOFOLLOW_LINKS))
        assertThrows<RunBrokerLeaseException> { RunBrokerLease.open(over, emptySet(), null) }
        assertFalse(Files.exists(over, LinkOption.NOFOLLOW_LINKS), "one byte more and nothing is created")
    }

    @Test
    fun `a run that reads its directory receives what the store holds and the token file is gone`() {
        val store = StoreDouble(mapOf("deploy-key" to SECRET))
        val lease = open(setOf(grant("deploy-key")), store)

        val provider = SocketCredentialProvider.inDirectory(lease.directory)
        assertEquals(
            setOf(RunBrokerLease.SOCKET_FILE_NAME),
            names(lease.directory),
            "the run took the token and deleted its file",
        )
        assertTrue(store.asked.isEmpty(), "nothing is asked of the store until a step resolves")

        val credential = provider.resolveToCredential(CredentialsId("deploy-key"))

        val text = assertInstanceOf(SecretText::class.java, credential)
        assertEquals(CredentialsId("deploy-key"), text.id)
        assertArrayEquals(SECRET, text.bytes)
        assertEquals(listOf("deploy-key"), store.asked)
        assertFalse(
            directoryHolds(lease.directory, SECRET),
            "the secret travelled over the socket and was written nowhere in the lease",
        )
    }

    @Test
    fun `a credential that was not granted is refused whether the store holds it or not and the store is not asked`() {
        val store = StoreDouble(mapOf("deploy-key" to SECRET, "other" to SECRET))
        val lease = open(setOf(grant("deploy-key")), store)
        val provider = SocketCredentialProvider.inDirectory(lease.directory)

        for (name in listOf("other", "missing")) {
            val failure = assertThrows<CredentialBrokerRefusedException>(name) {
                provider.resolveToCredential(CredentialsId(name))
            }

            assertEquals(CredentialBrokerRefusal.CREDENTIAL_NOT_GRANTED, failure.refusal, name)
            assertEquals(name, failure.credentialId)
        }
        assertTrue(store.asked.isEmpty())
    }

    @Test
    fun `a granted credential the store does not hold is not available and says nothing the store said`() {
        val store = StoreDouble(emptyMap())
        val lease = open(setOf(grant("deploy-key")), store)
        val provider = SocketCredentialProvider.inDirectory(lease.directory)

        val failure = assertThrows<CredentialBrokerRefusedException> {
            provider.resolveToCredential(CredentialsId("deploy-key"))
        }

        assertEquals(CredentialBrokerRefusal.CREDENTIAL_NOT_AVAILABLE, failure.refusal)
        assertEquals(
            "credential 'deploy-key' was not delivered: credential-not-available (${failure.refusal.meaning})",
            failure.message,
        )
        assertFalse(failure.message.orEmpty().contains("not held"))
    }

    @Test
    fun `a service without a store refuses a granted credential as not available`() {
        val lease = open(setOf(grant("deploy-key")), null)
        val provider = SocketCredentialProvider.inDirectory(lease.directory)

        val failure = assertThrows<CredentialBrokerRefusedException> {
            provider.resolveToCredential(CredentialsId("deploy-key"))
        }

        assertEquals(CredentialBrokerRefusal.CREDENTIAL_NOT_AVAILABLE, failure.refusal)
    }

    @Test
    fun `a run cannot resolve what was granted to another run and its session opens nothing of the other`() {
        val store = StoreDouble(mapOf("a-key" to SECRET, "b-key" to OTHER_SECRET))
        val first = open(setOf(grant("a-key")), store)
        val second = open(setOf(grant("b-key")), store)
        val firstToken = Files.readString(first.directory.resolve(RunBrokerLease.TOKEN_FILE_NAME))
        val firstRun = SocketCredentialProvider.inDirectory(first.directory)
        val secondRun = SocketCredentialProvider.inDirectory(second.directory)

        val notGranted = assertThrows<CredentialBrokerRefusedException> {
            firstRun.resolveToCredential(CredentialsId("b-key"))
        }
        val firstHolds = firstRun.resolveToCredential(CredentialsId("a-key"))
        val secondHolds = secondRun.resolveToCredential(CredentialsId("b-key"))
        val borrowed = SocketCredentialProvider(
            UnixSocketBrokerLink(second.directory.resolve(RunBrokerLease.SOCKET_FILE_NAME)),
            firstToken,
        )
        val foreignSession = assertThrows<CredentialBrokerRefusedException> {
            borrowed.resolveToCredential(CredentialsId("b-key"))
        }

        assertEquals(CredentialBrokerRefusal.CREDENTIAL_NOT_GRANTED, notGranted.refusal)
        assertArrayEquals(SECRET, (firstHolds as SecretText).bytes)
        assertArrayEquals(OTHER_SECRET, (secondHolds as SecretText).bytes)
        assertEquals(CredentialBrokerRefusal.SESSION_NOT_RECOGNIZED, foreignSession.refusal)
        assertEquals(listOf("a-key", "b-key"), store.asked.sorted(), "the refused requests never reached the store")
    }

    @Test
    fun `leases served at the same time answer each run with its own credential`() {
        val store = StoreDouble(mapOf("a-key" to SECRET, "b-key" to OTHER_SECRET))
        val first = SocketCredentialProvider.inDirectory(open(setOf(grant("a-key")), store).directory)
        val second = SocketCredentialProvider.inDirectory(open(setOf(grant("b-key")), store).directory)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val jobs = (0 until 40).map { index ->
                pool.submit<Boolean> {
                    if (index % 2 == 0) {
                        (first.resolveToCredential(CredentialsId("a-key")) as SecretText).bytes.contentEquals(SECRET)
                    } else {
                        (second.resolveToCredential(CredentialsId("b-key")) as SecretText).bytes.contentEquals(OTHER_SECRET)
                    }
                }
            }

            assertTrue(jobs.all { it.get(30, TimeUnit.SECONDS) }, "every run got its own secret and no other")
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `closing the lease removes the directory, leaves the store open, and a run that kept the token opens nothing`() {
        val store = StoreDouble(mapOf("deploy-key" to SECRET))
        val threadsBefore = brokerThreads()
        val lease = open(setOf(grant("deploy-key")), store)
        val provider = SocketCredentialProvider.inDirectory(lease.directory)
        assertInstanceOf(SecretText::class.java, provider.resolveToCredential(CredentialsId("deploy-key")))
        assertTrue((brokerThreads() - threadsBefore).isNotEmpty(), "an open lease has a broker running")

        lease.close()
        lease.close()

        assertFalse(Files.exists(lease.directory, LinkOption.NOFOLLOW_LINKS), "no socket and no directory remain")
        assertTrue(Files.isDirectory(root()), "the root belongs to the service")
        assertEquals(0, store.closed.get(), "the store was lent to the broker and is not the broker's to close")
        val failure = assertThrows<CredentialBrokerRefusedException> {
            provider.resolveToCredential(CredentialsId("deploy-key"))
        }
        assertEquals(CredentialBrokerRefusal.BROKER_UNREACHABLE, failure.refusal)
        assertEquals(listOf("deploy-key"), store.asked, "nothing reached the store after the lease was over")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while ((brokerThreads() - threadsBefore).isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(25)
        assertEquals(emptySet<Thread>(), brokerThreads() - threadsBefore, "the broker of a closed lease has no thread left")
    }

    @Test
    fun `the factory gives each client the grants of its own authorization and a stranger none`() {
        val store = StoreDouble(mapOf("a-key" to SECRET, "b-key" to OTHER_SECRET))
        val factory = RunBrokerFactory(root(), store) { digest ->
            when (digest) {
                "digest-a" -> setOf(grant("a-key"))
                "digest-b" -> setOf(grant("b-key"))
                else -> emptySet()
            }
        }
        val a = factory.open("digest-a").also { leases += it }
        val b = factory.open("digest-b").also { leases += it }
        val stranger = factory.open("digest-unknown").also { leases += it }
        val anonymous = factory.open(null).also { leases += it }

        val byA = SocketCredentialProvider.inDirectory(a.directory)
        val byB = SocketCredentialProvider.inDirectory(b.directory)

        assertArrayEquals(SECRET, (byA.resolveToCredential(CredentialsId("a-key")) as SecretText).bytes)
        assertArrayEquals(OTHER_SECRET, (byB.resolveToCredential(CredentialsId("b-key")) as SecretText).bytes)
        assertEquals(
            CredentialBrokerRefusal.CREDENTIAL_NOT_GRANTED,
            assertThrows<CredentialBrokerRefusedException> { byA.resolveToCredential(CredentialsId("b-key")) }.refusal,
        )
        for (lease in listOf(stranger, anonymous)) {
            val run = SocketCredentialProvider.inDirectory(lease.directory)
            for (name in listOf("a-key", "b-key")) {
                assertEquals(
                    CredentialBrokerRefusal.CREDENTIAL_NOT_GRANTED,
                    assertThrows<CredentialBrokerRefusedException> { run.resolveToCredential(CredentialsId(name)) }.refusal,
                    "a client the service does not know is granted nothing",
                )
            }
        }
        assertEquals(listOf("a-key", "b-key"), store.asked.sorted())
    }

    @Test
    fun `the factory sweeps what an earlier process left in its root`() {
        val factory = RunBrokerFactory(root(), null) { emptySet() }
        val leftover = Files.createDirectories(root().resolve("leftoverdir1"))
        Files.writeString(leftover.resolve(RunBrokerLease.TOKEN_FILE_NAME), "old")

        factory.sweep()

        assertEquals(emptySet<String>(), names(root()))
    }

    private fun brokerThreads(): Set<Thread> =
        Thread.getAllStackTraces().keys
            .filter { it.isAlive && it.name.startsWith("pipelinek-credential-broker") }
            .toSet()

    private fun grant(id: String) = CredentialGrant(id, BrokeredCredentialKind.STRING)

    private fun modeOf(path: Path): String =
        PosixFilePermissions.toString(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS))

    private fun names(directory: Path): Set<String> =
        Files.list(directory).use { stream -> stream.map { it.fileName.toString() }.toList().toSet() }

    /** Whether any regular file below [directory] holds [needle] as a run of bytes. */
    private fun directoryHolds(directory: Path, needle: ByteArray): Boolean =
        Files.walk(directory).use { stream ->
            stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .anyMatch { indexOf(Files.readAllBytes(it), needle) >= 0 }
        }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        for (start in 0..haystack.size - needle.size) {
            if (needle.indices.all { haystack[start + it] == needle[it] }) return start
        }
        return -1
    }

    /** A store that holds bytes by name and records what it was asked and how often it was closed. */
    private class StoreDouble(private val secrets: Map<String, ByteArray>) : CredentialProvider {
        val asked: MutableList<String> = CopyOnWriteArrayList()
        val closed = AtomicInteger(0)

        override val providerId: String = "store-double"

        override fun resolve(id: CredentialsId): SecretHandle = error("the broker resolves credentials, not handles")

        override fun resolveToCredential(id: CredentialsId): Credential {
            asked += id.value
            val bytes = secrets[id.value] ?: throw SecretStoreTamperException("not held")
            return SecretText(id, bytes = bytes.copyOf())
        }

        override fun close() {
            closed.incrementAndGet()
        }
    }

    private companion object {
        val SECRET: ByteArray = "s3cr3t-of-the-first".toByteArray()
        val OTHER_SECRET: ByteArray = "s3cr3t-of-the-second".toByteArray()
    }
}
