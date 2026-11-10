package dev.rubentxu.pipeline.v2.runtime

import dev.rubentxu.pipeline.v2.domain.step.StepCapability

/**
 * Capability key under which the engine supplies the [InputDecisions] port
 * .
 * ## Why this is a capability and not a wider `StepHandlerContext`
 * The handler needs exactly one thing from the outside: ask a question and wait
 * for an answer. It does not need the control directory, the run identity, the
 * clock or a coordinator. Widening `StepHandlerContext` would push the answer
 * mechanism onto every Step handler in the system to serve one of them.
 * The same reasoning as [LOCK_COORDINATION_CAPABILITY]: the handler asks
 * for the port, the bridge builds the adapter, and a Step that does not need
 * human input never sees this capability.
 * Deliberately NOT an authorization surface: [InputRequest.submitter] is
 * attribution only, because a headless runner has no user database to authorize
 * against.
*/
val INPUT_DECISIONS_CAPABILITY: StepCapability = StepCapability("inputDecisions")

/**
 * The port `core.input` talks to. It does not know what answers it: no file, no
 * path, no clock, no journal. Everything it needs arrives in [InputRequest], and
 * everything it reports is one of two closed cases.
 * Implementations MUST be cancellation-cooperative: cancelling the calling
 * coroutine aborts the wait and yields [InputResolution.Denied] with
 * [InputDenialReason.Cancelled] rather than throwing across the kernel boundary.
*/
interface InputDecisions {

    /**
     * Publishes [request] and waits for a human answer.
     * @param waitMillis upper bound on the wait, or `null` for "until the caller's
     *   own budget runs out or the coroutine is cancelled". The bound is computed
     *   by the handler from the author's `timeout` and the enclosing scope budget,
     *   so the port never has to know about either.
     * @return the answer, or a typed reason why there is none.
*/
    suspend fun awaitDecision(request: InputRequest, waitMillis: Long?): InputResolution
}

/**
 * The durable operation an [InputDecisions] adapter is bound to.
 *
 * The engine binds the adapter once per dispatch, so the question is filed under the identity
 * of the operation that asks it — the stage, the step, the branch and the body path — and not
 * under a name the step could build from the little it knows. [opId] is
 * `OpId.format()` of the dispatch: two `input` steps in one run, in two stages or in two
 * branches of a `parallel`, are two questions and never share a file. A resumed run binds the
 * same identity, so it finds the answer it was given.
 */
data class InputOperation(
    val runId: String,
    val opId: String,
)

/** What the pipeline author asked, in the vocabulary the port understands. */
data class InputRequest(
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
