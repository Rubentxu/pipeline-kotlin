package dev.rubentxu.pipeline.v2.events.durable

import java.sql.Connection

/**
 * The store's SCHEMA owner: the tables the durable lanes need, the index that makes a run's
 * sequence unique, and the migrations that bring an older database forward.
 *
 * ## What this owns
 *
 * The SHAPE of the file, and every statement that changes it:
 *
 * - the `events` table;
 * - the `ux_events_run_sequence` uniqueness index, which makes the DATABASE the authority on a
 *   run's sequence instead of the writer's discipline;
 * - the durable operation journal's tables;
 * - the migrations that add the journal's `started_at`, `ended_at` and `run_id` columns, and the
 *   backfill that attributes the rows an older build left unattributed.
 *
 * ## What this is NOT
 *
 * It is not the store's contract: no published surface depends on this object's shape. It is not
 * a migration engine: the migrations here are the ones this store has always run, and they are
 * idempotent, so opening an already-migrated database is a no-op.
 *
 * ## Why the migrations live here and not in the reader
 *
 * A READER cannot migrate anything: its lane opens the file through an engine that refuses every
 * write. So the migrations belong to the WRITER's lane, and this owner is only ever called from
 * it.
 *
 * ## Where the bounded refusal lives
 *
 * The head of an unreadable payload that a refusal quotes back is bounded by
 * [JsonEventLog.STORED_ROW_DETAIL_CHARS], in the code that reads the payload. This owner used to
 * carry a second constant with the same value and the same intent, and it was never read: two
 * constants that name one bound are two places where the bound can change, and the one that was
 * not read was not the one that was honoured.
 */
internal object SqliteEventSchema {

