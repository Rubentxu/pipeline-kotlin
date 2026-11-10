package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.runtime.durable.OperationJournal
import dev.rubentxu.pipeline.v2.runtime.durable.ReplayCursorStore
import dev.rubentxu.pipeline.v2.runtime.durable.SagaObligationStore
import java.nio.file.Path

/**
 * The durable stores of a run: the journal, the replay cursor, the Saga obligations and the
 * lease, all over ONE database.
 *
 * ## Why they are built together and not one by one
 *
 * These four stores are not four independent things. They live in the same SQLite database, they
 * are reached through the same connection factory, and the lease is fenced by the same epoch the
 * journal writes. A caller that built them one by one could — and did — wire them four different
 * ways: with a different codec configuration, with a lease directory spelled by hand, and with a
 * journal opened on a path that no other store knew about. None of those wirings was wrong enough
 * to fail a test, and each of them was a place where the run's durable state could disagree with
 * itself.
 *
 * So there is ONE constructor, and it is this one. Everything the four stores share — the
 * connection factory, the database path, the clock, the lease directory — is decided here, once,
 * and a caller that needs a store asks this object for it.
 *
 * ## What this object does NOT decide
 *
 * It does not decide whether the stores may be opened, and it does not reconcile anything: it
 * opens and hands over. The lease is ACQUIRED elsewhere, with the fencing rules the lease's own
 * decider states, and a reader that only wants to look asks for the observation half.
 */
data class DurableStores(
    /** The record of what the run executed. */
    val journal: OperationJournal,

    /** Where the run resumes, and how far it got. */
    val cursors: ReplayCursorStore,

    /** The promises a Saga owes, and whether they were kept. */
    val sagaObligations: SagaObligationStore,

    /**
     * The exclusivity over the run: who may publish, and who is alive.
     *
     * It is handed over with its CONCRETE type because the run's scope has to close it: the
     * lease holds an OS lock, and the store that took that lock is the only thing that knows
     * how to give it back. The port itself does not say "closeable" — the observation half of
     * the lease must never be closed by a reader — so the closable half is named here, at the
     * one place a caller acquires it.
     */
    val lease: FileBackedRunExecutionLeaseStore,
) {
    companion object {

        /**
         * The directory the lease keeps its lock files in, under the run's control root.
         *
         * It is named here because it is a fact about the layout of the control root and not
         * about the lease: two callers that spelled it differently would keep two leases, and
         * the second one would find the run free.
         */
        private const val LEASE_DIRECTORY = "leases"

        /**
         * Open the durable stores of a run over [eventStore]'s database.
         *
         * @param eventStore the store whose database holds the run's durable state.
         * @param controlRoot where the run's control state lives; the lease is kept under it.
         * @param clock the clock the stores stamp their writes with.
         */
        fun open(
            eventStore: SqliteEventStore,
            controlRoot: Path,
            clock: Clock,
        ): DurableStores {
            val dbPath = eventStore.databasePath()
            // The SAME pools for the three stores that write and read in this database: the
            // journal, the cursor and the Saga obligations. One pool means one write connection
            // and one set of reading doors, so the cost of the door is paid once per connection
            // and not once per operation — and it means the stores of a run cannot disagree about
            // how the file is reached.
            val connections = eventStore.durableConnections
            return DurableStores(
                journal = SqliteOperationJournalImpl(
                    connections = connections,
                    clock = clock,
                    dbPath = dbPath,
                ),
                cursors = SqliteReplayCursorStoreImpl(connections, clock),
                sagaObligations = SqliteSagaObligationStore(
                    connections = connections,
                    dbPath = dbPath,
                    // The LANE of the substrate decides whether the schema may be created: a
                    // reader of a run's durable state asks the file what is there instead of
                    // writing what is missing.
                    mayWrite = eventStore.mayWrite,
                ),
                lease = FileBackedRunExecutionLeaseStore(controlRoot.resolve(LEASE_DIRECTORY)),
            )
        }
    }
}
