package dev.rubentxu.pipeline.v2.runtime.explain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The classifier decides what a run's facts mean, and the dangerous failures
 * are interpretations an operator would act on: a run without a terminal
 * event read as ended, an inference presented as an observed fact, an
 * outcome word re-derived instead of quoted. Each test pins one refusal.
 */
class RunDiagnosisTest {

    private val evidence = EvidenceRef("event-journal", 1)

    private fun ended(outcome: String) = Fact.RunEnded(outcome, 100, evidence)

    private fun explain(facts: DiagnosisFacts): Diagnosis.Explained =
        classify(facts, Completeness.EventsOnly) as Diagnosis.Explained

    @Test
    fun `a run without a terminal event is open, and open is never a success word`() {
        val outcome = explain(
            DiagnosisFacts(
                runEnded = null,
                firstFailure = null,
                neverClosedStep = null,
                lastObservedAtMs = 80,
                lastSequence = 9,
            ),
        )

        assertEquals("still-open", outcome.status)
        assertTrue(!outcome.status.contains("success"))
        assertTrue(outcome.findings.none { it is Fact.RunEnded })
    }

    @Test
    fun `the terminal fact quotes the outcome word instead of re-deriving it`() {
        val outcome = explain(DiagnosisFacts(ended("unstable"), null, null, 100, 12))

        assertEquals("ended-unstable", outcome.status)
        assertEquals(listOf(ended("unstable")).map { it.code }, outcome.findings.map { it.code })
    }

    @Test
    fun `a failure is a fact that precedes the terminal fact, and each names its evidence`() {
        val failure = Fact.StepBroke("SCRIPT", "deploy", "exit 1", EvidenceRef("event-journal", 8))
        val terminal = ended("failure")

        val outcome = explain(DiagnosisFacts(terminal, failure, null, 100, 12))

        assertEquals("ended-failure", outcome.status)
        assertEquals(listOf("step-broke", "run-ended"), outcome.findings.map { it.code })
        assertTrue(outcome.findings.all { it.evidence.sequence > 0 })
    }

    @Test
    fun `an open step is reported as an inference beside the facts, never as a fact`() {
        val dangling = Inference.StepNeverClosed("deploy", EvidenceRef("event-journal", 5))

        val outcome = explain(DiagnosisFacts(null, null, dangling, 80, 9))

        assertTrue(outcome.findings.contains(dangling))
        assertTrue(outcome.findings.filterIsInstance<Inference.RunStillOpen>().isNotEmpty())
        assertTrue(outcome.findings.none { it is Fact })
    }

    @Test
    fun `completeness states which lanes were consulted, not which were assumed`() {
        val outcome = explain(DiagnosisFacts(ended("success"), null, null, 100, 12))

        assertEquals(LaneState.Read, outcome.completeness.eventLane)
        assertEquals(LaneState.NotConsulted, outcome.completeness.outputLane)
        assertEquals(LaneState.NotConsulted, outcome.completeness.leaseLane)
    }
}
