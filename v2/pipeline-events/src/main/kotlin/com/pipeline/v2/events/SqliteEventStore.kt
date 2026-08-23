package com.pipeline.v2.events

import java.sql.Connection
import java.sql.DriverManager

/**
 * SQLite-backed event store using JDK 21 stdlib java.sql.
 */
class SqliteEventStore(private val file: String) : EventSink {

    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:$file")

    init {
        connection.createStatement().use { stmt ->
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
    }

    override fun append(event: DomainEvent) {
        connection.prepareStatement(
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
    }

    override fun eventsFor(runId: String): Sequence<DomainEvent> {
        return connection.prepareStatement(
            "SELECT payload FROM events WHERE run_id = ? ORDER BY sequence ASC"
        ).use { ps ->
            ps.setString(1, runId)
            ps.executeQuery().use { rs ->
                generateSequence {
                    if (rs.next()) {
                        val payload = rs.getString(1)
                        JsonEventLog.decode(payload).firstOrNull()
                    } else null
                }
            }
        }
    }
}
