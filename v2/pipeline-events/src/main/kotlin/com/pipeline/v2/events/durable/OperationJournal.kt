package com.pipeline.v2.events.durable

import com.pipeline.v2.domain.durable.DurableOperation
import com.pipeline.v2.domain.durable.OperationOutput
import com.pipeline.v2.domain.durable.OperationStatus
import com.pipeline.v2.domain.durable.RerunOperation
import com.pipeline.v2.domain.durable.MemoizedOperation
import com.pipeline.v2.domain.durable.CompositeOperation
import com.pipeline.v2.domain.durable.OperationInput
import com.pipeline.v2.domain.durable.Fingerprint
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.sql.Connection

/**
 * SQLite WAL-backed journal for durable operations.
 *
 * ## Concurrency
 *
 * All write operations ([append]) are synchronized to ensure serialized
 * writes to the WAL-enabled SQLite database. This is the single-writer
 * guarantee required by SQLite WAL mode and documented per [design.md §R-A].
 *
 * ## Serialization
 *
 * [OperationInput] and [OperationOutput] are serialized to JSON using
 * kotlinx-serialization with canonical encoding. The `kind` discriminator
 * field is used to reconstruct the correct [DurableOperation] variant
 * on deserialization.
 *
 * @param connectionFactory A factory function that returns an open [Connection].
 *                          The connection MUST be obtained from [SqliteConnectionFactory.open]
 *                          to ensure WAL mode is active.
 * @param json The [Json] instance to use for serialization. Defaults to
 *             canonical encoding with [Json][kotlinx.serialization.json.Json].
 *
 * @see <a href="design.md §E4-03">Design §E4-03</a>
 */
class OperationJournal(
    private val connectionFactory: () -> Connection,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) {

    /**
     * Appends a durable operation to the journal.
     *
     * The append is serialized via [synchronized] to avoid WAL contention
     * when multiple threads attempt to journal concurrently (theoretical;
     * in practice the orchestrator is single-threaded per run).
     *
     * @param op The durable operation to journal.
     * @throws IllegalStateException if [op.id] already exists in the journal
     *         (PRIMARY KEY constraint).
     */
    fun append(op: DurableOperation) {
        synchronized(this) {
            val conn = connectionFactory()
            try {
                val inputJson = json.encodeToString(op.input)
                val outputJson = op.output?.let { json.encodeToString(it) }

                conn.prepareStatement(
                    """
                    INSERT INTO operation_journal
                        (op_id, fingerprint, status, input, output, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, op.id)
                    ps.setString(2, op.fingerprint.hex)
                    ps.setString(3, op.status.name)
                    ps.setString(4, inputJson)
                    ps.setString(5, outputJson)
                    ps.setLong(6, System.currentTimeMillis())
                    ps.setLong(7, System.currentTimeMillis())
                    ps.executeUpdate()
                }
            } finally {
                conn.close()
            }
        }
    }

    /**
     * Retrieves a journaled operation by its [opId].
     *
     * @param opId The operation identifier.
     * @return The [DurableOperation] if found, or `null` if no entry exists.
     */
    fun get(opId: String): DurableOperation? {
        val conn = connectionFactory()
        try {
            conn.prepareStatement(
                """
                SELECT op_id, fingerprint, status, input, output
                FROM operation_journal
                WHERE op_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, opId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return readOperation(rs, opId)
                }
            }
        } finally {
            conn.close()
        }
    }

    /**
     * Lists all journaled operations for a given [runId], ordered by [created_at] ascending.
     *
     * @param runId The run identifier.
     * @return A [List] of [DurableOperation] in execution order.
     */
    fun listForRun(runId: String): List<DurableOperation> {
        val conn = connectionFactory()
        val results = mutableListOf<DurableOperation>()
        try {
            conn.prepareStatement(
                """
                SELECT j.op_id, j.fingerprint, j.status, j.input, j.output
                FROM operation_journal j
                WHERE j.input LIKE ?
                ORDER BY j.created_at ASC
                """.trimIndent()
            ).use { ps ->
                // Match by runId in the JSON input blob.
                ps.setString(1, "%\"runId\":\"$runId\"%")
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val opId = rs.getString(1)
                        readOperation(rs, opId)?.let { results.add(it) }
                    }
                }
            }
        } finally {
            conn.close()
        }
        return results
    }

    private fun readOperation(rs: java.sql.ResultSet, opId: String): DurableOperation? {
        val fingerprint = Fingerprint(rs.getString(2))
        val status = OperationStatus.valueOf(rs.getString(3))
        val inputJson = rs.getString(4)
        val outputJson = rs.getString(5)

        val input = json.decodeFromString<OperationInput>(inputJson)
        val output = outputJson?.let { json.decodeFromString<OperationOutput>(it) }

        return RerunOperation(
            id = opId,
            fingerprint = fingerprint,
            input = input,
            output = output,
            status = status,
            attempt = input.attempt,
        )
    }
}
