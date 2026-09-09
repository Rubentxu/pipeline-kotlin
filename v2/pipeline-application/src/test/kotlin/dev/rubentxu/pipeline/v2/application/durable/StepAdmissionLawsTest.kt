package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.durable.StepAdmissionLaws.AdmissionReport
import dev.rubentxu.pipeline.v2.application.durable.StepAdmissionLaws.ReconciliationResolution
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pure unit tests for [StepAdmissionLaws]. WU-1 introduces the value-object and
 * the typed ADT; the spine integration is WU-3..5. The test class deliberately
 * does not touch the coordinator, the factory, the family router, or
 * `CommonExecutionBoundary`.
 */
class StepAdmissionLawsTest {

    @Test
    fun `fresh resolution maps to C1FreshExecutedOnce`() {
        val report = StepAdmissionLaws.observe(ReconciliationResolution.Fresh, 1)
        assertEquals(AdmissionReport.C1FreshExecutedOnce(1), report)
    }

    @Test
    fun `reuseCompleted resolution maps to C2ReuseNoExecute`() {
        val report = StepAdmissionLaws.observe(ReconciliationResolution.ReuseCompleted, 0)
        assertEquals(AdmissionReport.C2ReuseNoExecute(0), report)
    }

    @Test
    fun `rejectedDecode resolution maps to C3DecodeRejectNoExecute`() {
        val report = StepAdmissionLaws.observe(ReconciliationResolution.RejectedDecode, 0)
        assertEquals(AdmissionReport.C3DecodeRejectNoExecute(0), report)
    }

    @Test
    fun `diverged resolution maps to C4DivergenceNoExecute`() {
        val report = StepAdmissionLaws.observe(ReconciliationResolution.Diverged, 0)
        assertEquals(AdmissionReport.C4DivergenceNoExecute(0), report)
    }

    @Test
    fun `schemaPrecedence resolution maps to C5SchemaPrecedenceOverReuse`() {
        val report = StepAdmissionLaws.observe(ReconciliationResolution.SchemaPrecedence, 0)
        assertEquals(AdmissionReport.C5SchemaPrecedenceOverReuse(0), report)
    }

    @Test
    fun `overlayApplied resolution maps to C6OverlayAppliedOnReuse`() {
        val report = StepAdmissionLaws.observe(ReconciliationResolution.OverlayApplied, 1)
        assertEquals(AdmissionReport.C6OverlayAppliedOnReuse(1), report)
    }

    @Test
    fun `other resolution maps to Other catch-all`() {
        val report = StepAdmissionLaws.observe(ReconciliationResolution.Other, 0)
        assertEquals(AdmissionReport.Other(0), report)
    }
}
