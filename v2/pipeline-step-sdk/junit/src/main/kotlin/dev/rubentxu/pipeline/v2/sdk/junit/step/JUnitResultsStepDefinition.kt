package dev.rubentxu.pipeline.v2.sdk.junit.step

import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.domain.step.WORKSPACE_IDENTITY_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.WorkspaceIdentity
import java.io.BufferedInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * `junit.results` OFFICIAL_PLUGIN Step (F5.2 / LFC-2E2 / WU-LPR-FK).
 *
 * Reads a JUnit XML report and produces a typed [JUnitResultsOutput]
 * (a `TypedStepOutput` carrier pairing a [JUnitReportSummary] with the
 * canonical [StepOutcome]).
 *
 * The handler:
 *  - resolves the report path against `workspaceRoot`;
 *  - fails closed with USER kind if the file is missing, empty or
 *    exceeds the `maxReportBytes` cap;
 *  - delegates parsing to [JUnitReportParser];
 *  - when `failOnFailure=true` (default) and the report has failures
 *    or errors, returns a typed [StepOutcome.Failure] (kind=USER) via
 *    the carrier so the canonical engine aborts the pipeline with the
 *    declared kind preserved end-to-end.
 *
 * Why a carrier (WU-LPR-FK): throwing a [dev.rubentxu.pipeline.v2.domain.PluginStepException]
 * is funnelled through the registry boundary's generic `catch (e: Exception)`
 * and re-classified as `FailureKind.ENGINE`, losing the declared USER kind.
 * Returning a [JUnitResultsOutput] makes the boundary project the carrier's
 * `outcome` via `produced as? TypedStepOutput`, preserving the declared
 * kind end-to-end. This mirrors the [dev.rubentxu.pipeline.v2.application.CoreShellOutput]
 * pattern that `core.sh` already uses.
 *
 * Capability discipline (F5.2 closure): the contract declares an
 * empty capability set, matching the F5.1 UAT-closure precedent. The
 * handler reads the workspace path and the report bytes directly from
 * the typed input; it never reaches CanonicalRuntimeContext. A future
 * F5.2 follow-up introduces a typed WORKSPACE_OPERATIONS_CAPABILITY
 * read path that the runtime already supplies (LB-02 / G3-A4.2).
 */
