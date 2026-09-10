package dev.rubentxu.pipeline.v2.application.durable.retry

import dev.rubentxu.pipeline.v2.application.durable.FileBasedRetryControlJournal
import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * RED-first tests for [FileBasedRetryControlJournal] — the per-retry
 * JSON control store under [controlDirRoot].
 *
 * ADR-0075 §4 + §6 — every retry logical invocation has its own control
 * file. Writes are atomic (write to temp, rename). The writer is the
 * single-writer: no other component may mutate the control set.
 *
 * The driver's contract is:
 * - `listChildrenForRetry` returns the per-attempt child rows whose
 *   bodyPath carries the canonical retry-attempt BlockSegment for the
 *   given control identity. Pre-ADR legacy rows are reported via a
 *   separate path so the legacy compat branch can use them.
 * - `beginAttempt` persists BEFORE any child launch (ADR-0075 §6) and
 *   is idempotent for the same (controlOpId, attempt, fingerprint) tuple.
 * - `updateStatus` mutates a single attempt's aggregate status without
 *   rewriting the whole file from scratch (atomic single-row update).
 */
class FileBasedRetryControlJournalTest {

    @TempDir
    lateinit var tempDir: Path

    private val runId = "run-x"
    private val stageIndex = 0
    private val stepIndex = 2
    private val parentPath = listOf(BlockSegment(0, PluginStepId("dir")))
    private val fp: Fingerprint = Fingerprint.compute(
        OperationInput("core.retry", emptyMap(), runId, 1),
        "core.retry",
        ReplayPolicy.MEMOIZED,
        1,
    )
    private val mismatchedFp = Fingerprint("cafecafecafecafecafecafecafecafecafecafecafecafecafecafecafecafe")

    private fun journal() = FileBasedRetryControlJournal(tempDir)

    @Test
    fun `empty store returns empty state`() {
        val j = journal()
        val state = j.readState(
            controlOpId = "ctrl-1",
            runId = runId,
            stageIndex = stageIndex,
            stepIndex = stepIndex,
            parentBodyPath = parentPath,
            maxAttempts = 3,
            currentFingerprint = fp,
        )
        assertTrue(state.controlRows.isEmpty(), "fresh store MUST yield no control rows")
        assertTrue(state.childrenByAttempt.isEmpty(), "fresh store MUST yield no child rows")
        assertTrue(state.preControlChildren.isEmpty(), "fresh store MUST yield no legacy children")
    }

    @Test
    fun `beginAttempt persists a control row at attempt 1`() {
        val j = journal()
        val controlOpId = j.controlOpIdFor(runId, stageIndex, stepIndex, parentPath)
        j.beginAttempt(controlOpId, attempt = 1, fingerprint = fp, status = OperationStatus.PENDING)

        val state = j.readState(
            controlOpId, runId, stageIndex, stepIndex, parentPath, maxAttempts = 3, currentFingerprint = fp,
        )
        assertEquals(1, state.controlRows.size)
        assertEquals(1, state.controlRows.first().attempt)
        assertEquals(OperationStatus.PENDING, state.controlRows.first().status)
        assertEquals(fp, state.controlRows.first().fingerprint)
    }

    @Test
    fun `beginAttempt is idempotent for the same triple`() {
        val j = journal()
        val controlOpId = j.controlOpIdFor(runId, stageIndex, stepIndex, parentPath)
        j.beginAttempt(controlOpId, 1, fp, OperationStatus.RUNNING)
        j.beginAttempt(controlOpId, 1, fp, OperationStatus.RUNNING) // duplicate call
        val state = j.readState(controlOpId, runId, stageIndex, stepIndex, parentPath, 3, fp)
        assertEquals(1, state.controlRows.size, "duplicate begin MUST NOT add a second row")
    }

    @Test
    fun `beginAttempt for distinct attempts persists distinct rows`() {
        val j = journal()
        val controlOpId = j.controlOpIdFor(runId, stageIndex, stepIndex, parentPath)
        j.beginAttempt(controlOpId, 1, fp, OperationStatus.RUNNING)
        j.beginAttempt(controlOpId, 2, fp, OperationStatus.RUNNING)
        j.beginAttempt(controlOpId, 3, fp, OperationStatus.RUNNING)
        val state = j.readState(controlOpId, runId, stageIndex, stepIndex, parentPath, 3, fp)
        assertEquals(3, state.controlRows.size)
        assertEquals(listOf(1, 2, 3), state.controlRows.map { it.attempt }.sorted())
    }

