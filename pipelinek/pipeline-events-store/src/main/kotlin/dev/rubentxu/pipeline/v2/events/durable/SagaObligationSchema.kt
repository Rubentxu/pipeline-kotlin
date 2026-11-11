package dev.rubentxu.pipeline.v2.events.durable

import java.sql.Connection

/**
 * The SCHEMA owner of the table the obligations live in.
 *
 * ## What this owns
 *
 * The shape of that table, the index over the runs, and the two columns a
 * database written by an older build does not carry.
 *
 * ## What this is NOT
 *
 * It is not the store: the store reads and writes the rows, and this owner
 * only changes the shape of the file. It is not a migration engine: every
 * statement here is additive and idempotent, so running it on a file that
 * already carries the shape changes nothing.
 *
 * ## Why it is an owner of its own
 *
 * Because the PREPARATION of a substrate creates the events schema and the
 * table of the promises in ONE transaction, and a transaction cannot span two
 * connections. The store that used to own these statements was built over a
 * pool of connections and could only create the table on one of them, in a
 * transaction of its own. A preparation that must leave no half state needs
 * both shapes in the SAME transaction, and that needs an owner that takes the
 * connection as an argument.
 *
 * The journal's tables have an owner of this shape already
 * ([OperationJournalSchema]), and the two owners are read by the same reader.
 */
internal object SagaObligationSchema {

    /**
     * The name of the table the obligations live in.
     *
     * It is named here because the reader asks the file for a table BY NAME,
     * and the name it asks for is the name the refusal gives the operator.
     */
    const val TABLE: String = "saga_obligation"

    /**
     * Creates the table and its index if they are not there yet.
     *
     * Additive and idempotent: a database written by an older build does not
     * have the table, and one written by this build already does. The
     * statements never touch an existing table, so they cannot disturb a run
     * that is already in flight.
     *
     * The two columns a database written by an older build does not carry are
     * added here and not in the store: a column that is missing is a fact
     * about the FILE, and the file is what this owner changes.
     */
    fun create(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(CREATE_OBLIGATION_TABLE)
            statement.execute(CREATE_OBLIGATION_RUN_INDEX)
        }
        addOwnerColumnIfMissing(connection)
        addBodyDigestColumnIfMissing(connection)
    }

    /**
     * Adds the column that says which plugin armed each promise, when it is not there yet.
     *
     * A database written by an older build has the table without that column, and the rows it
     * holds were armed by nobody in particular: they keep working, and they simply cannot prove
     * a conflict against anyone. The column is nullable and the statement only ever adds it, so
     * a run that is already in flight is not disturbed.
     */
    private fun addOwnerColumnIfMissing(connection: Connection) {
        val hasColumn = connection.metaData.getColumns(null, null, TABLE, "owner_plugin_id")
            .use { rs -> rs.next() }
        if (hasColumn) return
        connection.createStatement().use { statement ->
            statement.execute(ADD_OWNER_COLUMN)
        }
    }

    /**
     * Adds the column that says which body each promise is paid with, when it is not there yet.
     *
     * A database written by an older build holds promises that were armed before the engine
     * measured bodies, so they name no body: they keep working, and they simply cannot prove a
     * divergence against anybody. The column is nullable and the statement only ever adds it, so
     * a run that is already in flight is not disturbed.
     */
    private fun addBodyDigestColumnIfMissing(connection: Connection) {
        val hasColumn = connection.metaData.getColumns(null, null, TABLE, "body_digest")
            .use { rs -> rs.next() }
        if (hasColumn) return
        connection.createStatement().use { statement ->
            statement.execute(ADD_BODY_DIGEST_COLUMN)
        }
    }

    private const val CREATE_OBLIGATION_TABLE = """
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
            owner_plugin_id TEXT,
            body_digest TEXT,
            PRIMARY KEY (run_id, saga_id, action_id, compensation_id)
        )
    """

    /**
     * The column that says which plugin armed each promise.
     *
     * It is added to a table that already exists, never to a fresh one: the create statement
     * already declares it, and this one is only for the databases that were written before
     * the provenance was kept.
     */
    private const val ADD_OWNER_COLUMN = """
        ALTER TABLE saga_obligation ADD COLUMN owner_plugin_id TEXT
    """

    /**
     * The column that says which body each promise is paid with.
     *
     * Same story as the owner's column: it is added to a table that already exists, and the
     * promises in those rows simply do not name a body, so they keep working and cannot
     * prove a divergence against anybody.
     */
    private const val ADD_BODY_DIGEST_COLUMN = """
        ALTER TABLE saga_obligation ADD COLUMN body_digest TEXT
    """

    private const val CREATE_OBLIGATION_RUN_INDEX = """
        CREATE INDEX IF NOT EXISTS ix_saga_obligation_run
        ON saga_obligation (run_id, status)
    """
}
