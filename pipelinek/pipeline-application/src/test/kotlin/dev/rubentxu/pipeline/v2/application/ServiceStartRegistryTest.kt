package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The record of the starts, and the promise that a key means one run.
 *
 * ## The guarantee under trial
 *
 * That the same key with the same request answers with the SAME run, and that
 * the same key with ANOTHER request is a conflict and not a second run. The
 * promise has to survive the service: a client that retries after a restart
 * must not get a second run for a key the service already answered.
 *
 * ## The capacity this serves
 *
 * A client that starts runs over a socket and loses the answer —the socket
 * closed, the machine busy— retries with the same key. That retry is safe
 * only if the key means one run, and it is safe only if the promise is
 * DURABLE: an in-memory table would make it true until the service restarts,
 * which is exactly when a retry happens.
 *
 * ## Why the claim is taken BEFORE the run starts
 *
 * Because two requests can arrive at the same time. The second one has to
 * find the first one's start and wait for it, and not start a second run: the
 * claim is what it finds, and it is released when the start fails, so a
 * request that could not start a run has to be able to ask again.
 *
 * ## What this does not freeze
 *
 * The shape of the file: the file is the service's own and it is read back
 * through [ServiceStartRegistry.read], so the promise survives the service
 * without the file's format being part of any contract.
 */
class ServiceStartRegistryTest {

    @Test
    fun `a key that was never used is free`(@TempDir root: Path) {
        val registry = registry(root)

        val claim = registry.claim("key-1", DIGEST)

        assertInstanceOf(ServiceStartClaim.Owner::class.java, claim)
        assertNull(registry.find("key-1"), "nothing has started yet")
    }

    @Test
    fun `the same key with the same request answers with the run that started`(@TempDir root: Path) {
        val registry = registry(root)
        registry.claim("key-1", DIGEST)
        registry.complete("key-1", RECORD)

        val claim = registry.claim("key-1", DIGEST)

        val known = assertInstanceOf(ServiceStartClaim.Known::class.java, claim)
        assertEquals(RECORD, known.record, "the record of the start is the one that started")
    }

    @Test
    fun `the same key with another request is a conflict`(@TempDir root: Path) {
        val registry = registry(root)
        registry.claim("key-1", DIGEST)
        registry.complete("key-1", RECORD)

        val claim = registry.claim("key-1", "another-digest")

        assertEquals(
            ServiceStartClaim.Conflict,
            claim,
            "a key names a REQUEST, so the same key with another request is a conflict",
        )
    }

    @Test
    fun `a key that is being started is awaited and not started twice`(@TempDir root: Path) {
        val registry = registry(root)
        registry.claim("key-1", DIGEST)

        val claim = registry.claim("key-1", DIGEST)

        val awaiting = assertInstanceOf(ServiceStartClaim.Awaiting::class.java, claim)
        registry.complete("key-1", RECORD)
        assertEquals(
            RECORD,
            awaiting.future.get(),
            "the caller that waited is answered with the run the other request started",
        )
    }

    @Test
    fun `a start that failed releases the key`(@TempDir root: Path) {
        val registry = registry(root)
        registry.claim("key-1", DIGEST)

        registry.fail("key-1", "the child could not be launched")

        assertInstanceOf(
            ServiceStartClaim.Owner::class.java,
            registry.claim("key-1", DIGEST),
            "a request that could not start a run has to be able to ask again",
        )
    }

    @Test
    fun `a caller that waits for a failed start is answered with the failure`(@TempDir root: Path) {
        val registry = registry(root)
        registry.claim("key-1", DIGEST)
        val awaiting = assertInstanceOf(
            ServiceStartClaim.Awaiting::class.java,
            registry.claim("key-1", DIGEST),
        )

        registry.fail("key-1", "the child died")

        val failure = assertInstanceOf(
            java.util.concurrent.ExecutionException::class.java,
            runCatching { awaiting.future.get() }.exceptionOrNull(),
        )
        assertTrue(
            failure.cause is ServiceStartFailed,
            "the failure travels to the callers that were waiting for the same key",
        )
    }

    @Test
    fun `the record of the starts survives the service`(@TempDir root: Path) {
        val path = root.resolve(ServiceStartRegistry.FILE_NAME)
        val registry = registry(root)
        registry.claim("key-1", DIGEST)
        registry.complete("key-1", RECORD)

        val read = ServiceStartRegistry.read(path)

        val reread = assertInstanceOf(ServiceStartRegistryReading.Read::class.java, read)
        assertEquals(
            RECORD,
            reread.registry.find("key-1"),
            "a client that retries after a restart gets the run that already started",
        )
    }

    @Test
    fun `the record of the starts is private`(@TempDir root: Path) {
        val path = root.resolve(ServiceStartRegistry.FILE_NAME)
        val registry = registry(root)
        registry.claim("key-1", DIGEST)
        registry.complete("key-1", RECORD)

        val permissions = Files.getPosixFilePermissions(path)

        assertEquals(
            setOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
            ),
            permissions,
            "the record says which runs the operator started, and it is the operator's own",
        )
    }

    @Test
    fun `a record that is not a document is refused`(@TempDir root: Path) {
        val path = root.resolve(ServiceStartRegistry.FILE_NAME)
        Files.writeString(path, "not a document")

        val read = ServiceStartRegistry.read(path)

        assertInstanceOf(
            ServiceStartRegistryReading.Refused::class.java,
            read,
            "a record that cannot be read is a promise that cannot be kept",
        )
    }

    @Test
    fun `a record that holds a start without a run is refused`(@TempDir root: Path) {
        val path = root.resolve(ServiceStartRegistry.FILE_NAME)
        Files.writeString(path, """{"key-1":{"digest":"d"}}""")

        val read = ServiceStartRegistry.read(path)

        assertInstanceOf(
            ServiceStartRegistryReading.Refused::class.java,
            read,
            "a start without a run names no run",
        )
    }

    @Test
    fun `a record that is not there is an empty record`(@TempDir root: Path) {
        val reading = ServiceStartRegistry.read(root.resolve(ServiceStartRegistry.FILE_NAME))

        val read = assertInstanceOf(ServiceStartRegistryReading.Read::class.java, reading)
        assertNull(read.registry.find("key-1"), "the service has not started anything yet")
    }

    /**
     * The record of the starts, in [root].
     */
    private fun registry(root: Path): ServiceStartRegistry {
        val read = ServiceStartRegistry.read(root.resolve(ServiceStartRegistry.FILE_NAME))
        return assertInstanceOf(ServiceStartRegistryReading.Read::class.java, read).registry
    }

    private companion object {
        /** The fingerprint of the request that is made. */
        private const val DIGEST: String = "digest-1"

        /** The start that is remembered. */
        private val RECORD: ServiceStartRecord = ServiceStartRecord(
            digest = DIGEST,
            runId = "run-1",
            correlationId = "corr-1",
            ownerId = "owner-1",
            startedAtMs = 1700L,
        )
    }
}