    @Test
    fun `updateStatus mutates a single attempt`() {
        val j = journal()
        val controlOpId = j.controlOpIdFor(runId, stageIndex, stepIndex, parentPath)
        j.beginAttempt(controlOpId, 1, fp, OperationStatus.RUNNING)
        j.beginAttempt(controlOpId, 2, fp, OperationStatus.RUNNING)
        j.updateStatus(controlOpId, 1, OperationStatus.SUCCEEDED, fp)
        val state = j.readState(controlOpId, runId, stageIndex, stepIndex, parentPath, 3, fp)
        val row = state.controlRows.first { it.attempt == 1 }
        assertEquals(OperationStatus.SUCCEEDED, row.status)
        // Attempt 2 untouched.
        assertEquals(OperationStatus.RUNNING, state.controlRows.first { it.attempt == 2 }.status)
    }

    @Test
    fun `mismatched fingerprint on read returns RejectDivergence evidence`() {
        val j = journal()
        val controlOpId = j.controlOpIdFor(runId, stageIndex, stepIndex, parentPath)
        j.beginAttempt(controlOpId, 1, fp, OperationStatus.RUNNING)
        // Reading with a different fingerprint surfaces a control-row fingerprint mismatch
        // — the test confirms the journal exposes the fingerprint field for the
        // Reconciler to gate on. The decision is the Reconciler's responsibility.
        val state = j.readState(controlOpId, runId, stageIndex, stepIndex, parentPath, 3, currentFingerprint = mismatchedFp)
        val stored = state.controlRows.first()
        assertFalse(stored.fingerprint.equals(mismatchedFp), "stored fingerprint MUST be the originally persisted one")
    }

    @Test
    fun `controlOpIdFor matches RetryIdentityFactory's control opId`() {
        // ADR-0075 §2 — the canonical opId is the same string regardless of
        // which component materializes it.
        val j = journal()
        val fromFactory = dev.rubentxu.pipeline.v2.application.durable.RetryIdentityFactory
            .controlOperationId(runId, stageIndex, stepIndex, parentPath)
        val fromJournal = j.controlOpIdFor(runId, stageIndex, stepIndex, parentPath)
        assertEquals(fromFactory, fromJournal)
    }

    @Test
    fun `persisted control file is keyed deterministically per retry`() {
        val j = journal()
        val controlA = j.controlOpIdFor(runId, 0, 0, parentPath)
        val controlB = j.controlOpIdFor(runId, 0, 1, parentPath)
        assertNotNull(controlA)
        assertNotNull(controlB)
        assertFalse(controlA == controlB, "distinct retries MUST map to distinct keys")
    }

    @Test
    fun `persisted file survives a fresh journal instance reading the same root`() {
        val j1 = journal()
        val controlOpId = j1.controlOpIdFor(runId, stageIndex, stepIndex, parentPath)
        j1.beginAttempt(controlOpId, 1, fp, OperationStatus.SUCCEEDED)

        // Fresh instance pointing at the same root reads the persisted file.
        val j2 = FileBasedRetryControlJournal(tempDir)
        val state = j2.readState(controlOpId, runId, stageIndex, stepIndex, parentPath, 3, fp)
        assertEquals(1, state.controlRows.size)
        assertEquals(OperationStatus.SUCCEEDED, state.controlRows.first().status)
    }

    @Test
    fun `fingerprint-column equality uses the value class semantics`() {
        // Sanity check that two fingerprints computed with the same input fingerprint
        // match the journal's persisted fingerprint field.
        val j = journal()
        val controlOpId = j.controlOpIdFor(runId, stageIndex, stepIndex, parentPath)
        j.beginAttempt(controlOpId, 1, fp, OperationStatus.SUCCEEDED)
        val state = j.readState(controlOpId, runId, stageIndex, stepIndex, parentPath, 3, fp)
        val stored = state.controlRows.first().fingerprint
        // Fingerprint is a value class with equality on the hex field.
        assertEquals(fp.hex, stored.hex)
    }

    @Test
    fun `no control rows yet and no children returns canonical empty state`() {
        val j = journal()
        val controlOpId = j.controlOpIdFor(runId, stageIndex, stepIndex, parentPath)
        val state = j.readState(controlOpId, runId, stageIndex, stepIndex, parentPath, 3, fp)
        assertNull(state.firstLegacyChildAttemptOrNull(), "no legacy children means no legacy attempt ordinal")
    }
}

/**
 * First legacy attempt ordinal (1-based) under the legacy compat path, or
 * null when no legacy child rows exist.
 *
 * This helper lives next to the test fixtures for clarity.
 */
private fun dev.rubentxu.pipeline.v2.application.durable.RetryControlState.firstLegacyChildAttemptOrNull(): Int? {
    if (preControlChildren.isEmpty()) return null
    return preControlChildren.minOf { it.attempt }
}
