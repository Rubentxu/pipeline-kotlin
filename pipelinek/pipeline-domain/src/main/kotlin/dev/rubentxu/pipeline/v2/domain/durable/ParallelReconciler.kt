package dev.rubentxu.pipeline.v2.domain.durable

/**
 * Parallel reconciler — pure, plan-only, effect-free (
 * thinker/writer pattern reused). Reads observed durable facts, returns a
 * [ParallelDecision]. The coordinator is the single writer.
 * Laws (matrix + confirmed decisions)
 * - a terminal child is NEVER re-executed;
 * - reuse/close paths schedule zero executions;
 * - structural divergence rejects before any effect;
 * A FAILED child row possibly hiding an Unstable row (no lossless carrier for THAT row)
 * Is NOT Failure: it is [ParallelDecision.RejectAmbiguousOutcome]. Since an UNSTABLE
 * Row IS a lossless carrier, so `reconstructBranch` and the stale-aggregate fold close
 *   unstable deterministically from it — the ambiguity survives only for FAILED rows;
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
                // A branch with any non-terminal child is INCOMPLETE: the
                // canonical child dispatch resumes it; no ambiguity involved.
                children.any { !it.status.isTerminal } -> incomplete += branch
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
            // Aggregate row terminal with exact semantic outcome — reuse it. UNSTABLE
            // Joined the whitelist when made the aggregate persist it and made the
            // restart reuse it: an aggregate that says UNSTABLE carries its BranchTerminal
            // losslessly in `semanticOutcome`, so ReuseUnstable needs no child evidence.
            input.aggregateRow?.status?.let {
                it == OperationStatus.SUCCEEDED ||
                    it == OperationStatus.UNSTABLE ||
                    it == OperationStatus.FAILED ||
                    it == OperationStatus.ABORTED
            } == true &&
                input.aggregateRow.semanticOutcome != null -> when (input.aggregateRow.semanticOutcome) {
                is BranchTerminal.Succeeded -> ParallelDecision.ReuseSuccess
                is BranchTerminal.Unstable -> ParallelDecision.ReuseUnstable
                is BranchTerminal.Failed -> ParallelDecision.ReuseFailure
            }

            // No aggregate, no children.
            input.aggregateRow == null && branchOutcomes.isEmpty() && incomplete.size == input.branchCount ->
                ParallelDecision.Start((0 until input.branchCount).toList())

            // Aggregate admitted, nothing launched.
            input.aggregateRow != null && branchOutcomes.isEmpty() && incomplete.size == input.branchCount ->
                ParallelDecision.Start((0 until input.branchCount).toList())

            // Every branch terminal, aggregate stale — close from children when the
            // children prove the fold losslessly.
            incomplete.isEmpty() -> {
                val fold = foldAwaitAll(branchOutcomes)
                // Only a SUCCESS fold was provable from children: a FAILED child row
                // Could not distinguish Failure from Unstable. gave unstable rows a
                // lossless carrier (`OperationStatus.UNSTABLE`), so an UNSTABLE fold is now
                // provable the same way an all-SUCCEEDED fold always was. A fold that needed
                // a FAILED branch never reaches here — reconstructBranch reports that
                // ambiguity before a terminal ever enters the map.
                when (fold) {
                    is BranchTerminal.Succeeded -> ParallelDecision.CloseFromChildren(fold)
                    is BranchTerminal.Unstable -> ParallelDecision.CloseFromChildren(fold)
                    is BranchTerminal.Failed -> ParallelDecision.RejectAmbiguousOutcome(
                        "all children terminal but aggregate stale; child FAILED rows cannot distinguish Failure from Unstable (no lossless semantic carrier)",
                    )
                }
            }

            //: partially completed — resume only the incomplete branches.
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
        val hasUnstable = children.any { it.status == OperationStatus.UNSTABLE }
        val allSucceeded = children.all { it.status == OperationStatus.SUCCEEDED }
        return when {
            allSucceeded -> Reconstructed.Terminal(BranchTerminal.Succeeded)
            // FAILED keeps the legacy ambiguity FIRST: a FAILED row may hide an
            // unstable step outcome, and the fold cannot distinguish the two from it.
            hasFailed -> Reconstructed.Ambiguous(
                "branch child rows contain FAILED; cannot distinguish real Failure from Unstable without a lossless semantic carrier",
            )
            // The row says exactly what happened, so
            // the branch reconstructs deterministically instead of failing closed.
            hasUnstable -> Reconstructed.Terminal(BranchTerminal.Unstable)
            // Terminal-but-neither-succeeded-nor-unstable-nor-failed (LOST, FAILED_TIMEOUT,
            // ABORTED, DIVERGENT) — ambiguous, fail closed.
            else -> Reconstructed.Ambiguous("branch child rows terminal with non-succeeded/failed statuses")
        }
    }
}


