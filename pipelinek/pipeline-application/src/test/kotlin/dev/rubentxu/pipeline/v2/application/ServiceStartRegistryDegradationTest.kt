package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.service.ServiceRefusal
import java.io.IOException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The degradation of the promise an idempotency key makes.
 *
 * ## The guarantee under trial
 *
 * That a start whose result could not be written down is NAMED: the operator
 * is told which start it was, which run is at stake, and what to do about it,
 * without a credential in the line. And that a service which restarts after
 * such a failure recovers a key whose effect is unknown, keeping the
 * correlation of the lost run and naming the operations that reconcile it.
 *
 * ## The capacity this serves
 *
 * The record of the starts is a file, and a file is written by a machine that
 * can refuse: a disk that is full, a directory that is gone, a filesystem
 * that is read only. The service keeps answering its clients anyway, because
 * the run has started and a client left without an answer reads a closed
 * connection as a failure of the start. What it must not do is keep that
 * failure to itself: a promise that degraded in silence leaves an operator
 * with a key that is stuck, and no way of knowing why.
 *
 * ## Why this instrument is a UNIT test
 *
 * The failure of a write is the thing under trial, and it cannot be
 * provoked on a disk that is healthy: the registry writes through a store,
 * and this test hands it one that refuses on demand. Nothing here touches a
 * file, a socket, a clock or a process: the record of the starts lives in the
 * memory of the test, and the restart is a second registry over the same
 * store.
 *
 * ## What this does not freeze
 *
 * The words of the lines the operator reads, and the shape of the file the
 * service owns: those are other guarantees. What this freezes is that the
 * degradation is said, that it says the correlation and the run, that it
 * says no credential, and that the recovered state after a failed write is
 * an unknown effect with a guide and not a run that never started.
 */
class ServiceStartRegistryDegradationTest {

    @Test
    fun `a start whose outcome could not be written is named to the operator`() {
        val store = MemoryStore()
        val diagnostics = RecordingDiagnostics()
        val registry = registry(store, diagnostics)
        registry.claim(KEY, DIGEST, CORRELATION)
        store.refuseWrites = true

        registry.complete(KEY, RECORD)

        assertEquals(
            1,
            diagnostics.degradations.size,
            "the degradation is said once: a promise that broke in silence is a broken promise",
        )
        val degradation = diagnostics.degradations.single()
        assertInstanceOf(
            ServiceStartDegradation.Started::class.java,
            degradation,
            "the start that could not be written down started a run",
        )
        assertEquals(
            CORRELATION,
            degradation.correlationId,
            "the correlation is what lets the operator find the run",
        )
        assertEquals(
            RUN_ID,
            (degradation as ServiceStartDegradation.Started).runId,
            "the operator is told which run is at stake",
        )
        assertTrue(
            degradation.detail.isNotBlank(),
            "the degradation says why the machine refused the write",
        )
    }

    @Test
    fun `the degradation says the run and the correlation, and no credential`() {
        val store = MemoryStore()
        val diagnostics = RecordingDiagnostics()
        val registry = registry(store, diagnostics)
        registry.claim(KEY, DIGEST, CORRELATION)
        store.refuseWrites = true

        registry.complete(KEY, RECORD)

        val described = diagnostics.degradations.single().describe()
        assertFalse(
            described.contains(DIGEST),
            "the fingerprint of the credential is not said: $described",
        )
        assertFalse(
            described.contains(KEY),
            "the key of the start is not said: $described",
        )
        assertTrue(
            described.contains(CORRELATION),
            "the correlation is said, because it is what finds the run: $described",
        )
        assertTrue(
            described.contains(RUN_ID),
            "the run is said, because it is what has to be reconciled: $described",
        )
    }

    @Test
    fun `a durable claim and an outcome that could not be written leave an unknown effect after a restart`() {
        val store = MemoryStore()
        val registry = registry(store)
        registry.claim(KEY, DIGEST, CORRELATION)
        store.refuseWrites = true

        registry.complete(KEY, RECORD)

        val restarted = registry(store)
        val claim = restarted.claim(KEY, DIGEST, CORRELATION)
        val unknown = assertInstanceOf(
            ServiceStartClaim.EffectUnknown::class.java,
            claim,
            "the file still holds the claim, so the service cannot tell whether the run started, " +
                "and the key is not free: a second run for the same key is exactly what the key " +
                "promises there is not",
        )
        assertEquals(
            CORRELATION,
            unknown.correlationId,
            "the correlation of the lost start survives the restart",
        )
        assertNull(
            restarted.find(KEY),
            "the recovered state is not the run that started: the promise degraded across the " +
                "restart, and the degradation was said out loud",
        )
    }

