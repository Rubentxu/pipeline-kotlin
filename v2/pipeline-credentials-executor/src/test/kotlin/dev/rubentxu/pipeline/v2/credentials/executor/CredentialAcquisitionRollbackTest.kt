package dev.rubentxu.pipeline.v2.credentials.executor

import dev.rubentxu.pipeline.v2.credentials.spi.CredentialProvider
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.credentials.Credential
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialProjector
import dev.rubentxu.pipeline.v2.domain.credentials.MaterializedCredentialDomain
import dev.rubentxu.pipeline.v2.domain.credentials.ProjectionResult
import dev.rubentxu.pipeline.v2.domain.credentials.SecretText
import dev.rubentxu.pipeline.v2.domain.credentials.StringBindingSpec
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.events.NullEventSink
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant

/**
 * What happens when an acquisition FAILS after some credentials have already been materialised.
 *
 * Bind two credentials and the second can fail — a tampered store, a wrong kind, a missing linked
 * secret — after the first has already written a private key to disk. The rollback closed the
 * projections it had retained with `catch (_: Throwable) { /* swallow: best-effort */ }`, so a key
 * that could not be wiped left no trace whatsoever: the operator was told about the missing
 * credential and nothing about the bytes its predecessor had left behind.
 *
 * The fix folds each wipe failure into the throwable already in flight as a SUPPRESSED exception,
 * so `ProjectionResult.WipeException` stays the single thing that names an orphan path.
 *
 * The materialisations below carry a real [Path] but write nothing: [ProjectionResult.close]
 * derives an orphan path from `materialized.path` when the materialisation throws, so a fake with
 * a null path would report an EMPTY orphan list and every assertion about reporting would pass
 * against a defect. The subclass is what manufactures the failure, not the path.
 */
class CredentialAcquisitionRollbackTest {

    private val orphan = Paths.get("/tmp/pipelinek-idempotency-key")
    private val otherOrphan = Paths.get("/tmp/pipelinek-server.crt")

    /** A [ProjectionResult] holding one materialisation that runs [onClose] when wiped. */
    private fun projectionWiping(path: Path? = null, onClose: () -> Unit): ProjectionResult {
        val materialization = object : MaterializedCredentialDomain(path = path, handle = null) {
            override fun close(): Unit = onClose()
        }
        return ProjectionResult(bindings = emptyMap(), retainedMaterializations = listOf(materialization))
    }

    /** A materialisation whose delete fails, exactly as a locked file would in production. */
    private fun projectionThatCannotWipe(path: Path): ProjectionResult =
        projectionWiping(path) { throw IllegalStateException("delete refused: $path") }

    // --- the rollback reports instead of swallowing --------------------------------

    @Test
    fun `a wipe failure is suppressed onto the throwable already being propagated`() {
        val primary = IllegalStateException("SecretStoreTamperException: cred-2")

        drainProjections(listOf(projectionThatCannotWipe(orphan)), primary)

        // THE assertion. Before the fix this list was empty: the operator saw only the tamper.
        val wipe = assertInstanceOf(ProjectionResult.WipeException::class.java, primary.suppressed.single())
        assertEquals(listOf(orphan), wipe.orphanPaths)
    }

    @Test
    fun `the orphan paths of the failure are returned to the caller as well`() {
        val primary = IllegalStateException("SecretStoreTamperException: cred-2")

        val orphans = drainProjections(listOf(projectionThatCannotWipe(orphan)), primary)

        assertEquals(listOf(orphan), orphans)
    }

    @Test
    fun `every retained projection is drained even when the first one fails`() {
        val drained = mutableListOf<String>()
        val primary = IllegalStateException("boom")

        drainProjections(
            listOf(
                projectionWiping { drained += "first" },
                projectionWiping(otherOrphan) {
                    drained += "second"
                    error("delete refused")
                },
                projectionWiping { drained += "third" },
            ),
            primary,
        )

        // Reverse-LIFO, and no early exit: the projection that wiped cleanly on either side of the
        // failing one still had to be attempted.
        assertEquals(listOf("third", "second", "first"), drained)
        assertEquals(1, primary.suppressed.size)
    }

    @Test
    fun `orphan paths from every projection are collected, not only the first`() {
        val primary = IllegalStateException("boom")

        val orphans = drainProjections(
            listOf(projectionThatCannotWipe(orphan), projectionThatCannotWipe(otherOrphan)),
            primary,
        )

        assertEquals(listOf(otherOrphan, orphan), orphans, "reverse-LIFO: the last acquired fails first")
        assertEquals(2, primary.suppressed.size)
    }

    @Test
    fun `a projection that wipes cleanly adds nothing to the propagated failure`() {
        val primary = IllegalStateException("boom")

        val orphans = drainProjections(listOf(projectionWiping { }), primary)

        assertEquals(emptyList<Path>(), orphans)
        assertEquals(0, primary.suppressed.size)
    }

    // --- and the binding path actually reaches that rollback -----------------------

    @Test
    fun `a failed bind reports the materialization of the credential that did bind`() {
        val executor = WithCredentialsExecutor(
            provider = SingleTextProvider,
            projector = projectorFailingOn("cred-2", orphan),
            clock = FixedClock,
        )

        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                executor.bindSpecs(
                    specs = twoSpecs(),
                    runId = "run-1",
                    eventSink = NullEventSink,
                )
            }
        }

        assertEquals("SecretStoreTamperException: cred-2", failure.message)
        val wipe = assertInstanceOf(ProjectionResult.WipeException::class.java, failure.suppressed.single())
        assertEquals(listOf(orphan), wipe.orphanPaths)
    }

    @Test
    fun `a failed bind whose rollback wipes cleanly reports only the acquisition failure`() {
        val executor = WithCredentialsExecutor(
            provider = SingleTextProvider,
            projector = projectorFailingOn("cred-2", orphan, wipeSucceeds = true),
            clock = FixedClock,
        )

        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                executor.bindSpecs(
                    specs = twoSpecs(),
                    runId = "run-1",
                    eventSink = NullEventSink,
                )
            }
        }

        assertEquals("SecretStoreTamperException: cred-2", failure.message)
        // Guards the other direction: a rollback that genuinely succeeded must not manufacture a
        // suppressed failure, or every operator would chase orphan paths that were wiped.
        assertTrue(failure.suppressed.isEmpty(), "a clean rollback must not fabricate a suppressed failure")
    }

    @Test
    fun `a bind that succeeds hands back a scope that can be closed`() {
        val executor = WithCredentialsExecutor(
            provider = SingleTextProvider,
            projector = CredentialProjector { _, _, _ -> projectionWiping { } },
            clock = FixedClock,
        )

        val bound = runBlocking {
            executor.bindSpecs(
                specs = twoSpecs(),
                runId = "run-1",
                eventSink = NullEventSink,
            )
        }

        assertEquals(listOf(CredentialsId("cred-1"), CredentialsId("cred-2")), bound.credentials())
        bound.close()
    }

    private fun twoSpecs() = listOf(
        StringBindingSpec(CredentialsId("cred-1"), "FIRST"),
        StringBindingSpec(CredentialsId("cred-2"), "SECOND"),
    )

    private fun projectorFailingOn(failingId: String, orphanPath: Path, wipeSucceeds: Boolean = false) =
        CredentialProjector { spec, _, _ ->
            if (spec.credentialsId.value == failingId) {
                error("SecretStoreTamperException: $failingId")
            }
            if (wipeSucceeds) projectionWiping { } else projectionThatCannotWipe(orphanPath)
        }
}

/** A fixed instant. The unit-test rule forbids a real clock; a constant is the fix. */
private object FixedClock : Clock {
    override fun now(): Instant = Instant.parse("2026-01-01T00:00:00Z")
}

/** Resolves every id to the same inert text. Only [CredentialProvider.resolveToCredential] is reached. */
private object SingleTextProvider : CredentialProvider {
    override val providerId: String = "test"
    override fun resolve(id: CredentialsId): SecretHandle = SecretHandle.secret(id.value.toByteArray())
    override fun resolveToCredential(id: CredentialsId): Credential = SecretText(id, bytes = byteArrayOf(0x78))
    override fun close() = Unit
}
