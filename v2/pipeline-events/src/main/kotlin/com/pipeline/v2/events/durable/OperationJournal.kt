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
 * Interface for durable operation journaling.
 *
 * ## M3-R1 → M3-R2 Contract
 *
 * This interface is stable for M3-R2 consumption per [design.md §8].
 * The concrete implementation [SqliteOperationJournalImpl] uses SQLite WAL mode.
 *
 * @see <a href="design.md §E4-03">Design §E4-03</a>
 */
interface OperationJournal {
    /**
     * Appends a durable operation to the journal.
     *
     * @param op The durable operation to journal.
     * @param deadlineMs The deadline timestamp in milliseconds (epoch),
     *                   or null if no timeout is set.
     */
    fun append(op: DurableOperation, deadlineMs: Long? = null)

    /**
     * Retrieves the latest journaled operation by its [opId].
     * Returns the entry with the highest attempt number.
     *
     * @param opId The operation identifier.
     * @return The [DurableOperation] if found, or `null` if no entry exists.
     */
    fun get(opId: String): DurableOperation?

    /**
     * Retrieves a journaled operation by its [opId] and [attempt].
     *
     * @param opId The operation identifier.
     * @param attempt The 1-based attempt number.
     * @return The [DurableOperation] if found, or `null` if no entry exists.
     */
    fun get(opId: String, attempt: Int): DurableOperation?

    /**
     * Lists all journaled operations for a given [runId], ordered by [created_at] ascending.
     *
     * @param runId The run identifier.
     * @return A [List] of [DurableOperation] in execution order.
     */
    fun listForRun(runId: String): List<DurableOperation>

    /**
     * Retrieves the journaled deadline timestamp (epoch ms) for a given operation attempt.
     * Returns null if no deadline was set (timeout = null), or if no journal entry exists.
     *
     * @param opId The operation identifier.
     * @param attempt The 1-based attempt number.
     * @return The deadline timestamp in milliseconds, or `null` if not set or not found.
     */
    fun getDeadlineMs(opId: String, attempt: Int): Long?
}

