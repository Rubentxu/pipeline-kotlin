package dev.rubentxu.pipeline.v2.sdk.junit.step

import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.PluginStepException
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import java.io.BufferedInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * `junit.results` OFFICIAL_PLUGIN Step (F5.2 / LFC-2E2).
 *
 * Reads a JUnit XML report and produces a typed [JUnitReportSummary].
 * The handler:
 *  - resolves the report path against `workspaceRoot`;
 *  - fails closed with USER kind if the file is missing, empty or
 *    exceeds the `maxReportBytes` cap;
 *  - delegates parsing to [JUnitReportParser];
 *  - when `failOnFailure=true` (default) and the report has failures
 *    or errors, throws a [PluginStepException] with USER kind so the
 *    canonical engine aborts the pipeline with a typed failure (no
 *    false successes).
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
) : StepDefinition<JUnitResultsInput, JUnitReportSummary> {

    override val contract: StepContract<JUnitResultsInput, JUnitReportSummary> = StepContract(
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
        outputCodec = JUnitReportSummaryCodec,
        requiredCapabilities = emptySet(),
    )

    override val handler = StepHandler<JUnitResultsInput, JUnitReportSummary> { input, _ ->
        // Resolve the effective workspaceRoot:
        // - absolute: caller-supplied authoritative (CI/test).
        // - empty / "." / "./" / not-a-directory: fall back to the
        //   `pipeline.workspace.root` system property (set by the binary
        //   when --workspace is provided) and finally to the process
        //   cwd. This is the seam that makes
        //   `junitResults(reportPath = "build/test-results/test.xml")`
        //   resolve against the actual pipeline workspace without the
        //   caller having to know its absolute path.
        // - any other relative path: resolve against the process cwd
        //   (preserves existing test-only behaviour where the harness
        //   sets `workspaceRoot = "test/..."`).
        val configured = Paths.get(input.workspaceRoot)
        val workspaceRoot: Path = when {
            input.workspaceRoot.isBlank() ||
                input.workspaceRoot == "." ||
                input.workspaceRoot == "./" -> workspaceRootResolver()
            configured.isAbsolute -> if (Files.isDirectory(configured)) configured else workspaceRootResolver()
            Files.isDirectory(configured) -> configured
            else -> workspaceRootResolver()
        }.also {
            require(Files.isDirectory(it)) {
                "junit.results: workspaceRoot is not a directory: ${it}"
            }
        }
        val reportPath = resolveReport(workspaceRoot, input.reportPath)

        if (!Files.exists(reportPath)) {
            throw PluginStepException(
                failure = PipelineFailure(
                    kind = FailureKind.USER,
                    message = "junit.results: report file not found at $reportPath",
                ),
            )
        }
        if (Files.isDirectory(reportPath)) {
            throw PluginStepException(
                failure = PipelineFailure(
                    kind = FailureKind.USER,
                    message = "junit.results: reportPath points at a directory, not a file: $reportPath",
                ),
            )
        }
        val sizeBytes: Long = try {
            Files.size(reportPath)
        } catch (e: IOException) {
            throw PluginStepException(
                failure = PipelineFailure(
                    kind = FailureKind.INFRASTRUCTURE,
                    message = "junit.results: failed to stat report file $reportPath: ${e.message ?: "unknown"}",
                ),
            )
        }
        if (sizeBytes == 0L) {
            throw PluginStepException(
                failure = PipelineFailure(
                    kind = FailureKind.USER,
                    message = "junit.results: report file is empty (0 bytes): $reportPath",
                ),
            )
        }
        if (sizeBytes > input.maxReportBytes) {
            throw PluginStepException(
                failure = PipelineFailure(
                    kind = FailureKind.USER,
                    message = "junit.results: report file is ${sizeBytes} bytes (maxReportBytes=${input.maxReportBytes}); refusing to parse. Raise maxReportBytes in the input if this is intentional.",
                ),
            )
        }

        val summary: JUnitReportSummary = Files.newInputStream(reportPath).use { rawStream ->
            // BufferedInputStream gives the SAX parser a chance to read
            // larger chunks efficiently without crossing our cap.
            BufferedInputStream(rawStream, 64 * 1024).use { stream ->
                try {
                    JUnitReportParser.parse(stream, reportPath.toString())
                } catch (e: JUnitReportParseException) {
                    throw PluginStepException(
                        failure = PipelineFailure(
                            kind = FailureKind.USER,
                            message = "junit.results: malformed XML at $reportPath: ${e.message ?: "unknown"}",
                        ),
                    )
                }
            }
        }

        if (input.failOnFailure && summary.failed > 0) {
            throw PluginStepException(
                failure = PipelineFailure(
                    kind = FailureKind.USER,
                    message = "junit.results: report has ${summary.failed} failed test(s) " +
                        "(${summary.failures} failures + ${summary.errors} errors out of ${summary.tests}); " +
                        "report=$reportPath",
                ),
            )
        }

        summary
    }

    private fun resolveReport(workspaceRoot: Path, rawPath: String): Path {
        val p = Paths.get(rawPath)
        return if (p.isAbsolute) p else workspaceRoot.resolve(rawPath)
    }
}
