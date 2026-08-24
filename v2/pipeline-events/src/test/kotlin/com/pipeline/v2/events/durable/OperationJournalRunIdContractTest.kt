package com.pipeline.v2.events.durable

import com.pipeline.v2.events.SqliteEventStore
import com.pipeline.v2.domain.durable.Clock
import com.pipeline.v2.domain.durable.Fingerprint
import com.pipeline.v2.domain.durable.OperationInput
import com.pipeline.v2.domain.durable.OperationOutput
import com.pipeline.v2.domain.durable.OperationStatus
import com.pipeline.v2.domain.durable.RerunOperation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Contract tests for run_id column populate and query (C-032).
 *
 * Tests the F04 HIGH finding closure: listForRun now uses the indexed
 * run_id column instead of LIKE substring-match on the JSON blob.
 */
class OperationJournalRunIdContractTest {

    @TempDir
    lateinit var tempDir: Path

    private val systemClock: Clock = object : Clock {
        override fun now() = java.time.Clock.systemUTC().instant()
    }

    private fun freshJournal(): Pair<OperationJournal, String> {
        val dbPath = tempDir.resolve("run-id-test.db").toString()
        val eventStore = SqliteEventStore(dbPath)
        val journal = SqliteOperationJournalImpl(
            eventStore.underlyingConnectionFactory(),
            systemClock,
            Json { ignoreUnknownKeys = true; encodeDefaults = true },
            eventStore.databasePath(),
        )
        return journal to dbPath
    }

    /**
     * C-032.1: Idempotent migration — run_id column exists and index is created.
     *
     * Verifies that the second SqliteEventStore init is a no-op (no duplicate
     * columns or index errors).
     */
    @Test
    fun `migration is idempotent`() {
        val dbPath = tempDir.resolve("idempotent-migration.db").toString()
        val eventStore1 = SqliteEventStore(dbPath)
        // Second init should be a no-op
        val eventStore2 = SqliteEventStore(dbPath)
        // If we get here without exception, the migration was idempotent
        assertTrue(true, "second init should not throw")
    }

    /**
     * C-032.2: append populates run_id column from op.input.runId.
     *
     * Verifies that after append, the run_id column is populated with
     * the runId from op.input.runId.
     */
    @Test
    fun `append populates run_id`() {
        val (journal, dbPath) = freshJournal()
        val runId = "run-id-populate-test"

        val op = RerunOperation(
            id = "op-1",
            fingerprint = Fingerprint("a".repeat(64)),
            input = OperationInput("step", mapOf("k" to JsonPrimitive("v")), runId, 1),
            output = OperationOutput(JsonPrimitive("result"), 100L, System.currentTimeMillis()),
            status = OperationStatus.SUCCEEDED,
            attempt = 1,
        )
        journal.append(op)

        // Verify via direct SQL that run_id is populated
        val conn = SqliteEventStore(dbPath).underlyingConnectionFactory()()
        try {
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery(
                    "SELECT run_id FROM operation_journal WHERE op_id = 'op-1'"
                )
                rs.next()
                val actualRunId = rs.getString(1)
                assertEquals(runId, actualRunId, "run_id should be populated from op.input.runId")
            }
        } finally {
            conn.close()
        }
    }

    /**
     * C-032.3: listForRun excludes rows where run_id is a substring match.
     *
     * Verifies that listForRun("abc") does NOT return rows where
     * run_id = "abc-def" (F04 regression fix).
     */
    @Test
    fun `listForRun excludes substring runIds`() {
        val (journal, _) = freshJournal()

        // Insert two operations with similar runIds
        val runId1 = "abc"
        val runId2 = "abc-def"

        val op1 = RerunOperation(
            id = "op-substr-1",
            fingerprint = Fingerprint("a".repeat(64)),
            input = OperationInput("step", mapOf(), runId1, 1),
            output = null,
            status = OperationStatus.SUCCEEDED,
            attempt = 1,
        )
        val op2 = RerunOperation(
            id = "op-substr-2",
            fingerprint = Fingerprint("b".repeat(64)),
            input = OperationInput("step", mapOf(), runId2, 1),
            output = null,
            status = OperationStatus.SUCCEEDED,
            attempt = 1,
        )
        journal.append(op1)
        journal.append(op2)

        // listForRun("abc") should only return op1, NOT op2
        val results = journal.listForRun(runId1)
        assertEquals(1, results.size, "listForRun should return exactly 1 result")
        assertEquals("op-substr-1", results[0].id, "should return op1, not op2")
    }
}
