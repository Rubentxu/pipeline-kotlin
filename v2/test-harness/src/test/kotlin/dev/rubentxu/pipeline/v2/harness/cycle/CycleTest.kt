package dev.rubentxu.pipeline.v2.harness.cycle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class CycleTest {

    private val commit = "0123456789abcdef0123456789abcdef01234567"

    /**
     * Walk a list of (event, context-in) tuples through the
     * decider, threading the accumulator across calls, and
     * return the final state. Helper for the happy-path tests.
     */
    private fun walk(
        start: CycleState,
        events: List<CycleEvent>,
        startContext: CycleContext = CycleContext(),
    ): Pair<CycleState, CycleContext> {
        var state = start
        var ctx = startContext
        for (event in events) {
            val outcome = Cycle.decide(state, event, ctx)
            assertTrue(outcome is CycleOutcome.Next) { "unexpected outcome for $event from $state: $outcome" }
            state = (outcome as CycleOutcome.Next).nextState
            ctx = outcome.nextContext
        }
        return state to ctx
    }

    @Nested
    inner class HappyPath {
        @Test
        fun `Draft to Running on PrepareStarted`() {
            val outcome = Cycle.decide(CycleState.Draft, CycleEvent.PrepareStarted)
            val next = outcome as CycleOutcome.Next
            assertEquals(CycleState.Running, next.nextState)
        }

        @Test
        fun `Running accumulates probes, then Observed on AllProbesObserved`() {
            val (state, ctx) = walk(
                start = CycleState.Draft,
                events = listOf(
                    CycleEvent.PrepareStarted,
                    CycleEvent.ProbeFinished(0, "ev-1"),
                    CycleEvent.ProbeFinished(0, "ev-2"),
                    CycleEvent.AllProbesObserved(commit),
                ),
            )
            assertEquals(CycleState.Observed(commit), state)
            assertEquals(2, ctx.probeOutcomes.size)
        }

        @Test
        fun `Observed to Promoted carries the commit and the replacement evidence`() {
            val (obsState, obsCtx) = walk(
                start = CycleState.Draft,
                events = listOf(
                    CycleEvent.PrepareStarted,
                    CycleEvent.ProbeFinished(0, "ev"),
                    CycleEvent.AllProbesObserved(commit),
                ),
            )
            assertEquals(CycleState.Observed(commit), obsState)
            val outcome = Cycle.decide(obsState, CycleEvent.PromoteRequested("permanent contract"), obsCtx)
            val next = outcome as CycleOutcome.Next
            val promoted = next.nextState as CycleState.Promoted
            assertEquals(commit, promoted.promotedAtCommit)
            assertEquals("permanent contract", next.nextContext.latestReplacementEvidence)
        }

        @Test
        fun `Promoted closes with the close reason`() {
            val (state, _) = walk(
                start = CycleState.Draft,
                events = listOf(
                    CycleEvent.PrepareStarted,
                    CycleEvent.ProbeFinished(0, "ev"),
                    CycleEvent.AllProbesObserved(commit),
                    CycleEvent.PromoteRequested("permanent contract"),
                    CycleEvent.CloseRequested("temporary probes retired"),
                ),
            )
            assertTrue(state is CycleState.Closed) { "got $state" }
            assertEquals("temporary probes retired", (state as CycleState.Closed).closeReason)
        }

        @Test
        fun `Closed is terminal and any further event is an illegal transition`() {
            val outcome = Cycle.decide(
                CycleState.Closed("done"),
                CycleEvent.CloseRequested("again"),
            )
            assertTrue(outcome is CycleOutcome.IllegalTransition) { "got $outcome" }
        }
    }

    @Nested
    inner class RefusalPaths {
        @Test
        fun `Draft can close directly with a close reason`() {
            val outcome = Cycle.decide(CycleState.Draft, CycleEvent.CloseRequested("manifest invalid"))
            val next = outcome as CycleOutcome.Next
            assertTrue(next.nextState is CycleState.Refused) { "got ${next.nextState}" }
        }

        @Test
        fun `any state can move to BlockedExternal on a blocker event`() {
            val states: List<CycleState> = listOf(
                CycleState.Draft,
                CycleState.Running,
                CycleState.Observed(commit),
                CycleState.Promoted(commit),
            )
            for (state in states) {
                val outcome = Cycle.decide(
                    state,
                    CycleEvent.ExternalBlockerRaised("harness verdict pending"),
                )
                assertTrue(outcome is CycleOutcome.Next) { "got $outcome for $state" }
                assertEquals(
                    CycleState.BlockedExternal("harness verdict pending"),
                    (outcome as CycleOutcome.Next).nextState,
                )
            }
        }
    }

    @Nested
    inner class IllegalTransitions {
        @Test
        fun `Draft cannot receive ProbeFinished`() {
            val outcome = Cycle.decide(CycleState.Draft, CycleEvent.ProbeFinished(0, "ev"))
            assertTrue(outcome is CycleOutcome.IllegalTransition) { "got $outcome" }
        }
    }

    /**
     * BQ4 adversarial tests (2026-10-16 audit, RC3 close-out):
     * the previous decider accepted transitions on state + event
     * alone, with no access to the cycle's accumulated evidence.
     * That allowed sequences that violated the spec. The new
     * decider threads [CycleContext] and refuses:
     *
     *   - `AllProbesObserved` from `Running` with no probes;
     *   - `AllProbesObserved` with at least one failing probe;
     *   - `PromoteRequested` with blank `replacementEvidence`;
     *   - `CloseRequested` from `Observed` with no probes
     *     accumulated.
     *
     * Each test is a single transition; the runner tests thread
     * the accumulator.
     */
    @Nested
    inner class BQ4Adversarial {

        @Test
        fun `AllProbesObserved with no probes is Refused (the audit's P0)`() {
            // Draft -> Running (no probes), then AllProbesObserved.
            val running = (Cycle.decide(
                CycleState.Draft,
                CycleEvent.PrepareStarted,
            ) as CycleOutcome.Next).nextState
            assertEquals(CycleState.Running, running)
            val outcome = Cycle.decide(
                running,
                CycleEvent.AllProbesObserved(commit),
            )
            val next = outcome as CycleOutcome.Next
            val refused = next.nextState as CycleState.Refused
            assertTrue(
                refused.reason.contains("no probe succeeded"),
                "refusal must name the missing evidence, got ${refused.reason}",
            )
        }

        @Test
        fun `AllProbesObserved with one failing probe is Refused`() {
            // The decider refuses a failed probe at the probe
            // boundary: a `ProbeFinished(exitCode=1, ...)`
            // transitions the cycle to `Refused` immediately,
            // BEFORE `AllProbesObserved` is even consulted. The
            // test below drives the sequence step by step and
            // asserts the refusal at the failing probe.
            val running = (Cycle.decide(
                CycleState.Draft,
                CycleEvent.PrepareStarted,
            ) as CycleOutcome.Next).nextState
            val okOutcome = Cycle.decide(
                running,
                CycleEvent.ProbeFinished(0, "ok-1"),
            )
            assertTrue(okOutcome is CycleOutcome.Next) { "got $okOutcome" }
            val stillRunning = (okOutcome as CycleOutcome.Next).nextState
            assertEquals(CycleState.Running, stillRunning)
            // Now the failing probe: the cycle MUST refuse
            // immediately, NOT stay in Running.
            val badOutcome = Cycle.decide(
                stillRunning,
                CycleEvent.ProbeFinished(1, "bad-1"),
            )
            val next = badOutcome as CycleOutcome.Next
            val refused = next.nextState as CycleState.Refused
            assertTrue(
                refused.reason.contains("failed with exitCode=1"),
                "refusal must name the failing probe, got ${refused.reason}",
            )
        }

        @Test
        fun `PromoteRequested with blank replacementEvidence is Refused`() {
            val (obsState, obsCtx) = walk(
                start = CycleState.Draft,
                events = listOf(
                    CycleEvent.PrepareStarted,
                    CycleEvent.ProbeFinished(0, "ev"),
                    CycleEvent.AllProbesObserved(commit),
                ),
            )
            val outcome = Cycle.decide(
                obsState,
                CycleEvent.PromoteRequested(""),
                obsCtx,
            )
            val next = outcome as CycleOutcome.Next
            val refused = next.nextState as CycleState.Refused
            assertTrue(
                refused.reason.contains("blank replacementEvidence"),
                "refusal must name the missing replacement evidence, got ${refused.reason}",
            )
        }

        @Test
        fun `CloseRequested from Observed with no probes is Refused`() {
            // Construct an Observed state directly (the data
            // class init accepts it) but with an EMPTY context:
            // a cycle that observed with no probe finished.
            val obsState = CycleState.Observed(commit)
            val outcome = Cycle.decide(
                obsState,
                CycleEvent.CloseRequested("clean cycle"),
                CycleContext(),
            )
            val next = outcome as CycleOutcome.Next
            val refused = next.nextState as CycleState.Refused
            assertTrue(
                refused.reason.contains("never collected evidence"),
                "refusal must name the missing evidence, got ${refused.reason}",
            )
        }

        @Test
        fun `a probe with non-zero exit code is Refused at the probe boundary`() {
            val running = (Cycle.decide(
                CycleState.Draft,
                CycleEvent.PrepareStarted,
            ) as CycleOutcome.Next).nextState
            val outcome = Cycle.decide(
                running,
                CycleEvent.ProbeFinished(1, "bad-1"),
            )
            val next = outcome as CycleOutcome.Next
            assertTrue(next.nextState is CycleState.Refused) {
                "got ${next.nextState}; a non-zero exit code MUST refuse the cycle"
            }
        }

        @Test
        fun `a probe with blank evidenceRef is refused at the data class init`() {
            // The ProbeFinished data class requires a non-blank
            // evidenceRef. A caller that produces a blank
            // evidence ref gets the refusal at the call site,
            // before the decider is even consulted.
            val ex = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException::class.java,
            ) {
                ProbeFinished(exitCode = 0, evidenceRef = "")
            }
            assertNotNull(ex.message)
        }

        @Test
        fun `a probe with blank evidenceRef as an event is rejected by the decider`() {
            // The decider creates a ProbeFinished internally,
            // and the data-class init refuses the construction.
            // The exception type is the same; either way a
            // blank evidence ref cannot enter the accumulator.
            val running = (Cycle.decide(
                CycleState.Draft,
                CycleEvent.PrepareStarted,
            ) as CycleOutcome.Next).nextState
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
                Cycle.decide(running, CycleEvent.ProbeFinished(0, ""))
            }
        }
    }
}