    @Test
    fun `the unknown effect names the operations that reconcile it`() {
        val store = MemoryStore()
        val registry = registry(store)
        registry.claim(KEY, DIGEST, CORRELATION)
        store.refuseWrites = true
        registry.complete(KEY, RECORD)
        val restarted = registry(store)

        val unknown = assertInstanceOf(
            ServiceStartClaim.EffectUnknown::class.java,
            restarted.claim(KEY, DIGEST, CORRELATION),
        )

        assertTrue(
            unknown.reconciliation.contains("runs recover"),
            "the state names the operation that reconciles a run whose work finished: " +
                unknown.reconciliation,
        )
        assertTrue(
            unknown.reconciliation.contains("runs cancel"),
            "the state names the operation that closes a run that is still there: " +
                unknown.reconciliation,
        )
        assertTrue(
            unknown.reconciliation.contains(CORRELATION),
            "the state says which run to reconcile: ${unknown.reconciliation}",
        )
    }

    @Test
    fun `a start that failed and could not be released leaves the key claimed after a restart`() {
        val store = MemoryStore()
        val diagnostics = RecordingDiagnostics()
        val registry = registry(store, diagnostics)
        registry.claim(KEY, DIGEST, CORRELATION)
        store.refuseWrites = true

        registry.fail(KEY, REFUSAL)

        val restarted = registry(store)
        assertInstanceOf(
            ServiceStartClaim.EffectUnknown::class.java,
            restarted.claim(KEY, DIGEST, CORRELATION),
            "the file could not be told that the start failed, so the key stays claimed on the " +
                "disk and a retry is refused",
        )
        assertTrue(
            diagnostics.degradations.single().describe().contains("no run was left behind"),
            "the operator is told that there is no run to reconcile, so the key is freed by hand: " +
                diagnostics.degradations.single().describe(),
        )
    }

    @Test
    fun `a start that is written says nothing`() {
        val store = MemoryStore()
        val diagnostics = RecordingDiagnostics()
        val registry = registry(store, diagnostics)
        registry.claim(KEY, DIGEST, CORRELATION)

        registry.complete(KEY, RECORD)

        assertTrue(
            diagnostics.degradations.isEmpty(),
            "a record that was written has nothing to say: the operator is not told about a " +
                "degradation that did not happen",
        )
    }

    @Test
    fun `the record of a degraded start stays in memory and the retry gets the same run`() {
        val store = MemoryStore()
        val registry = registry(store)
        registry.claim(KEY, DIGEST, CORRELATION)
        store.refuseWrites = true

        registry.complete(KEY, RECORD)

        assertEquals(
            RECORD,
            registry.find(KEY),
            "the record stays in memory, so the promise holds for the life of this service",
        )
        val claim = registry.claim(KEY, DIGEST, CORRELATION)
        assertInstanceOf(
            ServiceStartClaim.Known::class.java,
            claim,
            "the retry gets the run that already started, and not a second run for the same key",
        )
    }

    /**
     * The record of the starts, in the memory of the test.
     *
     * The store is the seam the registry writes through, and it is here so
     * that the failure of a write can be produced on demand: the degradation
     * this file is about cannot be provoked on a disk that is healthy.
     */
    private class MemoryStore : ServiceStartStore {

        /** Where the record lives, in the words the refusals use. */
        override val location: String = "the memory of the test"

        /** The document as it stands, or null when there is none. */
        var document: String? = null

        /** Whether the next write is refused by the machine. */
        var refuseWrites: Boolean = false

        override fun read(): String? = document

        override fun write(document: String) {
            if (refuseWrites) throw IOException("the machine refused the write")
            this.document = document
        }
    }

    /**
     * The diagnostics that keep what they were told.
     */
    private class RecordingDiagnostics : ServiceStartDiagnostics {
        val degradations = mutableListOf<ServiceStartDegradation>()
        override fun degraded(degradation: ServiceStartDegradation) {
            degradations.add(degradation)
        }
    }

    /**
     * The record of the starts, over [store].
     *
     * A second registry over the same store is what a restart looks like:
     * the memory of the service is gone, and the store is all there is.
     */
    private fun registry(
        store: ServiceStartStore,
        diagnostics: ServiceStartDiagnostics = NoServiceStartDiagnostics,
    ): ServiceStartRegistry {
        val read = ServiceStartRegistry.read(store, diagnostics)
        return assertInstanceOf(ServiceStartRegistryReading.Read::class.java, read).registry
    }

    private companion object {
        /** The key the client chose. */
        private const val KEY: String = "key-1"

        /** The fingerprint of the request that is made. */
        private const val DIGEST: String = "digest-1"

        /** The correlation the service gave to the run. */
        private const val CORRELATION: String = "correlation-1"

        /** The run the start produced. */
        private const val RUN_ID: String = "run-1"

        /** The refusal a failed start is answered with. */
        private val REFUSAL: ServiceRefusal = ServiceRefusal.RunStartRefused(
            "the-child-died",
            "the run ended before it owned itself",
        )

        /** The start that is remembered. */
        private val RECORD: ServiceStartRecord = ServiceStartRecord(
            digest = DIGEST,
            runId = RUN_ID,
            correlationId = CORRELATION,
            ownerId = "owner-1",
            startedAtMs = 1700L,
        )
    }
}
