package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.CompositeOperation
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * PAR-D P6-9 aggregate journal contract proof (HF0/HF1, in-memory store;
 * the SQLite adapter mirrors the same upsert contract).
 *
 * The parallel aggregate MUST be journaled as a COMPOSITE operation from
 * birth: `append(CompositeOperation(status = RUNNING, ...))` followed by
 * `append(same id, status = terminal)` preserves identity, fingerprint,
 * and kind. Never `beginOperation` (which writes kind=RERUN) followed by
 * a composite append — that would leave a conceptually-composite row
 * stored as RERUN, since the upsert does not update `kind`.
 */
class ParallelAggregateJournalContractTest {

    private class FixedClock(private var current: Instant) : Clock {
        override fun now(): Instant = current
    }

    private fun journal() = InMemoryOperationJournal(
        FixedClock(Instant.parse("2026-09-10T10:00:00Z")),
        Json { ignoreUnknownKeys = true; encodeDefaults = true },
    )

    private fun composite(status: OperationStatus) = CompositeOperation(
        id = "run-x-s0-parallel-control",
        fingerprint = Fingerprint("b".repeat(64)),
        input = OperationInput("core.parallel", mapOf("control" to JsonPrimitive("aggregate")), "run-x", 1),
        output = null,
        status = status,
        attempt = 1,
        subOperations = emptyList(),
    )

    @Test
    fun `P6-9 composite aggregate RUNNING then terminal preserves identity fingerprint and kind`() {
        val journal = journal()

        journal.append(composite(OperationStatus.RUNNING))
        val running = journal.get("run-x-s0-parallel-control")
        assertNotNull(running)
        assertEquals(OperationStatus.RUNNING, running!!.status)

        journal.append(composite(OperationStatus.SUCCEEDED))
        val terminal = journal.get("run-x-s0-parallel-control")
        assertNotNull(terminal)
        assertEquals(OperationStatus.SUCCEEDED, terminal!!.status)
        assertEquals("run-x-s0-parallel-control", terminal.id)
        assertEquals("b".repeat(64), terminal.fingerprint.hex)
        assertTrue(terminal is CompositeOperation, "kind must remain COMPOSITE after the terminal append, was: ${terminal::class.simpleName}")
    }

    @Test
    fun `P6-9 anti-pattern - beginOperation then composite append cannot repair kind (documented trap)`() {
        val journal = journal()

        // beginOperation writes kind=RERUN and is the ONLY allowed row creator for that op.
        journal.beginOperation(
            "run-x-s0-parallel-control",
            1,
            "b".repeat(64),
            """{"stepId":"core.parallel","params":{"control":"aggregate"},"runId":"run-x","attempt":1}""",
            null,
        )
        // A naive append of a COMPOSITE op with the same identity does NOT rewrite kind
        // (upsert only updates status/output/endedAt/runId). This test PINS the trap so
        // the aggregate writer must use the pure append/append pattern above.
        journal.append(composite(OperationStatus.SUCCEEDED))
        val row = journal.get("run-x-s0-parallel-control")
        assertNotNull(row)
        // Identity/fingerprint/status updated; kind still the birth kind (RERUN) in the
        // store. Proven by round-trip: the row does NOT deserialize back to CompositeOperation.
        assertTrue(
            row !is CompositeOperation,
            "upsert unexpectedly repaired kind — update this trap documentation if the journal contract changes",
        )
    }
}
