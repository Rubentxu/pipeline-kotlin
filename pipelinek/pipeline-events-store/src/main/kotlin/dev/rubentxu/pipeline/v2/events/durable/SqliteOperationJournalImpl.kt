package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.DurableOperation
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationOutput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.runtime.durable.OperationJournal
import kotlinx.serialization.json.Json
import kotlin.concurrent.withLock

/**
 * SQLite WAL-backed implementation of [OperationJournal].
 *
 * ## The connections
 *
 * The journal does not open a connection per operation. It BORROWS one from the durable lane's
 * pools and gives it back, so the cost of the door — which is where almost all of the cost of an
 * operation lived — is paid once per connection and not once per operation.
 *
 * The writes are borrowed from the pool of one connection, inside the lock that fences them, and
 * the reads from the pool of several, so that a read never waits for a writer and a writer never
 * waits for a read.
 *
 * ## Concurrency
 *
 * All write operations ([append], [beginOperation]) are synchronized to ensure serialized
 * writes to the WAL-enabled SQLite database. This is the single-writer
 * guarantee required by SQLite WAL mode.
 *
 * The lock is the FENCE, and it is not bypassed: it is taken BEFORE the connection is borrowed,
 * so two instances over the same file still serialize their writes.
 *
 * A connection is never shared between two operations at the same time: the pool lends one to an
 * operation and takes it back when the operation ends.
 *
 * ## Serialization
 *
 * [OperationInput] and [OperationOutput] are serialized to JSON using
 * kotlinx-serialization with canonical encoding. The `kind` discriminator
 * field is used to reconstruct the correct [DurableOperation] variant
 * on deserialization.
 *
 * @param connections the pools the journal borrows its connections from: the one it writes
 * through and the one it reads through.
 * @param json The [Json] instance to use for serialization. Defaults to
 *             canonical encoding with [Json][kotlinx.serialization.json.Json].
 *
 */
