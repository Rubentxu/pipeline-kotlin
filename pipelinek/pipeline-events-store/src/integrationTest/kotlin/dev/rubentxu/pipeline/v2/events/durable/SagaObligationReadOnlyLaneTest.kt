package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.saga.CompensationId
import dev.rubentxu.pipeline.v2.domain.saga.CompensationKey
import dev.rubentxu.pipeline.v2.domain.saga.CompensationKind
import dev.rubentxu.pipeline.v2.domain.saga.CompensationObligationStatus
import dev.rubentxu.pipeline.v2.domain.saga.ResourceRef
import dev.rubentxu.pipeline.v2.domain.saga.SagaActionId
import dev.rubentxu.pipeline.v2.domain.saga.SagaId
import dev.rubentxu.pipeline.v2.domain.saga.SagaPlanFingerprint
import dev.rubentxu.pipeline.v2.runtime.durable.SagaObligationRow
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * The Saga obligation store in the READER's lane.
 *
 * ## The guarantee under trial
 *
 * That a store opened over a substrate it may not change creates NOTHING in it, and says what
 * that substrate holds: a database with no table of promises holds no promises, and a store that
 * cannot write refuses to write the table BY NAME.
 *
 * ## The capacity this serves
 *
 * The service of a local operator opens the substrate of a run in the reader's lane when it may
 * not cancel anything. That store used to create its schema on construction, so the composition
 * of the service ended in the engine's refusal ("attempt to write a readonly database") for
 * every substrate that another tool had opened, and the operator was sent to look at the
 * database engine instead of at the table that was missing.
 *
 * ## Why this instrument is an INTEGRATION test
 *
 * The subject is the DATABASE: the real engine, the real file, the real door the engine refuses
 * to write through. Whether a `CREATE TABLE` is a write or a no-op on a read-only connection is
 * a fact about the engine, and a double of the store would answer whatever it was told.
 *
 * ## What this does not freeze
 *
 * The words of the refusal, beyond the table it names, and the order in which the schema of the
 * writer's lane is created.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class SagaObligationReadOnlyLaneTest {

    @TempDir
    lateinit var tempDir: Path

    /**
     * A clock that says the same instant, so the rows this test writes are comparable.
     */
    private class FixedClock : Clock {
        override fun now(): Instant = Instant.parse("2026-01-01T00:00:00Z")
    }

    @Test
    fun `the reader's lane creates nothing and answers that nothing is owed`() {
        val db = tempDir.resolve("substrate.db")
        // A substrate another tool opened: the producer's schema is in it, and the table of the
        // promises is not, because nobody armed a promise there.
        val producer = admitted(db, EventStorePurpose.Produce)
        producer.close()
        assertFalse(
            tableIsThere(db, PROMISE_TABLE),
            "the substrate starts without the table of the promises",
        )

        val reader = admitted(db, EventStorePurpose.Read)
        val stores = DurableStores.open(reader, tempDir.resolve("control"), FixedClock())
        try {
            assertEquals(
                emptyList<SagaObligationRow>(),
                stores.sagaObligations.listForRun("run-1"),
                "a substrate with no table of promises owes nothing",
            )
            assertNull(
                stores.sagaObligations.find("run-1", key()),
                "a substrate with no table of promises holds no promise",
            )
            assertFalse(
                tableIsThere(db, PROMISE_TABLE),
                "the reader's lane created nothing in the substrate it reads",
            )
        } finally {
            reader.close()
        }
    }

    @Test
    fun `the writer's lane creates the table of the promises`() {
        val db = tempDir.resolve("written.db")
        val writer = admitted(db, EventStorePurpose.Produce)

        DurableStores.open(writer, tempDir.resolve("control"), FixedClock())

        assertTrue(
            tableIsThere(db, PROMISE_TABLE),
            "the writer's lane creates the table of the promises",
        )
        writer.close()
    }

    @Test
    fun `the reader's lane refuses to arm a promise and names the table`() {
        val db = tempDir.resolve("substrate.db")
        val producer = admitted(db, EventStorePurpose.Produce)
        producer.close()

        val reader = admitted(db, EventStorePurpose.Read)
        val stores = DurableStores.open(reader, tempDir.resolve("control"), FixedClock())
        try {
            val refusal = assertThrows(
                IllegalStateException::class.java,
                { stores.sagaObligations.arm(row()) },
                "a store that may not change the substrate does not arm promises in it",
            )
            assertTrue(
                refusal.message?.contains(PROMISE_TABLE) == true,
                "the refusal names the table it would have written: ${refusal.message}",
            )
        } finally {
            reader.close()
        }
    }

    /**
     * The store over [db], opened in the lane of [purpose].
     */
    private fun admitted(db: Path, purpose: EventStorePurpose): SqliteEventStore {
        val admission = admitEventStore(db.toString(), purpose)
        return (admission as EventStoreAdmission.Admitted).store
    }

    /**
     * Whether the database at [db] holds a table of that name.
     *
     * The question is asked through the reading door, which is a door the engine refuses to
     * write through: asking costs nothing and changes nothing.
     */
    private fun tableIsThere(db: Path, table: String): Boolean =
        SqliteConnectionFactory.openReadOnly(db.toString()).use { connection ->
            connection.prepareStatement(SELECT_TABLE).use { statement ->
                statement.setString(1, table)
                statement.executeQuery().use { rs -> rs.next() }
            }
        }

    private fun row(): SagaObligationRow = SagaObligationRow(
        runId = "run-1",
        sagaId = SagaId.of("checkout"),
        actionId = SagaActionId.of("action-one"),
        compensationId = CompensationId.of("release-stock"),
        intentVersion = 1,
        planFingerprint = SagaPlanFingerprint("a".repeat(64)),
        resourceRef = ResourceRef.of("stock/reservation"),
        kind = CompensationKind.RESTORE,
        status = CompensationObligationStatus.ARMED,
        forwardOpId = "run-1|stage-0|step-one",
        compensationOpId = "saga-v1|compensate|release-stock|run-1|stage-0|step-one",
        leaseEpoch = 1L,
        createdAtMs = 1_000L,
        updatedAtMs = 1_000L,
        receiptRef = null,
        ownerPluginId = "example.saga",
    )

    private fun key(): CompensationKey = CompensationKey(
        sagaId = SagaId.of("checkout"),
        actionId = SagaActionId.of("action-one"),
        compensationId = CompensationId.of("release-stock"),
    )

    private companion object {

        /**
         * The name of the table the promises live in.
         *
         * It is written here, and not taken from the product, because the fact under trial is
         * about the FILE: a row that read the product's own constant would agree with the
         * product whatever the product did.
         */
        const val PROMISE_TABLE = "saga_obligation"

        const val SELECT_TABLE = """
            SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?
        """
    }
}
