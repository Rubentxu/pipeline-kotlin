package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.runtime.inspect.FencingToken
import dev.rubentxu.pipeline.v2.runtime.inspect.IntrospectionRefusal
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeIntrospectionPort
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeIntrospectionResult
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeObservation

/**
 * The application operation that answers "what does this run look like right now?".
 *
 * ## What this owns
 *
 * The MEANING of the introspection port's answer: whether the run was observed, and —
 * when it could not be answered — WHICH of the reasons a consumer acts on applies. A
 * run that nobody knows, a substrate that is not configured and a store that failed
 * are three different facts. A consumer that cannot tell them apart either waits for
 * a run that does not exist, retries a store that is not there, or reports a failure
 * as a success.
 *
 * ## What it does not own
 *
 * The substrate — the introspection port is the authority for what a run looks like —
 * and the presentation. One consumer renders the answer in a line of text and another
 * renders the same answer in its own shape; the interpretation lives here so that both
 * read the SAME fact instead of each of them reading the port's answer on its own.
 *
 * ## The contract
 *
 * The answer names no SQLite type, no event store and no output store: a consumer asks
 * about a run and receives a typed answer about that run. The operation NEVER throws:
 * a substrate that cannot answer is an answer, not an exception.
 */
internal class RunStateQuery(
    private val introspection: RuntimeIntrospectionPort,
) {

    /**
     * The state of [runId], right now.
     *
     * @param runId The run to ask about.
     * @return [RunStateAnswer.Observed] when the run was observed, and
     *   [RunStateAnswer.Refused] when it could not be answered. A refusal is never
     *   reported as an observation.
     */
    fun stateOf(runId: String): RunStateAnswer =
        when (val result = introspection.inspect(runId)) {
            is RuntimeIntrospectionResult.Observation -> RunStateAnswer.Observed(result.observation)
            is RuntimeIntrospectionResult.Refused -> RunStateAnswer.Refused(refusalOf(result.refusal))
        }

    /**
     * The one translation from the port's refusal vocabulary to this operation's.
     *
     * Every refusal the port can answer a run query with is mapped here, and none of
     * them is dropped: the reasons a consumer acts on differently get a case of their
     * own, and the rest keep the facts they carried.
     */
    private fun refusalOf(refusal: IntrospectionRefusal): RunStateRefusal = when (refusal) {
        is IntrospectionRefusal.UnknownRun -> RunStateRefusal.UnknownRun(refusal.runId)
        IntrospectionRefusal.NoControlRoot ->
            RunStateRefusal.NotConfigured(RunStateRefusal.Missing.CONTROL_ROOT)

        IntrospectionRefusal.NoEventStore ->
            RunStateRefusal.NotConfigured(RunStateRefusal.Missing.EVENT_PLANE)

        IntrospectionRefusal.NoOutputPlane ->
            RunStateRefusal.NotConfigured(RunStateRefusal.Missing.OUTPUT_PLANE)

        is IntrospectionRefusal.StorageError -> RunStateRefusal.StorageFailed(refusal.cause)
        is IntrospectionRefusal.LeaseHeldByAnother ->
            RunStateRefusal.HeldByAnother(refusal.heldBy, refusal.fencingToken)

        is IntrospectionRefusal.InconsistentLeaseState -> RunStateRefusal.Inconsistent(refusal.details)
        is IntrospectionRefusal.UnknownOperation ->
            RunStateRefusal.UnknownOperation(refusal.operationId, refusal.attempt)
    }
}

/**
 * The answer of a run-state query.
 *
 * Two cases, because the question has two outcomes: the run was observed, or it could
 * not be answered. The refusal is a refusal and never becomes an observation.
 */
internal sealed interface RunStateAnswer {

    /** The run was observed, and [observation] is what it looks like right now. */
    data class Observed(val observation: RuntimeObservation) : RunStateAnswer

    /** The run could not be answered, and [refusal] names why. */
    data class Refused(val refusal: RunStateRefusal) : RunStateAnswer
}

/**
 * Why a run could not be answered.
 *
 * The vocabulary is the operation's own, and every case names a fact a consumer acts
 * on differently: a run that does not exist is not waited for, a substrate that is not
 * configured is not retried, and a store that failed is.
 */
internal sealed interface RunStateRefusal {

    /** The run is not known to this instance. */
    data class UnknownRun(val runId: String) : RunStateRefusal

    /** The substrate lacks a piece, and [missing] names what could not be read. */
    data class NotConfigured(val missing: Missing) : RunStateRefusal

    /** The storage failed while answering, and [cause] is a short diagnostic. */
    data class StorageFailed(val cause: String) : RunStateRefusal

    /** Another process is publishing the run, and it holds the publishing authority. */
    data class HeldByAnother(
        val heldBy: String,
        val fencingToken: FencingToken,
    ) : RunStateRefusal

    /** The substrate disagrees with itself about the run, and [details] says how. */
    data class Inconsistent(val details: String) : RunStateRefusal

    /** The journal holds no row for the (operation, attempt) pair that was asked for. */
    data class UnknownOperation(val operationId: String, val attempt: Int) : RunStateRefusal

    /**
     * The piece of the substrate that is missing.
     *
     * The three cases are the three ways an instance can be configured without the
     * material a run's state is read from, and they are named apart because a
     * consumer that is told "the substrate is not configured" cannot tell which piece
     * it has to provide.
     */
    enum class Missing {
        /** There is no control root, so there is no journal and no lease to consult. */
        CONTROL_ROOT,

        /** The event plane is not configured for this instance. */
        EVENT_PLANE,

        /** The output plane is not configured for this instance. */
        OUTPUT_PLANE,
    }
}
