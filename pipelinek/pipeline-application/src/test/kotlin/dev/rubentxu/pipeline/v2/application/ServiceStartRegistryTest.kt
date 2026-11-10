package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.service.ServiceRefusal
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
 * ## The claim that was never answered
 *
 * The claim is WRITTEN DOWN before the child is launched, and that is what
 * makes the promise survive a crash: a claim that lived only in memory was a
 * claim that a crash erased, and a claim that a crash erased is a key that
 * names two runs. A claim the service never answered is a claim whose effect
 * is UNKNOWN, and the key is not free.
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

        val claim = registry.claim("key-1", DIGEST, CORRELATION)

        assertInstanceOf(ServiceStartClaim.Owner::class.java, claim)
        assertNull(registry.find("key-1"), "nothing has started yet")
    }

    @Test
    fun `the same key with the same request answers with the run that started`(@TempDir root: Path) {
        val registry = registry(root)
        registry.claim("key-1", DIGEST, CORRELATION)
        registry.complete("key-1", RECORD)

        val claim = registry.claim("key-1", DIGEST, CORRELATION)

        val known = assertInstanceOf(ServiceStartClaim.Known::class.java, claim)
        assertEquals(RECORD, known.record, "the record of the start is the one that started")
    }

    @Test
    fun `the same key with another request is a conflict`(@TempDir root: Path) {
        val registry = registry(root)
        registry.claim("key-1", DIGEST, CORRELATION)
        registry.complete("key-1", RECORD)

        val claim = registry.claim("key-1", "another-digest", CORRELATION)

        assertEquals(
            ServiceStartClaim.Conflict,
            claim,
            "a key names a REQUEST, so the same key with another request is a conflict",
        )
    }

    @Test
    fun `a key that is being started is awaited and not started twice`(@TempDir root: Path) {
        val registry = registry(root)
        registry.claim("key-1", DIGEST, CORRELATION)

        val claim = registry.claim("key-1", DIGEST, CORRELATION)

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
        registry.claim("key-1", DIGEST, CORRELATION)

        registry.fail("key-1", REFUSAL)

        assertInstanceOf(
            ServiceStartClaim.Owner::class.java,
            registry.claim("key-1", DIGEST, CORRELATION),
            "a request that could not start a run has to be able to ask again",
        )
    }

    @Test
    fun `a caller that waits for a failed start is answered with the failure`(@TempDir root: Path) {
        val registry = registry(root)
        registry.claim("key-1", DIGEST, CORRELATION)
        val awaiting = assertInstanceOf(
            ServiceStartClaim.Awaiting::class.java,
            registry.claim("key-1", DIGEST, CORRELATION),
        )

        registry.fail("key-1", REFUSAL)

        val failure = assertInstanceOf(
            java.util.concurrent.ExecutionException::class.java,
            runCatching { awaiting.future.get() }.exceptionOrNull(),
        )
        val cause = assertInstanceOf(ServiceStartFailed::class.java, failure.cause)
        assertEquals(
            REFUSAL,
            cause.refusal,
            "the caller that waited reads the SAME refusal the owner read, " +
                "and not a summary of it",
        )
    }

    @Test
    fun `a record that cannot be written stays in memory and the answer is still given`(
        @TempDir root: Path,
    ) {
        // A registry whose file lives in a directory that is NOT there: the
        // write fails and the machine, not the run, is at fault.
        val unreachable = root.resolve("not-there").resolve(ServiceStartRegistry.FILE_NAME)
        val registry = assertInstanceOf(
            ServiceStartRegistryReading.Read::class.java,
            ServiceStartRegistry.read(unreachable),
        ).registry
        registry.claim("key-1", DIGEST, CORRELATION)

        registry.complete("key-1", RECORD)

        assertEquals(
            RECORD,
            registry.find("key-1"),
            "the record stays in memory, so the promise holds for the life of this service",
        )
    }

    @Test
    fun `a start whose file cannot be written is refused and leaves nobody waiting`(
        @TempDir root: Path,
    ) {
        val unreachable = root.resolve("not-there").resolve(ServiceStartRegistry.FILE_NAME)
        val registry = assertInstanceOf(
            ServiceStartRegistryReading.Read::class.java,
            ServiceStartRegistry.read(unreachable),
        ).registry

        val first = registry.claim("key-1", DIGEST, CORRELATION)

        val refused = assertInstanceOf(ServiceStartClaim.Refused::class.java, first)
        assertTrue(
            refused.cause.isNotBlank(),
            "the refusal says why the claim could not be written",
        )
        assertInstanceOf(
            ServiceStartClaim.Refused::class.java,
            registry.claim("key-1", DIGEST, CORRELATION),
            "the second caller is refused too: a file that cannot be written must not leave the " +
                "callers waiting for ever, and a key that was not claimed grants nothing",
        )
    }

    @Test
    fun `the record of the starts survives the service`(@TempDir root: Path) {
        val path = root.resolve(ServiceStartRegistry.FILE_NAME)
        val registry = registry(root)
        registry.claim("key-1", DIGEST, CORRELATION)
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
    fun `a claim that was never answered survives the service`(@TempDir root: Path) {
        val path = root.resolve(ServiceStartRegistry.FILE_NAME)
        val registry = registry(root)
        registry.claim("key-1", DIGEST, CORRELATION)

        val read = ServiceStartRegistry.read(path)

        val reread = assertInstanceOf(ServiceStartRegistryReading.Read::class.java, read).registry
        assertNull(
            reread.find("key-1"),
            "the claim was never answered, so there is no run to remember",
        )
    }

    @Test
    fun `a claim that was never answered is not started twice after the service dies`(
        @TempDir root: Path,
    ) {
        val path = root.resolve(ServiceStartRegistry.FILE_NAME)
        val registry = registry(root)
        registry.claim("key-1", DIGEST, CORRELATION)

        val reread = assertInstanceOf(
            ServiceStartRegistryReading.Read::class.java,
            ServiceStartRegistry.read(path),
        ).registry
        val claim = reread.claim("key-1", DIGEST, CORRELATION)

        val unknown = assertInstanceOf(ServiceStartClaim.EffectUnknown::class.java, claim)
        assertEquals(
            CORRELATION,
            unknown.correlationId,
            "the correlation is what lets the operator find the run the service lost",
        )
    }

    @Test
    fun `a claim that was never answered is a conflict with another request`(@TempDir root: Path) {
        val path = root.resolve(ServiceStartRegistry.FILE_NAME)
        val registry = registry(root)
        registry.claim("key-1", DIGEST, CORRELATION)

        val reread = assertInstanceOf(
            ServiceStartRegistryReading.Read::class.java,
            ServiceStartRegistry.read(path),
        ).registry

        assertEquals(
            ServiceStartClaim.Conflict,
            reread.claim("key-1", "another-digest", CORRELATION),
            "a key names a request, and the request of the lost start is not this one",
        )
    }

    @Test
    fun `a start that failed leaves no claim behind`(@TempDir root: Path) {
        val path = root.resolve(ServiceStartRegistry.FILE_NAME)
        val registry = registry(root)
        registry.claim("key-1", DIGEST, CORRELATION)

        registry.fail("key-1", REFUSAL)

        val reread = assertInstanceOf(
            ServiceStartRegistryReading.Read::class.java,
            ServiceStartRegistry.read(path),
        ).registry
        assertInstanceOf(
            ServiceStartClaim.Owner::class.java,
            reread.claim("key-1", DIGEST, CORRELATION),
            "a start that failed started nothing, so the key is free after a restart too",
        )
    }

    @Test
    fun `the record of the starts is private`(@TempDir root: Path) {
        val path = root.resolve(ServiceStartRegistry.FILE_NAME)
        val registry = registry(root)
        registry.claim("key-1", DIGEST, CORRELATION)
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
    fun `a record that holds a start without a fingerprint is refused`(@TempDir root: Path) {
        val path = root.resolve(ServiceStartRegistry.FILE_NAME)
        Files.writeString(path, """{"key-1":{"correlationId":"c"}}""")

        val read = ServiceStartRegistry.read(path)

        assertInstanceOf(
            ServiceStartRegistryReading.Refused::class.java,
            read,
            "a start without a fingerprint names no request",
        )
    }

    @Test
    fun `a record that holds a claim without a correlation is refused`(@TempDir root: Path) {
        val path = root.resolve(ServiceStartRegistry.FILE_NAME)
        Files.writeString(path, """{"key-1":{"digest":"d"}}""")

        val read = ServiceStartRegistry.read(path)

        assertInstanceOf(
            ServiceStartRegistryReading.Refused::class.java,
            read,
            "a claim without a correlation cannot be reconciled by anybody",
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

        /** The correlation the service gave to the run. */
        private const val CORRELATION: String = "correlation-1"

        /** The refusal a failed start is answered with. */
        private val REFUSAL: ServiceRefusal = ServiceRefusal.RunStartRefused(
            "the-child-died",
            "the run ended before it owned itself",
        )

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
