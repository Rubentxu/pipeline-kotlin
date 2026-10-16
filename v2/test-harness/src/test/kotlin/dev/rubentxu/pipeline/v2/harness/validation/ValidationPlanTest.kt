package dev.rubentxu.pipeline.v2.harness.validation

import dev.rubentxu.pipeline.v2.harness.catalog.CapabilityId
import dev.rubentxu.pipeline.v2.harness.catalog.ContractId
import dev.rubentxu.pipeline.v2.harness.evidence.ExpectedSuite
import dev.rubentxu.pipeline.v2.harness.evidence.ReportDecision
import dev.rubentxu.pipeline.v2.harness.evidence.ReportObservation
import dev.rubentxu.pipeline.v2.harness.plan.HarnessFidelity
import dev.rubentxu.pipeline.v2.harness.plan.ImpactDecision
import dev.rubentxu.pipeline.v2.harness.plan.Obligation
import dev.rubentxu.pipeline.v2.harness.plan.Refusal
import dev.rubentxu.pipeline.v2.harness.provenance.CommitRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Contract tests for [ValidationPlan] — the orchestrator that combines the
 * impact plan and the runner's evidence into a single verdict.
 */
class ValidationPlanTest {

    private val commit = CommitRef("0123456789abcdef0123456789abcdef01234567")
    private val expected = ExpectedSuite(
        contract = ContractId("contract.x"),
        suiteFqcn = "owner.ContractTest",
        expectedCommit = commit,
        invocation = "build-1",
    )
    private val cleanObs = ReportObservation(
        suiteFqcn = "owner.ContractTest",
        tests = 4,
        skipped = 0,
        failures = 0,
        errors = 0,
        reportedCommit = commit,
    )

    @Test
    fun `Refused plan is Refused by the orchestrator`() {
        val plan = ImpactDecision.Refused(listOf(Refusal.CatalogueGap("src/x.kt", "no owner")))
        val verdict = ValidationPlan.adjudicate(plan, emptyList())
        assertTrue(verdict is ValidationVerdict.Refused) { "got $verdict" }
        assertEquals(ValidationVerdict.Cause.PLAN_REFUSED, (verdict as ValidationVerdict.Refused).cause)
    }

    @Test
    fun `Selected plan with no observations is Refused MISSING_OBLIGATION`() {
        val obligation = Obligation(
            contract = ContractId("contract.x"),
            capability = CapabilityId("c.x"),
            minimumFidelity = HarnessFidelity.HF0,
            requiresInstalledBinary = false,
            requiresExternalCertifier = false,
            sourcePath = "src/x.kt",
        )
        val plan = ImpactDecision.Selected(setOf(obligation), HarnessFidelity.HF0)
        val verdict = ValidationPlan.adjudicate(plan, emptyList())
        assertTrue(verdict is ValidationVerdict.Refused) { "got $verdict" }
        assertEquals(
            ValidationVerdict.Cause.MISSING_OBLIGATION,
            (verdict as ValidationVerdict.Refused).cause,
        )
    }

    @Test
    fun `Selected plan with valid evidence is Discharged`() {
        val obligation = Obligation(
            contract = ContractId("contract.x"),
            capability = CapabilityId("c.x"),
            minimumFidelity = HarnessFidelity.HF0,
            requiresInstalledBinary = false,
            requiresExternalCertifier = false,
            sourcePath = "src/x.kt",
        )
        val plan = ImpactDecision.Selected(setOf(obligation), HarnessFidelity.HF0)
        val observed = ObservedObligation(
            obligation = obligation,
            observedFidelity = HarnessFidelity.HF1,
            decision = ReportDecision.Valid(executed = 4, skipped = 0),
            observation = cleanObs,
            expected = expected,
        )
        val verdict = ValidationPlan.adjudicate(plan, listOf(observed))
        assertTrue(verdict is ValidationVerdict.Discharged) { "got $verdict" }
        val d = verdict as ValidationVerdict.Discharged
        assertEquals(setOf(ContractId("contract.x")), d.contracts)
        assertEquals(HarnessFidelity.HF1, d.observedFidelity)
    }

    @Test
    fun `Selected plan with empty evidence is Refused EVIDENCE_INVALID`() {
        val obligation = Obligation(
            contract = ContractId("contract.x"),
            capability = CapabilityId("c.x"),
            minimumFidelity = HarnessFidelity.HF0,
            requiresInstalledBinary = false,
            requiresExternalCertifier = false,
            sourcePath = "src/x.kt",
        )
        val plan = ImpactDecision.Selected(setOf(obligation), HarnessFidelity.HF0)
        val observed = ObservedObligation(
            obligation = obligation,
            observedFidelity = HarnessFidelity.HF0,
            decision = ReportDecision.Refused(ReportDecision.Cause.EMPTY),
            observation = null,
            expected = expected,
        )
        val verdict = ValidationPlan.adjudicate(plan, listOf(observed))
        assertTrue(verdict is ValidationVerdict.Refused) { "got $verdict" }
        assertEquals(
            ValidationVerdict.Cause.EVIDENCE_INVALID,
            (verdict as ValidationVerdict.Refused).cause,
        )
    }

    @Test
    fun `lower observed fidelity than required is Refused FIDELITY_GAP`() {
        val obligation = Obligation(
            contract = ContractId("contract.x"),
            capability = CapabilityId("c.x"),
            minimumFidelity = HarnessFidelity.HF2,
            requiresInstalledBinary = false,
            requiresExternalCertifier = false,
            sourcePath = "src/x.kt",
        )
        val plan = ImpactDecision.Selected(setOf(obligation), HarnessFidelity.HF2)
        val observed = ObservedObligation(
            obligation = obligation,
            observedFidelity = HarnessFidelity.HF0,
            decision = ReportDecision.Valid(executed = 4, skipped = 0),
            observation = cleanObs,
            expected = expected,
        )
        val verdict = ValidationPlan.adjudicate(plan, listOf(observed))
        assertTrue(verdict is ValidationVerdict.Refused) { "got $verdict" }
        assertEquals(
            ValidationVerdict.Cause.FIDELITY_GAP,
            (verdict as ValidationVerdict.Refused).cause,
        )
    }
}
