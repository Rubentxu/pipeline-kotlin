package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.StepCapability

/**
 * Capability key under which the engine supplies the [InputDecisions] port
 * (RP6-B / WU-092).
 *
 * ## Why this is a capability and not a wider `StepHandlerContext`
 *
 * The handler needs exactly one thing from the outside: ask a question and wait
 * for an answer. It does not need the control directory, the run identity, the
 * clock or a coordinator. Widening `StepHandlerContext` would push the answer
 * mechanism onto every Step handler in the system to serve one of them.
 *
 * The same reasoning as [LOCK_COORDINATION_CAPABILITY] (RP6-A): the handler asks
 * for the port, the bridge builds the adapter, and a Step that does not need
 * human input never sees this capability.
 *
 * Deliberately NOT an authorization surface: [InputRequest.submitter] is
 * attribution (SPEC_WU092_INPUT.md §3.5), because a headless runner has no user
 * database to authorize against.
 */
val INPUT_DECISIONS_CAPABILITY: StepCapability = StepCapability("inputDecisions")

/**
 * The port `core.input` talks to. It does not know what answers it: no file, no
 * path, no clock, no journal. Everything it needs arrives in [InputRequest], and
 * everything it reports is one of two closed cases.
 *
 * Implementations MUST be cancellation-cooperative: cancelling the calling
 * coroutine aborts the wait and yields [InputResolution.Denied] with
 * [InputDenialReason.Cancelled] rather than throwing across the kernel boundary.
 */
interface InputDecisions {

    /**
     * Publishes [request] and waits for a human answer.
     *
     * @param waitMillis upper bound on the wait, or `null` for "until the caller's
     *   own budget runs out or the coroutine is cancelled". The bound is computed
     *   by the handler from the author's `timeout` and the enclosing scope budget,
     *   so the port never has to know about either.
     * @return the answer, or a typed reason why there is none.
     */
    suspend fun awaitDecision(request: InputRequest, waitMillis: Long?): InputResolution
}

/** What the pipeline author asked, in the vocabulary the port understands. */
data class InputRequest(
    /** Stable operation identity: the answer is filed under it, so a resumed run finds it. */
    val opId: String,
    val message: String,
    /** Label of the affirmative answer, Jenkins `ok`. */
    val ok: String,
    /** Attribution, not authorization. */
    val submitter: String?,
    /** Author-supplied correlation id, Jenkins `id`. */
    val id: String?,
)

/** Exactly one answer, or exactly one typed reason why there is none. */
sealed interface InputResolution {
    data class Answered(val decision: InputDecision) : InputResolution
    data class Denied(val reason: InputDenialReason) : InputResolution
}
