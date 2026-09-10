package dev.rubentxu.pipeline.v2.domain.durable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * RED-first HF0 tests for [ParallelReconciler] — pure reconciliation of
 * parallel durable facts into an explicit [ParallelDecision] (PAR-D).
 *
 * Matrix under test (PAR-D0 §2 + confirmed decisions):
 * - P6-1..P6-7 core scenarios, W0..W7 crash windows,
 * - P6-8 ambiguity is NOT failure (FAILED child may hide Unstable),
 * - P6-9 aggregate COMPOSITE identity/kind proof lives at the journal
 *   contract level; here we assert the state-space rows that consume it.
 *
 * Zero I/O: snapshots only. The coordinator is the single writer.
 */
class ParallelReconcilerTest {

    private val fp = Fingerprint.compute(OperationInput("core.parallel", emptyMap(), "run-x", 1), "core.parallel", ReplayPolicy.MEMOIZED, 1)
    private val otherFp = Fingerprint("deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef")
    private val id = ParallelAggregateId(runId = "run-x", stageIndex = 0)

    private fun child(branch: Int, idx: Int, status: OperationStatus) =
        ParallelBranchChildSnapshot(branchIndex = branch, childIndex = idx, status = status)

    private fun aggregate(status: OperationStatus, fp: Fingerprint = this.fp, outcome: BranchTerminal? = null) =
        ParallelAggregateSnapshot(id = id, fingerprint = fp, status = status, semanticOutcome = outcome)

    private fun input(
        branches: Int = 2,
        aggregateRow: ParallelAggregateSnapshot? = null,
        children: Map<Int, List<ParallelBranchChildSnapshot>> = emptyMap(),
        currentFingerprint: Fingerprint = fp,
    ) = ParallelReconciliationInput(
        aggregateId = id,
        currentFingerprint = currentFingerprint,
        aggregateRow = aggregateRow,
        branchCount = branches,
        childrenByBranch = children,
    )

    // P6-1 — W0 fresh success/success structure
    @Test
    @DisplayName("P6-1: fresh no aggregate no children -> Start(all)")
    fun p61_freshStartsAll() {
        val d = ParallelReconciler.reconcile(input(branches = 2))
        assertEquals(ParallelDecision.Start(listOf(0, 1)), d)
    }

    // P6-2 — W0 with prior failure evidence absent; fold ordering is AwaitAll semantics
    @Test
    @DisplayName("P6-2: AwaitAll fold: failure dominates, lowest index wins")
    fun p62_foldAwaitAll() {
        val outcomes = mapOf(
            0 to BranchTerminal.Succeeded,
            1 to BranchTerminal.Unstable,
            2 to BranchTerminal.Failed("boom"),
            3 to BranchTerminal.Failed("later"),
        )
        val fold = foldAwaitAll(outcomes)
        assertTrue(fold is BranchTerminal.Failed)
        assertEquals("boom", (fold as BranchTerminal.Failed).message)

        val onlyUnstable = foldAwaitAll(mapOf(0 to BranchTerminal.Succeeded, 1 to BranchTerminal.Unstable))
        assertEquals(BranchTerminal.Unstable, onlyUnstable)
    }

    // P6-3 — W6 terminal success aggregate
    @Test
    @DisplayName("P6-3: aggregate SUCCEEDED with exact outcome -> ReuseSuccess, zero executions")
    fun p63_reuseSuccess() {
        val d = ParallelReconciler.reconcile(
            input(aggregateRow = aggregate(OperationStatus.SUCCEEDED, outcome = BranchTerminal.Succeeded)),
        )
        assertEquals(ParallelDecision.ReuseSuccess, d)
    }

    // P6-4 — W6 terminal failure aggregate
    @Test
    @DisplayName("P6-4: aggregate FAILED with exact outcome -> ReuseFailure, zero executions")
    fun p64_reuseFailure() {
        val d = ParallelReconciler.reconcile(
            input(aggregateRow = aggregate(OperationStatus.FAILED, outcome = BranchTerminal.Failed("boom"))),
        )
        assertEquals(ParallelDecision.ReuseFailure, d)
    }

    // P6-5 — W3 partial recovery: A terminal, B incomplete
    @Test
    @DisplayName("P6-5: A terminal SUCCEEDED, B absent -> ResumeBranches([B]); A executions = 0")
    fun p65_resumeIncompleteOnly() {
        val d = ParallelReconciler.reconcile(
            input(
                branches = 2,
                children = mapOf(0 to listOf(child(0, 0, OperationStatus.SUCCEEDED), child(0, 1, OperationStatus.SUCCEEDED))),
            ),
        )
        assertEquals(ParallelDecision.ResumeBranches(listOf(1)), d)
    }

