package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.domain.saga.CompensationId
import dev.rubentxu.pipeline.v2.domain.saga.CompensationKey
import dev.rubentxu.pipeline.v2.domain.saga.CompensationKind
import dev.rubentxu.pipeline.v2.domain.saga.CompensationObligationStatus
import dev.rubentxu.pipeline.v2.domain.saga.ResourceRef
import dev.rubentxu.pipeline.v2.domain.saga.SagaActionId
import dev.rubentxu.pipeline.v2.domain.saga.SagaId
import dev.rubentxu.pipeline.v2.domain.saga.SagaIntentContract
import dev.rubentxu.pipeline.v2.domain.saga.SagaPlanFingerprint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager

/**
 * A promise belongs to the plugin that armed it, and the DURABLE store is where that is
 * enforced.
 *
 * The file is real on purpose: what is under test is what SURVIVES a write that should not
 * have happened, and an in-memory double would only assert the code's own idea of that.
 */
class SagaPromiseOwnershipStoreTest {

    @TempDir
    lateinit var tempDir: Path

    private fun dbPath(): String = tempDir.resolve("run.sqlite").toString()

    private fun store(): SqliteSagaObligationStore =
        SqliteSagaObligationStore(
            connectionFactory = { SqliteConnectionFactory.open(dbPath()) },
            dbPath = dbPath(),
        )

    private fun row(
        actionId: String = "reserve-stock",
        sagaId: SagaId = SagaId.of("checkout"),
        ownerPluginId: String? = "example.saga",
    ) = SagaObligationRow(
        runId = "run-1",
        sagaId = sagaId,
        actionId = SagaActionId.of(actionId),
        compensationId = CompensationId.of("release-stock"),
        intentVersion = SagaIntentContract.CURRENT_VERSION,
        planFingerprint = SagaPlanFingerprint("a".repeat(64)),
        resourceRef = ResourceRef.of("stock/reservation-42"),
        kind = CompensationKind.RESTORE,
        status = CompensationObligationStatus.ARMED,
        forwardOpId = "run-1|stage-0|step-3",
        compensationOpId = "saga-v1|compensate|release-stock|run-1|stage-0|step-3",
        leaseEpoch = 1L,
        createdAtMs = 1_000L,
        updatedAtMs = 1_000L,
        receiptRef = null,
        ownerPluginId = ownerPluginId,
    )

    private fun keyOf(actionId: String, sagaId: SagaId = SagaId.of("checkout")) = CompensationKey(
        sagaId = sagaId,
        actionId = SagaActionId.of(actionId),
        compensationId = CompensationId.of("release-stock"),
    )

    @Test
    fun `the promise remembers the plugin that armed it`() {
        val store = store()

        store.arm(row(ownerPluginId = "example.saga"))

        val stored = store.find("run-1", keyOf("reserve-stock"))
        assertEquals("example.saga", stored?.ownerPluginId)
    }

    @Test
    fun `a plugin that does not own the promise cannot move it`() {
        val store = store()
        store.arm(row(ownerPluginId = "example.saga"))

        val outcome = store.updateOutcome(
            SagaObligationUpdate(
                runId = "run-1",
                key = keyOf("reserve-stock"),
                status = CompensationObligationStatus.EFFECT_CONFIRMED,
                receiptRef = null,
                updatedAtMs = 2_000L,
                leaseEpoch = 1L,
                ownerPluginId = "other.saga",
            ),
        )

        val refusal = assertInstanceOf<SagaUpdatePersistence.ForeignOwner>(outcome)
        assertEquals("example.saga", refusal.row.ownerPluginId)
        // The promise is untouched: the refusal is not a write that failed halfway.
        assertEquals(
            CompensationObligationStatus.ARMED,
            store.find("run-1", keyOf("reserve-stock"))?.status,
        )
    }

