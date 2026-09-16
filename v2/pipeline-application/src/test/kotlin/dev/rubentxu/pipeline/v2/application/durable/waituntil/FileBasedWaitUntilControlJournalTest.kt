package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.WaitUntilControlRowSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Unit tests for [FileBasedWaitUntilControlJournal] — the file-based
 * control store for the waitUntil predicate polling loop.
 *
 * WU-G5R.5 — mirrors [FileBasedRetryControlJournalTest] but for waitUntil.
 *
 * ## Key contract points
 * - `beginAttempt` persists BEFORE any child launch (persist-before-effects).
 * - `beginAttempt` is idempotent for the same (controlOpId, attempt, fingerprint).
 * - A second `beginAttempt` with a different fingerprint throws
 *   [WaitUntilControlJournalDivergenceException].
 * - `updateStatus` mutates a single attempt's aggregate status atomically.
 * - `readState` returns the persisted control rows.
 */
class FileBasedWaitUntilControlJournalTest {

    @TempDir
    lateinit var tempDir: Path

    private val CONTROL_OP_ID = "run-x/stage-0/step-2/waitUntil/0/0"
    private val fp: Fingerprint = Fingerprint("abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234")
    private val fp2: Fingerprint = Fingerprint("ef01ef01ef01ef01ef01ef01ef01ef01ef01ef01ef01ef01ef01ef01ef01ef01")

    private fun journal() = FileBasedWaitUntilControlJournal(tempDir)

    // =============================================================================
    // Fresh state
    // =============================================================================

    @Test
    fun `empty store returns empty rows`() {
        val j = journal()
        val state = j.readState(
            controlOpId = CONTROL_OP_ID,
            initialRecurrencePeriodMs = 1000L,
            maxBackoffMs = 8000L,
            currentFingerprint = fp,
        )
        assertTrue(state.controlRows.isEmpty(), "fresh store must yield no control rows")
    }

    // =============================================================================
    // beginAttempt
    // =============================================================================

    @Test
    fun `beginAttempt persists a RUNNING control row at attempt 1`() {
        val j = journal()
        j.beginAttempt(
            controlOpId = CONTROL_OP_ID,
            attempt = 1,
            currentBackoffMs = 1000L,
            fingerprint = fp,
            status = OperationStatus.RUNNING,
        )

        val state = j.readState(
            controlOpId = CONTROL_OP_ID,
            initialRecurrencePeriodMs = 1000L,
            maxBackoffMs = 8000L,
            currentFingerprint = fp,
        )
        assertEquals(1, state.controlRows.size)
        assertEquals(1, state.controlRows[0].attempt)
        assertEquals(OperationStatus.RUNNING, state.controlRows[0].status)
        assertEquals(1000L, state.controlRows[0].currentBackoffMs)
        assertEquals(fp.hex, state.controlRows[0].fingerprint.hex)
    }

    @Test
    fun `beginAttempt is idempotent for same fingerprint`() {
        val j = journal()
        j.beginAttempt(CONTROL_OP_ID, 1, 1000L, fp, OperationStatus.RUNNING)
        j.beginAttempt(CONTROL_OP_ID, 1, 1000L, fp, OperationStatus.RUNNING) // duplicate

        val state = j.readState(CONTROL_OP_ID, 1000L, 8000L, fp)
        assertEquals(1, state.controlRows.size, "idempotent call must not duplicate rows")
    }

    @Test
    fun `beginAttempt throws on fingerprint divergence`() {
        val j = journal()
        j.beginAttempt(CONTROL_OP_ID, 1, 1000L, fp, OperationStatus.RUNNING)

        val ex = runCatching {
            j.beginAttempt(CONTROL_OP_ID, 1, 1000L, fp2, OperationStatus.RUNNING)
        }.exceptionOrNull()

        assertNotNull(ex, "divergent fingerprint must throw")
        assertTrue(ex is WaitUntilControlJournalDivergenceException)
    }

