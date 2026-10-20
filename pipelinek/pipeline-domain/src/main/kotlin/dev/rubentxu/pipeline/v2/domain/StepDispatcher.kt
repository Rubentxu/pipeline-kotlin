package dev.rubentxu.pipeline.v2.domain

/**
 * Execution context handed to a [StepDispatcher] for one step invocation.
 *
 * Carries the minimum identity information a dispatcher needs to execute
 * and audit a single step. Deliberately narrow: durable machinery
 * (operation journal, replay cursors, reconcilers) lives in the
 * application layer and reaches the dispatcher through its own
 * construction — not through this contract. Keeping this type pure is
 * what lets [InMemoryRunCoordinator] and the future durable coordinator
 * share the same [StepDispatcher] port.
 *
 * @property runId identity of the pipeline invocation this step belongs to.
 * @property attempt zero-based retry attempt counter. `0` is the first
 *                   execution of the step within this run. Retries
 *                   increment the attempt but reuse the same [runId].
 */
data class StepExecutionContext(
    val runId: RunId,
    val attempt: Int = 0,
) {
    init {
        require(attempt >= 0) { "StepExecutionContext.attempt must be non-negative, got $attempt" }
    }
}

/**
 * Port for executing a single pipeline step.
 *
 * This is the LF-0204 contract. Every step execution in the V2 runtime —
 * serial, parallel, resumed, or retried — MUST flow through exactly one
 * [StepDispatcher] instance. This is the seam that makes M2-004
 * ("parallel usa dispatcher principal") enforceable: once parallel
 * execution lands (LF-0207), it dispatches through this port like every
 * other execution path.
 *
 * ## Contract
 *
 * - The dispatcher MUST return a non-null [StepOutcome]; it MUST NOT
 *   throw to signal step failure. Throwing signals a *dispatcher bug*
 *   (or a cancelled JVM), not a step result — callers treat an exception
 *   as fail-closed and map it to [RunOutcome.Aborted] upstream.
 * - The dispatcher is responsible for resolving the step's executable
 *   payload from [StepDescriptor.configRef] (or its own configuration);
 *   this port deliberately does not carry payloads so that
 *   [StepDescriptor] stays metadata-only.
 * - Implementations must be safe to call sequentially from one thread.
 *   Thread-safety beyond that is an implementation concern.
 *
 * ## Adapters
 *
 * - [RecordingStepDispatcher] (domain) — test-friendly: records the
 *   dispatched step ids in order and returns configurable outcomes.
 * - The production durable dispatcher (shell/echo/... execution with
 *   journal + reconciliation) lands in LF-0205 as application work.
 *
 * @see StepOutcome
 * @see StepExecutionContext
 * @see RunCoordinator
 */
fun interface StepDispatcher {
    fun dispatch(step: StepDescriptor, context: StepExecutionContext): StepOutcome
}
