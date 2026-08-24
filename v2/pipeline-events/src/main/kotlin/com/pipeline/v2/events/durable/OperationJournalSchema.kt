package com.pipeline.v2.events.durable

/**
 * SQL DDL constants for the operation journal and replay cursor tables.
 *
 * These tables are created alongside the existing `events` table in the same
 * SQLite database (`pipeline.db`) via [SqliteConnectionFactory].
 *
 * ## operation_journal
 *
 * Stores one row per durable operation execution, keyed by [op_id].
 * The [fingerprint] column stores the SHA-256 fingerprint at the time of execution.
 * [input] and [output] store the canonical JSON serialization of the operation's
 * input and output payloads.
 *
 * ## replay_cursor
 *
 * Stores the replay cursor for each run, keyed by [run_id].
 * The cursor tracks the last successfully journaled operation ([last_op_id])
 * and the [stage_index] at which execution should resume.
 *
 * @see <a href="design.md §E4-03">Design §E4-03</a>
 */
object OperationJournalSchema {

    const val CREATE_OPERATION_JOURNAL = """
        CREATE TABLE IF NOT EXISTS operation_journal (
            op_id       TEXT    NOT NULL,
            fingerprint TEXT    NOT NULL,
            status      TEXT    NOT NULL,
            kind       TEXT    NOT NULL DEFAULT 'RERUN',
            attempt    INTEGER NOT NULL DEFAULT 1,
            input       TEXT    NOT NULL,
            output      TEXT,
            started_at  INTEGER,
            ended_at    INTEGER,
            created_at  INTEGER NOT NULL,
            updated_at  INTEGER NOT NULL,
            deadline_ms INTEGER,
            run_id      TEXT,
            PRIMARY KEY (op_id, attempt)
        )
    """

    const val CREATE_OPERATION_JOURNAL_RUN_ID_IDX = """
        CREATE INDEX IF NOT EXISTS operation_journal_run_id_idx ON operation_journal(run_id)
    """

    const val CREATE_REPLAY_CURSOR = """
        CREATE TABLE IF NOT EXISTS replay_cursor (
            run_id      TEXT    NOT NULL PRIMARY KEY,
            last_op_id  TEXT,
            stage_index INTEGER NOT NULL,
            saved_at    INTEGER NOT NULL
        )
    """

    /**
     * Creates both tables if they do not already exist.
     *
     * @param connection An open SQLite connection (must be in WAL mode).
     */
    fun create(connection: java.sql.Connection) {
        connection.createStatement().use { stmt ->
            stmt.execute(CREATE_OPERATION_JOURNAL)
            stmt.execute(CREATE_REPLAY_CURSOR)
        }
    }
}
