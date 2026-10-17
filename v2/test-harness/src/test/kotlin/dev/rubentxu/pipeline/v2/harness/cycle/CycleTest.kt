package dev.rubentxu.pipeline.v2.harness.cycle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class CycleTest {

    private val commit = "0123456789abcdef0123456789abcdef01234567"

    /**
     * Build a well-formed [PromotionEvidence.PermanentContract]
     * with stable fields for use in decider tests. The decider
     * does not consult the carrier's payload (it is the
     * runner's authority), so a static, well-formed carrier is
     * sufficient.
     */
    private fun permanentContract(boundCommit: String): PromotionEvidence.PermanentContract =
        PromotionEvidence.PermanentContract(
            contractId = "test",
            boundCommit = boundCommit,
            sourcePath = "test/Contract.kt",
            contentDigest = "0".repeat(64),
            fidelity = "HF0",
            oracle = OracleRef.Fitness("test.fixture", ":test-harness"),
        )

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
                    CycleEvent.ProbeFinished(ProbeOutcome.Verified("ev-1")),
                    CycleEvent.ProbeFinished(ProbeOutcome.Verified("ev-2")),
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
                    CycleEvent.ProbeFinished(ProbeOutcome.Verified("ev")),
                    CycleEvent.AllProbesObserved(commit),
                ),
            )
            assertEquals(CycleState.Observed(commit), obsState)
            val evidence = permanentContract(commit)
            val outcome = Cycle.decide(obsState, CycleEvent.PromoteRequested(evidence), obsCtx)
            val next = outcome as CycleOutcome.Next
            val promoted = next.nextState as CycleState.Promoted
            assertEquals(commit, promoted.promotedAtCommit)
            assertEquals(evidence, next.nextContext.latestReplacementEvidence)
        }

        @Test
        fun `Promoted closes with the close reason`() {
            val (state, _) = walk(
                start = CycleState.Draft,
                events = listOf(
                    CycleEvent.PrepareStarted,
                    CycleEvent.ProbeFinished(ProbeOutcome.Verified("ev")),
                    CycleEvent.AllProbesObserved(commit),
                    CycleEvent.PromoteRequested(permanentContract(commit)),
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
            val outcome = Cycle.decide(CycleState.Draft, CycleEvent.ProbeFinished(ProbeOutcome.Verified("ev")))
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
            // BQ4 v2 (typed refactor): the "no probe succeeded"
            // condition is now its own [CycleState.Refused.NoProbesSucceeded]
            // case, not a stringly-typed `Refused(reason)`. The
            // assertion is on the typed carrier; the receipt text
            // is a derived view of that carrier.
            val refused = next.nextState as CycleState.Refused.NoProbesSucceeded
            assertEquals(0, refused.observedCount)
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
                CycleEvent.ProbeFinished(ProbeOutcome.Verified("ok-1")),
            )
            assertTrue(okOutcome is CycleOutcome.Next) { "got $okOutcome" }
            val stillRunning = (okOutcome as CycleOutcome.Next).nextState
            assertEquals(CycleState.Running, stillRunning)
            // Now the failing probe: the cycle MUST refuse
            // immediately, NOT stay in Running. The new
            // decider refuses Failed on the ADT directly,
            // with a typed reason that names the failing
            // probe by its evidence ref and the underlying
            // reason.
            val badOutcome = Cycle.decide(
                stillRunning,
                CycleEvent.ProbeFinished(ProbeOutcome.Failed("bad-1", "test fail")),
            )
            val next = badOutcome as CycleOutcome.Next
            // BQ4 v2 (typed refactor): the per-probe failure is
            // now a typed [CycleState.Refused.ProbeFailed] with
            // its own payload (evidenceRef, failureReason). The
            // receipt text is derived from the carrier.
            val refused = next.nextState as CycleState.Refused.ProbeFailed
            assertEquals("bad-1", refused.evidenceRef)
            assertEquals("test fail", refused.failureReason)
            assertTrue(
                refused.reason.contains("bad-1") && refused.reason.contains("Failed") &&
                    refused.reason.contains("test fail"),
                "refusal reason must derive from the typed fields, got ${refused.reason}",
            )
        }

        @Test
        fun `PromoteRequested with a typed carrier advances to Promoted (BQ4 v2, the decider is a pure transition)`() {
            // BQ4 v2: the decider no longer string-prefix-validates
            // the evidence; the runner's PromotionEvidenceVerifier
            // has already done that before the event reached the
            // decider. A typed carrier is therefore guaranteed
            // valid by construction; the decider just records
            // it and advances the state. A blank carrier is
            // impossible: the PromotionEvidence data class init
            // refuses blanks, so the only way to get an empty
            // replacementEvidence is to construct an empty
            // PromotionEvidence (impossible by typed carrier)
            // — the data class init throws first.
            val (obsState, obsCtx) = walk(
                start = CycleState.Draft,
                events = listOf(
                    CycleEvent.PrepareStarted,
                    CycleEvent.ProbeFinished(ProbeOutcome.Verified("ev")),
                    CycleEvent.AllProbesObserved(commit),
                ),
            )
            val outcome = Cycle.decide(
                obsState,
                CycleEvent.PromoteRequested(permanentContract(commit)),
                obsCtx,
            )
            val next = outcome as CycleOutcome.Next
            assertTrue(next.nextState is CycleState.Promoted) {
                "got ${next.nextState}; the decider's only job is to advance"
            }
        }

        @Test
        fun `CloseRequested from Observed is IllegalTransition (BQ4 audit vertical)`() {
            // BQ4 audit (2026-10-16, vertical): a cycle that
            // observed with probes but did not request promotion
            // must NOT be able to skip `Promoted` by going
            // straight to `CloseRequested`. The only success path
            // is `Observed -> Promoted -> Closed`. A direct
            // `Observed -> CloseRequested` is therefore
            // `IllegalTransition`, regardless of whether the
            // accumulator has probes.
            val obsState = CycleState.Observed(commit)
            val outcome = Cycle.decide(
                obsState,
                CycleEvent.CloseRequested("clean cycle"),
                CycleContext(),
            )
            assertTrue(outcome is CycleOutcome.IllegalTransition) {
                "BQ4 audit: Observed -> CloseRequested MUST be IllegalTransition; got $outcome"
            }
        }

        @Test
        fun `a probe with non-zero exit code is Refused at the probe boundary`() {
            val running = (Cycle.decide(
                CycleState.Draft,
                CycleEvent.PrepareStarted,
            ) as CycleOutcome.Next).nextState
            val outcome = Cycle.decide(
                running,
                CycleEvent.ProbeFinished(ProbeOutcome.Failed("bad-1", "test fail")),
            )
            val next = outcome as CycleOutcome.Next
            assertTrue(next.nextState is CycleState.Refused) {
                "got ${next.nextState}; a non-zero exit code MUST refuse the cycle"
            }
        }

        @Test
        fun `a probe with blank evidenceRef is refused at the data class init`() {
            // The ProbeFinished data class requires a Verified
            // outcome with a non-blank evidenceRef. A blank
            // evidence ref gets the refusal at the call site,
            // before the decider is even consulted.
            val ex = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException::class.java,
            ) {
                ProbeFinished(ProbeOutcome.Verified(""))
            }
            assertNotNull(ex.message)
        }

        @Test
        fun `a probe with blank evidenceRef as an event is rejected by the decider`() {
            // The new shape refuses a blank evidence ref at the
            // ProbeFinished construction, which is what the
            // decider would have to do. Either way a blank
            // evidence ref cannot enter the accumulator.
            val running = (Cycle.decide(
                CycleState.Draft,
                CycleEvent.PrepareStarted,
            ) as CycleOutcome.Next).nextState
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
                Cycle.decide(running, CycleEvent.ProbeFinished(ProbeOutcome.Verified("")))
            }
        }
    }
}
