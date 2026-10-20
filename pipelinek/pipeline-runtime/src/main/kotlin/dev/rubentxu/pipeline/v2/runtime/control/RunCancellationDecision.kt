package dev.rubentxu.pipeline.v2.runtime.control

/**
 * The pure decision behind a run cancellation.
 *
 * Every fact this decider reads is OBSERVED, never chosen: who owns the run
 * right now, whether the run is known at all, whether the run already reached
 * a terminal state, and whether a cancellation of this run was already
 * recorded. The decision is a total function of those facts, with no I/O, no
 * clock and no filesystem, so the rule that decides whether a cancel may
 * proceed is verifiable on its own.
 *
 * The adapter that owns the stores reads the facts and executes the verdict.
 * Keeping the rule here is what stops the stores from growing a second,
 * slightly different opinion about when a cancel is allowed.
 */
object RunCancellationDecision {

    /** The facts a cancel consults before it writes anything. */
    data class Facts(
        /** Whether this instance knows the run at all. */
        val runKnown: Boolean,
        /** Who holds the run's lease right now. */
        val lease: LeaseVerdict,
        /** The run's terminal state, when it already reached one. */
        val terminal: Terminal?,
        /** Whether a cancellation of this run was already recorded. */
        val cancelRecorded: Boolean,
    )

    /**
     * The ownership verdict for the run.
     *
     * [Held] is only ever produced by an acquisition that took the run's
     * exclusive lock, so it means "no other process is publishing this run".
     * [LiveOwner] is the opposite, and it is the fact that a cancel must not
     * cross.
     */
    sealed interface LeaseVerdict {

        /** This process holds the run's lease, exclusively. */
        data object Held : LeaseVerdict

        /** Another process holds the run's lease and is still publishing. */
        data class LiveOwner(
            val ownerId: String,
            val fencingToken: Long,
        ) : LeaseVerdict
    }

    /** The run's terminal state, as the event lane recorded it. */
    data class Terminal(
        val outcome: String,
        val terminalAtMs: Long,
    )

    /** What the facts mean for a cancel. */
    sealed interface Decision {

        /** Nothing stands in the way: the cancel may write the terminal facts. */
        data object Proceed : Decision

        /**
         * A prior cancel already closed the run, so this call is a no-op and not
         * a refusal. Idempotency is the contract: a cancel made N times leaves
         * one terminal state, and the second call says so.
         */
        data class AlreadyCancelled(val terminalAtMs: Long) : Decision

        /** The cancel may not happen, and this names why. */
        data class Refused(val reason: Refusal) : Decision
    }

    /** The closed set of reasons a cancel may be refused for. */
    sealed interface Refusal {

        /** The run is not known to this instance. */
        data object UnknownRun : Refusal

        /** A live process holds the run's lease, so the cancel must not cross it. */
        data class LiveOwnerHeld(
            val ownerId: String,
            val fencingToken: Long,
        ) : Refusal

        /** The run already reached a terminal state that is not a cancellation. */
        data class AlreadyTerminal(val outcome: String) : Refusal
    }

    /**
     * The rule.
     *
     * The order of the cases is the policy, and each one is a distinct
     * property:
     *
     *  1. **A live owner wins over everything.** A process that currently holds
     *     the run's lease is publishing it; a cancel would cross a live
     *     boundary. The refusal names the owner, because "busy" is not an
     *     actionable diagnostic.
     *  2. **An unknown run is refused.** A cancel may not invent a run.
     *  3. **A recorded cancel is a no-op, not a refusal.** The run is closed
     *     and the caller may retry without seeing an error.
     *  4. **A terminal run that was not cancelled is refused**, and the refusal
     *     names the outcome so the caller can tell a moot cancel from a
     *     forbidden one.
     *  5. **Everything else proceeds.** That includes the repair case: a cancel
     *     that recorded its intent and died before writing the terminal fact
     *     leaves a recorded cancel and no terminal state, and the next call
     *     finishes the job instead of declaring the run closed.
     */
    fun decide(facts: Facts): Decision {
        val liveOwner = facts.lease as? LeaseVerdict.LiveOwner
        return when {
            liveOwner != null -> Decision.Refused(
                Refusal.LiveOwnerHeld(
                    ownerId = liveOwner.ownerId,
                    fencingToken = liveOwner.fencingToken,
                ),
            )

            !facts.runKnown -> Decision.Refused(Refusal.UnknownRun)

            facts.terminal != null && facts.cancelRecorded ->
                Decision.AlreadyCancelled(facts.terminal.terminalAtMs)

            facts.terminal != null ->
                Decision.Refused(Refusal.AlreadyTerminal(facts.terminal.outcome))

            else -> Decision.Proceed
        }
    }
}
