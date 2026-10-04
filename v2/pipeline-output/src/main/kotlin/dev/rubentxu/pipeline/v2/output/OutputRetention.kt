package dev.rubentxu.pipeline.v2.output

/**
 * B2 — retention: who may discard output, and when.
 *
 * ## Why this needed its own vocabulary
 *
 * The store could delete a stream directory long before anyone wrote down that it was allowed to.
 * Nothing said who owned the decision, so two answers were available and neither was wrong: the
 * plane could keep everything forever, or anything that could see a stream id could delete it.
 * Neither is a policy. The first is a leak, the second is a deletion authority without a reason
 * attached — and a deletion with no reason cannot be reviewed, cannot be reproduced, and cannot be
 * told apart from a bug.
 *
 * So retention is stated as three closed vocabularies rather than a flag:
 *
 * | type | question it answers | who decides |
 * |---|---|---|
 * | [RunLifecycle] | what happened to the run? | the runtime, which executed it |
 * | [RetainUntil] | when may its output go? | declared once, at configuration |
 * | [OutputPruneIntent] | may THIS deletion happen, and why? | the policy, applied to the lifecycle |
 *
 * ## The division that is the whole point
 *
 * [SegmentOutputStore] can delete bytes. It cannot decide that they are deletable: it never learns
 * whether a run is still running, and inventing that knowledge would make the store the authority
 * on run lifecycle, which is a second authority on a fact the runtime already owns.
 *
 * So a deletion names its reason in its type. [OutputPruneIntent] has no case for a run that is
 * still running, and none for "clean up old output" — "a run reached a terminal state" and "an
 * operator asked" are the only two things that can authorise a delete, and each carries the run
 * and the author. **Deleting output of a live run is not a mistake this API can express.**
 *
 * The journal is deliberately not given any of this. It may reference output — the console reader
 * resolves a stream from a run and an operation — but it has no method that deletes. A component
 * that can only cite output cannot prune it.
 *
 * ## Why no outcome travels with the lifecycle
 *
 * An earlier draft carried the run's outcome as a `String` on [RunLifecycle.Terminal] and on
 * [OutputPruneIntent.RunReachedTerminalState], and that was a second authority in disguise. The
 * typed outcome already has exactly one owner — `RunOutcome` in `:pipeline-domain`, a module this
 * one deliberately does not depend on, so no fitness would have caught the drift — and a retention
 * decision does not need it. `RunTerminalPlus` asks exactly one question, "has the run ended?",
 * and the answer is the same for `Success`, `Unstable` and `Failure`: the console served its
 * purpose.
 *
 * A caller that wants to know HOW a run ended asks the event plane or the journal, which own that
 * fact and already expose it typed. Re-spelling it here as an unvalidated `String` would have made
 * the prune report a second place where "unstable" and "failed" could each be spelled, and the two
 * would drift. So the lifecycle says what retention needs, and nothing more.
 */
sealed interface RunLifecycle {
    /**
     * The run reached a terminal state. The particular outcome is deliberately absent: retention
     * treats every terminal state alike, and the engine is the authority on which one it was.
     */
    data object Terminal : RunLifecycle

    /** The run is still executing. Nothing about its output may be discarded. */
    data object StillRunning : RunLifecycle
}

/**
 * When a run's output may be discarded.
 *
 * A policy, not a switch: it is asked what to do and it answers with an intent or with nothing.
 * [Forever] answers with nothing **by construction** rather than by a check a caller can forget.
 */
sealed interface RetainUntil {

    /** Keep until the run is terminal, then release. The default for console output. */
    data object RunTerminalPlus : RetainUntil

    /** Keep regardless of the run's state; only an explicit operator release removes it. */
    data object ExplicitReleaseOnly : RetainUntil

    /** Never discard. For transcripts under a retention hold. */
    data object Forever : RetainUntil

    /**
     * The decision this policy makes about one run, or `null` when the output must be kept.
     *
     * Returning `null` is the whole of [Forever] and of [ExplicitReleaseOnly] while a run is live.
     * Neither is a special case in the caller, because the caller has no branch to take: a `null`
     * answer is not an error and not a partial result, it is the answer.
     */
    fun authorize(runId: String, lifecycle: RunLifecycle): OutputPruneIntent? = when (this) {
        RunTerminalPlus -> when (lifecycle) {
            RunLifecycle.Terminal -> OutputPruneIntent.RunReachedTerminalState(runId)
            RunLifecycle.StillRunning -> null
        }
        // Both remaining policies keep a live run, and differ only once it ends — which is the
        // point: an operator release stays available after the run finishes, so a post-mortem
        // transcript can be dropped on request without a window where it is undeletable.
        ExplicitReleaseOnly, Forever -> null
    }
}

/**
 * A deletion that has been authorised, naming its reason.
 *
 * The constructor of a case is the permission. There is no `prune(runId, force = true)`, because a
 * boolean is exactly the shape that lets a caller talk itself into a deletion it did not earn.
 */
sealed interface OutputPruneIntent {
    /** The run this intent speaks about, so the store never re-reads a sealed case to find it. */
    val runId: String

    /**
     * The run finished; its console output has served its purpose.
     *
     * It carries no outcome, and the reason is in [RunLifecycle]: a prune authorisation is not a
     * report on how the run went, so naming the outcome here would only create a second spelling
     * of a fact the engine already owns.
     */
    data class RunReachedTerminalState(override val runId: String) : OutputPruneIntent

    /** A named operator asked for this output to go, before or after the run ended. */
    data class OperatorReleased(override val runId: String, val requestedBy: String) : OutputPruneIntent
}

/** What a prune pass actually did. Zero counts are a legitimate, meaningful answer. */
data class OutputPruneReport(
    val streamsRemoved: Int,
    val bytesReleased: Long,
    /**
     * How many of the run's streams were **not** removed.
     *
     * Non-zero means the filesystem refused a deletion that was authorised. It is reported rather
     * than swallowed because a caller confirming "my release reached everything" has to be able to
     * tell "there was nothing there" from "something resisted", and a removal count of zero cannot
     * distinguish those.
     */
    val streamsRetained: Int,
)

/**
 * The **retention** side of the Output Plane.
 *
 * Separate from [OutputAppendPort] and [OutputReadPort] because the authority is different in
 * kind: those two are about bytes, and this one is about permission to destroy them.
 */
interface OutputRetentionPort {

    /** Whether this run has any output at all. Cheap; does not enumerate. */
    fun hasOutputFor(runId: String): Boolean

    /**
     * Discards a run's output under [intent], and reports what happened.
     *
     * Total: releasing a run that has no output is a no-op with a report, never an error. The
     * refusal surface belongs to reads, where a wrong answer would be a wrong answer about bytes;
     * here a wrong answer is a missing deletion, which the caller can see.
     */
    fun prune(intent: OutputPruneIntent): OutputPruneReport
}
