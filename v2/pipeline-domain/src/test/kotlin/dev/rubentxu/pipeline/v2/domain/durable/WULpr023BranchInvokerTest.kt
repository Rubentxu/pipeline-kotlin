package dev.rubentxu.pipeline.v2.domain.durable

import dev.rubentxu.pipeline.v2.domain.durable.ParallelDecision.CloseFromChildren
import dev.rubentxu.pipeline.v2.domain.durable.ParallelDecision.RejectAmbiguousOutcome
import dev.rubentxu.pipeline.v2.domain.durable.ParallelDecision.RejectDivergence
import dev.rubentxu.pipeline.v2.domain.durable.ParallelDecision.ResumeBranches
import dev.rubentxu.pipeline.v2.domain.durable.ParallelDecision.ReuseFailure
import dev.rubentxu.pipeline.v2.domain.durable.ParallelDecision.ReuseSuccess
import dev.rubentxu.pipeline.v2.domain.durable.ParallelDecision.ReuseUnstable
import dev.rubentxu.pipeline.v2.domain.durable.ParallelDecision.Start
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * WU-LPR-023 — Pure branch invoker tests.
 *
 * The invoker translates a [ParallelDecision] + a named-branch list into a
 * typed [BranchPlan]. The contract is closed: every decision variant has
 * exactly one interpretation, and out-of-range indices are dropped silently
 * (the reconciler is authoritative for which branches to dispatch).
 */
class WULpr023BranchInvokerTest {

    private val invoker: BranchInvoker = DefaultBranchInvoker
    private val branches = listOf(
        NamedBranch(index = 0, name = "linux-amd64"),
        NamedBranch(index = 1, name = "linux-arm64"),
        NamedBranch(index = 2, name = "macos-amd64"),
    )

    @Nested
    @DisplayName("Start")
    inner class StartCases {

        @Test
        fun `Start with all indices yields LaunchAll with named branches`() {
            val plan = invoker.plan(Start(branches = listOf(0, 1, 2)), branches)
            assertTrue(plan is BranchPlan.LaunchAll)
            assertEquals(branches, (plan as BranchPlan.LaunchAll).branches)
        }

        @Test
        fun `Start with subset yields LaunchAll with subset`() {
            val plan = invoker.plan(Start(branches = listOf(0, 2)), branches)
            assertTrue(plan is BranchPlan.LaunchAll)
            assertEquals(
                listOf(branches[0], branches[2]),
                (plan as BranchPlan.LaunchAll).branches,
            )
        }

        @Test
        fun `Start with empty list yields LaunchAll with empty list`() {
            val plan = invoker.plan(Start(branches = emptyList()), branches)
            assertTrue(plan is BranchPlan.LaunchAll)
            assertEquals(emptyList<NamedBranch>(), (plan as BranchPlan.LaunchAll).branches)
        }

        @Test
        fun `Start with out-of-range indices drops them silently`() {
            val plan = invoker.plan(Start(branches = listOf(0, 99)), branches)
            assertTrue(plan is BranchPlan.LaunchAll)
            assertEquals(listOf(branches[0]), (plan as BranchPlan.LaunchAll).branches)
        }

        @Test
        fun `Start preserves branch order from the decision (not the named list)`() {
            val plan = invoker.plan(Start(branches = listOf(2, 0)), branches)
            assertTrue(plan is BranchPlan.LaunchAll)
            assertEquals(
                listOf(branches[2], branches[0]),
                (plan as BranchPlan.LaunchAll).branches,
            )
        }
    }

    @Nested
    @DisplayName("ResumeBranches")
    inner class ResumeCases {

        @Test
        fun `ResumeBranches yields Resume with named branches`() {
            val plan = invoker.plan(ResumeBranches(branches = listOf(1)), branches)
            assertTrue(plan is BranchPlan.Resume)
            assertEquals(listOf(branches[1]), (plan as BranchPlan.Resume).branches)
        }

        @Test
        fun `ResumeBranches with out-of-range indices drops them silently`() {
            val plan = invoker.plan(ResumeBranches(branches = listOf(99, 100)), branches)
            assertTrue(plan is BranchPlan.Resume)
            assertEquals(emptyList<NamedBranch>(), (plan as BranchPlan.Resume).branches)
        }
    }

    @Nested
    @DisplayName("Reuse / Close / Reject — all yield NoOp")
    inner class ClosureCases {

        @Test
        fun `ReuseSuccess yields NoOp`() {
            assertEquals(BranchPlan.NoOp, invoker.plan(ReuseSuccess, branches))
        }

        @Test
        fun `ReuseUnstable yields NoOp`() {
            assertEquals(BranchPlan.NoOp, invoker.plan(ReuseUnstable, branches))
        }

        @Test
        fun `ReuseFailure yields NoOp`() {
            assertEquals(BranchPlan.NoOp, invoker.plan(ReuseFailure, branches))
        }

        @Test
        fun `CloseFromChildren yields NoOp`() {
            val plan = invoker.plan(
                CloseFromChildren(outcome = BranchTerminal.Succeeded),
                branches,
            )
            assertEquals(BranchPlan.NoOp, plan)
        }

        @Test
        fun `RejectDivergence yields NoOp (runner must NOT launch)`() {
            val plan = invoker.plan(
                RejectDivergence(reason = "fingerprint mismatch"),
                branches,
            )
            assertEquals(BranchPlan.NoOp, plan)
        }

        @Test
        fun `RejectAmbiguousOutcome yields NoOp`() {
            val plan = invoker.plan(
                RejectAmbiguousOutcome(reason = "ambiguous"),
                branches,
            )
            assertEquals(BranchPlan.NoOp, plan)
        }
    }

    @Nested
    @DisplayName("NamedBranch invariants")
    inner class NamedBranchInvariants {

        @Test
        fun `NamedBranch rejects negative index`() {
            val ex = assertThrows<IllegalArgumentException> {
                NamedBranch(index = -1, name = "x")
            }
            assertTrue(ex.message!!.contains("index"))
        }

        @Test
        fun `NamedBranch rejects blank name`() {
            val ex = assertThrows<IllegalArgumentException> {
                NamedBranch(index = 0, name = "")
            }
            assertTrue(ex.message!!.contains("name"))
        }
    }

    @Nested
    @DisplayName("Exhaustiveness")
    inner class Exhaustiveness {

        @Test
        fun `invoker is total over the closed ParallelDecision ADT`() {
            val decisions: List<ParallelDecision> = listOf(
                Start(listOf(0)),
                ResumeBranches(listOf(0)),
                ReuseSuccess,
                ReuseUnstable,
                ReuseFailure,
                CloseFromChildren(BranchTerminal.Succeeded),
                RejectDivergence("x"),
                RejectAmbiguousOutcome("y"),
            )
            decisions.forEach { d ->
                val plan = invoker.plan(d, branches)
                assertTrue(
                    plan is BranchPlan.LaunchAll ||
                        plan is BranchPlan.Resume ||
                        plan is BranchPlan.NoOp,
                    "invoker MUST map every decision to a typed plan; got $plan for $d",
                )
            }
        }
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
        try {
            block()
        } catch (t: Throwable) {
            if (t is T) return t
            throw AssertionError("expected ${T::class.simpleName}, got ${t::class.simpleName}: ${t.message}")
        }
        throw AssertionError("expected ${T::class.simpleName}, no exception thrown")
    }
}
