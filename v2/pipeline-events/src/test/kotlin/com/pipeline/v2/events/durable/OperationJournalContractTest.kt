package com.pipeline.v2.events.durable

import com.pipeline.v2.events.SqliteEventStore
import com.pipeline.v2.domain.durable.Clock
import kotlinx.serialization.json.Json
import com.pipeline.v2.domain.durable.Fingerprint
import com.pipeline.v2.domain.durable.OperationInput
import com.pipeline.v2.domain.durable.OperationOutput
import com.pipeline.v2.domain.durable.OperationStatus
import com.pipeline.v2.domain.durable.RerunOperation
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Contract tests for [OperationJournal] interface.
 * Tests the interface contract per M3-R1 design.md §8 and C-013.
 */
class OperationJournalContractTest {

    @TempDir
    lateinit var tempDir: Path

    private fun freshJournal(clock: Clock): OperationJournal {
        val dbPath = tempDir.resolve("contract-test.db").toString()
        val eventStore = SqliteEventStore(dbPath)
        return SqliteOperationJournalImpl(eventStore.underlyingConnectionFactory(), clock, Json { ignoreUnknownKeys = true; encodeDefaults = true }, eventStore.databasePath())
    }

    private val systemClock: Clock = object : Clock {
        override fun now() = java.time.Clock.systemUTC().instant()
    }

    @Test
    fun `append then get returns the same operation`() {
        val journal = freshJournal(systemClock)
        val op = RerunOperation(
            id = "op-contract-1",
            fingerprint = Fingerprint("a".repeat(64)),
            input = OperationInput("step", mapOf("k" to JsonPrimitive("v")), "run-1", 1),
            output = OperationOutput(JsonPrimitive("result"), 100L, System.currentTimeMillis()),
            status = OperationStatus.SUCCEEDED,
            attempt = 1,
        )
        journal.append(op)
        val retrieved = journal.get("op-contract-1")
        assertNotNull(retrieved)
        assertEquals(op.id, retrieved!!.id)
        assertEquals(op.fingerprint.hex, retrieved.fingerprint.hex)
        assertEquals(op.status, retrieved.status)
    }

    @Test
    fun `listForRun returns all operations for that run in order`() {
        val journal = freshJournal(systemClock)
        val runId = "run-contract-list"
        for (i in 1..3) {
            journal.append(
                RerunOperation(
                    id = "op-$i",
                    fingerprint = Fingerprint(("%064x").format(i.toLong())),
                    input = OperationInput("step", mapOf(), runId, 1),
                    output = OperationOutput(JsonPrimitive("result-$i"), 10L, System.currentTimeMillis()),
                    status = OperationStatus.SUCCEEDED,
                    attempt = 1,
                )
            )
        }
        val ops = journal.listForRun(runId)
        assertEquals(3, ops.size)
        assertEquals("op-1", ops[0].id)
        assertEquals("op-2", ops[1].id)
        assertEquals("op-3", ops[2].id)
    }

    @Test
    fun `get with non-existent opId returns null`() {
        val journal = freshJournal(systemClock)
        assertNull(journal.get("nonexistent-op"))
    }

    @Test
    fun `append on existing row updates status in place (UPSERT)`() {
        val journal = freshJournal(systemClock)
        val op = RerunOperation(
            id = "op-dup",
            fingerprint = Fingerprint("a".repeat(64)),
            input = OperationInput("step", mapOf(), "run-1", 1),
            output = null,
            status = OperationStatus.SUCCEEDED,
            attempt = 1,
        )
        journal.append(op)
        // Second append updates the existing row (UPSERT, not throw)
        val updatedOp = op.copy(status = OperationStatus.FAILED)
        journal.append(updatedOp)
        // Row is updated to FAILED
        val retrieved = journal.get("op-dup")
        assertNotNull(retrieved)
        assertEquals(OperationStatus.FAILED, retrieved!!.status)
        // fingerprint preserved
        assertEquals(op.fingerprint.hex, retrieved.fingerprint.hex)
    }

    // M3-R3 C-026 contract tests

