package com.pipeline.v2.events

import com.pipeline.v2.events.durable.OperationJournalSchema
import com.pipeline.v2.events.durable.SqliteConnectionFactory
import java.sql.Connection

/**
 * SQLite-backed event store using JDK 21 stdlib java.sql.
 * Each operation opens a fresh connection that auto-commits and closes,
 * ensuring data is immediately visible to subsequent readers.
 *
 * ## M2-R1 Variants
 * This store supports all M2-R1 event variants in addition to M1-R3 variants:
 * - [AgentResolved][com.pipeline.v2.events.AgentResolved]
 * - [ParallelBranchStarted][com.pipeline.v2.events.ParallelBranchStarted]
 * - [ParallelBranchFinished][com.pipeline.v2.events.ParallelBranchFinished]
 * - [RetryAttemptStarted][com.pipeline.v2.events.RetryAttemptStarted]
 * - [RetryAttemptFinished][com.pipeline.v2.events.RetryAttemptFinished]
 * - [TimeoutScheduled][com.pipeline.v2.events.TimeoutScheduled]
 *
 * New variants are decoded via the [JsonEventLog][com.pipeline.v2.events.JsonEventLog]
 * `kind` discriminator — no schema migration required.
 *
 * ## M3-R1 Extension
 * This store also creates the [operation_journal][com.pipeline.v2.events.durable.OperationJournalSchema]
 * and [replay_cursor][com.pipeline.v2.events.durable.OperationJournalSchema] tables
 * via [SqliteConnectionFactory] with WAL mode enabled.
 */
class SqliteEventStore(private val file: String) : EventSink, AutoCloseable {

    private fun freshConnection(): Connection =
        SqliteConnectionFactory.open(file)

    private fun withConnection(block: (Connection) -> Unit) {
        val conn = freshConnection()
        try {
            block(conn)
        } finally {
            conn.close()
        }
    }

    init {
        val conn = freshConnection()
        try {
            conn.createStatement().use { stmt ->
                stmt.execute("PRAGMA journal_mode = WAL")
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS events (
                        event_id TEXT NOT NULL,
                        run_id TEXT NOT NULL,
                        sequence INTEGER NOT NULL,
                        kind TEXT NOT NULL,
                        occurred_at TEXT NOT NULL,
                        payload TEXT NOT NULL
                    )
                    """.trimIndent()
                )
            }
            // Create durable operation journal tables.
            OperationJournalSchema.create(conn)
        } finally {
            conn.close()
        }
    }

    override fun append(event: DomainEvent) {
        val conn = freshConnection()
        try {
            conn.prepareStatement(
                "INSERT INTO events (event_id, run_id, sequence, kind, occurred_at, payload) VALUES (?, ?, ?, ?, ?, ?)"
            ).use { ps ->
                ps.setString(1, event.eventId)
                ps.setString(2, event.runId)
                ps.setLong(3, event.sequence)
                ps.setString(4, event.kind)
                ps.setString(5, event.occurredAt.toString())
                ps.setString(6, JsonEventLog.encode(listOf(event)))
                ps.executeUpdate()
            }
        } finally {
            conn.close()
        }
    }

    override fun eventsFor(runId: String): Sequence<DomainEvent> {
        val conn = freshConnection()
        val results = mutableListOf<DomainEvent>()
        try {
            conn.prepareStatement(
                "SELECT payload FROM events WHERE run_id = ? ORDER BY sequence ASC"
            ).use { ps ->
                ps.setString(1, runId)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val payload = rs.getString(1)
                        JsonEventLog.decode(payload).firstOrNull()?.let { results.add(it) }
                    }
                }
            }
        } finally {
            conn.close()
        }
        return results.asSequence()
    }

    /**
     * Closes this store.
     *
     * This is a no-op because each operation uses a fresh connection that
     * auto-commits and closes immediately. Keeping a long-lived connection
     * open would not improve performance for this use pattern.
     *
     * This class implements [AutoCloseable] to support use-with-resources
     * (`use { }`) patterns, but callers do not need to invoke this method
     * for correct operation.
     */
    override fun close() {
        // No-op: we use fresh connections per operation.
    }

    /**
     * Exposes the underlying connection factory for use by [OperationJournal]
     * and [ReplayCursorStore].
     *
     * This is intentionally internal — it is only used within the durable
     * execution subsystem that shares the same SQLite database file.
     */
    fun underlyingConnectionFactory(): () -> Connection = { freshConnection() }
}