    /**
     * The producer's lane's schema work, on a connection of its own.
     *
     * The connection is opened and closed HERE and not on the writer's persistent one: the schema
     * is created before the writer prepares its insert statement, and the writer's connection is
     * opened before the schema exists. Keeping the two apart is what lets the writer prepare its
     * insert against a table that is already there.
     */
    fun prepare(connections: () -> Connection) {
        val conn = connections()
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
            // The durable sequence authority.
            // The per-run counter is seeded ONCE per instance from
            // MAX(sequence), so two store instances over the same database
            // each start from the same number and then increment
            // independently. Without a uniqueness constraint BOTH inserts
            // succeed and the events table accumulates duplicate sequences,
            // breaking the per-run monotonic invariant that replay and every
            // External observer depend on (observed rows=300 distinct=150).
            // UNIQUE(run_id, sequence) makes the DATABASE the authority: a
            // writer that would duplicate a sequence is rejected by SQLite
            // itself (fail-closed, no silent corruption) instead of
            // appending a second event with a number already in the log.
            // This is deliberately the MINIMAL invariant repair. It does not
            // renumber a colliding event: the single-writer queue already
            // gives a dense 1..N for one instance, and renumbering under
            // contention would open holes. Deciding which writer owns a run
            // is the separate RunExecutionLease design question.
            createSequenceUniquenessIndex(conn)
            // Create durable operation journal tables.
            OperationJournalSchema.create(conn)
            // Migrate operation_journal schema: add started_at + ended_at columns if absent.
            // Idempotent: safe to run on databases written by an older format
            migrateOperationJournalSchema(conn)
            // Backfill run_id from input JSON for pre-existing rows.
            // Idempotent: only updates rows where run_id IS NULL.
            backfillRunId(conn)
        } finally {
            conn.close()
        }
    }

    /**
     * Makes `UNIQUE(run_id, sequence)` the durable authority.
     * Idempotent: `IF NOT EXISTS`, so opening an already-migrated database
     * is a no-op.
     * A database that ALREADY carries duplicate sequences cannot satisfy the
     * constraint, and SQLite would then fail the index creation for an opaque
     * reason at some later moment. That is reported here, with the offending
     * run, instead of leaving a corrupt log that only fails later: an operator
     * gets a named cause and a remediation (drop the database, or re-run the
     * corrupted run) rather than a mystery UNIQUE violation mid-run.
*/
    private fun createSequenceUniquenessIndex(conn: java.sql.Connection) {
        // Cheap existence probe FIRST. The duplicate scan below is a full
        // GROUP BY over the whole events table; running it on every open of an
        // already-migrated database would cost a full scan to learn something
        // the index presence already answers — and a durable event log is
        // exactly the table that grows without bound. One indexed lookup, then
        // a no-op for every subsequent open.
        val alreadyMigrated = conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = 'ux_events_run_sequence'"
            ).use { rs -> rs.next() }
        }
        if (alreadyMigrated) return

        val duplicates = conn.createStatement().use { stmt ->
            stmt.executeQuery(
                """
                SELECT run_id, sequence, COUNT(*) FROM events
                GROUP BY run_id, sequence HAVING COUNT(*) > 1
                ORDER BY run_id, sequence LIMIT 5
                """.trimIndent()
            ).use { rs ->
                buildList {
                    while (rs.next()) {
                        add("runId=${rs.getString(1)} sequence=${rs.getLong(2)} count=${rs.getLong(3)}")
                    }
                }
            }
        }
        check(duplicates.isEmpty()) {
            "durable event log has duplicate (run_id, sequence) pairs, " +
                "so UNIQUE(run_id, sequence) cannot be established: ${duplicates.joinToString("; ")}. " +
                "This database was written by more than one store instance before the " +
                "constraint existed. It cannot be repaired automatically without " +
                "renumbering committed history; start a fresh database or re-run the run."
        }
        conn.createStatement().use { stmt ->
            stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_events_run_sequence ON events(run_id, sequence)")
        }
    }

    /**
     * Idempotent schema migration for operation_journal.
     * Adds started_at INTEGER and ended_at INTEGER columns if they do not already exist.
     * Safe to call on databases created with the schema (before this migration)
*/
    private fun migrateOperationJournalSchema(conn: java.sql.Connection) {
        conn.createStatement().use { stmt ->
            // Check which columns exist in operation_journal
            val existingColumns = mutableSetOf<String>()
            stmt.executeQuery("PRAGMA table_info(operation_journal)").use { rs ->
                while (rs.next()) {
                    existingColumns.add(rs.getString("name"))
                }
            }
            // Idempotent: only ALTER if column is absent
            if (!existingColumns.contains("started_at")) {
                stmt.execute("ALTER TABLE operation_journal ADD COLUMN started_at INTEGER")
            }
            if (!existingColumns.contains("ended_at")) {
                stmt.execute("ALTER TABLE operation_journal ADD COLUMN ended_at INTEGER")
            }
            // Add run_id column and index if absent
            if (!existingColumns.contains("run_id")) {
                stmt.execute("ALTER TABLE operation_journal ADD COLUMN run_id TEXT")
                stmt.execute("CREATE INDEX IF NOT EXISTS operation_journal_run_id_idx ON operation_journal(run_id)")
            }
        }
    }

    /**
     * Backfills run_id from the input JSON.
     *
     * Idempotent: only updates rows where run_id IS NULL.
     * Uses json_extract to pull $.runId from the input JSON blob.
     *
     * ## What this is for, and what it is NOT for
     *
     * This is a NORMALISATION of the rows an older build wrote: that build
     * left a RUNNING row unattributed, so a run that died mid-operation left
     * a row that named no run. Filling the column puts those rows on the
     * index and makes the write lane's own reads cheap.
     *
     * It is NOT what makes the read lane correct. A reader cannot run this
     * — the read lane opens the database through an engine that refuses every
     * write — so the read reads through the attribution that is missing
     * instead (see [SqliteOperationJournalImpl.listForRun]). Before that, the
     * journal's answer about a run's operations depended on WHICH LANE was
     * asking, and a half-finished run looked finished to the lane that could
     * not repair the column.
*/
    private fun backfillRunId(conn: java.sql.Connection) {
        conn.createStatement().use { stmt ->
            stmt.execute(
                "UPDATE operation_journal SET run_id = json_extract(input, '\$.runId') WHERE run_id IS NULL"
            )
        }
    }
}