    // P6-6 — W5 all children terminal, aggregate stale, all success
    @Test
    @DisplayName("P6-6: all children SUCCEEDED, aggregate stale -> CloseFromChildren(Success), children = 0")
    fun p66_closeFromChildrenSuccess() {
        val d = ParallelReconciler.reconcile(
            input(
                branches = 2,
                children = mapOf(
                    0 to listOf(child(0, 0, OperationStatus.SUCCEEDED)),
                    1 to listOf(child(1, 0, OperationStatus.SUCCEEDED)),
                ),
            ),
        )
        assertEquals(ParallelDecision.CloseFromChildren(BranchTerminal.Succeeded), d)
    }

    // P6-7 — W7 divergence before effects
    @Test
    @DisplayName("P6-7: fingerprint mismatch -> RejectDivergence, zero executions")
    fun p67_divergenceRejects() {
        val d = ParallelReconciler.reconcile(
            input(
                aggregateRow = aggregate(OperationStatus.RUNNING, fp = otherFp),
                children = mapOf(0 to listOf(child(0, 0, OperationStatus.SUCCEEDED))),
                currentFingerprint = fp,
            ),
        )
        assertTrue(d is ParallelDecision.RejectDivergence)
    }

    // P6-8 — ambiguity is NOT failure (confirmed decision (a))
    @Test
    @DisplayName("P6-8a: aggregate stale + FAILED child + no lossless carrier -> RejectAmbiguousOutcome, zero executions")
    fun p68_ambiguousFailedChild() {
        val d = ParallelReconciler.reconcile(
            input(
                branches = 2,
                children = mapOf(
                    0 to listOf(child(0, 0, OperationStatus.FAILED)),
                    1 to listOf(child(1, 0, OperationStatus.SUCCEEDED)),
                ),
            ),
        )
        assertTrue(d is ParallelDecision.RejectAmbiguousOutcome, "was: $d")
    }

    @Test
    @DisplayName("P6-8b: W6 reuse of UNSTABLE aggregate uses the exact durable outcome")
    fun p68_reuseUnstable() {
        val d = ParallelReconciler.reconcile(
            input(aggregateRow = aggregate(OperationStatus.ABORTED, outcome = BranchTerminal.Unstable)),
        )
        assertEquals(ParallelDecision.ReuseUnstable, d)
    }

    @Test
    @DisplayName("P6-8c: W1 aggregate admitted RUNNING, zero children -> Start(all)")
    fun p68_admittedNotLaunched() {
        val d = ParallelReconciler.reconcile(input(branches = 2, aggregateRow = aggregate(OperationStatus.RUNNING)))
        assertEquals(ParallelDecision.Start(listOf(0, 1)), d)
    }

    @Test
    @DisplayName("P6-8d: W4 a FAILED terminal child with aggregate RUNNING (non-stale evidence path) -> ambiguous, not resume of terminal branches")
    fun p68_w4FailedTerminalIsAmbiguous() {
        // Branch 0 has a FAILED child; branch 1 is mid-run (non-terminal child).
        // Branch 0's fold cannot be proven lossless -> fail closed.
        val d = ParallelReconciler.reconcile(
            input(
                branches = 2,
                aggregateRow = aggregate(OperationStatus.RUNNING),
                children = mapOf(
                    0 to listOf(child(0, 0, OperationStatus.FAILED)),
                    1 to listOf(child(1, 0, OperationStatus.RUNNING)),
                ),
            ),
        )
        assertTrue(d is ParallelDecision.RejectAmbiguousOutcome, "was: $d")
    }

    // P6-9 — W6 aggregate terminal via COMPOSITE row (identity/kind contract at journal level)
    @Test
    @DisplayName("P6-9: terminal aggregate with structural fingerprint and exact outcome reuses; identity fields stay structural")
    fun p69_aggregateIdentityContract() {
        val row = aggregate(OperationStatus.SUCCEEDED, outcome = BranchTerminal.Succeeded)
        assertEquals(id, row.id)
        assertEquals(fp, row.fingerprint)
        val d = ParallelReconciler.reconcile(input(aggregateRow = row))
        assertEquals(ParallelDecision.ReuseSuccess, d)
    }

    @Test
    @DisplayName("invariant: reuse/close decisions never name a branch to execute")
    fun invariant_reuseSchedulesNothing() {
        listOf(
            ParallelReconciler.reconcile(input(aggregateRow = aggregate(OperationStatus.SUCCEEDED, outcome = BranchTerminal.Succeeded))),
            ParallelReconciler.reconcile(input(aggregateRow = aggregate(OperationStatus.FAILED, outcome = BranchTerminal.Failed("x")))),
        ).forEach { d ->
            assertTrue(d is ParallelDecision.ReuseSuccess || d is ParallelDecision.ReuseFailure, "was: $d")
        }
    }
}
