package com.pipeline.v2.events.durable

import com.pipeline.v2.events.SqliteEventStore
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

    private fun freshJournal(): OperationJournal {
        val dbPath = tempDir.resolve("contract-test.db").toString()
        val eventStore = SqliteEventStore(dbPath)
        return SqliteOperationJournalImpl(eventStore.underlyingConnectionFactory())
    }

    @Test
    fun `append then get returns the same operation`() {
        val journal = freshJournal()
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
        val journal = freshJournal()
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
        val journal = freshJournal()
        assertNull(journal.get("nonexistent-op"))
    }

    @Test
    fun `duplicate append throws exception preserving first entry`() {
        val journal = freshJournal()
        val op = RerunOperation(
            id = "op-dup",
            fingerprint = Fingerprint("a".repeat(64)),
            input = OperationInput("step", mapOf(), "run-1", 1),
            output = null,
            status = OperationStatus.SUCCEEDED,
            attempt = 1,
        )
        journal.append(op)
        assertThrows(Exception::class.java) {
            journal.append(op.copy(status = OperationStatus.FAILED))
        }
        // Original entry is still retrievable
        val retrieved = journal.get("op-dup")
        assertNotNull(retrieved)
        assertEquals(OperationStatus.SUCCEEDED, retrieved!!.status)
    }
}
