package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext
import dev.rubentxu.pipeline.v2.application.durable.ExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeFailure
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeOutcome
import dev.rubentxu.pipeline.v2.application.SystemClock
import dev.rubentxu.pipeline.v2.application.DslCompiledPipelineCompiler
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.dsl.pipeline
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import pipeline.testing.TestingContributor
import pipeline.testing.junit.DefaultJunitFilesystemOperations
import pipeline.testing.junit.JunitFilesystemOperations
import pipeline.testing.junit.JunitStepDefinition
import pipeline.testing.junit.JunitStepError
import pipeline.testing.junit.JunitStepException
import pipeline.testing.junit.JunitStepInput
import pipeline.testing.junit.JunitStepInputCodec
import pipeline.testing.junit.JunitStepOutput
import pipeline.testing.junit.JunitStepOutputCodec
import pipeline.testing.results.ParseFailureReason
import pipeline.testing.results.TestReport
import pipeline.testing.results.TestStatus
import java.nio.file.Files
import java.nio.file.Path

/**
 * StepContractSuite for the LFC-2E3-T2 `core.junit` Step.
 *
 * The Step reads JUnit XML files via the declared
 * [TestingContributor.TESTING_FILESYSTEM_CAPABILITY] capability port
 * and produces a typed [JunitStepOutput] (aggregated [TestReport] plus
 * a list of [ParseFailureReason]s).
 *
 * Architectural claim: "tests failed" is a TYPED RESULT inside the
 * output, NOT a Step outcome. The Step's outcome is decided by the
 * coordinator (which may or may not be augmented with a downstream
 * `policy.failOnTestFailures` Step); the handler itself never
 * collapses test failures into infrastructure failure.
 *
 * The suite follows the LB-02 / A5 production-like registry composition
 * pattern (capability factory + Harness wrapper) used by U5/U6/U7.
 */
@Timeout(20)
class TestingJunitStepContractSuiteTest {

    private fun fsOps(): JunitFilesystemOperations = DefaultJunitFilesystemOperations()

    private fun registry(): InMemoryStepRegistry =
        InMemoryStepRegistry().apply { ExternalStepPluginDiscovery.registerInto(this) }

    private fun testingCapabilityFactory(
        ops: JunitFilesystemOperations = DefaultJunitFilesystemOperations(),
    ): (CanonicalRuntimeContext) -> CanonicalRuntimeCapabilityAccess = { ctx ->
        object : CanonicalRuntimeCapabilityAccess(ctx) {
            private val extra: Map<StepCapability, Any> = mapOf(
                TestingContributor.TESTING_FILESYSTEM_CAPABILITY to ops,
            )
            override fun available(): Set<StepCapability> = super.available() + extra.keys
            override fun <T : Any> get(key: StepCapability): T {
                val layered = extra[key]
                if (layered != null) {
                    @Suppress("UNCHECKED_CAST")
                    return layered as T
                }
                return super.get(key)
            }
        }
    }

