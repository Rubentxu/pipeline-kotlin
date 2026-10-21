package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.domain.saga.CompensationBodyDigest
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager

/**
 * The body a promise is paid with, kept by the DURABLE store.
 *
 * The file is real on purpose: what is under test is what SURVIVES — what a later process reads
 * when it comes to undo the effect — and an in-memory double would only assert the code's own
 * idea of that.
 */
class SagaPromisedBodyStoreTest {

    @TempDir
    lateinit var tempDir: Path

    private fun dbPath(): String = tempDir.resolve("run.sqlite").toString()

    private fun store(): SqliteSagaObligationStore =
        SqliteSagaObligationStore(
            connectionFactory = { SqliteConnectionFactory.open(dbPath()) },
            dbPath = dbPath(),
        )

    private fun row(bodyDigest: CompensationBodyDigest? = BODY) = SagaObligationRow(
        runId = "run-1",
        sagaId = SagaId.of("checkout"),
        actionId = SagaActionId.of("reserve-stock"),
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
        ownerPluginId = "example.saga",
        bodyDigest = bodyDigest,
    )

    private fun keyOf() = CompensationKey(
        sagaId = SagaId.of("checkout"),
        actionId = SagaActionId.of("reserve-stock"),
        compensationId = CompensationId.of("release-stock"),
    )

    @Test
    fun `the promise remembers the body it is paid with`() {
        val store = store()

        store.arm(row(bodyDigest = BODY))

        assertEquals(BODY, store.find("run-1", keyOf())?.bodyDigest)
    }

    @Test
    fun `a promise that names no body remembers nothing`() {
        val store = store()

        store.arm(row(bodyDigest = null))

        assertNull(store.find("run-1", keyOf())?.bodyDigest)
    }

    @Test
    fun `the body of the promise survives the promise moving`() {
        val store = store()
        store.arm(row(bodyDigest = BODY))

        val outcome = store.updateOutcome(
            SagaObligationUpdate(
                runId = "run-1",
                key = keyOf(),
                status = CompensationObligationStatus.EFFECT_CONFIRMED,
                receiptRef = null,
                updatedAtMs = 2_000L,
                leaseEpoch = 1L,
                ownerPluginId = "example.saga",
            ),
        )

        assertInstanceOf<SagaUpdatePersistence.Updated>(outcome)
        // The promise does not change while it travels: the body it named is still the body
        // it named.
        assertEquals(BODY, store.find("run-1", keyOf())?.bodyDigest)
    }

    @Test
    fun `a database written before the digests existed keeps working`() {
        legacySchema()

        val store = store()
        store.arm(row(bodyDigest = BODY))

        val stored = store.find("run-1", keyOf())
        assertNotNull(stored)
        // The column was added, so this build can write the body it measured.
        assertEquals(BODY, stored?.bodyDigest)
        assertTrue(columns().contains("body_digest"))
    }

    @Test
    fun `a reader can read a database written before the digests existed`() {
        // The read-only reader cannot add a column, so a database written before the digests
        // existed is the case it has to survive. The promise it holds names no body, and that
        // is what it says.
        legacySchema()
        insertLegacyRow()

        val read = SqliteSagaObligationReader(dbPath()).read("run-1")

        val state = assertInstanceOf<SagaInspectionRead.Read>(read)
        assertEquals(1, state.rows.size)
        assertNull(state.rows.single().bodyDigest)
    }

    /**
     * A table written by a build that knew nothing about bodies.
     */
    private fun legacySchema() {
        DriverManager.getConnection("jdbc:sqlite:${dbPath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS events (
                        run_id TEXT NOT NULL,
                        sequence INTEGER NOT NULL,
                        kind TEXT NOT NULL,
                        payload TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS saga_obligation (
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
    }

    private fun insertLegacyRow() {
        DriverManager.getConnection("jdbc:sqlite:${dbPath()}").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO saga_obligation (
                    run_id, saga_id, action_id, compensation_id, intent_version, plan_fingerprint,
                    resource_ref, compensation_kind, status, forward_op_id, compensation_op_id,
                    lease_epoch, created_at_ms, updated_at_ms, receipt_ref
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, "run-1")
                statement.setString(2, "checkout")
                statement.setString(3, "reserve-stock")
                statement.setString(4, "release-stock")
                statement.setInt(5, SagaIntentContract.CURRENT_VERSION)
                statement.setString(6, "a".repeat(64))
                statement.setString(7, "stock/reservation-42")
                statement.setString(8, CompensationKind.RESTORE.name)
                statement.setString(9, CompensationObligationStatus.ARMED.name)
                statement.setString(10, "run-1|stage-0|step-3")
                statement.setString(11, "saga-v1|compensate|release-stock|run-1|stage-0|step-3")
                statement.setLong(12, 1L)
                statement.setLong(13, 1_000L)
                statement.setLong(14, 1_000L)
                statement.setNull(15, java.sql.Types.VARCHAR)
                statement.executeUpdate()
            }
        }
    }

    private fun columns(): List<String> =
        DriverManager.getConnection("jdbc:sqlite:${dbPath()}").use { connection ->
            connection.metaData.getColumns(null, null, "saga_obligation", null).use { rs ->
                buildList {
                    while (rs.next()) add(rs.getString("COLUMN_NAME"))
                }
            }
        }

    private companion object {
        val BODY = CompensationBodyDigest("a".repeat(64))
    }
}