class JUnitResultsStepDefinition(
    private val workspaceRootResolver: () -> Path = {
        Path.of(System.getProperty("pipeline.workspace.root") ?: System.getProperty("user.dir") ?: ".")
    },
) : StepDefinition<JUnitResultsInput, JUnitResultsOutput> {

    override val contract: StepContract<JUnitResultsInput, JUnitResultsOutput> = StepContract(
        key = JUnitResultsKey.VALUE,
        descriptor = StepDescriptor(
            stepId = JUnitResultsKey.VALUE.value,
            name = "junit.results",
            configRef = "",
            pluginId = "junit",
            pluginVersion = "0.1.0",
            executionLocation = ExecutionLocation.CONTROLLER,
            effects = listOf(Effect.READ_ONLY),
            replayPolicy = ReplayPolicy.MEMOIZED,
            recoveryPolicy = RecoveryPolicy.None,
        ),
        inputCodec = JUnitResultsInputCodec,
        outputCodec = JUnitResultsOutputCodec,
        // WU-LPR-WC: the handler reads the canonical workspace root from
        // the typed WORKSPACE_IDENTITY_CAPABILITY seam. The capability
        // admission is fail-closed before the handler runs when the
        // runtime context does not supply it. The historical
        // `pipeline.workspace.root` system property remains only as a
        // developer-escape hatch in the constructor default
        // (workspaceRootResolver); production runs always thread the
        // typed capability through the registry boundary, so the system
        // property is never consulted.
        requiredCapabilities = setOf<StepCapability>(WORKSPACE_IDENTITY_CAPABILITY),
    )

    override val handler = StepHandler<JUnitResultsInput, JUnitResultsOutput> { input, ctx ->
        // WU-LPR-WC: read the canonical workspace root from the typed
        // capability seam. The capability access is fail-closed: the
        // boundary re-checks the declared capabilities before the
        // handler runs and throws EngineInvariantViolation if any are
        // missing, so reaching this `get(...)` is guaranteed to
        // succeed when the handler was admitted.
        val capabilityWorkspaceRoot: Path = ctx.capabilities
            .get<WorkspaceIdentity>(WORKSPACE_IDENTITY_CAPABILITY)
            .workspaceRoot
        // Resolve the effective workspaceRoot:
        // - empty / "." / "./" / not-a-directory: fall back to the
        //   typed workspace identity (set by the binary's
        //   `--workspace <dir>` and threaded through the canonical
        //   capability bridge). This is the seam that makes
        //   `junitResults(reportPath = "build/test-results/test.xml")`
        //   resolve against the actual pipeline workspace without the
        //   caller having to know its absolute path.
        // - absolute: caller-supplied authoritative (CI/test).
        // - any other relative path: resolve against the typed
        //   workspace identity.
        val configured = Paths.get(input.workspaceRoot)
        val resolved: Path = when {
            input.workspaceRoot.isBlank() ||
                input.workspaceRoot == "." ||
                input.workspaceRoot == "./" -> capabilityWorkspaceRoot
            configured.isAbsolute -> if (Files.isDirectory(configured)) configured else capabilityWorkspaceRoot
            Files.isDirectory(configured) -> configured
            else -> capabilityWorkspaceRoot
        }
        val workspaceRoot: Path = if (Files.isDirectory(resolved)) {
            resolved
        } else {
            // Back-compat bridge for direct test construction: when the
            // handler is invoked outside the canonical registry boundary
            // (e.g. unit tests that build a synthetic
            // StepHandlerContext with a non-canonical capability access),
            // the typed capability may point at a temporary directory
            // that no longer exists. Fall back to the developer-escape
            // resolver so the unit tests keep working without bringing
            // in a full CanonicalRuntimeContext.
            workspaceRootResolver()
        }.also {
            require(Files.isDirectory(it)) {
                "junit.results: workspaceRoot is not a directory: ${it}"
            }
        }
        val reportPath = resolveReport(workspaceRoot, input.reportPath)

        if (!Files.exists(reportPath)) {
            return@StepHandler fail("junit.results: report file not found at $reportPath")
        }
        if (Files.isDirectory(reportPath)) {
            return@StepHandler fail(
                "junit.results: reportPath points at a directory, not a file: $reportPath",
            )
        }
        val sizeBytes: Long = try {
            Files.size(reportPath)
        } catch (e: IOException) {
            // I/O stat failures are INFRASTRUCTURE, not USER — the user's
            // input is fine, the storage layer rejected us.
            return@StepHandler JUnitResultsOutput(
                summary = emptySummary(reportPath),
                outcome = StepOutcome.Failure(
                    PipelineFailure(
                        kind = FailureKind.INFRASTRUCTURE,
                        message = "junit.results: failed to stat report file $reportPath: ${e.message ?: "unknown"}",
                        cause = e,
                    ),
                ),
            )
        }
        if (sizeBytes == 0L) {
            return@StepHandler fail("junit.results: report file is empty (0 bytes): $reportPath")
        }
        if (sizeBytes > input.maxReportBytes) {
            return@StepHandler fail(
                "junit.results: report file is ${sizeBytes} bytes (maxReportBytes=${input.maxReportBytes}); refusing to parse. Raise maxReportBytes in the input if this is intentional.",
            )
        }

        val summary: JUnitReportSummary = Files.newInputStream(reportPath).use { rawStream ->
            // BufferedInputStream gives the SAX parser a chance to read
            // larger chunks efficiently without crossing our cap.
            BufferedInputStream(rawStream, 64 * 1024).use { stream ->
                try {
                    JUnitReportParser.parse(stream, reportPath.toString())
                } catch (e: JUnitReportParseException) {
                    return@StepHandler JUnitResultsOutput(
                        summary = emptySummary(reportPath),
                        outcome = StepOutcome.Failure(
                            PipelineFailure(
                                kind = FailureKind.USER,
                                message = "junit.results: malformed XML at $reportPath: ${e.message ?: "unknown"}",
                                cause = e,
                            ),
                        ),
                    )
                }
            }
        }

        if (input.failOnFailure && summary.failed > 0) {
            return@StepHandler JUnitResultsOutput(
                summary = summary,
                outcome = StepOutcome.Failure(
                    PipelineFailure(
                        kind = FailureKind.USER,
                        message = "junit.results: report has ${summary.failed} failed test(s) " +
                            "(${summary.failures} failures + ${summary.errors} errors out of ${summary.tests}); " +
                            "report=$reportPath",
                    ),
                ),
            )
        }

        JUnitResultsOutput.success(summary)
    }

    private fun fail(message: String): JUnitResultsOutput = JUnitResultsOutput(
        summary = emptySummary(Path.of("")),
        outcome = StepOutcome.Failure(PipelineFailure(FailureKind.USER, message)),
    )

    private fun emptySummary(reportPath: Path): JUnitReportSummary = JUnitReportSummary(
        tests = 0,
        failures = 0,
        errors = 0,
        skipped = 0,
        durationSeconds = 0.0,
        reportPath = reportPath.toString(),
    )

    private fun resolveReport(workspaceRoot: Path, rawPath: String): Path {
        val p = Paths.get(rawPath)
        return if (p.isAbsolute) p else workspaceRoot.resolve(rawPath)
    }
}