    @Test
    fun `beginAttempt persists multiple attempts`() {
        val j = journal()
        j.beginAttempt(CONTROL_OP_ID, 1, 1000L, fp, OperationStatus.RUNNING)
        j.beginAttempt(CONTROL_OP_ID, 2, 2000L, fp, OperationStatus.RUNNING)

        val state = j.readState(CONTROL_OP_ID, 1000L, 8000L, fp)
        assertEquals(2, state.controlRows.size)
        val byAttempt = state.controlRows.associateBy { it.attempt }
        assertEquals(OperationStatus.RUNNING, byAttempt[1]!!.status)
        assertEquals(1000L, byAttempt[1]!!.currentBackoffMs)
        assertEquals(OperationStatus.RUNNING, byAttempt[2]!!.status)
        assertEquals(2000L, byAttempt[2]!!.currentBackoffMs)
    }

    // =============================================================================
    // updateStatus
    // =============================================================================

    @Test
    fun `updateStatus transitions RUNNING to SUCCEEDED`() {
        val j = journal()
        j.beginAttempt(CONTROL_OP_ID, 1, 1000L, fp, OperationStatus.RUNNING)
        j.updateStatus(CONTROL_OP_ID, 1, OperationStatus.SUCCEEDED, fp)

        val state = j.readState(CONTROL_OP_ID, 1000L, 8000L, fp)
        assertEquals(1, state.controlRows.size)
        assertEquals(OperationStatus.SUCCEEDED, state.controlRows[0].status)
    }

    @Test
    fun `updateStatus transitions RUNNING to FAILED`() {
        val j = journal()
        j.beginAttempt(CONTROL_OP_ID, 1, 1000L, fp, OperationStatus.RUNNING)
        j.updateStatus(CONTROL_OP_ID, 1, OperationStatus.FAILED, fp)

        val state = j.readState(CONTROL_OP_ID, 1000L, 8000L, fp)
        assertEquals(1, state.controlRows.size)
        assertEquals(OperationStatus.FAILED, state.controlRows[0].status)
    }

    @Test
    fun `updateStatus transitions RUNNING to FAILED_TIMEOUT`() {
        val j = journal()
        j.beginAttempt(CONTROL_OP_ID, 1, 1000L, fp, OperationStatus.RUNNING)
        j.updateStatus(CONTROL_OP_ID, 1, OperationStatus.FAILED_TIMEOUT, fp)

        val state = j.readState(CONTROL_OP_ID, 1000L, 8000L, fp)
        assertEquals(1, state.controlRows.size)
        assertEquals(OperationStatus.FAILED_TIMEOUT, state.controlRows[0].status)
    }

    @Test
    fun `updateStatus throws on fingerprint divergence`() {
        val j = journal()
        j.beginAttempt(CONTROL_OP_ID, 1, 1000L, fp, OperationStatus.RUNNING)

        val ex = runCatching {
            j.updateStatus(CONTROL_OP_ID, 1, OperationStatus.SUCCEEDED, fp2)
        }.exceptionOrNull()

        assertNotNull(ex, "divergent fingerprint must throw")
        assertTrue(ex is WaitUntilControlJournalDivergenceException)
    }

    @Test
    fun `updateStatus throws when attempt does not exist`() {
        val j = journal()
        // No beginAttempt — no row exists.

        val ex = runCatching {
            j.updateStatus(CONTROL_OP_ID, 1, OperationStatus.SUCCEEDED, fp)
        }.exceptionOrNull()

        assertNotNull(ex, "update on non-existent attempt must throw")
        assertTrue(ex is IllegalStateException)
    }

    @Test
    fun `updateStatus is idempotent for same status`() {
        val j = journal()
        j.beginAttempt(CONTROL_OP_ID, 1, 1000L, fp, OperationStatus.RUNNING)
        j.updateStatus(CONTROL_OP_ID, 1, OperationStatus.SUCCEEDED, fp)
        j.updateStatus(CONTROL_OP_ID, 1, OperationStatus.SUCCEEDED, fp) // duplicate

        val state = j.readState(CONTROL_OP_ID, 1000L, 8000L, fp)
        assertEquals(1, state.controlRows.size)
        assertEquals(OperationStatus.SUCCEEDED, state.controlRows[0].status)
    }

