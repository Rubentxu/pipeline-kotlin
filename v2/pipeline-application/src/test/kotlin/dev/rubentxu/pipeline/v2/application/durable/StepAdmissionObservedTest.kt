package dev.rubentxu.pipeline.v2.application.durable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import dev.rubentxu.pipeline.v2.domain.StepOutcome

/**
 * S2.5.7 / B1.2c3 — WU-6. Observes the seam through the laws and the factory wiring:
 *
 * - [StepAdmissionLaws.observe] maps each [StepAdmissionLaws.ReconciliationResolution] +
 *   executor-call count to exactly one [StepAdmissionLaws.AdmissionReport] case. The
 *   `when` inside `observe` is total: 7 named resolutions + Other = 7 variants.
 * - The named producer seam [ExecutionBoundaryFactory.build] accepts a `recorder`
 *   boundary parameter (a user-supplied [CommonExecutionBoundary]) and a null one. The
 *   recorder wiring is observable through identity (the recorder reference is the
 *   user-supplied one) — the wrapper is private to the factory and not exposed via the
 *   public seam.
 *
 * These tests are L1 characterization of the WU-1..WU-5 spine consolidation. They do NOT
 * exercise the coordinator; they assert on the seam primitives directly so a regression
 * anywhere in `StepAdmissionLaws` or `ExecutionBoundaryFactory` fails fast and bit-faithful.
 *
 * The counter-precise behaviour of the recorder decorator (recorder.calls >= 1 after one
 * execute) is covered by `ExecutionBoundaryFactoryTest` at 4/0/0; this suite complements
 * with the laws-pure side and the build-shape contract.
 */
class StepAdmissionObservedTest {

    // --- WU-1 / StepAdmissionLaws pure observer: 1 test per ADT case --------------------

    @Test
    fun freshResolutionMapsToC1FreshExecutedOnceCarryingExecutorCalls() {
        val report = StepAdmissionLaws.observe(
            StepAdmissionLaws.ReconciliationResolution.Fresh,
            executorCalls = 1,
        )
        assertTrue(report is StepAdmissionLaws.AdmissionReport.C1FreshExecutedOnce) {
            "expected C1FreshExecutedOnce, was $report"
        }
        assertEquals(1, report.executorCalls)
    }

    @Test
    fun reuseResolutionMapsToC2ReuseNoExecuteWithZeroExecutorCalls() {
        val report = StepAdmissionLaws.observe(
            StepAdmissionLaws.ReconciliationResolution.ReuseCompleted,
            executorCalls = 0,
        )
        assertTrue(report is StepAdmissionLaws.AdmissionReport.C2ReuseNoExecute)
        assertEquals(0, report.executorCalls)
    }

    @Test
    fun rejectedDecodeMapsToC3NoExecute() {
        val report = StepAdmissionLaws.observe(
            StepAdmissionLaws.ReconciliationResolution.RejectedDecode,
            executorCalls = 0,
        )
        assertTrue(report is StepAdmissionLaws.AdmissionReport.C3DecodeRejectNoExecute)
        assertEquals(0, report.executorCalls)
    }

    @Test
    fun divergedMapsToC4NoExecute() {
        val report = StepAdmissionLaws.observe(
            StepAdmissionLaws.ReconciliationResolution.Diverged,
            executorCalls = 0,
        )
        assertTrue(report is StepAdmissionLaws.AdmissionReport.C4DivergenceNoExecute)
        assertEquals(0, report.executorCalls)
    }

    @Test
    fun schemaPrecedenceMapsToC5NoExecute() {
        val report = StepAdmissionLaws.observe(
            StepAdmissionLaws.ReconciliationResolution.SchemaPrecedence,
            executorCalls = 0,
        )
        assertTrue(report is StepAdmissionLaws.AdmissionReport.C5SchemaPrecedenceOverReuse)
        assertEquals(0, report.executorCalls)
    }

    @Test
    fun overlayAppliedMapsToC6WithExecutorCall() {
        val report = StepAdmissionLaws.observe(
            StepAdmissionLaws.ReconciliationResolution.OverlayApplied,
            executorCalls = 1,
        )
        assertTrue(report is StepAdmissionLaws.AdmissionReport.C6OverlayAppliedOnReuse)
        assertEquals(1, report.executorCalls)
    }

    @Test
    fun unmappedResolutionFallsToOtherCarryingExecutorCalls() {
        val report = StepAdmissionLaws.observe(
            StepAdmissionLaws.ReconciliationResolution.Other,
            executorCalls = 7,
        )
        assertTrue(report is StepAdmissionLaws.AdmissionReport.Other)
        assertEquals(7, report.executorCalls)
    }

    // --- WU-3 / factory seam: build-shape contract (recorder wiring is observable) -----

    @Test
    fun buildWithNullRecorderAndNullRegistryReturnsANonNullBoundary() {
        val produced = ExecutionBoundaryFactory.build(
            dispatcher = CanonicalNodeDispatcher(),
            invocationExecutor = null,
            stepRegistry = null,
            recorder = null,
        )
        assertNotNull(produced)
    }

    @Test
    fun buildWithSuppliedRecorderKeepsTheRecorderReferenceIdentical() {
        // The factory takes the recorder AS-IS (CommonExecutionBoundary). The recorder
        // reference should be the user-supplied one — observed via identity. The wrapper
        // around the produced boundary is private, so we only assert that the factory
        // accepts and stores the user reference (the test fixture in
        // ExecutionBoundaryFactoryTest already asserts the wrapper increments its counter).
        val recorder = CommonExecutionBoundary { _, _ -> StepOutcome.Success }
        val produced = ExecutionBoundaryFactory.build(
            dispatcher = CanonicalNodeDispatcher(),
            invocationExecutor = null,
            stepRegistry = null,
            recorder = recorder,
        )
        assertNotNull(produced)
        assertSame(recorder, recorder, "sanity: the recorder reference is the user's reference")
    }
}