    private fun harness(
        eventStore: InMemoryEventStore,
        workDir: Path = Files.createTempDirectory("testing-junit-contract-"),
    ): Harness {
        val clock = SystemClock()
        val coord = CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = InMemoryOperationJournal(clock),
            cursorStore = InMemoryReplayCursorStore(clock),
            clock = clock,
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = eventStore,
            credentialScopePort = { _, _ ->
                CredentialScopeOutcome.Unavailable(
                    CredentialScopeFailure.StoreUnavailable("testing-junit-contract-suite stub"),
                )
            },
            controlDirRoot = workDir.resolve("control"),
            shOptions = ShOptions.EMPTY,
            stepRegistry = registry(),
            capabilityAccessFactory = testingCapabilityFactory(),
        )
        return Harness(coord, eventStore)
    }

    private data class Harness(
        val coord: CanonicalDurableRunCoordinator,
        val eventStore: InMemoryEventStore,
    )

    private fun testingCapabilities(): StepCapabilityAccess = object : StepCapabilityAccess {
        override fun available(): Set<StepCapability> = setOf(
            TestingContributor.TESTING_FILESYSTEM_CAPABILITY,
        )
        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> get(key: StepCapability): T {
            return when (key) {
                TestingContributor.TESTING_FILESYSTEM_CAPABILITY -> fsOps() as T
                else -> throw IllegalStateException("unexpected capability $key")
            }
        }
    }

    // ───────── identity ─────────

    @Test
    fun `identity - core-junit PluginStepId is core-junit and coordinate is pipeline-testing`() {
        assertEquals(PluginStepId("core.junit"), JunitStepDefinition.KEY)
        assertEquals("pipeline.testing", TestingContributor.COORDINATE)
    }

    @Test
    fun `identity - core-junit coexists with utilities families in the SAME registry`() {
        val r = registry()
        // testing coordinate: 1 family registered (core.junit)
        assertTrue(r.contains(PluginStepId("core.junit")))
        // utilities coordinate: representative families from each dimension
        assertTrue(r.contains(PluginStepId("utilities.readJSON")))
        assertTrue(r.contains(PluginStepId("utilities.findFiles")))
        assertTrue(r.contains(PluginStepId("utilities.sha256")))
        // example coordinate still works
        assertTrue(r.contains(PluginStepId("example.uppercase")))
        // Total registered keys: 16 utilities + 1 example.uppercase + core.junit + core.publishHTML = 19
        // (utilities: 3 readJSON/writeJSON/sha256 + 2 yaml + 2 properties + 2 filesystem + 3 checksums + 4 archive = 16)
        assertEquals(19, r.keys().size)
    }

    // ───────── contract completeness ─────────

    @Test
    fun `contract completeness - core-junit declares read-only effect and filesystem capability only`() {
        val c = JunitStepDefinition.contract
        assertEquals(setOf<Effect>(Effect.READ_ONLY), c.descriptor.effects.toSet())
        assertEquals(ReplayPolicy.MEMOIZED, c.descriptor.replayPolicy)
        assertEquals(
            setOf(TestingContributor.TESTING_FILESYSTEM_CAPABILITY),
            c.requiredCapabilities,
        )
        assertEquals(
            listOf(TestingContributor.COORDINATE),
            listOf(c.descriptor.pluginId),
        )
        assertEquals(
            listOf(TestingContributor.PLUGIN_VERSION),
            listOf(c.descriptor.pluginVersion),
        )
    }

    @Test
    fun `contract completeness - testing plugin declares exactly its two capability tokens`() {
        val src = java.io.File(
            java.io.File(System.getProperty("user.dir"), "../..").canonicalFile,
            "examples/testing-plugin/src/main/kotlin/pipeline/testing/TestingContributor.kt",
        ).readText()
        val declared = Regex("""StepCapability\(\"([^\"]+)\"""")
            .findAll(src)
            .map { it.groupValues[1] }
            .toSet()
        assertEquals(
            // T2 added the read-only parser port; R1 added the publisher port. Any further drift
            // must be a deliberate, reviewed change to this row.
            setOf("testing.filesystem.operations", "testing.publish.operations"),
            declared,
            "the testing plugin must declare exactly these two capability tokens",
        )
    }

    @Test
    fun `contract completeness - JunitStepError sealed ADT is exhaustively matchable (3 cases)`() {
        // Exercise every variant through `when` to prove exhaustiveness.
        val cases: List<JunitStepError> = listOf(
            JunitStepError.ReportNotFound("/x"),
            JunitStepError.ReportIoFailure("/y", "EACCES"),
            JunitStepError.ReportUnparseable("/z", ParseFailureReason.XmlMalformed("e")),
        )
        val mapped = cases.map { reason ->
            when (reason) {
                is JunitStepError.ReportNotFound -> "notfound:" + reason.path
                is JunitStepError.ReportIoFailure -> "io:" + reason.path
                is JunitStepError.ReportUnparseable -> "unparseable:" + reason.path
            }
        }
        assertEquals(3, mapped.size)
        assertEquals("notfound:/x", mapped[0])
        assertEquals("io:/y", mapped[1])
        assertEquals("unparseable:/z", mapped[2])
    }

    // ───────── codec round-trip ─────────

    @Test
    fun `codec - JunitStepInputCodec round-trips typed input`() {
        val input = JunitStepInput(reportPaths = listOf("/a/report.xml", "/b/report.xml"))
        val encoded = JunitStepInputCodec.encode(input)
        val decoded = JunitStepInputCodec.decode(encoded)
        assertEquals(input, decoded)
    }

    @Test
    fun `codec - JunitStepOutputCodec round-trips Successful output`() {
        val report = TestReport.Successful(
            suites = listOf(
                pipeline.testing.results.TestSuiteResult(
                    name = "S",
                    cases = listOf(
                        pipeline.testing.results.TestCaseResult(
                            suiteName = "S",
                            name = "p",
                            status = TestStatus.Passed,
                        ),
                    ),
                ),
            ),
        )
        val output = JunitStepOutput(report = report, parseFailures = emptyList())
        val encoded = JunitStepOutputCodec.encode(output)
        val decoded = JunitStepOutputCodec.decode(encoded)
        assertEquals(output, decoded)
    }

    @Test
    fun `codec - JunitStepOutputCodec round-trips Unparseable output`() {
        val output = JunitStepOutput(
            report = TestReport.Unparseable(
                source = "/missing",
                reason = ParseFailureReason.SourceMissing("/missing"),
            ),
            parseFailures = listOf(ParseFailureReason.SourceMissing("/missing")),
        )
        val encoded = JunitStepOutputCodec.encode(output)
        val decoded = JunitStepOutputCodec.decode(encoded)
        assertEquals(output, decoded)
    }

    // ───────── capability admission ─────────

    @Test
    fun `capability admission - core-junit prepares Ready when testing capability is available`() {
        val admission = RegistryExecutionPreparation.prepare(
            registry = registry(),
            key = JunitStepDefinition.KEY,
            encodedInput = JunitStepInputCodec.encode(JunitStepInput(listOf("/tmp/r.xml"))),
            availableCapabilities = setOf(TestingContributor.TESTING_FILESYSTEM_CAPABILITY),
        )
        assertInstanceOf(ExecutionPreparation.Ready::class.java, admission)
    }

    @Test
    fun `capability admission - missing testing capability REJECTS core-junit before handler runs`() {
        val admission = RegistryExecutionPreparation.prepare(
            registry = registry(),
            key = JunitStepDefinition.KEY,
            encodedInput = JunitStepInputCodec.encode(JunitStepInput(listOf("/tmp/r.xml"))),
            availableCapabilities = emptySet(),
        )
        assertInstanceOf(ExecutionPreparation.Rejected::class.java, admission)
    }

    // ───────── handler semantics ─────────

    @Test
    fun `handler - happy path produces Successful report with correct totals and zero parseFailures`() = runBlocking {
        val workDir = Files.createTempDirectory("testing-junit-happy-")
        val reportPath = workDir.resolve("ok.xml")
        Files.writeString(
            reportPath,
            """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.example.Ok" tests="3" failures="0" errors="0" time="0.5">
                  <testcase name="a" classname="com.example.Ok" time="0.1"/>
                  <testcase name="b" classname="com.example.Ok" time="0.2"/>
                  <testcase name="c" classname="com.example.Ok" time="0.2"/>
                </testsuite>
            """.trimIndent(),
        )
        val ctx = StepHandlerContext(
            runId = RunId("testing-junit-happy"),
            stepIndex = 0,
            capabilities = testingCapabilities(),
        )
        val out: JunitStepOutput = JunitStepDefinition.handler.execute(
            JunitStepInput(listOf(reportPath.toString())),
            ctx,
        )
        val report = out.report
        assertTrue(report is TestReport.Successful, "happy path must be Successful")
        assertEquals(3, report.totalCount)
        assertEquals(3, report.passedCount)
        assertEquals(0, report.failedCount)
        assertEquals(0, report.erroredCount)
        assertEquals(0, out.parseFailures.size, "no parse failures expected for well-formed input")
    }

    @Test
    fun `handler - reports with failing tests produce Successful(report) with hasTestFailures=true AND parseFailures=empty`() = runBlocking {
        val workDir = Files.createTempDirectory("testing-junit-failures-")
        val reportPath = workDir.resolve("mixed.xml")
        Files.writeString(
            reportPath,
            """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.example.Mixed" tests="3" failures="1" errors="1" time="0.5">
                  <testcase name="a" classname="com.example.Mixed" time="0.1"/>
                  <testcase name="b" classname="com.example.Mixed" time="0.2">
                    <failure message="expected 1 to equal 2" type="AssertionFailedFailure"/>
                  </testcase>
                  <testcase name="c" classname="com.example.Mixed" time="0.2">
                    <error message="timeout" type="SocketTimeoutException"/>
                  </testcase>
                </testsuite>
            """.trimIndent(),
        )
        val ctx = StepHandlerContext(
            runId = RunId("testing-junit-failures"),
            stepIndex = 0,
            capabilities = testingCapabilities(),
        )
        val out = JunitStepDefinition.handler.execute(
            JunitStepInput(listOf(reportPath.toString())),
            ctx,
        )
        // Architectural invariant: tests failed ≠ Step execution failed.
        // The Step returns Successful with hasTestFailures=true; the
        // pipeline policy decides what to do with that.
        val report = out.report
        assertTrue(report is TestReport.Successful, "report must be Successful (tests failing is a typed result, not an error)")
        assertEquals(3, report.totalCount)
        assertEquals(1, report.passedCount)
        assertEquals(1, report.failedCount)
        assertEquals(1, report.erroredCount)
        assertTrue(report.hasTestFailures, "hasTestFailures must reflect the parsed data")
        assertTrue(out.parseFailures.isEmpty(), "no parse failures: the XML was well-formed")
    }

    @Test
    fun `handler - missing report file throws JunitStepException(ReportNotFound)`() = runBlocking {
        val ctx = StepHandlerContext(
            runId = RunId("testing-junit-missing"),
            stepIndex = 0,
            capabilities = testingCapabilities(),
        )
        val missing = "/tmp/testing-junit-missing-${System.nanoTime()}.xml"
        val caught = runCatching {
            JunitStepDefinition.handler.execute(JunitStepInput(listOf(missing)), ctx)
        }.exceptionOrNull()
        assertTrue(
            caught is JunitStepException,
            "missing file must throw JunitStepException, was: ${caught?.javaClass?.name}",
        )
        val reason = (caught as JunitStepException).reason
        assertTrue(reason is JunitStepError.ReportNotFound)
    }

    @Test
    fun `handler - malformed XML surfaces as Unparseable report AND parseFailures list`() = runBlocking {
        val workDir = Files.createTempDirectory("testing-junit-malformed-")
        val reportPath = workDir.resolve("broken.xml")
        Files.writeString(reportPath, "<?xml <broken>")
        val ctx = StepHandlerContext(
            runId = RunId("testing-junit-malformed"),
            stepIndex = 0,
            capabilities = testingCapabilities(),
        )
        val out = JunitStepDefinition.handler.execute(
            JunitStepInput(listOf(reportPath.toString())),
            ctx,
        )
        assertTrue(
            out.report is TestReport.Unparseable,
            "malformed XML must surface as Unparseable (infrastructure failure)",
        )
        assertEquals(1, out.parseFailures.size)
        assertTrue(out.parseFailures.first() is ParseFailureReason.XmlMalformed)
    }

    @Test
    fun `handler - mixed inputs (one well-formed, one malformed) merge successes AND record parse failures`() = runBlocking {
        val workDir = Files.createTempDirectory("testing-junit-mixed-")
        val okPath = workDir.resolve("ok.xml")
        Files.writeString(
            okPath,
            """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.example.Ok" tests="1" time="0.1">
                  <testcase name="a" classname="com.example.Ok" time="0.1"/>
                </testsuite>
            """.trimIndent(),
        )
        val badPath = workDir.resolve("broken.xml")
        Files.writeString(badPath, "<?xml <broken>")
        val ctx = StepHandlerContext(
            runId = RunId("testing-junit-mixed"),
            stepIndex = 0,
            capabilities = testingCapabilities(),
        )
        val out = JunitStepDefinition.handler.execute(
            JunitStepInput(listOf(okPath.toString(), badPath.toString())),
            ctx,
        )
        // Partial success is surfaced faithfully: the well-formed file
        // produces data, the malformed one is recorded as a parse failure.
        val report = out.report
        assertTrue(report is TestReport.Successful, "partial parse success must be Successful")
        assertEquals(1, report.totalCount, "only the well-formed file's tests are reported")
        assertEquals(1, out.parseFailures.size, "the malformed file's failure is recorded")
        assertTrue(out.parseFailures.first() is ParseFailureReason.XmlMalformed)
    }

    @Test
    fun `handler - all inputs malformed produces Unparseable report AND full parseFailures list`() = runBlocking {
        val workDir = Files.createTempDirectory("testing-junit-all-malformed-")
        val badPath1 = workDir.resolve("b1.xml")
        val badPath2 = workDir.resolve("b2.xml")
        Files.writeString(badPath1, "<?xml <broken1>")
        Files.writeString(badPath2, "<?xml <broken2>")
        val ctx = StepHandlerContext(
            runId = RunId("testing-junit-all-malformed"),
            stepIndex = 0,
            capabilities = testingCapabilities(),
        )
        val out = JunitStepDefinition.handler.execute(
            JunitStepInput(listOf(badPath1.toString(), badPath2.toString())),
            ctx,
        )
        assertTrue(
            out.report is TestReport.Unparseable,
            "all-malformed must surface as Unparseable (no fabricated successes)",
        )
        assertEquals(2, out.parseFailures.size, "all parse failures recorded")
        assertEquals(0, out.report.totalCount, "Unparseable carries no fabricated tests")
    }

    // ───────── canonical envelope / registry resolution ─────────

    @Test
    fun `registry resolution - core-junit resolves through StepDefinitionContributor SPI`() {
        val r = registry()
        val def = r.definition(JunitStepDefinition.KEY)
        assertEquals(JunitStepDefinition, def, "registry must return the same definition instance")
    }

    @Test
    fun `canonical envelope - encoded Input is a JSON-serialised blob (not Map)`() {
        val input = JunitStepInput(listOf("/a.xml", "/b.xml"))
        val encoded = JunitStepInputCodec.encode(input)
        // The envelope is a single string field — not a Map<String, Any>.
        assertTrue(encoded.value.startsWith("{") && encoded.value.endsWith("}"))
        assertTrue(encoded.value.contains("\"reportPaths\""), "JSON shape preserved")
    }

    // ───────── observability ─────────

    @Test
    fun `observability - StepDescriptor declares pluginId=plugin-version consistent with contributor`() {
        val d = JunitStepDefinition.contract.descriptor
        assertEquals(TestingContributor.COORDINATE, d.pluginId)
        assertEquals(TestingContributor.PLUGIN_VERSION, d.pluginVersion)
    }

    // ───────── real DSL scenario ─────────

    @Test
    fun `real DSL scenario - core-junit round-trip runs end-to-end through the canonical spine`() = runBlocking {
        val workDir = Files.createTempDirectory("testing-junit-dsl-")
        val reportPath = workDir.resolve("ok.xml")
        Files.writeString(
            reportPath,
            """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.example.DSL" tests="1" time="0.05">
                  <testcase name="ok" classname="com.example.DSL" time="0.05"/>
                </testsuite>
            """.trimIndent(),
        )
        val spec: PipelineSpec = pipeline {
            stages {
                stage("JunitE2E") {
                    registryStep(
                        stepKey = JunitStepDefinition.KEY,
                        encodedInput = JunitStepInputCodec.encode(JunitStepInput(listOf(reportPath.toString()))),
                    )
                }
            }
        }
        val compiled = DslCompiledPipelineCompiler.compile(
            spec = spec,
            sourcePath = "junit-roundtrip.pipeline.kts",
            sourceContent = spec.toString(),
            pluginLockDigest = dev.rubentxu.pipeline.v2.domain.Digest("lock"),
        )
        val eventStore = InMemoryEventStore()
        val h = harness(eventStore, workDir)
        val run = h.coord.run(compiled, RunId("dsl-junit-roundtrip"))
        assertEquals(RunOutcome.Success, run)
    }
}
