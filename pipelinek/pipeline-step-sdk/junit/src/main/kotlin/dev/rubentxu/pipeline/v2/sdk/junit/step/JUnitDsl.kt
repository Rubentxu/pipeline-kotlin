package dev.rubentxu.pipeline.v2.sdk.junit.step

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.dsl.StageScope

/**
 * Ergonomic Kotlin DSL extension for the `junit.results` Step
 * (F5.2 / LFC-2E2).
 *
 * Same shape as the SCM/Git façade:
 *  - constructs the typed [JUnitResultsInput] from positional/named
 *    arguments;
 *  - encodes it with the plugin's own [JUnitResultsInputCodec];
 *  - lowers to the generic [StageScope.registryStep] primitive.
 *
 * @param reportPath      Workspace-relative or absolute path to the XML.
 * @param workspaceRoot   The directory the relative path resolves
 *                        against. Usually the pipeline --workspace.
 * @param failOnFailure   When true (default), the Step fails the
 *                        pipeline if the report has any failures /
 *                        errors. When false, the Step always SUCCEEDS
 *                        but still emits the typed summary.
 * @param maxReportBytes  Hard cap on the report file size we will
 *                        read (default 10 MiB).
 */
fun StageScope.junitResults(
    reportPath: String,
    workspaceRoot: String,
    failOnFailure: Boolean = true,
    maxReportBytes: Long = JUnitResultsInput.DEFAULT_MAX_REPORT_BYTES,
) {
    val input = JUnitResultsInput(
        reportPath = reportPath,
        workspaceRoot = workspaceRoot,
        failOnFailure = failOnFailure,
        maxReportBytes = maxReportBytes,
    )
    val encoded: EncodedStepValue = JUnitResultsInputCodec.encode(input)
    registryStep(
        stepKey = junitResultsStepKey(),
        encodedInput = encoded,
    )
}

fun junitResultsStepKey(): PluginStepId = JUnitResultsKey.VALUE
