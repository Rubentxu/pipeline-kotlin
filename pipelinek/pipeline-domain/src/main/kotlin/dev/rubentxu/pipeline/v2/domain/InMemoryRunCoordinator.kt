package dev.rubentxu.pipeline.v2.domain

/**
 * Deterministic in-process [RunCoordinator]: plans the execution with
 * [ExecutionPlanner], dispatches each unit through the injected
 * [StepDispatcher] (concurrent waves through the optional
 * [ConcurrentStepDispatcher], which reuses the SAME dispatcher instance),
 * and folds outcomes with [RunOutcomeReducer].
 *
 * This adapter exists to (a) exercise the full M2 contract chain in
 * tests and characterisation without I/O, and (b) define the reference
 * execution semantics the production durable coordinator (LF-0205+) must
 * reproduce: same order, same dispatch sequence, same reduced outcome —
 * that is the M2 exit criterion ("InMemory/SQLite producen outcomes y
 * orden semántico equivalente").
 *
 * ## Semantics
 *
 * - Plan: [ExecutionPlanner.plan] — waves of constraint-free steps;
 *   fail-closed on unknown edge references, cycles, and contradictory
 *   `PARALLEL` edges.
 * - Concurrent waves: with a [ConcurrentStepDispatcher] injected, wave
 *   steps dispatch concurrently through the same [StepDispatcher]
 *   instance (M2-004). Without one, waves flatten to sequential dispatch
 *   in declaration order (the pre-LF-0207 behaviour, kept as fallback).
 * - Resume: with [RunRequest.resumeAfter] set, steps up to and including
 *   the cursor are skipped **without dispatching**; a wave partially
 *   before the cursor keeps only its remaining steps (as a wave if two
 *   or more remain, as a single step otherwise). An unknown cursor id
 *   throws.
 * - Reduction: outcomes fold in declaration order by
 *   [RunOutcomeReducer] — first Failure wins, else first Unstable, else
 *   Success. An empty execution (definition without steps) reduces to
 *   [RunOutcome.Success].
 *
 * The adapter is not thread-safe per instance; wrap it for concurrent use.
 *
 * @see RunCoordinator
 * @see ExecutionPlanner
 * @see ConcurrentStepDispatcher
 * @see RecordingStepDispatcher
 */
class InMemoryRunCoordinator(
    private val dispatcher: StepDispatcher,
    private val concurrentDispatcher: ConcurrentStepDispatcher? = null,
) : RunCoordinator {

    override fun run(request: RunRequest): RunOutcome {
        val plan = ExecutionPlanner.plan(request.definition)
        val toExecute = sliceForResume(plan, request.resumeAfter)
        val outcomes = toExecute.flatMap { unit ->
            when (unit) {
                is ExecutionUnit.Single ->
                    listOf(dispatcher.dispatch(unit.step, StepExecutionContext(runId = request.runId)))
                is ExecutionUnit.Concurrent -> {
                    val context = StepExecutionContext(runId = request.runId)
                    concurrentDispatcher?.dispatchAll(unit.steps, context)
                        ?: unit.steps.map { dispatcher.dispatch(it, context) }
                }
            }
        }
        return RunOutcomeReducer.reduce(outcomes)
    }

    private fun sliceForResume(plan: ExecutionPlan, resumeAfter: String?): List<ExecutionUnit> {
        if (resumeAfter == null) return plan.units
        val flat = plan.linearSteps
        val cursorIndex = flat.indexOfFirst { it.id == resumeAfter }
        if (cursorIndex < 0) {
            throw IllegalArgumentException(
                "resumeAfter references unknown step '$resumeAfter'; " +
                    "resolved order: ${flat.map { it.id }}"
            )
        }
        val remaining = flat.drop(cursorIndex + 1).map { it.id }.toSet()
        return plan.units.mapNotNull { unit ->
            val kept = unit.steps.filter { it.id in remaining }
            when (kept.size) {
                0 -> null
                1 -> ExecutionUnit.Single(kept.single())
                else -> ExecutionUnit.Concurrent(kept)
            }
        }
    }
}
