package dev.rubentxu.pipeline.v2.events.durable

/**
 * Whether a LIVE process holds a run's lease right now.
 *
 * ## The question this answers, and the one it does not
 * "Is anyone publishing this run AT THIS MOMENT?" — a fact about the present,
 * which only the OS lock can answer. It is not "who held it?" — that is the
 * durable RECORD's answer, and it survives the death of its owner, so it says
 * "alive" for a process that was killed an hour ago.
 *
 * The two are kept apart on purpose. A reader that wants to know whether a run
 * is still being worked on must not read the record; a caller that wants to
 * know who to blame must not read the lock. Asking the wrong one is how a dead
 * run stays busy forever.
 *
 * ## The three answers
 * - [Held]: a live process holds the lock. The answer is a fact and the caller
 *   must not touch the run.
 * - [Free]: nobody holds the lock. The run is takeable.
 * - [Unverifiable]: the question could not be answered — the lock file is
 *   unreadable, or the platform refused the probe. Never collapsed into
 *   [Free]: "I could not tell" and "nobody holds it" are opposites, and the
 *   second one authorises writing.
 */
sealed interface LeaseLiveness {

    /**
     * A live process holds the lock.
     *
     * [heldBy] is the owner the durable RECORD names. It is a best-effort
     * attribution and not part of the fact: the lock is held even when the
     * record is missing or names somebody else, and a record that names nobody
     * is reported as the store's `unknown-owner` placeholder rather than as a
     * missing name — the same placeholder the acquisition uses, so both
     * answers name the same holder for the same run.
     */
    data class Held(val heldBy: RunOwnerId) : LeaseLiveness

    /** Nobody holds the lock. The run is takeable. */
    data object Free : LeaseLiveness

    /** The question could not be answered, and says why. */
    data class Unverifiable(val reason: String) : LeaseLiveness
}
