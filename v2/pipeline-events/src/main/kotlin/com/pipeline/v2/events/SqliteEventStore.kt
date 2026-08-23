package com.pipeline.v2.events

import java.sql.Connection
import java.sql.DriverManager

/**
 * SQLite-backed event store using JDK 21 stdlib java.sql.
 * Each operation opens a fresh connection that auto-commits and closes,
 * ensuring data is immediately visible to subsequent readers.
 */
class SqliteEventStore(private val file: String) : EventSink, AutoCloseable {

    private fun freshConnection(): Connection =
        DriverManager.getConnection("jdbc:sqlite:$file")

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
                // Use DELETE journal mode: committed writes go directly to the
                // main db file and are immediately visible to other connections.
                stmt.execute("PRAGMA journal_mode = DELETE")
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

    override fun close() {
        // No-op: we use fresh connections per operation.
        // This class is AutoCloseable for use-with-resources patterns.
    }
}
