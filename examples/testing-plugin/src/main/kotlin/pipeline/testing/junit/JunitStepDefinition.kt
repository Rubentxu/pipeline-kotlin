package pipeline.testing.junit

import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.dsl.StageScope
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import pipeline.testing.TestingContributor
import pipeline.testing.results.ParseFailureReason
import pipeline.testing.results.TestReport
import pipeline.testing.results.TestReportAdapter
import pipeline.testing.results.TestStatus
import pipeline.testing.results.TestSuiteResult
import pipeline.testing.results.JunitAdapterFactory

/**
 * LFC-2E3-T2 — `core.junit` StepDefinition.
 *
 * Reads one or more JUnit XML files via the declared filesystem capability,
 * parses each through [JunitXmlAdapter] (E3-T1), and aggregates the typed
 * [TestReport]s into a [JunitStepOutput].
 *
 * **Architectural invariant** (E3-T0, re-stated here for the production path):
 *   "tests failed"  !=  "Step execution failed"
 *
 *   - The Step returns [JunitStepOutput] carrying the aggregated typed report
 *     and a list of parse failures. The Step's OWN outcome depends on the
 *     pipeline policy layer (a future `policy.failOnTestFailures` Step or
 *     DSL construct) — NOT on `hasTestFailures`.
 *   - If the handler cannot read a file at all (NotFound / IoFailure /
 *     missing capability), it throws a typed [JunitStepException]; this is
 *     a real Step-infrastructure failure that downstream policy can route
 *     differently from "tests failed".
 *   - Per-file parse failures do NOT throw; they are recorded in
 *     [JunitStepOutput.parseFailures] so the caller can decide whether
 *     a partial parse is acceptable.
 *
 * The handler NEVER touches the file system directly: it reaches the
 * filesystem ONLY through the declared `testing.filesystem.operations`
 * capability port (LB-02 / G3-A4.2). Admission at prepare-time verifies
 * the capability is available; the handler re-checks via the typed
 * [StepHandlerContext].
 */
object JunitStepDefinition : StepDefinition<JunitStepInput, JunitStepOutput> {
    val KEY: PluginStepId = PluginStepId("core.junit")

    /**
     * LFC-2E3-P / P2: the type tag this Step publishes. A consumer binding this output must
     * declare the SAME tag; resolution fails closed on mismatch. Plugin-owned, so the piping
     * mechanism carries no knowledge of this Step.
     */
    const val OUTPUT_TYPE_TAG: String = "pipeline.testing.junit.JunitStepOutput"

    override val contract = StepContract(
        key = KEY,
        descriptor = StepDescriptor(
            stepId = KEY.value,
            name = "junit",
            configRef = "",
            pluginId = TestingContributor.COORDINATE,
            pluginVersion = TestingContributor.PLUGIN_VERSION,
            executionLocation = ExecutionLocation.CONTROLLER,
            effects = listOf(Effect.READ_ONLY),
            replayPolicy = ReplayPolicy.MEMOIZED,
        ),
        inputCodec = JunitStepInputCodec,
        outputCodec = JunitStepOutputCodec,
        requiredCapabilities = setOf(TestingContributor.TESTING_FILESYSTEM_CAPABILITY),
    )

