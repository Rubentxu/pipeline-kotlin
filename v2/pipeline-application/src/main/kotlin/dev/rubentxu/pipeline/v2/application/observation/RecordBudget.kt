package dev.rubentxu.pipeline.v2.application.observation

/**
 * How many SELECTED records a read may emit.
 *
 * ## Why this is not a dimension of [ObservationQuery]
 *
 * A query answers "does this record belong in the answer", and the answer is the same however many
 * times it is asked. A limit answers "have I emitted enough yet", which is different every time — it
 * is a budget being spent, not a predicate being evaluated. Putting it in the query would have meant
 * either a counter inside a value that is compared for equality and passed around as a filter, or a
 * mutable object shared by every emitter of the read. Both make "how much have I shown so far"
 * depend on who asked, which is exactly the kind of hidden state that makes a limit unreproducible.
 *
 * ## It counts SELECTED records, not scanned ones
 *
 * `observe RUN --stage build --limit 5` means *five things matching `--stage build`*. A budget spent
 * on records the query rejected would make the flag a second, invisible filter: the same five records
 * would appear for a query that matched five and for one that matched five out of ten thousand. That
 * is the reading `kubectl logs --limit` gives, and it is the only one a caller can predict without
 * knowing how many records the run happened to commit.
 *
 * ## It composes with `--follow`, and the follow then stops for a named reason
 *
 * A follower that has emitted its budget has not finished the run, and the consumer did not ask it
 * to stop. Reporting either of the two existing reasons would be a lie in a direction that matters:
 * [dev.rubentxu.pipeline.v2.application.FollowOutcome.StoppedByConsumer] says "I stopped when asked",
 * and `ReachedRunFinish` says "the run committed a terminal fact". Neither happened, so the budget
 * carries its own case.
 */
sealed interface RecordBudget {

    /** No `--limit` was given: every selected record is emitted. */
    data object All : RecordBudget

    /**
     * At most [records] selected records, and at least one.
     *
     * Zero is refused rather than clamped: `--limit 0` is not "show me nothing", it is a caller that
     * computed a budget it did not expect to be empty, and answering with silence would hide the
     * mistake behind an empty screen.
     */
    data class UpTo(val records: Int) : RecordBudget {
        init {
            require(records > 0) { "a limit of $records records shows nothing; use All instead" }
        }
    }

    /**
     * Whether the record at position [emitted] of the answer may still be shown.
     *
     * [emitted] counts what has already gone out, so the first record asks with `0`.
     */
    fun allows(emitted: Int): Boolean = when (this) {
        All -> true
        is UpTo -> emitted < records
    }

    /** The prefix of [items] this budget pays for. Bounded only when it is bounded. */
    fun <T> bounded(items: List<T>): List<T> = when (this) {
        All -> items
        is UpTo -> items.take(records)
    }
}