internal class SqliteOperationJournalImpl(
    private val connections: DurableConnections,
    private val clock: Clock,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
    private val dbPath: String,
) : OperationJournal {

    /**
     * Appends a durable operation to the journal.
     *
     * Uses UPSERT semantics: if a row already exists for (op_id, attempt),
     * the row is updated in place (RUNNING → terminal). This enables the
     * two-phase journal pattern (beginOperation + append) where a RUNNING
     * row is transitioned to SUCCEEDED/FAILED atomically.
     *
     * The append is serialized via [DbLock] to avoid WAL contention.
     *
     * @param op The durable operation to journal.
     * @param deadlineMs The deadline timestamp in milliseconds, or null if no timeout.
     */
    override fun append(op: DurableOperation, deadlineMs: Long?) {
        DbLock.forPath(dbPath).withLock {
            connections.writes.withConnection { conn ->
                val inputJson = json.encodeToString(op.input)
                val outputJson = op.output?.let { json.encodeToString(it) }
                val kind = OperationKindCodec.encode(op)
                val now = clock.now().toEpochMilli()
                val isTerminal = op.status.isTerminal
                val endedAtVal: Long? = if (isTerminal) now else null

                conn.prepareStatement(
                    """
                    INSERT INTO operation_journal
                        (op_id, fingerprint, status, kind, attempt, input, output, started_at, created_at, updated_at, ended_at, deadline_ms, run_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(op_id, attempt) DO UPDATE SET
                        status = excluded.status,
                        output = excluded.output,
                        updated_at = excluded.updated_at,
                        ended_at = excluded.ended_at,
                        run_id = excluded.run_id
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, op.id)
                    ps.setString(2, op.fingerprint.hex)
                    ps.setString(3, op.status.name)
                    ps.setString(4, kind)
                    ps.setInt(5, op.attempt)
                    ps.setString(6, inputJson)
                    ps.setString(7, outputJson)
                    ps.setLong(8, now)   // started_at — preserved on conflict by omission from UPDATE SET
                    ps.setLong(9, now)   // created_at — preserved on conflict
                    ps.setLong(10, now)  // updated_at
                    if (endedAtVal != null) {
                        ps.setLong(11, endedAtVal)
                    } else {
                        ps.setNull(11, java.sql.Types.BIGINT)
                    }
                    if (deadlineMs != null) {
                        ps.setLong(12, deadlineMs)
                    } else {
                        ps.setNull(12, java.sql.Types.BIGINT)
                    }
                    // Populate run_id column from op.input.runId
                    ps.setString(13, op.input.runId)
                    ps.executeUpdate()
                }
            }
        }
    }

    /**
     * Begins a durable operation by writing a RUNNING row.
     *
     * ## Caller-Passes-Formatted
     *
     * The caller is responsible for formatting the [opId] string before calling this method.
     * The journal uses [opId] exactly as provided — no derivation, no suffix appending.
     * This ensures the journal is never responsible for formatting operation IDs.
     *
     * ## The row carries its run from the start
     *
     * The run id is taken from the operation's input and written with the row,
     * so the row that says "this operation is RUNNING" is a row that says
     * WHICH RUN is still in flight. See [OperationJournal.beginOperation] for
     * why that attribution cannot wait for the operation to terminate.
     *
     * @throws IllegalStateException if a row already exists for (opId, attempt).
     */
    override fun beginOperation(
        opId: String,
        attempt: Int,
        fingerprint: String,
        inputJson: String,
        deadlineMs: Long?,
    ) {
        DbLock.forPath(dbPath).withLock {
            connections.writes.withConnection { conn ->
                // Check for duplicate
                conn.prepareStatement(
                    "SELECT 1 FROM operation_journal WHERE op_id = ? AND attempt = ?"
                ).use { ps ->
                    ps.setString(1, opId)
                    ps.setInt(2, attempt)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) {
                            error("Operation $opId attempt $attempt already journaled")
                        }
                    }
                }

                // The attribution comes from the operation's own input, which
                // is where the run id was written down in the first place.
                // A malformed input fails here, loudly, instead of producing a
                // row that belongs to no run.
                val runId = json.decodeFromString(OperationInput.serializer(), inputJson).runId

                val now = clock.now().toEpochMilli()
                conn.prepareStatement(
                    """
                    INSERT INTO operation_journal
                        (op_id, fingerprint, status, kind, attempt, input, output, started_at, created_at, updated_at, deadline_ms, run_id)
                    VALUES (?, ?, 'RUNNING', 'RERUN', ?, ?, NULL, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, opId)
                    ps.setString(2, fingerprint)
                    ps.setInt(3, attempt)
                    ps.setString(4, inputJson)
                    ps.setLong(5, now)
                    ps.setLong(6, now)
                    ps.setLong(7, now)
                    if (deadlineMs != null) {
                        ps.setLong(8, deadlineMs)
                    } else {
                        ps.setNull(8, java.sql.Types.BIGINT)
                    }
                    ps.setString(9, runId)
                    ps.executeUpdate()
                }
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
    override fun get(opId: String): DurableOperation? = connections.reads.withConnection { conn ->
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
                if (rs.next()) readOperation(rs) else null
            }
        }
    }

    /**
     * Retrieves a journaled operation by its [opId] and [attempt].
     *
     * @param opId The operation identifier.
     * @param attempt The 1-based attempt number.
     * @return The [DurableOperation] if found, or `null` if no entry exists.
     */
    override fun get(opId: String, attempt: Int): DurableOperation? = connections.reads.withConnection { conn ->
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
                if (rs.next()) readOperation(rs) else null
            }
        }
    }

    /**
     * Lists all journaled operations for a given [runId], ordered by [created_at] ascending.
     *
     * ## The read does not depend on a repair
     *
     * The run id is read from the column when the column has it, and from the
     * operation's own input when it does not. The second form exists for the
     * rows an OLDER build wrote: that build left the RUNNING row unattributed
     * and only filled the column when the operation terminated, so a run that
     * died mid-operation left a row with no attribution at all.
     *
     * A reader cannot repair those rows — the read lane opens the database
     * through an engine that refuses every write — so a read that only
     * believed the column would report a half-finished run as FINISHED, and
     * the answer would depend on which lane asked. Reading through the
     * attribution that is missing makes the answer the same for both.
     *
     * The indexed column still serves the query; the second term only adds the
     * unattributed rows to it.
     *
     * @param runId The run identifier.
     * @return A [List] of [DurableOperation] in execution order.
     */
    override fun listForRun(runId: String): List<DurableOperation> = connections.reads.withConnection { conn ->
        val results = mutableListOf<DurableOperation>()
        conn.prepareStatement(
            """
            SELECT j.op_id, j.fingerprint, j.status, j.kind, j.attempt, j.input, j.output
            FROM operation_journal j
            WHERE j.run_id = ?
               OR (j.run_id IS NULL AND json_extract(j.input, '$.runId') = ?)
            ORDER BY j.created_at ASC
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, runId)
            ps.setString(2, runId)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    results.add(readOperation(rs))
                }
            }
        }
        results
    }

    /**
     * Retrieves the journaled deadline timestamp (epoch ms) for a given operation attempt.
     *
     * @param opId The operation identifier.
     * @param attempt The 1-based attempt number.
     * @return The deadline timestamp in milliseconds, or `null` if not set or not found.
     */
    override fun getDeadlineMs(opId: String, attempt: Int): Long? = connections.reads.withConnection { conn ->
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
                if (!rs.next()) {
                    null
                } else {
                    val value = rs.getLong(1)
                    if (rs.wasNull()) null else value
                }
            }
        }
    }

    /**
     * Retrieves the ended_at timestamp (epoch ms) for a given operation attempt.
     *
     * @param opId The operation identifier.
     * @param attempt The 1-based attempt number.
     * @return The ended_at timestamp in milliseconds, or `null` if not set or not found.
     */
    override fun getEndedAt(opId: String, attempt: Int): Long? = connections.reads.withConnection { conn ->
        conn.prepareStatement(
            """
            SELECT ended_at
            FROM operation_journal
            WHERE op_id = ? AND attempt = ?
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, opId)
            ps.setInt(2, attempt)
            ps.executeQuery().use { rs ->
                if (!rs.next()) {
                    null
                } else {
                    val value = rs.getLong(1)
                    if (rs.wasNull()) null else value
                }
            }
        }
    }

    /**
     * Retrieves the started_at timestamp (epoch ms) for a given operation attempt.
     *
     * @param opId The operation identifier.
     * @param attempt The 1-based attempt number.
     * @return The started_at timestamp in milliseconds, or `null` if not set or not found.
     */
    override fun getStartedAt(opId: String, attempt: Int): Long? = connections.reads.withConnection { conn ->
        conn.prepareStatement(
            """
            SELECT started_at
            FROM operation_journal
            WHERE op_id = ? AND attempt = ?
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, opId)
            ps.setInt(2, attempt)
            ps.executeQuery().use { rs ->
                if (!rs.next()) {
                    null
                } else {
                    val value = rs.getLong(1)
                    if (rs.wasNull()) null else value
                }
            }
        }
    }

    /**
     * The operation a row names.
     *
     * The columns are read BY NAME. The reading used to be by POSITION, and
     * that made the answer depend on three separate `SELECT` lists agreeing on
     * the same order: a column added to one of them would shift every index
     * and the row would be read as something it is not — the `kind` read as
     * the `attempt`, for instance. Reading by name cannot drift, and it
     * changes neither the wire nor the schema.
     *
     * The `kind` decides which kind of operation the row is, and that decision
     * belongs to [OperationKindCodec]: a word this build does not know is
     * refused there, named, instead of being read as the nearest kind it does
     * know.
     */
    private fun readOperation(rs: java.sql.ResultSet): DurableOperation {
        val operationId = rs.getString("op_id")
        val fingerprint = Fingerprint(rs.getString("fingerprint"))
        val status = OperationStatus.valueOf(rs.getString("status"))
        val kind = rs.getString("kind")
        val attempt = rs.getInt("attempt")
        val inputJson = rs.getString("input")
        val outputJson = rs.getString("output")

        val input = json.decodeFromString<OperationInput>(inputJson)
        val output = outputJson?.let { json.decodeFromString<OperationOutput>(it) }

        return OperationKindCodec.decode(
            kind = kind,
            operationId = operationId,
            fingerprint = fingerprint,
            input = input,
            output = output,
            status = status,
            attempt = attempt,
        )
    }
}
