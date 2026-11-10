package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.events.RunStarted
import java.nio.file.Path
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The durability barrier of ONE fact.
 *
 * ## The guarantee under trial
 *
 * That the door that says "this fact is durable" fails CLOSED for the fact
 * that is not, and only for that: a store that has refused the fact of
 * another producer has not refused this one.
 *
 * ## The defect this instrument pursues
 *
 * The store accumulates the refusals since it opened, on purpose: a refusal
 * that a later barrier forgot would be a refusal the producer never heard.
 * But that accumulation turns the barrier into a signal about the LIFE of
 * the store and not about MY fact: as soon as one insert is refused, every
 * later barrier throws, and a long-lived service that shares the store with
 * other producers sees its own writes fail over somebody else's refusal.
 *
 * ## Why it matters in the product
 *
 * A cancellation writes the terminal of a run and then asks whether it is
 * durable. With the wrong signal, an unrelated refusal made the
 * cancellation answer "the storage failed" over a run it had just closed,
 * and the next cancellation of the same run answered "already cancelled":
 * two contradictory answers about the same run.
 *
 * ## Why this is an INTEGRATION test
 *
 * Because the refusal is produced by the engine: the row is refused because
 * its primary key already exists, and the store knows it by the
 * constraint of the database. A double would have to re-implement the
 * constraint to be worth anything.
 */
class SqliteEventStoreDurabilityTest {

    @TempDir
    lateinit var tempDir: Path

    private val runId = "v2:run:durable"
    private val startedAt = Instant.parse("2026-01-01T00:00:00Z")

    private fun started(eventId: String) = RunStarted(
        eventId = eventId,
        runId = runId,
        sequence = 0L,
        occurredAt = startedAt,
        scriptPath = "pipeline.kts",
    )

    @Test
    fun `the barrier of one fact does not fail for the refusal of another`() {
        val store = SqliteEventStore(tempDir.resolve("pipeline.db").toString(), EventStoreMode.Writable)
        try {
            // Two facts in the SAME place of the run: the database refuses
            // the second one, and the refusal stays in the store's history.
            store.append(started("event-1").copy(sequence = 7L))
            store.append(started("event-2").copy(sequence = 7L))
            // A fact of its own, which the store accepted.
            store.append(started("event-3").copy(sequence = 8L))

            val refused = assertThrows(EventAppendRefusedException::class.java) {
                store.makeDurable("event-2")
            }
            assertTrue(
                refused.refusals.any { it.event.eventId == "event-2" },
                "the barrier of a refused fact names THAT fact: ${refused.refusals}",
            )

            store.makeDurable("event-3")
        } finally {
            store.close()
        }
    }

    @Test
    fun `the close does not repeat a refusal that a barrier already reported`() {
        val store = SqliteEventStore(tempDir.resolve("pipeline.db").toString(), EventStoreMode.Writable)
        try {
            store.append(started("event-1").copy(sequence = 7L))
            store.append(started("event-2").copy(sequence = 7L))

            // The barrier hears the refusal, and the producer that asked is
            // the one that owns the fact.
            assertThrows(EventAppendRefusedException::class.java) {
                store.makeDurable("event-2")
            }
        } finally {
            // The close is not a producer's question: the refusal was heard
            // at the barrier, and a close that says it again would turn a
            // refusal that was already handled into a failure of the
            // shutdown —and a store that has refused one append could never
            // be closed cleanly.
            store.close()
        }
    }

    @Test
    fun `the close reports a refusal that no barrier reported`() {
        val store = SqliteEventStore(tempDir.resolve("pipeline.db").toString(), EventStoreMode.Writable)
        store.append(started("event-1").copy(sequence = 7L))
        store.append(started("event-2").copy(sequence = 7L))

        // Nobody asked for a barrier, so nobody heard the refusal. The
        // close is the last chance to say it, and a refusal that nobody
        // heard is a fact that vanished.
        val refused = assertThrows(EventAppendRefusedException::class.java) {
            store.close()
        }
        assertTrue(
            refused.refusals.any { it.event.eventId == "event-2" },
            "the close names the fact that was refused: ${refused.refusals}",
        )
    }
}