    @Test
    fun `the plugin that owns the promise can move it`() {
        val store = store()
        store.arm(row(ownerPluginId = "example.saga"))

        val outcome = store.updateOutcome(
            SagaObligationUpdate(
                runId = "run-1",
                key = keyOf("reserve-stock"),
                status = CompensationObligationStatus.EFFECT_CONFIRMED,
                receiptRef = null,
                updatedAtMs = 2_000L,
                leaseEpoch = 1L,
                ownerPluginId = "example.saga",
            ),
        )

        assertInstanceOf<SagaUpdatePersistence.Updated>(outcome)
    }

    @Test
    fun `closing a Saga stops when one of its promises belongs to another plugin`() {
        val store = store()
        store.arm(row(actionId = "reserve-stock", ownerPluginId = "example.saga"))
        store.arm(row(actionId = "charge-card", ownerPluginId = "other.saga"))

        val outcome = store.moveAll(
            runId = "run-1",
            sagaId = SagaId.of("checkout"),
            from = CompensationObligationStatus.ARMED,
            to = CompensationObligationStatus.EFFECT_KEPT,
            updatedAtMs = 3_000L,
            leaseEpoch = 1L,
            ownerPluginId = "example.saga",
        )

        val refusal = assertInstanceOf<SagaBulkMove.ForeignOwner>(outcome)
        assertEquals("other.saga", refusal.row.ownerPluginId)
        // The bulk move is all or nothing: neither promise moved.
        assertEquals(
            CompensationObligationStatus.ARMED,
            store.find("run-1", keyOf("reserve-stock"))?.status,
        )
        assertEquals(
            CompensationObligationStatus.ARMED,
            store.find("run-1", keyOf("charge-card"))?.status,
        )
    }

    @Test
    fun `a promise whose owner was never recorded can still be moved`() {
        val store = store()
        store.arm(row(ownerPluginId = null))

        val outcome = store.updateOutcome(
            SagaObligationUpdate(
                runId = "run-1",
                key = keyOf("reserve-stock"),
                status = CompensationObligationStatus.EFFECT_CONFIRMED,
                receiptRef = null,
                updatedAtMs = 2_000L,
                leaseEpoch = 1L,
                ownerPluginId = "other.saga",
            ),
        )

        assertInstanceOf<SagaUpdatePersistence.Updated>(outcome)
    }

    @Test
    fun `a database written before the provenance existed keeps working`() {
        // A table written by an older build: the promises are there and the column is not.
        DriverManager.getConnection("jdbc:sqlite:${dbPath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE saga_obligation (
                        run_id TEXT NOT NULL,
                        saga_id TEXT NOT NULL,
                        action_id TEXT NOT NULL,
                        compensation_id TEXT NOT NULL,
                        intent_version INTEGER NOT NULL,
                        plan_fingerprint TEXT NOT NULL,
                        resource_ref TEXT NOT NULL,
                        compensation_kind TEXT NOT NULL,
                        status TEXT NOT NULL,
                        forward_op_id TEXT NOT NULL,
                        compensation_op_id TEXT NOT NULL,
                        lease_epoch INTEGER NOT NULL,
                        created_at_ms INTEGER NOT NULL,
                        updated_at_ms INTEGER NOT NULL,
                        receipt_ref TEXT,
                        PRIMARY KEY (run_id, saga_id, action_id, compensation_id)
                    )
                    """.trimIndent(),
                )
            }
        }

        val store = store()
        store.arm(row(ownerPluginId = "example.saga"))

        val stored = store.find("run-1", keyOf("reserve-stock"))
        assertNotNull(stored)
        // The column was added, so this build knows who owns the promise it just wrote.
        assertEquals("example.saga", stored?.ownerPluginId)
        assertTrue(columns().contains("owner_plugin_id"))
    }

    private fun columns(): List<String> =
        DriverManager.getConnection("jdbc:sqlite:${dbPath()}").use { connection ->
            connection.metaData.getColumns(null, null, "saga_obligation", null).use { rs ->
                buildList {
                    while (rs.next()) add(rs.getString("COLUMN_NAME"))
                }
            }
        }
}