    override val handler = StepHandler<JunitStepInput, JunitStepOutput> { input, ctx ->
        val ops: JunitFilesystemOperations = ctx.capabilities.get(
            TestingContributor.TESTING_FILESYSTEM_CAPABILITY,
        )
        val adapter: TestReportAdapter = JunitAdapterFactory.junitAdapter()

        val aggregatedSuites = mutableListOf<TestSuiteResult>()
        val parseFailures = mutableListOf<ParseFailureReason>()
        val seenFqtns = mutableSetOf<String>()
        var ambiguous: ParseFailureReason? = null

        for (path in input.reportPaths) {
            val bytes = ops.readBytes(path) // may throw JunitStepException
            val result = adapter.parse(bytes, source = path)
            when (result) {
                is TestReport.Successful -> {
                    // Cross-file identity collision check (the adapter
                    // already enforces per-file uniqueness; this guards
                    // against fqtn collisions across files).
                    for (suite in result.suites) {
                        for (case in suite.cases) {
                            val fqtn = "${suite.name}::${case.name}"
                            if (!seenFqtns.add(fqtn)) {
                                ambiguous = ParseFailureReason.AmbiguousIdentity(listOf(fqtn))
                            }
                        }
                        aggregatedSuites.add(suite)
                    }
                }
                is TestReport.Unparseable -> {
                    parseFailures.add(result.reason)
                }
            }
        }

        val report: TestReport = when {
            ambiguous != null -> TestReport.Unparseable(
                source = "core.junit.aggregate",
                reason = ambiguous,
            )
            parseFailures.isNotEmpty() && aggregatedSuites.isEmpty() -> TestReport.Unparseable(
                source = "core.junit.aggregate",
                // Use the FIRST parse failure as the headline reason;
                // the complete list is in JunitStepOutput.parseFailures.
                reason = parseFailures.first(),
            )
            else -> TestReport.Successful(suites = aggregatedSuites.toList())
        }

        JunitStepOutput(
            report = report,
            parseFailures = parseFailures.toList(),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Codecs (typed JSON; the StepCodec represents the WHOLE payload).
// ─────────────────────────────────────────────────────────────────────────────

private val inputJson = Json { encodeDefaults = true }
private val outputJson = Json { encodeDefaults = true }

object JunitStepInputCodec : StepCodec<JunitStepInput> {
    override fun encode(value: JunitStepInput): EncodedStepValue =
        EncodedStepValue(inputJson.encodeToString(JunitStepInput.serializer(), value))

    override fun decode(encoded: EncodedStepValue): JunitStepInput =
        inputJson.decodeFromString(JunitStepInput.serializer(), encoded.value)
}

/**
 * Output codec. The full [JunitStepOutput] is a tagged union
 * (`TestReport.Successful` vs `TestReport.Unparseable`); we serialise
 * it with a discriminator `kind`. This is the same pattern the
 * `PipelineOutcome` codec uses — never a `Map<String, Any>` public
 * contract.
 */
object JunitStepOutputCodec : StepCodec<JunitStepOutput> {
    override fun encode(value: JunitStepOutput): EncodedStepValue =
        EncodedStepValue(outputJson.encodeToString(JunitStepOutput.serializer(), value))

    override fun decode(encoded: EncodedStepValue): JunitStepOutput =
        outputJson.decodeFromString(JunitStepOutput.serializer(), encoded.value)
}

// ─────────────────────────────────────────────────────────────────────────────
// DSL extension (typed Kotlin facade over the generic registryStep primitive).
//
// The extension MAY construct typed Input and call the codec; it MUST NOT
// resolve the runtime registry, access capabilities, or query runtime state.
// ─────────────────────────────────────────────────────────────────────────────

fun StageScope.junit(reportPaths: List<String>) =
    registryStep(
        stepKey = JunitStepDefinition.KEY,
        encodedInput = JunitStepInputCodec.encode(JunitStepInput(reportPaths)),
    )

fun StageScope.junit(reportPath: String) = junit(listOf(reportPath))

/**
 * LFC-2E3-P / P2 — the SAME junit Step, additionally PUBLISHING its typed output under
 * [outputName] so a later Step can bind it.
 *
 * The returned reference is declarative: it names the output and states the expected type tag. The
 * parsed report is computed at run time and resolved from committed durable state through the
 * `step.output.resolver` capability — it is never fabricated during DSL construction.
 *
 * This is a plugin-owned facade, so adding a parameter is additive for the SDK; `.pipeline.kts`
 * scripts are recompiled on every run, and no frozen plugin JAR calls this function.
 */
fun StageScope.junitPublishing(reportPaths: List<String>, outputName: String) =
    registryStepPublishing(
        stepKey = JunitStepDefinition.KEY,
        encodedInput = JunitStepInputCodec.encode(JunitStepInput(reportPaths)),
        outputName = outputName,
        outputTypeTag = JunitStepDefinition.OUTPUT_TYPE_TAG,
    )

fun StageScope.junitPublishing(reportPath: String, outputName: String) =
    junitPublishing(listOf(reportPath), outputName)
