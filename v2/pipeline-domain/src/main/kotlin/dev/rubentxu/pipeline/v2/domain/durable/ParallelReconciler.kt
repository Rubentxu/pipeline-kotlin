package dev.rubentxu.pipeline.v2.domain.durable

/**
 * PAR-D parallel reconciler — pure, plan-only, effect-free (ADR-0075
 * thinker/writer pattern reused). Reads observed durable facts, returns a
 * [ParallelDecision]. The coordinator is the single writer.
 *
 * Laws (PAR-D0 §2 matrix + confirmed decisions):
 * - a terminal child is NEVER re-executed;
 * - reuse/close paths schedule zero executions;
 * - structural divergence rejects before any effect;
 * - ambiguity (FAILED child possibly hiding Unstable, no lossless carrier)
 *   is NOT Failure: it is [ParallelDecision.RejectAmbiguousOutcome];
 * - decision makes the executed AND not-executed branch sets explicit.
 */
object ParallelReconciler {

    fun reconcile(input: ParallelReconciliationInput): ParallelDecision {
        // Structural divergence first: fingerprint mismatch rejects before anything else.
        if (input.aggregateRow != null && input.aggregateRow.fingerprint != input.currentFingerprint) {
            return ParallelDecision.RejectDivergence(
                "parallel aggregate fingerprint mismatch at '${input.aggregateId.runId}-s${input.aggregateId.stageIndex}'",
            )
        }

        val branchOutcomes = mutableMapOf<Int, BranchTerminal>()
        val incomplete = mutableListOf<Int>()
        for (branch in 0 until input.branchCount) {
            val children = input.childrenByBranch[branch].orEmpty()
            when {
                children.isEmpty() -> incomplete += branch
                else -> {
                    val terminal = reconstructBranch(children)
                    when (terminal) {
                        is Reconstructed.Terminal -> branchOutcomes[branch] = terminal.outcome
                        is Reconstructed.Ambiguous ->
                            return ParallelDecision.RejectAmbiguousOutcome(terminal.reason)
                    }
                }
            }
        }

        return when {
            // W6: aggregate row terminal with exact semantic outcome — reuse it.
            input.aggregateRow?.status?.let { it == OperationStatus.SUCCEEDED || it == OperationStatus.FAILED || it == OperationStatus.ABORTED } == true &&
                input.aggregateRow.semanticOutcome != null -> when (input.aggregateRow.semanticOutcome) {
                is BranchTerminal.Succeeded -> ParallelDecision.ReuseSuccess
                is BranchTerminal.Unstable -> ParallelDecision.ReuseUnstable
                is BranchTerminal.Failed -> ParallelDecision.ReuseFailure
            }

            // W0: no aggregate, no children.
            input.aggregateRow == null && branchOutcomes.isEmpty() && incomplete.size == input.branchCount ->
                ParallelDecision.Start((0 until input.branchCount).toList())

            // W1: aggregate admitted, nothing launched.
            input.aggregateRow != null && branchOutcomes.isEmpty() && incomplete.size == input.branchCount ->
                ParallelDecision.Start((0 until input.branchCount).toList())

            // W5: every branch terminal, aggregate stale — close from children if lossless.
            incomplete.isEmpty() -> {
                val fold = foldAwaitAll(branchOutcomes)
                // If the fold is unstable/failed, child rows cannot prove it losslessly:
                // a FAILED child row may hide an Unstable step outcome. Only a SUCCESS
                // fold is provable from all-SUCCEEDED children.
                when (fold) {
                    is BranchTerminal.Succeeded -> ParallelDecision.CloseFromChildren(fold)
                    else -> ParallelDecision.RejectAmbiguousOutcome(
                        "all children terminal but aggregate stale; child FAILED rows cannot distinguish Failure from Unstable (no lossless semantic carrier)",
                    )
                }
            }

            // W2–W4: partially completed — resume only the incomplete branches.
            else -> ParallelDecision.ResumeBranches(incomplete.toList())
        }
    }

    /** Reconstruct one branch's terminal outcome from child rows, or report ambiguity. */
    private sealed interface Reconstructed {
        data class Terminal(val outcome: BranchTerminal) : Reconstructed
        data class Ambiguous(val reason: String) : Reconstructed
    }

    private fun reconstructBranch(children: List<ParallelBranchChildSnapshot>): Reconstructed {
        val hasFailed = children.any { it.status == OperationStatus.FAILED }
        val allSucceeded = children.all { it.status == OperationStatus.SUCCEEDED }
        return when {
            // Not every child terminal: the branch is incomplete (or mid-run).
            children.any { !it.status.isTerminal } -> Reconstructed.Ambiguous("__INCOMPLETE__")
            allSucceeded -> Reconstructed.Terminal(BranchTerminal.Succeeded)
            hasFailed -> Reconstructed.Ambiguous(
                "branch child rows contain FAILED; cannot distinguish real Failure from Unstable without a lossless semantic carrier",
            )
            // Terminal-but-neither-succeeded-nor-failed (e.g. LOST, TIMEOUT) — ambiguous, fail closed.
            else -> Reconstructed.Ambiguous("branch child rows terminal with non-succeeded/failed statuses")
        }
    }
}