    // =============================================================================
    // Replay / fingerprint isolation
    // =============================================================================

    @Test
    fun `readState returns all persisted rows for matching fingerprint`() {
        val j = journal()
        j.beginAttempt(CONTROL_OP_ID, 1, 1000L, fp, OperationStatus.RUNNING)
        j.updateStatus(CONTROL_OP_ID, 1, OperationStatus.SUCCEEDED, fp)
        j.beginAttempt(CONTROL_OP_ID, 2, 2000L, fp, OperationStatus.RUNNING)
        j.updateStatus(CONTROL_OP_ID, 2, OperationStatus.FAILED, fp)

        val state = j.readState(CONTROL_OP_ID, 1000L, 8000L, fp)
        assertEquals(2, state.controlRows.size)
        assertEquals(OperationStatus.SUCCEEDED, state.controlRows.find { it.attempt == 1 }!!.status)
        assertEquals(OperationStatus.FAILED, state.controlRows.find { it.attempt == 2 }!!.status)
    }

    // =============================================================================
    // Typical polling sequence
    // =============================================================================

    @Test
    fun `typical sequence — attempt 1 FAILED, attempt 2 SUCCEEDED`() {
        val j = journal()

        // Attempt 1: start → evaluate → unsatisfied
        j.beginAttempt(CONTROL_OP_ID, 1, 1000L, fp, OperationStatus.RUNNING)
        j.updateStatus(CONTROL_OP_ID, 1, OperationStatus.FAILED, fp)

        val state1 = j.readState(CONTROL_OP_ID, 1000L, 8000L, fp)
        assertEquals(1, state1.controlRows.size)
        assertEquals(OperationStatus.FAILED, state1.controlRows[0].status)

        // Attempt 2: start → evaluate → satisfied
        j.beginAttempt(CONTROL_OP_ID, 2, 2000L, fp, OperationStatus.RUNNING)
        j.updateStatus(CONTROL_OP_ID, 2, OperationStatus.SUCCEEDED, fp)

        val state2 = j.readState(CONTROL_OP_ID, 1000L, 8000L, fp)
        assertEquals(2, state2.controlRows.size)
        assertEquals(
            OperationStatus.FAILED,
            state2.controlRows.find { it.attempt == 1 }!!.status,
        )
        assertEquals(
            OperationStatus.SUCCEEDED,
            state2.controlRows.find { it.attempt == 2 }!!.status,
        )
    }

    @Test
    fun `typical sequence — attempt 1 FAILED, attempt 2 FAILED_TIMEOUT (ceiling)`() {
        val j = journal()

        // Attempt 1: start → evaluate → unsatisfied
        j.beginAttempt(CONTROL_OP_ID, 1, 1000L, fp, OperationStatus.RUNNING)
        j.updateStatus(CONTROL_OP_ID, 1, OperationStatus.FAILED, fp)

        // Attempt 2: start → evaluate → unsatisfied → next backoff would exceed ceiling
        j.beginAttempt(CONTROL_OP_ID, 2, 2000L, fp, OperationStatus.RUNNING)
        j.updateStatus(CONTROL_OP_ID, 2, OperationStatus.FAILED_TIMEOUT, fp)

        val state = j.readState(CONTROL_OP_ID, 1000L, 8000L, fp)
        assertEquals(2, state.controlRows.size)
        assertEquals(
            OperationStatus.FAILED,
            state.controlRows.find { it.attempt == 1 }!!.status,
        )
        assertEquals(
            OperationStatus.FAILED_TIMEOUT,
            state.controlRows.find { it.attempt == 2 }!!.status,
        )
    }
}
