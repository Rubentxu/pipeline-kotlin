package dev.rubentxu.pipeline.v2.credentials.executor

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.credentials.MaterializedCredentialDomain
import dev.rubentxu.pipeline.v2.domain.credentials.ProjectionResult
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The teardown of a credential binding has three phases — wipe the materialisations, append
 * `CredentialUnbound`, wipe the in-memory handles — and each releases something no later phase can.
 *
 * `BoundCredentials.close()` used to call them in sequence with no `try/finally`, so the first
 * throwable ended the teardown. The append is the phase that throws in production: `UNIQUE(run_id,
 * sequence)` made the event store fail closed rather than accept a duplicated sequence, which is a
 * real failure, not a hypothetical one. When it threw, every [SecretHandle] byte array stayed
 * resident and `closed` had already been set, so the retry was a no-op.
 *
 * These tests are pure by construction. The materialisation is an anonymous subclass of the `open`
 * [MaterializedCredentialDomain], so a wipe failure is manufactured without a filesystem, and the
 * handle is a real [SecretHandle] whose wipe is observable through [SecretHandle.bytesView].
 */
class CredentialTeardownTest {

    private val orphan = Paths.get("/tmp/pipelinek-orphan-key.pem")
    private val secretBytes = "correct-horse-battery-staple".toByteArray()

    /**
     * A [ProjectionResult] whose materialisation cannot be wiped, naming [path] as the orphan.
     *
     * The materialisation carries a real [Path] but writes nothing, and it throws from its own
     * `close` rather than throwing a hand-built [ProjectionResult.WipeException]: [ProjectionResult.close]
     * derives the orphan list from `materialized.path`, so a fake with a null path would produce an
     * EMPTY orphan list and every assertion below would pass against the very defect it names.
     */
    private fun failingWipe(path: Path = orphan): ProjectionResult =
        projectionWiping(path) { throw IllegalStateException("delete refused: $path") }

    private fun projectionWiping(path: Path? = null, onClose: () -> Unit = {}): ProjectionResult {
        val materialization = object : MaterializedCredentialDomain(path = path, handle = null) {
            override fun close(): Unit = onClose()
        }
        return ProjectionResult(bindings = emptyMap(), retainedMaterializations = listOf(materialization))
    }

    private fun projectionWipingCleanly(onClose: () -> Unit = {}) = projectionWiping(onClose = onClose)

    private fun bound(
        handle: SecretHandle,
        projections: List<ProjectionResult> = emptyList(),
        closeAction: () -> Unit,
    ) = BoundCredentials(
        env = mapOf("TOKEN" to handle),
        credentials = listOf(CredentialsId("cred-1")),
        retainedProjections = projections,
        closeAction = closeAction,
    )

    // --- the phase that follows a failing phase still runs ------------------------

    @Test
    fun `a teardown runs every phase even when an earlier one throws`() {
        val ran = mutableListOf<String>()

        val outcome = runTeardown(
            listOf(
                TeardownPhase(TeardownPhaseName.Wipe) { ran += "wipe" },
                TeardownPhase(TeardownPhaseName.EmitUnbound) {
                    ran += "unbound"
                    error("append rejected")
                },
                TeardownPhase(TeardownPhaseName.CloseHandles) { ran += "handles" },
            ),
        )

        assertEquals(listOf("wipe", "unbound", "handles"), ran)
        val failed = assertInstanceOf(TeardownOutcome.Failed::class.java, outcome)
        assertEquals(1, failed.failures.size)
        assertEquals(TeardownPhaseName.EmitUnbound, failed.firstFailure.phase)
    }

    @Test
    fun `a teardown in which no phase threw is Clean`() {
        val outcome = runTeardown(
            listOf(
                TeardownPhase(TeardownPhaseName.Wipe) { },
                TeardownPhase(TeardownPhaseName.EmitUnbound) { },
                TeardownPhase(TeardownPhaseName.CloseHandles) { },
            ),
        )

        assertSame(TeardownOutcome.Clean, outcome)
    }

    @Test
    fun `every failure is reported, not only the first`() {
        val outcome = runTeardown(
            listOf(
                TeardownPhase(TeardownPhaseName.Wipe) { throw IllegalStateException("wipe 1") },
                TeardownPhase(TeardownPhaseName.Wipe) { throw IllegalStateException("wipe 2") },
                TeardownPhase(TeardownPhaseName.EmitUnbound) { throw IllegalStateException("append") },
            ),
        )

        val failed = assertInstanceOf(TeardownOutcome.Failed::class.java, outcome)
        assertEquals(3, failed.failures.size)
        // The first keeps its identity; the rest are reachable only as suppressed.
        assertEquals("wipe 1", failed.firstFailure.throwable.message)
        assertEquals(2, failed.firstFailure.throwable.suppressed.size)
    }

