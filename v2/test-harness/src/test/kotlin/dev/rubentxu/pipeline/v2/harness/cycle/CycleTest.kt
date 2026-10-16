package dev.rubentxu.pipeline.v2.harness.cycle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class CycleTest {

    private val commit = "0123456789abcdef0123456789abcdef01234567"

    @Nested
    inner class HappyPath {
        @Test
        fun `Draft to Running on PrepareStarted`() {
            val outcome = Cycle.decide(CycleState.Draft, CycleEvent.PrepareStarted)
            assertEquals(CycleState.Running, (outcome as CycleOutcome.Next).state)
        }

        @Test
        fun `Running stays on each ProbeFinished, then Observed on AllProbesObserved`() {
            var state: CycleState = CycleState.Draft
            state = (Cycle.decide(state, CycleEvent.PrepareStarted) as CycleOutcome.Next).state
            assertEquals(CycleState.Running, state)
            state = (Cycle.decide(state, CycleEvent.ProbeFinished(0, "ev-1")) as CycleOutcome.Stay).state
            assertEquals(CycleState.Running, state)
            state = (Cycle.decide(state, CycleEvent.ProbeFinished(0, "ev-2")) as CycleOutcome.Stay).state
            assertEquals(CycleState.Running, state)
            state = (Cycle.decide(state, CycleEvent.AllProbesObserved(commit)) as CycleOutcome.Next).state
            assertEquals(CycleState.Observed(commit), state)
        }

        @Test
        fun `Observed to Promoted carries the commit`() {
            val observed = CycleState.Observed(commit)
            val outcome = Cycle.decide(observed, CycleEvent.PromoteRequested("permanent contract"))
            val promoted = (outcome as CycleOutcome.Next).state as CycleState.Promoted
            assertEquals(commit, promoted.promotedAtCommit)
        }

        @Test
        fun `Promoted closes with the close reason`() {
            val promoted = CycleState.Promoted(commit)
            val outcome = Cycle.decide(promoted, CycleEvent.CloseRequested("temporary probes retired"))
            val refused = (outcome as CycleOutcome.Next).state as CycleState.Refused
            assertEquals("temporary probes retired", refused.reason)
        }
    }

    @Nested
    inner class RefusalPaths {
        @Test
        fun `Draft can close directly with a close reason`() {
            val outcome = Cycle.decide(CycleState.Draft, CycleEvent.CloseRequested("manifest invalid"))
            val refused = (outcome as CycleOutcome.Next).state as CycleState.Refused
            assertEquals("manifest invalid", refused.reason)
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
                val outcome = Cycle.decide(state, CycleEvent.ExternalBlockerRaised("harness verdict pending"))
                assertTrue(outcome is CycleOutcome.Next) { "got $outcome for $state" }
                assertEquals(
                    CycleState.BlockedExternal("harness verdict pending"),
                    (outcome as CycleOutcome.Next).state,
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

        @Test
        fun `Running cannot be re-prepared`() {
            val outcome = Cycle.decide(CycleState.Running, CycleEvent.PrepareStarted)
            assertTrue(outcome is CycleOutcome.IllegalTransition) { "got $outcome" }
        }

        @Test
        fun `terminal Refused rejects any further event`() {
            val refused = CycleState.Refused("done")
            for (event in listOf<CycleEvent>(
                CycleEvent.PrepareStarted,
                CycleEvent.ProbeFinished(0, "ev"),
                CycleEvent.AllProbesObserved(commit),
                CycleEvent.PromoteRequested("r"),
                CycleEvent.CloseRequested("c"),
                CycleEvent.ExternalBlockerRaised("b"),
            )) {
                val outcome = Cycle.decide(refused, event)
                assertTrue(outcome is CycleOutcome.IllegalTransition) { "got $outcome for $event" }
            }
        }

        @Test
        fun `terminal BlockedExternal rejects any further event`() {
            val blocked = CycleState.BlockedExternal("harness")
            for (event in listOf<CycleEvent>(
                CycleEvent.PrepareStarted,
                CycleEvent.ProbeFinished(0, "ev"),
                CycleEvent.AllProbesObserved(commit),
                CycleEvent.PromoteRequested("r"),
                CycleEvent.CloseRequested("c"),
                CycleEvent.ExternalBlockerRaised("b"),
            )) {
                val outcome = Cycle.decide(blocked, event)
                assertTrue(outcome is CycleOutcome.IllegalTransition) { "got $outcome for $event" }
            }
        }
    }
}
