package dev.rubentxu.pipeline.v2.harness.validation

import dev.rubentxu.pipeline.v2.harness.catalog.ContractId
import dev.rubentxu.pipeline.v2.harness.evidence.ExpectedSuite
import dev.rubentxu.pipeline.v2.harness.evidence.ReportDecision
import dev.rubentxu.pipeline.v2.harness.evidence.ReportObservation
import dev.rubentxu.pipeline.v2.harness.plan.HarnessFidelity
import dev.rubentxu.pipeline.v2.harness.plan.ImpactDecision
import dev.rubentxu.pipeline.v2.harness.plan.Obligation

/**
 * Pure orchestrator: the [ImpactDecision] plus the [ReportObservation]s
 * collected by the runner become a [ValidationVerdict]. No I/O happens here.
 *
 * The verdict is closed:
 *  - `Discharged`: every obligation has matching Valid evidence at the right
 *    fidelity. The plan was run.
 *  - `Refused`: the plan or the evidence did not match. The cause is named
 *    so the caller knows whether to fix the catalogue, the runner, or the
 *    evidence.
 *
 * This is the function a CLI `validate` would call: "given the plan and the
 * evidence, is this change ready to ship?".
 */
object ValidationPlan {
    fun adjudicate(
        plan: ImpactDecision,
        observations: List<ObservedObligation>,
    ): ValidationVerdict {
        if (plan is ImpactDecision.Refused) {
            return ValidationVerdict.Refused(
                cause = ValidationVerdict.Cause.PLAN_REFUSED,
                detail = plan.reasons.joinToString { it.javaClass.simpleName },
            )
        }
        val selected = plan as ImpactDecision.Selected
        val byContract: Map<ContractId, ObservedObligation> = observations
            .associateBy { it.obligation.contract }

        val missing = mutableListOf<ContractId>()
        val misSized = mutableListOf<Pair<ContractId, ReportDecision.Cause>>()
        var observedMax: HarnessFidelity? = null

        for (obligation in selected.obligations) {
            val observed = byContract[obligation.contract]
            if (observed == null) {
                missing += obligation.contract
                continue
            }
            when (val verdict = observed.decision) {
                is ReportDecision.Valid -> {
                    if (verdict.executed < 1) {
                        misSized += obligation.contract to ReportDecision.Cause.EMPTY
                    }
                }
                is ReportDecision.Refused -> {
                    misSized += obligation.contract to verdict.cause
                }
            }
            observedMax = maxFidelity(observedMax, observed.observedFidelity)
        }

        return when {
            missing.isNotEmpty() -> ValidationVerdict.Refused(
                cause = ValidationVerdict.Cause.MISSING_OBLIGATION,
                detail = "missing evidence for: ${missing.joinToString { it.value }}",
            )
            misSized.isNotEmpty() -> ValidationVerdict.Refused(
                cause = ValidationVerdict.Cause.EVIDENCE_INVALID,
                detail = misSized.joinToString { (id, cause) -> "${id.value}=$cause" },
            )
            observedMax == null || !observedMax.reaches(selected.minFidelity) -> {
                ValidationVerdict.Refused(
                    cause = ValidationVerdict.Cause.FIDELITY_GAP,
                    detail = "plan requires ${selected.minFidelity}, observed ${observedMax}",
                )
            }
            else -> ValidationVerdict.Discharged(
                contracts = selected.obligations.map { it.contract }.toSet(),
                observedFidelity = observedMax,
            )
        }
    }

    private fun maxFidelity(
        current: HarnessFidelity?,
        next: HarnessFidelity,
    ): HarnessFidelity = if (current == null || current.ordinal >= next.ordinal) current ?: next else next
}

/** What the runner reported for one obligation. */
data class ObservedObligation(
    val obligation: Obligation,
    val observedFidelity: HarnessFidelity,
    val decision: ReportDecision,
    val observation: ReportObservation?,
    val expected: ExpectedSuite,
)

/** The final verdict. Closed: there is no "default" branch. */
sealed interface ValidationVerdict {
    data class Discharged(
        val contracts: Set<ContractId>,
        val observedFidelity: HarnessFidelity,
    ) : ValidationVerdict

    data class Refused(
        val cause: Cause,
        val detail: String,
    ) : ValidationVerdict

    enum class Cause {
        /** The plan itself was refused (unclassified production, catalogue gap, etc.). */
        PLAN_REFUSED,

        /** The runner did not report any observation for one or more obligations. */
        MISSING_OBLIGATION,

        /** The runner reported an observation, but the evidence reader refused it. */
        EVIDENCE_INVALID,

        /** The runner ran at a lower fidelity than the plan required. */
        FIDELITY_GAP,
    }
}
