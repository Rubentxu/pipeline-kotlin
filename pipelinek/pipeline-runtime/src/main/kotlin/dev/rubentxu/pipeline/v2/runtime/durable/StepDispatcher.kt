package dev.rubentxu.pipeline.v2.runtime.durable

import dev.rubentxu.pipeline.v2.runtime.SpanScope
import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.ExecutionContext
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions

/**
 * The coordinates of ONE dispatch of ONE step.
 *
 * ## WHY A VALUE AND NOT EIGHT PARAMETERS
 *
 * A dispatch answers a question with eight facts in it: which step, of which
 * run, in which stage, at which index, with which options, under which body
 * path, and under which span parent. Eight parameters are eight independent
 * chances for two callers to disagree about the same dispatch, and the
 * disagreement would be invisible: the run would still produce the right
 * answer, and only the trace would be wrong.
 *
 * As ONE value the facts travel together, and a consumer that forwards a
 * dispatch forwards all of it or none of it.
 *
 * ## WHY THE SCOPE TRAVELS IN HERE
 *
 * The span parent is part of the dispatch's coordinates and not part of its
 * surroundings. A parallel branch runs on another dispatcher, where anything
 * ambient would already be gone, and a durable run resumes in another
 * process, where nothing ambient survives at all. Carrying the parent here
 * is what makes the lineage of a run a fact about the run instead of a fact
 * about the thread that happened to run it.
 */
internal data class StepDispatchRequest(
    /** The step to dispatch. */
    val step: StepNode,

    /** The run that owns the dispatch. */
    val runId: RunId,

    /** The name of the stage that owns the step. */
    val stageName: String,

    /** The index of the stage inside the pipeline. */
    val stageIndex: Int,

    /** The index of the step inside its stage. */
    val stepIndex: Int,

    /** The options the step runs under. */
    val shOptions: ShOptions,

    /** The durable path that tells this step apart from its siblings. */
    val bodyPath: List<BlockSegment> = emptyList(),

    /** The context and the span parent this dispatch runs under. */
    val spanScope: SpanScope = SpanScope.EMPTY,
)

/**
 * What a dispatch produced: the ATOMIC result and the context it left behind.
 *
 * The two are kept in one value because a consumer that gets the outcome but
 * not the context cannot continue the run faithfully, and one that gets the
 * context but not the outcome cannot report the run. A convenience `outcome`
 * accessor is deliberately absent: every consumer naming the field it reads
 * is the evidence that no projection is happening by accident.
 */
internal data class DispatchedStep(
    val result: CommonExecutionResult,
    val context: ExecutionContext,
)

/**
 * The step-execution spine, as its consumers see it.
 *
 * ## WHY THERE IS A PORT AND NOT ONLY A CLASS
 *
 * The spine is the ONE place where "take this step and make it happen" is
 * decided. It has exactly one implementation, and this port does not add a
 * second one. What the port adds is the ability to CONSUME the spine without
 * the whole of it: a consumer that only needs to know which parent a branch
 * was given has no business constructing the preparation, the resolver, the
 * recovery interpretation and the runtime context factory.
 *
 * The consumers are the ones that decide what a dispatch means to them, so
 * they depend on the port and the composition root alone knows the class.
 *
 * ## WHAT THE PORT DOES NOT ALLOW
 *
 * The port cannot open a span of its own, cannot write a durable row and
 * cannot decide an outcome. It dispatches and it returns what the dispatch
 * produced. A consumer that wanted a second opinion about the outcome would
 * have to hold the authority the spine already holds, and there is exactly
 * one authority for the outcome of a step.
 */
internal fun interface StepDispatcher {

    /** Dispatches the step the request names, and returns what it produced. */
    suspend fun dispatch(request: StepDispatchRequest): DispatchedStep
}
