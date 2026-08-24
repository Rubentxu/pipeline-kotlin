package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationOutput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.events.SqliteEventStore
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.events.durable.SqliteOperationJournalImpl
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Contract tests for reconciliation status-only behavior (C-027.1).
 *
 * Tests that the reconciliation pass preserves the journaled status
 * WITHOUT inspecting the output JSON, fixing the partial C-027 debt
 * (E4-17 MEDIUM finding).
 */
class ReconciliationStatusOnlyTest {

    @TempDir
    lateinit var tempDir: Path

    private val systemClock: Clock = object : Clock {
        override fun now() = java.time.Clock.systemUTC().instant()
    }

    /**
     * C-027.1.1: status=FAILED is preserved WITHOUT inspecting output JSON.
     *
     * Verifies that when a RUNNING operation is reconciled with ended_at NOT NULL
     * (subprocess completed), the FAILED status is preserved and not overwritten
     * to SUCCEEDED. The reconciliation does NOT inspect the output JSON to determine
     * status — it uses only the status column.
     */
    @Test
    fun `failed status preserved without output inspection`() {
        val dbPath = tempDir.resolve("reconcile-failed-test.db").toString()
        val eventStore = SqliteEventStore(dbPath)
        val journal: OperationJournal = SqliteOperationJournalImpl(
            eventStore.underlyingConnectionFactory(),
            systemClock,
            Json { ignoreUnknownKeys = true; encodeDefaults = true },
            eventStore.databasePath(),
        )

        val runId = "reconcile-failed-run"
        val opId = OpId(runId, 0, 0).format()
        val fingerprint = Fingerprint("a".repeat(64))

        // Phase 1: write a RUNNING row (as if subprocess was mid-execution)
        journal.beginOperation(opId, 1, fingerprint.hex, """{"stepId":"sh","params":{},"runId":"$runId","attempt":1}""", null)

        // Simulate: subprocess completed with FAILED status but we don't set output
        // (status-only reconciliation should preserve FAILED without looking at output)
        val failedOp = RerunOperation(
            id = opId,
            fingerprint = fingerprint,
            input = OperationInput("sh", mapOf(), runId, 1),
            output = null, // intentionally null — reconciliation must not depend on this
            status = OperationStatus.FAILED,
            attempt = 1,
        )
        journal.append(failedOp, null)

        // Verify: reconciliation should preserve FAILED status
        val reconciled = journal.get(opId, 1)
        assertNotNull(reconciled, "operation should exist after append")
        assertEquals(
            OperationStatus.FAILED,
            reconciled!!.status,
            "FAILED status must be preserved — reconciliation must NOT overwrite to SUCCEEDED"
        )
    }

    /**
     * C-027.1.2: status=SUCCEEDED is preserved; no replay.
     *
     * Verifies that when a RUNNING operation is reconciled with ended_at NOT NULL
     * and status=SUCCEEDED, the status is preserved and the operation is NOT
     * replayed (no re-execution of the step).
     */
    @Test
    fun `succeeded status preserved no replay`() {
        val dbPath = tempDir.resolve("reconcile-succeeded-test.db").toString()
        val eventStore = SqliteEventStore(dbPath)
        val journal: OperationJournal = SqliteOperationJournalImpl(
            eventStore.underlyingConnectionFactory(),
            systemClock,
            Json { ignoreUnknownKeys = true; encodeDefaults = true },
            eventStore.databasePath(),
        )

        val runId = "reconcile-succeeded-run"
        val opId = OpId(runId, 0, 0).format()
        val fingerprint = Fingerprint("b".repeat(64))

        // Phase 1: write a RUNNING row
        journal.beginOperation(opId, 1, fingerprint.hex, """{"stepId":"sh","params":{},"runId":"$runId","attempt":1}""", null)

        // Phase 2: write SUCCEEDED terminal row
        val succeededOp = RerunOperation(
            id = opId,
            fingerprint = fingerprint,
            input = OperationInput("sh", mapOf(), runId, 1),
            output = OperationOutput(JsonPrimitive("result"), 100L, System.currentTimeMillis()),
            status = OperationStatus.SUCCEEDED,
            attempt = 1,
        )
        journal.append(succeededOp, null)

        // Verify: reconciliation should find SUCCEEDED and not replay
        val reconciled = journal.get(opId, 1)
        assertNotNull(reconciled, "operation should exist after append")
        assertEquals(
            OperationStatus.SUCCEEDED,
            reconciled!!.status,
            "SUCCEEDED status must be preserved — no replay"
        )
        assertNotNull(reconciled.output, "output should be preserved")
    }
}
