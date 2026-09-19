package dev.rubentxu.pipeline.v2.sdk.junit.step

import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.TypedStepOutput

/**
 * Typed output carrier for `junit.results` (F5.2 / WU-LPR-FK).
 *
 * Mirrors the LB-02 / G3-A4.3 pattern that [dev.rubentxu.pipeline.v2.application.CoreShellOutput]
 * established for `core.sh`: the carrier pairs the user-visible
 * [JUnitReportSummary] with the canonical [StepOutcome] so the
 * [dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionBoundary]
 * can project `outcome` via `produced as? TypedStepOutput` without ever
 * inspecting a concrete `StepKey`.
 *
 * Without this carrier, every handler-thrown `PluginStepException` would
 * be funnelled through the boundary's generic `catch (e: Exception)`
 * and re-classified as `FailureKind.ENGINE`, losing the original USER
 * declaration (see `docs/v2/07-uat/F5_2_JUNIT_CLOSURE_RECEIPT.md` and
 * the WU-LPR-FK characterisation).
 *
 * `JUnitResultsOutput` is ALWAYS emitted — on success and on typed
 * failure alike — so that downstream consumers and the durable journal
 * receive a single, structured record of what happened, regardless of
 * the outcome. The `outcome` field is the single source of truth for
 * pass/fail classification.
 */
data class JUnitResultsOutput(
    val summary: JUnitReportSummary,
    override val outcome: StepOutcome,
) : TypedStepOutput {
    companion object {
        /** Convenience: success carrier for the given summary. */
        fun success(summary: JUnitReportSummary): JUnitResultsOutput =
            JUnitResultsOutput(summary, StepOutcome.Success)
    }
}
