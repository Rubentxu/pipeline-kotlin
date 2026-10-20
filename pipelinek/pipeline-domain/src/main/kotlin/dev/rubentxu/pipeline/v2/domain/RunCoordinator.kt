package dev.rubentxu.pipeline.v2.domain

/**
 * Port that orchestrates one complete pipeline invocation.
 * This is the contract — the "Single RunCoordinator" that is
 * named after. The coordinator owns the execution loop: it resolves the
 * step order from the [PipelineDefinition] (via [StepOrderResolver]),
 * Dispatches each step through the [StepDispatcher] port, and
 * folds the observed [StepOutcome]s into the canonical [RunOutcome] via
 * [RunOutcomeReducer]— the single authority for run outcomes.
 * ## Contract
 * - `run` returns exactly one [RunOutcome] and never throws to signal a
 *   *step* failure; step failures are data ([RunOutcome.Failure]).
 *   Throwing is reserved for invalid requests (unknown resume cursor,
 *   malformed definition) and coordinator bugs.
 * - [RunOutcome.Aborted] is never fabricated here. Cancellation is an
 * Orchestrator-level concern of the production adapter; the
 *   contract documents that a coordinator implementation MAY return
 *   [RunOutcome.Aborted] when the run is externally interrupted.
 * - Implementations must be deterministic for the same request when the
 *   injected [StepDispatcher] is deterministic (characterisation
 *   property behind).
 * ## Resume
 * [RunRequest.resumeAfter] names a step id; the coordinator executes the
 * steps strictly after it in the resolved order and skips the rest
 * without dispatching them. An unknown cursor id is an invalid request
 * (throws). The surface supports a single linear cursor; richer
 * resume (per-branch, journal-backed) is the durable adapter's concern.
 * ## Adapters
 * - [InMemoryRunCoordinator] (domain) — deterministic, in-process.
 * - The production durable coordinator (journal, replay, reconciliation)
 * Lands in as application work
 * @see RunRequest
 * @see InMemoryRunCoordinator
 * @see RunOutcomeReducer
*/
interface RunCoordinator {
    fun run(request: RunRequest): RunOutcome
}

/**
 * Request to execute one pipeline invocation.
 * @property definition the compiled pipeline to execute. Never empty-named.
 * @property runId identity of this invocation; two runs of the same
 * Definition must carry different run ids
 * @property resumeAfter optional step id: execute strictly the steps after
 *                       this one in the resolved order. `null` runs the
 *                       whole pipeline from the start. Structural resume
 *                       (no persisted state); used by deterministic
 *                       in-process adapters.
 * @property resumeFromCursor optional resume flag: when `true`, the
 *                            coordinator loads whatever persisted cursor
 *                            state it owns for [runId] and continues from
 * It (journal-backed resume,). `false`
 *                            (default) starts a fresh run. The two resume
 *                            mechanisms are orthogonal: store-backed
 *                            resume for the durable coordinator, step-id
 *                            resume for structural adapters.
*/
data class RunRequest(
    val definition: PipelineDefinition,
    val runId: RunId,
    val resumeAfter: String? = null,
    val resumeFromCursor: Boolean = false,
)
