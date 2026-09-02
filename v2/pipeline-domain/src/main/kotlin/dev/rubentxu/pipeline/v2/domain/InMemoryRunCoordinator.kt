package dev.rubentxu.pipeline.v2.domain

/**
 * Deterministic in-process [RunCoordinator]: resolves the step order with
 * [StepOrderResolver], dispatches each step through the injected
 * [StepDispatcher], and folds outcomes with [RunOutcomeReducer].
 *
 * This adapter exists to (a) exercise the full M2 contract chain in
 * tests and characterisation without I/O, and (b) define the reference
 * execution semantics the production durable coordinator (LF-0205) must
 * reproduce: same order, same dispatch sequence, same reduced outcome —
 * that is the M2 exit criterion ("InMemory/SQLite producen outcomes y
 * orden semántico equivalente").
 *
 * ## Semantics
 *
 * - Order: [StepOrderResolver.resolve] — declaration order without
 *   edges, deterministic topological order with them; fail-closed on
 *   unknown edge references and cycles.
 * - Resume: with [RunRequest.resumeAfter] set, steps up to and including
 *   the cursor are skipped **without dispatching**; an unknown cursor id
 *   throws.
 * - Reduction: outcomes are folded in dispatch order by
 *   [RunOutcomeReducer] — first Failure wins, else first Unstable, else
 *   Success. An empty execution (definition without steps) reduces to
 *   [RunOutcome.Success].
 *
 * The adapter is not thread-safe; wrap it for concurrent use.
 *
 * @see RunCoordinator
 * @see RecordingStepDispatcher
 */
class InMemoryRunCoordinator(
    private val dispatcher: StepDispatcher,
) : RunCoordinator {

    override fun run(request: RunRequest): RunOutcome {
        val order = StepOrderResolver.resolve(request.definition)
        val toExecute = sliceForResume(order, request.resumeAfter)
        val outcomes = toExecute.map { step ->
            dispatcher.dispatch(step, StepExecutionContext(runId = request.runId))
        }
        return RunOutcomeReducer.reduce(outcomes)
    }

    private fun sliceForResume(order: List<StepDescriptor>, resumeAfter: String?): List<StepDescriptor> {
        if (resumeAfter == null) return order
        val cursorIndex = order.indexOfFirst { it.id == resumeAfter }
        if (cursorIndex < 0) {
            throw IllegalArgumentException(
                "resumeAfter references unknown step '$resumeAfter'; resolved order: ${order.map { it.id }}"
            )
        }
        return order.drop(cursorIndex + 1)
    }
}
