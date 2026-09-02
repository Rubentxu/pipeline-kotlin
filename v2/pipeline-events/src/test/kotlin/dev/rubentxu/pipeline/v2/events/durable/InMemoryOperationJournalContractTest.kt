package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationOutput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Contract tests for [InMemoryOperationJournal], mirroring the SQLite
 * journal contract so store choice cannot change semantics.
 */
class InMemoryOperationJournalContractTest {

    private class FixedClock(private var current: Instant) : Clock {
        override fun now(): Instant = current
        fun tickBySeconds(seconds: Long) { current = current.plusSeconds(seconds) }
    }

    private fun journal(): Pair<InMemoryOperationJournal, FixedClock> {
        val clock = FixedClock(Instant.parse("2026-09-02T10:00:00Z"))
        return InMemoryOperationJournal(clock, Json { ignoreUnknownKeys = true; encodeDefaults = true }) to clock
    }

    private fun operation(
        id: String,
        runId: String = "run-1",
        attempt: Int = 1,
        status: OperationStatus = OperationStatus.SUCCEEDED,
        output: String? = "result",
    ): RerunOperation = RerunOperation(
        id = id,
        fingerprint = Fingerprint("a".repeat(64)),
        input = OperationInput("step", mapOf("k" to JsonPrimitive("v")), runId, attempt),
        output = output?.let { OperationOutput(JsonPrimitive(it), 100L, System.currentTimeMillis()) },
        status = status,
        attempt = attempt,
    )

    @Test
    fun `append then get returns the same operation`() {
        val (journal, _) = journal()

        journal.append(operation("op-1"))

        val retrieved = journal.get("op-1")
        assertNotNull(retrieved)
        assertEquals("op-1", retrieved!!.id)
        assertEquals(OperationStatus.SUCCEEDED, retrieved.status)
    }

    @Test
    fun `get with no entry returns null`() {
        val (journal, _) = journal()

        assertNull(journal.get("missing"))
    }

    @Test
    fun `get by opId returns the highest attempt`() {
        val (journal, _) = journal()

        journal.append(operation("op-1", attempt = 1))
        journal.append(operation("op-1", attempt = 2))

        assertEquals(2, journal.get("op-1")!!.attempt)
        assertEquals(1, journal.get("op-1", 1)!!.attempt)
        assertEquals(2, journal.get("op-1", 2)!!.attempt)
        assertNull(journal.get("op-1", 3))
    }

    @Test
    fun `beginOperation writes a RUNNING row visible via get`() {
        val (journal, _) = journal()

        journal.beginOperation("op-b", 1, fingerprint = "b".repeat(64), inputJson = "{\"stepId\":\"sh\",\"params\":{},\"runId\":\"run-1\",\"attempt\":1}", deadlineMs = null)

        val running = journal.get("op-b")
        assertNotNull(running)
        assertEquals(OperationStatus.RUNNING, running!!.status)
        assertNull(running.output)
    }

    @Test
    fun `beginOperation on duplicate opId and attempt throws IllegalStateException`() {
        val (journal, _) = journal()

        journal.beginOperation("op-b", 1, fingerprint = "b".repeat(64), inputJson = "{\"stepId\":\"sh\",\"params\":{},\"runId\":\"run-1\",\"attempt\":1}", deadlineMs = null)

        assertThrows(IllegalStateException::class.java) {
            journal.beginOperation("op-b", 1, fingerprint = "b".repeat(64), inputJson = "{\"stepId\":\"sh\",\"params\":{},\"runId\":\"run-1\",\"attempt\":1}", deadlineMs = null)
        }
    }

    @Test
    fun `append after beginOperation updates the RUNNING row preserving startedAt`() {
        val (journal, clock) = journal()

        journal.beginOperation("op-b", 1, fingerprint = "b".repeat(64), inputJson = "{\"stepId\":\"sh\",\"params\":{},\"runId\":\"run-1\",\"attempt\":1}", deadlineMs = 5000L)
        val startedAt = journal.getStartedAt("op-b", 1)
        clock.tickBySeconds(10)
        journal.append(operation("op-b", status = OperationStatus.SUCCEEDED), deadlineMs = null)

        assertEquals(startedAt, journal.getStartedAt("op-b", 1), "startedAt must be preserved on conflict")
        assertEquals(OperationStatus.SUCCEEDED, journal.get("op-b")!!.status)
        assertNotNull(journal.getEndedAt("op-b", 1))
        assertEquals(5000L, journal.getDeadlineMs("op-b", 1), "deadline must be preserved on conflict")
    }

    @Test
    fun `terminal append stamps endedAt while non-terminal does not`() {
        val (journal, _) = journal()

        journal.append(operation("op-ok", status = OperationStatus.SUCCEEDED))
        journal.append(operation("op-running", status = OperationStatus.RUNNING, output = null))

        assertNotNull(journal.getEndedAt("op-ok", 1))
        assertNull(journal.getEndedAt("op-running", 1))
    }

    @Test
    fun `listForRun filters by runId and orders by createdAt`() {
        val (journal, clock) = journal()

        journal.append(operation("op-a", runId = "run-1"))
        clock.tickBySeconds(1)
        journal.append(operation("op-b", runId = "run-2"))
        clock.tickBySeconds(1)
        journal.append(operation("op-c", runId = "run-1"))

        val run1 = journal.listForRun("run-1")
        assertEquals(listOf("op-a", "op-c"), run1.map { it.id })
        assertEquals(0, journal.listForRun("run-unknown").size)
    }

    @Test
    fun `getters with no row return null`() {
        val (journal, _) = journal()

        assertNull(journal.getDeadlineMs("missing", 1))
        assertNull(journal.getEndedAt("missing", 1))
        assertNull(journal.getStartedAt("missing", 1))
    }
}