    // --- A4: an append failure no longer leaks the handles ------------------------

    @Test
    fun `a rejected unbound append still wipes the secret handle`() {
        val handle = SecretHandle.secret(secretBytes)

        val failure = assertThrows(IllegalStateException::class.java) {
            bound(handle, closeAction = { throw IllegalStateException("append rejected") }).close()
        }

        assertEquals("append rejected", failure.message)
        // THE assertion. Before the fix the append threw out of close() before closeHandles() ran,
        // so these bytes were still resident and nothing about the throwable said so.
        assertArrayEquals(ByteArray(secretBytes.size), handle.bytesView())
    }

    @Test
    fun `a rejected unbound append still wipes the materializations`() {
        var wiped = false

        val failure = assertThrows(IllegalStateException::class.java) {
            bound(
                handle = SecretHandle.secret(secretBytes),
                projections = listOf(projectionWipingCleanly { wiped = true }),
                closeAction = { throw IllegalStateException("append rejected") },
            ).close()
        }

        assertEquals("append rejected", failure.message)
        assertTrue(wiped, "the projection wipe was skipped because the append threw first")
    }

    @Test
    fun `a rejected unbound append after a failed wipe still reports the orphan paths`() {
        val handle = SecretHandle.secret(secretBytes)

        val failure = assertThrows(BoundCredentials.CleanupException::class.java) {
            bound(
                handle = handle,
                projections = listOf(failingWipe()),
                closeAction = { throw IllegalStateException("append rejected") },
            ).close()
        }

        // Before the fix the append threw out of the wipe-failure branch, so CleanupException was
        // never constructed: the orphan path AND the handle bytes were both lost.
        assertEquals(listOf(orphan), failure.orphanPaths)
        assertArrayEquals(ByteArray(secretBytes.size), handle.bytesView())
    }

    @Test
    fun `orphan paths from every projection are reported, not only the first`() {
        val second = Paths.get("/tmp/pipelinek-orphan-cert.p12")

        val failure = assertThrows(BoundCredentials.CleanupException::class.java) {
            bound(
                handle = SecretHandle.secret(secretBytes),
                projections = listOf(failingWipe(), failingWipe(second)),
                closeAction = { },
            ).close()
        }

        assertEquals(listOf(second, orphan), failure.orphanPaths)
    }

    @Test
    fun `a wipe failure does not stop the unbound append`() {
        var emitted = false

        assertThrows(BoundCredentials.CleanupException::class.java) {
            bound(
                handle = SecretHandle.secret(secretBytes),
                projections = listOf(failingWipe()),
                closeAction = { emitted = true },
            ).close()
        }

        assertTrue(emitted, "the audit append was skipped because the wipe threw first")
    }

    // --- the shapes that were already correct must stay correct -------------------

    @Test
    fun `a clean teardown wipes the handle and throws nothing`() {
        val handle = SecretHandle.secret(secretBytes)

        bound(
            handle = handle,
            projections = listOf(projectionWipingCleanly()),
            closeAction = { },
        ).close()

        assertArrayEquals(ByteArray(secretBytes.size), handle.bytesView())
    }

    @Test
    fun `a teardown is idempotent and a retry after a failure does not re-run it`() {
        var appends = 0
        val handle = SecretHandle.secret(secretBytes)
        val credentials = bound(handle, closeAction = { appends++ })

        credentials.close()
        credentials.close()

        assertEquals(1, appends, "CredentialUnbound must be emitted exactly once per binding")
    }

    @Test
    fun `a failure outside the wipe is not reported as a cleanup failure`() {
        // The audit append rejecting is not a failure to wipe anything. Reporting it as a
        // CleanupException would tell the operator to go looking for orphan files that do not
        // exist, so it propagates unwrapped and the adapter maps it as it always has.
        val handle = SecretHandle.secret(secretBytes)

        val failure = assertThrows(IllegalStateException::class.java) {
            bound(handle, closeAction = { throw IllegalStateException("append rejected") }).close()
        }

        assertNull(failure.cause)
        assertArrayEquals(ByteArray(secretBytes.size), handle.bytesView())
    }

    @Test
    fun `the projections are wiped reverse-LIFO and before the append`() {
        val order = mutableListOf<String>()

        bound(
            handle = SecretHandle.secret(secretBytes),
            projections = listOf(
                projectionWipingCleanly { order += "first" },
                projectionWipingCleanly { order += "second" },
            ),
            closeAction = { order += "unbound" },
        ).close()

        // Reverse-LIFO is the projection contract (last acquired wipes first), and the append
        // cannot precede either wipe: the audit trail records a teardown that actually happened.
        assertEquals(listOf("second", "first", "unbound"), order)
    }
}
