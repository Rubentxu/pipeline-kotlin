package dev.rubentxu.pipeline.v2.output.store

/**
 * What this store is allowed to do, in one place.
 *
 * ## Why the four predicates exist
 *
 * The store used to carry the question in FOUR different shapes, spread over four call sites:
 * `check(recovered)`, `check(recovered || !recoveryPermitted)`, `check(recoveryPermitted)` and
 * `if (!recovered && recoveryPermitted)`. Each shape was right where it sat, and together they
 * answered the same question four times — which means a change to the question had to be made four
 * times, and a reader had to compare four spellings to know what the store would do.
 *
 * This type is the question, once. A store is a [Reader] (opened for reading, never reconciles,
 * never writes) or a [Writer] (may reconcile, and writes only once it has), and the four predicates
 * below are derived from that one fact instead of being re-derived at every call site.
 *
 * ## The invariant that makes the table total
 *
 * A reader is never reconciled, so `mayWrite` is false for it BY CONSTRUCTION and not by a flag
 * that could disagree with the role. There is no third state and no half-reconciled reader.
 *
 * | predicate | Reader | Writer, not reconciled | Writer, reconciled |
 * |---|---|---|---|
 * | [mayRecover] | false | true | true |
 * | [mayWrite] | false | false | true |
 * | [mayRead] | true | false | true |
 * | [requiresReconciliationForReads] | false | true | false |
 */
internal sealed interface StoreAuthority {

    /** Whether this store may reconcile durable state at all. */
    val mayRecover: Boolean

    /** Whether this store may append, seal or prune. */
    val mayWrite: Boolean

    /** Whether this store may serve a read. */
    val mayRead: Boolean

    /** Whether a read has to be refused until the store has reconciled. */
    val requiresReconciliationForReads: Boolean

    /**
     * A store opened for WRITING: it may reconcile, and it may write once it has.
     *
     * @param reconciled whether the reconciliation has already happened.
     */
    data class Writer(val reconciled: Boolean) : StoreAuthority {
        override val mayRecover: Boolean = true
        override val mayWrite: Boolean = reconciled
        override val mayRead: Boolean = reconciled
        override val requiresReconciliationForReads: Boolean = !reconciled
    }

    /**
     * A store opened for READING: it never reconciles, so it never writes either.
     *
     * Reconciliation truncates the uncommitted tail of a stream, and an uncommitted tail is exactly
     * what a live writer looks like — so a reader that reconciled could destroy a live writer's
     * bytes. That is why the reader's `mayWrite` is false and not merely unused.
     */
    data object Reader : StoreAuthority {
        override val mayRecover: Boolean = false
        override val mayWrite: Boolean = false
        override val mayRead: Boolean = true
        override val requiresReconciliationForReads: Boolean = false
    }
}

/**
 * The authority of a store opened with [recoveryPermitted] and reconciled or not.
 *
 * [recoveryPermitted] chooses the ROLE and nothing else; [reconciled] says whether a writing store
 * has already run its reconciliation.
 */
internal fun storeAuthority(recoveryPermitted: Boolean, reconciled: Boolean = false): StoreAuthority =
    if (recoveryPermitted) StoreAuthority.Writer(reconciled) else StoreAuthority.Reader