/**
 * SQLite WAL-backed implementation of [OperationJournal].
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
class SqliteOperationJournalImpl(
    private val connectionFactory: () -> Connection,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) : OperationJournal {

    /**
     * Appends a durable operation to the journal.
     *
     * The append is serialized via [synchronized] to avoid WAL contention
     * when multiple threads attempt to journal concurrently (theoretical;
     * in practice the orchestrator is single-threaded per run).
     *
     * @param op The durable operation to journal.
     * @param deadlineMs The deadline timestamp in milliseconds, or null if no timeout.
     * @throws IllegalStateException if [op.id] already exists in the journal
     *         (PRIMARY KEY constraint).
     */
    override fun append(op: DurableOperation, deadlineMs: Long?) {
        synchronized(this) {
            val conn = connectionFactory()
            try {
                // Check if entry already exists for this (op_id, attempt)
                conn.prepareStatement(
                    "SELECT 1 FROM operation_journal WHERE op_id = ? AND attempt = ?"
                ).use { ps ->
                    ps.setString(1, op.id)
                    ps.setInt(2, op.attempt)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) {
                            throw IllegalStateException(
                                "Operation ${op.id} attempt ${op.attempt} already journaled"
                            )
                        }
                    }
                }

                val inputJson = json.encodeToString(op.input)
                val outputJson = op.output?.let { json.encodeToString(it) }
                val kind = when (op) {
                    is RerunOperation -> "RERUN"
                    is MemoizedOperation -> "MEMOIZED"
                    is CompositeOperation -> "COMPOSITE"
                }

                conn.prepareStatement(
                    """
                    INSERT INTO operation_journal
                        (op_id, fingerprint, status, kind, attempt, input, output, created_at, updated_at, deadline_ms)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, op.id)
                    ps.setString(2, op.fingerprint.hex)
                    ps.setString(3, op.status.name)
                    ps.setString(4, kind)
                    ps.setInt(5, op.attempt)
                    ps.setString(6, inputJson)
                    ps.setString(7, outputJson)
                    ps.setLong(8, System.currentTimeMillis())
                    ps.setLong(9, System.currentTimeMillis())
                    if (deadlineMs != null) {
                        ps.setLong(10, deadlineMs)
                    } else {
                        ps.setNull(10, java.sql.Types.BIGINT)
                    }
                    ps.executeUpdate()
                }
            } finally {
                conn.close()
            }
        }
    }

    /**
     * Retrieves the latest journaled operation by its [opId].
     * Returns the entry with the highest attempt number.
     *
     * @param opId The operation identifier.
     * @return The [DurableOperation] if found, or `null` if no entry exists.
     */
    override fun get(opId: String): DurableOperation? {
        val conn = connectionFactory()
        try {
            conn.prepareStatement(
                """
                SELECT op_id, fingerprint, status, kind, attempt, input, output
                FROM operation_journal
                WHERE op_id = ?
                ORDER BY attempt DESC
                LIMIT 1
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
     * Retrieves a journaled operation by its [opId] and [attempt].
     *
     * @param opId The operation identifier.
     * @param attempt The 1-based attempt number.
     * @return The [DurableOperation] if found, or `null` if no entry exists.
     */
    override fun get(opId: String, attempt: Int): DurableOperation? {
        val conn = connectionFactory()
        try {
            conn.prepareStatement(
                """
                SELECT op_id, fingerprint, status, kind, attempt, input, output
                FROM operation_journal
                WHERE op_id = ? AND attempt = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, opId)
                ps.setInt(2, attempt)
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
    override fun listForRun(runId: String): List<DurableOperation> {
        val conn = connectionFactory()
        val results = mutableListOf<DurableOperation>()
        try {
            conn.prepareStatement(
                """
                SELECT j.op_id, j.fingerprint, j.status, j.kind, j.attempt, j.input, j.output
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

    /**
     * Retrieves the journaled deadline timestamp (epoch ms) for a given operation attempt.
     *
     * @param opId The operation identifier.
     * @param attempt The 1-based attempt number.
     * @return The deadline timestamp in milliseconds, or `null` if not set or not found.
     */
    override fun getDeadlineMs(opId: String, attempt: Int): Long? {
        val conn = connectionFactory()
        try {
            conn.prepareStatement(
                """
                SELECT deadline_ms
                FROM operation_journal
                WHERE op_id = ? AND attempt = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, opId)
                ps.setInt(2, attempt)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    val value = rs.getLong(1)
                    return if (rs.wasNull()) null else value
                }
            }
        } finally {
            conn.close()
        }
    }

    private fun readOperation(rs: java.sql.ResultSet, opId: String): DurableOperation? {
        val fingerprint = Fingerprint(rs.getString(2))
        val status = OperationStatus.valueOf(rs.getString(3))
        val kind = rs.getString(4)
        val attempt = rs.getInt(5)
        val inputJson = rs.getString(6)
        val outputJson = rs.getString(7)

        val input = json.decodeFromString<OperationInput>(inputJson)
        val output = outputJson?.let { json.decodeFromString<OperationOutput>(it) }

        return when (kind) {
            "RERUN" -> RerunOperation(
                id = opId,
                fingerprint = fingerprint,
                input = input,
                output = output,
                status = status,
                attempt = attempt,
            )
            "MEMOIZED" -> MemoizedOperation(
                id = opId,
                fingerprint = fingerprint,
                input = input,
                output = output,
                status = status,
                attempt = attempt,
                cachedOutput = output,
            )
            "COMPOSITE" -> CompositeOperation(
                id = opId,
                fingerprint = fingerprint,
                input = input,
                output = output,
                status = status,
                attempt = attempt,
                subOperations = emptyList(),
            )
            else -> RerunOperation(
                id = opId,
                fingerprint = fingerprint,
                input = input,
                output = output,
                status = status,
                attempt = attempt,
            )
        }
    }
}