    @Test
    fun `beginOperation writes RUNNING row with started_at`() {
        val journal = freshJournal(systemClock)
        val opId = "op-begin-1"
        val attempt = 1
        val fingerprint = "a".repeat(64)
        val inputJson = """{"stepId":"sh","params":{},"runId":"run-1","attempt":1}"""

        journal.beginOperation(opId, attempt, fingerprint, inputJson, null)

        val retrieved = journal.get(opId, attempt)
        assertNotNull(retrieved)
        assertEquals(OperationStatus.RUNNING, retrieved!!.status)
        assertEquals(fingerprint, retrieved.fingerprint.hex)
        // started_at is set (not null)
        val startedAt = journal.getStartedAt(opId, attempt)
        assertNotNull(startedAt)
    }

    @Test
    fun `beginOperation on duplicate throws IllegalStateException`() {
        val journal = freshJournal(systemClock)
        val opId = "op-begin-dup"
        val attempt = 1
        val fingerprint = "b".repeat(64)
        val inputJson = """{"stepId":"sh","params":{},"runId":"run-1","attempt":1}"""

        journal.beginOperation(opId, attempt, fingerprint, inputJson, null)

        assertThrows(IllegalStateException::class.java) {
            journal.beginOperation(opId, attempt, fingerprint, inputJson, null)
        }
    }

    @Test
    fun `append UPSERTs RUNNING to SUCCEEDED preserving started_at`() {
        val journal = freshJournal(systemClock)
        val opId = "op-upsert-succ"
        val attempt = 1
        val fingerprint = "c".repeat(64)
        val inputJson = """{"stepId":"sh","params":{},"runId":"run-1","attempt":1}"""

        // Phase 1: beginOperation writes RUNNING
        journal.beginOperation(opId, attempt, fingerprint, inputJson, null)

        // Capture started_at before append
        val startedAtBefore = journal.getStartedAt(opId, attempt)
        assertNotNull(startedAtBefore)

        // Phase 2: append transitions RUNNING → SUCCEEDED
        val terminalOp = RerunOperation(
            id = opId,
            fingerprint = Fingerprint(fingerprint),
            input = OperationInput("sh", mapOf(), "run-1", attempt),
            output = OperationOutput(JsonPrimitive("hello"), 50L, System.currentTimeMillis()),
            status = OperationStatus.SUCCEEDED,
            attempt = attempt,
        )
        journal.append(terminalOp, null)

        // Verify status is SUCCEEDED
        val retrieved = journal.get(opId, attempt)
        assertNotNull(retrieved)
        assertEquals(OperationStatus.SUCCEEDED, retrieved!!.status)
        // started_at preserved (not overwritten by append)
        val startedAtAfter = journal.getStartedAt(opId, attempt)
        assertEquals(startedAtBefore, startedAtAfter)
        // ended_at is set
        val endedAt = journal.getEndedAt(opId, attempt)
        assertNotNull(endedAt)
    }

    @Test
    fun `append UPSERTs RUNNING to FAILED preserving started_at`() {
        val journal = freshJournal(systemClock)
        val opId = "op-upsert-fail"
        val attempt = 1
        val fingerprint = "d".repeat(64)
        val inputJson = """{"stepId":"sh","params":{},"runId":"run-1","attempt":1}"""

        // Phase 1: beginOperation writes RUNNING
        journal.beginOperation(opId, attempt, fingerprint, inputJson, null)

        // Capture started_at
        val startedAtBefore = journal.getStartedAt(opId, attempt)
        assertNotNull(startedAtBefore)

        // Phase 2: append transitions RUNNING → FAILED
        val terminalOp = RerunOperation(
            id = opId,
            fingerprint = Fingerprint(fingerprint),
            input = OperationInput("sh", mapOf(), "run-1", attempt),
            output = null,
            status = OperationStatus.FAILED,
            attempt = attempt,
        )
        journal.append(terminalOp, null)

        // Verify status is FAILED
        val retrieved = journal.get(opId, attempt)
        assertNotNull(retrieved)
        assertEquals(OperationStatus.FAILED, retrieved!!.status)
        // started_at preserved
        val startedAtAfter = journal.getStartedAt(opId, attempt)
        assertEquals(startedAtBefore, startedAtAfter)
        // ended_at is set
        val endedAt = journal.getEndedAt(opId, attempt)
        assertNotNull(endedAt)
    }
}
